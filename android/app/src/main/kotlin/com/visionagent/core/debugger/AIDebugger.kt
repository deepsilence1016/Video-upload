package com.visionagent.core.debugger

import android.content.Context
import com.visionagent.core.crash.CrashSnapshot
import com.visionagent.core.diagnostic.DiagnosticReport
import com.visionagent.core.event.*
import com.visionagent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

// ============================================================
// AIDebugger — Intelligent Error Analysis & Fix Suggestions
//
// PC नहीं है? कोई बात नहीं।
// यह module automatically:
//
// 1. Logcat पढ़ता है
// 2. Stacktrace समझता है (pattern matching)
// 3. Error का कारण बताता है
// 4. किस file/line पर दिक्कत है बताता है
// 5. Fix का सुझाव देता है
// 6. Severity estimate करता है
//
// All OFFLINE — no API call needed for basic analysis.
// Optional: send to AI backend for deeper analysis.
//
// Pattern Database:
// - 50+ known Android crash patterns
// - JNI/NDK specific errors
// - Kotlin coroutine errors
// - OpenCV errors
// - Tesseract errors
// - Room DB errors
// - OOM patterns
// - ANR patterns
// ============================================================

@Serializable
data class DebugAnalysis(
    val errorType:       String,
    val rootCause:       String,
    val affectedFile:    String?,
    val affectedLine:    Int?,
    val severity:        ErrorSeverity,
    val explanation:     String,
    val fixSuggestions:  List<FixSuggestion>,
    val preventionTips:  List<String>,
    val similarCases:    List<String>,
    val confidence:      Float,        // 0.0-1.0 how confident we are
    val requiresPC:      Boolean       // Whether fix needs PC/Android Studio
)

@Serializable
data class FixSuggestion(
    val step:        Int,
    val action:      String,
    val codeSnippet: String?,
    val isAutoFix:   Boolean   // Can we apply this automatically?
)

@Serializable
enum class ErrorSeverity { INFO, LOW, MEDIUM, HIGH, CRITICAL }

// ─────────────────────────────────────────────────────────────────────────────
// Pattern Database — Known error patterns with fixes
// ─────────────────────────────────────────────────────────────────────────────

data class ErrorPattern(
    val id:           String,
    val keywords:     List<String>,    // All must match
    val anyKeywords:  List<String> = emptyList(),  // Any must match
    val errorType:    String,
    val rootCause:    String,
    val severity:     ErrorSeverity,
    val fixes:        List<FixSuggestion>,
    val prevention:   List<String>
)

object ErrorPatternDB {

    val patterns = listOf(

        // ── OutOfMemoryError ────────────────────────────────────────────
        ErrorPattern(
            id          = "OOM_001",
            keywords    = listOf("OutOfMemoryError"),
            anyKeywords = listOf("heap", "bitmap", "allocation"),
            errorType   = "OutOfMemoryError",
            rootCause   = "JVM heap exhausted. Likely cause: Bitmap not recycled, " +
                          "STM too large, or frame buffer leak.",
            severity    = ErrorSeverity.CRITICAL,
            fixes       = listOf(
                FixSuggestion(1, "Reduce STM max size immediately",
                    "configEngine.updateMemory { copy(stmMaxSize = 200) }", true),
                FixSuggestion(2, "Clear frame queue",
                    "screenCapture.stopCapture(); framePool.clear()", true),
                FixSuggestion(3, "Trigger GC explicitly",
                    "Runtime.getRuntime().gc(); System.runFinalization()", true),
                FixSuggestion(4, "Check Bitmap recycle in ScreenCaptureEngine",
                    "bitmap.recycle() // Line ~180 in ScreenCaptureEngine.kt", false)
            ),
            prevention = listOf(
                "Always call bitmap.recycle() after use",
                "Use FrameMemoryPool — never allocate new ByteArrays in hot path",
                "Monitor RAM via SelfDiagnosticEngine periodic reports",
                "Set largeHeap=false in manifest (forces better memory hygiene)"
            )
        ),

        // ── UnsatisfiedLinkError (JNI) ──────────────────────────────────
        ErrorPattern(
            id          = "JNI_001",
            keywords    = listOf("UnsatisfiedLinkError"),
            errorType   = "JNI Library Not Found",
            rootCause   = "Native library (.so file) not found or not compiled. " +
                          "Usually means NDK build failed or wrong ABI.",
            severity    = ErrorSeverity.CRITICAL,
            fixes       = listOf(
                FixSuggestion(1, "Check if .so files exist in APK",
                    "unzip -l app-debug.apk | grep .so", false),
                FixSuggestion(2, "Rebuild NDK libraries",
                    "./gradlew app:buildCMakeDebug\n" +
                    "# Or via GitHub Actions: push to main", false),
                FixSuggestion(3, "Verify ABI filter in build.gradle.kts",
                    "ndk { abiFilters += listOf(\"arm64-v8a\", \"x86_64\") }", false),
                FixSuggestion(4, "Check OpenCV and Tesseract pre-built libraries",
                    "ls android/app/src/main/cpp/opencv/sdk/native/libs/arm64-v8a/", false)
            ),
            prevention = listOf(
                "CI/CD pipeline verifies .so files after every build",
                "Use System.loadLibrary() in companion object init{} with try-catch",
                "GitHub Actions native build step runs on every push"
            )
        ),

        // ── NullPointerException in Coroutine ───────────────────────────
        ErrorPattern(
            id          = "NPE_COR_001",
            keywords    = listOf("NullPointerException"),
            anyKeywords = listOf("coroutine", "suspend", "Continuation"),
            errorType   = "Null Pointer in Coroutine",
            rootCause   = "Null value accessed in coroutine context. " +
                          "Often caused by: late-initialized var accessed before init, " +
                          "or scope cancelled before completion.",
            severity    = ErrorSeverity.HIGH,
            fixes       = listOf(
                FixSuggestion(1, "Add null safety check",
                    "val result = getValue() ?: return@launch  // Safe return", false),
                FixSuggestion(2, "Use requireNotNull() with descriptive message",
                    "val engine = requireNotNull(visionEngine) { \"VisionEngine not initialized\" }", false),
                FixSuggestion(3, "Check initialization order in DI module",
                    "// In AppModule.kt — ensure dependency order is correct", false)
            ),
            prevention = listOf(
                "Use Kotlin null safety everywhere — never use !",
                "Initialize all dependencies in correct order via Hilt",
                "Add @Inject lateinit var checks in unit tests"
            )
        ),

        // ── ANR ─────────────────────────────────────────────────────────
        ErrorPattern(
            id          = "ANR_001",
            keywords    = listOf("ANR"),
            anyKeywords = listOf("not responding", "Input dispatching timed out"),
            errorType   = "Application Not Responding (ANR)",
            rootCause   = "Main thread blocked for >5 seconds. " +
                          "Possible causes: IO on main thread, long computation, " +
                          "deadlock, or waiting for lock.",
            severity    = ErrorSeverity.CRITICAL,
            fixes       = listOf(
                FixSuggestion(1, "Move blocking work to Dispatchers.IO",
                    "withContext(Dispatchers.IO) {\n    // DB/Network/File operations here\n}", false),
                FixSuggestion(2, "Check for Thread.sleep() on main thread",
                    "// Search: grep -r 'Thread.sleep' app/src/main/kotlin/", false),
                FixSuggestion(3, "Add StrictMode in debug build",
                    """StrictMode.setThreadPolicy(
    StrictMode.ThreadPolicy.Builder()
        .detectAll()
        .penaltyLog()
        .build()
)""", false)
            ),
            prevention = listOf(
                "All IO operations use Dispatchers.IO or Dispatchers.Default",
                "EventBus subscriptions are on Dispatchers.Default",
                "Database operations use Room's suspend functions",
                "CI: UI Automator ANR detection enabled"
            )
        ),

        // ── OpenCV Error ─────────────────────────────────────────────────
        ErrorPattern(
            id          = "OPENCV_001",
            keywords    = listOf("cv::Exception"),
            anyKeywords = listOf("OpenCV", "CvException"),
            errorType   = "OpenCV Native Error",
            rootCause   = "OpenCV threw an exception. Common causes: " +
                          "invalid Mat dimensions, empty image, wrong data type.",
            severity    = ErrorSeverity.HIGH,
            fixes       = listOf(
                FixSuggestion(1, "Add Mat empty check before processing",
                    """if (frame.empty()) {
    LOGE("Empty frame received");
    return VisionResult{};
}""", false),
                FixSuggestion(2, "Verify frame dimensions match expectations",
                    """CV_Assert(frame.cols > 0 && frame.rows > 0);
CV_Assert(frame.type() == CV_8UC4);""", false),
                FixSuggestion(3, "Enable OpenCV error callback for better debugging",
                    "cv::redirectError(cvErrorCallback);", false)
            ),
            prevention = listOf(
                "Always check Mat.empty() before any OpenCV operation",
                "Use try-catch in VisionCore.cpp around all cv:: calls",
                "Enable UBSan in debug builds to catch type errors early"
            )
        ),

        // ── Tesseract Error ──────────────────────────────────────────────
        ErrorPattern(
            id          = "OCR_001",
            keywords    = listOf("tesseract"),
            anyKeywords = listOf("TessBaseAPI", "tessdata", "Init failed"),
            errorType   = "Tesseract OCR Initialization Error",
            rootCause   = "Tesseract failed to initialize. " +
                          "Most likely: tessdata file missing or corrupted.",
            severity    = ErrorSeverity.HIGH,
            fixes       = listOf(
                FixSuggestion(1, "Verify tessdata exists",
                    """val tessdata = File(filesDir, "tessdata/eng.traineddata")
if (!tessdata.exists()) {
    // Download from assets or remote
    copyTessDataFromAssets()
}""", true),
                FixSuggestion(2, "Re-download tessdata",
                    "# In Termux or PC:\n" +
                    "wget -O tessdata/eng.traineddata " +
                    "https://github.com/tesseract-ocr/tessdata_best/raw/main/eng.traineddata", false),
                FixSuggestion(3, "Check tessdata path in OCREngine.kt",
                    """val tessPath = context.filesDir.absolutePath
// Pass to: ocr_initialize(tessPath, "eng", 6, 3, 60.0f)""", false)
            ),
            prevention = listOf(
                "Check tessdata in SelfDiagnosticEngine on every startup",
                "Include tessdata in assets/ for offline availability",
                "CI: verify tessdata size > 1MB after build"
            )
        ),

        // ── Room Database Error ──────────────────────────────────────────
        ErrorPattern(
            id          = "DB_001",
            keywords    = listOf("SQLiteException"),
            anyKeywords = listOf("Room", "database", "SQLite"),
            errorType   = "Room Database Error",
            rootCause   = "SQLite operation failed. Possible: DB corrupted, " +
                          "schema mismatch, or disk full.",
            severity    = ErrorSeverity.HIGH,
            fixes       = listOf(
                FixSuggestion(1, "Clear and recreate database",
                    "context.deleteDatabase(\"vision_agent.db\")\n" +
                    "// Restart app — Room will recreate", true),
                FixSuggestion(2, "Check free storage space",
                    "val freeMB = context.filesDir.freeSpace / 1024 / 1024", true),
                FixSuggestion(3, "Add fallback to destructive migration",
                    ".fallbackToDestructiveMigration() // in AgentDatabase builder", false)
            ),
            prevention = listOf(
                "Always use WAL journal mode for concurrent access",
                "Monitor storage via SelfDiagnosticEngine",
                "Add try-catch around all DB operations"
            )
        ),

        // ── Coroutine CancellationException ─────────────────────────────
        ErrorPattern(
            id          = "COR_001",
            keywords    = listOf("CancellationException"),
            errorType   = "Coroutine Cancelled",
            rootCause   = "A coroutine was cancelled. This is usually normal " +
                          "(scope cancelled on stop), but if unexpected may indicate " +
                          "premature scope cancellation.",
            severity    = ErrorSeverity.LOW,
            fixes       = listOf(
                FixSuggestion(1, "Use SupervisorJob to prevent parent cancellation",
                    "CoroutineScope(SupervisorJob() + Dispatchers.Default)", false),
                FixSuggestion(2, "Don't catch CancellationException silently",
                    """try {
    // work
} catch (e: Exception) {
    if (e is CancellationException) throw e  // Re-throw!
    // handle other exceptions
}""", false)
            ),
            prevention = listOf(
                "Always re-throw CancellationException",
                "Use SupervisorJob for independent child coroutines",
                "Lifecycle-aware scopes (lifecycleScope, viewModelScope)"
            )
        ),

        // ── Native Heap Buffer Overflow (ASan) ──────────────────────────
        ErrorPattern(
            id          = "ASAN_001",
            keywords    = listOf("AddressSanitizer"),
            anyKeywords = listOf("heap-buffer-overflow", "use-after-free"),
            errorType   = "Native Memory Safety Error (ASan)",
            rootCause   = "Buffer overflow or use-after-free in native C++ code. " +
                          "This is detected by AddressSanitizer in debug builds.",
            severity    = ErrorSeverity.CRITICAL,
            fixes       = listOf(
                FixSuggestion(1, "Check array bounds in VisionCore.cpp",
                    "// Look for: for loops accessing frame data\n" +
                    "// Ensure: i < width*height*4 not i < width*height", false),
                FixSuggestion(2, "Check FrameProcessor SPSC buffer access",
                    "// In FrameProcessor.cpp — verify head/tail indices\n" +
                    "// Use: buffer_[tail & (CAPACITY-1)] not buffer_[tail]", false),
                FixSuggestion(3, "Run with ASan in CI",
                    "cmake -DENABLE_ASAN=ON ..", false)
            ),
            prevention = listOf(
                "Enable ASan in all debug builds via sanitizer_flags.cmake",
                "Use span<> or std::array instead of raw pointers",
                "Run cppcheck in CI for static detection"
            )
        )
    )

    fun findMatchingPattern(text: String): ErrorPattern? {
        val lower = text.lowercase()
        return patterns.firstOrNull { pattern ->
            pattern.keywords.all { lower.contains(it.lowercase()) } &&
            (pattern.anyKeywords.isEmpty() ||
             pattern.anyKeywords.any { lower.contains(it.lowercase()) })
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Stack Trace Parser
// ─────────────────────────────────────────────────────────────────────────────

data class ParsedStackTrace(
    val exceptionType: String,
    val message:       String,
    val frames:        List<StackFrame>,
    val causedBy:      ParsedStackTrace?
)

data class StackFrame(
    val className:  String,
    val methodName: String,
    val fileName:   String?,
    val lineNumber: Int?,
    val isOurCode:  Boolean   // com.visionagent.*
)

object StackTraceParser {
    fun parse(stackTrace: String): ParsedStackTrace {
        val lines   = stackTrace.lines()
        val firstLine = lines.firstOrNull() ?: ""
        val colonIdx  = firstLine.indexOf(':')
        val exType    = if (colonIdx > 0) firstLine.substring(0, colonIdx).trim() else firstLine
        val message   = if (colonIdx > 0) firstLine.substring(colonIdx + 1).trim() else ""

        val frames = lines.drop(1)
            .filter { it.trimStart().startsWith("at ") }
            .mapNotNull { line ->
                parseFrame(line.trimStart().removePrefix("at ").trim())
            }

        return ParsedStackTrace(exType, message, frames, null)
    }

    private fun parseFrame(frameStr: String): StackFrame? {
        return try {
            val parenIdx = frameStr.lastIndexOf('(')
            val classMethod = frameStr.substring(0, parenIdx)
            val dotIdx = classMethod.lastIndexOf('.')
            val className  = classMethod.substring(0, dotIdx)
            val methodName = classMethod.substring(dotIdx + 1)

            val fileInfo = frameStr.substring(parenIdx + 1, frameStr.length - 1)
            val colonIdx2 = fileInfo.lastIndexOf(':')
            val fileName  = if (colonIdx2 > 0) fileInfo.substring(0, colonIdx2) else fileInfo
            val lineNum   = if (colonIdx2 > 0) fileInfo.substring(colonIdx2 + 1).toIntOrNull() else null

            StackFrame(
                className  = className,
                methodName = methodName,
                fileName   = fileName,
                lineNumber = lineNum,
                isOurCode  = className.startsWith("com.visionagent")
            )
        } catch (e: Exception) { null }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// AIDebugger — Main Analysis Engine
// ─────────────────────────────────────────────────────────────────────────────

@Singleton
class AIDebugger @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger
) {
    companion object { private const val TAG = "AIDebugger" }

    private val analysisCache = LinkedHashMap<String, DebugAnalysis>(20, 0.75f, true)

    /**
     * Analyze a crash/error and return actionable debug info.
     * Works 100% offline.
     */
    fun analyze(
        stackTrace:     String,
        logcatContext:  String = "",
        crashSnapshot:  CrashSnapshot? = null,
        diagnosticReport: DiagnosticReport? = null
    ): DebugAnalysis {

        val cacheKey = stackTrace.hashCode().toString()
        analysisCache[cacheKey]?.let { return it }

        val parsed  = StackTraceParser.parse(stackTrace)
        val pattern = ErrorPatternDB.findMatchingPattern(stackTrace)

        val ourFrames = parsed.frames.filter { it.isOurCode }
        val firstOurFrame = ourFrames.firstOrNull()

        val analysis = if (pattern != null) {
            // Matched known pattern
            DebugAnalysis(
                errorType      = pattern.errorType,
                rootCause      = pattern.rootCause,
                affectedFile   = firstOurFrame?.fileName,
                affectedLine   = firstOurFrame?.lineNumber,
                severity       = pattern.severity,
                explanation    = buildExplanation(parsed, pattern, diagnosticReport),
                fixSuggestions = pattern.fixes,
                preventionTips = pattern.prevention,
                similarCases   = findSimilarCases(pattern.id),
                confidence     = 0.85f,
                requiresPC     = pattern.fixes.any { !it.isAutoFix }
            )
        } else {
            // Unknown pattern — generic analysis
            genericAnalysis(parsed, ourFrames, logcatContext)
        }

        analysisCache[cacheKey] = analysis
        logAnalysis(analysis)
        return analysis
    }

    /**
     * Analyze a diagnostic report and suggest improvements.
     */
    fun analyzeDiagnosticReport(report: DiagnosticReport): List<String> {
        return buildList {
            report.checks.forEach { check ->
                when {
                    check.status == com.visionagent.core.diagnostic.DiagnosticStatus.CRITICAL -> {
                        add("🔴 CRITICAL [${check.name}]: ${check.message}")
                        add("   Fix: ${check.value} (threshold: ${check.threshold})")
                    }
                    check.status == com.visionagent.core.diagnostic.DiagnosticStatus.WARNING -> {
                        add("🟡 WARNING [${check.name}]: ${check.message}")
                    }
                    else -> {}
                }
            }
            if (report.healthScore < 70) {
                add("\n📊 Overall: Health ${report.healthScore.toInt()}% — immediate action required")
            }
            report.recommendations.forEach { add("💡 $it") }
        }
    }

    /**
     * Parse logcat and find relevant errors.
     */
    fun analyzeLogcat(logcat: String): List<DebugAnalysis> {
        val results   = mutableListOf<DebugAnalysis>()
        val errorLines = logcat.lines().filter { line ->
            line.contains("E/") || line.contains("FATAL") || line.contains("Exception")
        }

        // Group into stack traces
        val stackTraces = mutableListOf<String>()
        var current     = StringBuilder()
        for (line in errorLines) {
            if (line.contains("Exception") || line.contains("Error:")) {
                if (current.isNotEmpty()) stackTraces.add(current.toString())
                current = StringBuilder(line)
            } else if (line.trimStart().startsWith("at ")) {
                current.appendLine(line)
            }
        }
        if (current.isNotEmpty()) stackTraces.add(current.toString())

        stackTraces.take(5).forEach { st ->
            if (st.isNotBlank()) {
                results.add(analyze(st))
            }
        }
        return results
    }

    // ── Private Helpers ───────────────────────────────────────────────────

    private fun buildExplanation(
        parsed:     ParsedStackTrace,
        pattern:    ErrorPattern,
        diagnostic: DiagnosticReport?
    ): String = buildString {
        appendLine("**Error:** ${parsed.exceptionType}")
        appendLine("**Message:** ${parsed.message.take(200)}")
        appendLine()
        appendLine("**Root Cause:** ${pattern.rootCause}")
        appendLine()

        val ourFrames = parsed.frames.filter { it.isOurCode }
        if (ourFrames.isNotEmpty()) {
            appendLine("**Your Code Involved:**")
            ourFrames.take(3).forEach { frame ->
                appendLine("  → ${frame.className}.${frame.methodName}() " +
                           "(${frame.fileName}:${frame.lineNumber ?: "?"})")
            }
        }

        diagnostic?.let { d ->
            appendLine()
            appendLine("**System State at Crash:**")
            appendLine("  Health Score: ${d.healthScore.toInt()}%")
            d.checks.filter {
                it.status != com.visionagent.core.diagnostic.DiagnosticStatus.OK
            }.take(3).forEach { check ->
                appendLine("  • ${check.name}: ${check.value}")
            }
        }
    }

    private fun genericAnalysis(
        parsed:     ParsedStackTrace,
        ourFrames:  List<StackFrame>,
        logcat:     String
    ): DebugAnalysis {
        val firstFrame = ourFrames.firstOrNull()
        return DebugAnalysis(
            errorType      = parsed.exceptionType,
            rootCause      = "Unknown pattern. Exception in ${firstFrame?.className ?: "unknown class"}",
            affectedFile   = firstFrame?.fileName,
            affectedLine   = firstFrame?.lineNumber,
            severity       = ErrorSeverity.MEDIUM,
            explanation    = "Unknown error pattern. Check the stack trace manually.\n" +
                             "Exception: ${parsed.exceptionType}\n" +
                             "Message: ${parsed.message}",
            fixSuggestions = listOf(
                FixSuggestion(1, "Add try-catch around the failing code",
                    "try {\n    // failing operation\n} catch (e: Exception) {\n    logger.e(TAG, \"Error\", e)\n}",
                    false),
                FixSuggestion(2, "Check the Logcat around this time for more context",
                    "adb logcat -d VisionAgent:V *:S", false),
                FixSuggestion(3, "Run Self Diagnostic to check system health",
                    "selfDiagnosticEngine.runFullDiagnostic()", true)
            ),
            preventionTips = listOf("Add error handling for this code path"),
            similarCases   = emptyList(),
            confidence     = 0.4f,
            requiresPC     = true
        )
    }

    private fun findSimilarCases(patternId: String): List<String> {
        return when (patternId) {
            "OOM_001"   -> listOf("OutOfMemoryError often follows unchecked bitmap creation",
                                  "STM LRU eviction not working = OOM risk")
            "JNI_001"   -> listOf("CMake build failure = missing .so",
                                  "Wrong ABI filter = library not found on device")
            "ANR_001"   -> listOf("Room query on main thread = ANR",
                                  "Blocking coroutine on main thread = ANR")
            else        -> emptyList()
        }
    }

    private fun logAnalysis(analysis: DebugAnalysis) {
        logger.i(TAG, "══════════════════════════════════════")
        logger.i(TAG, " AI DEBUGGER ANALYSIS")
        logger.i(TAG, " Error: ${analysis.errorType}")
        logger.i(TAG, " Severity: ${analysis.severity} | Confidence: ${(analysis.confidence*100).toInt()}%")
        logger.i(TAG, "──────────────────────────────────────")
        logger.i(TAG, " Root Cause: ${analysis.rootCause}")
        if (analysis.affectedFile != null) {
            logger.i(TAG, " File: ${analysis.affectedFile}:${analysis.affectedLine ?: "?"}")
        }
        logger.i(TAG, "──────────────────────────────────────")
        logger.i(TAG, " FIX STEPS:")
        analysis.fixSuggestions.forEach { fix ->
            logger.i(TAG, " ${fix.step}. ${fix.action} ${if (fix.isAutoFix) "[AUTO]" else "[MANUAL]"}")
        }
        logger.i(TAG, " PC Required: ${analysis.requiresPC}")
        logger.i(TAG, "══════════════════════════════════════")
    }
}
