package com.visionagent.core.event

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// ============================================================
// EventBus — Central Message Bus for all Agent Modules
// Pattern: Event-Driven Architecture
// Thread-safe | Non-blocking | Backpressure handled
// ============================================================

/**
 * Base sealed class for all Agent Events.
 * Every module communicates ONLY through these events.
 * This ensures complete decoupling between modules.
 */
sealed class AgentEvent {
    abstract val timestamp: Long
    abstract val sessionId: String
}

// ---- Screen Capture Events ----
data class FrameCapturedEvent(
    val frameId: Long,
    val frameData: ByteArray,
    val width: Int,
    val height: Int,
    val fps: Float,
    override val timestamp: Long = System.currentTimeMillis(),
    override val sessionId: String
) : AgentEvent()

data class FrameDroppedEvent(
    val frameId: Long,
    val reason: FrameDropReason,
    override val timestamp: Long = System.currentTimeMillis(),
    override val sessionId: String
) : AgentEvent()

enum class FrameDropReason { QUEUE_FULL, PROCESSING_TIMEOUT, LOW_MEMORY }

// ---- Vision Events ----
data class UIElementDetectedEvent(
    val elements: List<DetectedUIElement>,
    val screenType: ScreenType,
    val confidence: Float,
    override val timestamp: Long = System.currentTimeMillis(),
    override val sessionId: String
) : AgentEvent()

@kotlinx.serialization.Serializable
data class DetectedUIElement(
    val type: UIElementType,
    val bounds: Rect,
    val text: String? = null,
    val confidence: Float,
    val attributes: Map<String, String> = emptyMap()
)

@kotlinx.serialization.Serializable
data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int)

enum class UIElementType {
    BUTTON, TEXT_FIELD, ICON, POPUP, DIALOG,
    NAVIGATION_BAR, TOOLBAR, LIST_ITEM, IMAGE,
    CHECKBOX, RADIO, TOGGLE, PROGRESS_BAR, UNKNOWN
}

enum class ScreenType {
    HOME, LOADING, DIALOG, ERROR, FORM,
    LIST, DETAIL, NAVIGATION, UNKNOWN
}

// ---- OCR Events ----
data class OCRCompletedEvent(
    val text: String,
    val blocks: List<TextBlock>,
    val confidence: Float,
    val language: String,
    val isCached: Boolean = false,
    override val timestamp: Long = System.currentTimeMillis(),
    override val sessionId: String
) : AgentEvent()

data class TextBlock(
    val text: String,
    val bounds: Rect,
    val confidence: Float,
    val language: String
)

// ---- Rule Engine Events ----
data class RuleEvaluatedEvent(
    val ruleId: String,
    val ruleName: String,
    val matched: Boolean,
    val priority: Int,
    val decision: AgentDecision?,
    override val timestamp: Long = System.currentTimeMillis(),
    override val sessionId: String
) : AgentEvent()

data class AgentDecision(
    val actionType: ActionType,
    val target: DetectedUIElement?,
    val parameters: Map<String, Any> = emptyMap(),
    val confidence: Float,
    val reasoning: String
)

// ---- Planner Events ----
data class PlanCreatedEvent(
    val planId: String,
    val steps: List<PlanStep>,
    val estimatedDuration: Long,
    override val timestamp: Long = System.currentTimeMillis(),
    override val sessionId: String
) : AgentEvent()

data class PlanStep(
    val stepId: String,
    val action: ActionType,
    val target: DetectedUIElement?,
    val expectedOutcome: String,
    val fallback: PlanStep?
)

// ---- Action Events ----
data class ActionExecutedEvent(
    val actionId: String,
    val actionType: ActionType,
    val success: Boolean,
    val durationMs: Long,
    val errorMessage: String? = null,
    override val timestamp: Long = System.currentTimeMillis(),
    override val sessionId: String
) : AgentEvent()

enum class ActionType {
    TAP, LONG_PRESS, DOUBLE_TAP, SCROLL_UP, SCROLL_DOWN,
    SWIPE_LEFT, SWIPE_RIGHT, TEXT_INPUT, WAIT, RETRY,
    CANCEL, NAVIGATE_BACK, SCREENSHOT, NONE
}

// ---- Memory Events ----
data class MemoryStoredEvent(
    val key: String,
    val memoryType: MemoryType,
    override val timestamp: Long = System.currentTimeMillis(),
    override val sessionId: String
) : AgentEvent()

enum class MemoryType { SHORT_TERM, LONG_TERM, SESSION, SCREEN, ACTION, LEARNING, PREFERENCE }

// ---- Recovery Events ----
data class RecoveryTriggeredEvent(
    val recoveryType: RecoveryType,
    val reason: String,
    val attemptNumber: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val sessionId: String
) : AgentEvent()

enum class RecoveryType {
    UNKNOWN_SCREEN, MISSING_ELEMENT, UNEXPECTED_DIALOG,
    LOADING_TIMEOUT, NETWORK_FAILURE, INVALID_STATE,
    MAX_RETRIES_EXCEEDED, SAFE_ROLLBACK
}

// ---- Error Events ----
data class AgentErrorEvent(
    val errorCode: AgentErrorCode,
    val message: String,
    val stackTrace: String? = null,
    val isFatal: Boolean = false,
    override val timestamp: Long = System.currentTimeMillis(),
    override val sessionId: String
) : AgentEvent()

enum class AgentErrorCode {
    CAPTURE_FAILED, VISION_FAILED, OCR_FAILED, RULE_ERROR,
    PLAN_FAILED, ACTION_FAILED, MEMORY_ERROR, RECOVERY_FAILED,
    PERMISSION_DENIED, NATIVE_CRASH, UNKNOWN
}

// ---- Performance Events ----
data class PerformanceMetricEvent(
    val module: String,
    val operationName: String,
    val durationMs: Long,
    val memoryUsageBytes: Long,
    val cpuPercent: Float,
    override val timestamp: Long = System.currentTimeMillis(),
    override val sessionId: String
) : AgentEvent()

// ---- State Events ----
data class StateChangedEvent(
    val previousState: AgentState,
    val currentState: AgentState,
    val trigger: String,
    override val timestamp: Long = System.currentTimeMillis(),
    override val sessionId: String
) : AgentEvent()

// Alias used by CrashReplaySystem
typealias AgentStateChangedEvent = StateChangedEvent

// Session lifecycle events
data class SessionStartEvent(
    val agentVersion: String = "",
    override val timestamp: Long = System.currentTimeMillis(),
    override val sessionId: String
) : AgentEvent()

data class SessionEndEvent(
    val totalFrames: Long = 0,
    val totalActions: Long = 0,
    override val timestamp: Long = System.currentTimeMillis(),
    override val sessionId: String
) : AgentEvent()

// Buffered event record used by CrashReplaySystem ring buffer
data class BufferedEvent(
    val eventType: String,
    val timestampMs: Long,
    val payload: String = ""
)

enum class AgentState {
    IDLE, CAPTURING, ANALYZING, PLANNING, EXECUTING,
    RECOVERING, WAITING, PAUSED, ERROR, TERMINATED
}

// ============================================================
// EventBus Implementation
// Thread-safe, coroutine-based, backpressure-aware
// ============================================================
@Singleton
class AgentEventBus @Inject constructor() {

    // FIX L5-5: busScope removed — was only used by the dropped emit() fallback.

    // SharedFlow with replay=0 for real-time events
    // extraBufferCapacity=256 to handle bursts without blocking
    // FIX INLINE-1: inline functions (subscribe/subscribeFiltered) cannot access
    // private members. Changed to internal so inlined call-site can access it.
    @PublishedApi
    internal val _events = MutableSharedFlow<AgentEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    /**
     * Publish an event to the bus.
     * Non-blocking — uses tryEmit for fire-and-forget.
     * Falls back to coroutine emit if buffer is full.
     */
    fun publish(event: AgentEvent) {
        // FIX L5-5: Removed `busScope.launch { _events.emit(event) }` fallback.
        //
        // The old fallback launched a NEW coroutine every time tryEmit() returned false
        // (buffer full). Each coroutine called _events.emit() which suspends until space
        // is available. Under sustained load (100 events/sec, buffer drains at 80/sec):
        // 20 suspended coroutines/sec accumulate → 600 after 30 seconds → OOM.
        //
        // The buffer is configured with DROP_OLDEST overflow — so when the buffer is full,
        // the oldest event is dropped automatically. tryEmit() with DROP_OLDEST always
        // returns true because it drops rather than failing. The else branch was dead code.
        //
        // If DROP_OLDEST ever returns false (future API change), simply dropping is correct:
        // an event bus for real-time agent events should prefer recency over completeness.
        _events.tryEmit(event)   // DROP_OLDEST — always succeeds, no fallback needed
    }

    /**
     * Subscribe to a specific event type.
     * Uses reified type param for clean filtering.
     */
    // FIX NC-8: Return type changed from SharedFlow<T> to Flow<T>.
    // filterIsInstance<T>() returns Flow<T>, NOT SharedFlow<T>.
    // Casting Flow<T> as SharedFlow<T> is an unchecked cast — compiles fine but
    // any caller accessing SharedFlow-specific APIs (.replayCache, .subscriptionCount)
    // would get ClassCastException at runtime.
    // All callers use .onEach{}.launchIn() which works on any Flow<T>, so this
    // type correction is safe and backward-compatible.
    inline fun <reified T : AgentEvent> subscribe(): Flow<T> {
        return _events.filterIsInstance<T>()
    }

    /**
     * Subscribe with additional filter predicate.
     */
    inline fun <reified T : AgentEvent> subscribeFiltered(
        crossinline predicate: (T) -> Boolean
    ): Flow<T> = _events.filterIsInstance<T>().filter { predicate(it) }

    /**
     * Subscribe to events from a specific session.
     */
    fun subscribeToSession(sessionId: String) =
        _events.filter { it.sessionId == sessionId }
}
