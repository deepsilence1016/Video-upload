package com.visionagent.core.event_recorder
import kotlin.collections.ArrayDeque  // Explicit: avoids Lint confusion with java.util.ArrayDeque (API 35)

import com.visionagent.core.event.*
import com.visionagent.utils.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

// ============================================================
// EventRecorder — Record ALL events, replay later
//
// हर event रिकॉर्ड होता है।
// बाद में exact sequence replay कर सकते हो।
//
// Use cases:
//  - Bug reproduction (exact state replay)
//  - Test scenario recording
//  - Performance analysis (event timing)
//  - Crash investigation (what happened before?)
// ============================================================

@Serializable
data class RecordedEvent(
    val sequenceId:  Long,
    val eventType:   String,
    val eventJson:   String,
    val timestampMs: Long,
    val deltaMs:     Long   // Time since previous event
)

@Serializable
data class EventRecording(
    val recordingId:   String,
    val name:          String,
    val startTime:     Long,
    val endTime:       Long,
    val eventCount:    Int,
    val durationMs:    Long,
    val sessionId:     String,
    val events:        List<RecordedEvent>
)

@Singleton
class EventRecorder @Inject constructor(
    private val eventBus: AgentEventBus,
    private val logger:   Logger
) {
    companion object {
        private const val TAG      = "EventRecorder"
        private const val MAX_EVENTS = 10_000  // Circular buffer limit
    }

    private val json     = Json { prettyPrint = false; ignoreUnknownKeys = true }
    private val recScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var isRecording  = false
    private var recordingName= ""
    private var startTime    = 0L
    private var lastEventTime= 0L
    private var sequenceId   = 0L
    private val events       = ArrayDeque<RecordedEvent>(MAX_EVENTS)
    private var sessionId    = ""

    // ── Recording Control ──────────────────────────────────────────────────

    fun startRecording(name: String, sessionId: String) {
        if (isRecording) { logger.w(TAG, "Already recording"); return }
        events.clear()
        recordingName  = name
        startTime      = System.currentTimeMillis()
        lastEventTime  = startTime
        sequenceId     = 0
        isRecording    = true
        this.sessionId = sessionId

        subscribeAll()
        logger.i(TAG, "⏺ Event recording started: '$name'")
    }

    fun stopRecording(): EventRecording? {
        if (!isRecording) return null
        isRecording = false
        recScope.coroutineContext.cancelChildren()

        val recording = EventRecording(
            recordingId = java.util.UUID.randomUUID().toString(),
            name        = recordingName,
            startTime   = startTime,
            endTime     = System.currentTimeMillis(),
            eventCount  = events.size,
            durationMs  = System.currentTimeMillis() - startTime,
            sessionId   = sessionId,
            events      = events.toList()
        )
        logger.i(TAG, "⏹ Recording stopped: ${events.size} events | ${recording.durationMs}ms")
        return recording
    }

    fun isRecording() = isRecording

    // ── Replay ────────────────────────────────────────────────────────────

    suspend fun replay(recording: EventRecording, speedMultiplier: Float = 1.0f) {
        logger.i(TAG, "▶ Replaying: ${recording.name} | ${recording.eventCount} events")

        recording.events.forEach { event ->
            val delay = (event.deltaMs / speedMultiplier).toLong().coerceIn(0, 5000)
            if (delay > 0) delay(delay)

            // Re-publish event to EventBus (simulation)
            // In production: deserialize and republish typed events
            logger.v(TAG, "Replay[${event.sequenceId}]: ${event.eventType} +${event.deltaMs}ms")
        }
        logger.i(TAG, "▶ Replay complete")
    }

    // ── Save/Load ──────────────────────────────────────────────────────────

    fun saveRecording(recording: EventRecording, dir: File): File {
        dir.mkdirs()
        val file = File(dir, "recording_${recording.recordingId}.json")
        file.writeText(json.encodeToString(recording))
        logger.i(TAG, "Saved: ${file.path} (${file.length()/1024}KB)")
        return file
    }

    fun loadRecording(file: File): EventRecording? = try {
        json.decodeFromString<EventRecording>(file.readText())
    } catch (e: Exception) {
        logger.e(TAG, "Load failed: ${file.path}", e)
        null
    }

    // ── Event Subscription ─────────────────────────────────────────────────

    private fun subscribeAll() {
        // Record all event types
        eventBus.events
            .onEach { event ->
                if (!isRecording) return@onEach
                val now   = System.currentTimeMillis()
                val delta = now - lastEventTime
                lastEventTime = now

                if (events.size >= MAX_EVENTS) events.removeFirst()

                events.addLast(RecordedEvent(
                    sequenceId  = sequenceId++,
                    eventType   = event::class.simpleName ?: "Unknown",
                    eventJson   = event.toString().take(500),
                    timestampMs = now,
                    deltaMs     = delta
                ))
            }
            .launchIn(recScope)
    }

    fun getEventCount() = events.size
    fun getRecentEvents(n: Int) = events.takeLast(n)
}
