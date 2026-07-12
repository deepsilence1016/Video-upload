package com.visionagent.core.crashlytics

import android.content.Context
import com.visionagent.core.event.*
import com.visionagent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

// ============================================================
// Firebase Crashlytics Integration
//
// Setup:
// 1. google-services.json → android/app/ में रखो
// 2. build.gradle.kts में add करो:
//    id("com.google.gms.google-services")
//    id("com.google.firebase.crashlytics")
//
// 3. Dependencies:
//    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
//    implementation("com.google.firebase:firebase-crashlytics-ktx")
//    implementation("com.google.firebase:firebase-analytics-ktx")
//
// Crashlytics automatically captures:
//   - Crash stacktrace
//   - Device model + OS version
//   - RAM available
//   - Custom keys (added below)
//
// We add custom keys:
//   - agent_state: EXECUTING, IDLE, etc.
//   - screen_type: FORM, DIALOG, etc.
//   - last_action: TAP, SCROLL, etc.
//   - session_id: UUID
//   - fps: current frame rate
//   - ram_mb: current RAM usage
//   - vision_confidence: last vision confidence
//
// Non-fatal errors also logged:
//   - Vision pipeline failures
//   - OCR failures
//   - Rule engine errors
//   - Recovery attempts
// ============================================================

@Singleton
class CrashlyticsIntegration @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventBus: AgentEventBus,
    private val logger:   Logger
) {
    companion object {
        private const val TAG = "Crashlytics"
        private var instance: Any? = null  // com.google.firebase.crashlytics.FirebaseCrashlytics
    }

    private val crashScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun initialize() {
        try {
            // Reflection-based initialization (avoids hard dependency)
            val crashlyticsClass = Class.forName(
                "com.google.firebase.crashlytics.FirebaseCrashlytics")
            instance = crashlyticsClass.getMethod("getInstance").invoke(null)
            subscribeToEvents()
            logger.i(TAG, "Firebase Crashlytics initialized")
        } catch (e: ClassNotFoundException) {
            logger.w(TAG, "Firebase Crashlytics not on classpath — using local crash capture only")
        }
    }

    private fun subscribeToEvents() {
        // Update custom keys on state changes
        eventBus.subscribe<StateChangedEvent>()
            .onEach { event ->
                setKey("agent_state", event.currentState.name)
                setKey("session_id", event.sessionId)
            }
            .launchIn(crashScope)

        eventBus.subscribe<UIElementDetectedEvent>()
            .onEach { event ->
                setKey("screen_type",         event.screenType.name)
                setKey("vision_confidence",   event.confidence.toString())
                setKey("element_count",       event.elements.size.toString())
            }
            .launchIn(crashScope)

        eventBus.subscribe<ActionExecutedEvent>()
            .onEach { event ->
                setKey("last_action",         event.actionType.name)
                setKey("last_action_success", event.success.toString())
            }
            .launchIn(crashScope)

        // Log non-fatal errors to Crashlytics
        eventBus.subscribe<AgentErrorEvent>()
            .onEach { event ->
                if (!event.isFatal) {
                    logNonFatal(
                        Exception("${event.errorCode}: ${event.message}"),
                        mapOf(
                            "error_code"  to event.errorCode.name,
                            "session_id"  to event.sessionId,
                            "is_fatal"    to event.isFatal.toString()
                        )
                    )
                }
            }
            .launchIn(crashScope)

        // Performance keys
        crashScope.launch {
            while (isActive) {
                delay(30_000L)  // Update every 30 seconds
                val ram = Runtime.getRuntime().let {
                    (it.totalMemory() - it.freeMemory()) / 1024 / 1024
                }
                setKey("ram_mb", ram.toString())
                setKey("thread_count", Thread.activeCount().toString())
            }
        }
    }

    fun setKey(key: String, value: String) {
        try {
            instance?.javaClass?.getMethod("setCustomKey", String::class.java, String::class.java)
                ?.invoke(instance, key, value)
        } catch (e: Exception) { /* Crashlytics not available */ }
    }

    fun logNonFatal(error: Throwable, keys: Map<String, String> = emptyMap()) {
        try {
            keys.forEach { (k, v) -> setKey(k, v) }
            instance?.javaClass?.getMethod("recordException", Throwable::class.java)
                ?.invoke(instance, error)
            logger.d(TAG, "Non-fatal logged: ${error.message}")
        } catch (e: Exception) { /* Crashlytics not available */ }
    }

    fun log(message: String) {
        try {
            instance?.javaClass?.getMethod("log", String::class.java)
                ?.invoke(instance, message)
        } catch (e: Exception) { /* Crashlytics not available */ }
    }

    fun setUserId(id: String) {
        try {
            instance?.javaClass?.getMethod("setUserId", String::class.java)
                ?.invoke(instance, id)
        } catch (e: Exception) { /* Crashlytics not available */ }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Setup Instructions (README section)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * CRASHLYTICS SETUP GUIDE
 * ────────────────────────────────────────────────────────────
 *
 * 1. Firebase Console जाओ: https://console.firebase.google.com
 *    → New Project → "VisionAgent"
 *    → Android app add करो: com.visionagent.app
 *    → google-services.json download करो
 *    → android/app/google-services.json में रखो
 *
 * 2. android/build.gradle.kts (root):
 *    ```kotlin
 *    plugins {
 *        id("com.google.gms.google-services") version "4.4.0" apply false
 *        id("com.google.firebase.crashlytics") version "2.9.9" apply false
 *    }
 *    ```
 *
 * 3. android/app/build.gradle.kts:
 *    ```kotlin
 *    plugins {
 *        id("com.google.gms.google-services")
 *        id("com.google.firebase.crashlytics")
 *    }
 *
 *    dependencies {
 *        implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
 *        implementation("com.google.firebase:firebase-crashlytics-ktx")
 *        implementation("com.google.firebase:firebase-analytics-ktx")
 *    }
 *    ```
 *
 * 4. Debug builds में Crashlytics disable करो (optional):
 *    ```kotlin
 *    firebaseCrashlytics {
 *        mappingFileUploadEnabled = true
 *        nativeSymbolUploadEnabled = true   // For NDK crashes
 *    }
 *    ```
 *
 * 5. VisionAgentApp.kt में add करो:
 *    ```kotlin
 *    crashlyticsIntegration.initialize()
 *    crashlyticsIntegration.setUserId(deviceId)
 *    ```
 *
 * अब हर crash automatically Firebase Dashboard में दिखेगा:
 * - Stack trace
 * - Device info
 * - Custom keys (agent_state, screen_type, etc.)
 * - Non-fatal errors
 * - Session breadcrumbs
 */
