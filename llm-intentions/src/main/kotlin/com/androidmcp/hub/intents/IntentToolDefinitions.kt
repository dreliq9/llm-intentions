package com.androidmcp.hub.intents

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import com.androidmcp.core.protocol.*
import com.androidmcp.core.registry.McpToolDef
import com.androidmcp.core.registry.ToolRegistry
import com.androidmcp.core.registry.jsonSchema
import kotlinx.serialization.json.*

/**
 * Registers Android Intent-based tools under the "android" namespace.
 * These turn standard Android capabilities into MCP tools.
 */
class IntentToolDefinitions(private val context: Context) {

    fun registerAll(registry: ToolRegistry) {
        registerShareText(registry)
        registerOpenUrl(registry)
        registerOpenMaps(registry)
        registerDialPhone(registry)
        registerSetAlarm(registry)
        registerInstalledApps(registry)
    }

    private fun registerShareText(registry: ToolRegistry) {
        registry.register(McpToolDef(
            info = ToolInfo(
                name = "android.share_text",
                description = "Share text via Android share sheet (opens app chooser)",
                inputSchema = jsonSchema {
                    string("text", "Text to share")
                    string("title", "Title for the share chooser", required = false)
                }
            ),
            handler = { args ->
                val text = args["text"]?.jsonPrimitive?.content ?: ""
                val title = args["title"]?.jsonPrimitive?.contentOrNull ?: "Share"
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ToolCallResult(content = listOf(ContentBlock.text("Share sheet opened with text: ${text.take(100)}")))
            }
        ))
    }

    private fun registerOpenUrl(registry: ToolRegistry) {
        registry.register(McpToolDef(
            info = ToolInfo(
                name = "android.open_url",
                description = "Open a URL in the default browser",
                inputSchema = jsonSchema {
                    string("url", "URL to open")
                }
            ),
            handler = { args ->
                val url = args["url"]?.jsonPrimitive?.content ?: ""
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ToolCallResult(content = listOf(ContentBlock.text("Opened: $url")))
            }
        ))
    }

    private fun registerOpenMaps(registry: ToolRegistry) {
        registry.register(McpToolDef(
            info = ToolInfo(
                name = "android.open_maps",
                description = "Open a location in the maps app",
                inputSchema = jsonSchema {
                    string("query", "Location name or 'lat,lng' coordinates")
                }
            ),
            handler = { args ->
                val query = args["query"]?.jsonPrimitive?.content ?: ""
                val uri = if (query.matches(Regex("-?\\d+\\.\\d+,-?\\d+\\.\\d+"))) {
                    Uri.parse("geo:$query")
                } else {
                    Uri.parse("geo:0,0?q=${Uri.encode(query)}")
                }
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ToolCallResult(content = listOf(ContentBlock.text("Opened maps: $query")))
            }
        ))
    }

    private fun registerDialPhone(registry: ToolRegistry) {
        registry.register(McpToolDef(
            info = ToolInfo(
                name = "android.dial_phone",
                description = "Open the phone dialer with a number (does not auto-call)",
                inputSchema = jsonSchema {
                    string("number", "Phone number to dial")
                }
            ),
            handler = { args ->
                val number = args["number"]?.jsonPrimitive?.content ?: ""
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ToolCallResult(content = listOf(ContentBlock.text("Dialer opened: $number")))
            }
        ))
    }

    private fun registerSetAlarm(registry: ToolRegistry) {
        registry.register(McpToolDef(
            info = ToolInfo(
                name = "android.set_alarm",
                description = "Set an alarm on the device",
                inputSchema = jsonSchema {
                    integer("hour", "Hour (0-23)")
                    integer("minutes", "Minutes (0-59)")
                    string("message", "Alarm label", required = false)
                }
            ),
            handler = { args ->
                val hour = args["hour"]?.jsonPrimitive?.int ?: 8
                val minutes = args["minutes"]?.jsonPrimitive?.int ?: 0
                val message = args["message"]?.jsonPrimitive?.contentOrNull ?: "MCP Alarm"
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minutes)
                    putExtra(AlarmClock.EXTRA_MESSAGE, message)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ToolCallResult(content = listOf(ContentBlock.text("Alarm set: $hour:${minutes.toString().padStart(2, '0')} — $message")))
            }
        ))
    }

    private fun registerInstalledApps(registry: ToolRegistry) {
        registry.register(McpToolDef(
            info = ToolInfo(
                name = "android.installed_apps",
                description = "List installed apps on the device",
                inputSchema = jsonSchema {
                    boolean("user_only", "Only show user-installed apps (not system)", required = false)
                }
            ),
            handler = { args ->
                val userOnly = args["user_only"]?.jsonPrimitive?.booleanOrNull ?: true
                val pm = context.packageManager
                val packages = pm.getInstalledPackages(0)
                val apps = packages
                    .filter { pkg ->
                        if (userOnly) {
                            pkg.applicationInfo?.let {
                                (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0
                            } ?: false
                        } else true
                    }
                    .map { pkg ->
                        val label = pkg.applicationInfo?.let { pm.getApplicationLabel(it).toString() } ?: pkg.packageName
                        "$label (${pkg.packageName}) v${pkg.versionName ?: "?"}"
                    }
                    .sorted()

                ToolCallResult(content = listOf(ContentBlock.text(
                    "Installed apps (${apps.size}):\n${apps.joinToString("\n")}"
                )))
            }
        ))
    }
}
