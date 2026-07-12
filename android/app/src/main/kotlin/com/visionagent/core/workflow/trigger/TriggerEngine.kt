package com.visionagent.core.workflow.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.visionagent.core.event.*
import com.visionagent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

// ============================================================
// TriggerEngine — Workflow Trigger System
//
// Trigger Types:
// ┌──────────────────────────────────────────────────────┐
// │  APP_OPEN          ← App launched                    │
// │  SCREEN_CHANGE     ← Screen type changes             │
// │  ELEMENT_FOUND     ← Specific UI element detected    │
// │  TEXT_DETECTED     ← OCR finds specific text         │
// │  NOTIFICATION      ← Notification received           │
// │  TIME_OF_DAY       ← Scheduled time                  │
// │  INTERVAL          ← Every N minutes                 │
// │  BATTERY_LOW       ← Battery below threshold         │
// │  BATTERY_CHARGING  ← Plugged in / unplugged          │
// │  NETWORK_CHANGE    ← WiFi/Mobile/None                │
// │  AGENT_STATE       ← Agent state changed             │
// │  CUSTOM_EVENT      ← Published via EventBus          │
// │  MANUAL            ← User triggered (Dashboard)      │
// └──────────────────────────────────────────────────────┘
// ============================================================

data class TriggerEvent(
    val type:       String,
    val data:       Map<String, String> = emptyMap(),
    val sessionId:  String              = "",
    val timestamp:  Long                = System.currentTimeMillis()
)

data class TriggerConfig(
    val type:       String,
    val parameters: Map<String, String> = emptyMap()
)

@Singleton
class TriggerEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventBus: AgentEventBus,
    private val logger:   Logger
) {
    companion object { private const val TAG = "TriggerEngine" }

    private val triggerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _triggerEvents = MutableSharedFlow<TriggerEvent>(
        replay           = 0,
        extraBufferCapacity = 50
    )
    val triggerEvents: SharedFlow<TriggerEvent> = _triggerEvents.asSharedFlow()

    private var currentSessionId = ""
    private var batteryReceiver: BroadcastReceiver? = null

    // ── Initialize ────────────────────────────────────────────────────────

    fun initialize(sessionId: String) {
        currentSessionId = sessionId
        setupEventBusTriggers()
        setupBatteryTrigger()
        setupTimeTriggers()

        // App open trigger
        fire("APP_OPEN", mapOf("session_id" to sessionId))
        logger.i(TAG, "TriggerEngine initialized")
    }

    // ── EventBus Triggers ─────────────────────────────────────────────────

    private fun setupEventBusTriggers() {
        // Screen change trigger
        eventBus.subscribe<UIElementDetectedEvent>()
            .distinctUntilChanged { a, b -> a.screenType == b.screenType }
            .onEach { event ->
                fire("SCREEN_CHANGE", mapOf(
                    "screen_type"  to event.screenType.name,
                    "element_count" to event.elements.size.toString()
                ), event.sessionId)

                // Element found triggers
                event.elements.forEach { el ->
                    if (el.confidence > 0.8f) {
                        fire("ELEMENT_FOUND", mapOf(
                            "element_type" to el.type.name,
                            "element_text" to (el.text ?: ""),
                            "confidence"   to el.confidence.toString()
                        ), event.sessionId)
                    }
                }
            }
            .launchIn(triggerScope)

        // OCR text trigger
        eventBus.subscribe<OCRCompletedEvent>()
            .filter { it.text.isNotBlank() }
            .onEach { event ->
                fire("TEXT_DETECTED", mapOf(
                    "text"       to event.text.take(200),
                    "confidence" to event.confidence.toString()
                ), event.sessionId)
            }
            .launchIn(triggerScope)

        // Agent state trigger
        eventBus.subscribe<StateChangedEvent>()
            .onEach { event ->
                fire("AGENT_STATE", mapOf(
                    "previous" to event.previousState.name,
                    "current"  to event.currentState.name,
                    "trigger"  to event.trigger
                ), event.sessionId)
            }
            .launchIn(triggerScope)

        // Error trigger
        eventBus.subscribe<AgentErrorEvent>()
            .onEach { event ->
                fire("ERROR_OCCURRED", mapOf(
                    "error_code" to event.errorCode.name,
                    "message"    to event.message.take(200),
                    "is_fatal"   to event.isFatal.toString()
                ), event.sessionId)
            }
            .launchIn(triggerScope)
    }

    // ── Battery Trigger ───────────────────────────────────────────────────

    private fun setupBatteryTrigger() {
        // FIX R5-5: If initialize() is called twice (e.g., session restart),
        // the old batteryReceiver-A stays registered but its reference is lost.
        // stop() only unregisters batteryReceiver-B → batteryReceiver-A leaks forever,
        // firing duplicate trigger events on every battery change.
        // Fix: always unregister existing receiver before creating a new one.
        batteryReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) { /* not registered */ }
        }
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                intent ?: return
                val level    = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale    = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val pct      = if (scale > 0) level * 100 / scale else -1
                val plugged  = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                val charging = plugged != 0

                when {
                    pct in 1..15 && !charging ->
                        fire("BATTERY_LOW", mapOf("level" to pct.toString()))
                    charging ->
                        fire("BATTERY_CHARGING", mapOf("level" to pct.toString(), "plugged" to plugged.toString()))
                    !charging ->
                        fire("BATTERY_UNPLUGGED", mapOf("level" to pct.toString()))
                }
            }
        }
        context.registerReceiver(batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    // ── Time Triggers ─────────────────────────────────────────────────────

    private fun setupTimeTriggers() {
        // Every-minute check for scheduled triggers
        triggerScope.launch {
            while (isActive) {
                delay(60_000L)
                val cal = Calendar.getInstance()
                val timeStr = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY),
                                                  cal.get(Calendar.MINUTE))
                fire("TIME_OF_DAY", mapOf(
                    "time"    to timeStr,
                    "hour"    to cal.get(Calendar.HOUR_OF_DAY).toString(),
                    "minute"  to cal.get(Calendar.MINUTE).toString(),
                    "weekday" to cal.get(Calendar.DAY_OF_WEEK).toString()
                ))
            }
        }

        // Interval triggers: fire every 5 minutes
        triggerScope.launch {
            var count = 0
            while (isActive) {
                delay(5 * 60_000L)
                fire("INTERVAL_5MIN", mapOf("count" to (++count).toString()))
            }
        }
    }

    // ── Manual Triggers ────────────────────────────────────────────────────

    fun fireTrigger(type: String, data: Map<String, String> = emptyMap()) {
        fire(type, data, currentSessionId)
    }

    fun fireCustomEvent(name: String, data: Map<String, String> = emptyMap()) {
        fire("CUSTOM:$name", data, currentSessionId)
    }

    private fun fire(type: String, data: Map<String, String> = emptyMap(), sessionId: String = currentSessionId) {
        val event = TriggerEvent(type = type, data = data, sessionId = sessionId)
        _triggerEvents.tryEmit(event)
        logger.v(TAG, "Trigger fired: $type | data=${data.entries.take(3)}")
    }

    fun stop() {
        triggerScope.cancel()
        batteryReceiver?.let { context.unregisterReceiver(it) }
        logger.i(TAG, "TriggerEngine stopped")
    }
}
