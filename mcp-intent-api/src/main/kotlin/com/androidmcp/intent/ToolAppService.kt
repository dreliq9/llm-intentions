package com.androidmcp.intent

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.androidmcp.core.protocol.ContentBlock
import com.androidmcp.core.protocol.ToolCallResult
import com.androidmcp.core.protocol.ToolInfo
import com.androidmcp.core.registry.ToolRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/**
 * Base Service that tool apps extend to participate in the MCP Intent protocol.
 *
 * Subclasses override [onCreateTools] to register their tools.
 * The service handles incoming EXECUTE and LIST_TOOLS intents automatically,
 * dispatching tool calls and sending results back to the Hub via broadcast.
 *
 * Communication:
 *   Hub → App: startService() with ACTION_EXECUTE / ACTION_LIST_TOOLS
 *   App ��� Hub: sendBroadcast() with ACTION_TOOL_RESULT + callback_id
 *
 * Manifest declaration:
 * ```xml
 * <service android:name=".MyToolService" android:exported="true">
 *     <intent-filter>
 *         <action android:name="com.androidmcp.tool.EXECUTE" />
 *         <action android:name="com.androidmcp.tool.LIST_TOOLS" />
 *     </intent-filter>
 *     <meta-data android:name="com.androidmcp.TOOL_APP" android:value="true" />
 *     <meta-data android:name="com.androidmcp.NAMESPACE" android:value="myapp" />
 * </service>
 * ```
 */
abstract class ToolAppService : Service() {

    private val registry = ToolRegistry()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        onCreateTools(registry)
        Log.i(TAG, "ToolAppService started with ${registry.size()} tools")
    }

    abstract fun onCreateTools(registry: ToolRegistry)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        when (intent.action) {
            McpIntentConstants.ACTION_EXECUTE -> handleExecute(intent)
            McpIntentConstants.ACTION_LIST_TOOLS -> handleListTools(intent)
            else -> Log.w(TAG, "Unknown action: ${intent.action}")
        }

        return START_NOT_STICKY
    }

    private fun handleExecute(intent: Intent) {
        val toolName = intent.getStringExtra(McpIntentConstants.EXTRA_TOOL_NAME)
        val argsJson = intent.getStringExtra(McpIntentConstants.EXTRA_ARGUMENTS) ?: "{}"
        val callbackId = intent.getStringExtra(McpIntentConstants.EXTRA_CALLBACK_ID)
        val replyTo = intent.getStringExtra(McpIntentConstants.EXTRA_REPLY_TO)

        if (toolName == null || callbackId == null || replyTo == null) {
            Log.w(TAG, "EXECUTE missing required extras (tool=$toolName, callback=$callbackId, replyTo=$replyTo)")
            return
        }

        val toolDef = registry.get(toolName)
        if (toolDef == null) {
            sendResult(replyTo, callbackId, isError = true,
                data = json.encodeToString(ToolCallResult.serializer(),
                    ToolCallResult(content = listOf(ContentBlock.text("Tool not found: $toolName")), isError = true)))
            return
        }

        scope.launch {
            try {
                val args = json.parseToJsonElement(argsJson).jsonObject
                val result = toolDef.handler(args)
                sendResult(replyTo, callbackId,
                    isError = result.isError,
                    data = json.encodeToString(ToolCallResult.serializer(), result))
            } catch (e: Exception) {
                Log.e(TAG, "Tool execution failed: $toolName", e)
                sendResult(replyTo, callbackId, isError = true,
                    data = json.encodeToString(ToolCallResult.serializer(),
                        ToolCallResult(content = listOf(ContentBlock.text("Error: ${e.message}")), isError = true)))
            }
        }
    }

    private fun handleListTools(intent: Intent) {
        val callbackId = intent.getStringExtra(McpIntentConstants.EXTRA_CALLBACK_ID)
        val replyTo = intent.getStringExtra(McpIntentConstants.EXTRA_REPLY_TO)

        if (callbackId == null || replyTo == null) {
            Log.w(TAG, "LIST_TOOLS missing callback_id or reply_to")
            return
        }

        val tools = registry.list()
        val toolsJson = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(ToolInfo.serializer()),
            tools
        )

        val reply = Intent(McpIntentConstants.ACTION_TOOL_RESULT).apply {
            setPackage(replyTo)
            putExtra(McpIntentConstants.EXTRA_CALLBACK_ID, callbackId)
            putExtra(McpIntentConstants.RESULT_KEY_TOOL_DEFINITIONS, toolsJson)
        }
        sendBroadcast(reply)
        Log.i(TAG, "LIST_TOOLS: sent ${tools.size} tool definitions to $replyTo")
    }

    /**
     * Send a tool execution result back to the Hub via broadcast.
     */
    private fun sendResult(replyTo: String, callbackId: String, isError: Boolean, data: String) {
        val reply = Intent(McpIntentConstants.ACTION_TOOL_RESULT).apply {
            setPackage(replyTo)
            putExtra(McpIntentConstants.EXTRA_CALLBACK_ID, callbackId)
            putExtra(McpIntentConstants.RESULT_KEY_DATA, data)
            putExtra(McpIntentConstants.RESULT_KEY_IS_ERROR, isError)
        }
        sendBroadcast(reply)
        Log.i(TAG, "Result sent for callback $callbackId to $replyTo (error=$isError)")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        Log.i(TAG, "ToolAppService destroyed")
    }

    companion object {
        private const val TAG = "MCP-ToolApp"
    }
}

/**
 * Convenience extension: register a tool that returns a text string.
 * Wraps the string result in ToolCallResult automatically.
 */
fun ToolRegistry.textTool(
    name: String,
    description: String,
    params: JsonObject,
    handler: suspend (JsonObject) -> String
) {
    register(com.androidmcp.core.registry.McpToolDef(
        info = com.androidmcp.core.protocol.ToolInfo(name = name, description = description, inputSchema = params),
        handler = { args ->
            com.androidmcp.core.protocol.ToolCallResult(
                content = listOf(com.androidmcp.core.protocol.ContentBlock.text(handler(args)))
            )
        }
    ))
}
