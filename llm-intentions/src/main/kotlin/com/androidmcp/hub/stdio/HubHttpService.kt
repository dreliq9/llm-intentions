package com.androidmcp.hub.stdio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.androidmcp.hub.HubMcpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class HubHttpService : Service() {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private lateinit var engine: HubMcpEngine
    private var server: McpRawHttpServer? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))

        engine = HubMcpEngine(this)

        serviceScope.launch {
            withContext(Dispatchers.IO) {
                engine.initialize()
            }
            sharedEngine = engine
            startedAtMillis = System.currentTimeMillis()

            server = McpRawHttpServer(PORT, engine, json)
            server?.start()

            Log.i(TAG, "HTTP server started on 127.0.0.1:$PORT with ${engine.registry.size()} tools")
            updateNotification("Running — ${engine.registry.size()} tools on localhost:$PORT")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        server?.stop()
        engine.shutdown()
        sharedEngine = null
        startedAtMillis = 0L
        Log.i(TAG, "HubHttpService destroyed")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LLM Intentions Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps LLM Intentions running for local MCP clients"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("LLM Intentions")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("LLM Intentions")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .build()
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "LLM-Http"
        const val PORT = 8379
        private const val CHANNEL_ID = "llm_intentions_service"
        private const val NOTIFICATION_ID = 1

        @Volatile var sharedEngine: HubMcpEngine? = null
            private set
        @Volatile var startedAtMillis: Long = 0L
            private set
    }
}
