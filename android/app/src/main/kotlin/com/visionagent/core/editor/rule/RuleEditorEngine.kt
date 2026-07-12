package com.visionagent.core.editor.rule
import kotlin.collections.ArrayDeque  // Explicit: avoids Lint confusion with java.util.ArrayDeque (API 35)

import com.visionagent.core.event.*
import com.visionagent.core.memory.MemoryEngine
import com.visionagent.core.rule.*
import com.visionagent.utils.Logger
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

// FIX C-10: Extract the minimum interface that RuleEvaluator actually needs from MemoryEngine.
// This breaks the circular dependency and eliminates the null!! constructor hack.
// RuleEditorEngine only needs to know if a key exists in STM — nothing else.
fun interface MemoryKeyStore {
    fun hasKey(key: String): Boolean
}

// ============================================================
// RuleEditorEngine — In-app Rule Editor (No rebuild needed)
//
// Rules को app के अंदर से edit/add/delete करो।
// Build दोबारा करने की ज़रूरत नहीं।
//
// Features:
//  - Live rule list (with enable/disable toggle)
//  - Add new rule (JSON editor or visual)
//  - Edit existing rule
//  - Delete rule
//  - Test rule (simulate against current screen state)
//  - Import rules from JSON file
//  - Export rules to JSON file
//  - Rule execution history (last 50 matches)
//
// JSON Rule Format:
// {
//   "id": "MY_RULE_001",
//   "name": "Custom Rule",
//   "priority": 100,
//   "conditions": [
//     {"type": "SCREEN_TYPE", "operator": "EQUALS", "value": "DIALOG"}
//   ],
//   "action": {
//     "actionType": "TAP",
//     "targetSelector": {"byText": "OK"}
//   },
//   "retryConfig": {"maxRetries": 3, "initialDelayMs": 500}
// }
// ============================================================

data class RuleTestResult(
    val ruleId:   String,
    val matched:  Boolean,
    val reason:   String,
    val decision: AgentDecision?
)

data class RuleHistoryEntry(
    val ruleId:     String,
    val ruleName:   String,
    val matched:    Boolean,
    val timestamp:  Long,
    val screenType: ScreenType
)

@Singleton
class RuleEditorEngine @Inject constructor(
    private val ruleEngine:    RuleEngine,
    private val memoryEngine:  MemoryEngine,  // FIX C-10: inject real MemoryEngine
    private val eventBus:      AgentEventBus,
    private val logger:        Logger
) {
    companion object {
        private const val TAG       = "RuleEditor"
        private const val MAX_HISTORY = 50
    }

    private val json    = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val history = ArrayDeque<RuleHistoryEntry>(MAX_HISTORY)
    private var lastScreenType = ScreenType.UNKNOWN
    private var lastElements   = emptyList<DetectedUIElement>()
    private var lastOCR        = ""

    init {
        // Track current state for rule testing
        CoroutineScope(Dispatchers.Default).launch {
            eventBus.subscribe<UIElementDetectedEvent>()
                .collect { event ->
                    lastScreenType = event.screenType
                    lastElements   = event.elements
                }
        }
        CoroutineScope(Dispatchers.Default).launch {
            eventBus.subscribe<OCRCompletedEvent>()
                .collect { event -> lastOCR = event.text }
        }
        CoroutineScope(Dispatchers.Default).launch {
            eventBus.subscribe<RuleEvaluatedEvent>()
                .collect { event ->
                    if (history.size >= MAX_HISTORY) history.removeFirst()
                    history.addLast(RuleHistoryEntry(
                        ruleId     = event.ruleId,
                        ruleName   = event.ruleName,
                        matched    = event.matched,
                        timestamp  = event.timestamp,
                        screenType = lastScreenType
                    ))
                }
        }
    }

    // ── Rule CRUD ─────────────────────────────────────────────────────────

    fun addRuleFromJson(jsonStr: String): Result<Rule> {
        return try {
            val rule = json.decodeFromString<Rule>(jsonStr)
            validateRule(rule).getOrThrow()
            ruleEngine.registerRule(rule)
            logger.i(TAG, "Rule added: ${rule.name} (${rule.id})")
            Result.success(rule)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun addRule(rule: Rule): Result<Rule> {
        return try {
            validateRule(rule).getOrThrow()
            ruleEngine.registerRule(rule)
            logger.i(TAG, "Rule added: ${rule.name}")
            Result.success(rule)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun deleteRule(ruleId: String) {
        ruleEngine.ruleRegistry.unregister(ruleId)
        logger.i(TAG, "Rule deleted: $ruleId")
    }

    fun enableRule(ruleId: String) {
        val rules = ruleEngine.ruleRegistry.getOrderedRules()
        rules.find { it.id == ruleId }?.let { rule ->
            ruleEngine.ruleRegistry.unregister(ruleId)
            ruleEngine.registerRule(rule.copy(isEnabled = true))
            logger.i(TAG, "Rule enabled: $ruleId")
        }
    }

    fun disableRule(ruleId: String) {
        ruleEngine.ruleRegistry.disable(ruleId)
        logger.i(TAG, "Rule disabled: $ruleId")
    }

    fun updateRule(rule: Rule): Result<Rule> {
        ruleEngine.ruleRegistry.unregister(rule.id)
        return addRule(rule)
    }

    // ── Rule Testing ──────────────────────────────────────────────────────

    fun testRule(rule: Rule, sessionId: String = "test"): RuleTestResult {
        // FIX C-10: Pass a MemoryKeyStore lambda backed by the real MemoryEngine.
        // No null!! or fake constructor needed.
        val evaluator = RuleEvaluator(memoryEngine)
        val context   = RuleEvaluator.EvaluationContext(
            currentState    = AgentState.CAPTURING,
            screenType      = lastScreenType,
            detectedElements = lastElements,
            ocrText         = lastOCR,
            retryCount      = 0,
            lastActionTimestamp = 0L,
            sessionId       = sessionId
        )

        val matched = evaluator.evaluate(rule, context)
        logger.d(TAG, "Rule test: ${rule.name} → matched=$matched (screen=$lastScreenType)")

        return RuleTestResult(
            ruleId   = rule.id,
            matched  = matched,
            reason   = if (matched) "All conditions met" else "One or more conditions failed",
            decision = if (matched) {
                AgentDecision(
                    actionType = rule.action.actionType,
                    target     = null,
                    confidence = 0.9f,
                    reasoning  = "Test match"
                )
            } else null
        )
    }

    fun testAllRules(sessionId: String = "test"): List<RuleTestResult> =
        ruleEngine.ruleRegistry.getOrderedRules().map { testRule(it, sessionId) }

    // ── Import/Export ──────────────────────────────────────────────────────

    fun exportRules(): String {
        val rules = ruleEngine.ruleRegistry.getOrderedRules()
        return json.encodeToString(rules)
    }

    fun exportRulesToFile(file: File): Boolean {
        return try {
            file.writeText(exportRules())
            logger.i(TAG, "Rules exported: ${file.path}")
            true
        } catch (e: Exception) {
            logger.e(TAG, "Export failed", e)
            false
        }
    }

    fun importRulesFromFile(file: File): Result<Int> {
        return try {
            val jsonStr = file.readText()
            val rules   = json.decodeFromString<List<Rule>>(jsonStr)
            rules.forEach { ruleEngine.registerRule(it) }
            logger.i(TAG, "Imported ${rules.size} rules from ${file.name}")
            Result.success(rules.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Rule Templates ────────────────────────────────────────────────────

    fun getTemplates(): List<Rule> = listOf(
        Rule(
            id         = "TMPL_DIALOG_DISMISS",
            name       = "[Template] Dismiss Any Dialog",
            priority   = 200,
            conditions = listOf(RuleCondition(ConditionType.SCREEN_TYPE, ConditionOperator.EQUALS, ScreenType.DIALOG)),
            action     = RuleAction(ActionType.TAP, ElementSelector(byText = "OK")),
            tags       = setOf("template", "dialog")
        ),
        Rule(
            id         = "TMPL_LOADING_WAIT",
            name       = "[Template] Wait on Loading Screen",
            priority   = 100,
            conditions = listOf(RuleCondition(ConditionType.SCREEN_TYPE, ConditionOperator.EQUALS, ScreenType.LOADING)),
            action     = RuleAction(ActionType.WAIT, null, mapOf("durationMs" to 2000L)),
            tags       = setOf("template", "loading")
        ),
        Rule(
            id         = "TMPL_ERROR_RETRY",
            name       = "[Template] Retry on Error",
            priority   = 150,
            conditions = listOf(
                RuleCondition(ConditionType.SCREEN_TYPE, ConditionOperator.EQUALS, ScreenType.ERROR),
                RuleCondition(ConditionType.RETRY_COUNT_LESS_THAN, ConditionOperator.LESS_THAN, 3)
            ),
            action     = RuleAction(ActionType.TAP, ElementSelector(byText = "Retry")),
            tags       = setOf("template", "error")
        ),
        Rule(
            id         = "TMPL_TEXT_TRIGGER",
            name       = "[Template] Trigger on OCR Text",
            priority   = 80,
            conditions = listOf(RuleCondition(ConditionType.TEXT_CONTAINS, ConditionOperator.CONTAINS, "your_keyword_here")),
            action     = RuleAction(ActionType.TAP, ElementSelector(byType = UIElementType.BUTTON)),
            tags       = setOf("template", "ocr")
        )
    )

    fun getRuleHistory(): List<RuleHistoryEntry> = history.toList().reversed()

    fun getAllRules(): List<Rule> = ruleEngine.ruleRegistry.getOrderedRules()

    fun getRuleCount(): Int = ruleEngine.ruleRegistry.size()

    // ── Validation ────────────────────────────────────────────────────────

    private fun validateRule(rule: Rule): Result<Unit> {
        if (rule.id.isBlank())   return Result.failure(IllegalArgumentException("Rule ID cannot be empty"))
        if (rule.name.isBlank()) return Result.failure(IllegalArgumentException("Rule name cannot be empty"))
        if (rule.conditions.isEmpty()) return Result.failure(IllegalArgumentException("Rule must have at least one condition"))
        if (rule.priority < 0 || rule.priority > 10000) return Result.failure(IllegalArgumentException("Priority must be 0-10000"))
        return Result.success(Unit)
    }
    // FIX C-10: memoryEngineProxy() removed — it contained null!! which throws NPE
    // at construction time. Now using the injected MemoryEngine directly.
}
