package com.visionagent.presentation

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.visionagent.R
import com.visionagent.core.AgentConfig
import com.visionagent.core.AgentOrchestrator
import com.visionagent.core.screen.CaptureConfig
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * MainActivity — Vision Agent ka main entry point.
 *
 * Architecture:
 *   MainActivity → AgentOrchestrator (ek hi jagah sab kuch start/stop)
 *   AgentOrchestrator → ScreenCaptureEngine + VisionEngine + OCREngine
 *                     + RuleEngine + PlannerEngine + MemoryEngine
 *                     + ActionEngine + RecoveryEngine
 *
 * Flow:
 *   App open → UI dikhao → User START tap kare
 *   → Permissions check → MediaProjection popup (sirf ek baar)
 *   → AgentOrchestrator.initialize() → startAgent()
 *   → Sab engines chalu → Real-time screen capture + analysis
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    // ── Injected Dependencies ─────────────────────────────────────────────────
    @Inject lateinit var orchestrator: AgentOrchestrator

    companion object {
        private const val TAG                        = "MainActivity"
        private const val REQUEST_MEDIA_PROJECTION   = 1001
        private const val REQUEST_OVERLAY_PERMISSION = 1002

        // Session flag — survives rotation, resets on process death
        @Volatile private var agentStartedThisSession = false
    }

    // ── UI Views ──────────────────────────────────────────────────────────────
    private var tvStatus:      TextView? = null
    private var tvSession:     TextView? = null
    private var tvFrames:      TextView? = null
    private var tvState:       TextView? = null
    private var tvPermOverlay: TextView? = null
    private var tvPermA11y:    TextView? = null
    private var tvPermScreen:  TextView? = null
    private var btnStart:      Button?   = null
    private var btnStop:       Button?   = null

    // ── UI Update Handler ─────────────────────────────────────────────────────
    private val uiHandler   = Handler(Looper.getMainLooper())
    private val statsPoller = object : Runnable {
        override fun run() {
            try { refreshUI() } catch (e: Exception) { Log.w(TAG, "UI refresh error", e) }
            uiHandler.postDelayed(this, 1000)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
            bindViews()
            setupButtons()
            refreshUI()
        } catch (e: Exception) {
            Log.e(TAG, "onCreate error — showing fallback", e)
            showFallback("Starting Vision Agent...\n\n${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            refreshUI()
            uiHandler.post(statsPoller)
        } catch (e: Exception) { Log.w(TAG, "onResume error", e) }
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(statsPoller)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            try {
                agentStartedThisSession = false
                orchestrator.stopAgent()
            } catch (e: Exception) { Log.w(TAG, "onDestroy stopAgent error", e) }
        }
    }

    // ── View Binding ──────────────────────────────────────────────────────────

    private fun bindViews() {
        tvStatus      = findViewById(R.id.tv_status)
        tvSession     = findViewById(R.id.tv_session)
        tvFrames      = findViewById(R.id.tv_frames)
        tvState       = findViewById(R.id.tv_state)
        tvPermOverlay = findViewById(R.id.tv_perm_overlay)
        tvPermA11y    = findViewById(R.id.tv_perm_accessibility)
        tvPermScreen  = findViewById(R.id.tv_perm_projection)
        btnStart      = findViewById(R.id.btn_start)
        btnStop       = findViewById(R.id.btn_stop)
    }

    // ── Button Setup ──────────────────────────────────────────────────────────

    private fun setupButtons() {
        btnStart?.setOnClickListener {
            try { onStartTapped() }
            catch (e: Exception) {
                Log.e(TAG, "Start error", e)
                setStatus("❌ Error: ${e.message}")
            }
        }
        btnStop?.setOnClickListener {
            try { onStopTapped() }
            catch (e: Exception) { Log.e(TAG, "Stop error", e) }
        }
    }

    // ── Start Flow ────────────────────────────────────────────────────────────

    private fun onStartTapped() {
        if (orchestrator.isAgentRunning()) {
            setStatus("ℹ️ Agent already running")
            return
        }
        // Step 1: Overlay permission
        if (!Settings.canDrawOverlays(this)) {
            setStatus("⚙️ Allow 'Display over other apps' first...")
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")),
                REQUEST_OVERLAY_PERMISSION
            )
            return
        }
        // Step 2: MediaProjection permission
        requestScreenCapture()
    }

    private fun requestScreenCapture() {
        if (agentStartedThisSession && orchestrator.isAgentRunning()) return
        setStatus("📋 Please allow screen capture...")
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE)
            as android.media.projection.MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    private fun onStopTapped() {
        agentStartedThisSession = false
        orchestrator.stopAgent()
        setStatus("⏹ Agent stopped")
        refreshUI()
    }

    // ── Permission Results ────────────────────────────────────────────────────

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        try {
            when (requestCode) {
                REQUEST_OVERLAY_PERMISSION -> {
                    if (Settings.canDrawOverlays(this)) {
                        requestScreenCapture()
                    } else {
                        setStatus("❌ Overlay permission needed — tap START again")
                    }
                    refreshUI()
                }
                REQUEST_MEDIA_PROJECTION -> {
                    if (resultCode == Activity.RESULT_OK && data != null) {
                        startAgent(resultCode, data)
                    } else {
                        setStatus("❌ Screen capture denied — tap START to retry")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onActivityResult error", e)
            setStatus("❌ ${e.message}")
        }
    }

    // ── Agent Start ───────────────────────────────────────────────────────────

    private fun startAgent(resultCode: Int, projectionData: Intent) {
        setStatus("🔄 Initializing all engines...")

        // Build config
        val config = AgentConfig(
            captureConfig = CaptureConfig(
                targetFps       = 15,
                maxQueueSize    = 10,
                enableROI       = true,
                roiChangeThreshold = 0.02f
            ),
            // Tessdata path — will be copied from assets on first run
            tessDataPath    = "${filesDir.absolutePath}/tessdata",
            enablePlanner   = true,
            enableRecovery  = true,
            enablePerformanceTracking = true
        )

        try {
            // Initialize all engines via Orchestrator
            orchestrator.initialize(config)

            // Copy tessdata from assets if needed (first-time setup)
            copyTessDataIfNeeded()

            // Start: ScreenCapture + Vision + OCR + Rules all begin
            // Note: ActionEngine needs AccessibilityService — it starts
            // automatically when user enables it in Settings.
            // For now we start without it (capture + analysis still works)
            orchestrator.startCaptureOnly(resultCode, projectionData)

            agentStartedThisSession = true
            setStatus("🟢 Agent running — screen capture + analysis active")
            refreshUI()

        } catch (e: Exception) {
            Log.e(TAG, "startAgent error", e)
            setStatus("❌ Start failed: ${e.message}")
        }
    }

    // ── Tessdata Setup ────────────────────────────────────────────────────────

    private fun copyTessDataIfNeeded() {
        val tessDir = java.io.File(filesDir, "tessdata")
        tessDir.mkdirs()
        val engFile = java.io.File(tessDir, "eng.traineddata")
        if (!engFile.exists()) {
            try {
                assets.open("tessdata/eng.traineddata").use { input ->
                    engFile.outputStream().use { output -> input.copyTo(output) }
                }
                Log.i(TAG, "Tessdata copied to ${tessDir.absolutePath}")
            } catch (e: Exception) {
                // eng.traineddata not in assets yet — OCR will skip, rest works
                Log.w(TAG, "tessdata not in assets — OCR disabled: ${e.message}")
            }
        }
    }

    // ── UI Refresh ────────────────────────────────────────────────────────────

    private fun refreshUI() {
        val running = try { orchestrator.isAgentRunning() } catch (e: Exception) { false }

        btnStart?.isEnabled = !running
        btnStop?.isEnabled  = running

        // Session & frames
        if (running) {
            tvSession?.text = "Session: ${orchestrator.getCurrentSessionId().take(8)}..."
            tvState?.text   = "State: ${orchestrator.getCurrentState()}"
            tvFrames?.text  = "Frames: —"
        } else {
            tvSession?.text = "Session: —"
            tvState?.text   = "State: IDLE"
            tvFrames?.text  = "Frames: —"
        }

        // Permissions
        refreshPermStatus()
    }

    private fun refreshPermStatus() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val am         = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val hasA11y    = am.isEnabled
        val running    = try { orchestrator.isAgentRunning() } catch (e: Exception) { false }

        fun perm(view: TextView?, label: String, granted: Boolean) {
            view?.text = if (granted) "✅  $label" else "⬜  $label"
            view?.setTextColor(if (granted) 0xFF00C853.toInt() else 0xFFAAAAAA.toInt())
        }

        perm(tvPermOverlay, "Display over other apps", hasOverlay)
        perm(tvPermA11y,    "Accessibility service", hasA11y)
        perm(tvPermScreen,  "Screen capture", running)
    }

    private fun setStatus(msg: String) {
        tvStatus?.text = msg
    }

    // ── Fallback screen ───────────────────────────────────────────────────────

    private fun showFallback(msg: String) {
        val tv = TextView(this).apply {
            text = "🤖 Vision Agent\n\n$msg"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(48, 0, 48, 0)
        }
        setContentView(android.widget.FrameLayout(this).apply {
            setBackgroundColor(0xFF0A0A0A.toInt())
            addView(tv, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            ).also { it.gravity = android.view.Gravity.CENTER })
        })
    }
}
