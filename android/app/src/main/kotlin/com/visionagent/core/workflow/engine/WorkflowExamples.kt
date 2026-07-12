package com.visionagent.core.workflow.engine

import com.visionagent.core.event.ActionType
// WorkflowTriggerConfig is in same package (engine) — no import needed

// ============================================================
// WorkflowExamples — Ready-to-use Workflow templates
//
// इन्हें WorkflowEngine.register() में pass करो।
// ============================================================

object WorkflowExamples {

    // ── Example 1: Auto-dismiss dialogs ──────────────────────────────────
    val AUTO_DISMISS_DIALOG = Workflow(
        name        = "Auto Dismiss Unexpected Dialogs",
        description = "जब भी unexpected dialog आए, automatically dismiss करो",
        triggers    = listOf(WorkflowTriggerConfig(
            triggerType = "SCREEN_CHANGE",
            parameters = mapOf("screen_type" to "DIALOG")
        )),
        blocks      = listOf(
            LogBlock(message = "Dialog detected — trying to dismiss"),
            RetryBlock(
                maxAttempts = 3,
                delayMs     = 500L,
                body        = listOf(
                    VisionFindBlock(
                        elementType      = "BUTTON",
                        textContains     = "OK",
                        storeInVariable  = "ok_button"
                    ),
                    IfBlock(
                        condition  = WorkflowCondition("ok_button", ConditionOp.IS_NOT_EMPTY, ""),
                        thenBlocks = listOf(
                            ActionBlock(
                                label      = "Tap OK",
                                actionType = ActionType.TAP,
                                targetSelector = TargetSelector(byType = "BUTTON", byText = "OK")
                            )
                        ),
                        elseBlocks = listOf(
                            ActionBlock(label = "Navigate Back", actionType = ActionType.NAVIGATE_BACK)
                        )
                    )
                )
            ),
            LogBlock(message = "Dialog dismissed successfully")
        )
    )

    // ── Example 2: Error Screen Recovery ─────────────────────────────────
    val ERROR_RECOVERY = Workflow(
        name        = "Error Screen Auto Recovery",
        description = "Error screen detect होने पर retry करो",
        triggers    = listOf(WorkflowTriggerConfig(
            triggerType = "SCREEN_CHANGE",
            parameters = mapOf("screen_type" to "ERROR")
        )),
        blocks      = listOf(
            OCRReadBlock(storeInVariable = "error_text"),
            LogBlock(message = "Error screen: {error_text}"),
            SetVariableBlock(variableName = "retry_count", value = "0"),
            LoopBlock(
                loopType  = LoopType.WHILE,
                condition = WorkflowCondition("retry_count", ConditionOp.LESS_THAN, "3"),
                body      = listOf(
                    VisionFindBlock(
                        elementType     = "BUTTON",
                        textContains    = "Retry",
                        storeInVariable = "retry_btn"
                    ),
                    IfBlock(
                        condition  = WorkflowCondition("retry_btn", ConditionOp.IS_NOT_EMPTY, ""),
                        thenBlocks = listOf(
                            ActionBlock(label = "Tap Retry", actionType = ActionType.TAP,
                                targetSelector = TargetSelector(byText = "Retry")),
                            WaitBlock(waitType = WaitType.FIXED, durationMs = 2000)
                        ),
                        elseBlocks = listOf(
                            ActionBlock(label = "Go Back", actionType = ActionType.NAVIGATE_BACK)
                        )
                    ),
                    SetVariableBlock(
                        variableName = "retry_count",
                        value        = "{retry_count} + 1"
                    )
                )
            )
        )
    )

    // ── Example 3: Loading Screen Wait ───────────────────────────────────
    val LOADING_WAIT = Workflow(
        name        = "Loading Screen Wait",
        description = "Loading screen पर wait करो, timeout handle करो",
        triggers    = listOf(WorkflowTriggerConfig(
            triggerType = "SCREEN_CHANGE",
            parameters = mapOf("screen_type" to "LOADING")
        )),
        maxRuntime  = 30_000L,
        blocks      = listOf(
            WaitBlock(
                label      = "Wait for loading to finish",
                waitType   = WaitType.ELEMENT_DISAPPEARS,
                durationMs = 500,
                timeoutMs  = 15_000L
            ),
            LogBlock(message = "Loading complete"),
            OnErrorBlock(
                errorType = "timed out",
                handler   = listOf(
                    LogBlock(level = "WARN", message = "Loading timeout — navigating back"),
                    ActionBlock(label = "Back", actionType = ActionType.NAVIGATE_BACK)
                )
            )
        )
    )

    // ── Example 4: Daily Diagnostic ──────────────────────────────────────
    val DAILY_DIAGNOSTIC = Workflow(
        name        = "Daily Health Check",
        description = "हर 5 मिनट पर system health check",
        triggers    = listOf(WorkflowTriggerConfig(triggerType = "INTERVAL_5MIN")),
        blocks      = listOf(
            ScriptBlock(
                script         = "now()",
                storeResultIn  = "check_time"
            ),
            LogBlock(message = "Health check at {check_time}"),
            // In production: call SelfDiagnosticEngine.runFullDiagnostic()
            SetVariableBlock(variableName = "health_ok", value = "true"),
            IfBlock(
                condition  = WorkflowCondition("health_ok", ConditionOp.IS_FALSE, ""),
                thenBlocks = listOf(
                    LogBlock(level = "ERROR", message = "Health check failed!")
                )
            )
        )
    )

    // ── Example 5: Form Auto-fill ─────────────────────────────────────────
    fun createFormFillWorkflow(
        triggerOnScreen: String = "FORM",
        fieldMappings:   Map<String, String>  // elementText → value
    ): Workflow {
        val fillBlocks = fieldMappings.map { (fieldLabel, value) ->
            listOf(
                VisionFindBlock(
                    elementType     = "TEXT_FIELD",
                    textContains    = fieldLabel,
                    storeInVariable = "field_${fieldLabel.replace(" ", "_")}"
                ),
                ActionBlock(
                    label      = "Fill: $fieldLabel",
                    actionType = ActionType.TAP,
                    targetSelector = TargetSelector(byType = "TEXT_FIELD", byText = fieldLabel)
                ),
                ActionBlock(
                    label      = "Type: $value",
                    actionType = ActionType.TEXT_INPUT,
                    textInput  = value
                )
            )
        }.flatten()

        return Workflow(
            name    = "Auto-fill Form",
            triggers = listOf(WorkflowTriggerConfig(
                triggerType = "SCREEN_CHANGE",
                parameters = mapOf("screen_type" to triggerOnScreen)
            )),
            blocks  = fillBlocks + listOf(
                WaitBlock(waitType = WaitType.FIXED, durationMs = 500),
                LogBlock(message = "Form filled with ${fieldMappings.size} fields")
            )
        )
    }

    fun allExamples() = listOf(
        AUTO_DISMISS_DIALOG,
        ERROR_RECOVERY,
        LOADING_WAIT,
        DAILY_DIAGNOSTIC
    )
}
