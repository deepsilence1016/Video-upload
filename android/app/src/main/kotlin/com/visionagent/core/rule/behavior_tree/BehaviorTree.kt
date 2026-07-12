package com.visionagent.core.rule.behavior_tree
import kotlin.collections.ArrayDeque  // Explicit: avoids Lint confusion with java.util.ArrayDeque (API 35)

import com.visionagent.core.event.*
import com.visionagent.core.memory.MemoryEngine
import com.visionagent.core.vision.semantic.UISemanticGraph
import com.visionagent.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

// ============================================================
// Behavior Tree — Advanced AI Decision Making
//
// Behavior Trees are used in AAA game AI (Halo, The Sims)
// and modern robotics. They solve exactly our problem:
// "Given current state, what should the agent do next?"
//
// Advantages over simple Rule Engine:
// - Hierarchical: complex behaviors from simple nodes
// - Reusable: nodes compose like LEGO bricks
// - Debuggable: visual tree structure, clear execution trace
// - Reactive: re-evaluates every tick, no state machine deadlocks
//
// Node Types:
// ┌─────────────────────────────────────────────────────┐
// │ COMPOSITE                                            │
// │  ├── Sequence  : All children must succeed (AND)     │
// │  ├── Selector  : First success wins (OR)             │
// │  ├── Parallel  : Run all children simultaneously     │
// │  └── Random    : Pick random child (exploration)     │
// │                                                      │
// │ DECORATOR                                            │
// │  ├── Inverter  : Flip SUCCESS ↔ FAILURE              │
// │  ├── Repeater  : Repeat N times                      │
// │  ├── Succeeder : Always return SUCCESS               │
// │  └── Limiter   : Limit executions per time period   │
// │                                                      │
// │ LEAF                                                 │
// │  ├── Condition : Check agent state/screen            │
// │  └── Action    : Execute agent action                │
// └─────────────────────────────────────────────────────┘
//
// Status: RUNNING, SUCCESS, FAILURE
// ============================================================

enum class BTStatus { RUNNING, SUCCESS, FAILURE }

// ─────────────────────────────────────────────────────────────────────────────
// BT Context — passed to every node tick
// ─────────────────────────────────────────────────────────────────────────────

data class BTContext(
    val screenType:     ScreenType,
    val elements:       List<DetectedUIElement>,
    val ocrText:        String,
    val semanticGraph:  UISemanticGraph?,
    val agentState:     AgentState,
    val retryCount:     Int,
    val sessionId:      String,
    val memory:         MemoryEngine,
    val eventBus:       AgentEventBus,
    val timestamp:      Long = System.currentTimeMillis()
)

// ─────────────────────────────────────────────────────────────────────────────
// Base Node
// ─────────────────────────────────────────────────────────────────────────────

abstract class BTNode(val name: String) {
    protected var status: BTStatus = BTStatus.FAILURE
    open fun tick(ctx: BTContext): BTStatus = BTStatus.FAILURE
    open fun reset() { status = BTStatus.FAILURE }
    open fun describe(indent: Int = 0): String = " ".repeat(indent) + "[$name]"
}

// ─────────────────────────────────────────────────────────────────────────────
// Composite Nodes
// ─────────────────────────────────────────────────────────────────────────────

/** Sequence: tick children left-to-right; fail on first FAILURE (AND logic) */
class Sequence(name: String, private val children: List<BTNode>) : BTNode(name) {
    private var runningChildIdx = 0

    override fun tick(ctx: BTContext): BTStatus {
        while (runningChildIdx < children.size) {
            val result = children[runningChildIdx].tick(ctx)
            when (result) {
                BTStatus.FAILURE -> {
                    reset()
                    return BTStatus.FAILURE.also { status = it }
                }
                BTStatus.RUNNING -> return BTStatus.RUNNING.also { status = it }
                BTStatus.SUCCESS -> runningChildIdx++
            }
        }
        reset()
        return BTStatus.SUCCESS.also { status = it }
    }

    override fun reset() { runningChildIdx = 0; children.forEach { it.reset() } }
    override fun describe(i: Int) = " ".repeat(i) + "→[Seq: $name]\n" +
        children.joinToString("\n") { it.describe(i + 2) }
}

/** Selector: tick children left-to-right; succeed on first SUCCESS (OR logic) */
class Selector(name: String, private val children: List<BTNode>) : BTNode(name) {
    private var runningChildIdx = 0

    override fun tick(ctx: BTContext): BTStatus {
        while (runningChildIdx < children.size) {
            val result = children[runningChildIdx].tick(ctx)
            when (result) {
                BTStatus.SUCCESS -> {
                    reset()
                    return BTStatus.SUCCESS.also { status = it }
                }
                BTStatus.RUNNING -> return BTStatus.RUNNING.also { status = it }
                BTStatus.FAILURE -> runningChildIdx++
            }
        }
        reset()
        return BTStatus.FAILURE.also { status = it }
    }

    override fun reset() { runningChildIdx = 0; children.forEach { it.reset() } }
    override fun describe(i: Int) = " ".repeat(i) + "?[Sel: $name]\n" +
        children.joinToString("\n") { it.describe(i + 2) }
}

/** Parallel: tick ALL children each tick; success when M of N succeed */
class Parallel(
    name:     String,
    private val children:       List<BTNode>,
    private val successPolicy:  Int = children.size,   // How many must succeed
    private val failurePolicy:  Int = 1                // How many failures = tree failure
) : BTNode(name) {
    override fun tick(ctx: BTContext): BTStatus {
        var successes = 0
        var failures  = 0
        children.forEach { child ->
            when (child.tick(ctx)) {
                BTStatus.SUCCESS -> successes++
                BTStatus.FAILURE -> failures++
                BTStatus.RUNNING -> {}
            }
        }
        return when {
            successes >= successPolicy -> BTStatus.SUCCESS
            failures  >= failurePolicy -> BTStatus.FAILURE
            else                       -> BTStatus.RUNNING
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Decorator Nodes
// ─────────────────────────────────────────────────────────────────────────────

class Inverter(name: String, private val child: BTNode) : BTNode(name) {
    override fun tick(ctx: BTContext) = when (child.tick(ctx)) {
        BTStatus.SUCCESS -> BTStatus.FAILURE
        BTStatus.FAILURE -> BTStatus.SUCCESS
        BTStatus.RUNNING -> BTStatus.RUNNING
    }
}

class Repeater(name: String, private val child: BTNode, private val times: Int) : BTNode(name) {
    private var count = 0
    override fun tick(ctx: BTContext): BTStatus {
        if (count >= times) { count = 0; return BTStatus.SUCCESS }
        when (child.tick(ctx)) {
            BTStatus.SUCCESS -> { child.reset(); count++ }
            BTStatus.FAILURE -> { count = 0; return BTStatus.FAILURE }
            BTStatus.RUNNING -> return BTStatus.RUNNING
        }
        return if (count >= times) { count = 0; BTStatus.SUCCESS } else BTStatus.RUNNING
    }
    override fun reset() { count = 0; child.reset() }
}

class Succeeder(child: BTNode) : BTNode("Succeeder") {
    private val inner = child
    override fun tick(ctx: BTContext): BTStatus { inner.tick(ctx); return BTStatus.SUCCESS }
}

class Limiter(
    name: String,
    private val child:         BTNode,
    private val maxPerMinute:  Int = 5
) : BTNode(name) {
    private val callTimes = ArrayDeque<Long>(maxPerMinute + 1)
    override fun tick(ctx: BTContext): BTStatus {
        val now    = System.currentTimeMillis()
        val cutoff = now - 60_000L
        while (callTimes.isNotEmpty() && callTimes.first() < cutoff) callTimes.removeFirst()
        if (callTimes.size >= maxPerMinute) return BTStatus.FAILURE
        callTimes.addLast(now)
        return child.tick(ctx)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Leaf Nodes — Conditions and Actions
// ─────────────────────────────────────────────────────────────────────────────

/** Condition node — checks something about the world */
class ConditionNode(
    name: String,
    private val check: (BTContext) -> Boolean
) : BTNode(name) {
    override fun tick(ctx: BTContext) =
        if (check(ctx)) BTStatus.SUCCESS else BTStatus.FAILURE
}

/** Action node — dispatches an agent action via EventBus */
class ActionNode(
    name: String,
    private val action:     ActionType,
    private val selector:   (BTContext) -> DetectedUIElement? = { null },
    private val parameters: Map<String, Any>                  = emptyMap()
) : BTNode(name) {
    override fun tick(ctx: BTContext): BTStatus {
        val target = selector(ctx)

        // If action requires a target and we can't find it, fail
        if (action in listOf(ActionType.TAP, ActionType.LONG_PRESS) && target == null) {
            return BTStatus.FAILURE
        }

        ctx.eventBus.publish(
            RuleEvaluatedEvent(
                ruleId    = "bt_${name.hashCode()}",
                ruleName  = "BT:$name",
                matched   = true,
                priority  = 300,
                decision  = AgentDecision(
                    actionType = action,
                    target     = target,
                    parameters = parameters,
                    confidence = target?.confidence ?: 0.9f,
                    reasoning  = "BehaviorTree: $name"
                ),
                sessionId = ctx.sessionId
            )
        )
        return BTStatus.RUNNING  // Wait for ActionExecutedEvent to confirm
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Built-in Behavior Trees for common scenarios
// ─────────────────────────────────────────────────────────────────────────────

object BehaviorTrees {

    /** Handle unexpected dialog — dismiss it safely */
    fun dismissDialogTree(): BTNode = Sequence("HandleDialog", listOf(
        ConditionNode("IsDialog") { ctx ->
            ctx.screenType == ScreenType.DIALOG
        },
        Selector("DismissOptions", listOf(
            // Try "OK" button first
            ActionNode("TapOK", ActionType.TAP, selector = { ctx: BTContext ->
                ctx.elements.find { elem: DetectedUIElement ->
                    elem.type == UIElementType.BUTTON &&
                    elem.text?.lowercase() in setOf("ok", "yes", "confirm", "done")
                }
            }),
            // Try "Cancel" button
            ActionNode("TapCancel", ActionType.TAP, selector = { ctx: BTContext ->
                ctx.elements.find { elem: DetectedUIElement ->
                    elem.type == UIElementType.BUTTON &&
                    elem.text?.lowercase() in setOf("cancel", "no", "dismiss", "close")
                }
            }),
            // Fallback: navigate back
            ActionNode("NavigateBack", ActionType.NAVIGATE_BACK)
        ))
    ))

    /** Handle loading screen — wait then retry */
    fun handleLoadingTree(maxWaitMs: Long = 10_000L): BTNode = Sequence("HandleLoading", listOf(
        ConditionNode("IsLoading") { ctx -> ctx.screenType == ScreenType.LOADING },
        Limiter("WaitForLoad", ActionNode("Wait", ActionType.WAIT,
            selector = { null },
            parameters = mapOf("durationMs" to 2000L)), maxPerMinute = 5),
        Inverter("NotLoading", ConditionNode("StillLoading") { ctx ->
            ctx.screenType == ScreenType.LOADING
        })
    ))

    /** Handle error screen — retry with backoff */
    fun handleErrorTree(): BTNode = Sequence("HandleError", listOf(
        ConditionNode("IsError") { ctx -> ctx.screenType == ScreenType.ERROR },
        ConditionNode("CanRetry") { ctx -> ctx.retryCount < 3 },
        Selector("ErrorRecovery", listOf(
            // Try tapping retry button
            ActionNode("TapRetry", ActionType.TAP, selector = { ctx: BTContext ->
                ctx.elements.find { elem: DetectedUIElement ->
                    elem.type == UIElementType.BUTTON &&
                    elem.text?.lowercase() in setOf("retry", "try again", "refresh")
                }
            }),
            // Navigate back
            ActionNode("GoBack", ActionType.NAVIGATE_BACK)
        ))
    ))

    /** Main agent behavior — checks scenarios in priority order */
    fun mainAgentTree(): BTNode = Selector("AgentRoot", listOf(
        dismissDialogTree(),
        handleLoadingTree(),
        handleErrorTree(),
        // Default: continue with planned action
        ActionNode("ProceedWithPlan", ActionType.NONE)
    ))
}

// ─────────────────────────────────────────────────────────────────────────────
// BehaviorTreeEngine — executes trees and reports results
// ─────────────────────────────────────────────────────────────────────────────

@Singleton
class BehaviorTreeEngine @Inject constructor(
    private val eventBus: AgentEventBus,
    private val memory:   MemoryEngine,
    private val logger:   Logger
) {
    companion object { private const val TAG = "BTEngine" }

    private var activeTree: BTNode = BehaviorTrees.mainAgentTree()

    fun setTree(tree: BTNode) {
        activeTree = tree
        logger.i(TAG, "BT set: ${tree.name}")
    }

    fun tick(
        screenType:    ScreenType,
        elements:      List<DetectedUIElement>,
        ocrText:       String,
        semanticGraph: UISemanticGraph?,
        agentState:    AgentState,
        retryCount:    Int,
        sessionId:     String
    ): BTStatus {
        val ctx = BTContext(
            screenType    = screenType,
            elements      = elements,
            ocrText       = ocrText,
            semanticGraph = semanticGraph,
            agentState    = agentState,
            retryCount    = retryCount,
            sessionId     = sessionId,
            memory        = memory,
            eventBus      = eventBus
        )
        val result = activeTree.tick(ctx)
        logger.d(TAG, "BT tick: ${activeTree.name} → $result")
        return result
    }

    /** Get tree structure as text — for debugging */
    fun describeTree(): String = activeTree.describe()

    fun resetTree() = activeTree.reset()
}
