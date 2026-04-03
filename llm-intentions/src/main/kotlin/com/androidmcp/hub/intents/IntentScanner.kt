package com.androidmcp.hub.intents

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.MediaStore
import com.androidmcp.core.protocol.*
import com.androidmcp.core.registry.McpToolDef
import com.androidmcp.core.registry.ToolRegistry
import com.androidmcp.core.registry.jsonSchema
import kotlinx.serialization.json.*

/**
 * Auto-discovers Android app capabilities via Intent resolution and registers
 * them as MCP tools. Groups by capability, not by app — avoids overwhelming
 * the LLM with hundreds of duplicate handlers.
 *
 * Tools are registered under the "android" namespace alongside the curated ones.
 */
class IntentScanner(private val context: Context) {

    private val pm = context.packageManager

    fun registerDiscovered(registry: ToolRegistry) {
        discoverShareTargets(registry)
        discoverViewHandlers(registry)
        discoverMediaCapabilities(registry)
        discoverProductivityActions(registry)
    }

    // --- Share targets: what MIME types can be shared? ---

    private fun discoverShareTargets(registry: ToolRegistry) {
        val mimeTypes = listOf(
            "text/plain" to "text",
            "image/*" to "images",
            "application/pdf" to "PDFs",
            "video/*" to "videos",
            "audio/*" to "audio files",
        )

        val shareable = mutableListOf<String>()

        for ((mime, label) in mimeTypes) {
            val intent = Intent(Intent.ACTION_SEND).apply { type = mime }
            val handlers = queryHandlers(intent)
            if (handlers.size >= 2) { // at least 2 apps can receive this type
                shareable.add(label)
            }
        }

        if (shareable.isNotEmpty()) {
            // Register a text share tool. Non-text types (images, PDFs, etc.)
            // require file URIs which aren't available through MCP Content Provider IPC.
            // The device can share: ${shareable} — but only text is MCP-automatable.
            registry.register(McpToolDef(
                info = ToolInfo(
                    name = "android.share",
                    description = "Share text via Android share sheet. Device also supports sharing ${shareable.joinToString(", ")} manually.",
                    inputSchema = jsonSchema {
                        string("text", "Text content to share")
                    }
                ),
                handler = { args ->
                    val text = args["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    ToolCallResult(content = listOf(ContentBlock.text("Share sheet opened")))
                }
            ))
        }
    }

    // --- View handlers: what URI schemes and content types can be opened? ---

    private fun discoverViewHandlers(registry: ToolRegistry) {
        val schemes = mapOf(
            "mailto" to "compose email",
            "sms" to "compose SMS",
            "smsto" to "compose SMS",
            "spotify" to "open in Spotify",
            "youtube" to "open in YouTube",
            "twitter" to "open in Twitter/X",
            "instagram" to "open in Instagram",
            "slack" to "open in Slack",
            "tg" to "open in Telegram",
            "whatsapp" to "open in WhatsApp",
        )

        val available = mutableListOf<Pair<String, String>>()

        for ((scheme, description) in schemes) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$scheme:test"))
            val handlers = queryHandlers(intent)
            if (handlers.isNotEmpty()) {
                available.add(scheme to description)
            }
        }

        if (available.isNotEmpty()) {
            registry.register(McpToolDef(
                info = ToolInfo(
                    name = "android.open_app",
                    description = buildString {
                        append("Open content in a specific app via deep link. Available: ")
                        append(available.joinToString(", ") { "${it.first}:// (${it.second})" })
                    },
                    inputSchema = jsonSchema {
                        string("uri", "Deep link URI (e.g., mailto:user@example.com, tg://resolve?domain=username)")
                    }
                ),
                handler = { args ->
                    val uri = args["uri"]?.jsonPrimitive?.content ?: ""
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(intent)
                        ToolCallResult(content = listOf(ContentBlock.text("Opened: $uri")))
                    } catch (e: Exception) {
                        ToolCallResult(
                            content = listOf(ContentBlock.text("No app can handle: $uri")),
                            isError = true
                        )
                    }
                }
            ))
        }
    }

    // --- Media capabilities: camera, audio recording, media playback ---

    private fun discoverMediaCapabilities(registry: ToolRegistry) {
        val capabilities = mutableListOf<String>()

        // Check for camera
        if (hasHandler(Intent(MediaStore.ACTION_IMAGE_CAPTURE))) {
            capabilities.add("photo capture")
        }
        if (hasHandler(Intent(MediaStore.ACTION_VIDEO_CAPTURE))) {
            capabilities.add("video recording")
        }

        // Check for audio recording
        if (hasHandler(Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION))) {
            capabilities.add("audio recording")
        }

        // Check for music playback
        val musicIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("content://media/external/audio/media/1"), "audio/*")
        }
        if (hasHandler(musicIntent)) {
            capabilities.add("audio playback")
        }

        if (capabilities.isNotEmpty()) {
            registry.register(McpToolDef(
                info = ToolInfo(
                    name = "android.media_capabilities",
                    description = "Device media capabilities: ${capabilities.joinToString(", ")}. " +
                        "Note: capture tools require user interaction — the camera/recorder UI will open.",
                    inputSchema = jsonSchema { }
                ),
                handler = { _ ->
                    ToolCallResult(content = listOf(ContentBlock.text(
                        "Available media capabilities:\n${capabilities.joinToString("\n") { "- $it" }}"
                    )))
                }
            ))
        }
    }

    // --- Productivity: calendar, contacts, document editing ---

    private fun discoverProductivityActions(registry: ToolRegistry) {
        val actions = mutableListOf<String>()

        // Calendar
        val calIntent = Intent(Intent.ACTION_INSERT).apply {
            data = android.provider.CalendarContract.Events.CONTENT_URI
        }
        if (hasHandler(calIntent)) {
            actions.add("create calendar events")
            registry.register(McpToolDef(
                info = ToolInfo(
                    name = "android.create_event",
                    description = "Create a calendar event",
                    inputSchema = jsonSchema {
                        string("title", "Event title")
                        string("description", "Event description", required = false)
                        string("location", "Event location", required = false)
                        string("begin", "Start time as epoch millis or ISO8601")
                        string("end", "End time as epoch millis or ISO8601", required = false)
                    }
                ),
                handler = { args ->
                    val title = args["title"]?.jsonPrimitive?.content ?: ""
                    val desc = args["description"]?.jsonPrimitive?.contentOrNull
                    val location = args["location"]?.jsonPrimitive?.contentOrNull
                    val begin = args["begin"]?.jsonPrimitive?.contentOrNull?.let { raw ->
                        raw.toLongOrNull() ?: try {
                            java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli()
                        } catch (_: Exception) {
                            try {
                                java.time.LocalDateTime.parse(raw)
                                    .atZone(java.time.ZoneId.systemDefault())
                                    .toInstant().toEpochMilli()
                            } catch (_: Exception) { null }
                        }
                    } ?: System.currentTimeMillis()

                    val intent = Intent(Intent.ACTION_INSERT).apply {
                        data = android.provider.CalendarContract.Events.CONTENT_URI
                        putExtra(android.provider.CalendarContract.Events.TITLE, title)
                        desc?.let { putExtra(android.provider.CalendarContract.Events.DESCRIPTION, it) }
                        location?.let { putExtra(android.provider.CalendarContract.Events.EVENT_LOCATION, it) }
                        putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    ToolCallResult(content = listOf(ContentBlock.text("Calendar event creation opened: $title")))
                }
            ))
        }

        // Document viewers/editors
        val docTypes = mapOf(
            "application/pdf" to "PDF",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "Excel",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "Word",
        )

        val viewable = mutableListOf<String>()
        for ((mime, label) in docTypes) {
            val intent = Intent(Intent.ACTION_VIEW).apply { type = mime }
            if (hasHandler(intent)) {
                viewable.add(label)
            }
        }

        if (viewable.isNotEmpty()) {
            registry.register(McpToolDef(
                info = ToolInfo(
                    name = "android.document_capabilities",
                    description = "Device can view/edit: ${viewable.joinToString(", ")}",
                    inputSchema = jsonSchema { }
                ),
                handler = { _ ->
                    ToolCallResult(content = listOf(ContentBlock.text(
                        "Document types supported:\n${viewable.joinToString("\n") { "- $it" }}"
                    )))
                }
            ))
        }
    }

    // --- Helpers ---

    private fun queryHandlers(intent: Intent): List<ResolveInfo> {
        return pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
    }

    private fun hasHandler(intent: Intent): Boolean {
        return queryHandlers(intent).isNotEmpty()
    }
}
