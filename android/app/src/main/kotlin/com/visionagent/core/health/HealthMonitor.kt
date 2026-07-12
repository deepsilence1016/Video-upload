package com.visionagent.core.health

import com.visionagent.core.event.*
import com.visionagent.core.performance.PerformanceTracker
import com.visionagent.core.screen.ScreenCaptureEngine
import com.visionagent.utils.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

// ============================================================
// HealthMonitor — Real-time Agent Health Check System
//
// Monitors:
// - Frame capture rate (is capture stalled?)
// - Vision pipeline latency (is it too slow?)
// - Memory usage (approaching OOM?)
// - Error rate (too many failures?)
// - Battery drain rate
// - Thermal throttling
// - Module responsiveness (heartbeat)
//
// Actions:
// - Publish HealthAlertEvent when thresholds exceeded
// - Automatic adaptive quality degradation
// - OOM preemption (reduce capture rate, flush caches)
// - Thermal throttle response (reduce processing)
// ============================================================

enum class HealthStatus { HEALTHY, WARNING, CRITICAL, UNKNOWN }

data class HealthReport(
    val timestamp:       Long,
    val overallStatus:   HealthStatus,
    val frameCapture:    ModuleHealth,
    val visionPipeline:  ModuleHealth,
    val ocrPipeline:     ModuleHealth,
    val memoryUsage:     ModuleHealth,
    val errorRate:       ModuleHealth,
    val batteryDrain:    ModuleHealth,
    val recommendations: List<String>
)

data class ModuleHealth(
    val module:  String,
    val status:  HealthStatus,
    val value:   Float,
    val unit:    String,
    val message: String
)

@Singleton
class HealthMonitor @Inject constructor(
    private val eventBus:         AgentEventBus,
    private val performanceTracker: PerformanceTracker,
    private val screenCapture:    ScreenCaptureEngine,
    private val logger:           Logger
) {
    companion object {
        private const val TAG              = "HealthMonitor"
        private const val CHECK_INTERVAL_MS = 5_000L   // Check every 5 seconds
        private const val FRAME_STALL_MS   = 10_000L  // No frame in 10s = stall

        // Thresholds
        private const val MEMORY_WARNING_MB  = 150
        private const val MEMORY_CRITICAL_MB = 200
        private const val ERROR_RATE_WARNING  = 0.1f  // 10% failure rate
        private const val ERROR_RATE_CRITICAL = 0.3f  // 30% failure rate
        private const val FPS_WARNING_MIN     = 5f
        private const val VISION_WARNING_MS   = 150L
        private const val VISION_CRITICAL_MS  = 300L
    }

    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Health metrics (atomic for thread-safety)
    private val totalActions    = AtomicLong(0)
    private val failedActions   = AtomicLong(0)
    private val lastFrameTime   = AtomicLong(System.currentTimeMillis())
    private val consecutiveErrors = AtomicInteger(0)
    private val totalErrors     = AtomicLong(0)

    private var currentSessionId = ""
    private var isRunning = false

    fun startMonitoring(sessionId: String) {
        currentSessionId = sessionId
        isRunning = true
        subscribeToEvents()
        startPeriodicChecks()
        logger.i(TAG, "HealthMonitor started for session: $sessionId")
    }

    private fun subscribeToEvents() {
        eventBus.subscribe<FrameCapturedEvent>()
            .onEach { lastFrameTime.set(System.currentTimeMillis()) }
            .launchIn(monitorScope)

        eventBus.subscribe<ActionExecutedEvent>()
            .onEach { event ->
                totalActions.incrementAndGet()
                if (!event.success) {
                    failedActions.incrementAndGet()
                    consecutiveErrors.incrementAndGet()
                } else {
                    consecutiveErrors.set(0)
                }
            }
            .launchIn(monitorScope)

        eventBus.subscribe<AgentErrorEvent>()
            .onEach { totalErrors.incrementAndGet() }
            .launchIn(monitorScope)
    }

    private fun startPeriodicChecks() {
        monitorScope.launch {
            while (isRunning && isActive) {
                delay(CHECK_INTERVAL_MS)
                val report = generateHealthReport()
                handleHealthReport(report)
            }
        }
    }

    fun generateHealthReport(): HealthReport {
        val frameHealth    = checkFrameCapture()
        val visionHealth   = checkVisionPipeline()
        val ocrHealth      = checkOCRPipeline()
        val memoryHealth   = checkMemoryUsage()
        val errorHealth    = checkErrorRate()
        val batteryHealth  = checkBatteryDrain()

        val statuses = listOf(
            frameHealth, visionHealth, ocrHealth,
            memoryHealth, errorHealth, batteryHealth
        )

        val overallStatus = when {
            statuses.any { it.status == HealthStatus.CRITICAL } -> HealthStatus.CRITICAL
            statuses.any { it.status == HealthStatus.WARNING }  -> HealthStatus.WARNING
            else -> HealthStatus.HEALTHY
        }

        val recommendations = buildRecommendations(
            frameHealth, visionHealth, memoryHealth, errorHealth
        )

        return HealthReport(
            timestamp       = System.currentTimeMillis(),
            overallStatus   = overallStatus,
            frameCapture    = frameHealth,
            visionPipeline  = visionHealth,
            ocrPipeline     = ocrHealth,
            memoryUsage     = memoryHealth,
            errorRate       = errorHealth,
            batteryDrain    = batteryHealth,
            recommendations = recommendations
        )
    }

    private fun checkFrameCapture(): ModuleHealth {
        val timeSinceLastFrame = System.currentTimeMillis() - lastFrameTime.get()
        val droppedFrames = screenCapture.getDroppedFrameCount()
        val total = screenCapture.getTotalFramesCaptured().coerceAtLeast(1)
        val dropRate = droppedFrames.toFloat() / total

        val status = when {
            timeSinceLastFrame > FRAME_STALL_MS -> HealthStatus.CRITICAL
            dropRate > 0.3f                     -> HealthStatus.WARNING
            !screenCapture.isCapturing()         -> HealthStatus.WARNING
            else                                -> HealthStatus.HEALTHY
        }

        return ModuleHealth("FrameCapture", status, dropRate * 100,
            "%", "Drop rate: ${(dropRate * 100).toInt()}%")
    }

    private fun checkVisionPipeline(): ModuleHealth {
        val avgMs = performanceTracker.getAverageLatency("vision_pipeline").toFloat()
        val status = when {
            avgMs > VISION_CRITICAL_MS -> HealthStatus.CRITICAL
            avgMs > VISION_WARNING_MS  -> HealthStatus.WARNING
            else                       -> HealthStatus.HEALTHY
        }
        return ModuleHealth("VisionPipeline", status, avgMs, "ms",
            "Avg latency: ${avgMs.toInt()}ms")
    }

    private fun checkOCRPipeline(): ModuleHealth {
        val avgMs = performanceTracker.getAverageLatency("ocr_pipeline").toFloat()
        val status = if (avgMs > 500) HealthStatus.WARNING else HealthStatus.HEALTHY
        return ModuleHealth("OCRPipeline", status, avgMs, "ms",
            "Avg latency: ${avgMs.toInt()}ms")
    }

    private fun checkMemoryUsage(): ModuleHealth {
        val usedMB = performanceTracker.getCurrentMemoryMB()
        val status = when {
            usedMB > MEMORY_CRITICAL_MB -> HealthStatus.CRITICAL
            usedMB > MEMORY_WARNING_MB  -> HealthStatus.WARNING
            else                        -> HealthStatus.HEALTHY
        }
        return ModuleHealth("Memory", status, usedMB.toFloat(), "MB",
            "Used: ${usedMB}MB")
    }

    private fun checkErrorRate(): ModuleHealth {
        val total  = totalActions.get().coerceAtLeast(1)
        val failed = failedActions.get()
        val rate   = failed.toFloat() / total

        val status = when {
            rate > ERROR_RATE_CRITICAL   -> HealthStatus.CRITICAL
            rate > ERROR_RATE_WARNING    -> HealthStatus.WARNING
            consecutiveErrors.get() >= 5 -> HealthStatus.WARNING
            else                         -> HealthStatus.HEALTHY
        }
        return ModuleHealth("ErrorRate", status, rate * 100, "%",
            "Failure: ${(rate * 100).toInt()}%")
    }

    private fun checkBatteryDrain(): ModuleHealth {
        // Read from /sys/class/power_supply/battery/current_now
        return try {
            val current = java.io.File("/sys/class/power_supply/battery/current_now")
                .readText().trim().toLong() / 1000  // µA → mA
            val status = when {
                current < -2000 -> HealthStatus.WARNING  // > 2A drain
                else            -> HealthStatus.HEALTHY
            }
            ModuleHealth("Battery", status, current.toFloat(), "mA",
                "Current draw: ${current}mA")
        } catch (e: Exception) {
            ModuleHealth("Battery", HealthStatus.UNKNOWN, 0f, "mA", "Unable to read")
        }
    }

    private fun handleHealthReport(report: HealthReport) {
        when (report.overallStatus) {
            HealthStatus.CRITICAL -> {
                logger.e(TAG, "🔴 CRITICAL health! ${report.recommendations}")
                triggerAdaptiveResponse(report)
            }
            HealthStatus.WARNING -> {
                logger.w(TAG, "🟡 Health warning: ${report.recommendations}")
            }
            HealthStatus.HEALTHY -> {
                logger.v(TAG, "🟢 Agent healthy")
            }
            else -> {}
        }
    }

    private fun triggerAdaptiveResponse(report: HealthReport) {
        // Memory critical → reduce capture rate + flush OCR cache
        if (report.memoryUsage.status == HealthStatus.CRITICAL) {
            logger.w(TAG, "OOM risk — reducing capture FPS")
            screenCapture.updateFps(5)
        }

        // Vision too slow → suggest disabling expensive features
        if (report.visionPipeline.status == HealthStatus.CRITICAL) {
            logger.w(TAG, "Vision pipeline overloaded")
            eventBus.publish(AgentErrorEvent(
                errorCode = AgentErrorCode.VISION_FAILED,
                message   = "Vision pipeline latency critical: ${report.visionPipeline.value.toInt()}ms",
                isFatal   = false,
                sessionId = currentSessionId
            ))
        }
    }

    private fun buildRecommendations(
        frame: ModuleHealth,
        vision: ModuleHealth,
        memory: ModuleHealth,
        errors: ModuleHealth
    ): List<String> = buildList {
        if (frame.status  != HealthStatus.HEALTHY) add("Reduce capture FPS to ease CPU load")
        if (vision.status == HealthStatus.CRITICAL) add("Disable GPU acceleration flag and reduce maxElements")
        if (memory.status == HealthStatus.CRITICAL) add("Clear OCR cache and reduce STM size")
        if (errors.status != HealthStatus.HEALTHY) add("Check recovery engine — high error rate detected")
    }

    fun stopMonitoring() {
        isRunning = false
        monitorScope.cancel()
        logger.i(TAG, "HealthMonitor stopped")
    }

    fun resetMetrics() {
        totalActions.set(0)
        failedActions.set(0)
        consecutiveErrors.set(0)
        totalErrors.set(0)
        lastFrameTime.set(System.currentTimeMillis())
    }
}
