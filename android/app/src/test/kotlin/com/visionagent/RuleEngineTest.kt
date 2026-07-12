package com.visionagent

import com.visionagent.core.event.*
import com.visionagent.core.rule.*
import io.mockk.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import app.cash.turbine.test

// ============================================================
// RuleEngineTest — Unit Tests for Rule Engine
// ============================================================

class RuleEngineTest {

    private lateinit var stateMachine: AgentStateMachine
    private lateinit var registry: RuleRegistry
    private lateinit var memoryEngine: com.visionagent.core.memory.MemoryEngine

    @Before
    fun setup() {
        stateMachine = AgentStateMachine()
        registry = RuleRegistry()
        memoryEngine = mockk(relaxed = true)
    }

    // ---- State Machine Tests ----

    @Test
    fun `state machine starts in IDLE state`() {
        assertEquals(AgentState.IDLE, stateMachine.getCurrentState())
    }

    @Test
    fun `valid transition IDLE to CAPTURING succeeds`() {
        val result = stateMachine.transition(AgentState.CAPTURING, "test")
        assertTrue(result is StateTransitionResult.Success)
        assertEquals(AgentState.CAPTURING, stateMachine.getCurrentState())
    }

    @Test
    fun `invalid transition IDLE to EXECUTING fails`() {
        val result = stateMachine.transition(AgentState.EXECUTING, "test")
        assertTrue(result is StateTransitionResult.Invalid)
        assertEquals(AgentState.IDLE, stateMachine.getCurrentState())  // State unchanged
    }

    @Test
    fun `rollback restores previous state`() {
        stateMachine.transition(AgentState.CAPTURING, "step1")
        stateMachine.transition(AgentState.ANALYZING, "step2")
        val previous = stateMachine.rollback()
        assertEquals(AgentState.CAPTURING, previous)
        assertEquals(AgentState.CAPTURING, stateMachine.getCurrentState())
    }

    // ---- Rule Registry Tests ----

    @Test
    fun `rules are sorted by priority descending`() {
        registry.register(Rule(
            id = "low", name = "Low Priority", priority = 10,
            conditions = emptyList(),
            action = RuleAction(ActionType.WAIT, null)
        ))
        registry.register(Rule(
            id = "high", name = "High Priority", priority = 100,
            conditions = emptyList(),
            action = RuleAction(ActionType.TAP, null)
        ))

        val ordered = registry.getOrderedRules()
        assertEquals("high", ordered[0].id)
        assertEquals("low", ordered[1].id)
    }

    @Test
    fun `disabled rules are excluded from evaluation`() {
        val rule = Rule(
            id = "disabled", name = "Disabled Rule", priority = 50,
            conditions = emptyList(),
            action = RuleAction(ActionType.WAIT, null),
            isEnabled = false
        )
        registry.register(rule)
        val ordered = registry.getOrderedRules()
        assertTrue(ordered.none { it.id == "disabled" })
    }

    // ---- Rule Evaluator Tests ----

    @Test
    fun `SCREEN_TYPE condition matches correctly`() {
        val evaluator = RuleEvaluator(memoryEngine)
        every { memoryEngine.hasKey(any()) } returns false

        val condition = RuleCondition(
            type = ConditionType.SCREEN_TYPE,
            operator = ConditionOperator.EQUALS,
            value = ScreenType.DIALOG
        )

        val context = RuleEvaluator.EvaluationContext(
            currentState = AgentState.CAPTURING,
            screenType = ScreenType.DIALOG,
            detectedElements = emptyList(),
            ocrText = "",
            retryCount = 0,
            lastActionTimestamp = 0L,
            sessionId = "test"
        )

        assertTrue(evaluator.evaluate(
            Rule("R1", "Test", 10, listOf(condition), RuleAction(ActionType.TAP, null)),
            context
        ))
    }

    @Test
    fun `negated condition works correctly`() {
        val evaluator = RuleEvaluator(memoryEngine)
        every { memoryEngine.hasKey(any()) } returns false

        val condition = RuleCondition(
            type = ConditionType.SCREEN_TYPE,
            operator = ConditionOperator.EQUALS,
            value = ScreenType.DIALOG,
            negate = true  // NOT DIALOG
        )

        val context = RuleEvaluator.EvaluationContext(
            currentState = AgentState.CAPTURING,
            screenType = ScreenType.HOME,  // Not a dialog
            detectedElements = emptyList(),
            ocrText = "",
            retryCount = 0,
            lastActionTimestamp = 0L,
            sessionId = "test"
        )

        assertTrue(evaluator.evaluate(
            Rule("R2", "Test", 10, listOf(condition), RuleAction(ActionType.TAP, null)),
            context
        ))
    }

    @Test
    fun `TEXT_CONTAINS condition matches case-insensitively`() {
        val evaluator = RuleEvaluator(memoryEngine)
        every { memoryEngine.hasKey(any()) } returns false

        val condition = RuleCondition(
            type = ConditionType.TEXT_CONTAINS,
            operator = ConditionOperator.CONTAINS,
            value = "ERROR"
        )

        val context = RuleEvaluator.EvaluationContext(
            currentState = AgentState.CAPTURING,
            screenType = ScreenType.UNKNOWN,
            detectedElements = emptyList(),
            ocrText = "An error occurred. Please retry.",
            retryCount = 0,
            lastActionTimestamp = 0L,
            sessionId = "test"
        )

        assertTrue(evaluator.evaluate(
            Rule("R3", "Test", 10, listOf(condition), RuleAction(ActionType.TAP, null)),
            context
        ))
    }

    @Test
    fun `RETRY_COUNT_LESS_THAN condition evaluates correctly`() {
        val evaluator = RuleEvaluator(memoryEngine)
        every { memoryEngine.hasKey(any()) } returns false

        val condition = RuleCondition(
            type = ConditionType.RETRY_COUNT_LESS_THAN,
            operator = ConditionOperator.LESS_THAN,
            value = 3
        )

        val contextWithLowRetries = RuleEvaluator.EvaluationContext(
            currentState = AgentState.CAPTURING,
            screenType = ScreenType.UNKNOWN,
            detectedElements = emptyList(),
            ocrText = "",
            retryCount = 1,  // Less than 3
            lastActionTimestamp = 0L,
            sessionId = "test"
        )

        assertTrue(evaluator.evaluate(
            Rule("R4", "Test", 10, listOf(condition), RuleAction(ActionType.RETRY, null)),
            contextWithLowRetries
        ))

        val contextWithHighRetries = contextWithLowRetries.copy(retryCount = 5)
        assertFalse(evaluator.evaluate(
            Rule("R4", "Test", 10, listOf(condition), RuleAction(ActionType.RETRY, null)),
            contextWithHighRetries
        ))
    }
}
