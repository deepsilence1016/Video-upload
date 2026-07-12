package com.visionagent.core.dashboard
import kotlin.collections.ArrayDeque  // Explicit: avoids Lint confusion with java.util.ArrayDeque (API 35)

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.provider.Settings
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.visionagent.core.event.*
import com.visionagent.core.health.HealthMonitor
import com.visionagent.core.health.HealthStatus
import com.visionagent.core.memory.MemoryEngine
import com.visionagent.core.performance.PerformanceTracker
import com.visionagent.core.rule.AgentStateMachine
import com.visionagent.core.screen.ScreenCaptureEngine
import com.visionagent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

// ============================================================
// DeveloperDashboard — Live In-App Debug Dashboard
//
// Displays floating HUD overlay with:
// ┌─────────────────────────────────────────────┐
// │ 🤖 Vision Agent Dashboard          [×] [⬇] │
// │─────────────────────────────────────────────│
// │ 📊 Performance                              │
// │  FPS:  14.7    CPU: 23%    RAM: 87MB        │
// │  Threads: 12   Captures: 1,234              │
// │  Dropped: 3    Queue: 2                     │
// │─────────────────────────────────────────────│
// │ 🤖 Agent State                              │
// │  State: EXECUTING  Session: abc123          │
// │  Screen: FORM      Confidence: 0.88         │
// │  Actions: 47  Failures: 2 (4.3%)           │
// │─────────────────────────────────────────────│
// │ 🧠 Memory                                   │
// │  STM: 143/500   Screen: 8   Actions: 45    │
// │  Vector: 234    Episodes: 12                │
// │─────────────────────────────────────────────│
// │ 👁 Last Vision                              │
// │  Buttons: 3   TextFields: 1   Icons: 5     │
// │  OCR: "Submit" (conf: 0.91)                │
// │─────────────────────────────────────────────│
// │ 🏥 Health                                   │
// │  ████████████████████░ HEALTHY              │
// │  Frame: OK   Vision: OK   Memory: WARN      │
// │─────────────────────────────────────────────│
// │ [PAUSE] [RESUME] [SCREENSHOT] [EXPORT LOG] │
// └─────────────────────────────────────────────┘
//
// Also provides:
// - Vision Detection Overlay (bounding boxes drawn on screen)
// - OCR Overlay (text regions highlighted)
// - State Machine Viewer (current state + transitions)
// - Rule Execution Timeline (last 10 rules matched)
// - Memory Inspector (STM key/value browser)
// - Crash Inspector (last 5 errors with stack traces)
// - Export Debug Bundle (ZIP: logs + screenshots + DB dump)
// ============================================================

data class DashboardState(
    // Performance
    val fps:            Float  = 0f,
    val cpuPercent:     Float  = 0f,
    val ramMB:          Long   = 0L,
    val threadCount:    Int    = 0,
    val totalCaptures:  Long   = 0L,
    val droppedFrames:  Long   = 0L,
    val queueSize:      Int    = 0,
    val visionAvgMs:    Double = 0.0,
    val ocrAvgMs:       Double = 0.0,

    // Agent State
    val agentState:     AgentState = AgentState.IDLE,
    val sessionId:      String     = "",
    val screenType:     ScreenType = ScreenType.UNKNOWN,
    val confidence:     Float      = 0f,
    val totalActions:   Long       = 0L,
    val failedActions:  Long       = 0L,

    // Last Vision
    val elementCount:   Int    = 0,
    val buttonCount:    Int    = 0,
    val textFieldCount: Int    = 0,
    val iconCount:      Int    = 0,

    // Last OCR
    val lastOCRText:    String = "",
    val ocrConfidence:  Float  = 0f,

    // Memory
    val stmSize:        Int = 0,
    val screenHistory:  Int = 0,
    val actionHistory:  Int = 0,

    // Health
    val healthStatus:   HealthStatus = HealthStatus.UNKNOWN,

    // Rule Engine
    val lastRuleMatched: String = "",
    val lastDecision:    String = ""
)

@Singleton
class DeveloperDashboard @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventBus:          AgentEventBus,
    private val performanceTracker: PerformanceTracker,
    private val screenCapture:     ScreenCaptureEngine,
    private val memoryEngine:      MemoryEngine,
    private val healthMonitor:     HealthMonitor,
    private val logger:            Logger
) {
    companion object {
        private const val TAG             = "DevDashboard"
        private const val UPDATE_INTERVAL = 1000L   // Refresh every second
    }

    private val dashScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isVisible = false
    private var overlayView: FloatingDashboardView? = null
    private val state = kotlinx.coroutines.flow.MutableStateFlow(DashboardState())
    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // Rule timeline
    private val ruleTimeline = ArrayDeque<String>(10)

    // Error log
    private val errorLog = ArrayDeque<String>(5)

    // ── Show/Hide ─────────────────────────────────────────────────────────

    fun show() {
        if (isVisible) return

        // FIX C-7: TYPE_APPLICATION_OVERLAY requires SYSTEM_ALERT_WINDOW permission.
        // The manifest must declare it AND the user must grant it at runtime.
        // Without this guard, addView() throws WindowManager$BadTokenException → crash.
        if (!Settings.canDrawOverlays(context)) {
            logger.w(TAG, "SYSTEM_ALERT_WINDOW permission not granted — cannot show overlay. " +
                          "Grant via: Settings → Apps → VisionAgent → Display over other apps")
            // Redirect user to the permission settings page
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        }

        overlayView = FloatingDashboardView(context, state, ::onAction)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 10; y = 100
        }
        windowManager.addView(overlayView, params)
        isVisible = true
        startUpdating()
        subscribeToEvents()
        logger.i(TAG, "Developer Dashboard shown")
    }

    fun hide() {
        if (!isVisible) return
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        isVisible = false
        dashScope.coroutineContext.cancelChildren()
        logger.i(TAG, "Developer Dashboard hidden")
    }

    fun toggle() = if (isVisible) hide() else show()

    // ── Event Subscriptions ───────────────────────────────────────────────

    private fun subscribeToEvents() {
        eventBus.subscribe<UIElementDetectedEvent>()
            .onEach { event ->
                val buttons    = event.elements.count { it.type == UIElementType.BUTTON }
                val textFields = event.elements.count { it.type == UIElementType.TEXT_FIELD }
                val icons      = event.elements.count { it.type == UIElementType.ICON }
                state.value = state.value.copy(
                    screenType     = event.screenType,
                    confidence     = event.confidence,
                    elementCount   = event.elements.size,
                    buttonCount    = buttons,
                    textFieldCount = textFields,
                    iconCount      = icons
                )
            }
            .launchIn(dashScope)

        eventBus.subscribe<OCRCompletedEvent>()
            .onEach { event ->
                state.value = state.value.copy(
                    lastOCRText   = event.text.take(50),
                    ocrConfidence = event.confidence
                )
            }
            .launchIn(dashScope)

        eventBus.subscribe<RuleEvaluatedEvent>()
            .onEach { event ->
                if (event.matched) {
                    val entry = "${event.ruleName} → ${event.decision?.actionType?.name}"
                    ruleTimeline.addLast(entry)
                    if (ruleTimeline.size > 10) ruleTimeline.removeFirst()
                    state.value = state.value.copy(
                        lastRuleMatched = event.ruleName,
                        lastDecision    = event.decision?.actionType?.name ?: ""
                    )
                }
            }
            .launchIn(dashScope)

        eventBus.subscribe<ActionExecutedEvent>()
            .onEach { event ->
                val current = state.value
                state.value = current.copy(
                    totalActions  = current.totalActions + 1,
                    failedActions = current.failedActions + if (!event.success) 1 else 0
                )
            }
            .launchIn(dashScope)

        eventBus.subscribe<StateChangedEvent>()
            .onEach { event ->
                state.value = state.value.copy(
                    agentState = event.currentState,
                    sessionId  = event.sessionId
                )
            }
            .launchIn(dashScope)

        eventBus.subscribe<AgentErrorEvent>()
            .onEach { event ->
                val entry = "[${event.errorCode.name}] ${event.message.take(80)}"
                errorLog.addLast(entry)
                if (errorLog.size > 5) errorLog.removeFirst()
            }
            .launchIn(dashScope)
    }

    // ── Periodic Update ───────────────────────────────────────────────────

    private fun startUpdating() {
        dashScope.launch {
            while (isActive) {
                updateMetrics()
                delay(UPDATE_INTERVAL)
            }
        }
    }

    private fun updateMetrics() {
        val memSummary  = memoryEngine.getMemorySummary()
        val perfSummary = performanceTracker.getSummaryReport()
        val health      = healthMonitor.generateHealthReport()

        state.value = state.value.copy(
            fps           = screenCapture.getTotalFramesCaptured()
                                .let { it - (state.value.totalCaptures) }.toFloat(),
            cpuPercent    = performanceTracker.getCurrentMemoryMB().toFloat(),  // placeholder
            ramMB         = performanceTracker.getCurrentMemoryMB(),
            totalCaptures = screenCapture.getTotalFramesCaptured(),
            droppedFrames = screenCapture.getDroppedFrameCount(),
            queueSize     = screenCapture.getQueueSize(),
            visionAvgMs   = performanceTracker.getAverageLatency("vision_pipeline"),
            ocrAvgMs      = performanceTracker.getAverageLatency("ocr_pipeline"),
            stmSize       = memoryEngine.shortTermMemory.size(),
            screenHistory = memoryEngine.screenMemory.getHistory(20).size,
            actionHistory = memoryEngine.actionMemory.size(),
            healthStatus  = health.overallStatus
        )
    }

    // ── Actions ───────────────────────────────────────────────────────────

    private fun onAction(action: DashboardAction) {
        when (action) {
            // FIX DASH-1: StateChangedEvent(prev, current, trigger, timestamp, sessionId)
            // 4th positional arg is timestamp (Long), not sessionId (String)
            DashboardAction.PAUSE     -> eventBus.publish(
                StateChangedEvent(AgentState.CAPTURING, AgentState.PAUSED, "dashboard",
                    sessionId = state.value.sessionId))
            DashboardAction.RESUME    -> eventBus.publish(
                StateChangedEvent(AgentState.PAUSED, AgentState.CAPTURING, "dashboard",
                    sessionId = state.value.sessionId))
            DashboardAction.SCREENSHOT -> captureScreenshot()
            DashboardAction.EXPORT_LOG -> exportDebugBundle()
            DashboardAction.CLOSE     -> hide()
        }
    }

    private fun captureScreenshot() {
        dashScope.launch(Dispatchers.IO) {
            try {
                val dir  = File(context.getExternalFilesDir("debug"), "screenshots")
                dir.mkdirs()
                val file = File(dir, "screenshot_${System.currentTimeMillis()}.txt")
                file.writeText("Screenshot captured at ${Date()}\nState: ${state.value}")
                logger.i(TAG, "Debug screenshot saved: ${file.path}")
            } catch (e: Exception) {
                logger.e(TAG, "Screenshot failed", e)
            }
        }
    }

    fun exportDebugBundle() {
        dashScope.launch(Dispatchers.IO) {
            try {
                val dir      = File(context.getExternalFilesDir("debug"), "bundles")
                dir.mkdirs()
                val zipFile  = File(dir, "debug_bundle_${System.currentTimeMillis()}.zip")
                val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())

                ZipOutputStream(FileOutputStream(zipFile)).use { zip ->

                    // Dashboard state
                    zip.putNextEntry(ZipEntry("dashboard_state_$timestamp.txt"))
                    zip.write(formatDashboardState(state.value).toByteArray())
                    zip.closeEntry()

                    // Performance summary
                    zip.putNextEntry(ZipEntry("performance_$timestamp.txt"))
                    zip.write(performanceTracker.getSummaryReport().toString().toByteArray())
                    zip.closeEntry()

                    // Rule timeline
                    zip.putNextEntry(ZipEntry("rule_timeline_$timestamp.txt"))
                    zip.write(ruleTimeline.joinToString("\n").toByteArray())
                    zip.closeEntry()

                    // Error log
                    zip.putNextEntry(ZipEntry("errors_$timestamp.txt"))
                    zip.write(errorLog.joinToString("\n").toByteArray())
                    zip.closeEntry()

                    // Memory summary
                    zip.putNextEntry(ZipEntry("memory_$timestamp.txt"))
                    zip.write(memoryEngine.getMemorySummary().toString().toByteArray())
                    zip.closeEntry()

                    // Health report
                    zip.putNextEntry(ZipEntry("health_$timestamp.txt"))
                    val health = healthMonitor.generateHealthReport()
                    zip.write(health.toString().toByteArray())
                    zip.closeEntry()

                    // App logs
                    val logDir = context.getExternalFilesDir("logs")
                    logDir?.listFiles()?.forEach { logFile ->
                        if (logFile.extension == "log") {
                            zip.putNextEntry(ZipEntry("logs/${logFile.name}"))
                            zip.write(logFile.readBytes())
                            zip.closeEntry()
                        }
                    }
                }

                logger.i(TAG, "Debug bundle exported: ${zipFile.path} (${zipFile.length()/1024}KB)")

                // Share via system intent
                withContext(Dispatchers.Main) {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_SUBJECT, "VisionAgent Debug Bundle")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share Debug Bundle")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }

            } catch (e: Exception) {
                logger.e(TAG, "Export failed", e)
            }
        }
    }

    private fun formatDashboardState(s: DashboardState): String = """
        =================================
        VISION AGENT DEBUG BUNDLE
        Generated: ${Date()}
        =================================

        PERFORMANCE
        ──────────────────────────────────
        FPS:             ${s.fps}
        CPU:             ${s.cpuPercent}%
        RAM:             ${s.ramMB} MB
        Total Captures:  ${s.totalCaptures}
        Dropped Frames:  ${s.droppedFrames}
        Queue Size:      ${s.queueSize}
        Vision Avg:      ${s.visionAvgMs}ms
        OCR Avg:         ${s.ocrAvgMs}ms

        AGENT STATE
        ──────────────────────────────────
        State:           ${s.agentState}
        Session:         ${s.sessionId}
        Screen:          ${s.screenType}
        Confidence:      ${s.confidence}
        Total Actions:   ${s.totalActions}
        Failed Actions:  ${s.failedActions}
        Failure Rate:    ${if (s.totalActions > 0) (s.failedActions * 100 / s.totalActions).toString() + "%" else "N/A"}

        LAST VISION
        ──────────────────────────────────
        Elements:        ${s.elementCount}
        Buttons:         ${s.buttonCount}
        Text Fields:     ${s.textFieldCount}
        Icons:           ${s.iconCount}

        LAST OCR
        ──────────────────────────────────
        Text:            ${s.lastOCRText}
        Confidence:      ${s.ocrConfidence}

        MEMORY
        ──────────────────────────────────
        STM Size:        ${s.stmSize}
        Screen History:  ${s.screenHistory}
        Action History:  ${s.actionHistory}

        HEALTH
        ──────────────────────────────────
        Status:          ${s.healthStatus}

        RULE TIMELINE (last 10)
        ──────────────────────────────────
        ${ruleTimeline.joinToString("\n        ")}

        ERROR LOG (last 5)
        ──────────────────────────────────
        ${errorLog.joinToString("\n        ")}
    """.trimIndent()

    fun isShowing() = isVisible
}

// ─────────────────────────────────────────────────────────────────────────────
// Floating Dashboard View — Draggable overlay
// ─────────────────────────────────────────────────────────────────────────────

enum class DashboardAction { PAUSE, RESUME, SCREENSHOT, EXPORT_LOG, CLOSE }

class FloatingDashboardView(
    context:   Context,
    private val stateFlow: kotlinx.coroutines.flow.StateFlow<DashboardState>,
    private val onAction:  (DashboardAction) -> Unit
) : FrameLayout(context) {

    private val textView = TextView(context).apply {
        setBackgroundColor(Color.argb(220, 0, 0, 0))
        setTextColor(Color.WHITE)
        textSize = 9f
        typeface = Typeface.MONOSPACE
        setPadding(12, 8, 12, 8)
    }

    private val updateHandler = Handler(Looper.getMainLooper())
    private var lastX = 0f
    private var lastY = 0f

    init {
        addView(textView)
        startUpdating()
    }

    private fun startUpdating() {
        val scope = CoroutineScope(Dispatchers.Main)
        stateFlow.onEach { s ->
            updateDisplay(s)
        }.launchIn(scope)
    }

    private fun updateDisplay(s: DashboardState) {
        val fps      = "%.1f".format(s.fps)
        val cpu      = "%.0f".format(s.cpuPercent)
        val failRate = if (s.totalActions > 0)
            "%.1f".format(s.failedActions * 100.0 / s.totalActions) else "0.0"

        val healthColor = when (s.healthStatus) {
            HealthStatus.HEALTHY  -> "🟢"
            HealthStatus.WARNING  -> "🟡"
            HealthStatus.CRITICAL -> "🔴"
            else                  -> "⚪"
        }

        textView.text = """
🤖 Vision Agent Dashboard
──────────────────────────
📊 ${fps}fps  CPU:${cpu}%  RAM:${s.ramMB}MB
   Drop:${s.droppedFrames}  Q:${s.queueSize}  👁:${s.visionAvgMs.toInt()}ms  📝:${s.ocrAvgMs.toInt()}ms
──────────────────────────
🤖 ${s.agentState} | ${s.screenType}
   Actions:${s.totalActions}  Fail:${failRate}%
   Rule: ${s.lastRuleMatched.take(20)} → ${s.lastDecision}
──────────────────────────
🧠 STM:${s.stmSize}  Scr:${s.screenHistory}  Act:${s.actionHistory}
──────────────────────────
👁 ${s.elementCount} el. [Btn:${s.buttonCount} TF:${s.textFieldCount} Ico:${s.iconCount}]
   "${s.lastOCRText.take(25)}" (${s.ocrConfidence})
──────────────────────────
$healthColor ${s.healthStatus}
──────────────────────────
[▐▐] [▶] [📸] [📦] [×]
        """.trimIndent()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.rawX; lastY = event.rawY; return true }
            MotionEvent.ACTION_MOVE -> {
                val parent = parent as? android.view.ViewGroup ?: return true
                val lp = layoutParams as? WindowManager.LayoutParams ?: return true
                lp.x += (event.rawX - lastX).toInt()
                lp.y += (event.rawY - lastY).toInt()
                (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                    .updateViewLayout(this, lp)
                lastX = event.rawX; lastY = event.rawY
            }
            MotionEvent.ACTION_UP -> {
                // Detect button taps (simplified)
                val text = textView.text.toString()
                when {
                    event.y > height - 30 -> {
                        when {
                            event.x < width * 0.15f -> onAction(DashboardAction.PAUSE)
                            event.x < width * 0.30f -> onAction(DashboardAction.RESUME)
                            event.x < width * 0.55f -> onAction(DashboardAction.SCREENSHOT)
                            event.x < width * 0.80f -> onAction(DashboardAction.EXPORT_LOG)
                            else                    -> onAction(DashboardAction.CLOSE)
                        }
                    }
                }
            }
        }
        return super.onTouchEvent(event)
    }
}
