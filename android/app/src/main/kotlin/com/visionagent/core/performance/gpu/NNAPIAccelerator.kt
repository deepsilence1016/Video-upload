package com.visionagent.core.performance.gpu

import android.content.Context
import android.os.Build
import com.visionagent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

// ============================================================
// NNAPIAccelerator — Android Neural Networks API Integration
//
// NNAPI provides hardware acceleration for ML inference via:
// - DSP (Digital Signal Processor) — Qualcomm Hexagon
// - NPU (Neural Processing Unit) — Kirin, Exynos, MediaTek
// - GPU — via NNAPI GPU backend
// - CPU SIMD — fallback with XNNPACK
//
// When to use NNAPI vs CPU:
// ┌──────────────────┬───────────────┬────────────────┐
// │ Batch Size       │ CPU (NEON)    │ NNAPI          │
// ├──────────────────┼───────────────┼────────────────┤
// │ Single inference │ ~50ms         │ ~10ms (3-5x)   │
// │ Batch 8          │ ~200ms        │ ~15ms (13x)    │
// │ Model load       │ ~100ms        │ ~500ms (cold)  │
// └──────────────────┴───────────────┴────────────────┘
//
// Strategy: Use NNAPI for vision classification (frequent),
//           CPU for small rule evaluation (cheap).
//
// Supported Models via NNAPI:
// - Screen classifier (MobileNetV3)
// - UI element detector (EfficientDet-Lite)
// - Text region detector (CRAFT-TFLite)
// ============================================================

data class NNAPICapabilities(
    val isAvailable:      Boolean,
    val apiLevel:         Int,
    val hasNPU:           Boolean,
    val hasDSP:           Boolean,
    val hasGPUBackend:    Boolean,
    val acceleratorNames: List<String>,
    val recommendedLevel: NNAPILevel
)

enum class NNAPILevel {
    NONE,       // No NNAPI — use NEON CPU
    BASIC,      // Basic NNAPI (API 27) — CPU with NNAPI overhead
    ACCELERATED,// Real hardware acceleration (API 29+)
    FULL        // Full NPU/DSP acceleration (API 31+)
}

@Singleton
class NNAPIAccelerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger
) {
    companion object {
        private const val TAG = "NNAPIAccelerator"
    }

    private var capabilities: NNAPICapabilities? = null

    fun initialize(): NNAPICapabilities {
        val caps = detectCapabilities()
        capabilities = caps
        logger.i(TAG, "NNAPI capabilities: level=${caps.recommendedLevel} | NPU=${caps.hasNPU} | DSP=${caps.hasDSP}")
        return caps
    }

    private fun detectCapabilities(): NNAPICapabilities {
        val apiLevel = Build.VERSION.SDK_INT

        if (apiLevel < 27) {
            return NNAPICapabilities(
                isAvailable      = false,
                apiLevel         = apiLevel,
                hasNPU           = false,
                hasDSP           = false,
                hasGPUBackend    = false,
                acceleratorNames = emptyList(),
                recommendedLevel = NNAPILevel.NONE
            )
        }

        // Detect accelerator names via NnApiDelegate (API 29+)
        val acceleratorNames = mutableListOf<String>()
        var hasNPU = false
        var hasDSP = false
        var hasGPU = false

        if (apiLevel >= 29) {
            // In production: use NnApiDelegate.getAcceleratorNames()
            // Requires: implementation("org.tensorflow:tensorflow-lite:2.x")
            // Placeholder detection via manufacturer heuristics:
            val manufacturer = Build.MANUFACTURER.lowercase()
            when {
                manufacturer.contains("qualcomm") || manufacturer.contains("qcom") -> {
                    acceleratorNames.add("qti-dsp")
                    acceleratorNames.add("qti-gpu")
                    hasNPU = apiLevel >= 30  // Snapdragon 888+ has dedicated NPU
                    hasDSP = true
                    hasGPU = true
                }
                manufacturer.contains("samsung") -> {
                    acceleratorNames.add("samsung-npu")
                    hasNPU = true
                    hasGPU = true
                }
                manufacturer.contains("huawei") -> {
                    acceleratorNames.add("kirin-npu")
                    hasNPU = true
                    hasDSP = true
                }
                manufacturer.contains("mediatek") -> {
                    acceleratorNames.add("mediatek-apu")
                    hasNPU = apiLevel >= 30
                }
                else -> {
                    acceleratorNames.add("default")
                    hasGPU = apiLevel >= 29
                }
            }
        }

        val level = when {
            hasNPU && apiLevel >= 31 -> NNAPILevel.FULL
            (hasNPU || hasDSP) && apiLevel >= 29 -> NNAPILevel.ACCELERATED
            apiLevel >= 27 -> NNAPILevel.BASIC
            else -> NNAPILevel.NONE
        }

        return NNAPICapabilities(
            isAvailable      = apiLevel >= 27,
            apiLevel         = apiLevel,
            hasNPU           = hasNPU,
            hasDSP           = hasDSP,
            hasGPUBackend    = hasGPU,
            acceleratorNames = acceleratorNames,
            recommendedLevel = level
        )
    }

    /**
     * Get optimal TFLite delegate configuration string for the current device.
     * Used to configure the TFLite interpreter.
     */
    fun getOptimalDelegateConfig(): TFLiteDelegateConfig {
        val caps = capabilities ?: detectCapabilities()

        return when (caps.recommendedLevel) {
            NNAPILevel.FULL, NNAPILevel.ACCELERATED -> TFLiteDelegateConfig(
                useNNAPI         = true,
                nnApiAccelerator = caps.acceleratorNames.firstOrNull(),
                useGPU           = false,  // NNAPI already handles GPU
                numThreads       = 2,
                allowFP16        = true
            )
            NNAPILevel.BASIC -> TFLiteDelegateConfig(
                useNNAPI    = false,
                useGPU      = caps.hasGPUBackend,
                numThreads  = 4,
                allowFP16   = true
            )
            NNAPILevel.NONE -> TFLiteDelegateConfig(
                useNNAPI    = false,
                useGPU      = false,
                numThreads  = Runtime.getRuntime().availableProcessors().coerceAtMost(4),
                allowFP16   = false
            )
        }
    }

    /**
     * Estimate inference speedup over baseline CPU (NEON).
     * Used to decide whether to use NNAPI or skip the overhead.
     */
    fun estimatedSpeedup(): Float = when (capabilities?.recommendedLevel) {
        NNAPILevel.FULL        -> 5.0f   // 5x faster on NPU
        NNAPILevel.ACCELERATED -> 3.0f   // 3x on DSP
        NNAPILevel.BASIC       -> 1.2f   // Marginal on CPU-NNAPI
        else                   -> 1.0f   // No benefit
    }

    fun getCapabilities() = capabilities
    fun isAcceleratedAvailable() =
        capabilities?.recommendedLevel in listOf(NNAPILevel.ACCELERATED, NNAPILevel.FULL)
}

data class TFLiteDelegateConfig(
    val useNNAPI:          Boolean,
    val nnApiAccelerator:  String? = null,
    val useGPU:            Boolean,
    val numThreads:        Int,
    val allowFP16:         Boolean
)
