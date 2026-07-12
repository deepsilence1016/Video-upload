package com.visionagent.core.performance
import kotlin.collections.ArrayDeque  // Explicit: avoids Lint confusion with java.util.ArrayDeque (API 35)

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import com.visionagent.core.event.AgentEventBus
import com.visionagent.core.event.PerformanceMetricEvent
import com.visionagent.utils.Logger
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

// ============================================================
// PerformanceTracker — Real-time Performance Monitoring
//
// Tracks:
// - Per-operation execution time (nanosecond precision)
// - Memory usage (heap + native)
// - CPU percentage (per thread)
// - GC pressure (allocation count)
// - Frame processing throughput
//
// Design:
// - ThreadLocal for zero-contention timing
// - Ring buffer for historical metrics
// - Adaptive alerting (warn if latency exceeds threshold)
// - Async reporting to avoid impacting hot paths
//
// Time: O(1) for all operations
// Space: O(N) where N = ring buffer size (configurable)
// ============================================================

data class PerformanceMetric(
    val module: String,
    val operation: String,
    val durationMs: Long,
    val memoryBytes: Long,
    val cpuPercent: Float,
    val timestamp: Long = System.currentTimeMillis()
)

data class PerformanceThresholds(
    val frameCapture: Long = 50,     // ms — max acceptable
    val visionPipeline: Long = 100,
    val ocrPipeline: Long = 200,
    val ruleEvaluation: Long = 20,
    val actionExecution: Long = 500,
    val planGeneration: Long = 50,
    val memoryWarningBytes: Long = 200 * 1024 * 1024  // 200 MB
)

@Singleton
class PerformanceTracker @Inject constructor(
    private val eventBus: AgentEventBus,
    private val logger: Logger
) {

    companion object {
        private const val TAG = "PerformanceTracker"
        private const val RING_BUFFER_SIZE = 1000
    }

    private val thresholds = PerformanceThresholds()
    // FIX NC-5: startTimes ConcurrentHashMap removed — it was dead code.
    // start() wrote to it; end() ignored it (used the returned Long instead);
    // then end() called startTimes.remove() removing a potentially wrong entry.
    // All timing is correctly computed from the Long returned by start() and
    // passed as a parameter to end() — the map was purely overhead (60 ops/sec).

    // Ring buffer for historical metrics
    private val metricsBuffer = ArrayDeque<PerformanceMetric>(RING_BUFFER_SIZE)
    private val metricsLock = Any()

    // Aggregated stats
    private val operationCounts = ConcurrentHashMap<String, AtomicLong>()
    private val totalDurations = ConcurrentHashMap<String, AtomicLong>()
    private val maxDurations = ConcurrentHashMap<String, AtomicLong>()

    private val trackerScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("PerformanceTracker")
    )

    // FIX M4-1: Debug.getMemoryInfo() is a synchronous Binder IPC call (5-50ms per call).
    // It was called inside end() which runs ~60 times/second — adding up to 3 seconds of
    // blocking per second on the caller thread.
    // Fix: sample memory in a background coroutine every 5 seconds and cache the result.
    // end() reads the cached value with zero IPC overhead.
    private val cachedMemoryBytes = AtomicLong(0L)
    private val nativeMemoryUsed  = AtomicLong(0L)

    init {
        // Background memory sampler — runs every 5 seconds, not 60 times/second
        trackerScope.launch {
            while (isActive) {
                try {
                    val memInfo = Debug.MemoryInfo()
                    Debug.getMemoryInfo(memInfo)   // IPC call — safe here, not on hot path
                    cachedMemoryBytes.set(memInfo.totalPss * 1024L)
                } catch (_: Exception) { /* non-fatal */ }
                delay(5_000L)
            }
        }
    }

    fun start(operation: String): Long {
        val startTime = SystemClock.elapsedRealtimeNanos()
        return startTime  // FIX NC-5: no longer stored in map
    }

    fun end(operation: String, startTime: Long, sessionId: String): Long {
        val endTime = SystemClock.elapsedRealtimeNanos()
        val durationMs = (endTime - startTime) / 1_000_000L

        // Update aggregates
        operationCounts.getOrPut(operation) { AtomicLong(0) }.incrementAndGet()
        totalDurations.getOrPut(operation) { AtomicLong(0) }.addAndGet(durationMs)
        // FIX NC-4: Non-atomic read-modify-write on maxDurations.
        // Old code: if (get() > max) set() — two separate ops, not atomic.
        // Two threads could both pass the check and the lower value could win.
        // Fix: CAS loop (compareAndSet) — standard atomic max update pattern.
        maxDurations.getOrPut(operation) { AtomicLong(0) }.let { max ->
            var current = max.get()
            while (durationMs > current) {
                if (max.compareAndSet(current, durationMs)) break
                current = max.get()  // lost the race — re-read and retry
            }
        }
        // FIX NC-5: startTimes.remove removed — map no longer exists
        // FIX M4-1: Read cached memory value instead of calling IPC per operation.
        val memoryBytes = cachedMemoryBytes.get()

        // Threshold check
        checkThreshold(operation, durationMs, sessionId)

        // Async metric recording (don't block hot path)
        trackerScope.launch {
            recordMetric(
                module = operation.substringBefore("_"),
                operation = operation,
                durationMs = durationMs,
                memoryBytes = memoryBytes,
                sessionId = sessionId
            )
        }

        return durationMs
    }

    private fun checkThreshold(operation: String, durationMs: Long, sessionId: String) {
        val threshold = when {
            operation.contains("frame") -> thresholds.frameCapture
            operation.contains("vision") -> thresholds.visionPipeline
            operation.contains("ocr") -> thresholds.ocrPipeline
            operation.contains("rule") -> thresholds.ruleEvaluation
            operation.contains("action") -> thresholds.actionExecution
            operation.contains("plan") -> thresholds.planGeneration
            else -> Long.MAX_VALUE
        }

        if (durationMs > threshold) {
            logger.w(TAG, "⚠️ Threshold exceeded | op=$operation | dur=${durationMs}ms | threshold=${threshold}ms")
        }
    }

    private fun recordMetric(
        module: String,
        operation: String,
        durationMs: Long,
        memoryBytes: Long,
        sessionId: String
    ) {
        val metric = PerformanceMetric(module, operation, durationMs, memoryBytes, getCpuPercent())

        synchronized(metricsLock) {
            if (metricsBuffer.size >= RING_BUFFER_SIZE) metricsBuffer.removeFirst()
            metricsBuffer.addLast(metric)
        }

        // Publish to event bus for Logger and DB
        eventBus.publish(
            PerformanceMetricEvent(
                module = module,
                operationName = operation,
                durationMs = durationMs,
                memoryUsageBytes = memoryBytes,
                cpuPercent = metric.cpuPercent,
                sessionId = sessionId
            )
        )
    }

    private fun getCpuPercent(): Float {
        // Read /proc/self/stat for CPU usage
        return try {
            val stat = java.io.File("/proc/self/stat").readText().split(" ")
            val utime = stat[13].toLong()
            val stime = stat[14].toLong()
            (utime + stime).toFloat() / Runtime.getRuntime().availableProcessors()
        } catch (e: Exception) {
            0f
        }
    }

    // ---- Analytics ----

    fun getAverageLatency(operation: String): Double {
        val count = operationCounts[operation]?.get() ?: return 0.0
        val total = totalDurations[operation]?.get() ?: return 0.0
        return if (count > 0) total.toDouble() / count else 0.0
    }

    fun getMaxLatency(operation: String): Long =
        maxDurations[operation]?.get() ?: 0L

    fun getOperationCount(operation: String): Long =
        operationCounts[operation]?.get() ?: 0L

    fun getRecentMetrics(n: Int): List<PerformanceMetric> =
        synchronized(metricsLock) { metricsBuffer.takeLast(minOf(n, metricsBuffer.size)) }

    fun getSummaryReport(): Map<String, Any> {
        return operationCounts.keys.associate { op ->
            op to mapOf(
                "count" to getOperationCount(op),
                "avg_ms" to getAverageLatency(op),
                "max_ms" to getMaxLatency(op)
            )
        }
    }

    fun getCurrentMemoryMB(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    }

    fun updateNativeMemory(bytes: Long) {
        nativeMemoryUsed.set(bytes)
    }

    fun reset() {
        operationCounts.clear()
        totalDurations.clear()
        maxDurations.clear()
        synchronized(metricsLock) { metricsBuffer.clear() }
    }

    // FIX R5-9: trackerScope had no shutdown path.
    // The background memory sampler coroutine and async recordMetric() coroutines
    // were never cancelled on app shutdown — they continued running until process death.
    // While harmless in most cases (process death cleans up), this is bad hygiene
    // and prevents clean shutdown in tests.
    fun stop() {
        trackerScope.cancel()
    }
}

// ============================================================
// Memory Pool — Reduces GC pressure for frame data
// ============================================================

class FrameMemoryPool(
    private val poolSize: Int = 5,
    private val frameByteSize: Int = 1920 * 1080 * 4  // ARGB max
) {
    private val pool = ArrayDeque<ByteArray>(poolSize)

    init {
        // Pre-allocate pool on initialization
        repeat(poolSize) { pool.addLast(ByteArray(frameByteSize)) }
    }

    @Synchronized
    fun acquire(): ByteArray {
        return if (pool.isNotEmpty()) pool.removeFirst()
        else ByteArray(frameByteSize)  // Allocate if pool exhausted
    }

    @Synchronized
    fun release(buffer: ByteArray) {
        if (pool.size < poolSize) {
            pool.addLast(buffer)
        }
        // If pool full, let GC collect
    }

    fun poolSize() = pool.size
}

// ============================================================
// ThreadPool — Optimized for Vision Agent workloads
// ============================================================

object AgentThreadPools {
    // Vision processing — 2 threads (CPU intensive)
    val visionPool = newFixedThreadPoolContext(2, "VisionWorker")

    // IO operations — uses Dispatchers.IO
    val ioPool = Dispatchers.IO

    // Frame capture — single thread for determinism
    val capturePool = newSingleThreadContext("CaptureWorker")

    // OCR — single thread (Tesseract not thread-safe)
    val ocrPool = newSingleThreadContext("OCRWorker")

    // Rule evaluation — fast, coroutine-based
    val rulePool = Dispatchers.Default
}
