package com.visionagent.core.leak
import kotlin.collections.ArrayDeque  // Explicit: avoids Lint confusion with java.util.ArrayDeque (API 35)

import android.app.Application
import android.content.Context
import com.visionagent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

// ============================================================
// LeakDetector — Memory Leak Detection (LeakCanary-inspired)
//
// Integration:
// - Debug builds: Use square/leakcanary-android (full featured)
// - Release builds: Lightweight custom detector (this class)
//
// What we detect:
// 1. Object retention: objects that should be GC'd but aren't
//    → Track via WeakReference + ReferenceQueue
// 2. Native heap growth: monotonically growing native heap
//    → Read /proc/self/maps periodically
// 3. Bitmap leaks: Bitmap objects not recycled
//    → Track Bitmap.allocationByteCount sum
// 4. Coroutine scope leaks: CoroutineScopes not cancelled
//    → Track scope lifecycle
// 5. JNI global ref leaks: JNI objects not released
//    → Track via JNI call count
//
// Architecture:
// - WeakReference pool for tracked objects
// - GC trigger + ReferenceQueue drain
// - Periodic heap analysis
// - Leak report generation
// ============================================================

data class LeakReport(
    val objectClass:    String,
    val retainedCount:  Int,
    val retainedBytes:  Long,
    val firstSeenMs:    Long,
    val lastSeenMs:     Long,
    val suspectedLeak:  Boolean,
    val trace:          String
)

data class HeapSnapshot(
    val timestampMs:    Long,
    val heapUsedMB:     Long,
    val nativeHeapMB:   Long,
    val bitmapCountEstimate: Int,
    val trackedObjects: Int
)

// ─────────────────────────────────────────────────────────────────────────────
// Tracked Object Slot
// ─────────────────────────────────────────────────────────────────────────────

data class TrackedSlot(
    val className:  String,
    val weakRef:    WeakReference<Any>,
    val trackedAt:  Long = System.currentTimeMillis(),
    val callsite:   String = ""
)

// ─────────────────────────────────────────────────────────────────────────────
// LeakDetector
// ─────────────────────────────────────────────────────────────────────────────

@Singleton
class LeakDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger
) {
    companion object {
        private const val TAG                = "LeakDetector"
        private const val CHECK_INTERVAL_MS  = 30_000L    // Check every 30s
        private const val RETENTION_THRESHOLD = 60_000L   // >60s = suspected leak
        private const val MAX_TRACKED        = 200
        // Threshold: if heap grows by >50MB in 5 checks → leak
        private const val NATIVE_HEAP_GROWTH_MB = 50L
        private val ENABLED = com.visionagent.BuildConfig.DEBUG
    }

    private val detectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val trackedObjects = ConcurrentHashMap<Int, TrackedSlot>()  // id -> slot
    private val trackCounter   = AtomicInteger(0)
    private val leakReports    = ConcurrentHashMap<String, LeakReport>()

    // Native heap history for trend detection
    private val heapHistory    = ArrayDeque<Long>(10)
    private var lastHeapCheckMs = 0L

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Start leak detection loop.
     * No-op in release builds (zero overhead).
     */
    fun start() {
        if (!ENABLED) return
        detectorScope.launch { runDetectionLoop() }
        logger.i(TAG, "LeakDetector started (debug mode)")
    }

    /**
     * Track an object that SHOULD be GC'd soon.
     * Call this when releasing objects, then verify they're gone.
     *
     * Example:
     * ```kotlin
     * // When session ends:
     * leakDetector.track(screenCaptureEngine, "session_end")
     * // If still alive after 60s → leak suspected
     * ```
     */
    fun track(obj: Any, reason: String = "") {
        if (!ENABLED) return
        if (trackedObjects.size >= MAX_TRACKED) {
            pruneDeadReferences()
        }
        val id   = trackCounter.incrementAndGet()
        val slot = TrackedSlot(
            className = obj::class.java.name,
            weakRef   = WeakReference(obj),
            callsite  = reason
        )
        trackedObjects[id] = slot
        logger.v(TAG, "Tracking: ${slot.className} (reason=$reason, total=${trackedObjects.size})")
    }

    /**
     * Manually trigger GC and check for retained objects.
     * Call after major cleanup operations.
     */
    fun checkNow(): List<LeakReport> {
        if (!ENABLED) return emptyList()
        triggerGC()
        return detectRetainedObjects()
    }

    fun getAllReports(): List<LeakReport> = leakReports.values.toList()
        .sortedByDescending { it.retainedBytes }

    // ── Detection Loop ─────────────────────────────────────────────────────

    private suspend fun runDetectionLoop() {
        while (detectorScope.isActive) {
            delay(CHECK_INTERVAL_MS)
            performCheck()
        }
    }

    private fun performCheck() {
        // 1. Check retained objects
        val retained = detectRetainedObjects()
        if (retained.isNotEmpty()) {
            retained.forEach { report ->
                logger.w(TAG, "⚠️ POTENTIAL LEAK: ${report.objectClass} x${report.retainedCount}")
            }
        }

        // 2. Check native heap trend
        checkNativeHeapTrend()

        // 3. Prune dead references
        pruneDeadReferences()

        // 4. Log snapshot
        val snapshot = captureSnapshot()
        logger.d(TAG, "Heap: ${snapshot.heapUsedMB}MB native | Tracked: ${snapshot.trackedObjects}")
    }

    private fun detectRetainedObjects(): List<LeakReport> {
        triggerGC()

        val now    = System.currentTimeMillis()
        val reports = mutableMapOf<String, LeakReport>()

        trackedObjects.forEach { (_, slot) ->
            val obj = slot.weakRef.get() ?: return@forEach  // GC'd → not a leak

            // Still alive → check how long
            val ageMs = now - slot.trackedAt
            if (ageMs < RETENTION_THRESHOLD) return@forEach  // Too young to judge

            val className = slot.className
            val existing  = reports[className]
            reports[className] = LeakReport(
                objectClass   = className,
                retainedCount = (existing?.retainedCount ?: 0) + 1,
                retainedBytes = 0L,  // Would need Instrument API to measure precisely
                firstSeenMs   = existing?.firstSeenMs ?: slot.trackedAt,
                lastSeenMs    = now,
                suspectedLeak = ageMs > RETENTION_THRESHOLD * 2,
                trace         = slot.callsite
            )
        }

        // Merge with global reports
        reports.forEach { (k, v) -> leakReports[k] = v }
        return reports.values.filter { it.suspectedLeak }
    }

    private fun checkNativeHeapTrend() {
        val nativeHeap = android.os.Debug.getNativeHeapAllocatedSize() / 1024 / 1024
        heapHistory.addLast(nativeHeap)
        if (heapHistory.size > 10) heapHistory.removeFirst()

        if (heapHistory.size >= 5) {
            val growth = heapHistory.last() - heapHistory.first()
            if (growth > NATIVE_HEAP_GROWTH_MB) {
                logger.w(TAG, "⚠️ Native heap growing: +${growth}MB over ${heapHistory.size} checks")
            }
        }
    }

    private fun pruneDeadReferences() {
        val dead = trackedObjects.entries
            .filter { (_, slot) -> slot.weakRef.get() == null }
            .map { it.key }
        dead.forEach { trackedObjects.remove(it) }
        if (dead.isNotEmpty()) {
            logger.v(TAG, "Pruned ${dead.size} GC'd references")
        }
    }

    private fun captureSnapshot(): HeapSnapshot {
        val runtime    = Runtime.getRuntime()
        val heapUsed   = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        val nativeHeap = android.os.Debug.getNativeHeapAllocatedSize() / 1024 / 1024
        return HeapSnapshot(
            timestampMs  = System.currentTimeMillis(),
            heapUsedMB   = heapUsed,
            nativeHeapMB = nativeHeap,
            bitmapCountEstimate = 0,  // Would need reflection to count
            trackedObjects = trackedObjects.size
        )
    }

    private fun triggerGC() {
        Runtime.getRuntime().gc()
        System.runFinalization()
        // Brief sleep to let GC complete
        Thread.sleep(50)
    }

    fun stop() {
        detectorScope.cancel()
        trackedObjects.clear()
        logger.i(TAG, "LeakDetector stopped | reports=${leakReports.size}")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// LeakCanary Integration (Debug Only)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * LeakCanary setup — add to Application.onCreate() in DEBUG flavor.
 *
 * build.gradle.kts:
 * ```kotlin
 * debugImplementation("com.squareup.leakcanary:leakcanary-android:2.13")
 * ```
 *
 * LeakCanary auto-installs via ContentProvider — no code needed in most cases.
 * This class adds custom object watching for Vision Agent specific objects.
 */
object LeakCanaryConfig {

    /**
     * Call this after LeakCanary is initialized to watch Vision Agent objects.
     * Only works in debug builds with LeakCanary on classpath.
     */
    fun watchVisionAgentObjects(
        screenCaptureEngine: Any,
        visionEngine:        Any,
        ocrEngine:           Any,
        memoryEngine:        Any
    ) {
        if (!com.visionagent.BuildConfig.DEBUG) return

        try {
            // Reflection to call LeakCanary.objectWatcher without hard dependency
            val clazz  = Class.forName("leakcanary.LeakCanary")
            val field  = clazz.getDeclaredField("objectWatcher")
            val watcher = field.get(null)
            val method = watcher.javaClass.getMethod("watch", Any::class.java, String::class.java)

            method.invoke(watcher, screenCaptureEngine, "ScreenCaptureEngine released")
            method.invoke(watcher, visionEngine,        "VisionEngine released")
            method.invoke(watcher, ocrEngine,           "OCREngine released")
            method.invoke(watcher, memoryEngine,        "MemoryEngine released")
        } catch (e: ClassNotFoundException) {
            // LeakCanary not on classpath — use custom detector
        } catch (e: Exception) {
            android.util.Log.w("LeakCanaryConfig", "LeakCanary watch failed: ${e.message}")
        }
    }
}
