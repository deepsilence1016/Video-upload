package com.visionagent.core.workflow.macro

import com.visionagent.core.event.*
import com.visionagent.core.workflow.engine.*
import com.visionagent.utils.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// ============================================================
// MacroRecorder — Record & Replay User Actions
//
// अपने ऐप में user actions रिकॉर्ड करो,
// और बाद में exact sequence replay करो।
//
// Record करता है:
// - TAP (coordinates + element info)
// - SCROLL / SWIPE (direction + distance)
// - TEXT INPUT (what was typed)
// - WAIT (timing between actions)
// - SCREENSHOT (captured frame reference)
//
// Output: Workflow JSON जो WorkflowEngine में directly चल सके
//
// Use Cases:
// - Repetitive tasks automate करो
// - Test scenarios record करो
// - Tutorial flows capture करो
// - Regression test cases बनाओ
// ============================================================

@Serializable
data class RecordedAction(
    val sequenceIdx:    Int,
    val actionType:     ActionType,
    val x:              Float?    = null,
    val y:              Float?    = null,
    val text:           String?   = null,
    val elementType:    String?   = null,
    val elementText:    String?   = null,
    val confidence:     Float     = 0f,
    val screenTypeBefore: ScreenType,
    val timestampMs:    Long      = System.currentTimeMillis(),
    val durationMs:     Long      = 0L    // How long the action took
)

@Serializable
data class RecordedMacro(
    val macroId:      String = UUID.randomUUID().toString(),
    val name:         String,
    val description:  String = "",
    val createdAt:    Long   = System.currentTimeMillis(),
    val actions:      List<RecordedAction>,
    val totalDuration:Long,
    val screenFlow:   List<String>   // List of screen types visited
)

@Singleton
class MacroRecorder @Inject constructor(
    private val eventBus: AgentEventBus,
    private val logger:   Logger
) {
    companion object { private const val TAG = "MacroRecorder" }

    private val json         = Json { prettyPrint = true }
    private val recorderScope= CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var isRecording    = false
    private val recordedActions = mutableListOf<RecordedAction>()
    private var recordingName  = ""
    private var recordingStart = 0L
    private var sequenceIdx    = 0
    private var lastScreenType = ScreenType.UNKNOWN
    private val screenFlow     = mutableListOf<String>()
    private var lastActionTime = 0L

    // ── Recording Control ──────────────────────────────────────────────────

    fun startRecording(name: String) {
        if (isRecording) {
            logger.w(TAG, "Already recording — stop current recording first")
            return
        }
        recordedActions.clear()
        screenFlow.clear()
        sequenceIdx    = 0
        recordingName  = name
        recordingStart = System.currentTimeMillis()
        lastActionTime = recordingStart
        isRecording    = true
        subscribeToEvents()
        logger.i(TAG, "📹 Recording started: '$name'")
    }

    fun stopRecording(): RecordedMacro? {
        if (!isRecording) return null
        isRecording = false
        recorderScope.coroutineContext.cancelChildren()

        val macro = RecordedMacro(
            name          = recordingName,
            actions       = recordedActions.toList(),
            totalDuration = System.currentTimeMillis() - recordingStart,
            screenFlow    = screenFlow.distinct()
        )
        logger.i(TAG, "📹 Recording stopped: ${recordedActions.size} actions in ${macro.totalDuration}ms")
        return macro
    }

    fun isRecording() = isRecording

    // ── Event Subscriptions (during recording) ─────────────────────────────

    private fun subscribeToEvents() {
        // FIX IG-3: ActionExecutedEvent does not carry coordinates.
        // Subscribe to RuleEvaluatedEvent INSTEAD (or additionally) because that event
        // carries the AgentDecision which has the target element with bounds.
        // We record from RuleEvaluatedEvent (intent) rather than ActionExecutedEvent (outcome)
        // so we capture: element type, element text, and can derive coordinates at replay.
        eventBus.subscribe<RuleEvaluatedEvent>()
            .onEach { event ->
                if (!isRecording || !event.matched) return@onEach
                val decision = event.decision ?: return@onEach
                val now      = System.currentTimeMillis()
                val elapsed  = now - lastActionTime
                lastActionTime = now

                // Insert WAIT block if significant pause between actions
                if (elapsed > 500 && recordedActions.isNotEmpty()) {
                    recordedActions.add(RecordedAction(
                        sequenceIdx      = sequenceIdx++,
                        actionType       = ActionType.WAIT,
                        screenTypeBefore = lastScreenType,
                        durationMs       = elapsed
                    ))
                }

                // Extract coordinates from the decision target bounds
                val target = decision.target
                recordedActions.add(RecordedAction(
                    sequenceIdx      = sequenceIdx++,
                    actionType       = decision.actionType,
                    x                = target?.bounds?.let { ((it.left + it.right) / 2).toFloat() },
                    y                = target?.bounds?.let { ((it.top + it.bottom) / 2).toFloat() },
                    elementType      = target?.type?.name,
                    elementText      = target?.text?.take(100),
                    confidence       = decision.confidence,
                    screenTypeBefore = lastScreenType,
                    durationMs       = 0L  // filled in from ActionExecutedEvent correlation
                ))
                logger.v(TAG, "Recorded: ${decision.actionType} @ (${target?.bounds?.let{(it.left+it.right)/2}}, ${target?.bounds?.let{(it.top+it.bottom)/2}})")
            }
            .launchIn(recorderScope)

        eventBus.subscribe<UIElementDetectedEvent>()
            .onEach { event ->
                if (!isRecording) return@onEach
                lastScreenType = event.screenType
                if (screenFlow.lastOrNull() != event.screenType.name) {
                    screenFlow.add(event.screenType.name)
                }
            }
            .launchIn(recorderScope)
    }

    // ── Macro → Workflow Conversion ────────────────────────────────────────

    /**
     * Convert a recorded macro to a WorkflowEngine-compatible Workflow.
     * The workflow can then be registered and re-triggered automatically.
     */
    fun macroToWorkflow(macro: RecordedMacro): Workflow {
        val blocks: List<WorkflowBlock> = macro.actions.flatMap { action ->
            when (action.actionType) {
                ActionType.WAIT -> listOf(WaitBlock(
                    label      = "Wait ${action.durationMs}ms",
                    waitType   = WaitType.FIXED,
                    durationMs = action.durationMs
                ))
                ActionType.TAP, ActionType.LONG_PRESS, ActionType.DOUBLE_TAP -> {
                    // If we have element info → use VisionFind + Action
                    if (action.elementType != null) {
                        // First find the element, then tap it
                        listOf(
                            VisionFindBlock(
                                label            = "Find ${action.elementType}",
                                elementType      = action.elementType,
                                textContains     = action.elementText,
                                storeInVariable  = "found_element",
                                failIfNotFound   = false
                            ),
                            ActionBlock(
                                label      = "${action.actionType.name} ${action.elementType}",
                                actionType = action.actionType,
                                targetSelector = TargetSelector(
                                    byType = action.elementType,
                                    byText = action.elementText
                                )
                            )
                        )
                    } else {
                        listOf(ActionBlock(
                            label      = "${action.actionType.name} (${action.x?.toInt()}, ${action.y?.toInt()})",
                            actionType = action.actionType
                        ))
                    }
                }
                ActionType.TEXT_INPUT -> listOf(
                    ActionBlock(
                        label      = "Type: ${action.text?.take(20)}",
                        actionType = ActionType.TEXT_INPUT,
                        textInput  = action.text
                    )
                )
                ActionType.SCROLL_DOWN, ActionType.SCROLL_UP,
                ActionType.SWIPE_LEFT, ActionType.SWIPE_RIGHT -> listOf(
                    ActionBlock(
                        label      = action.actionType.name,
                        actionType = action.actionType
                    )
                )
                ActionType.NAVIGATE_BACK -> listOf(
                    ActionBlock(
                        label      = "Navigate Back",
                        actionType = ActionType.NAVIGATE_BACK
                    )
                )
                else -> emptyList()
            }
        }

        return Workflow(
            name        = "Macro: ${macro.name}",
            description = "Auto-generated from recording. ${macro.actions.size} steps. ${macro.totalDuration}ms.",
            triggers    = listOf(WorkflowTriggerConfig(triggerType = "MANUAL")),
            blocks      = blocks,
            maxRuntime  = macro.totalDuration * 3L   // 3x recording time as timeout
        )
    }

    // ── Save/Load ──────────────────────────────────────────────────────────

    fun saveMacro(macro: RecordedMacro, directory: File): File {
        directory.mkdirs()
        val file = File(directory, "macro_${macro.macroId}.json")
        file.writeText(json.encodeToString(macro))
        logger.i(TAG, "Macro saved: ${file.path}")
        return file
    }

    fun loadMacro(file: File): RecordedMacro? = try {
        json.decodeFromString<RecordedMacro>(file.readText())
    } catch (e: Exception) {
        logger.e(TAG, "Failed to load macro: ${file.path}", e)
        null
    }
}
