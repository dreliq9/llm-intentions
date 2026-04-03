package com.androidmcp.hub.system

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.androidmcp.core.protocol.*
import com.androidmcp.core.registry.McpToolDef
import com.androidmcp.core.registry.ToolRegistry
import com.androidmcp.core.registry.jsonSchema
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP tools for posting and managing Android notifications.
 * Registered under the "system" namespace.
 */
class NotificationTools(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val CHANNEL_ID = "mcp_hub_default"
        const val CHANNEL_NAME = "LLM Intentions"

        // State lives in companion so it survives across instances.
        // NotificationTools is re-created on every populateRegistry() call,
        // but posted notifications need to remain cancellable after refresh.
        private val idCounter = AtomicInteger(1000)
        private val postedNotifications = java.util.concurrent.ConcurrentHashMap<String, Int>()
    }

    init {
        ensureChannel()
    }

    private fun ensureChannel() {
        // minSdk 26 = O, so notification channels are always available
        val existing = notificationManager.getNotificationChannel(CHANNEL_ID)
        if (existing == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications posted via MCP tools"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun registerAll(registry: ToolRegistry) {
        registerSendNotification(registry)
        registerCancelNotification(registry)
        registerListChannels(registry)
    }

    private fun registerSendNotification(registry: ToolRegistry) {
        registry.register(McpToolDef(
            info = ToolInfo(
                name = "system.send_notification",
                description = "Post an Android notification. Returns a tag that can be used to cancel it later.",
                inputSchema = jsonSchema {
                    string("title", "Notification title")
                    string("text", "Notification body text")
                    string("tag", "Unique tag for this notification (for cancel/update)", required = false)
                    string("priority", "Priority: low, default, high", required = false)
                    boolean("ongoing", "If true, notification cannot be swiped away", required = false)
                }
            ),
            handler = { args ->
                val title = args["title"]?.jsonPrimitive?.content ?: "LLM Intentions"
                val text = args["text"]?.jsonPrimitive?.content ?: ""
                val tag = args["tag"]?.jsonPrimitive?.contentOrNull
                    ?: "mcp_${System.currentTimeMillis()}"
                val priority = when (args["priority"]?.jsonPrimitive?.contentOrNull) {
                    "low" -> NotificationCompat.PRIORITY_LOW
                    "high" -> NotificationCompat.PRIORITY_HIGH
                    else -> NotificationCompat.PRIORITY_DEFAULT
                }
                val ongoing = args["ongoing"]?.jsonPrimitive?.booleanOrNull ?: false

                val id = postedNotifications.getOrPut(tag) { idCounter.getAndIncrement() }

                val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setPriority(priority)
                    .setOngoing(ongoing)
                    .setAutoCancel(!ongoing)
                    .build()

                notificationManager.notify(tag, id, notification)

                ToolCallResult(content = listOf(ContentBlock.text(
                    "Notification posted (tag: $tag)"
                )))
            }
        ))
    }

    private fun registerCancelNotification(registry: ToolRegistry) {
        registry.register(McpToolDef(
            info = ToolInfo(
                name = "system.cancel_notification",
                description = "Cancel a previously posted notification by its tag",
                inputSchema = jsonSchema {
                    string("tag", "Tag of the notification to cancel")
                }
            ),
            handler = { args ->
                val tag = args["tag"]?.jsonPrimitive?.content ?: ""
                val id = postedNotifications.remove(tag)

                if (id != null) {
                    notificationManager.cancel(tag, id)
                    ToolCallResult(content = listOf(ContentBlock.text(
                        "Notification cancelled: $tag"
                    )))
                } else {
                    ToolCallResult(
                        content = listOf(ContentBlock.text("No notification found with tag: $tag")),
                        isError = true
                    )
                }
            }
        ))
    }

    private fun registerListChannels(registry: ToolRegistry) {
        registry.register(McpToolDef(
            info = ToolInfo(
                name = "system.notification_channels",
                description = "List notification channels configured on this device for LLM Intentions",
                inputSchema = jsonSchema { }
            ),
            handler = { _ ->
                val channels = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    notificationManager.notificationChannels.joinToString("\n") { ch ->
                        "- ${ch.id}: ${ch.name} (importance: ${ch.importance})"
                    }
                } else {
                    "Notification channels not supported on this Android version"
                }

                val active = postedNotifications.keys.joinToString(", ").ifEmpty { "none" }

                ToolCallResult(content = listOf(ContentBlock.text(
                    "Channels:\n$channels\n\nActive notifications: $active"
                )))
            }
        ))
    }
}
