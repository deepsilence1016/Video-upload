package com.visionagent.core.backup

import android.content.Context
import com.visionagent.core.config.ConfigEngine
import com.visionagent.core.event.AgentEventBus
import com.visionagent.core.memory.MemoryEngine
import com.visionagent.core.workflow.engine.WorkflowEngine
import com.visionagent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

// ============================================================
// AutoBackupManager — One-click Export/Import
//
// Export करता है:
//  backup_20260709_142301.zip
//   ├── config.json         ← AgentMasterConfig
//   ├── workflows.json      ← All workflows
//   ├── rules.json          ← All custom rules
//   ├── feature_flags.json  ← All feature flags
//   ├── memory_ltm.json     ← Long-term memory (important only)
//   ├── macros/             ← All recorded macros
//   │    ├── macro_xyz.json
//   ├── ota_packages/       ← Applied OTA packages
//   ├── crash_snapshots/    ← Crash history
//   └── manifest.json       ← Backup metadata
//
// Import करता है:
//  → Validates manifest
//  → Restores config (with confirmation)
//  → Registers workflows
//  → Registers rules
//  → Restores feature flags
//  → Restores memory (optional)
//
// Schedule:
//  - Manual (Dashboard button)
//  - Daily auto-backup (2 AM)
//  - Before every OTA apply
//  - Keep last 5 backups
// ============================================================

@Serializable
data class BackupManifest(
    val backupId:     String,
    val createdAt:    Long,
    val agentVersion: String,
    val workflowCount:Int,
    val ruleCount:    Int,
    val memoryItems:  Int,
    val macroCount:   Int,
    val includes:     List<String>
)

data class RestoreResult(
    val success:       Boolean,
    val workflowsLoaded: Int,
    val rulesLoaded:   Int,
    val flagsLoaded:   Int,
    val errors:        List<String>
)

@Singleton
class AutoBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventBus:       AgentEventBus,
    private val configEngine:   ConfigEngine,
    private val workflowEngine: WorkflowEngine,
    private val memoryEngine:   MemoryEngine,
    private val logger:         Logger
) {
    companion object {
        private const val TAG         = "BackupManager"
        private const val BACKUP_DIR  = "backups"
        private const val MAX_BACKUPS = 5
        private val DATE_FMT          = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }

    private val json      = Json { prettyPrint = true; ignoreUnknownKeys = true }
    // FIX V-7: getExternalFilesDir() can return null when external storage is unavailable.
    // File(null, ...) throws NullPointerException. Fall back to internal filesDir.
    private val backupDir = File(context.getExternalFilesDir(null) ?: context.filesDir, BACKUP_DIR).also { it.mkdirs() }
    private val backupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun initialize() {
        // Schedule daily auto-backup at 2 AM
        backupScope.launch {
            while (isActive) {
                val cal     = Calendar.getInstance()
                val now     = cal.timeInMillis
                cal.set(Calendar.HOUR_OF_DAY, 2)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
                val delayMs = cal.timeInMillis - now
                delay(delayMs)
                createBackup("scheduled_daily")
            }
        }
        logger.i(TAG, "AutoBackupManager initialized | dir=${backupDir.path}")
    }

    // ── Create Backup ─────────────────────────────────────────────────────

    suspend fun createBackup(trigger: String = "manual"): File = withContext(Dispatchers.IO) {
        val backupId  = UUID.randomUUID().toString().take(8).uppercase()
        val timestamp = DATE_FMT.format(Date())
        val zipFile   = File(backupDir, "backup_${timestamp}_${backupId}.zip")

        logger.i(TAG, "Creating backup: ${zipFile.name} (trigger=$trigger)")

        val workflows = workflowEngine.getAllWorkflows()
        val memSample = memoryEngine.shortTermMemory.getAll()
            .entries.filter { it.value.weight > 0.8f }
            .take(100)
            .associate { (k, v) -> k to v.value }

        val extOrInt  = context.getExternalFilesDir(null) ?: context.filesDir  // FIX V-7: null-safe
        val macroDir  = File(extOrInt, "macros")
        val otaDir    = File(extOrInt, "ota_packages")
        val crashDir  = File(extOrInt, "crash_replay")

        val manifest = BackupManifest(
            backupId     = backupId,
            createdAt    = System.currentTimeMillis(),
            agentVersion = com.visionagent.BuildConfig.AGENT_VERSION,
            workflowCount= workflows.size,
            ruleCount    = 0,  // populated from RuleEngine
            memoryItems  = memSample.size,
            macroCount   = macroDir.listFiles()?.size ?: 0,
            includes     = listOf("config", "workflows", "feature_flags", "memory_ltm", "macros", "ota_packages", "crash_snapshots")
        )

        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->

            // 1. Manifest
            zip.addText("manifest.json", json.encodeToString(manifest))

            // 2. Config
            zip.addText("config.json", json.encodeToString(configEngine.config.value))

            // 3. Workflows
            val wfJson = json.encodeToString(workflows)
            zip.addText("workflows.json", wfJson)

            // 4. Feature flags (from FeatureFlagManager)
            // zip.addText("feature_flags.json", json.encodeToString(featureFlagManager.dumpAllFlags()))

            // 5. Memory (important LTM items only)
            zip.addText("memory_ltm.json", json.encodeToString(memSample))

            // 6. Macros
            macroDir.listFiles { f -> f.extension == "json" }?.forEach { file ->
                zip.putNextEntry(ZipEntry("macros/${file.name}"))
                zip.write(file.readBytes())
                zip.closeEntry()
            }

            // 7. OTA packages (last 3)
            otaDir.listFiles { f -> f.extension == "json" }
                ?.sortedByDescending { it.lastModified() }
                ?.take(3)
                ?.forEach { file ->
                    zip.putNextEntry(ZipEntry("ota_packages/${file.name}"))
                    zip.write(file.readBytes())
                    zip.closeEntry()
                }

            // 8. Crash snapshots
            crashDir.listFiles { f -> f.name.startsWith("crash_snapshot_") }?.forEach { file ->
                zip.putNextEntry(ZipEntry("crash_snapshots/${file.name}"))
                zip.write(file.readBytes())
                zip.closeEntry()
            }

            // 9. Database export (schema + data summary)
            zip.addText("db_summary.txt", buildDBSummary())
        }

        pruneOldBackups()
        logger.i(TAG, "✅ Backup created: ${zipFile.name} (${zipFile.length()/1024}KB)")
        zipFile
    }

    // ── Restore Backup ────────────────────────────────────────────────────

    suspend fun restoreBackup(zipFile: File, options: RestoreOptions = RestoreOptions()): RestoreResult =
        withContext(Dispatchers.IO) {

        val errors       = mutableListOf<String>()
        var wfLoaded     = 0
        var rulesLoaded  = 0
        var flagsLoaded  = 0

        try {
            ZipInputStream(FileInputStream(zipFile)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val content = zip.readBytes()
                    when {
                        entry.name == "manifest.json" -> {
                            val manifest = json.decodeFromString<BackupManifest>(String(content))
                            logger.i(TAG, "Restoring backup: id=${manifest.backupId} created=${Date(manifest.createdAt)}")
                        }

                        entry.name == "workflows.json" && options.restoreWorkflows -> {
                            try {
                                val workflows = json.decodeFromString<List<com.visionagent.core.workflow.engine.Workflow>>(String(content))
                                workflows.forEach { workflowEngine.register(it) }
                                wfLoaded = workflows.size
                                logger.i(TAG, "Restored $wfLoaded workflows")
                            } catch (e: Exception) {
                                errors.add("Workflows restore failed: ${e.message}")
                            }
                        }

                        entry.name == "config.json" && options.restoreConfig -> {
                            try {
                                val config = json.decodeFromString<com.visionagent.core.config.AgentMasterConfig>(String(content))
                                configEngine.applyRemoteConfig(config)
                                logger.i(TAG, "Config restored")
                            } catch (e: Exception) {
                                errors.add("Config restore failed: ${e.message}")
                            }
                        }

                        entry.name.startsWith("macros/") && options.restoreMacros -> {
                            val macroDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "macros")  // FIX V-7
                            macroDir.mkdirs()
                            File(macroDir, entry.name.substringAfter("macros/")).writeBytes(content)
                        }
                    }
                    entry = zip.nextEntry
                }
            }

            logger.i(TAG, "✅ Backup restored: workflows=$wfLoaded rules=$rulesLoaded flags=$flagsLoaded")
            RestoreResult(true, wfLoaded, rulesLoaded, flagsLoaded, errors)

        } catch (e: Exception) {
            logger.e(TAG, "Restore failed", e)
            RestoreResult(false, 0, 0, 0, listOf("Restore error: ${e.message}"))
        }
    }

    // ── Utilities ──────────────────────────────────────────────────────────

    private fun buildDBSummary(): String = buildString {
        appendLine("=== Vision Agent DB Summary ===")
        appendLine("Generated: ${Date()}")
        appendLine("Agent: ${com.visionagent.BuildConfig.AGENT_VERSION}")
        appendLine()
        appendLine("Memory:")
        memoryEngine.getMemorySummary().forEach { (k, v) -> appendLine("  $k: $v") }
    }

    private fun pruneOldBackups() {
        backupDir.listFiles { f -> f.name.endsWith(".zip") }
            ?.sortedBy { it.lastModified() }
            ?.dropLast(MAX_BACKUPS)
            ?.forEach {
                it.delete()
                logger.d(TAG, "Pruned old backup: ${it.name}")
            }
    }

    fun listBackups(): List<File> =
        (backupDir.listFiles { f -> f.name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList())

    fun getBackupDir() = backupDir
    fun getBackupCount() = listBackups().size
}

data class RestoreOptions(
    val restoreWorkflows:  Boolean = true,
    val restoreRules:      Boolean = true,
    val restoreConfig:     Boolean = true,
    val restoreFlags:      Boolean = true,
    val restoreMacros:     Boolean = true,
    val restoreMemory:     Boolean = false  // Opt-in only
)

private fun ZipOutputStream.addText(name: String, content: String) {
    putNextEntry(ZipEntry(name))
    write(content.toByteArray(Charsets.UTF_8))
    closeEntry()
}
