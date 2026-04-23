package com.androidmcp.hub.meta

import android.util.Log
import com.androidmcp.core.protocol.*
import com.androidmcp.core.registry.McpToolDef
import com.androidmcp.core.registry.ToolRegistry
import com.androidmcp.core.registry.jsonSchema
import com.androidmcp.hub.discovery.DiscoveredApp
import com.androidmcp.hub.health.IntentHealthMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL

/**
 * Meta-tools for inspecting and managing the Hub itself.
 * Registered under the "hub" namespace.
 */
class HubMetaTools(
    private val healthMonitor: IntentHealthMonitor,
    private val getDiscoveredApps: () -> List<DiscoveredApp>,
    private val refreshCallback: () -> RefreshResult
) {

    data class RefreshResult(
        val previousCount: Int,
        val newCount: Int,
        val apps: List<DiscoveredApp>
    )

    fun registerAll(registry: ToolRegistry) {
        registerStatus(registry)
        registerHealth(registry)
        registerRefresh(registry)
        registerClaude(registry)
    }

    private fun registerStatus(registry: ToolRegistry) {
        registry.register(McpToolDef(
            info = ToolInfo(
                name = "hub.status",
                description = "Show Hub status: discovered apps, tool counts per source, total tools",
                inputSchema = jsonSchema { }
            ),
            handler = { _ ->
                val apps = getDiscoveredApps()
                val text = buildString {
                    appendLine("LLM Intentions v0.5.0 — Intent Gateway")
                    appendLine()
                    appendLine("Discovered tool apps: ${apps.size}")
                    for (app in apps) {
                        appendLine("  ${app.namespace} — ${app.tools.size} tools (${app.packageName})")
                    }
                    appendLine()
                    appendLine("Built-in sources:")
                    appendLine("  android.* — Intent tools + system intents")
                    appendLine("  system.* — System API tools")
                    appendLine("  hub.* — Hub meta-tools")
                    appendLine()
                    val appTools = apps.sumOf { it.tools.size }
                    appendLine("External app tools: $appTools")
                }
                ToolCallResult(content = listOf(ContentBlock.text(text)))
            }
        ))
    }

    private fun registerHealth(registry: ToolRegistry) {
        registry.register(McpToolDef(
            info = ToolInfo(
                name = "hub.health",
                description = "Ping all discovered tool apps and report their health status",
                inputSchema = jsonSchema { }
            ),
            handler = { _ ->
                val apps = getDiscoveredApps()
                if (apps.isEmpty()) {
                    ToolCallResult(content = listOf(ContentBlock.text(
                        "No external tool apps discovered."
                    )))
                } else {
                    val results = healthMonitor.checkAll(apps)

                    val text = buildString {
                        appendLine("Tool App Health Check")
                        appendLine("=".repeat(40))
                        for (status in results) {
                            val icon = if (status.alive) "OK" else "DEAD"
                            appendLine()
                            appendLine("[$icon] ${status.namespace} (${status.packageName})")
                            if (status.alive) {
                                appendLine("  Latency: ${status.roundTripMs}ms")
                            } else {
                                appendLine("  Error: ${status.error ?: "Unknown"}")
                            }
                        }
                        appendLine()
                        val alive = results.count { it.alive }
                        appendLine("$alive/${results.size} apps healthy")
                    }
                    ToolCallResult(content = listOf(ContentBlock.text(text)))
                }
            }
        ))
    }

    private fun registerClaude(registry: ToolRegistry) {
        registry.register(McpToolDef(
            info = ToolInfo(
                name = "hub.claude",
                description = "Send a message to Claude Code CLI running in Termux and return the response. Uses the user's Claude subscription.",
                inputSchema = jsonSchema {
                    string("message", "The message to send to Claude")
                    string("session_id", "Session ID for conversation continuity", required = false)
                    string("system_prompt", "Additional system prompt to append", required = false)
                    string("model", "Model override: sonnet, opus, haiku", required = false)
                }
            ),
            handler = { args ->
                // args is the arguments JsonObject directly
                val message = args["message"]?.jsonPrimitive?.contentOrNull
                    ?: return@McpToolDef ToolCallResult(
                        content = listOf(ContentBlock.text("Error: message is required")),
                        isError = true
                    )

                try {
                    val result = withContext(Dispatchers.IO) {
                        callClaudeRelay(message, args)
                    }
                    ToolCallResult(content = listOf(ContentBlock.text(result)))
                } catch (e: Exception) {
                    Log.e("HubMetaTools", "Claude relay failed", e)
                    ToolCallResult(
                        content = listOf(ContentBlock.text("Claude relay error: ${e.message}")),
                        isError = true
                    )
                }
            }
        ))
    }

    private fun callClaudeRelay(message: String, args: JsonObject): String {
        val payload = buildJsonObject {
            put("message", JsonPrimitive(message))
            args?.get("session_id")?.jsonPrimitive?.contentOrNull?.let {
                put("session_id", JsonPrimitive(it))
            }
            args?.get("system_prompt")?.jsonPrimitive?.contentOrNull?.let {
                put("system_prompt", JsonPrimitive(it))
            }
            args?.get("model")?.jsonPrimitive?.contentOrNull?.let {
                put("model", JsonPrimitive(it))
            }
        }

        val url = URL("http://127.0.0.1:$TERMUX_RELAY_PORT/claude")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 10_000
        conn.readTimeout = 300_000 // 5 minute read timeout
        conn.doOutput = true

        conn.outputStream.use { out ->
            out.write(payload.toString().toByteArray(Charsets.UTF_8))
        }

        val responseCode = conn.responseCode
        val body = if (responseCode in 200..299) {
            conn.inputStream.bufferedReader().readText()
        } else {
            conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
        }

        // Parse the Claude JSON response and extract the result text
        return try {
            val json = Json.parseToJsonElement(body).jsonObject
            val resultText = json["result"]?.jsonPrimitive?.contentOrNull
            val error = json["error"]?.let { err ->
                when (err) {
                    is JsonPrimitive -> err.contentOrNull
                    is JsonObject -> err["message"]?.jsonPrimitive?.contentOrNull
                    else -> err.toString()
                }
            }

            when {
                resultText != null -> resultText
                error != null -> "Error: $error"
                else -> body // Return raw response if we can't parse it
            }
        } catch (_: Exception) {
            body // Return raw if JSON parsing fails
        }
    }

    private fun registerRefresh(registry: ToolRegistry) {
        registry.register(McpToolDef(
            info = ToolInfo(
                name = "hub.refresh",
                description = "Force re-discovery of all tool apps and rebuild tool registry",
                inputSchema = jsonSchema { }
            ),
            handler = { _ ->
                val result = refreshCallback()
                val text = buildString {
                    appendLine("Tool registry refreshed.")
                    appendLine("Previous external tools: ${result.previousCount}")
                    appendLine("New external tools: ${result.newCount}")
                    appendLine()
                    appendLine("Discovered apps:")
                    for (app in result.apps) {
                        appendLine("  ${app.namespace} — ${app.tools.size} tools (${app.packageName})")
                    }
                }
                ToolCallResult(content = listOf(ContentBlock.text(text)))
            }
        ))
    }

    companion object {
        private const val TERMUX_RELAY_PORT = 8378
    }
}
