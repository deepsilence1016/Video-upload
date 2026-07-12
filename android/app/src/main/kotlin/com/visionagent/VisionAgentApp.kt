package com.visionagent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.visionagent.utils.Logger
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

// ============================================================
// VisionAgentApp — Application Entry Point
//
// Responsibilities:
// - Hilt injection setup
// - Notification channels (required for Foreground Service)
// - Logger initialization
// - Crash handler setup
// - Performance monitoring bootstrap
// ============================================================

@HiltAndroidApp
class VisionAgentApp : Application() {

    @Inject lateinit var logger: Logger

    override fun onCreate() {
        super.onCreate()

        setupCrashHandler()
        setupNotificationChannels()
        initializeLogger()
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Log crash before delegating
            try {
                logger.e("VisionAgentApp", "UNCAUGHT EXCEPTION on thread: ${thread.name}", throwable)
                logger.flushAll()
            } catch (e: Exception) {
                // Logger itself failed — ignore
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun setupNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Screen Capture Service channel
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_CAPTURE,
                    "Screen Capture Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Vision Agent is monitoring your screen"
                    setShowBadge(false)
                }
            )

            // Agent Alerts channel
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERTS,
                    "Agent Alerts",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Important agent notifications"
                }
            )
        }
    }

    private fun initializeLogger() {
        val logDir = getExternalFilesDir("logs") ?: filesDir
        logger.initialize(logDir)
        logger.i("VisionAgentApp", "Vision Agent ${BuildConfig.VERSION_NAME} started")
        logger.i("VisionAgentApp", "Device: ${Build.MODEL} | API: ${Build.VERSION.SDK_INT}")
        logger.i("VisionAgentApp", "RAM: ${Runtime.getRuntime().maxMemory() / 1024 / 1024}MB available")
    }

    companion object {
        const val CHANNEL_CAPTURE = "channel_capture"
        const val CHANNEL_ALERTS = "channel_alerts"
    }
}
