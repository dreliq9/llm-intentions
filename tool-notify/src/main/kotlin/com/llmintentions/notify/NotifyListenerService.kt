package com.llmintentions.notify

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.serialization.json.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * NotificationListenerService that captures all device notifications.
 * Runs independently — Android manages its lifecycle.
 * NotifyToolService reads from the shared history.
 */
class NotifyListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val entry = buildJsonObject {
            put("key", sbn.key)
            put("package", sbn.packageName)
            put("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(sbn.postTime)))
            put("title", sbn.notification.extras.getCharSequence("android.title")?.toString() ?: "")
            put("text", sbn.notification.extras.getCharSequence("android.text")?.toString() ?: "")
            put("subtext", sbn.notification.extras.getCharSequence("android.subText")?.toString() ?: "")
            put("ongoing", sbn.isOngoing)
        }
        history.addFirst(entry)
        while (history.size > MAX_HISTORY) history.removeLast()
        Log.d(TAG, "Notification from ${sbn.packageName}: ${entry}")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        Log.d(TAG, "Notification removed: ${sbn.key}")
    }

    companion object {
        private const val TAG = "MCP-NotifyListener"
        private const val MAX_HISTORY = 200
        val history = ConcurrentLinkedDeque<JsonObject>()

        // Singleton reference so NotifyToolService can call getActiveNotifications()
        @Volatile
        var instance: NotifyListenerService? = null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "NotificationListenerService connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        Log.i(TAG, "NotificationListenerService disconnected")
    }
}
