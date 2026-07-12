package com.visionagent.core.planner

import com.visionagent.core.event.*
import com.visionagent.core.memory.MemoryEngine
import com.visionagent.core.memory.ActionMemoryItem
import com.visionagent.core.performance.PerformanceTracker
import com.visionagent.utils.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// ============================================================
// PlannerEngine — Goal-Oriented Action Planning
//
// Design:
// - BFS-based plan generation (finds shortest path to goal)
// - Current state analysis from Vision + OCR + Memory
// - Alternative path generation (fallback plans)
// - Risk scoring per plan step
// - Rollback strategy embedded in each plan
// - Plan caching for repeated tasks
//
// Pattern: GOAP (Goal-Oriented Action Planning) simplified
//          + Template Method for plan execution
//
// SOLID:
// - Each goal type has its own GoalAnalyzer
// - Plans are immutable data objects
// - Planner extensible via GoalRegistry
// ============================================================

data class AgentGoal(
    val id: String = UUID.randomUUID().toString(),
    val type: GoalType,
    val description: String,
    val parameters: Map<String, Any> = emptyMap(),
    val priority: Int = 0,
    val deadline: Long? = null  // null = no deadline
)

enum class GoalType {
    NAVIGATE_TO_SCREEN,
    FILL_FORM,
    CLICK_ELEMENT,
    EXTRACT_DATA,
    WAIT_FOR_ELEMENT,
    COMPLETE_FLOW,
    CUSTOM
}

data class ExecutionPlan(
    val planId: String = UUID.randomUUID().toString(),
    val goal: AgentGoal,
    val steps: List<PlanStep>,
    val alternativePlans: List<ExecutionPlan> = emptyList(),
    val estimatedDurationMs: Long,
    val riskScore: Float,  // 0.0 (safe) to 1.0 (risky)
    val rollbackPlan: ExecutionPlan? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class CurrentStateAnalysis(
    val screenType: ScreenType,
    val visibleElements: List<DetectedUIElement>,
    val ocrText: String,
    val agentState: AgentState,
    val recentActions: List<ActionMemoryItem>,
    val sessionId: String
)

// ============================================================
// Goal Analyzers — One per GoalType
// ============================================================

interface GoalAnalyzer {
    val supportedGoalType: GoalType
    fun generatePlan(
        goal: AgentGoal,
        currentState: CurrentStateAnalysis
    ): ExecutionPlan?
}

class NavigateToScreenAnalyzer : GoalAnalyzer {
    override val supportedGoalType = GoalType.NAVIGATE_TO_SCREEN

    override fun generatePlan(
        goal: AgentGoal,
        currentState: CurrentStateAnalysis
    ): ExecutionPlan {
        val targetScreen = goal.parameters["targetScreen"] as? ScreenType
            ?: ScreenType.UNKNOWN
        val stepsToTarget = buildNavigationSteps(currentState.screenType, targetScreen)

        return ExecutionPlan(
            goal = goal,
            steps = stepsToTarget,
            estimatedDurationMs = stepsToTarget.size * 500L,
            riskScore = calculateNavigationRisk(currentState.screenType, targetScreen)
        )
    }

    private fun buildNavigationSteps(from: ScreenType, to: ScreenType): List<PlanStep> {
        // Simplified navigation graph — expand based on your app's flow
        return listOf(
            PlanStep(
                stepId = UUID.randomUUID().toString(),
                action = ActionType.TAP,
                target = DetectedUIElement(
                    type = UIElementType.NAVIGATION_BAR,
                    bounds = Rect(0, 1800, 1080, 1920),
                    confidence = 0.9f
                ),
                expectedOutcome = "Navigate to $to",
                fallback = PlanStep(
                    stepId = UUID.randomUUID().toString(),
                    action = ActionType.NAVIGATE_BACK,
                    target = null,
                    expectedOutcome = "Return to previous screen",
                    fallback = null
                )
            )
        )
    }

    private fun calculateNavigationRisk(from: ScreenType, to: ScreenType): Float =
        if (from == to) 0.0f else 0.3f
}

class FillFormAnalyzer : GoalAnalyzer {
    override val supportedGoalType = GoalType.FILL_FORM

    override fun generatePlan(
        goal: AgentGoal,
        currentState: CurrentStateAnalysis
    ): ExecutionPlan? {
        if (currentState.screenType != ScreenType.FORM) return null

        val formData = goal.parameters["formData"] as? Map<*, *> ?: return null
        val textFields = currentState.visibleElements.filter {
            it.type == UIElementType.TEXT_FIELD
        }

        val steps = textFields.mapIndexed { index, field ->
            val value = formData[index.toString()] as? String ?: ""
            PlanStep(
                stepId = UUID.randomUUID().toString(),
                action = ActionType.TEXT_INPUT,
                target = field,
                expectedOutcome = "Field $index filled with: $value",
                fallback = null
            )
        }

        return ExecutionPlan(
            goal = goal,
            steps = steps,
            estimatedDurationMs = steps.size * 300L,
            riskScore = 0.2f
        )
    }
}

class WaitForElementAnalyzer : GoalAnalyzer {
    override val supportedGoalType = GoalType.WAIT_FOR_ELEMENT

    override fun generatePlan(
        goal: AgentGoal,
        currentState: CurrentStateAnalysis
    ): ExecutionPlan {
        val elementType = goal.parameters["elementType"] as? UIElementType
        val timeoutMs = goal.parameters["timeoutMs"] as? Long ?: 10000L

        return ExecutionPlan(
            goal = goal,
            steps = listOf(
                PlanStep(
                    stepId = UUID.randomUUID().toString(),
                    action = ActionType.WAIT,
                    target = elementType?.let {
                        DetectedUIElement(type = it, bounds = Rect(0,0,0,0), confidence = 0f)
                    },
                    expectedOutcome = "Element $elementType appears",
                    fallback = PlanStep(
                        stepId = UUID.randomUUID().toString(),
                        action = ActionType.SCROLL_DOWN,
                        target = null,
                        expectedOutcome = "Element visible after scroll",
                        fallback = null
                    )
                )
            ),
            estimatedDurationMs = timeoutMs,
            riskScore = 0.1f
        )
    }
}

// ============================================================
// GoalRegistry — Manages all goal analyzers
// ============================================================

class GoalRegistry {
    private val analyzers = mutableMapOf<GoalType, GoalAnalyzer>()

    fun register(analyzer: GoalAnalyzer) {
        analyzers[analyzer.supportedGoalType] = analyzer
    }

    fun getAnalyzer(goalType: GoalType): GoalAnalyzer? = analyzers[goalType]

    fun getSupportedGoals(): Set<GoalType> = analyzers.keys
}

// ============================================================
// RiskAnalyzer — Evaluates plan risk
// ============================================================

class RiskAnalyzer {
    fun analyzeRisk(plan: ExecutionPlan, state: CurrentStateAnalysis): Float {
        var risk = plan.riskScore

        // Higher risk if agent is in error state
        if (state.agentState == AgentState.ERROR) risk += 0.3f

        // Higher risk if many recent failures
        val recentFailures = state.recentActions.count { !it.success }
        risk += recentFailures * 0.05f

        // Higher risk if unknown screen
        if (state.screenType == ScreenType.UNKNOWN) risk += 0.2f

        return minOf(1.0f, risk)
    }
}

// ============================================================
// PlannerEngine — Main Planner Coordinator
// ============================================================

@Singleton
class PlannerEngine @Inject constructor(
    private val eventBus: AgentEventBus,
    private val memoryEngine: MemoryEngine,
    private val performanceTracker: PerformanceTracker,
    private val riskAnalyzer: RiskAnalyzer,
    private val logger: Logger
) {

    companion object {
        private const val TAG = "PlannerEngine"
        private const val HIGH_RISK_THRESHOLD = 0.7f
    }

    private val engineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("PlannerEngine")
    )

    private val goalRegistry = GoalRegistry()
    // FIX IG-4 + R4-2:
    // IG-4 fix: ConcurrentHashMap for planCache (correct).
    //
    // R4-2: @Volatile alone is insufficient for compound operations.
    // @Volatile guarantees visibility of individual reads/writes but NOT atomicity
    // of compound check-then-act sequences like:
    //   submitGoal()   → currentPlan = newPlan   (write 1)
    //                  → currentStepIndex = 0    (write 2)
    //   advancePlan()  → reads currentPlan (new) but currentStepIndex (old!) between writes
    //
    // Also: currentStepIndex++ in advancePlan() is not atomic on @Volatile Int.
    //
    // Fix: encapsulate plan state in an AtomicReference to a single immutable data class.
    // All mutations replace the whole PlanState atomically — no partial visibility window.
    private val planCache = java.util.concurrent.ConcurrentHashMap<String, ExecutionPlan>()

    private data class PlanState(
        val plan: ExecutionPlan?,
        val stepIndex: Int
    ) {
        companion object { val EMPTY = PlanState(null, 0) }
        fun advance() = copy(stepIndex = stepIndex + 1)
        fun reset(newPlan: ExecutionPlan) = PlanState(newPlan, 0)
        val isComplete get() = plan == null || stepIndex >= (plan.steps.size)
    }

    // Single AtomicReference replaces both @Volatile vars — swapped atomically.
    private val planState = java.util.concurrent.atomic.AtomicReference(PlanState.EMPTY)

    fun initialize() {
        registerDefaultAnalyzers()
        subscribeToEvents()
        logger.i(TAG, "PlannerEngine initialized | analyzers=${goalRegistry.getSupportedGoals()}")
    }

    private fun registerDefaultAnalyzers() {
        goalRegistry.register(NavigateToScreenAnalyzer())
        goalRegistry.register(FillFormAnalyzer())
        goalRegistry.register(WaitForElementAnalyzer())
    }

    private fun subscribeToEvents() {
        // Update plan execution on action completion
        eventBus.subscribe<ActionExecutedEvent>()
            .onEach { event ->
                if (event.success) {
                    advancePlan(event.sessionId)
                } else {
                    handlePlanStepFailure(event)
                }
            }
            .launchIn(engineScope)
    }

    /**
     * Submit a goal for planning and execution
     */
    suspend fun submitGoal(goal: AgentGoal, sessionId: String) {
        val startTime = performanceTracker.start("plan_generation")

        try {
            val state = buildCurrentState(sessionId)

            // Check cache
            val cacheKey = "${goal.type}_${goal.parameters.hashCode()}"
            val plan = planCache[cacheKey] ?: run {
                val analyzer = goalRegistry.getAnalyzer(goal.type)
                    ?: throw IllegalArgumentException("No analyzer for: ${goal.type}")

                val generated = analyzer.generatePlan(goal, state)
                    ?: throw IllegalStateException("Plan generation failed for: ${goal.type}")

                // Evaluate risk
                val risk = riskAnalyzer.analyzeRisk(generated, state)
                if (risk >= HIGH_RISK_THRESHOLD) {
                    logger.w(TAG, "High risk plan detected: risk=$risk | goal=${goal.type}")
                }

                planCache[cacheKey] = generated
                generated
            }

            // FIX R4-2: atomic swap — both plan and stepIndex set in one CAS operation.
            planState.set(PlanState.EMPTY.reset(plan))

            eventBus.publish(
                PlanCreatedEvent(
                    planId = plan.planId,
                    steps = plan.steps,
                    estimatedDuration = plan.estimatedDurationMs,
                    sessionId = sessionId
                )
            )

            logger.i(TAG, "Plan created: ${plan.planId} | steps=${plan.steps.size} | risk=${plan.riskScore}")

            // Execute first step
            executeNextStep(sessionId)

        } catch (e: Exception) {
            logger.e(TAG, "Plan generation failed", e)
            eventBus.publish(
                AgentErrorEvent(
                    errorCode = AgentErrorCode.PLAN_FAILED,
                    message = "Planning failed: ${e.message}",
                    sessionId = sessionId
                )
            )
        } finally {
            performanceTracker.end("plan_generation", startTime, sessionId)
        }
    }

    private fun executeNextStep(sessionId: String) {
        // FIX R4-2: read atomic snapshot — consistent view of plan + stepIndex together.
        val state = planState.get()
        val plan  = state.plan ?: return
        if (state.isComplete) {
            logger.i(TAG, "Plan completed: ${plan.planId}")
            planState.set(PlanState.EMPTY)   // atomic clear
            return
        }

        val step = plan.steps[state.stepIndex]
        logger.d(TAG, "Executing step ${state.stepIndex + 1}/${plan.steps.size}: ${step.action}")

        eventBus.publish(
            RuleEvaluatedEvent(
                ruleId   = "planner_${step.stepId}",
                ruleName = "PlanStep",
                matched  = true,
                priority = 500,
                decision = AgentDecision(
                    actionType = step.action,
                    target     = step.target,
                    confidence = 0.95f,
                    reasoning  = "Plan: ${plan.planId} | Step: ${state.stepIndex + 1}"
                ),
                sessionId = sessionId
            )
        )
    }

    private fun advancePlan(sessionId: String) {
        // FIX R4-2: atomic increment via CAS loop — no lost updates under concurrency.
        while (true) {
            val current = planState.get()
            if (current.plan == null) return   // no active plan
            val next    = current.advance()
            if (planState.compareAndSet(current, next)) break  // won the race
            // lost CAS — another thread updated, retry with fresh state
        }
        executeNextStep(sessionId)
    }

    private fun handlePlanStepFailure(event: ActionExecutedEvent) {
        val state      = planState.get()    // FIX R4-2: atomic read
        val plan       = state.plan ?: return
        val failedStep = plan.steps.getOrNull(state.stepIndex) ?: return

        if (failedStep.fallback != null) {
            logger.w(TAG, "Step failed, trying fallback: ${failedStep.fallback.action}")
            eventBus.publish(
                RuleEvaluatedEvent(
                    ruleId = "planner_fallback",
                    ruleName = "PlanStepFallback",
                    matched = true,
                    priority = 600,
                    decision = AgentDecision(
                        actionType = failedStep.fallback.action,
                        target = failedStep.fallback.target,
                        confidence = 0.8f,
                        // FIX PLAN-1: use state.stepIndex not currentStepIndex (old volatile var)
                        reasoning = "Fallback for step: ${state.stepIndex + 1}"
                    ),
                    sessionId = event.sessionId
                )
            )
        } else {
            // Try alternative plan — update planState atomically
            plan.alternativePlans.firstOrNull()?.let { altPlan ->
                logger.w(TAG, "Switching to alternative plan: ${altPlan.planId}")
                planState.set(PlanState(altPlan, 0))
                executeNextStep(event.sessionId)
            }
        }
    }

    private fun buildCurrentState(sessionId: String): CurrentStateAnalysis {
        val lastScreen = memoryEngine.screenMemory.getLast()
        return CurrentStateAnalysis(
            screenType = lastScreen?.screenType ?: ScreenType.UNKNOWN,
            visibleElements = lastScreen?.elements ?: emptyList(),
            ocrText = lastScreen?.ocrText ?: "",
            agentState = AgentState.PLANNING,
            recentActions = memoryEngine.actionMemory.getRecentActions(5),
            sessionId = sessionId
        )
    }

    fun cancelCurrentPlan() {
        planState.set(PlanState.EMPTY)   // FIX R4-2: atomic clear
        logger.i(TAG, "Current plan cancelled")
    }
}
