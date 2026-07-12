package com.visionagent.core.screen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * ScreenCaptureService — Foreground service for MediaProjection screen capture.
 * Required by Android to keep screen capture alive while app is in background.
 */
@AndroidEntryPoint
class ScreenCaptureService : Service() {

    @Inject lateinit var screenCaptureEngine: ScreenCaptureEngine

    companion object {
        private const val TAG             = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID      = "vision_agent_capture"

        const val ACTION_START = "com.visionagent.START_CAPTURE"
        const val ACTION_STOP  = "com.visionagent.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE  = "result_code"
        const val EXTRA_DATA_INTENT  = "data_intent"
        const val EXTRA_SESSION_ID   = "session_id"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val dataIntent = intent.getParcelableExtra<Intent>(EXTRA_DATA_INTENT)
                val sessionId  = intent.getStringExtra(EXTRA_SESSION_ID)
                    ?: java.util.UUID.randomUUID().toString()

                if (resultCode != -1 && dataIntent != null) {
                    screenCaptureEngine.startCapture(resultCode, dataIntent, sessionId)
                }
            }
            ACTION_STOP -> {
                screenCaptureEngine.stopCapture()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        screenCaptureEngine.stopCapture()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Vision Agent Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Screen capture for Vision Agent" }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Vision Agent")
            .setContentText("Agent is active and monitoring")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
}
