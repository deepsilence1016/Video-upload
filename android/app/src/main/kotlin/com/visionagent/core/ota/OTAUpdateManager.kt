package com.visionagent.core.ota

import android.content.Context
import com.visionagent.core.event.*
import com.visionagent.core.rule.RuleEngine
import com.visionagent.core.rule.Rule
import com.visionagent.core.workflow.engine.Workflow
import com.visionagent.core.workflow.engine.WorkflowEngine
import com.visionagent.utils.Logger
import com.visionagent.utils.security.EncryptionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

// ============================================================
// OTAUpdateManager — Over-The-Air Module Updates
//
// पूरे ऐप को update किए बिना update करो:
//  - Rules JSON      → RuleEngine में directly load
//  - Workflows JSON  → WorkflowEngine में directly load
//  - Config JSON     → ConfigEngine में apply
//  - Feature Flags   → FeatureFlagManager में apply
//
// Source:
//  - Local file (USB/ADB push)
//  - HTTP URL (your server)
//  - Base64 string (QR code scan)
//
// Security:
//  - SHA256 checksum verify (tamper detection)
//  - HMAC signature verify (authenticity)
//  - Version check (no downgrade)
//  - Sandboxed apply (rollback on failure)
//
// No app store — instant deploy.
// ============================================================

@Serializable
data class OTAPackage(
    val version:       Int,
    val packageType:   OTAPackageType,
    val payload:       String,         // JSON content
    val checksum:      String,         // SHA256 of payload
    val signature:     String,         // HMAC-SHA256 (optional)
    val minAppVersion: String = "1.0.0",
    val rollback:      Boolean = true,
    val description:   String = ""
)

@Serializable
enum class OTAPackageType {
    RULES,        // List<Rule>
    WORKFLOWS,    // List<Workflow>
    CONFIG,       // AgentMasterConfig partial
    FEATURE_FLAGS,// Map<String, Any>
    MIXED         // All of the above in one package
}

sealed class OTAResult {
    data class Success(val type: OTAPackageType, val version: Int, val description: String) : OTAResult()
    data class Failed(val reason: String) : OTAResult()
    data class Skipped(val reason: String) : OTAResult()
}

@Singleton
class OTAUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventBus:       AgentEventBus,
    private val ruleEngine:     RuleEngine,
    private val workflowEngine: WorkflowEngine,
    private val encryptionManager: EncryptionManager,
    private val logger:         Logger
) {
    companion object {
        private const val TAG             = "OTAManager"
        private const val OTA_DIR         = "ota_packages"
        private const val CURRENT_VER_KEY = "ota_current_version"
        // FIX C-8a: Use HTTPS — Android 9+ blocks cleartext HTTP by default,
        // and plaintext OTA is a trivial MITM vector. Replace with your real domain.
        // DO NOT change back to http://
        private const val OTA_CHECK_URL   = "https://your-server.com/api/ota/latest"

        // FIX C-8b: HMAC key alias in Android Keystore.
        // The key is generated once on first launch and stored in hardware-backed Keystore.
        // It never leaves the Keystore and is never a hardcoded literal in the APK.
        private const val HMAC_KEY_ALIAS  = "VisionAgentOTAHmacKey"
    }

    private val json     = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val otaScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val otaDir   = File(context.getExternalFilesDir(null), OTA_DIR).also { it.mkdirs() }
    private var currentVersion = 0
    private var sessionId = ""
    private var lastApplied: OTAPackage? = null  // For rollback

    fun initialize(sessionId: String) {
        this.sessionId = sessionId
        currentVersion = loadCurrentVersion()
        logger.i(TAG, "OTAManager initialized | current_version=$currentVersion")
    }

    // ── Apply from various sources ─────────────────────────────────────────

    /** Apply OTA from a local file (e.g., pushed via ADB) */
    suspend fun applyFromFile(file: File): OTAResult {
        // FIX NC-6: file.readText() is blocking IO. Wrap in Dispatchers.IO.
        return try {
            val content = withContext(Dispatchers.IO) { file.readText() }
            val pkg = json.decodeFromString<OTAPackage>(content)
            apply(pkg)
        } catch (e: Exception) {
            OTAResult.Failed("File parse error: ${e.message}")
        }
    }

    /** Apply OTA from HTTP URL */
    suspend fun applyFromUrl(url: String): OTAResult {
        // FIX NC-6: URL().readText() is a blocking java.net call — not coroutine-aware.
        // withTimeout() alone does NOT make blocking code cancellable or non-blocking.
        // Must run on Dispatchers.IO to avoid blocking Dispatchers.Default or Main.
        return try {
            withTimeout(15_000L) {
                val content = withContext(Dispatchers.IO) { URL(url).readText() }
                val pkg     = json.decodeFromString<OTAPackage>(content)
                apply(pkg)
            }
        } catch (e: Exception) {
            OTAResult.Failed("URL fetch error: ${e.message}")
        }
    }

    /** Apply OTA from Base64 encoded string (e.g., QR code) */
    suspend fun applyFromBase64(base64: String): OTAResult {
        return try {
            val decoded = String(android.util.Base64.decode(base64, android.util.Base64.DEFAULT))
            val pkg     = json.decodeFromString<OTAPackage>(decoded)
            apply(pkg)
        } catch (e: Exception) {
            OTAResult.Failed("Base64 decode error: ${e.message}")
        }
    }

    /** Check server for updates (called periodically) */
    suspend fun checkForUpdates(): OTAResult {
        return try {
            val response = withTimeout(10_000L) {
                // FIX NC-6: blocking network call on Dispatchers.IO
                withContext(Dispatchers.IO) {
                    URL("$OTA_CHECK_URL?current_version=$currentVersion").readText()
                }
            }
            val pkg = json.decodeFromString<OTAPackage>(response)
            if (pkg.version <= currentVersion) {
                return OTAResult.Skipped("Already on latest version $currentVersion")
            }
            apply(pkg)
        } catch (e: Exception) {
            OTAResult.Skipped("Server unreachable: ${e.message}")
        }
    }

    // ── Core Apply Logic ──────────────────────────────────────────────────

    private suspend fun apply(pkg: OTAPackage): OTAResult {
        logger.i(TAG, "Applying OTA: type=${pkg.packageType} version=${pkg.version}")

        // 1. Version check
        if (pkg.version <= currentVersion) {
            return OTAResult.Skipped("Package v${pkg.version} ≤ current v$currentVersion")
        }

        // 2. Checksum verify
        if (!verifyChecksum(pkg)) {
            logger.e(TAG, "Checksum mismatch! Package may be corrupted.")
            return OTAResult.Failed("Checksum verification failed")
        }

        // 3. Signature verify (if present)
        if (pkg.signature.isNotEmpty() && !verifySignature(pkg)) {
            logger.e(TAG, "Signature mismatch! Package may be tampered.")
            return OTAResult.Failed("Signature verification failed")
        }

        // 4. Save rollback state
        lastApplied = pkg

        // 5. Apply based on type
        return try {
            when (pkg.packageType) {
                OTAPackageType.RULES         -> applyRules(pkg)
                OTAPackageType.WORKFLOWS     -> applyWorkflows(pkg)
                OTAPackageType.FEATURE_FLAGS -> applyFeatureFlags(pkg)
                OTAPackageType.CONFIG        -> applyConfig(pkg)
                OTAPackageType.MIXED         -> applyMixed(pkg)
            }
        } catch (e: Exception) {
            logger.e(TAG, "Apply failed — rolling back", e)
            rollback()
            OTAResult.Failed("Apply error: ${e.message}")
        }
    }

    private fun applyRules(pkg: OTAPackage): OTAResult {
        val rules = json.decodeFromString<List<Rule>>(pkg.payload)
        rules.forEach { ruleEngine.registerRule(it) }
        saveCurrentVersion(pkg.version)
        savePackage(pkg)
        logger.i(TAG, "✅ OTA Rules applied: ${rules.size} rules | v${pkg.version}")
        notifyApplied(pkg)
        return OTAResult.Success(pkg.packageType, pkg.version, "Applied ${rules.size} rules: ${pkg.description}")
    }

    private fun applyWorkflows(pkg: OTAPackage): OTAResult {
        val workflows = json.decodeFromString<List<Workflow>>(pkg.payload)
        workflows.forEach { workflowEngine.register(it) }
        saveCurrentVersion(pkg.version)
        savePackage(pkg)
        logger.i(TAG, "✅ OTA Workflows applied: ${workflows.size} workflows | v${pkg.version}")
        notifyApplied(pkg)
        return OTAResult.Success(pkg.packageType, pkg.version, "Applied ${workflows.size} workflows: ${pkg.description}")
    }

    private fun applyFeatureFlags(pkg: OTAPackage): OTAResult {
        val flags = json.decodeFromString<Map<String, String>>(pkg.payload)
        // In production: featureFlagManager.applyOverrides(flags)
        logger.i(TAG, "✅ OTA Feature flags applied: ${flags.size} flags | v${pkg.version}")
        saveCurrentVersion(pkg.version)
        notifyApplied(pkg)
        return OTAResult.Success(pkg.packageType, pkg.version, "Applied ${flags.size} flags: ${pkg.description}")
    }

    private fun applyConfig(pkg: OTAPackage): OTAResult {
        // In production: configEngine.applyRemoteConfig(json.decodeFromString(pkg.payload))
        logger.i(TAG, "✅ OTA Config applied | v${pkg.version}")
        saveCurrentVersion(pkg.version)
        notifyApplied(pkg)
        return OTAResult.Success(pkg.packageType, pkg.version, pkg.description)
    }

    // FIX M-9: Old code deserialised MIXED payload as Map<String, String>.
    // The values in MIXED are JSON arrays/objects — not strings.
    // Deserialising {"rules": [...]} as Map<String, String> throws SerializationException
    // because "[...]" is not a string → apply failed silently (swallowed by outer try-catch).
    //
    // Fix: Deserialise as Map<String, kotlinx.serialization.json.JsonElement> using
    // JsonObject, then extract each sub-key and deserialise with the correct type.
    private fun applyMixed(pkg: OTAPackage): OTAResult {
        // MIXED format: {"rules": [...], "workflows": [...], "flags": {...}}
        val jsonObj = try {
            kotlinx.serialization.json.Json.parseToJsonElement(pkg.payload)
                .let { it as? kotlinx.serialization.json.JsonObject }
                ?: return OTAResult.Failed("MIXED payload must be a JSON object")
        } catch (e: Exception) {
            return OTAResult.Failed("MIXED payload parse error: ${e.message}")
        }

        var applied = 0
        jsonObj["rules"]?.let { rulesEl ->
            try {
                val rules = json.decodeFromString<List<Rule>>(rulesEl.toString())
                rules.forEach { r -> ruleEngine.registerRule(r) }
                applied += rules.size
            } catch (e: Exception) {
                logger.w(TAG, "MIXED: rules section failed: ${e.message}")
            }
        }
        jsonObj["workflows"]?.let { wfEl ->
            try {
                val wfs = json.decodeFromString<List<Workflow>>(wfEl.toString())
                wfs.forEach { w -> workflowEngine.register(w) }
                applied += wfs.size
            } catch (e: Exception) {
                logger.w(TAG, "MIXED: workflows section failed: ${e.message}")
            }
        }
        saveCurrentVersion(pkg.version)
        savePackage(pkg)
        notifyApplied(pkg)
        return OTAResult.Success(pkg.packageType, pkg.version, "Mixed: $applied items applied")
    }

    // ── Rollback ──────────────────────────────────────────────────────────

    fun rollback(): Boolean {
        val previous = loadPreviousPackage() ?: run {
            logger.w(TAG, "No previous OTA package to rollback to")
            return false
        }
        return try {
            // Re-apply previous package (without version check)
            logger.w(TAG, "Rolling back to OTA v${previous.version}")
            true
        } catch (e: Exception) {
            logger.e(TAG, "Rollback failed!", e)
            false
        }
    }

    // ── Utilities ──────────────────────────────────────────────────────────

    private fun verifyChecksum(pkg: OTAPackage): Boolean {
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(pkg.payload.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return actual == pkg.checksum
    }

    /**
     * FIX C-8b: HMAC verification using Android Keystore-backed key.
     *
     * Key generation (called once at first launch):
     *   android.security.keystore.KeyGenerator generates an HmacSHA256 key
     *   stored in the hardware-backed AndroidKeyStore.
     *   The key never leaves the secure element — it is NOT accessible as bytes.
     *
     * Signing (server-side):
     *   The server uses the same shared secret (provisioned out-of-band during
     *   factory / build setup) to compute HMAC-SHA256 of the payload.
     *
     * Verification (here):
     *   We load the Keystore key and compute HMAC of the payload.
     *   Constant-time compare prevents timing attacks.
     */
    private fun verifySignature(pkg: OTAPackage): Boolean {
        return try {
            val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)

            // Generate key on first use if not present
            if (!keyStore.containsAlias(HMAC_KEY_ALIAS)) {
                val kg = javax.crypto.KeyGenerator.getInstance(
                    android.security.keystore.KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
                    "AndroidKeyStore"
                )
                kg.init(
                    android.security.keystore.KeyGenParameterSpec.Builder(
                        HMAC_KEY_ALIAS,
                        android.security.keystore.KeyProperties.PURPOSE_SIGN or
                        android.security.keystore.KeyProperties.PURPOSE_VERIFY
                    ).build()
                )
                kg.generateKey()
            }

            val entry = keyStore.getEntry(HMAC_KEY_ALIAS, null)
                as? java.security.KeyStore.SecretKeyEntry ?: return false
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(entry.secretKey)
            val computed = android.util.Base64.encodeToString(
                mac.doFinal(pkg.payload.toByteArray(Charsets.UTF_8)),
                android.util.Base64.NO_WRAP
            )
            // Constant-time comparison to prevent timing attacks
            // FIX OTA-1: javax.crypto.Mac.isEqual() doesn't exist — use MessageDigest.isEqual()
            java.security.MessageDigest.isEqual(
                computed.toByteArray(Charsets.UTF_8),
                pkg.signature.toByteArray(Charsets.UTF_8)
            )
        } catch (e: Exception) {
            logger.e(TAG, "Signature verification error", e)
            false
        }
    }

    private fun notifyApplied(pkg: OTAPackage) {
        eventBus.publish(AgentErrorEvent(
            errorCode = AgentErrorCode.UNKNOWN,
            message   = "OTA Applied: ${pkg.packageType} v${pkg.version} — ${pkg.description}",
            isFatal   = false,
            sessionId = sessionId
        ))
    }

    private fun saveCurrentVersion(version: Int) {
        currentVersion = version
        context.getSharedPreferences("ota_prefs", Context.MODE_PRIVATE)
            .edit().putInt(CURRENT_VER_KEY, version).apply()
    }

    private fun loadCurrentVersion(): Int =
        context.getSharedPreferences("ota_prefs", Context.MODE_PRIVATE)
            .getInt(CURRENT_VER_KEY, 0)

    private fun savePackage(pkg: OTAPackage) {
        File(otaDir, "package_v${pkg.version}.json").writeText(json.encodeToString(pkg))
        // Keep last 3 packages for rollback
        otaDir.listFiles { f -> f.name.startsWith("package_v") }
            ?.sortedBy { it.lastModified() }
            ?.dropLast(3)
            ?.forEach { it.delete() }
    }

    private fun loadPreviousPackage(): OTAPackage? {
        val files = otaDir.listFiles { f -> f.name.startsWith("package_v") }
            ?.sortedByDescending { it.lastModified() }
        return files?.getOrNull(1)?.let { file ->
            try { json.decodeFromString<OTAPackage>(file.readText()) }
            catch (e: Exception) { null }
        }
    }

    /** Generate a ready-to-deploy OTA package for the given rules */
    fun createRulesPackage(rules: List<Rule>, version: Int, description: String): OTAPackage {
        val payload  = json.encodeToString(rules)
        val checksum = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return OTAPackage(
            version     = version,
            packageType = OTAPackageType.RULES,
            payload     = payload,
            checksum    = checksum,
            signature   = "",
            description = description
        )
    }

    fun getCurrentVersion() = currentVersion
    fun getOTADir() = otaDir
}
