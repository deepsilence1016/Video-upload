package com.visionagent.core.feature

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.visionagent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

// ============================================================
// FeatureFlagManager — Runtime Feature Toggle System
//
// Enables:
// - Gradual feature rollout (canary releases)
// - A/B testing of algorithms
// - Emergency disable of broken features
// - Environment-specific features (debug-only)
// - Remote flag updates (future: Firebase Remote Config compatible)
//
// All flags are:
// - Type-safe (Boolean/Int/Float/String)
// - Observable via Flow
// - Persisted across app restarts
// - Default-safe (always has fallback)
// ============================================================

// ─────────────────────────────────────────────────────────────────────────────
// Feature Flag Definitions — all flags in one place
// ─────────────────────────────────────────────────────────────────────────────

object FeatureFlags {
    // Vision Engine
    val VISION_GPU_ACCELERATION      = BooleanFlag("vision_gpu_accel",         false)
    val VISION_NNAPI_SUPPORT         = BooleanFlag("vision_nnapi",              false)
    val VISION_ENABLE_MSER           = BooleanFlag("vision_mser",               true)
    val VISION_ENABLE_ORB            = BooleanFlag("vision_orb",                true)
    val VISION_CONFIDENCE_THRESHOLD  = FloatFlag  ("vision_conf_threshold",     0.75f)

    // OCR Engine
    val OCR_ENABLE_PREPROCESSING     = BooleanFlag("ocr_preprocess",            true)
    val OCR_ENABLE_DESKEW            = BooleanFlag("ocr_deskew",                true)
    val OCR_ENABLE_CACHE             = BooleanFlag("ocr_cache",                 true)
    val OCR_MULTI_LANGUAGE           = BooleanFlag("ocr_multi_lang",            false)

    // Frame Capture
    val CAPTURE_ENABLE_HARDWARE_ACCEL= BooleanFlag("capture_hw_accel",          false)
    val CAPTURE_ADAPTIVE_FPS         = BooleanFlag("capture_adaptive_fps",       true)
    val CAPTURE_TARGET_FPS           = IntFlag    ("capture_target_fps",          15)
    val CAPTURE_ZERO_COPY_PIPELINE   = BooleanFlag("capture_zero_copy",          true)

    // Memory
    val MEMORY_ENABLE_VECTOR_STORE   = BooleanFlag("memory_vector_store",        false)  // Future
    val MEMORY_ENABLE_KNOWLEDGE_GRAPH= BooleanFlag("memory_knowledge_graph",     false)  // Future
    val MEMORY_ENABLE_LTM_ENCRYPTION = BooleanFlag("memory_ltm_encrypt",         true)

    // Recovery
    val RECOVERY_AGGRESSIVE_MODE     = BooleanFlag("recovery_aggressive",        false)
    val RECOVERY_ENABLE_AUTO_ROLLBACK= BooleanFlag("recovery_auto_rollback",     true)

    // Performance
    val PERF_ENABLE_DETAILED_TRACING = BooleanFlag("perf_detailed_tracing",      false)
    val PERF_MEMORY_POOL_ENABLED     = BooleanFlag("perf_memory_pool",           true)
    val PERF_ENABLE_SIMD             = BooleanFlag("perf_simd",                  true)

    // Experimental (default OFF)
    val EXP_RUST_STATE_MACHINE       = BooleanFlag("exp_rust_state_machine",     false)
    val EXP_RUST_FRAME_PIPELINE      = BooleanFlag("exp_rust_frame_pipeline",    false)
    val EXP_ONNX_VISION_MODEL        = BooleanFlag("exp_onnx_vision",            false)
    val EXP_PLUGIN_SYSTEM            = BooleanFlag("exp_plugin_system",          true)

    // Debug-only
    val DEBUG_SHOW_DETECTION_OVERLAY = BooleanFlag("debug_detection_overlay",    false)
    val DEBUG_LOG_ALL_FRAMES         = BooleanFlag("debug_log_all_frames",       false)
    val DEBUG_SIMULATE_SLOW_VISION   = BooleanFlag("debug_slow_vision",          false)

    val ALL: List<Flag<*>> = listOf(
        VISION_GPU_ACCELERATION, VISION_NNAPI_SUPPORT, VISION_ENABLE_MSER,
        VISION_ENABLE_ORB, VISION_CONFIDENCE_THRESHOLD,
        OCR_ENABLE_PREPROCESSING, OCR_ENABLE_DESKEW, OCR_ENABLE_CACHE, OCR_MULTI_LANGUAGE,
        CAPTURE_ENABLE_HARDWARE_ACCEL, CAPTURE_ADAPTIVE_FPS, CAPTURE_TARGET_FPS,
        CAPTURE_ZERO_COPY_PIPELINE,
        MEMORY_ENABLE_VECTOR_STORE, MEMORY_ENABLE_KNOWLEDGE_GRAPH, MEMORY_ENABLE_LTM_ENCRYPTION,
        RECOVERY_AGGRESSIVE_MODE, RECOVERY_ENABLE_AUTO_ROLLBACK,
        PERF_ENABLE_DETAILED_TRACING, PERF_MEMORY_POOL_ENABLED, PERF_ENABLE_SIMD,
        EXP_RUST_STATE_MACHINE, EXP_RUST_FRAME_PIPELINE, EXP_ONNX_VISION_MODEL,
        EXP_PLUGIN_SYSTEM,
        DEBUG_SHOW_DETECTION_OVERLAY, DEBUG_LOG_ALL_FRAMES, DEBUG_SIMULATE_SLOW_VISION
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Flag Type Wrappers
// ─────────────────────────────────────────────────────────────────────────────

sealed class Flag<T>(val key: String, val defaultValue: T)

class BooleanFlag(key: String, default: Boolean) : Flag<Boolean>(key, default)
class IntFlag    (key: String, default: Int)     : Flag<Int>    (key, default)
class FloatFlag  (key: String, default: Float)   : Flag<Float>  (key, default)
class StringFlag (key: String, default: String)  : Flag<String> (key, default)

// ─────────────────────────────────────────────────────────────────────────────
// FeatureFlagManager
// ─────────────────────────────────────────────────────────────────────────────

@Singleton
class FeatureFlagManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger
) {
    companion object {
        private const val TAG = "FeatureFlagManager"
    }

    // In-memory cache for fast synchronous reads (hot path)
    private val flagCache = java.util.concurrent.ConcurrentHashMap<String, Any>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // DataStore keys
    private val PREF_PREFIX = "ff_"

    init {
        // Load all defaults into cache immediately
        FeatureFlags.ALL.forEach { flag ->
            flagCache[flag.key] = flag.defaultValue!!
        }
    }

    suspend fun initialize() {
        loadPersistedFlags()
        logger.i(TAG, "FeatureFlagManager initialized | ${flagCache.size} flags loaded")
    }

    // ── Synchronous reads (non-blocking, from cache) ──────────────────────

    fun isEnabled(flag: BooleanFlag): Boolean =
        flagCache[flag.key] as? Boolean ?: flag.defaultValue

    fun getInt(flag: IntFlag): Int =
        flagCache[flag.key] as? Int ?: flag.defaultValue

    fun getFloat(flag: FloatFlag): Float =
        flagCache[flag.key] as? Float ?: flag.defaultValue

    fun getString(flag: StringFlag): String =
        flagCache[flag.key] as? String ?: flag.defaultValue

    // ── Updates ──────────────────────────────────────────────────────────

    suspend fun setFlag(flag: BooleanFlag, value: Boolean) = saveFlag(flag.key, value)
    suspend fun setFlag(flag: IntFlag,     value: Int)     = saveFlag(flag.key, value)
    suspend fun setFlag(flag: FloatFlag,   value: Float)   = saveFlag(flag.key, value)
    suspend fun setFlag(flag: StringFlag,  value: String)  = saveFlag(flag.key, value)

    private suspend fun saveFlag(key: String, value: Any) {
        flagCache[key] = value
        context.flagDataStore.edit { prefs: androidx.datastore.preferences.core.MutablePreferences ->
            when (value) {
                is Boolean -> prefs[booleanPreferencesKey("$PREF_PREFIX$key")] = value
                is Int     -> prefs[intPreferencesKey    ("$PREF_PREFIX$key")] = value
                is Float   -> prefs[floatPreferencesKey  ("$PREF_PREFIX$key")] = value
                is String  -> prefs[stringPreferencesKey ("$PREF_PREFIX$key")] = value
            }
        }
        logger.d(TAG, "Flag updated: $key = $value")
    }

    /** Apply a batch of flag overrides (e.g., from remote config) */
    suspend fun applyOverrides(overrides: Map<String, Any>) {
        overrides.forEach { entry ->
            val key: String = entry.key; val value: Any = entry.value
            flagCache[key] = value
        }
        logger.i(TAG, "Applied ${overrides.size} flag overrides")
    }

    private suspend fun loadPersistedFlags() {
        context.flagDataStore.data.firstOrNull()?.let { prefs ->
            prefs.asMap().forEach { entry ->
                val prefKey = entry.key; val value = entry.value
                val flagKey = prefKey.name.removePrefix(PREF_PREFIX)
                if (flagKey.isNotEmpty()) flagCache[flagKey] = value
            }
        }
    }

    fun dumpAllFlags(): Map<String, Any> = flagCache.toMap()

    // Observe a specific flag for real-time updates
    fun observeBoolean(flag: BooleanFlag): Flow<Boolean> =
        context.flagDataStore.data.map { prefs ->
            prefs[booleanPreferencesKey("$PREF_PREFIX${flag.key}")] ?: flag.defaultValue
        }.distinctUntilChanged()
}

// DataStore extension for feature flags
private val Context.flagDataStore by preferencesDataStore(name = "feature_flags")
