package com.androidmcp.hub.intents

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.androidmcp.core.protocol.ContentBlock
import com.androidmcp.core.protocol.ToolCallResult
import com.androidmcp.core.protocol.ToolInfo
import com.androidmcp.core.registry.McpToolDef
import com.androidmcp.core.registry.ToolRegistry
import com.androidmcp.core.registry.jsonSchema
import kotlinx.serialization.json.*

/**
 * Generic Intent tools that give Claude direct access to the Android Intent system.
 *
 * These tools let Claude compose and send arbitrary Intents to any app,
 * using any of the communication patterns supported by IntentEngine.
 */
class GenericIntentTools(
    private val context: Context,
    private val engine: IntentEngine
) {

    fun registerAll(registry: ToolRegistry) {
        registerSendIntent(registry)
        registerQueryIntent(registry)
    }

    /**
     * android.send_intent — compose and send any Intent
     */
    private fun registerSendIntent(registry: ToolRegistry) {
        registry.register(McpToolDef(
            info = ToolInfo(
                name = "android.send_intent",
                description = buildString {
                    append("Send an Android Intent to any app. Supports all delivery methods: ")
                    append("activity (launch UI), broadcast (background message), service (background task), ")
                    append("ordered_broadcast (broadcast with result), result_receiver (service with callback), ")
                    append("activity_for_result (launch UI and get result back). ")
                    append("This is the universal interface to the Android app ecosystem.")
                },
                inputSchema = jsonSchema {
                    string("action", "Intent action (e.g., android.intent.action.VIEW, android.intent.action.SEND)")
                    string("data", "Intent data URI (e.g., https://example.com, tel:+1234567890)", required = false)
                    string("type", "MIME type (e.g., text/plain, image/*)", required = false)
                    string("package", "Target package name (e.g., com.whatsapp)", required = false)
                    string("component", "Explicit component as package/class (e.g., com.app/.MyActivity)", required = false)
                    string("extras", "JSON object of key-value extras to include", required = false)
                    string("flags", "Comma-separated Intent flags (e.g., FLAG_ACTIVITY_NEW_TASK)", required = false)
                    enum("delivery", "How to deliver the Intent",
                        listOf("activity", "broadcast", "service", "ordered_broadcast",
                               "result_receiver", "activity_for_result"),
                        required = false
                    )
                    integer("timeout_ms", "Timeout in ms for methods that wait for results (default 30000)", required = false)
                }
            ),
            handler = { args ->
                handleSendIntent(args)
            }
        ))
    }

    private suspend fun handleSendIntent(args: JsonObject): ToolCallResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull
        val data = args["data"]?.jsonPrimitive?.contentOrNull
        val type = args["type"]?.jsonPrimitive?.contentOrNull
        val pkg = args["package"]?.jsonPrimitive?.contentOrNull
        val component = args["component"]?.jsonPrimitive?.contentOrNull
        val extrasJson = args["extras"]?.jsonPrimitive?.contentOrNull
        val flagsStr = args["flags"]?.jsonPrimitive?.contentOrNull
        val delivery = args["delivery"]?.jsonPrimitive?.contentOrNull ?: "activity"
        val timeoutMs = args["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 30_000L

        // Build the Intent
        val intent = Intent().apply {
            action?.let { this.action = it }
            data?.let { this.data = Uri.parse(it) }
            type?.let { this.type = it }
            pkg?.let { this.setPackage(it) }
            component?.let {
                val parts = it.split("/")
                if (parts.size == 2) {
                    this.component = ComponentName(parts[0], parts[1])
                }
            }

            // Parse extras JSON
            extrasJson?.let { json ->
                try {
                    val extras = Json.parseToJsonElement(json).jsonObject
                    for ((key, value) in extras) {
                        when {
                            value is JsonPrimitive && value.isString ->
                                putExtra(key, value.content)
                            value is JsonPrimitive && value.booleanOrNull != null ->
                                putExtra(key, value.boolean)
                            value is JsonPrimitive && value.intOrNull != null ->
                                putExtra(key, value.int)
                            value is JsonPrimitive && value.longOrNull != null ->
                                putExtra(key, value.long)
                            value is JsonPrimitive && value.doubleOrNull != null ->
                                putExtra(key, value.double)
                            else ->
                                putExtra(key, value.toString())
                        }
                    }
                } catch (e: Exception) {
                    return ToolCallResult(
                        content = listOf(ContentBlock.text("Invalid extras JSON: ${e.message}")),
                        isError = true
                    )
                }
            }

            // Parse flags
            flagsStr?.split(",")?.forEach { flag ->
                when (flag.trim().uppercase()) {
                    "FLAG_ACTIVITY_NEW_TASK" -> addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    "FLAG_ACTIVITY_CLEAR_TOP" -> addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    "FLAG_ACTIVITY_SINGLE_TOP" -> addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    "FLAG_GRANT_READ_URI_PERMISSION" -> addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }

        // Dispatch via the chosen delivery method
        val result = when (delivery) {
            "activity", "broadcast", "service" -> {
                engine.fireAndForget(intent, delivery)
            }
            "ordered_broadcast" -> {
                engine.orderedBroadcast(intent, timeoutMs = timeoutMs)
            }
            "result_receiver" -> {
                engine.withResultReceiver(intent, timeoutMs = timeoutMs)
            }
            "activity_for_result" -> {
                engine.activityForResult(intent, timeoutMs = timeoutMs)
            }
            else -> {
                return ToolCallResult(
                    content = listOf(ContentBlock.text("Unknown delivery method: $delivery")),
                    isError = true
                )
            }
        }

        return if (result.isSuccess) {
            val responseText = buildString {
                appendLine("Intent sent successfully via $delivery")
                result.data?.let { bundle ->
                    appendLine("Result code: ${result.resultCode}")
                    for (key in bundle.keySet()) {
                        appendLine("  $key = ${bundle.get(key)}")
                    }
                }
            }
            ToolCallResult(content = listOf(ContentBlock.text(responseText)))
        } else {
            ToolCallResult(
                content = listOf(ContentBlock.text("Intent failed: ${result.error}")),
                isError = true
            )
        }
    }

    /**
     * android.query_intent — discover what apps can handle a given Intent
     */
    private fun registerQueryIntent(registry: ToolRegistry) {
        registry.register(McpToolDef(
            info = ToolInfo(
                name = "android.query_intent",
                description = "Discover which Android apps can handle a given Intent action and data. " +
                    "Returns a list of apps with their package names and labels.",
                inputSchema = jsonSchema {
                    string("action", "Intent action to query (e.g., android.intent.action.VIEW)")
                    string("data", "Intent data URI (e.g., https://example.com)", required = false)
                    string("type", "MIME type (e.g., text/plain)", required = false)
                }
            ),
            handler = { args ->
                val action = args["action"]?.jsonPrimitive?.contentOrNull ?: ""
                val data = args["data"]?.jsonPrimitive?.contentOrNull
                val type = args["type"]?.jsonPrimitive?.contentOrNull

                val intent = Intent(action).apply {
                    data?.let { this.data = Uri.parse(it) }
                    type?.let { this.type = it }
                }

                val pm = context.packageManager
                val handlers = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)

                if (handlers.isEmpty()) {
                    ToolCallResult(content = listOf(ContentBlock.text("No apps can handle this Intent")))
                } else {
                    val list = handlers.map { ri ->
                        val label = ri.loadLabel(pm).toString()
                        val pkg = ri.activityInfo.packageName
                        val cls = ri.activityInfo.name
                        "  $label ($pkg/$cls)"
                    }
                    ToolCallResult(content = listOf(ContentBlock.text(
                        "Apps that can handle this Intent (${handlers.size}):\n${list.joinToString("\n")}"
                    )))
                }
            }
        ))
    }
}
