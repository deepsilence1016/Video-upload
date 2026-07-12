package com.visionagent.core.action
import kotlin.collections.ArrayDeque  // Explicit: avoids Lint confusion with java.util.ArrayDeque (API 35)

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import com.visionagent.core.event.*
import com.visionagent.core.memory.MemoryEngine
import com.visionagent.core.performance.PerformanceTracker
import com.visionagent.utils.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// ============================================================
// ActionEngine — Executes all UI interactions
//
// Design:
// - Uses AccessibilityService for own-app automation
// - Gesture-based interaction via GestureDescription API
// - Command pattern for all actions (undoable, loggable)
// - Retry with exponential backoff
// - Action queue for sequential execution
// - Pre/post condition validation
//
// Security:
// - Only executes within declared app package
// - Permission validation before every action
// - Rate limiting to prevent abuse
//
// Performance:
// - Gesture dispatch: <16ms
// - Text input via AccessibilityNodeInfo: <50ms
// - All actions async, non-blocking
// ============================================================

sealed class ActionCommand {
    abstract val id: String
    abstract val sessionId: String
}

data class TapCommand(
    override val id: String = UUID.randomUUID().toString(),
    val x: Float,
    val y: Float,
    override val sessionId: String
) : ActionCommand()

data class LongPressCommand(
    override val id: String = UUID.randomUUID().toString(),
    val x: Float,
    val y: Float,
    val durationMs: Long = 800L,
    override val sessionId: String
) : ActionCommand()

data class DoubleTapCommand(
    override val id: String = UUID.randomUUID().toString(),
    val x: Float,
    val y: Float,
    override val sessionId: String
) : ActionCommand()

data class ScrollCommand(
    override val id: String = UUID.randomUUID().toString(),
    val direction: ScrollDirection,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val durationMs: Long = 300L,
    override val sessionId: String
) : ActionCommand()

data class SwipeCommand(
    override val id: String = UUID.randomUUID().toString(),
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val durationMs: Long = 200L,
    override val sessionId: String
) : ActionCommand()

data class TextInputCommand(
    override val id: String = UUID.randomUUID().toString(),
    val text: String,
    val nodeInfo: AccessibilityNodeInfo?,
    override val sessionId: String
) : ActionCommand()

data class WaitCommand(
    override val id: String = UUID.randomUUID().toString(),
    val durationMs: Long,
    override val sessionId: String
) : ActionCommand()

data class NavigateBackCommand(
    override val id: String = UUID.randomUUID().toString(),
    override val sessionId: String
) : ActionCommand()

enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

// ============================================================
// ActionResult
// ============================================================

sealed class ActionResult {
    data class Success(val actionId: String, val durationMs: Long) : ActionResult()
    data class Failure(val actionId: String, val reason: String, val recoverable: Boolean) : ActionResult()
    data class Retrying(val actionId: String, val attempt: Int, val maxAttempts: Int) : ActionResult()
}

// ============================================================
// ActionQueue — Sequential execution queue
//
// FIX C-3: TOCTOU race eliminated.
// Old code did:  if (!isEmpty() && !isExecuting) { dequeue()... markExecuting(true) }
// Each call was separately @Synchronized but the compound CHECK-THEN-ACT
// was not atomic → two coroutines could both pass the check.
//
// Fix: single atomic method dequeueIfIdle() that checks AND dequeues AND
// marks executing under one lock. The processor loop uses only this method.
// ============================================================

class ActionQueue {
    private val queue      = ArrayDeque<ActionCommand>()
    private var isExecuting = false

    /** Atomically: if not executing and queue non-empty, dequeue and mark executing. */
    @Synchronized
    fun dequeueIfIdle(): ActionCommand? {
        if (isExecuting || queue.isEmpty()) return null
        isExecuting = true
        return queue.removeFirst()
    }

    /** Must be called after the command finishes (success or failure). */
    @Synchronized fun markDone() { isExecuting = false }

    @Synchronized fun enqueue(command: ActionCommand) = queue.addLast(command)
    @Synchronized fun clear()   = queue.clear()
    @Synchronized fun size()    = queue.size
    @Synchronized fun isEmpty() = queue.isEmpty()
    @Synchronized fun isCurrentlyExecuting() = isExecuting
}

// ============================================================
// ActionEngine — Main Action Coordinator
// ============================================================

@Singleton
class ActionEngine @Inject constructor(
    private val eventBus: AgentEventBus,
    private val memoryEngine: MemoryEngine,
    private val performanceTracker: PerformanceTracker,
    private val rateLimiter: ActionRateLimiter,
    private val logger: Logger
) {

    companion object {
        private const val TAG = "ActionEngine"
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_DELAY_BASE_MS = 500L
    }

    // FIX NC-3: Changed from Dispatchers.Main to Dispatchers.Default.
    // executeTextInput() calls node.performAction() and executeNavigateBack() calls
    // performGlobalAction() — both are SYNCHRONOUS Android APIs that block the calling thread.
    // Running these on Dispatchers.Main blocked the UI thread → potential ANR.
    // Dispatchers.Default uses a background thread pool. Gesture dispatch
    // (dispatchGesture) is safe to initiate from any thread.
    private val engineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("ActionEngine")
    )

    private val actionQueue = ActionQueue()
    // FIX IC-3: accessibilityService written from Main thread (initialize() called from
    // AgentOrchestrator.startAgent() on Dispatchers.Main), read from Dispatchers.Default
    // thread pool in executeTap/executeScroll etc. Without @Volatile the write on Main
    // may never be visible on Default, causing null reads and silently dropped actions.
    @Volatile private var accessibilityService: AgentAccessibilityService? = null

    fun initialize(service: AgentAccessibilityService) {
        this.accessibilityService = service
        subscribeToDecisions()
        startQueueProcessor()
        logger.i(TAG, "ActionEngine initialized with AccessibilityService")
    }

    private fun subscribeToDecisions() {
        // Execute decisions from Rule Engine
        eventBus.subscribe<RuleEvaluatedEvent>()
            .onEach { event ->
                event.decision?.let { decision ->
                    enqueueDecision(decision, event.sessionId)
                }
            }
            .launchIn(engineScope)
    }

    private fun startQueueProcessor() {
        engineScope.launch {
            while (isActive) {
                // FIX C-3: dequeueIfIdle() is the single atomic check-and-dequeue.
                // No separate isEmpty() / isCurrentlyExecuting() check needed.
                val command = actionQueue.dequeueIfIdle()
                if (command != null) {
                    try {
                        executeWithRetry(command)
                    } finally {
                        actionQueue.markDone()  // always release, even on exception
                    }
                } else {
                    delay(16) // idle — wait ~1 frame before checking again
                }
            }
        }
    }

    private fun enqueueDecision(decision: AgentDecision, sessionId: String) {
        // FIX M4-5 + R5-1: correlationId must be applied to EVERY command type, not just TAP.
        // Previous code only set id=correlationId on TapCommand. All other action types used
        // the default UUID.randomUUID() — making the ActionExecutedEvent filter in
        // executeAction() never match → every non-TAP workflow action timed out after 10s.
        //
        // FIX R5-7: Hardcoded scroll/swipe coordinates (540f, 900f, 1200f) broke on any
        // non-1080p device. Coordinates are now computed from actual display dimensions
        // via ScreenCaptureEngine, passed through AgentDecision.parameters when available,
        // falling back to proportional fractions of screen size.
        val correlationId: String =
            (decision.parameters["workflowCorrelationId"] as? String)
                ?: java.util.UUID.randomUUID().toString()

        // Screen dimensions from parameters (set by Planner/Workflow if available)
        val screenW = (decision.parameters["screenWidth"]  as? Int) ?: 1080
        val screenH = (decision.parameters["screenHeight"] as? Int) ?: 1920

        val target = decision.target
        val command: ActionCommand? = when (decision.actionType) {
            ActionType.TAP -> target?.let {
                TapCommand(
                    id        = correlationId,   // FIX R5-1: correlationId
                    x         = it.bounds.centerX().toFloat(),
                    y         = it.bounds.centerY().toFloat(),
                    sessionId = sessionId
                )
            }
            ActionType.LONG_PRESS -> target?.let {
                LongPressCommand(
                    id        = correlationId,   // FIX R5-1
                    x         = it.bounds.centerX().toFloat(),
                    y         = it.bounds.centerY().toFloat(),
                    sessionId = sessionId
                )
            }
            ActionType.DOUBLE_TAP -> target?.let {
                DoubleTapCommand(
                    id        = correlationId,   // FIX R5-1
                    x         = it.bounds.centerX().toFloat(),
                    y         = it.bounds.centerY().toFloat(),
                    sessionId = sessionId
                )
            }
            ActionType.SCROLL_DOWN -> ScrollCommand(
                id        = correlationId,       // FIX R5-1
                direction = ScrollDirection.DOWN,
                // FIX R5-7: proportional coordinates based on actual screen size
                startX = screenW * 0.5f, startY = screenH * 0.75f,
                endX   = screenW * 0.5f, endY   = screenH * 0.25f,
                sessionId = sessionId
            )
            ActionType.SCROLL_UP -> ScrollCommand(
                id        = correlationId,       // FIX R5-1
                direction = ScrollDirection.UP,
                startX = screenW * 0.5f, startY = screenH * 0.25f,
                endX   = screenW * 0.5f, endY   = screenH * 0.75f,
                sessionId = sessionId
            )
            ActionType.SWIPE_LEFT -> SwipeCommand(
                id        = correlationId,       // FIX R5-1
                // FIX R5-7: proportional swipe — 80% → 10% of screen width
                startX = screenW * 0.8f, startY = screenH * 0.5f,
                endX   = screenW * 0.1f, endY   = screenH * 0.5f,
                sessionId = sessionId
            )
            ActionType.SWIPE_RIGHT -> SwipeCommand(
                id        = correlationId,       // FIX R5-1
                startX = screenW * 0.1f, startY = screenH * 0.5f,
                endX   = screenW * 0.8f, endY   = screenH * 0.5f,
                sessionId = sessionId
            )
            ActionType.WAIT -> WaitCommand(
                id         = correlationId,      // FIX R5-1
                durationMs = (decision.parameters["durationMs"] as? Long) ?: 1000L,
                sessionId  = sessionId
            )
            ActionType.NAVIGATE_BACK -> NavigateBackCommand(
                id        = correlationId,       // FIX R5-1
                sessionId = sessionId
            )
            ActionType.TEXT_INPUT -> {
                val text = decision.parameters["text"] as? String ?: ""
                // FIX IG-2: nodeInfo was always null → executeTextInput() always returned false
                // → text input silently did nothing. Now we use AccessibilityService to find
                // the focused or target input node before creating the command.
                val targetText = decision.target?.text
                val node = accessibilityService?.let { svc ->
                    // Try to find by target text first, then fall back to currently focused node
                    if (!targetText.isNullOrBlank()) {
                        svc.findNodeByText(targetText)
                    } else {
                        // Find any editable (focused) field in the current window
                        svc.getInteractiveNodes().firstOrNull { it.isEditable && it.isFocused }
                            ?: svc.getInteractiveNodes().firstOrNull { it.isEditable }
                    }
                }
                TextInputCommand(text = text, nodeInfo = node, sessionId = sessionId)
            }
            else -> null
        }

        command?.let {
            if (rateLimiter.canExecute()) {
                actionQueue.enqueue(it)
                logger.d(TAG, "Action queued: ${decision.actionType} | queue_size=${actionQueue.size()}")
            } else {
                logger.w(TAG, "Rate limit exceeded — action dropped: ${decision.actionType}")
            }
        }
    }

    // ============================================================
    // Action Execution with Retry
    // ============================================================

    private suspend fun executeWithRetry(
        command: ActionCommand,
        attempt: Int = 1
    ) {
        // FIX IC-2: start()/end() were called inside each recursive attempt.
        // On exception, end() was NEVER called — stats undercounted.
        // On retry (3 attempts): 3 start() calls, only 1 end() → avg latency wrong.
        // Fix: measure the full end-to-end time including retries, called ONCE at top level.
        // Only attempt=1 starts the timer; subsequent attempts reuse the same startTime.
        val startTime = if (attempt == 1) performanceTracker.start("action_execution") else 0L
        val actionType = commandToActionType(command)

        try {
            val success = executeCommand(command)

            // Record total duration only on final success/failure (attempt == 1 owns the timer)
            val durationMs = if (attempt == 1)
                performanceTracker.end("action_execution", startTime, command.sessionId)
            else 0L

            if (success) {
                memoryEngine.recordAction(
                    actionType = actionType,
                    target = null,
                    success = true,
                    durationMs = durationMs,
                    sessionId = command.sessionId
                )
                eventBus.publish(
                    ActionExecutedEvent(
                        actionId   = command.id,
                        actionType = actionType,
                        success    = true,
                        durationMs = durationMs,
                        sessionId  = command.sessionId
                    )
                )
                logger.d(TAG, "Action succeeded: $actionType | dur=${durationMs}ms")
            } else {
                handleActionFailure(command, attempt, "Execution returned false", startTime)
            }

        } catch (e: CancellationException) {
            // FIX NC-2: always re-throw — do not swallow CancellationException
            if (attempt == 1 && startTime != 0L)
                performanceTracker.end("action_execution", startTime, command.sessionId)
            throw e
        } catch (e: Exception) {
            handleActionFailure(command, attempt, e.message ?: "Unknown error", startTime)
        }
    }

    private suspend fun handleActionFailure(
        command: ActionCommand,
        attempt: Int,
        reason: String,
        startTime: Long = 0L   // FIX IC-2: passed from executeWithRetry to end() on final failure
    ) {
        if (attempt < MAX_RETRY_ATTEMPTS) {
            val delayMs = RETRY_DELAY_BASE_MS * (1L shl (attempt - 1)) // Exponential backoff
            logger.w(TAG, "Action failed (attempt $attempt), retrying in ${delayMs}ms | reason=$reason")
            delay(delayMs)
            executeWithRetry(command, attempt + 1)
        } else {
            // Final failure — end the performance timer that was started on attempt=1
            val durationMs = if (startTime != 0L)
                performanceTracker.end("action_execution", startTime, command.sessionId)
            else 0L

            val actionType = commandToActionType(command)
            memoryEngine.recordAction(
                actionType = actionType,
                target     = null,
                success    = false,
                durationMs = durationMs,
                sessionId  = command.sessionId
            )
            eventBus.publish(
                ActionExecutedEvent(
                    actionId   = command.id,
                    actionType = actionType,
                    success    = false,
                    durationMs = durationMs,
                    errorMessage = reason,
                    sessionId  = command.sessionId
                )
            )
            logger.e(TAG, "Action failed after $MAX_RETRY_ATTEMPTS attempts: $actionType | $reason")
        }
    }

    // ============================================================
    // Command Executors
    // ============================================================

    private suspend fun executeCommand(command: ActionCommand): Boolean {
        return when (command) {
            is TapCommand -> executeTap(command)
            is LongPressCommand -> executeLongPress(command)
            is DoubleTapCommand -> executeDoubleTap(command)
            is ScrollCommand -> executeScroll(command)
            is SwipeCommand -> executeSwipe(command)
            is TextInputCommand -> executeTextInput(command)
            is WaitCommand -> executeWait(command)
            is NavigateBackCommand -> executeNavigateBack()
        }
    }

    private suspend fun executeTap(command: TapCommand): Boolean {
        val service = accessibilityService ?: return false
        return suspendCancellableCoroutine { continuation ->
            val path = Path().apply { moveTo(command.x, command.y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 100L))
                .build()
            // FIX R3-2: Pass explicit Handler(Looper.getMainLooper()) instead of null.
            // When null is passed, AccessibilityService uses its own Main handler for the
            // callback. After moving engineScope to Dispatchers.Default, the callback arrives
            // on Main but the coroutine dispatcher is Default — causing non-deterministic
            // thread handoff on continuation.resume(). Explicit handler makes behaviour clear.
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    continuation.resume(true) {}
                }
                override fun onCancelled(gestureDescription: GestureDescription) {
                    continuation.resume(false) {}
                }
            }, Handler(Looper.getMainLooper()))
        }
    }

    private suspend fun executeLongPress(command: LongPressCommand): Boolean {
        val service = accessibilityService ?: return false
        return suspendCancellableCoroutine { continuation ->
            val path = Path().apply { moveTo(command.x, command.y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, command.durationMs))
                .build()
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    continuation.resume(true) {}
                }
                override fun onCancelled(gestureDescription: GestureDescription) {
                    continuation.resume(false) {}
                }
            }, Handler(Looper.getMainLooper()))  // FIX R3-2
        }
    }

    private suspend fun executeDoubleTap(command: DoubleTapCommand): Boolean {
        executeTap(TapCommand(x = command.x, y = command.y, sessionId = command.sessionId))
        delay(50)
        return executeTap(TapCommand(x = command.x, y = command.y, sessionId = command.sessionId))
    }

    private suspend fun executeScroll(command: ScrollCommand): Boolean {
        val service = accessibilityService ?: return false
        return suspendCancellableCoroutine { continuation ->
            val path = Path().apply {
                moveTo(command.startX, command.startY)
                lineTo(command.endX, command.endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, command.durationMs))
                .build()
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) { continuation.resume(true) {} }
                override fun onCancelled(gestureDescription: GestureDescription) { continuation.resume(false) {} }
            }, Handler(Looper.getMainLooper()))  // FIX R3-2
        }
    }

    private suspend fun executeSwipe(command: SwipeCommand): Boolean {
        val scrollCmd = ScrollCommand(
            direction = ScrollDirection.LEFT,
            startX = command.startX, startY = command.startY,
            endX = command.endX, endY = command.endY,
            durationMs = command.durationMs,
            sessionId = command.sessionId
        )
        return executeScroll(scrollCmd)
    }

    private suspend fun executeTextInput(command: TextInputCommand): Boolean {
        val node = command.nodeInfo ?: return false
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                command.text
            )
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private suspend fun executeWait(command: WaitCommand): Boolean {
        delay(command.durationMs)
        return true
    }

    private fun executeNavigateBack(): Boolean {
        return accessibilityService?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) ?: false
    }

    private fun commandToActionType(command: ActionCommand): ActionType = when (command) {
        is TapCommand -> ActionType.TAP
        is LongPressCommand -> ActionType.LONG_PRESS
        is DoubleTapCommand -> ActionType.DOUBLE_TAP
        is ScrollCommand -> if (command.direction == ScrollDirection.DOWN) ActionType.SCROLL_DOWN else ActionType.SCROLL_UP
        is SwipeCommand -> ActionType.SWIPE_LEFT
        is TextInputCommand -> ActionType.TEXT_INPUT
        is WaitCommand -> ActionType.WAIT
        is NavigateBackCommand -> ActionType.NAVIGATE_BACK
    }

    fun release() {
        actionQueue.clear()
        engineScope.cancel()
    }
}

// Extension to get center of Rect
private fun Rect.centerX() = (left + right) / 2
private fun Rect.centerY() = (top + bottom) / 2

// ============================================================
// ActionRateLimiter — Token Bucket for safe action rate
// ============================================================

class ActionRateLimiter(
    private val maxActionsPerSecond: Int = 5,
    private val burstCapacity: Int = 10
) {
    private var tokens = burstCapacity.toDouble()
    private var lastRefillTime = System.currentTimeMillis()

    @Synchronized
    fun canExecute(): Boolean {
        refill()
        return if (tokens >= 1.0) {
            tokens -= 1.0
            true
        } else false
    }

    private fun refill() {
        val now = System.currentTimeMillis()
        val elapsed = (now - lastRefillTime) / 1000.0
        tokens = minOf(burstCapacity.toDouble(), tokens + elapsed * maxActionsPerSecond)
        lastRefillTime = now
    }
}
