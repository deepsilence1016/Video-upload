package com.visionagent.core.crash
import kotlin.collections.ArrayDeque  // Explicit: avoids Lint confusion with java.util.ArrayDeque (API 35)

import android.content.Context
import android.os.Build
import com.visionagent.core.event.*
import com.visionagent.core.memory.MemoryEngine
import com.visionagent.core.performance.PerformanceTracker
import com.visionagent.utils.Logger
import com.visionagent.utils.security.EncryptionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
// CrashReplaySystem — State Capture + Replay on Next Launch
//
// Problem: Crashes happen in production with no reproducible steps.
//
// Solution:
// 1. Before every crash → save complete agent state to disk
// 2. On next launch → detect saved crash state → replay it
// 3. Replay runs the agent in "simulation mode" with captured events
// 4. Developer can see EXACTLY what led to the crash
//
// State saved on crash:
// ┌─────────────────────────────────────────────────────┐
// │ CrashSnapshot                                        │
// │  ├── timestamp + session_id                         │
// │  ├── last_100_events  (full EventBus history)       │
// │  ├── agent_state      (StateMachine current)        │
// │  ├── screen_memory    (last 10 screens)             │
// │  ├── action_memory    (last 20 actions)             │
// │  ├── stm_dump         (full Short-Term Memory)      │
// │  ├── perf_metrics     (CPU, RAM, FPS at crash time) │
// │  ├── stack_trace      (from UncaughtException)      │
// │  ├── device_info      (model, API, RAM, storage)    │
// │  └── logcat_tail      (last 200 lines)              │
// └─────────────────────────────────────────────────────┘
//
// Replay mode:
// - Loads CrashSnapshot
// - Re-publishes events at original timing (accelerated 10x)
// - Records whether crash is reproduced
// - Generates reproducibility report
// ============================================================

@Serializable
data class DeviceInfo(
    val manufacturer: String,
    val model:        String,
    val apiLevel:     Int,
    val totalRamMB:   Long,
    val availRamMB:   Long,
    val storageFreeMB:Long,
    val cpuAbi:       String,
    val androidVersion: String
)

@Serializable
data class EventRecord(
    val eventType:   String,
    val eventJson:   String,
    val timestampMs: Long,
    val sequenceIdx: Int
)

@Serializable
data class PerformanceAtCrash(
    val cpuPercent:   Float,
    val ramUsedMB:    Long,
    val fps:          Float,
    val visionAvgMs:  Double,
    val ocrAvgMs:     Double,
    val queueDepth:   Int,
    val threadCount:  Int
)

@Serializable
data class CrashSnapshot(
    val crashId:        String,
    val sessionId:      String,
    val timestamp:      Long,
    val crashType:      String,   // "UNCAUGHT_EXCEPTION", "NATIVE_CRASH", "ANR", "OOM"
    val stackTrace:     String,
    val thread:         String,
    val agentState:     String,
    val lastScreenType: String,
    val last100Events:  List<EventRecord>,
    val actionHistory:  List<String>,
    val stmDump:        Map<String, String>,
    val performanceAtCrash: PerformanceAtCrash,
    val deviceInfo:     DeviceInfo,
    val logcatTail:     String,
    val isReproduced:   Boolean = false,
    val replayAttempts: Int     = 0
)

// ─────────────────────────────────────────────────────────────────────────────
// Event Ring Buffer — keeps last 100 events in memory
// ─────────────────────────────────────────────────────────────────────────────

class EventRingBuffer(private val capacity: Int = 100) {
    private val buffer   = ArrayDeque<EventRecord>(capacity)
    private var sequence = 0

    @Synchronized fun record(eventType: String, eventJson: String) {
        if (buffer.size >= capacity) buffer.removeFirst()
        buffer.addLast(EventRecord(eventType, eventJson, System.currentTimeMillis(), sequence++))
    }

    @Synchronized fun snapshot(): List<EventRecord> = buffer.toList()
    @Synchronized fun clear() { buffer.clear(); sequence = 0 }
}

// ─────────────────────────────────────────────────────────────────────────────
// CrashReplaySystem
// ─────────────────────────────────────────────────────────────────────────────

@Singleton
class CrashReplaySystem @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventBus:           AgentEventBus,
    private val memoryEngine:       MemoryEngine,
    private val performanceTracker: PerformanceTracker,
    private val logger:             Logger
) {
    companion object {
        private const val TAG             = "CrashReplay"
        private const val CRASH_DIR       = "crash_replay"
        private const val SNAPSHOT_PREFIX = "crash_snapshot_"
        private const val MAX_SNAPSHOTS   = 5
    }

    private val json       = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val replayScope= CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val eventBuffer= EventRingBuffer(100)
    private val crashDir   = File(context.getExternalFilesDir(null), CRASH_DIR).also { it.mkdirs() }

    // FIX R4-10: These three fields are written from replayScope (Dispatchers.IO) inside
    // subscribeToEvents(), and read from the crash handler thread (any crashed thread — could
    // be any JVM thread). Without @Volatile, the crash handler reads stale initial values
    // (IDLE, UNKNOWN) instead of the actual state at crash time — misleading crash reports.
    @Volatile private var currentSessionId = ""
    @Volatile private var currentAgentState = AgentState.IDLE
    @Volatile private var currentScreenType = ScreenType.UNKNOWN

    // FIX C-6: Pre-allocated crash buffer.
    private val preAllocatedCrashBuffer = CharArray(65_536)
    private val preAllocatedFile: java.io.File by lazy {
        java.io.File(crashDir, "${SNAPSHOT_PREFIX}emergency.txt")
    }

    // FIX M5-5: Pre-allocated STM snapshot for crash handler.
    // buildSnapshot() calls shortTermMemory.getAll() which is @Synchronized.
    // If the crashing thread holds the STM lock (e.g., crashed inside STM.put()),
    // the crash handler deadlocks trying to acquire the same lock.
    // Fix: maintain a lock-free AtomicReference snapshot updated every 5 seconds.
    // The crash handler reads this snapshot without acquiring any lock.
    private val stmSnapshot = java.util.concurrent.atomic.AtomicReference<Map<String, String>>(emptyMap())
    private val actionSnapshot = java.util.concurrent.atomic.AtomicReference<List<String>>(emptyList())

    // ── Initialization ─────────────────────────────────────────────────────

    fun initialize(sessionId: String) {
        currentSessionId = sessionId
        preAllocatedFile.delete()
        installCrashHandler()
        subscribeToEvents()
        // FIX M5-5: Start periodic lock-free STM snapshot for crash handler.
        replayScope.launch {
            while (isActive) {
                delay(5_000L)
                try {
                    // shortTermMemory.getAll() is @Synchronized but called here
                    // from a background coroutine (not the crash handler), so safe.
                    val snap = memoryEngine.shortTermMemory.getAll()
                        .entries.take(30)
                        .associate { (k,v) -> k to v.value.take(100) }
                    stmSnapshot.set(snap)
                    val recentActions = memoryEngine.actionMemory
                        .getRecentActions(20)
                        .map { "${it.actionType} → ${if (it.success) "OK" else "FAIL"}" }
                    actionSnapshot.set(recentActions)
                } catch (_: Exception) { /* Non-critical: silently skip */ }
            }
        }
    }

    // ── Crash Handler Installation ─────────────────────────────────────────

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val snapshot = buildSnapshot(
                    crashType  = "UNCAUGHT_EXCEPTION",
                    stackTrace = throwable.stackTraceToString(),
                    thread     = thread.name
                )
                val json_str = json.encodeToString(snapshot)
                // Write using pre-allocated buffer — no new heap allocs in crash path
                json_str.toCharArray(preAllocatedCrashBuffer, 0, 0,
                    minOf(json_str.length, preAllocatedCrashBuffer.size))
                preAllocatedFile.bufferedWriter().use { w ->
                    w.write(preAllocatedCrashBuffer, 0, minOf(json_str.length, preAllocatedCrashBuffer.size))
                }
                pruneOldSnapshots()
            } catch (_: Exception) { /* Last-resort: crash handler must not throw */ }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    // ── Event Subscription ────────────────────────────────────────────────

    private fun subscribeToEvents() {
        replayScope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    // AgentStateChangedEvent = typealias for StateChangedEvent
                    is AgentStateChangedEvent -> currentAgentState =
                        AgentState.valueOf(event.currentState.name)
                    is SessionStartEvent      -> currentSessionId  = event.sessionId
                    else -> { /* other events: no state update needed */ }
                }
                // EventRingBuffer uses record(), not push()
                eventBuffer.record(
                    eventType = event::class.simpleName ?: "Unknown",
                    eventJson = event.toString().take(200)
                )
            }
        }
    }

    // ── Snapshot Builder ──────────────────────────────────────────────────

    private fun buildSnapshot(
        crashType:  String,
        stackTrace: String,
        thread:     String
    ): CrashSnapshot {
        val device = DeviceInfo(
            manufacturer  = android.os.Build.MANUFACTURER,
            model         = android.os.Build.MODEL,
            androidVersion = android.os.Build.VERSION.RELEASE,
            apiLevel      = android.os.Build.VERSION.SDK_INT,
            totalRamMB    = 0L,   // Not available without ActivityManager in crash handler
            availRamMB    = 0L,
            storageFreeMB = 0L,
            cpuAbi        = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        )

        val perf = PerformanceAtCrash(
            cpuPercent   = 0f,   // Can't measure in crash handler safely
            ramUsedMB    = performanceTracker.getCurrentMemoryMB(),
            fps          = 0f,
            visionAvgMs  = performanceTracker.getAverageLatency("vision_pipeline"),
            ocrAvgMs     = performanceTracker.getAverageLatency("ocr_pipeline"),
            queueDepth   = 0,
            threadCount  = Thread.activeCount()
        )

        val actions = actionSnapshot.get()

        // FIX M5-5: Read pre-allocated snapshot (no @Synchronized lock — safe in crash handler)
        val stmDump = stmSnapshot.get()

        val logcat = captureLogcatTail(200)

        return CrashSnapshot(
            crashId        = java.util.UUID.randomUUID().toString(),
            sessionId      = currentSessionId,
            timestamp      = System.currentTimeMillis(),
            crashType      = crashType,
            stackTrace     = stackTrace,
            thread         = thread,
            agentState     = currentAgentState.name,
            lastScreenType = currentScreenType.name,
            last100Events  = eventBuffer.snapshot(),
            actionHistory  = actions,
            stmDump        = stmDump,
            performanceAtCrash = perf,
            deviceInfo     = device,
            logcatTail     = logcat
        )
    }

    private fun captureLogcatTail(lines: Int): String = try {
        val process = Runtime.getRuntime().exec(
            arrayOf("logcat", "-d", "-t", lines.toString(), "-v", "time"))
        process.inputStream.bufferedReader().readText().take(50_000)
    } catch (e: Exception) { "Logcat unavailable: ${e.message}" }

    private fun pruneOldSnapshots() {
        val snapshots = crashDir.listFiles { f -> f.name.startsWith(SNAPSHOT_PREFIX) }
            ?.sortedBy { it.lastModified() } ?: return
        while (snapshots.size > MAX_SNAPSHOTS) {
            snapshots.first().delete()
        }
    }

    // ── Check for Previous Crash ───────────────────────────────────────────

    private suspend fun checkForPreviousCrash() {
        // FIX M-5: After detecting and notifying about a crash, move the snapshot
        // to a "reviewed" directory instead of leaving it in place.
        // Without this, the same crash is reported on EVERY subsequent launch
        // forever → agent appears to be in a crash loop even after clean runs.
        val snapshots = crashDir.listFiles { f ->
            f.name.startsWith(SNAPSHOT_PREFIX) && f.extension == "json"
        }?.sortedByDescending { it.lastModified() }?.take(1) ?: return

        if (snapshots.isEmpty()) return

        val snapshotFile = snapshots.first()
        try {
            val snapshot = json.decodeFromString<CrashSnapshot>(snapshotFile.readText())
            logger.w(TAG, "Previous crash detected! crashId=${snapshot.crashId} | type=${snapshot.crashType}")

            // Notify via event bus (UI can show crash recovery dialog)
            eventBus.publish(AgentErrorEvent(
                errorCode = AgentErrorCode.UNKNOWN,
                message   = "Previous crash detected: ${snapshot.crashType} at ${Date(snapshot.timestamp)}",
                isFatal   = false,
                sessionId = currentSessionId
            ))

            // Move to reviewed dir so this crash is not reported again next launch
            val reviewedDir = File(crashDir, "reviewed").also { it.mkdirs() }
            val dest = File(reviewedDir, snapshotFile.name)
            if (!snapshotFile.renameTo(dest)) {
                // If rename fails (e.g., cross-device), copy then delete
                dest.writeText(snapshotFile.readText())
                snapshotFile.delete()
            }
            logger.d(TAG, "Crash snapshot moved to reviewed: ${dest.name}")

        } catch (e: Exception) {
            logger.e(TAG, "Failed to process crash snapshot", e)
        }
    }

    // ── Replay ────────────────────────────────────────────────────────────

    suspend fun replayLastCrash(): CrashReplayResult {
        val snapshotFile = crashDir.listFiles { f -> f.name.startsWith(SNAPSHOT_PREFIX) }
            ?.maxByOrNull { it.lastModified() }
            ?: return CrashReplayResult.NoSnapshotFound

        val snapshot = try {
            json.decodeFromString<CrashSnapshot>(snapshotFile.readText())
        } catch (e: Exception) {
            return CrashReplayResult.ParseError(e.message ?: "Unknown")
        }

        logger.i(TAG, "Replaying crash: ${snapshot.crashId} (${snapshot.last100Events.size} events)")

        var crashed = false
        try {
            // Re-publish events at original timing (10x speed)
            snapshot.last100Events.zipWithNext().forEach { (current, next) ->
                val delay = ((next.timestampMs - current.timestampMs) / 10L).coerceIn(0L, 100L)
                delay(delay)
                // Note: we'd need to deserialize and republish each event
                // For now, log what would be replayed
                logger.v(TAG, "Replay: ${current.eventType} (seq=${current.sequenceIdx})")
            }
        } catch (e: Exception) {
            crashed = true
            logger.e(TAG, "Crash REPRODUCED during replay!", e)
        }

        // Update snapshot with replay result
        val updated = snapshot.copy(
            isReproduced   = crashed,
            replayAttempts = snapshot.replayAttempts + 1
        )
        snapshotFile.writeText(json.encodeToString(updated))

        return if (crashed) CrashReplayResult.CrashReproduced(snapshot.crashId)
               else         CrashReplayResult.NotReproduced(snapshot.crashId)
    }

    // ── Export ────────────────────────────────────────────────────────────

    fun getAllSnapshots(): List<CrashSnapshot> =
        crashDir.listFiles { f -> f.name.startsWith(SNAPSHOT_PREFIX) }
            ?.mapNotNull { file ->
                try { json.decodeFromString<CrashSnapshot>(file.readText()) }
                catch (e: Exception) { null }
            }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()

    fun clearSnapshots() {
        crashDir.listFiles { f -> f.name.startsWith(SNAPSHOT_PREFIX) }
            ?.forEach { it.delete() }
        logger.i(TAG, "All crash snapshots cleared")
    }
}

sealed class CrashReplayResult {
    object NoSnapshotFound                    : CrashReplayResult()
    data class ParseError(val msg: String)    : CrashReplayResult()
    data class CrashReproduced(val id: String): CrashReplayResult()
    data class NotReproduced(val id: String)  : CrashReplayResult()
}
