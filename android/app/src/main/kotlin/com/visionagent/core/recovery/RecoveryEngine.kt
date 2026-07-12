package com.visionagent.core.recovery

import com.visionagent.core.event.*
import com.visionagent.core.memory.MemoryEngine
import com.visionagent.core.rule.AgentStateMachine
import com.visionagent.core.performance.PerformanceTracker
import com.visionagent.utils.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

// ============================================================
// RecoveryEngine — Fault Tolerance & Self-Healing
//
// Responsibilities:
// - Detect error conditions (unknown screen, timeout, etc.)
// - Select appropriate recovery strategy
// - Execute recovery with bounded retry count
// - Rollback to known-good state if recovery fails
// - Publish recovery events for logging/monitoring
//
// Recovery Strategies (Priority order):
// 1. WAIT_AND_RETRY       — For transient errors
// 2. DISMISS_DIALOG       — For unexpected popups
// 3. NAVIGATE_BACK        — For unknown screens
// 4. SCROLL_TO_FIND       — For missing elements
// 5. RESTART_FLOW         — For corrupted state
// 6. SAFE_ROLLBACK        — Last resort
// 7. TERMINATE            — If all recovery fails
//
// Pattern: Chain of Responsibility + Strategy
// ============================================================

data class RecoveryContext(
    val errorType: RecoveryType,
    val consecutiveErrors: Int,
    val lastScreenType: ScreenType,
    val lastAction: ActionType?,
    val sessionId: String
)

interface RecoveryStrategy {
    val name: String
    val maxAttempts: Int
    fun canHandle(context: RecoveryContext): Boolean
    suspend fun execute(context: RecoveryContext, eventBus: AgentEventBus): RecoveryResult
}

sealed class RecoveryResult {
    object Success : RecoveryResult()
    data class Failed(val reason: String) : RecoveryResult()
    object Escalate : RecoveryResult()  // Try next strategy
}

// ============================================================
// Recovery Strategies
// ============================================================

class WaitAndRetryStrategy : RecoveryStrategy {
    override val name = "WaitAndRetry"
    override val maxAttempts = 3

    override fun canHandle(context: RecoveryContext): Boolean =
        context.errorType in listOf(
            RecoveryType.LOADING_TIMEOUT,
            RecoveryType.NETWORK_FAILURE,
            RecoveryType.MISSING_ELEMENT
        )

    override suspend fun execute(
        context: RecoveryContext,
        eventBus: AgentEventBus
    ): RecoveryResult {
        val waitMs = 2000L * context.consecutiveErrors  // Progressive wait
        delay(waitMs)
        return RecoveryResult.Success  // Let main flow retry
    }
}

class DismissDialogStrategy : RecoveryStrategy {
    override val name = "DismissDialog"
    override val maxAttempts = 2

    override fun canHandle(context: RecoveryContext): Boolean =
        context.errorType == RecoveryType.UNEXPECTED_DIALOG ||
        context.lastScreenType == ScreenType.DIALOG

    override suspend fun execute(
        context: RecoveryContext,
        eventBus: AgentEventBus
    ): RecoveryResult {
        eventBus.publish(
            RuleEvaluatedEvent(
                ruleId = "recovery_dismiss",
                ruleName = "DismissDialog",
                matched = true,
                priority = Int.MAX_VALUE,
                decision = AgentDecision(
                    actionType = ActionType.TAP,
                    target = DetectedUIElement(
                        type = UIElementType.BUTTON,
                        bounds = Rect(0, 0, 100, 50),
                        text = "OK",
                        confidence = 0.9f
                    ),
                    confidence = 0.9f,
                    reasoning = "Recovery: Dismiss unexpected dialog"
                ),
                sessionId = context.sessionId
            )
        )
        delay(500)
        return RecoveryResult.Success
    }
}

class NavigateBackStrategy : RecoveryStrategy {
    override val name = "NavigateBack"
    override val maxAttempts = 3

    override fun canHandle(context: RecoveryContext): Boolean =
        context.errorType == RecoveryType.UNKNOWN_SCREEN

    override suspend fun execute(
        context: RecoveryContext,
        eventBus: AgentEventBus
    ): RecoveryResult {
        eventBus.publish(
            RuleEvaluatedEvent(
                ruleId = "recovery_back",
                ruleName = "NavigateBack",
                matched = true,
                priority = Int.MAX_VALUE,
                decision = AgentDecision(
                    actionType = ActionType.NAVIGATE_BACK,
                    target = null,
                    confidence = 1.0f,
                    reasoning = "Recovery: Navigate back from unknown screen"
                ),
                sessionId = context.sessionId
            )
        )
        delay(1000)
        return RecoveryResult.Success
    }
}

class SafeRollbackStrategy : RecoveryStrategy {
    override val name = "SafeRollback"
    override val maxAttempts = 1

    override fun canHandle(context: RecoveryContext): Boolean =
        context.errorType == RecoveryType.SAFE_ROLLBACK ||
        context.consecutiveErrors >= 5

    override suspend fun execute(
        context: RecoveryContext,
        eventBus: AgentEventBus
    ): RecoveryResult {
        // Repeatedly navigate back until known screen
        repeat(5) {
            eventBus.publish(
                RuleEvaluatedEvent(
                    ruleId = "recovery_rollback_$it",
                    ruleName = "SafeRollback",
                    matched = true,
                    priority = Int.MAX_VALUE,
                    decision = AgentDecision(
                        actionType = ActionType.NAVIGATE_BACK,
                        target = null,
                        confidence = 1.0f,
                        reasoning = "Recovery: Safe rollback step $it"
                    ),
                    sessionId = context.sessionId
                )
            )
            delay(500)
        }
        return RecoveryResult.Success
    }
}

// ============================================================
// RecoveryEngine — Strategy Chain Coordinator
// ============================================================

@Singleton
class RecoveryEngine @Inject constructor(
    private val eventBus: AgentEventBus,
    private val memoryEngine: MemoryEngine,
    private val performanceTracker: PerformanceTracker,
    private val logger: Logger
) {

    companion object {
        private const val TAG = "RecoveryEngine"
        private const val MAX_GLOBAL_RECOVERY_ATTEMPTS = 10
    }

    private val engineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("RecoveryEngine")
    )

    // Ordered chain: try strategies in priority order
    private val strategyChain: List<RecoveryStrategy> = listOf(
        DismissDialogStrategy(),
        WaitAndRetryStrategy(),
        NavigateBackStrategy(),
        SafeRollbackStrategy()
    )

    // FIX R3-5: Both fields are written by concurrent coroutines on Dispatchers.Default.
    // subscribeToErrors() and subscribeToActions() run on different coroutines in the same
    // thread pool. Plain `var Int` has no atomic increment guarantee and no @Volatile visibility.
    // consecutiveErrors.incrementAndGet() (read-modify-write) was a data race → counts could be lost.
    // totalRecoveryAttempts had the same issue — the "< MAX_ATTEMPTS" guard could be bypassed.
    private val totalRecoveryAttempts = AtomicInteger(0)
    private val consecutiveErrors     = AtomicInteger(0)

    fun initialize() {
        subscribeToErrors()
        subscribeToActions()
        logger.i(TAG, "RecoveryEngine initialized | strategies=${strategyChain.size}")
    }

    private fun subscribeToErrors() {
        eventBus.subscribe<AgentErrorEvent>()
            .onEach { event ->
                if (!event.isFatal) {
                    // FIX R4-3: `consecutiveErrors++` does not compile on AtomicInteger.
                    // Kotlin has no `inc()` operator for AtomicInteger — the compiler
                    // would try to box and unbox, losing atomicity entirely (or fail outright).
                    // Must use .incrementAndGet() explicitly.
                    consecutiveErrors.incrementAndGet()
                    triggerRecovery(
                        type = mapErrorToRecoveryType(event.errorCode),
                        sessionId = event.sessionId
                    )
                } else {
                    handleFatalError(event)
                }
            }
            .launchIn(engineScope)
    }

    private fun subscribeToActions() {
        eventBus.subscribe<ActionExecutedEvent>()
            .onEach { event ->
                if (event.success) {
                    consecutiveErrors.set(0)  // Reset on success — FIX R3-5
                }
            }
            .launchIn(engineScope)
    }

    private suspend fun triggerRecovery(type: RecoveryType, sessionId: String) {
        if (totalRecoveryAttempts.get() >= MAX_GLOBAL_RECOVERY_ATTEMPTS) {
            logger.e(TAG, "Max recovery attempts reached — terminating")
            eventBus.publish(
                AgentErrorEvent(
                    errorCode = AgentErrorCode.RECOVERY_FAILED,
                    message = "Recovery failed after $MAX_GLOBAL_RECOVERY_ATTEMPTS attempts",
                    isFatal = true,
                    sessionId = sessionId
                )
            )
            return
        }

        totalRecoveryAttempts.incrementAndGet()
        val lastScreen = memoryEngine.screenMemory.getLast()?.screenType ?: ScreenType.UNKNOWN
        val lastAction = memoryEngine.actionMemory.getRecentActions(1).firstOrNull()?.actionType

        val context = RecoveryContext(
            errorType = type,
            consecutiveErrors = consecutiveErrors.get(),
            lastScreenType = lastScreen,
            lastAction = lastAction,
            sessionId = sessionId
        )

        eventBus.publish(
            RecoveryTriggeredEvent(
                recoveryType = type,
                reason = "consecutive_errors=${consecutiveErrors.get()}",
                attemptNumber = totalRecoveryAttempts.get(),
                sessionId = sessionId
            )
        )

        logger.i(TAG, "Recovery triggered: $type | attempt=${totalRecoveryAttempts.get()}")

        // Try strategies in order
        for (strategy in strategyChain) {
            if (!strategy.canHandle(context)) continue

            logger.d(TAG, "Trying strategy: ${strategy.name}")
            val result = strategy.execute(context, eventBus)

            when (result) {
                RecoveryResult.Success -> {
                    logger.i(TAG, "Recovery succeeded via: ${strategy.name}")
                    return
                }
                // FIX RECOVERY-1: Failed is data class (has reason param) — match with is
                is RecoveryResult.Failed -> continue
                RecoveryResult.Escalate -> continue
            }
        }

        logger.e(TAG, "All recovery strategies exhausted for: $type")
    }

    private fun handleFatalError(event: AgentErrorEvent) {
        logger.e(TAG, "FATAL ERROR: ${event.errorCode} — ${event.message}")
        // Notify upstream, trigger clean shutdown
        engineScope.launch {
            eventBus.publish(
                StateChangedEvent(
                    previousState = AgentState.ERROR,
                    currentState = AgentState.TERMINATED,
                    trigger = "fatal_error",
                    sessionId = event.sessionId
                )
            )
        }
    }

    private fun mapErrorToRecoveryType(errorCode: AgentErrorCode): RecoveryType =
        when (errorCode) {
            AgentErrorCode.VISION_FAILED -> RecoveryType.UNKNOWN_SCREEN
            AgentErrorCode.OCR_FAILED -> RecoveryType.UNKNOWN_SCREEN
            AgentErrorCode.ACTION_FAILED -> RecoveryType.MISSING_ELEMENT
            AgentErrorCode.CAPTURE_FAILED -> RecoveryType.LOADING_TIMEOUT
            AgentErrorCode.RULE_ERROR -> RecoveryType.INVALID_STATE
            AgentErrorCode.PLAN_FAILED -> RecoveryType.SAFE_ROLLBACK
            else -> RecoveryType.SAFE_ROLLBACK
        }

    fun resetRecoveryState() {
        totalRecoveryAttempts.set(0)
        consecutiveErrors.set(0)
        logger.d(TAG, "Recovery state reset")
    }
}
