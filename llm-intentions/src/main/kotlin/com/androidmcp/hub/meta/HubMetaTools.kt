package com.androidmcp.hub.meta

import android.util.Log
import com.androidmcp.core.protocol.*
import com.androidmcp.core.registry.ToolRegistry
import com.androidmcp.core.registry.envelopeTool
import com.androidmcp.core.registry.jsonSchema
import com.androidmcp.core.registry.textTool
import com.androidmcp.core.registry.toolMetadata
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
        registry.textTool(
            name = "hub.status",
            description = "Show Hub status: discovered apps, tool counts per source, total tools",
            params = jsonSchema { },
            metadata = toolMetadata {
                destructive = false
                idempotent = true
                latencyClass = LatencyClass.FAST
            },
        ) { _ ->
            val apps = getDiscoveredApps()
            buildString {
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
        }
    }

    private fun registerHealth(registry: ToolRegistry) {
        registry.textTool(
            name = "hub.health",
            description = "Ping all discovered tool apps and report their health status",
            params = jsonSchema { },
            metadata = toolMetadata {
                destructive = false
                idempotent = true
                latencyClass = LatencyClass.SLOW
            },
        ) { _ ->
            val apps = getDiscoveredApps()
            if (apps.isEmpty()) {
                "No external tool apps discovered."
            } else {
                val results = healthMonitor.checkAll(apps)
                buildString {
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
            }
        }
    }

    private fun registerClaude(registry: ToolRegistry) {
        val claudeMeta = toolMetadata {
            destructive = false
            idempotent = false
            latencyClass = LatencyClass.VERY_SLOW
            failureMode(
                pattern = "Connection refused|ECONNREFUSED|Failed to connect",
                hint = "Termux relay server is not running on port $TERMUX_RELAY_PORT. Start it in Termux with: node ~/termux-mcp/server.js",
            )
            failureMode(
                exceptionType = "SocketTimeoutException",
                hint = "Claude CLI took longer than the 5-minute read timeout. Try a shorter prompt or check Termux logs.",
            )
            failureMode(
                pattern = "HTTP 404",
                hint = "The /claude endpoint is not implemented on the Termux relay — upgrade termux-mcp/server.js to a version that supports it.",
            )
            example(intent = "Ask Claude a quick question via your own subscription.") { args ->
                args["message"] = "What is the capital of France?"
            }
        }

        registry.envelopeTool(
            name = "hub.claude",
            description = "Send a message to Claude Code CLI running in Termux and return the response. Uses the user's Claude subscription.",
            params = jsonSchema {
                string("message", "The message to send to Claude")
                string("session_id", "Session ID for conversation continuity", required = false)
                string("system_prompt", "Additional system prompt to append", required = false)
                string("model", "Model override: sonnet, opus, haiku", required = false)
            },
            metadata = claudeMeta,
        ) { args ->
            val message = args["message"]?.jsonPrimitive?.contentOrNull
                ?: return@envelopeTool Envelope.fail(
                    summary = "hub.claude requires a 'message' argument",
                    hint = "Pass {\"message\": \"<your question>\"} in the call arguments.",
                )

            try {
                val result = withContext(Dispatchers.IO) {
                    callClaudeRelay(message, args)
                }
                Envelope.ok(
                    summary = "hub.claude responded",
                    data = buildJsonObject { put("response", JsonPrimitive(result)) },
                )
            } catch (e: Exception) {
                Log.e("HubMetaTools", "Claude relay failed", e)
                Envelope.fromException(
                    toolName = "hub.claude",
                    metadata = claudeMeta,
                    exception = e,
                )
            }
        }
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
        registry.textTool(
            name = "hub.refresh",
            description = "Force re-discovery of all tool apps and rebuild tool registry",
            params = jsonSchema { },
            metadata = toolMetadata {
                destructive = false
                idempotent = true
                latencyClass = LatencyClass.SLOW
            },
        ) { _ ->
            val result = refreshCallback()
            buildString {
                appendLine("Tool registry refreshed.")
                appendLine("Previous external tools: ${result.previousCount}")
                appendLine("New external tools: ${result.newCount}")
                appendLine()
                appendLine("Discovered apps:")
                for (app in result.apps) {
                    appendLine("  ${app.namespace} — ${app.tools.size} tools (${app.packageName})")
                }
            }
        }
    }

    companion object {
        private const val TERMUX_RELAY_PORT = 8378
    }
}
