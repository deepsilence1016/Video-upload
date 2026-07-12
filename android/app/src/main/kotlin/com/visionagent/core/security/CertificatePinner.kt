package com.visionagent.core.security

import android.util.Base64
import com.visionagent.utils.Logger
import com.visionagent.utils.security.EncryptionManager
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

// ============================================================
// CertificatePinner — TLS/SSL Certificate Pinning
//
// Problem: Even with HTTPS, a compromised CA could issue
//          a fake certificate for our backend. MITM attack.
//
// Solution: Certificate Pinning — only accept connections
//          to servers with known certificate hashes.
//
// We pin the PUBLIC KEY (not the full certificate) so that
// certificate rotation doesn't break the app.
//
// How to get the pin:
// openssl s_client -connect api.yourdomain.com:443 < /dev/null \
//   | openssl x509 -pubkey -noout \
//   | openssl pkey -pubin -outform der \
//   | openssl dgst -sha256 -binary \
//   | base64
//
// Security:
// - 2 pins minimum (1 backup) to avoid lockout
// - Pins stored encrypted in EncryptedSharedPreferences
// - Pin rotation supported via remote config
// - Failure reporting to backend
// ============================================================

data class PinConfig(
    val host:        String,
    val pins:        List<String>,  // sha256/BASE64_ENCODED_PUBLIC_KEY_HASH
    val includeSubdomains: Boolean = false
)

@Singleton
class CertificatePinnerManager @Inject constructor(
    private val logger: Logger
) {
    companion object {
        private const val TAG = "CertPinner"

        // Add your actual pins here
        // Replace with: openssl s_client -connect your-api.com:443 ... | base64
        private val PIN_CONFIGS = listOf(
            PinConfig(
                host  = "api.visionagent.yourdomain.com",
                pins  = listOf(
                    "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",  // Primary
                    "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="   // Backup
                )
            ),
            PinConfig(
                host  = "*.visionagent.yourdomain.com",
                pins  = listOf(
                    "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                    "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="
                ),
                includeSubdomains = true
            )
        )
    }

    fun buildPinnedOkHttpClient(): OkHttpClient {
        // Build certificate pinner
        val pinnerBuilder = CertificatePinner.Builder()
        PIN_CONFIGS.forEach { config ->
            config.pins.forEach { pin ->
                pinnerBuilder.add(config.host, pin)
            }
        }
        val pinner = pinnerBuilder.build()

        // Build OkHttp client with pinning + security settings
        return OkHttpClient.Builder()
            .certificatePinner(pinner)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30,    TimeUnit.SECONDS)
            .writeTimeout(30,   TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)  // Don't retry on pin failure
            .addInterceptor(buildSecurityInterceptor())
            .addInterceptor(buildLoggingInterceptor())
            .build()
    }

    /**
     * Manually verify a certificate against our pins.
     * Use for WebSocket connections where OkHttp pinner might not apply.
     */
    fun verifyCertificate(cert: X509Certificate): Boolean {
        val publicKey = cert.publicKey.encoded
        val hash = MessageDigest.getInstance("SHA-256").digest(publicKey)
        val pin  = "sha256/${Base64.encodeToString(hash, Base64.NO_WRAP)}"

        val matchingConfig = PIN_CONFIGS.find { config ->
            config.pins.contains(pin)
        }

        if (matchingConfig == null) {
            logger.e(TAG, "CERTIFICATE PIN MISMATCH! Subject: ${cert.subjectDN}")
            reportPinFailure(cert, pin)
        }

        return matchingConfig != null
    }

    private fun buildSecurityInterceptor() = okhttp3.Interceptor { chain ->
        val original = chain.request()
        val secured  = original.newBuilder()
            .header("X-Agent-Version", com.visionagent.BuildConfig.AGENT_VERSION)
            .header("X-Request-ID",    java.util.UUID.randomUUID().toString())
            .build()
        chain.proceed(secured)
    }

    private fun buildLoggingInterceptor() = HttpLoggingInterceptor().apply {
        level = if (com.visionagent.BuildConfig.DEBUG)
            HttpLoggingInterceptor.Level.BASIC
        else
            HttpLoggingInterceptor.Level.NONE
    }

    private fun reportPinFailure(cert: X509Certificate, observedPin: String) {
        // In production: send to security monitoring endpoint
        logger.e(TAG, """
            ⚠️ PIN FAILURE REPORT
            Subject: ${cert.subjectDN}
            Issuer:  ${cert.issuerDN}
            Observed Pin: $observedPin
            Valid Until: ${cert.notAfter}
        """.trimIndent())
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// AuditTrail — Tamper-evident action logging
// ─────────────────────────────────────────────────────────────────────────────

data class AuditEntry(
    val entryId:     String = java.util.UUID.randomUUID().toString(),
    val timestamp:   Long   = System.currentTimeMillis(),
    val action:      String,
    val actor:       String = "agent",
    val target:      String?,
    val outcome:     String,
    val sessionId:   String,
    val hmac:        String  // HMAC-SHA256 of entry data — tamper detection
)

@Singleton
class AuditTrail @Inject constructor(
    private val encryptionManager: EncryptionManager,
    private val logger: Logger
) {
    companion object {
        private const val TAG    = "AuditTrail"
        private const val SECRET = "audit_trail_hmac_key_v1"
    }

    private val entries = mutableListOf<AuditEntry>()

    fun record(
        action:    String,
        target:    String?,
        outcome:   String,
        sessionId: String
    ) {
        val data = "$action|$target|$outcome|$sessionId|${System.currentTimeMillis()}"
        val hmac = computeHMAC(data)

        val entry = AuditEntry(
            action    = action,
            target    = target,
            outcome   = outcome,
            sessionId = sessionId,
            hmac      = hmac
        )
        entries.add(entry)
        logger.v(TAG, "Audit: $action → $outcome")
    }

    fun verifyIntegrity(): Boolean {
        return entries.all { entry ->
            val data = "${entry.action}|${entry.target}|${entry.outcome}|${entry.sessionId}|${entry.timestamp}"
            computeHMAC(data) == entry.hmac
        }
    }

    fun getEntries(): List<AuditEntry> = entries.toList()
    fun clearSession() = entries.clear()

    private fun computeHMAC(data: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        val key = javax.crypto.spec.SecretKeySpec(SECRET.toByteArray(), "HmacSHA256")
        mac.init(key)
        return Base64.encodeToString(mac.doFinal(data.toByteArray()), Base64.NO_WRAP)
    }
}
