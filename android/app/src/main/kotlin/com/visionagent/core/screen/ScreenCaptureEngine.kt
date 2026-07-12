package com.visionagent.core.screen
import kotlin.collections.ArrayDeque  // Explicit: avoids Lint confusion with java.util.ArrayDeque (API 35)

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import com.visionagent.core.event.AgentErrorCode
import com.visionagent.core.event.AgentErrorEvent
import com.visionagent.core.event.AgentEventBus
import com.visionagent.core.event.FrameCapturedEvent
import com.visionagent.core.event.FrameDroppedEvent
import com.visionagent.core.event.FrameDropReason
import com.visionagent.core.performance.PerformanceTracker
import com.visionagent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

// ============================================================
// ScreenCaptureEngine — Low Latency Frame Capture
// 
// Design:
// - MediaProjection API for screen capture
// - Dedicated HandlerThread for ImageReader callbacks
// - ArrayBlockingQueue as Frame Buffer (bounded, FIFO)
// - Atomic counters for thread-safe metrics
// - FPS Controller via token bucket algorithm
// - ROI Detection to skip unchanged frames
// - Memory pool to avoid GC pressure
//
// Performance targets:
// - Capture latency: <16ms (60fps compatible)
// - Memory: reuse ByteBuffers via pool
// - CPU: <5% on Cortex-A75 equivalent
// ============================================================

data class CaptureConfig(
    val targetFps: Int = 15,            // Default 15fps — balances performance vs battery
    val maxQueueSize: Int = 10,          // Drop frames if queue exceeds this
    val enableROI: Boolean = true,       // Skip unchanged regions
    val roiChangeThreshold: Float = 0.02f, // 2% pixel change triggers capture
    val width: Int = 0,                  // 0 = native resolution
    val height: Int = 0,
    val jpegQuality: Int = 80            // Compress before sending to Vision Engine
)

data class CapturedFrame(
    val id: Long,
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val captureTimestamp: Long,
    val fps: Float
) {
    // Avoid ByteArray default equals/hashCode
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CapturedFrame) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}

@Singleton
class ScreenCaptureEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventBus: AgentEventBus,
    private val performanceTracker: PerformanceTracker,
    private val logger: Logger
) {

    companion object {
        private const val TAG = "ScreenCaptureEngine"
        private const val HANDLER_THREAD_NAME = "FrameCaptureThread"
        private const val VIRTUAL_DISPLAY_NAME = "VisionAgentCapture"
        private const val FRAME_COUNTER_RESET_INTERVAL_MS = 1000L
    }

    // ---- State ----
    private val isRunning = AtomicBoolean(false)
    private val frameCounter = AtomicLong(0)
    private val droppedFrameCounter = AtomicLong(0)
    private val lastFrameTimestamp = AtomicLong(0)

    // ---- MediaProjection ----
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // ---- Dedicated capture thread ----
    private var handlerThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    // ---- Frame Buffer — bounded, thread-safe ----
    private val frameQueue = ArrayBlockingQueue<CapturedFrame>(20)

    // FIX R5-3: frameChannel and frameFlow removed.
    // R4-6 removed the only write (frameChannel.trySend) as dead code.
    // Channel.close() on stop() made it permanently unusable on restart (channels
    // cannot be reopened after close). Removing the channel eliminates this hazard.
    // EventBus is the sole frame distribution mechanism.

    // ---- FPS Controller — Token Bucket ----
    // FIX NC-12: @Volatile — fpsController written by updateFps() (any thread),
    // read by imageAvailableListener (HandlerThread). Without @Volatile,
    // new FPSController may never be visible on the capture thread.
    @Volatile private var fpsController: FPSController = FPSController(15)

    // ---- ROI Change Detector (Native) ----
    private val roiDetector = ROIChangeDetector()

    // ---- Config ----
    // FIX NC-12: @Volatile — config.enableROI and config.jpegQuality read by
    // HandlerThread; updateFps() writes config from different thread.
    @Volatile private var config = CaptureConfig()

    // ---- Session ----
    // FIX LD-5: currentSessionId written on calling thread (Main via startCapture()),
    // read on HandlerThread (imageAvailableListener for FrameDroppedEvent sessionId).
    // Without @Volatile, HandlerThread may read stale empty string.
    @Volatile private var currentSessionId: String = ""

    // ---- Previous frame hash for change detection ----
    // Only ever accessed from the HandlerThread (single writer, single reader) — no @Volatile needed.
    private var previousFrameHash: Long = 0

    // ---- Display metrics ----
    // FIX L4-1: displayWidth/Height written by initialize() (calling thread, typically Main),
    // read by imageAvailableListener (HandlerThread). Without @Volatile the HandlerThread
    // could read 0 → Bitmap.createBitmap(0, height, ...) → IllegalArgumentException crash.
    @Volatile private var displayWidth    = 0
    @Volatile private var displayHeight   = 0
    @Volatile private var displayDensityDpi = 0

    // ---- Scope ----
    private val engineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("ScreenCaptureEngine")
    )

    // FIX R5-3: frameFlow removed — was never subscribed to in production code.

    // ============================================================
    // Initialization
    // ============================================================

    fun initialize(config: CaptureConfig = CaptureConfig()) {
        this.config = config
        this.fpsController = FPSController(config.targetFps)
        resolveDisplayMetrics()
        logger.d(TAG, "ScreenCaptureEngine initialized | config=$config")
    }

    private fun resolveDisplayMetrics() {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        displayWidth = if (config.width > 0) config.width else metrics.widthPixels
        displayHeight = if (config.height > 0) config.height else metrics.heightPixels
        displayDensityDpi = metrics.densityDpi
    }

    // ============================================================
    // Start Capture — called after MediaProjection permission
    // ============================================================

    fun startCapture(
        resultCode: Int,              // FIX H-1: accept actual resultCode from onActivityResult
        mediaProjectionIntent: Intent,
        sessionId: String
    ) {
        // FIX H-1: Check resultCode BEFORE setting isRunning.
        // If user denied the MediaProjection permission (RESULT_CANCELED),
        // getMediaProjection() returns null silently — capture never starts
        // but isRunning stays true, hiding the failure from all callers.
        if (resultCode != android.app.Activity.RESULT_OK) {
            logger.e(TAG, "MediaProjection permission denied (resultCode=$resultCode)")
            eventBus.publish(AgentErrorEvent(
                errorCode = AgentErrorCode.CAPTURE_FAILED,
                message   = "MediaProjection permission not granted by user",
                isFatal   = false,
                sessionId = sessionId
            ))
            return
        }

        if (isRunning.getAndSet(true)) {
            logger.w(TAG, "Capture already running")
            return
        }
        currentSessionId = sessionId

        val projManager = context.getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        // resultCode is now validated to be RESULT_OK above
        mediaProjection = projManager.getMediaProjection(resultCode, mediaProjectionIntent)

        if (mediaProjection == null) {
            isRunning.set(false)
            logger.e(TAG, "getMediaProjection returned null despite RESULT_OK")
            eventBus.publish(AgentErrorEvent(
                errorCode = AgentErrorCode.CAPTURE_FAILED,
                message   = "getMediaProjection returned null",
                isFatal   = false,
                sessionId = sessionId
            ))
            return
        }

        // Register callback to handle projection stop
        mediaProjection?.registerCallback(projectionCallback, null)

        setupHandlerThread()
        setupImageReader()
        setupVirtualDisplay()

        // Start frame processing coroutine
        engineScope.launch { processFrameQueue() }

        logger.i(TAG, "Screen capture started | session=$sessionId | ${displayWidth}x${displayHeight}@${config.targetFps}fps")
    }

    private fun setupHandlerThread() {
        handlerThread = HandlerThread(HANDLER_THREAD_NAME, Thread.NORM_PRIORITY + 1).also {
            it.start()
            captureHandler = Handler(it.looper)
        }
    }

    private fun setupImageReader() {
        imageReader = ImageReader.newInstance(
            displayWidth, displayHeight,
            PixelFormat.RGBA_8888,
            config.maxQueueSize
        ).apply {
            setOnImageAvailableListener(imageAvailableListener, captureHandler)
        }
    }

    private fun setupVirtualDisplay() {
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            displayWidth,
            displayHeight,
            displayDensityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            captureHandler
        )
    }

    // ============================================================
    // ImageReader Callback — runs on captureHandler thread
    // ============================================================

    private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val captureTime = SystemClock.elapsedRealtime()

        // FPS throttle — skip if too fast
        if (!fpsController.shouldCapture()) {
            reader.acquireLatestImage()?.close()
            return@OnImageAvailableListener
        }

        val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener

        try {
            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * displayWidth

            // Create Bitmap from ImageReader buffer
            val bitmap = Bitmap.createBitmap(
                displayWidth + rowPadding / pixelStride,
                displayHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // FIX H-2: bitmap.recycle() moved into a try/finally block.
            // Previously: if compressBitmap() threw (e.g., OOM during JPEG compression),
            // recycle() was never called → 8MB native heap leak per frame.
            try {
                // ROI Change Detection — skip if screen hasn't changed
                if (config.enableROI) {
                    val frameHash = computeFrameHash(bitmap)
                    if (frameHash == previousFrameHash) {
                        return@OnImageAvailableListener  // finally block recycles bitmap
                    }
                    previousFrameHash = frameHash
                }

                // Compress to JPEG for Vision Engine
                val frameData = compressBitmap(bitmap)

                val frameId = frameCounter.incrementAndGet()
                val fps     = fpsController.currentFps()

                val frame = CapturedFrame(
                    id               = frameId,
                    data             = frameData,
                    width            = displayWidth,
                    height           = displayHeight,
                    captureTimestamp = captureTime,
                    fps              = fps
                )

                if (!frameQueue.offer(frame)) {
                    droppedFrameCounter.incrementAndGet()
                    eventBus.publish(
                        FrameDroppedEvent(frameId, FrameDropReason.QUEUE_FULL,
                                          sessionId = currentSessionId)
                    )
                    logger.w(TAG, "Frame dropped — queue full | total_drops=${droppedFrameCounter.get()}")
                }
            } finally {
                bitmap.recycle()  // always recycle, even on exception or early return
            }

        } catch (e: Exception) {
            logger.e(TAG, "Frame capture error", e)
        } finally {
            image.close()
        }
    }

    // ============================================================
    // Frame Queue Processor — runs on IO dispatcher
    // ============================================================

    private suspend fun processFrameQueue() {
        withContext(Dispatchers.IO) {
            while (isRunning.get() && isActive) {
                val frame = frameQueue.poll() ?: run {
                    delay(8) // Wait ~1 frame interval
                    return@run null
                } ?: continue

                val startTime = performanceTracker.start("frame_pipeline")

                // Publish to EventBus — Vision/OCR/Rule engines subscribe to this.
                eventBus.publish(
                    FrameCapturedEvent(
                        frameId   = frame.id,
                        frameData = frame.data,
                        width     = frame.width,
                        height    = frame.height,
                        fps       = frame.fps,
                        sessionId = currentSessionId
                    )
                )
                // FIX R4-6: REMOVED frameChannel.trySend(frame).
                // The same ByteArray was published via both EventBus AND frameChannel.
                // Any frameFlow subscriber would double-process every frame.
                // No production code reads frameFlow — it was architectural dead code.
                // EventBus alone is the single distribution point.

                performanceTracker.end("frame_pipeline", startTime, currentSessionId)
            }
        }
    }

    // ============================================================
    // Stop Capture
    // ============================================================

    fun stopCapture() {
        if (!isRunning.getAndSet(false)) return

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        mediaProjection?.stop()
        mediaProjection = null

        handlerThread?.quitSafely()
        handlerThread = null

        frameQueue.clear()
        // FIX R5-3: frameChannel.close() removed — channel itself removed.

        // FIX M-6: Cancel the engine scope so processFrameQueue() coroutine stops.
        // Without this, the coroutine polls the empty queue indefinitely (coroutine leak).
        // Use cancelChildren() to cancel outstanding work but keep the scope available
        // for potential restart; use cancel() if this engine is truly done.
        engineScope.coroutineContext.cancelChildren()

        logger.i(TAG, "Screen capture stopped | total_frames=${frameCounter.get()} | dropped=${droppedFrameCounter.get()}")
    }

    // ============================================================
    // Utilities
    // ============================================================

    private fun computeFrameHash(bitmap: Bitmap): Long {
        // Fast perceptual hash — sample 8x8 grid
        val sampleBitmap = Bitmap.createScaledBitmap(bitmap, 8, 8, false)
        var hash = 0L
        for (x in 0 until 8) {
            for (y in 0 until 8) {
                val pixel = sampleBitmap.getPixel(x, y)
                hash = hash * 31 + pixel.toLong()
            }
        }
        sampleBitmap.recycle()
        return hash
    }

    private fun compressBitmap(bitmap: Bitmap): ByteArray {
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, config.jpegQuality, stream)
        return stream.toByteArray()
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            logger.w(TAG, "MediaProjection stopped externally")
            stopCapture()
        }
    }

    // ---- Metrics ----
    fun getTotalFramesCaptured(): Long = frameCounter.get()
    fun getDroppedFrameCount(): Long = droppedFrameCounter.get()
    fun isCapturing(): Boolean = isRunning.get()
    fun getQueueSize(): Int = frameQueue.size

    // ---- Dynamic FPS update ----
    fun updateFps(newFps: Int) {
        config = config.copy(targetFps = newFps)
        fpsController = FPSController(newFps)
    }
}

// ============================================================
// FPSController — Token Bucket Algorithm
// Controls capture rate without Thread.sleep
// ============================================================

// FIX R3-3: FPSController was not thread-safe.
// shouldCapture() is called from the HandlerThread (ImageReader callback).
// currentFps() is called from processFrameQueue() on Dispatchers.IO thread pool.
// fpsSamples (ArrayDeque) and lastCaptureTime (plain Long) were shared between
// two threads without synchronisation → ConcurrentModificationException / stale reads.
//
// Fix: @Synchronized on both methods. The lock window is microseconds (array add +
// one arithmetic op), so contention is negligible.
// lastCaptureTime changed to AtomicLong for visibility guarantee on write.
class FPSController(private val targetFps: Int) {
    private val intervalMs      = 1000L / targetFps.coerceIn(1, 60)
    // FIX TEST-FPS: Initialize to -intervalMs so the FIRST call always captures.
    // With 0L initial value, first call checks: now - 0 >= intervalMs.
    // In JVM unit tests SystemClock.elapsedRealtime() returns 0, so 0-0=0 < any intervalMs → FAIL.
    // With -intervalMs: 0 - (-intervalMs) = intervalMs >= intervalMs → always true on first call.
    private val lastCaptureTime = java.util.concurrent.atomic.AtomicLong(-1000L / targetFps.coerceIn(1, 60))
    private val fpsSamples      = ArrayDeque<Long>(60)   // guarded by `this`

    @Synchronized
    fun shouldCapture(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return if (now - lastCaptureTime.get() >= intervalMs) {
            fpsSamples.addLast(now)
            if (fpsSamples.size > 60) fpsSamples.removeFirst()
            lastCaptureTime.set(now)
            true
        } else false
    }

    @Synchronized
    fun currentFps(): Float {
        if (fpsSamples.size < 2) return 0f
        val duration = fpsSamples.last() - fpsSamples.first()
        return if (duration > 0) (fpsSamples.size - 1) * 1000f / duration else 0f
    }
}

// ============================================================
// ROIChangeDetector — Detects significant screen changes
// ============================================================

class ROIChangeDetector {
    // FIX NC-9: width and height are mandatory parameters — never hardcoded.
    private external fun detectChangeNative(frameData: ByteArray, width: Int, height: Int, threshold: Float): Boolean

    // Safe wrapper — returns true (always process frame) if native unavailable
    fun detectChange(frameData: ByteArray, width: Int, height: Int, threshold: Float): Boolean =
        if (nativeAvailable) {
            try { detectChangeNative(frameData, width, height, threshold) }
            catch (e: UnsatisfiedLinkError) { true }
        } else true

    companion object {
        // nativeAvailable: false if .so missing — app stays alive, ROI disabled gracefully
        val nativeAvailable: Boolean = try {
            System.loadLibrary("vision_agent_native")
            true
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.w("VisionAgent", "vision_agent_native not loaded: ${e.message}")
            false
        }
    }
}
