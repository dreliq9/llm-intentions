package com.llmintentions.people

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

class KeepAliveService : Service() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("people_service", "People Tools Service", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps People Tools available for LLM tool calls"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        startForeground(1, buildNotification())
        Log.i("People-KeepAlive", "Started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "people_service")
                .setContentTitle("People Tools").setContentText("MCP tool service running")
                .setSmallIcon(android.R.drawable.ic_menu_manage).setOngoing(true).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("People Tools").setContentText("MCP tool service running")
                .setSmallIcon(android.R.drawable.ic_menu_manage).setOngoing(true).build()
        }
    }
}
