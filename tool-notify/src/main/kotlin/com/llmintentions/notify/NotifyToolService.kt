package com.llmintentions.notify

import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import com.androidmcp.core.registry.ToolRegistry
import com.androidmcp.core.registry.jsonSchema
import com.androidmcp.intent.ToolAppService
import com.androidmcp.intent.textTool
import kotlinx.serialization.json.*
import java.text.SimpleDateFormat
import java.util.*

class NotifyToolService : ToolAppService() {

    override fun onCreateTools(registry: ToolRegistry) {

        // --- notifications_list ---
        registry.textTool("notifications_list", "List all currently active notifications on the device. Requires NotificationListener permission enabled in Settings.",
            jsonSchema { integer("limit", "Max results (default 50)", required = false) }
        ) { args ->
            val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 50
            val listener = NotifyListenerService.instance
                ?: return@textTool "NotificationListener not connected. Enable in Settings > Notifications > Notification access."

            val notifications = listener.activeNotifications
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val list = notifications.take(limit).map { sbn ->
                buildJsonObject {
                    put("key", sbn.key)
                    put("package", sbn.packageName)
                    put("time", df.format(Date(sbn.postTime)))
                    put("title", sbn.notification.extras.getCharSequence("android.title")?.toString() ?: "")
                    put("text", sbn.notification.extras.getCharSequence("android.text")?.toString() ?: "")
                    put("ongoing", sbn.isOngoing)
                    put("has_reply_action", hasReplyAction(sbn))
                }
            }
            buildJsonObject {
                put("count", list.size)
                put("listener_connected", true)
                put("notifications", JsonArray(list))
            }.toString()
        }

        // --- notification_details ---
        registry.textTool("notification_details", "Get full details for a notification by key",
            jsonSchema { string("key", "Notification key") }
        ) { args ->
            val key = args["key"]?.jsonPrimitive?.content ?: ""
            val listener = NotifyListenerService.instance
                ?: return@textTool "NotificationListener not connected"

            val sbn = listener.activeNotifications.find { it.key == key }
                ?: return@textTool "Notification not found: $key"

            val extras = sbn.notification.extras
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            buildJsonObject {
                put("key", sbn.key)
                put("package", sbn.packageName)
                put("time", df.format(Date(sbn.postTime)))
                put("title", extras.getCharSequence("android.title")?.toString() ?: "")
                put("text", extras.getCharSequence("android.text")?.toString() ?: "")
                put("big_text", extras.getCharSequence("android.bigText")?.toString() ?: "")
                put("subtext", extras.getCharSequence("android.subText")?.toString() ?: "")
                put("info_text", extras.getCharSequence("android.infoText")?.toString() ?: "")
                put("summary_text", extras.getCharSequence("android.summaryText")?.toString() ?: "")
                put("category", sbn.notification.category ?: "")
                put("ongoing", sbn.isOngoing)
                put("actions", JsonArray(
                    (sbn.notification.actions ?: emptyArray()).map { action ->
                        buildJsonObject {
                            put("title", action.title?.toString() ?: "")
                            put("has_remote_input", action.remoteInputs?.isNotEmpty() == true)
                        }
                    }
                ))
            }.toString()
        }

        // --- notification_dismiss ---
        registry.textTool("notification_dismiss", "Dismiss a notification by key",
            jsonSchema { string("key", "Notification key to dismiss") }
        ) { args ->
            val key = args["key"]?.jsonPrimitive?.content ?: ""
            val listener = NotifyListenerService.instance
                ?: return@textTool "NotificationListener not connected"
            listener.cancelNotification(key)
            "Dismissed: $key"
        }

        // --- notification_dismiss_all ---
        registry.textTool("notification_dismiss_all", "Dismiss all dismissable notifications",
            jsonSchema { }
        ) {
            val listener = NotifyListenerService.instance
                ?: return@textTool "NotificationListener not connected"
            listener.cancelAllNotifications()
            "All notifications dismissed"
        }

        // --- notification_reply ---
        registry.textTool("notification_reply", "Reply to a notification that has a reply action (e.g., messaging apps)",
            jsonSchema {
                string("key", "Notification key")
                string("reply", "Reply text")
            }
        ) { args ->
            val key = args["key"]?.jsonPrimitive?.content ?: ""
            val replyText = args["reply"]?.jsonPrimitive?.content ?: ""
            val listener = NotifyListenerService.instance
                ?: return@textTool "NotificationListener not connected"

            val sbn = listener.activeNotifications.find { it.key == key }
                ?: return@textTool "Notification not found: $key"

            val actions = sbn.notification.actions ?: return@textTool "No actions on this notification"
            for (action in actions) {
                val remoteInputs = action.remoteInputs ?: continue
                if (remoteInputs.isNotEmpty()) {
                    val intent = Intent()
                    val bundle = Bundle()
                    for (ri in remoteInputs) {
                        bundle.putCharSequence(ri.resultKey, replyText)
                    }
                    RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)
                    try {
                        action.actionIntent.send(applicationContext, 0, intent)
                    } catch (e: android.app.PendingIntent.CanceledException) {
                        return@textTool "Reply failed: notification action expired"
                    }
                    return@textTool "Reply sent to $key: ${replyText.take(50)}"
                }
            }
            "No reply action found on notification $key"
        }

        // --- notification_history ---
        registry.textTool("notification_history", "Get recent notification history (last 200, stored in memory)",
            jsonSchema {
                integer("limit", "Max results (default 50)", required = false)
                string("package_filter", "Filter by package name", required = false)
                string("text_filter", "Filter by text content", required = false)
            }
        ) { args ->
            val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 50
            val pkgFilter = args["package_filter"]?.jsonPrimitive?.contentOrNull
            val textFilter = args["text_filter"]?.jsonPrimitive?.contentOrNull?.lowercase()

            var items = NotifyListenerService.history.toList()
            if (pkgFilter != null) items = items.filter { it["package"]?.jsonPrimitive?.content == pkgFilter }
            if (textFilter != null) items = items.filter {
                (it["title"]?.jsonPrimitive?.contentOrNull?.lowercase()?.contains(textFilter) == true) ||
                (it["text"]?.jsonPrimitive?.contentOrNull?.lowercase()?.contains(textFilter) == true)
            }
            JsonArray(items.take(limit)).toString()
        }

        // --- notification_filter ---
        registry.textTool("notification_filter", "Search active notifications by app or text",
            jsonSchema {
                string("package_filter", "Filter by package name", required = false)
                string("text_filter", "Search in title/text", required = false)
            }
        ) { args ->
            val pkgFilter = args["package_filter"]?.jsonPrimitive?.contentOrNull
            val textFilter = args["text_filter"]?.jsonPrimitive?.contentOrNull?.lowercase()
            val listener = NotifyListenerService.instance
                ?: return@textTool "NotificationListener not connected"

            var notifications = listener.activeNotifications.toList()
            if (pkgFilter != null) notifications = notifications.filter { it.packageName == pkgFilter }

            val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val list = notifications.mapNotNull { sbn ->
                val title = sbn.notification.extras.getCharSequence("android.title")?.toString() ?: ""
                val text = sbn.notification.extras.getCharSequence("android.text")?.toString() ?: ""
                if (textFilter != null && !title.lowercase().contains(textFilter) && !text.lowercase().contains(textFilter)) {
                    return@mapNotNull null
                }
                buildJsonObject {
                    put("key", sbn.key)
                    put("package", sbn.packageName)
                    put("time", df.format(Date(sbn.postTime)))
                    put("title", title)
                    put("text", text)
                }
            }
            JsonArray(list).toString()
        }
    }

    private fun hasReplyAction(sbn: android.service.notification.StatusBarNotification): Boolean {
        return sbn.notification.actions?.any { it.remoteInputs?.isNotEmpty() == true } == true
    }
}
