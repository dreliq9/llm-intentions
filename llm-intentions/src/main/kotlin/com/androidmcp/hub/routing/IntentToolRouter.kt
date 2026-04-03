package com.androidmcp.hub.routing

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.androidmcp.core.protocol.*
import com.androidmcp.core.registry.McpToolDef
import com.androidmcp.core.registry.ToolRegistry
import com.androidmcp.hub.discovery.DiscoveredApp
import com.androidmcp.intent.McpIntentConstants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * Builds proxy tool handlers that forward MCP calls to app Services via Intents.
 * Results come back as broadcast replies with matching callback IDs.
 */
class IntentToolRouter(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun registerProxyTools(registry: ToolRegistry, apps: List<DiscoveredApp>) {
        for (app in apps) {
            val component = app.serviceComponent

            for (tool in app.tools) {
                val namespacedName = "${app.namespace}.${tool.name}"
                val proxyHandler = createProxyHandler(
                    packageName = app.packageName,
                    serviceName = component.className,
                    originalToolName = tool.name
                )

                registry.register(McpToolDef(
                    info = ToolInfo(
                        name = namespacedName,
                        description = "[${app.namespace}] ${tool.description}",
                        inputSchema = tool.inputSchema
                    ),
                    handler = proxyHandler
                ))
            }
        }
    }

    private fun createProxyHandler(
        packageName: String,
        serviceName: String,
        originalToolName: String
    ): suspend (JsonObject) -> ToolCallResult {
        return { args ->
            try {
                val callbackId = UUID.randomUUID().toString()
                val deferred = CompletableDeferred<ToolCallResult>()

                // Register receiver for the broadcast reply
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        val id = intent.getStringExtra(McpIntentConstants.EXTRA_CALLBACK_ID)
                        if (id != callbackId) return

                        val resultJson = intent.getStringExtra(McpIntentConstants.RESULT_KEY_DATA)
                        if (resultJson != null) {
                            try {
                                val result = json.decodeFromJsonElement<ToolCallResult>(
                                    json.parseToJsonElement(resultJson)
                                )
                                deferred.complete(result)
                            } catch (_: Exception) {
                                deferred.complete(ToolCallResult(
                                    content = listOf(ContentBlock.text(resultJson)),
                                    isError = intent.getBooleanExtra(McpIntentConstants.RESULT_KEY_IS_ERROR, false)
                                ))
                            }
                        } else {
                            deferred.complete(ToolCallResult(
                                content = listOf(ContentBlock.text("No result data from $packageName")),
                                isError = true
                            ))
                        }

                        try { context.unregisterReceiver(this) } catch (_: Exception) {}
                    }
                }

                val filter = IntentFilter(McpIntentConstants.ACTION_TOOL_RESULT)
                if (Build.VERSION.SDK_INT >= 34) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    context.registerReceiver(receiver, filter)
                }

                val intent = Intent(McpIntentConstants.ACTION_EXECUTE).apply {
                    component = ComponentName(packageName, serviceName)
                    putExtra(McpIntentConstants.EXTRA_TOOL_NAME, originalToolName)
                    putExtra(McpIntentConstants.EXTRA_ARGUMENTS, json.encodeToString(JsonObject.serializer(), args))
                    putExtra(McpIntentConstants.EXTRA_CALLBACK_ID, callbackId)
                    putExtra(McpIntentConstants.EXTRA_REPLY_TO, context.packageName)
                }

                context.startService(intent)

                try {
                    withTimeout(60_000) { deferred.await() }
                } finally {
                    try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                ToolCallResult(
                    content = listOf(ContentBlock.text("Error calling $packageName.$originalToolName: ${e.message}")),
                    isError = true
                )
            }
        }
    }
}
