package com.androidmcp.hub.system

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import com.androidmcp.core.protocol.*
import com.androidmcp.core.registry.McpToolDef
import com.androidmcp.core.registry.ToolRegistry
import com.androidmcp.core.registry.jsonSchema
import kotlinx.serialization.json.*

/**
 * System-level MCP tools under the "system" namespace.
 * Direct Android API access — no Termux:API dependency.
 */
class SystemToolDefinitions(private val context: Context) {

    fun registerAll(registry: ToolRegistry) {
        registerBatteryStatus(registry)
        registerClipboardGet(registry)
        registerClipboardSet(registry)
        registerWifiInfo(registry)
        registerDeviceInfo(registry)
        registerVolume(registry)
    }

    private fun registerBatteryStatus(registry: ToolRegistry) {
        registry.register(McpToolDef(
            info = ToolInfo(
                name = "system.battery",
                description = "Get battery level, charging status, and health",
                inputSchema = jsonSchema { }
            ),
            handler = { _ ->
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val charging = bm.isCharging
                val status = when {
                    charging -> "Charging"
                    level > 20 -> "Discharging"
                    else -> "Low"
                }
                ToolCallResult(content = listOf(ContentBlock.text(
                    "Battery: $level%\nStatus: $status\nCharging: $charging"
                )))
            }
        ))
    }

    private fun registerClipboardGet(registry: ToolRegistry) {
        registry.register(McpToolDef(
            info = ToolInfo(
                name = "system.clipboard_get",
                description = "Get the current clipboard contents. Note: Android 10+ restricts background clipboard access — may return empty if Hub is not in the foreground.",
                inputSchema = jsonSchema { }
            ),
            handler = { _ ->
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = try {
                    cm.primaryClip?.getItemAt(0)?.text?.toString()
                } catch (_: Exception) {
                    null
                }
                if (text != null) {
                    ToolCallResult(content = listOf(ContentBlock.text(text)))
                } else {
                    ToolCallResult(content = listOf(ContentBlock.text(
                        "(empty or inaccessible — Android 10+ restricts background clipboard reads)"
                    )))
                }
            }
        ))
    }

    private fun registerClipboardSet(registry: ToolRegistry) {
        registry.register(McpToolDef(
            info = ToolInfo(
                name = "system.clipboard_set",
                description = "Set the clipboard contents",
                inputSchema = jsonSchema {
                    string("text", "Text to copy to clipboard")
                }
            ),
            handler = { args ->
                val text = args["text"]?.jsonPrimitive?.content ?: ""
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("MCP", text))
                ToolCallResult(content = listOf(ContentBlock.text("Copied to clipboard: ${text.take(100)}")))
            }
        ))
    }

    private fun registerWifiInfo(registry: ToolRegistry) {
        registry.register(McpToolDef(
            info = ToolInfo(
                name = "system.wifi_info",
                description = "Get current WiFi connection info",
                inputSchema = jsonSchema { }
            ),
            handler = { _ ->
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val info = wm.connectionInfo
                @Suppress("DEPRECATION")
                val text = buildString {
                    appendLine("SSID: ${info.ssid}")
                    appendLine("BSSID: ${info.bssid}")
                    appendLine("RSSI: ${info.rssi} dBm")
                    appendLine("Link Speed: ${info.linkSpeed} Mbps")
                    appendLine("IP: ${intToIp(info.ipAddress)}")
                }
                ToolCallResult(content = listOf(ContentBlock.text(text)))
            }
        ))
    }

    private fun registerDeviceInfo(registry: ToolRegistry) {
        registry.register(McpToolDef(
            info = ToolInfo(
                name = "system.device_info",
                description = "Get detailed device information",
                inputSchema = jsonSchema { }
            ),
            handler = { _ ->
                val text = buildString {
                    appendLine("Manufacturer: ${Build.MANUFACTURER}")
                    appendLine("Model: ${Build.MODEL}")
                    appendLine("Device: ${Build.DEVICE}")
                    appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    appendLine("Board: ${Build.BOARD}")
                    appendLine("Hardware: ${Build.HARDWARE}")
                    appendLine("Product: ${Build.PRODUCT}")
                    appendLine("Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
                    appendLine("Cores: ${Runtime.getRuntime().availableProcessors()}")
                    appendLine("Memory: ${Runtime.getRuntime().maxMemory() / 1024 / 1024} MB max")
                }
                ToolCallResult(content = listOf(ContentBlock.text(text)))
            }
        ))
    }

    private fun registerVolume(registry: ToolRegistry) {
        registry.register(McpToolDef(
            info = ToolInfo(
                name = "system.volume",
                description = "Get or set device volume levels",
                inputSchema = jsonSchema {
                    string("stream", "Audio stream: music, ring, notification, alarm", required = false)
                    integer("level", "Volume level to set (omit to just read current)", required = false)
                }
            ),
            handler = { args ->
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val streamName = args["stream"]?.jsonPrimitive?.contentOrNull ?: "music"
                val stream = when (streamName) {
                    "ring" -> AudioManager.STREAM_RING
                    "notification" -> AudioManager.STREAM_NOTIFICATION
                    "alarm" -> AudioManager.STREAM_ALARM
                    else -> AudioManager.STREAM_MUSIC
                }

                val setLevel = args["level"]?.jsonPrimitive?.intOrNull
                if (setLevel != null) {
                    am.setStreamVolume(stream, setLevel, 0)
                }

                val current = am.getStreamVolume(stream)
                val max = am.getStreamMaxVolume(stream)

                ToolCallResult(content = listOf(ContentBlock.text(
                    "Stream: $streamName\nVolume: $current / $max"
                )))
            }
        ))
    }

    private fun intToIp(ip: Int): String {
        return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
    }
}
