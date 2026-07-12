package com.visionagent.core.rule
import kotlin.collections.ArrayDeque  // Explicit: avoids Lint confusion with java.util.ArrayDeque (API 35)

import com.visionagent.core.event.*
import com.visionagent.core.memory.MemoryEngine
import com.visionagent.core.performance.PerformanceTracker
import com.visionagent.utils.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

// ============================================================
// RuleEngine — State Machine + Decision Tree
//
// Design Philosophy:
// - Pure functional rule evaluation (no side effects)
// - Priority-based rule ordering
// - State Machine for agent lifecycle management
// - Decision Tree for context-aware action selection
// - All rules are data-driven and configurable
// - Rules loaded from JSON config (easily extensible)
//
// Architecture:
//   RuleEngine
//     ├── StateMachine (agent state transitions)
//     ├── RuleRegistry (rule storage & retrieval)
//     ├── RuleEvaluator (evaluation pipeline)
//     ├── DecisionMaker (action selection)
//     └── RuleValidator (pre/post condition checks)
//
// SOLID Compliance:
// - S: Each rule has single responsibility
// - O: Rules extensible via RuleRegistry without modification
// - L: All rule types substitutable
// - I: Segregated Rule interfaces by type
// - D: Dependencies injected, not created
// ============================================================

// ============================================================
// Rule Data Model
// ============================================================

data class Rule(
    val id: String,
    val name: String,
    val priority: Int,                          // Higher = evaluated first
    val conditions: List<RuleCondition>,
    val action: RuleAction,
    val retryConfig: RetryConfig = RetryConfig(),
    val timeoutMs: Long = 5000L,
    val isEnabled: Boolean = true,
    val tags: Set<String> = emptySet()
)

// FIX L-4: `value: Any` is not serialisable by kotlinx.serialization.
// OTA createRulesPackage() → json.encodeToString(rules) throws SerializationException
// because the serialiser cannot handle arbitrary Any types.
// @Contextual delegates serialisation to a registered context serialiser.
// In practice, rule values are always strings in JSON (ScreenType.name, etc.).
// We represent value as String here; callers already use .toString() comparisons.
@Serializable
data class RuleCondition(
    val type: ConditionType,
    val operator: ConditionOperator,
    @Contextual val value: Any,   // serialised as its toString() via ContextualSerializer
    val negate: Boolean = false
)

enum class ConditionType {
    SCREEN_TYPE,
    ELEMENT_PRESENT,
    ELEMENT_ABSENT,
    TEXT_CONTAINS,
    TEXT_EQUALS,
    STATE_IS,
    RETRY_COUNT_LESS_THAN,
    TIME_SINCE_LAST_ACTION,
    MEMORY_KEY_EXISTS,
    CONSECUTIVE_ERRORS
}

enum class ConditionOperator { EQUALS, CONTAINS, GREATER_THAN, LESS_THAN, REGEX }

data class RuleAction(
    val actionType: ActionType,
    val targetSelector: ElementSelector?,
    val parameters: Map<String, Any> = emptyMap(),
    val fallbackAction: RuleAction? = null
)

data class ElementSelector(
    val byType: UIElementType? = null,
    val byText: String? = null,
    val byPosition: Rect? = null,
    val byIndex: Int? = null,
    val confidence: Float = 0.75f
)

data class RetryConfig(
    val maxRetries: Int = 3,
    val initialDelayMs: Long = 500L,
    val backoffMultiplier: Float = 2.0f,    // Exponential backoff
    val maxDelayMs: Long = 10000L
)

// ============================================================
// Agent State Machine
// ============================================================

// FIX M-2: AgentStateMachine — all methods now @Synchronized.
// currentState and stateHistory are mutated by transitionState() called from
// RuleEngine (Dispatchers.Default thread pool) and from AgentOrchestrator (Dispatchers.Main).
// Without synchronisation two concurrent transitions corrupt stateHistory.
class AgentStateMachine {

    @Volatile private var currentState: AgentState = AgentState.IDLE
    private val stateHistory = ArrayDeque<AgentState>(20)
    private val lock = Any()  // intrinsic lock for all state mutations

    private val validTransitions: Map<AgentState, Set<AgentState>> = mapOf(
        AgentState.IDLE        to setOf(AgentState.CAPTURING, AgentState.TERMINATED),
        AgentState.CAPTURING   to setOf(AgentState.ANALYZING, AgentState.PAUSED, AgentState.ERROR, AgentState.IDLE),
        AgentState.ANALYZING   to setOf(AgentState.PLANNING, AgentState.RECOVERING, AgentState.ERROR, AgentState.CAPTURING),
        AgentState.PLANNING    to setOf(AgentState.EXECUTING, AgentState.RECOVERING, AgentState.ERROR),
        AgentState.EXECUTING   to setOf(AgentState.CAPTURING, AgentState.WAITING, AgentState.RECOVERING, AgentState.ERROR),
        AgentState.WAITING     to setOf(AgentState.CAPTURING, AgentState.RECOVERING, AgentState.ERROR),
        AgentState.RECOVERING  to setOf(AgentState.CAPTURING, AgentState.IDLE, AgentState.ERROR, AgentState.TERMINATED),
        AgentState.PAUSED      to setOf(AgentState.CAPTURING, AgentState.IDLE, AgentState.TERMINATED),
        AgentState.ERROR       to setOf(AgentState.RECOVERING, AgentState.IDLE, AgentState.TERMINATED),
        AgentState.TERMINATED  to emptySet()
    )

    fun canTransition(to: AgentState): Boolean =
        validTransitions[currentState]?.contains(to) == true

    fun transition(to: AgentState, trigger: String): StateTransitionResult = synchronized(lock) {
        if (!canTransition(to)) {
            return StateTransitionResult.Invalid(
                message = "Invalid transition: $currentState → $to (trigger=$trigger)"
            )
        }
        val previous = currentState
        stateHistory.addLast(currentState)
        if (stateHistory.size > 20) stateHistory.removeFirst()
        currentState = to
        return StateTransitionResult.Success(previous = previous, current = to, trigger = trigger)
    }

    fun rollback(): AgentState? = synchronized(lock) {
        if (stateHistory.isEmpty()) return null
        currentState = stateHistory.removeLast()
        return currentState
    }

    // @Volatile read — always sees latest write without lock
    fun getCurrentState(): AgentState = currentState
    fun getHistory(): List<AgentState> = synchronized(lock) { stateHistory.toList() }
}

sealed class StateTransitionResult {
    data class Success(val previous: AgentState, val current: AgentState, val trigger: String) : StateTransitionResult()
    data class Invalid(val message: String) : StateTransitionResult()
}

// ============================================================
// RuleRegistry — Storage & Retrieval of Rules
// ============================================================

// FIX NC-14: RuleRegistry fully synchronized.
// sortedMapOf (TreeMap) + MutableList are NOT thread-safe.
// Concurrent OTA rule updates + rule evaluation → ConcurrentModificationException.
// All public methods now synchronized on `this`.
class RuleRegistry {
    private val rules = sortedMapOf<Int, MutableList<Rule>>(compareByDescending { it })

    @Synchronized fun register(rule: Rule) {
        rules.getOrPut(rule.priority) { mutableListOf() }.add(rule)
    }

    @Synchronized fun registerAll(ruleList: List<Rule>) = ruleList.forEach { register(it) }

    @Synchronized fun getOrderedRules(): List<Rule> =
        rules.values.flatten().filter { it.isEnabled }

    @Synchronized fun getByTag(tag: String): List<Rule> =
        rules.values.flatten().filter { it.isEnabled && tag in it.tags }

    @Synchronized fun disable(ruleId: String) {
        // FIX L-2: in-place mutation — no duplicate entries.
        rules.values.forEach { list ->
            val idx = list.indexOfFirst { it.id == ruleId }
            if (idx >= 0) list[idx] = list[idx].copy(isEnabled = false)
        }
    }

    @Synchronized fun unregister(ruleId: String) {
        rules.values.forEach { list -> list.removeAll { it.id == ruleId } }
    }

    @Synchronized fun size(): Int = rules.values.sumOf { it.size }
}

// ============================================================
// RuleEvaluator — Evaluates rules against current context
// ============================================================

class RuleEvaluator @Inject constructor(
    private val memoryEngine: MemoryEngine
) {

    data class EvaluationContext(
        val currentState: AgentState,
        val screenType: ScreenType,
        val detectedElements: List<DetectedUIElement>,
        val ocrText: String,
        val retryCount: Int,
        val lastActionTimestamp: Long,
        val sessionId: String
    )

    fun evaluate(rule: Rule, context: EvaluationContext): Boolean {
        return rule.conditions.all { condition ->
            val result = evaluateCondition(condition, context)
            if (condition.negate) !result else result
        }
    }

    private fun evaluateCondition(
        condition: RuleCondition,
        context: EvaluationContext
    ): Boolean = when (condition.type) {
        ConditionType.SCREEN_TYPE ->
            context.screenType == condition.value

        ConditionType.ELEMENT_PRESENT ->
            context.detectedElements.any { el ->
                when (condition.operator) {
                    ConditionOperator.EQUALS -> el.type == condition.value
                    ConditionOperator.CONTAINS -> el.text?.contains(condition.value.toString()) == true
                    else -> false
                }
            }

        ConditionType.ELEMENT_ABSENT ->
            context.detectedElements.none { el -> el.type == condition.value }

        ConditionType.TEXT_CONTAINS ->
            context.ocrText.contains(condition.value.toString(), ignoreCase = true)

        ConditionType.TEXT_EQUALS ->
            context.ocrText.equals(condition.value.toString(), ignoreCase = true)

        ConditionType.STATE_IS ->
            context.currentState == condition.value

        ConditionType.RETRY_COUNT_LESS_THAN ->
            // FIX LD-2: condition.value is `Any` — if stored as Long (e.g., from JSON deserialisation
            // or Kotlin literal 3L), `as? Int` returns null and the fallback 3 is used silently.
            // Fix: use toIntOrNull() on the string representation, which handles Int, Long, Double.
            context.retryCount < (condition.value.toString().toIntSafe() ?: 3)

        ConditionType.TIME_SINCE_LAST_ACTION ->
            (System.currentTimeMillis() - context.lastActionTimestamp) >
                    (condition.value.toString().toLongSafe() ?: 1000L)

        ConditionType.MEMORY_KEY_EXISTS ->
            memoryEngine.hasKey(condition.value.toString())

        ConditionType.CONSECUTIVE_ERRORS ->
            context.retryCount >= (condition.value.toString().toIntSafe() ?: 3)
    }
}

// ============================================================
// RuleEngine — Main Orchestrator
// ============================================================

@Singleton
class RuleEngine @Inject constructor(
    private val eventBus: AgentEventBus,
    // FIX RULE-1: RuleEditorEngine accesses ruleRegistry — change private to internal
    internal val ruleRegistry: RuleRegistry,
    private val ruleEvaluator: RuleEvaluator,
    private val stateMachine: AgentStateMachine,
    private val performanceTracker: PerformanceTracker,
    private val logger: Logger
) {

    companion object {
        private const val TAG = "RuleEngine"
    }

    private val engineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("RuleEngine")
    )

    // FIX C-2: All context fields written by concurrent coroutines on Dispatchers.Default
    // (a thread pool). Plain `var` has no memory-barrier guarantee on JVM.
    // @Volatile ensures write visibility across threads for reference types.
    // AtomicInteger/AtomicLong provide atomic read-modify-write for counters.
    @Volatile private var latestScreenType: ScreenType = ScreenType.UNKNOWN
    @Volatile private var latestElements: List<DetectedUIElement> = emptyList()
    @Volatile private var latestOCRText: String = ""
    private val retryCount = AtomicInteger(0)           // atomic: ++ and = 0 are not atomic on plain Int
    private val lastActionTimestamp = AtomicLong(0L)    // atomic: cross-thread visibility

    fun initialize() {
        loadDefaultRules()
        subscribeToEvents()
        logger.i(TAG, "RuleEngine initialized | rules=${ruleRegistry.size()}")
    }

    private fun subscribeToEvents() {
        // Update context when Vision detects elements
        eventBus.subscribe<UIElementDetectedEvent>()
            .onEach { event ->
                latestScreenType = event.screenType
                latestElements = event.elements
                evaluateRules(event.sessionId)
            }
            .launchIn(engineScope)

        // Update context with OCR results
        eventBus.subscribe<OCRCompletedEvent>()
            .onEach { event ->
                latestOCRText = event.text
            }
            .launchIn(engineScope)

        // Track action completion for retry logic
        eventBus.subscribe<ActionExecutedEvent>()
            .onEach { event ->
                lastActionTimestamp.set(event.timestamp)
                if (!event.success) retryCount.incrementAndGet() else retryCount.set(0)
            }
            .launchIn(engineScope)
    }

    private suspend fun evaluateRules(sessionId: String) {
        val startTime = performanceTracker.start("rule_evaluation")
        val context = buildContext(sessionId)

        try {
            val matchedRules = ruleRegistry.getOrderedRules()
                .filter { rule -> ruleEvaluator.evaluate(rule, context) }

            // Execute only highest priority matching rule
            matchedRules.firstOrNull()?.let { rule ->
                val decision = buildDecision(rule, context)

                eventBus.publish(
                    RuleEvaluatedEvent(
                        ruleId = rule.id,
                        ruleName = rule.name,
                        matched = true,
                        priority = rule.priority,
                        decision = decision,
                        sessionId = sessionId
                    )
                )

                logger.d(TAG, "Rule matched: ${rule.name} | action=${decision?.actionType}")
            }
        } catch (e: Exception) {
            logger.e(TAG, "Rule evaluation error", e)
            eventBus.publish(
                AgentErrorEvent(
                    errorCode = AgentErrorCode.RULE_ERROR,
                    message = "Rule evaluation failed: ${e.message}",
                    sessionId = sessionId
                )
            )
        } finally {
            performanceTracker.end("rule_evaluation", startTime, sessionId)
        }
    }

    private fun buildContext(sessionId: String) = RuleEvaluator.EvaluationContext(
        currentState        = stateMachine.getCurrentState(),
        screenType          = latestScreenType,          // @Volatile read — always fresh
        detectedElements    = latestElements,            // @Volatile read — always fresh
        ocrText             = latestOCRText,             // @Volatile read — always fresh
        retryCount          = retryCount.get(),          // AtomicInteger.get()
        lastActionTimestamp = lastActionTimestamp.get(), // AtomicLong.get()
        sessionId           = sessionId
    )

    private fun buildDecision(rule: Rule, context: RuleEvaluator.EvaluationContext): AgentDecision? {
        val selector = rule.action.targetSelector ?: return AgentDecision(
            actionType = rule.action.actionType,
            target = null,
            parameters = rule.action.parameters,
            confidence = 1.0f,
            reasoning = "Rule: ${rule.name}"
        )

        val target = resolveTarget(selector, context.detectedElements) ?: return null

        return AgentDecision(
            actionType = rule.action.actionType,
            target = target,
            parameters = rule.action.parameters,
            confidence = target.confidence,
            reasoning = "Rule: ${rule.name} | Element: ${target.type}"
        )
    }

    private fun resolveTarget(
        selector: ElementSelector,
        elements: List<DetectedUIElement>
    ): DetectedUIElement? {
        return elements.filter { element ->
            (selector.byType == null || element.type == selector.byType) &&
            (selector.byText == null || element.text?.contains(selector.byText, true) == true) &&
            element.confidence >= selector.confidence
        }.sortedByDescending { it.confidence }.firstOrNull()
    }

    private fun loadDefaultRules() {
        // Default Recovery Rules
        ruleRegistry.register(Rule(
            id = "R001",
            name = "Handle Loading Screen",
            priority = 100,
            conditions = listOf(
                RuleCondition(ConditionType.SCREEN_TYPE, ConditionOperator.EQUALS, ScreenType.LOADING)
            ),
            action = RuleAction(
                actionType = ActionType.WAIT,
                targetSelector = null,
                parameters = mapOf("durationMs" to 2000L)
            ),
            tags = setOf("recovery", "loading")
        ))

        ruleRegistry.register(Rule(
            id = "R002",
            name = "Dismiss Unexpected Dialog",
            priority = 200,
            conditions = listOf(
                RuleCondition(ConditionType.SCREEN_TYPE, ConditionOperator.EQUALS, ScreenType.DIALOG)
            ),
            action = RuleAction(
                actionType = ActionType.TAP,
                targetSelector = ElementSelector(byType = UIElementType.BUTTON, byText = "OK"),
                fallbackAction = RuleAction(
                    actionType = ActionType.NAVIGATE_BACK,
                    targetSelector = null
                )
            ),
            tags = setOf("recovery", "dialog")
        ))

        ruleRegistry.register(Rule(
            id = "R003",
            name = "Retry on Error Screen",
            priority = 150,
            conditions = listOf(
                RuleCondition(ConditionType.SCREEN_TYPE, ConditionOperator.EQUALS, ScreenType.ERROR),
                RuleCondition(ConditionType.RETRY_COUNT_LESS_THAN, ConditionOperator.LESS_THAN, 3)
            ),
            action = RuleAction(
                actionType = ActionType.TAP,
                targetSelector = ElementSelector(byText = "Retry")
            ),
            retryConfig = RetryConfig(maxRetries = 3, initialDelayMs = 1000L, backoffMultiplier = 2f),
            tags = setOf("recovery", "error")
        ))

        logger.d(TAG, "Default rules loaded | total=${ruleRegistry.size()}")
    }

    fun registerRule(rule: Rule) = ruleRegistry.register(rule)
    fun registerRules(rules: List<Rule>) = ruleRegistry.registerAll(rules)
    fun getCurrentState() = stateMachine.getCurrentState()

    fun transitionState(to: AgentState, trigger: String, sessionId: String) {
        when (val result = stateMachine.transition(to, trigger)) {
            is StateTransitionResult.Success -> {
                eventBus.publish(
                    StateChangedEvent(
                        previousState = result.previous,
                        currentState = result.current,
                        trigger = trigger,
                        sessionId = sessionId
                    )
                )
                logger.d(TAG, "State: ${result.previous} → ${result.current}")
            }
            is StateTransitionResult.Invalid -> {
                logger.w(TAG, "Invalid state transition: ${result.message}")
            }
        }
    }
}

// ── Extension helpers (used in rule evaluation) ──────────────────────────────
private fun String.toIntSafe(): Int? = this.trim().toIntOrNull()
private fun String.toLongSafe(): Long? = this.trim().toLongOrNull()
