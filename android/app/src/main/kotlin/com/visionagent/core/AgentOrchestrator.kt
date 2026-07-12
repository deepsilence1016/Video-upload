package com.visionagent.core

import android.content.Intent
import com.visionagent.core.action.ActionEngine
import com.visionagent.core.action.AgentAccessibilityService
import com.visionagent.core.event.*
import com.visionagent.core.memory.MemoryEngine
import com.visionagent.core.ocr.OCRConfig
import com.visionagent.core.ocr.OCREngine
import com.visionagent.core.performance.PerformanceTracker
import com.visionagent.core.planner.AgentGoal
import com.visionagent.core.planner.GoalType
import com.visionagent.core.planner.PlannerEngine
import com.visionagent.core.recovery.RecoveryEngine
import com.visionagent.core.rule.RuleEngine
import com.visionagent.core.screen.CaptureConfig
import com.visionagent.core.screen.ScreenCaptureEngine
import com.visionagent.core.vision.VisionConfig
import com.visionagent.core.vision.VisionEngine
import com.visionagent.data.local.database.AgentDatabase
import com.visionagent.data.local.entity.SessionEntity
import com.visionagent.utils.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// ============================================================
// AgentOrchestrator — Central Coordination Layer
//
// This is the TOP-LEVEL coordinator that:
// 1. Initializes all engines in the correct order
// 2. Manages the agent lifecycle (start → process → stop)
// 3. Coordinates inter-module communication via EventBus
// 4. Manages session lifecycle
// 5. Handles graceful shutdown and cleanup
//
// Principle: Orchestrator knows ABOUT modules but modules
//            know NOTHING about each other (loose coupling).
//            All communication via EventBus.
//
// Pattern: Mediator Pattern
// ============================================================

data class AgentConfig(
    val captureConfig: CaptureConfig = CaptureConfig(),
    val visionConfig: VisionConfig = VisionConfig(),
    val ocrConfig: OCRConfig = OCRConfig(),
    val tessDataPath: String = "/data/user/0/com.visionagent.app/files/tessdata",
    val enablePlanner: Boolean = true,
    val enableRecovery: Boolean = true,
    val enablePerformanceTracking: Boolean = true
)

@Singleton
class AgentOrchestrator @Inject constructor(
    private val eventBus: AgentEventBus,
    private val screenCaptureEngine: ScreenCaptureEngine,
    private val visionEngine: VisionEngine,
    private val ocrEngine: OCREngine,
    private val ruleEngine: RuleEngine,
    private val plannerEngine: PlannerEngine,
    private val memoryEngine: MemoryEngine,
    private val actionEngine: ActionEngine,
    private val recoveryEngine: RecoveryEngine,
    private val performanceTracker: PerformanceTracker,
    private val database: AgentDatabase,
    private val logger: Logger
) {

    companion object {
        private const val TAG = "AgentOrchestrator"
    }

    private val orchestratorScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + CoroutineName("AgentOrchestrator")
    )

    private var currentSessionId: String = ""
    private var agentConfig: AgentConfig = AgentConfig()
    private var isRunning = false

    // ============================================================
    // Agent Lifecycle
    // ============================================================

    /**
     * Initialize all engines — call once on app start
     */
    fun initialize(config: AgentConfig = AgentConfig()) {
        this.agentConfig = config

        logger.i(TAG, "Initializing Vision Agent Framework v${com.visionagent.BuildConfig.AGENT_VERSION}")

        // Initialize in dependency order
        visionEngine.initialize(config.visionConfig)
        ocrEngine.initialize(config.ocrConfig, config.tessDataPath)
        ruleEngine.initialize()
        plannerEngine.initialize()
        recoveryEngine.initialize()
        screenCaptureEngine.initialize(config.captureConfig)

        // Subscribe to state changes
        subscribeToLifecycleEvents()

        logger.i(TAG, "All engines initialized successfully")
    }

    /**
     * Start agent with accessibility service (full mode — capture + vision + actions)
     */
    fun startAgent(
        mediaProjectionResultCode: Int,
        mediaProjectionIntent: Intent,
        accessibilityService: AgentAccessibilityService
    ) {
        if (isRunning) { logger.w(TAG, "Agent already running"); return }
        currentSessionId = UUID.randomUUID().toString()
        isRunning = true
        actionEngine.initialize(accessibilityService)
        orchestratorScope.launch { createSession() }
        ruleEngine.transitionState(AgentState.CAPTURING, "agent_start", currentSessionId)
        screenCaptureEngine.startCapture(mediaProjectionResultCode, mediaProjectionIntent, currentSessionId)
        logger.i(TAG, "Agent started (full) | session=$currentSessionId")
    }

    /**
     * Start agent WITHOUT accessibility service (capture + vision + OCR + rules, no tap/swipe).
     * Use this when accessibility permission not yet granted.
     * ActionEngine stays uninitialized — rules can fire but actions are no-ops.
     */
    fun startCaptureOnly(
        mediaProjectionResultCode: Int,
        mediaProjectionIntent: Intent
    ) {
        if (isRunning) { logger.w(TAG, "Agent already running"); return }
        currentSessionId = UUID.randomUUID().toString()
        isRunning = true
        orchestratorScope.launch { createSession() }
        ruleEngine.transitionState(AgentState.CAPTURING, "capture_only_start", currentSessionId)
        screenCaptureEngine.startCapture(mediaProjectionResultCode, mediaProjectionIntent, currentSessionId)
        logger.i(TAG, "Agent started (capture+vision only, no actions) | session=$currentSessionId")
    }

    /**
     * Upgrade running capture-only session to full agent (when accessibility becomes available)
     */
    fun upgradeToFullAgent(accessibilityService: AgentAccessibilityService) {
        if (!isRunning) { logger.w(TAG, "Agent not running"); return }
        actionEngine.initialize(accessibilityService)
        logger.i(TAG, "Agent upgraded to full mode — actions enabled")
    }

    /**
     * Stop the agent gracefully
     */
    fun stopAgent() {
        if (!isRunning) return

        logger.i(TAG, "Stopping agent | session=$currentSessionId")

        screenCaptureEngine.stopCapture()

        orchestratorScope.launch {
            finalizeSession()
        }

        ruleEngine.transitionState(AgentState.TERMINATED, "agent_stop", currentSessionId)

        isRunning = false

        // Flush performance report
        val report = performanceTracker.getSummaryReport()
        logger.i(TAG, "Performance Summary: $report")
    }

    /**
     * Submit a high-level goal to be planned and executed
     */
    fun submitGoal(goalType: GoalType, parameters: Map<String, Any> = emptyMap()) {
        if (!isRunning) {
            logger.w(TAG, "Cannot submit goal — agent not running")
            return
        }

        val goal = AgentGoal(
            type = goalType,
            description = "Goal: $goalType",
            parameters = parameters
        )

        orchestratorScope.launch {
            plannerEngine.submitGoal(goal, currentSessionId)
        }
    }

    /**
     * Pause the agent (e.g., app goes to background)
     */
    fun pauseAgent() {
        if (!isRunning) return
        ruleEngine.transitionState(AgentState.PAUSED, "user_pause", currentSessionId)
        logger.i(TAG, "Agent paused")
    }

    /**
     * Resume the agent
     */
    fun resumeAgent() {
        if (!isRunning) return
        ruleEngine.transitionState(AgentState.CAPTURING, "user_resume", currentSessionId)
        logger.i(TAG, "Agent resumed")
    }

    // ============================================================
    // Event Subscriptions
    // ============================================================

    private fun subscribeToLifecycleEvents() {

        // Log all state changes
        eventBus.subscribe<StateChangedEvent>()
            .onEach { event ->
                logger.i(TAG, "State: ${event.previousState} → ${event.currentState} | trigger=${event.trigger}")
                memoryEngine.storeSTM("agent_state", event.currentState.name, sessionId = event.sessionId)
            }
            .launchIn(orchestratorScope)

        // Log performance metrics to DB
        eventBus.subscribe<PerformanceMetricEvent>()
            .onEach { event ->
                savePerformanceLog(event)
            }
            .launchIn(orchestratorScope)

        // Log errors to DB
        eventBus.subscribe<AgentErrorEvent>()
            .onEach { event ->
                saveErrorLog(event)
                if (event.isFatal) {
                    logger.e(TAG, "FATAL: ${event.errorCode} — ${event.message}")
                    stopAgent()
                }
            }
            .launchIn(orchestratorScope)

        // Update screen memory on every vision result
        eventBus.subscribe<UIElementDetectedEvent>()
            .onEach { event ->
                val ocrText = memoryEngine.getSTM("last_ocr_text") ?: ""
                memoryEngine.recordScreen(event.screenType, event.elements, ocrText, event.sessionId)
            }
            .launchIn(orchestratorScope)

        // Cache last OCR result
        eventBus.subscribe<OCRCompletedEvent>()
            .onEach { event ->
                memoryEngine.storeSTM("last_ocr_text", event.text, ttlMs = 5000L, sessionId = event.sessionId)
            }
            .launchIn(orchestratorScope)

        // Track action completion
        eventBus.subscribe<ActionExecutedEvent>()
            .onEach { event ->
                logger.d(TAG, "Action: ${event.actionType} | success=${event.success} | dur=${event.durationMs}ms")
            }
            .launchIn(orchestratorScope)

        // Log recovery events
        eventBus.subscribe<RecoveryTriggeredEvent>()
            .onEach { event ->
                logger.w(TAG, "Recovery: ${event.recoveryType} | attempt=${event.attemptNumber}")
            }
            .launchIn(orchestratorScope)
    }

    // ============================================================
    // Database Operations
    // ============================================================

    private suspend fun createSession() {
        database.sessionDao().insert(
            SessionEntity(
                sessionId = currentSessionId,
                startedAt = System.currentTimeMillis(),
                agentVersion = com.visionagent.BuildConfig.AGENT_VERSION,
                appPackage = "com.visionagent.app"
            )
        )
        logger.d(TAG, "Session created: $currentSessionId")
    }

    private suspend fun finalizeSession() {
        database.sessionDao().getById(currentSessionId)?.let { session ->
            database.sessionDao().update(
                session.copy(
                    endedAt = System.currentTimeMillis(),
                    totalFrames = screenCaptureEngine.getTotalFramesCaptured(),
                    totalActions = performanceTracker.getOperationCount("action_execution"),
                    isSuccessful = true
                )
            )
        }
        // Clean expired memory
        database.memoryDao().deleteExpired()
        logger.d(TAG, "Session finalized: $currentSessionId")
    }

    private suspend fun savePerformanceLog(event: PerformanceMetricEvent) {
        database.performanceLogDao().insert(
            com.visionagent.data.local.entity.PerformanceLogEntity(
                sessionId = event.sessionId,
                module = event.module,
                operation = event.operationName,
                durationMs = event.durationMs,
                memoryBytes = event.memoryUsageBytes,
                cpuPercent = event.cpuPercent,
                timestamp = event.timestamp
            )
        )
    }

    private suspend fun saveErrorLog(event: AgentErrorEvent) {
        database.errorLogDao().insert(
            com.visionagent.data.local.entity.ErrorLogEntity(
                sessionId = event.sessionId,
                errorCode = event.errorCode.name,
                message = event.message,
                stackTrace = event.stackTrace,
                isFatal = event.isFatal,
                timestamp = event.timestamp
            )
        )
    }

    // ---- Status ----
    fun getCurrentSessionId() = currentSessionId
    fun isAgentRunning() = isRunning
    fun getMemorySummary() = memoryEngine.getMemorySummary()
    fun getPerformanceSummary() = performanceTracker.getSummaryReport()
    fun getCurrentState() = ruleEngine.getCurrentState()
}
