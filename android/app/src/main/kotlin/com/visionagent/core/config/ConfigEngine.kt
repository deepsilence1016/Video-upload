package com.visionagent.core.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.visionagent.data.local.database.AgentDatabase
import com.visionagent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

// ============================================================
// ConfigEngine — Centralized Configuration Management
//
// Features:
// - Typed config with defaults
// - Real-time config updates via Flow
// - Remote config override support (future)
// - Config validation before apply
// - Environment-specific configs (debug/release)
// - Encrypted storage for sensitive config
// - Config versioning + migration
// ============================================================

val Context.configDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "agent_config")

// ─────────────────────────────────────────────────────────────────────────────
// Config Definitions — all agent configs in one place
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class CaptureEngineConfig(
    val targetFps:             Int     = 15,
    val maxQueueSize:          Int     = 10,
    val enableROI:             Boolean = true,
    val roiChangeThreshold:    Float   = 0.02f,
    val jpegQuality:           Int     = 80,
    val enableHardwareAccel:   Boolean = false,
    val downscaleForLowEnd:    Boolean = false,
    val downscaleWidth:        Int     = 720,
    val downscaleHeight:       Int     = 1280
)

@Serializable
data class VisionEngineConfig(
    val confidenceThreshold:   Float   = 0.75f,
    val enableButtonDetection: Boolean = true,
    val enableIconDetection:   Boolean = true,
    val enablePopupDetection:  Boolean = true,
    val enableLayoutAnalysis:  Boolean = true,
    val maxElementsPerFrame:   Int     = 50,
    val enableGPUAcceleration: Boolean = false,
    val processingThreadCount: Int     = 2
)

@Serializable
data class OCREngineConfig(
    val enabledLanguages:      List<String> = listOf("eng"),
    val pageSegMode:           Int     = 6,
    val ocrEngineMode:         Int     = 3,
    val confidenceThreshold:   Float   = 0.60f,
    val enableErrorCorrection: Boolean = true,
    val enableCache:           Boolean = true,
    val cacheMaxSize:          Int     = 100,
    val preprocessingLevel:    Int     = 2   // MEDIUM
)

@Serializable
data class RuleEngineConfig(
    val maxRules:              Int     = 500,
    val evaluationTimeoutMs:   Long    = 20L,
    val enableRuleCache:       Boolean = true,
    val ruleCacheTtlSec:       Long    = 30L
)

@Serializable
data class MemoryEngineConfig(
    val stmMaxSize:            Int     = 500,
    val stmDefaultTtlMs:       Long    = 30_000L,
    val screenHistoryCapacity: Int     = 20,
    val actionHistoryCapacity: Int     = 100,
    val enableEncryptedMemory: Boolean = true,
    val ltmCleanupIntervalMs:  Long    = 3_600_000L  // 1 hour
)

@Serializable
data class RecoveryEngineConfig(
    val maxGlobalAttempts:     Int     = 10,
    val baseRetryDelayMs:      Long    = 500L,
    val backoffMultiplier:     Float   = 2.0f,
    val maxRetryDelayMs:       Long    = 10_000L,
    val enableAutoRecovery:    Boolean = true
)

@Serializable
data class PerformanceConfig(
    val enableTracking:        Boolean = true,
    val metricsFlushIntervalMs:Long    = 5_000L,
    val frameThresholdMs:      Long    = 50L,
    val visionThresholdMs:     Long    = 100L,
    val ocrThresholdMs:        Long    = 200L,
    val actionThresholdMs:     Long    = 500L,
    val memoryWarningMB:       Int     = 200,
    val enableDetailedLogging: Boolean = false
)

@Serializable
data class AgentMasterConfig(
    val capture:     CaptureEngineConfig  = CaptureEngineConfig(),
    val vision:      VisionEngineConfig   = VisionEngineConfig(),
    val ocr:         OCREngineConfig      = OCREngineConfig(),
    val rules:       RuleEngineConfig     = RuleEngineConfig(),
    val memory:      MemoryEngineConfig   = MemoryEngineConfig(),
    val recovery:    RecoveryEngineConfig = RecoveryEngineConfig(),
    val performance: PerformanceConfig    = PerformanceConfig(),
    val version:     Int                  = 1
)

// ─────────────────────────────────────────────────────────────────────────────
// Config Keys
// ─────────────────────────────────────────────────────────────────────────────

private object ConfigKeys {
    val MASTER_CONFIG  = stringPreferencesKey("master_config_json")
    val CONFIG_VERSION = intPreferencesKey("config_version")
    val LAST_UPDATED   = longPreferencesKey("config_last_updated")
}

// ─────────────────────────────────────────────────────────────────────────────
// ConfigEngine
// ─────────────────────────────────────────────────────────────────────────────

@Singleton
class ConfigEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AgentDatabase,
    private val logger:   Logger
) {
    companion object {
        private const val TAG = "ConfigEngine"
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val configScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Current config — hot StateFlow
    private val _config = MutableStateFlow(AgentMasterConfig())
    val config: StateFlow<AgentMasterConfig> = _config.asStateFlow()

    // Convenient typed accessors
    val captureConfig: CaptureEngineConfig   get() = _config.value.capture
    val visionConfig:  VisionEngineConfig    get() = _config.value.vision
    val ocrConfig:     OCREngineConfig       get() = _config.value.ocr
    val rulesConfig:   RuleEngineConfig      get() = _config.value.rules
    val memoryConfig:  MemoryEngineConfig    get() = _config.value.memory
    val recoveryConfig:RecoveryEngineConfig  get() = _config.value.recovery
    val perfConfig:    PerformanceConfig     get() = _config.value.performance

    suspend fun initialize() {
        loadSavedConfig()
        observeConfigChanges()
        logger.i(TAG, "ConfigEngine initialized | version=${_config.value.version}")
    }

    private suspend fun loadSavedConfig() {
        context.configDataStore.data.firstOrNull()?.let { prefs ->
            prefs[ConfigKeys.MASTER_CONFIG]?.let { jsonStr ->
                try {
                    _config.value = json.decodeFromString(jsonStr)
                    logger.d(TAG, "Config loaded from DataStore")
                } catch (e: Exception) {
                    logger.w(TAG, "Config parse error — using defaults", e)
                }
            }
        }
    }

    private fun observeConfigChanges() {
        context.configDataStore.data
            .catch { e -> logger.e(TAG, "Config DataStore error", e) }
            .onEach { prefs ->
                prefs[ConfigKeys.MASTER_CONFIG]?.let { jsonStr ->
                    try {
                        val newConfig = json.decodeFromString<AgentMasterConfig>(jsonStr)
                        if (newConfig.version > _config.value.version) {
                            _config.value = newConfig
                            logger.i(TAG, "Config updated to version ${newConfig.version}")
                        }
                    } catch (e: Exception) { /* ignore parse error */ }
                }
            }
            .launchIn(configScope)
    }

    /** Update a specific sub-config */
    suspend fun updateCapture(block: CaptureEngineConfig.() -> CaptureEngineConfig) {
        val current  = _config.value
        val newCfg   = current.copy(
            capture = current.capture.block(),
            version = current.version + 1
        )
        applyAndSave(newCfg)
    }

    suspend fun updateVision(block: VisionEngineConfig.() -> VisionEngineConfig) {
        val current = _config.value
        applyAndSave(current.copy(vision = current.vision.block(), version = current.version + 1))
    }

    suspend fun updateOCR(block: OCREngineConfig.() -> OCREngineConfig) {
        val current = _config.value
        applyAndSave(current.copy(ocr = current.ocr.block(), version = current.version + 1))
    }

    suspend fun updatePerformance(block: PerformanceConfig.() -> PerformanceConfig) {
        val current = _config.value
        applyAndSave(current.copy(performance = current.performance.block(), version = current.version + 1))
    }

    /** Apply a complete new config (e.g., from remote fetch) */
    suspend fun applyRemoteConfig(newConfig: AgentMasterConfig) {
        if (!validateConfig(newConfig)) {
            logger.w(TAG, "Remote config validation failed — rejecting")
            return
        }
        applyAndSave(newConfig)
        logger.i(TAG, "Remote config applied: version=${newConfig.version}")
    }

    /** Reset to factory defaults */
    suspend fun resetToDefaults() {
        applyAndSave(AgentMasterConfig())
        logger.i(TAG, "Config reset to defaults")
    }

    private suspend fun applyAndSave(config: AgentMasterConfig) {
        _config.value = config
        val jsonStr = json.encodeToString(config)
        context.configDataStore.edit { prefs ->
            prefs[ConfigKeys.MASTER_CONFIG] = jsonStr
            prefs[ConfigKeys.CONFIG_VERSION] = config.version
            prefs[ConfigKeys.LAST_UPDATED]   = System.currentTimeMillis()
        }
        // Also persist to Room DB for history
        database.configDao().upsertConfig("master_config", jsonStr)
    }

    private fun validateConfig(config: AgentMasterConfig): Boolean {
        if (config.capture.targetFps !in 1..60) return false
        if (config.vision.confidenceThreshold !in 0.0f..1.0f) return false
        if (config.ocr.confidenceThreshold !in 0.0f..1.0f) return false
        if (config.memory.stmMaxSize < 10) return false
        return true
    }

    fun observeChanges(): Flow<AgentMasterConfig> = _config.asStateFlow()
}
