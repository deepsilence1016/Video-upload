package com.visionagent.core.report
import kotlin.collections.ArrayDeque  // Explicit: avoids Lint confusion with java.util.ArrayDeque (API 35)

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import com.visionagent.core.crash.CrashSnapshot
import com.visionagent.core.event.*
import com.visionagent.core.health.HealthMonitor
import com.visionagent.core.memory.MemoryEngine
import com.visionagent.core.performance.PerformanceTracker
import com.visionagent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
// AutoBugReport — AI-readable Crash Report Generator
//
// On crash or manual trigger, generates:
// ┌─────────────────────────────────────────────────────┐
// │ bug_report_<timestamp>.zip                          │
// │  ├── 📄 summary.md          ← AI-readable summary   │
// │  ├── 📊 crash_snapshot.json ← Full crash state     │
// │  ├── 📱 device_info.json    ← Device metadata       │
// │  ├── ⚡ performance.json    ← CPU/RAM/FPS metrics   │
// │  ├── 🧠 memory_dump.json   ← STM + action history  │
// │  ├── 🏥 health_report.json ← Module health status  │
// │  ├── 📜 logcat.txt          ← Last 200 log lines    │
// │  ├── 📸 screenshot.jpg      ← Screen at crash time  │
// │  ├── 🔄 event_timeline.json ← Last 100 events      │
// │  └── 🔧 rule_timeline.txt   ← Last 10 rules fired  │
// └─────────────────────────────────────────────────────┘
//
// summary.md is written in structured format that an AI
// (ChatGPT/Claude) can directly analyze to suggest fixes.
// ============================================================

@Serializable
data class BugReportManifest(
    val reportId:       String,
    val generatedAt:    Long,
    val agentVersion:   String,
    val crashType:      String,
    val severityLevel:  String,
    val files:          List<String>,
    val aiPromptHint:   String
)

@Singleton
class AutoBugReport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventBus:           AgentEventBus,
    private val memoryEngine:       MemoryEngine,
    private val performanceTracker: PerformanceTracker,
    private val healthMonitor:      HealthMonitor,
    private val logger:             Logger
) {
    companion object {
        private const val TAG        = "AutoBugReport"
        private val DATE_FMT         = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    }

    private val json       = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val reportDir  = File(context.getExternalFilesDir(null), "bug_reports").also { it.mkdirs() }

    // Screenshot captured by ScreenCaptureEngine (set externally)
    var lastScreenshot: ByteArray? = null

    // Rule execution timeline
    private val ruleTimeline = ArrayDeque<String>(10)
    private val errorEvents  = ArrayDeque<AgentErrorEvent>(20)

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Generate a full bug report ZIP.
     * Can be called manually or automatically on crash.
     * Returns path to generated ZIP file.
     */
    suspend fun generate(
        crashSnapshot: CrashSnapshot? = null,
        sessionId:     String         = "",
        triggerReason: String         = "manual"
    ): File = withContext(Dispatchers.IO) {

        val reportId  = java.util.UUID.randomUUID().toString().take(8).uppercase()
        val timestamp = DATE_FMT.format(Date())
        val zipName   = "bug_report_${timestamp}_${reportId}.zip"
        val zipFile   = File(reportDir, zipName)

        logger.i(TAG, "Generating bug report: $reportId")

        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->

            // 1. AI-readable summary (most important)
            val summary = generateMarkdownSummary(reportId, crashSnapshot, triggerReason, sessionId)
            zip.addTextEntry("summary.md", summary)

            // 2. Crash snapshot JSON
            crashSnapshot?.let { snap ->
                zip.addTextEntry("crash_snapshot.json", json.encodeToString(snap))
            }

            // 3. Device info
            zip.addTextEntry("device_info.json", json.encodeToString(buildDeviceInfo()))

            // 4. Performance metrics
            zip.addTextEntry("performance.json", buildPerformanceJson())

            // 5. Memory dump
            zip.addTextEntry("memory_dump.json", buildMemoryDump())

            // 6. Health report
            val health = healthMonitor.generateHealthReport()
            zip.addTextEntry("health_report.json", json.encodeToString(health.toString()))

            // 7. Logcat
            val logcat = captureLogcat(200)
            zip.addTextEntry("logcat.txt", logcat)

            // 8. Screenshot
            lastScreenshot?.let { screenshotBytes ->
                zip.putNextEntry(ZipEntry("screenshot.jpg"))
                zip.write(screenshotBytes)
                zip.closeEntry()
            }

            // 9. Event timeline
            crashSnapshot?.let { snap ->
                val timeline = snap.last100Events.joinToString("\n") { event ->
                    "[${DATE_FMT.format(Date(event.timestampMs))}] ${event.eventType} (seq=${event.sequenceIdx})"
                }
                zip.addTextEntry("event_timeline.txt", timeline)
            }

            // 10. Rule timeline
            zip.addTextEntry("rule_timeline.txt", ruleTimeline.joinToString("\n"))

            // 11. App logs
            addLogFiles(zip)

            // 12. Manifest
            val manifest = BugReportManifest(
                reportId      = reportId,
                generatedAt   = System.currentTimeMillis(),
                agentVersion  = com.visionagent.BuildConfig.AGENT_VERSION,
                crashType     = crashSnapshot?.crashType ?: "NONE",
                severityLevel = if (crashSnapshot != null) "HIGH" else "INFO",
                files         = listOf("summary.md","crash_snapshot.json","device_info.json",
                                       "performance.json","memory_dump.json","health_report.json",
                                       "logcat.txt","screenshot.jpg","event_timeline.txt",
                                       "rule_timeline.txt","app_logs/"),
                aiPromptHint  = "Please analyze this bug report and suggest: 1) Root cause, 2) Fix, 3) Prevention"
            )
            zip.addTextEntry("manifest.json", json.encodeToString(manifest))
        }

        logger.i(TAG, "Bug report generated: ${zipFile.path} (${zipFile.length()/1024}KB)")
        zipFile
    }

    // ── Summary Generator (AI-readable Markdown) ──────────────────────────

    private fun generateMarkdownSummary(
        reportId:      String,
        crash:         CrashSnapshot?,
        triggerReason: String,
        sessionId:     String
    ): String {
        val health = healthMonitor.generateHealthReport()
        val memSummary = memoryEngine.getMemorySummary()

        return buildString {
            appendLine("# Vision Agent Bug Report — $reportId")
            appendLine("**Generated:** ${Date()}")
            appendLine("**Trigger:** $triggerReason")
            appendLine("**Agent Version:** ${com.visionagent.BuildConfig.AGENT_VERSION}")
            appendLine("**Session:** $sessionId")
            appendLine()

            appendLine("---")
            appendLine()
            appendLine("## 🔴 Crash Information")
            if (crash != null) {
                appendLine("- **Type:** ${crash.crashType}")
                appendLine("- **Thread:** ${crash.thread}")
                appendLine("- **Agent State at Crash:** ${crash.agentState}")
                appendLine("- **Last Screen:** ${crash.lastScreenType}")
                appendLine()
                appendLine("### Stack Trace")
                appendLine("```")
                appendLine(crash.stackTrace.take(3000))
                appendLine("```")
            } else {
                appendLine("_No crash — report generated manually_")
            }

            appendLine()
            appendLine("---")
            appendLine()
            appendLine("## 📱 Device")
            crash?.deviceInfo?.let { d ->
                appendLine("| Field | Value |")
                appendLine("|-------|-------|")
                appendLine("| Manufacturer | ${d.manufacturer} |")
                appendLine("| Model | ${d.model} |")
                appendLine("| Android API | ${d.apiLevel} (${d.androidVersion}) |")
                appendLine("| CPU ABI | ${d.cpuAbi} |")
                appendLine("| Total RAM | ${d.totalRamMB}MB |")
                appendLine("| Available RAM | ${d.availRamMB}MB |")
                appendLine("| Free Storage | ${d.storageFreeMB}MB |")
            } ?: appendLine("_Device info not available_")

            appendLine()
            appendLine("---")
            appendLine()
            appendLine("## ⚡ Performance at Crash Time")
            crash?.performanceAtCrash?.let { p ->
                appendLine("| Metric | Value |")
                appendLine("|--------|-------|")
                appendLine("| RAM Used | ${p.ramUsedMB}MB |")
                appendLine("| FPS | ${p.fps} |")
                appendLine("| Vision Avg | ${p.visionAvgMs}ms |")
                appendLine("| OCR Avg | ${p.ocrAvgMs}ms |")
                appendLine("| Active Threads | ${p.threadCount} |")
            }

            appendLine()
            appendLine("---")
            appendLine()
            appendLine("## 🏥 Module Health at Report Time")
            appendLine("**Overall Status:** ${health.overallStatus}")
            appendLine()
            appendLine("| Module | Status | Value |")
            appendLine("|--------|--------|-------|")
            listOf(health.frameCapture, health.visionPipeline, health.ocrPipeline,
                   health.memoryUsage, health.errorRate, health.batteryDrain)
                .forEach { m ->
                    appendLine("| ${m.module} | ${m.status} | ${m.value}${m.unit} |")
                }

            appendLine()
            appendLine("---")
            appendLine()
            appendLine("## 🔄 Last 10 Events Before Crash")
            crash?.last100Events?.takeLast(10)?.forEach { event ->
                appendLine("- [${Date(event.timestampMs)}] **${event.eventType}** (seq=${event.sequenceIdx})")
            }

            appendLine()
            appendLine("---")
            appendLine()
            appendLine("## 🎯 Last 5 Actions")
            crash?.actionHistory?.take(5)?.forEach { action ->
                appendLine("- $action")
            }

            appendLine()
            appendLine("---")
            appendLine()
            appendLine("## 🧠 Memory State")
            appendLine("| Layer | Size |")
            appendLine("|-------|------|")
            memSummary.forEach { (k, v) ->
                appendLine("| $k | $v |")
            }

            appendLine()
            appendLine("---")
            appendLine()
            appendLine("## ⚙️ Rule Timeline (last 10 fired)")
            ruleTimeline.forEach { appendLine("- $it") }

            appendLine()
            appendLine("---")
            appendLine()
            appendLine("## 🤖 AI Analysis Prompt")
            appendLine("""
```
You are a senior Android engineer analyzing a Vision Agent crash report.

Context:
- The app is a Vision Agent that captures and analyzes Android screens
- It uses Kotlin + C++ (OpenCV, Tesseract) + Rust
- Crash occurred in: ${crash?.thread ?: "unknown"} thread
- Agent state was: ${crash?.agentState ?: "unknown"}

Given the stack trace and event timeline above, please:
1. Identify the ROOT CAUSE of the crash
2. Explain WHY it happened (memory issue? race condition? null pointer?)
3. Suggest a SPECIFIC code fix
4. Suggest how to PREVENT this class of bugs in future
5. Estimate SEVERITY (P0/P1/P2/P3)

Be specific and actionable. Reference the exact file/line if visible in the stack trace.
```
            """.trimIndent())
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun buildDeviceInfo(): Map<String, Any> = mapOf(
        "manufacturer"   to Build.MANUFACTURER,
        "model"          to Build.MODEL,
        "api_level"      to Build.VERSION.SDK_INT,
        "android"        to Build.VERSION.RELEASE,
        "cpu_abi"        to Build.SUPPORTED_ABIS.toList(),
        "agent_version"  to com.visionagent.BuildConfig.AGENT_VERSION,
        "debug_build"    to com.visionagent.BuildConfig.DEBUG
    )

    private fun buildPerformanceJson(): String {
        val report = performanceTracker.getSummaryReport()
        return json.encodeToString(report.mapValues { (_, v) -> v.toString() })
    }

    private fun buildMemoryDump(): String {
        val summary = memoryEngine.getMemorySummary()
        val stmSample = memoryEngine.shortTermMemory.getAll()
            .entries.take(20)
            .associate { (k, v) -> k to v.value.take(50) }
        val actions = memoryEngine.actionMemory
            .getRecentActions(10)
            .map { val s = if (it.success) "OK" else "FAIL"; "${it.actionType}→$s (${it.durationMs}ms)" }

        return json.encodeToString(mapOf(
            "summary"    to summary.mapValues { it.value.toString() },
            "stm_sample" to stmSample,
            "actions"    to actions
        ))
    }

    private fun captureLogcat(lines: Int): String = try {
        Runtime.getRuntime()
            .exec(arrayOf("logcat", "-d", "-t", lines.toString(), "-v", "time", "VisionAgent:V", "*:S"))
            .inputStream.bufferedReader().readText().take(100_000)
    } catch (e: Exception) { "Logcat unavailable: ${e.message}" }

    private fun addLogFiles(zip: ZipOutputStream) {
        context.getExternalFilesDir("logs")?.listFiles()?.forEach { file ->
            if (file.extension == "log" && file.length() > 0) {
                zip.putNextEntry(ZipEntry("app_logs/${file.name}"))
                zip.write(file.readBytes().takeLast(50_000).toByteArray())
                zip.closeEntry()
            }
        }
    }

    fun recordRuleExecution(ruleName: String, action: String) {
        ruleTimeline.addLast("${Date()}: $ruleName → $action")
        if (ruleTimeline.size > 10) ruleTimeline.removeFirst()
    }

    fun getReportDir(): File = reportDir

    fun shareReport(reportFile: File) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_SUBJECT, "Vision Agent Bug Report")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share Bug Report")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

// Extension
private fun ZipOutputStream.addTextEntry(name: String, content: String) {
    putNextEntry(ZipEntry(name))
    write(content.toByteArray(Charsets.UTF_8))
    closeEntry()
}
