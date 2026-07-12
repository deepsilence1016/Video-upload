package com.visionagent.core.vision

import android.graphics.BitmapFactory
import com.visionagent.core.event.*
import com.visionagent.core.performance.PerformanceTracker
import com.visionagent.utils.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

// ============================================================
// VisionEngine — Screen Understanding & UI Detection
//
// Architecture:
// - Subscribes to FrameCapturedEvent via EventBus
// - Delegates heavy processing to Native VisionCore (C++)
// - Publishes UIElementDetectedEvent
//
// Native Bridge:
// - OpenCV for image processing pipeline
// - Template matching for known UI elements
// - Color analysis for button/icon detection
// - Layout analysis using connected components
//
// Performance:
// - Time complexity: O(W*H) per frame
// - Space: O(W*H) — reuse native buffers
// - Parallel processing on dedicated thread pool
// ============================================================

data class VisionConfig(
    val confidenceThreshold: Float = 0.75f,
    val enableButtonDetection: Boolean = true,
    val enableIconDetection: Boolean = true,
    val enablePopupDetection: Boolean = true,
    val enableLayoutAnalysis: Boolean = true,
    val maxElementsPerFrame: Int = 50,
    val enableGPUAcceleration: Boolean = false   // Future: OpenCL
)

@Singleton
class VisionEngine @Inject constructor(
    private val eventBus: AgentEventBus,
    private val performanceTracker: PerformanceTracker,
    private val nativeBridge: VisionNativeBridge,
    private val screenClassifier: ScreenClassifier,
    private val confidenceScorer: ConfidenceScorer,
    private val logger: Logger
) {

    companion object {
        private const val TAG = "VisionEngine"
    }

    private val engineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("VisionEngine")
    )

    // FIX LD-4: config and isInitialized written from calling thread (Main or Default via
    // AgentOrchestrator.initialize()), read from dispatcher (newFixedThreadPoolContext).
    // Without @Volatile, writes may not be visible on the processing threads.
    @Volatile private var config = VisionConfig()
    @Volatile private var isInitialized = false

    // ---- Internal processing pipeline ----
    private val dispatcher = newFixedThreadPoolContext(2, "VisionProcessors")

    fun initialize(config: VisionConfig = VisionConfig()) {
        this.config = config
        nativeBridge.initialize(config)
        isInitialized = true
        subscribeToEvents()
        logger.i(TAG, "VisionEngine initialized | config=$config")
    }

    private fun subscribeToEvents() {
        eventBus.subscribe<FrameCapturedEvent>()
            .onEach { event -> processFrame(event) }
            .launchIn(engineScope)
    }

    // ============================================================
    // Main Processing Pipeline
    // ============================================================

    private suspend fun processFrame(event: FrameCapturedEvent) {
        withContext(dispatcher) {
            val startTime = performanceTracker.start("vision_pipeline")

            try {
                val result = nativeBridge.processFrame(
                    frameData = event.frameData,
                    width = event.width,
                    height = event.height,
                    config = config
                )

                // Screen Classification
                val screenType = screenClassifier.classify(result)

                // Confidence scoring for all elements
                val scoredElements = result.elements.map { element ->
                    element.copy(
                        confidence = confidenceScorer.score(element, result.context)
                    )
                }.filter { it.confidence >= config.confidenceThreshold }
                 .take(config.maxElementsPerFrame)

                // Publish result
                if (scoredElements.isNotEmpty() || screenType != ScreenType.UNKNOWN) {
                    eventBus.publish(
                        UIElementDetectedEvent(
                            elements = scoredElements,
                            screenType = screenType,
                            confidence = result.overallConfidence,
                            sessionId = event.sessionId
                        )
                    )
                }

            } catch (e: Exception) {
                logger.e(TAG, "Vision processing failed", e)
                eventBus.publish(
                    AgentErrorEvent(
                        errorCode = AgentErrorCode.VISION_FAILED,
                        message = "Vision processing error: ${e.message}",
                        sessionId = event.sessionId
                    )
                )
            } finally {
                performanceTracker.end("vision_pipeline", startTime, event.sessionId)
            }
        }
    }

    fun release() {
        nativeBridge.release()
        engineScope.cancel()
        dispatcher.close()
        isInitialized = false
    }
}

// ============================================================
// VisionNativeBridge — JNI interface to C++ VisionCore
// ============================================================

data class NativeVisionResult(
    val elements: List<DetectedUIElement>,
    val overallConfidence: Float,
    val processingTimeMs: Long,
    val context: VisionContext
)

data class VisionContext(
    val dominantColors: List<Int>,
    val layoutType: String,
    val hasScrollableContent: Boolean
)

class VisionNativeBridge @Inject constructor() {

    external fun initialize(config: VisionConfig): Boolean
    external fun processFrame(
        frameData: ByteArray,
        width: Int,
        height: Int,
        config: VisionConfig
    ): NativeVisionResult
    external fun release(): Unit

    companion object {
        // nativeAvailable: false if .so missing (debug APK without native build)
        // App stays alive — native features simply disabled gracefully
        val nativeAvailable: Boolean = try {
            System.loadLibrary("vision_agent_native")
            true
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.w("VisionAgent", "vision_agent_native not loaded: ${e.message}")
            false
        }
    }
}

// ============================================================
// ScreenClassifier — Determines current screen type
// Pattern: Strategy + Chain of Responsibility
// ============================================================

@Singleton
class ScreenClassifier @Inject constructor() {

    private val classifiers: List<ScreenTypeClassifier> = listOf(
        DialogClassifier(),
        LoadingClassifier(),
        FormClassifier(),
        ErrorClassifier(),
        ListClassifier(),
        NavigationClassifier()
    )

    fun classify(result: NativeVisionResult): ScreenType {
        for (classifier in classifiers) {
            val type = classifier.classify(result)
            if (type != ScreenType.UNKNOWN) return type
        }
        return ScreenType.UNKNOWN
    }
}

interface ScreenTypeClassifier {
    fun classify(result: NativeVisionResult): ScreenType
}

class DialogClassifier : ScreenTypeClassifier {
    override fun classify(result: NativeVisionResult): ScreenType {
        val hasDialog = result.elements.any { it.type == UIElementType.DIALOG }
        val hasPopup = result.elements.any { it.type == UIElementType.POPUP }
        return if (hasDialog || hasPopup) ScreenType.DIALOG else ScreenType.UNKNOWN
    }
}

class LoadingClassifier : ScreenTypeClassifier {
    override fun classify(result: NativeVisionResult): ScreenType {
        val hasProgress = result.elements.any { it.type == UIElementType.PROGRESS_BAR }
        return if (hasProgress && result.elements.size < 5) ScreenType.LOADING else ScreenType.UNKNOWN
    }
}

class FormClassifier : ScreenTypeClassifier {
    override fun classify(result: NativeVisionResult): ScreenType {
        val textFieldCount = result.elements.count { it.type == UIElementType.TEXT_FIELD }
        return if (textFieldCount >= 2) ScreenType.FORM else ScreenType.UNKNOWN
    }
}

class ErrorClassifier : ScreenTypeClassifier {
    private val errorKeywords = setOf("error", "failed", "retry", "something went wrong")
    override fun classify(result: NativeVisionResult): ScreenType {
        val hasErrorText = result.elements.any { element ->
            element.text?.lowercase()?.let { text ->
                errorKeywords.any { keyword -> text.contains(keyword) }
            } == true
        }
        return if (hasErrorText) ScreenType.ERROR else ScreenType.UNKNOWN
    }
}

class ListClassifier : ScreenTypeClassifier {
    override fun classify(result: NativeVisionResult): ScreenType {
        val listItems = result.elements.count { it.type == UIElementType.LIST_ITEM }
        return if (listItems >= 3) ScreenType.LIST else ScreenType.UNKNOWN
    }
}

class NavigationClassifier : ScreenTypeClassifier {
    override fun classify(result: NativeVisionResult): ScreenType {
        val hasNavBar = result.elements.any { it.type == UIElementType.NAVIGATION_BAR }
        return if (hasNavBar) ScreenType.NAVIGATION else ScreenType.UNKNOWN
    }
}

// ============================================================
// ConfidenceScorer — Scores detection confidence
// ============================================================

@Singleton
class ConfidenceScorer @Inject constructor() {

    fun score(element: DetectedUIElement, context: VisionContext): Float {
        var score = element.confidence

        // Boost score for elements with associated text
        if (!element.text.isNullOrBlank()) score = minOf(1f, score * 1.1f)

        // Penalize very small elements (likely noise)
        val area = (element.bounds.right - element.bounds.left) *
                   (element.bounds.bottom - element.bounds.top)
        if (area < 100) score *= 0.5f

        // Boost for elements in expected layout positions
        if (isInExpectedPosition(element)) score = minOf(1f, score * 1.05f)

        return score
    }

    private fun isInExpectedPosition(element: DetectedUIElement): Boolean {
        // Buttons typically appear in lower 30% of screen
        return element.type == UIElementType.BUTTON &&
               element.bounds.top > 600  // Adjust based on screen height
    }
}
