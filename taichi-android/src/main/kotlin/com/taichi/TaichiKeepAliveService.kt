package com.taichi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Foreground service that keeps the Taichi process alive so Android
 * doesn't kill TaichiToolService when the activity is backgrounded.
 *
 * This service does nothing itself — it just holds the persistent
 * notification that prevents process death.
 */
class TaichiKeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.i(TAG, "Keep-alive service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Keep-alive service destroyed")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Taichi MCP Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Taichi available for LLM tool calls"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Taichi")
                .setContentText("MCP tool service running")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Taichi")
                .setContentText("MCP tool service running")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .build()
        }
    }

    companion object {
        private const val TAG = "Taichi-KeepAlive"
        private const val CHANNEL_ID = "taichi_service"
        private const val NOTIFICATION_ID = 1
    }
}
