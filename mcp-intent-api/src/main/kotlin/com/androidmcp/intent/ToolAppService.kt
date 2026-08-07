package com.androidmcp.intent

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.androidmcp.core.protocol.ContentBlock
import com.androidmcp.core.protocol.Envelope
import com.androidmcp.core.protocol.EnvelopeStatus
import com.androidmcp.core.protocol.ToolCallResult
import com.androidmcp.core.protocol.ToolInfo
import com.androidmcp.core.protocol.ToolMetadata
import com.androidmcp.core.registry.ToolRegistry
import com.androidmcp.intent.v1.CapAppCallerTrustPolicy
import com.androidmcp.intent.v1.CapAppToolDescriptor
import com.androidmcp.intent.v1.ICapAppCallback
import com.androidmcp.intent.v1.ICapAppService
import com.androidmcp.intent.v1.OfficialHubSameSignerTrustPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Base Service that tool apps extend to participate in CapApp local IPC.
 *
 * Protocol v1 is the secure default: an authenticated bound Binder API. Protocol v0 (started
 * service + broadcast reply) remains only as an explicit migration option and must be enabled by
 * a subclass that knowingly accepts its weaker trust model.
 */
abstract class ToolAppService : Service() {

    private val registry = ToolRegistry()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Override with an explicit trust-store policy when third-party Hub pairing is supported. */
    protected open val callerTrustPolicy: CapAppCallerTrustPolicy = OfficialHubSameSignerTrustPolicy

    /** Fail closed: legacy unauthenticated Intent/broadcast execution is opt-in only. */
    protected open val legacyIntentProtocolEnabled: Boolean = false

    override fun onCreate() {
        super.onCreate()
        onCreateTools(registry)
        Log.i(TAG, "ToolAppService started with ${registry.size()} tools")
    }

    abstract fun onCreateTools(registry: ToolRegistry)

    private val binder = object : ICapAppService.Stub() {
        override fun getDescriptorJson(): String {
            enforceTrustedBinderCaller()
            return buildJsonObject {
                put("protocolVersion", McpIntentConstants.CAPAPP_PROTOCOL_V1)
                put("toolDescriptorVersion", TOOL_DESCRIPTOR_VERSION)
                put("transport", "binder")
                put("toolCount", registry.size())
            }.toString()
        }

        override fun listTools(callback: ICapAppCallback?) {
            enforceTrustedBinderCaller()
            requireNotNull(callback) { "callback is required" }

            val descriptors = registry.definitions().map { definition ->
                CapAppToolDescriptor(
                    tool = definition.info,
                    metadata = definition.metadata ?: ToolMetadata(),
                )
            }
            val toolsJson = json.encodeToString(
                ListSerializer(CapAppToolDescriptor.serializer()),
                descriptors,
            )
            callback.onTools(toolsJson)
        }

        override fun execute(
            requestId: String?,
            toolName: String?,
            argumentsJson: String?,
            callback: ICapAppCallback?,
        ) {
            enforceTrustedBinderCaller()

            require(!requestId.isNullOrBlank()) { "requestId is required" }
            require(!toolName.isNullOrBlank()) { "toolName is required" }
            requireNotNull(callback) { "callback is required" }

            val toolDef = registry.get(toolName)
            if (toolDef == null) {
                callback.onError(requestId, ERROR_TOOL_NOT_FOUND, "Tool not found: $toolName")
                return
            }

            scope.launch {
                try {
                    val args = json.parseToJsonElement(argumentsJson ?: "{}").let { element ->
                        element as? JsonObject ?: throw IllegalArgumentException("arguments must be a JSON object")
                    }
                    val result = toolDef.handler(args)
                    callback.onResult(
                        requestId,
                        json.encodeToString(ToolCallResult.serializer(), result),
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Binder tool execution failed: $toolName", e)
                    val env = Envelope.fromException(
                        toolName = toolName,
                        metadata = toolDef.metadata,
                        exception = e,
                    )
                    val result = ToolCallResult(
                        content = listOf(ContentBlock.text(env.renderText())),
                        isError = env.status == EnvelopeStatus.FAIL,
                    )
                    try {
                        callback.onResult(
                            requestId,
                            json.encodeToString(ToolCallResult.serializer(), result),
                        )
                    } catch (callbackError: Exception) {
                        Log.w(TAG, "Unable to return Binder result for $requestId", callbackError)
                    }
                }
            }
        }
    }

    private fun enforceTrustedBinderCaller() {
        val callingUid = Binder.getCallingUid()
        if (callerTrustPolicy.isTrusted(this, callingUid)) return

        val packages = packageManager.getPackagesForUid(callingUid)?.joinToString(",") ?: "unknown"
        Log.w(TAG, "Rejected untrusted Binder caller uid=$callingUid packages=$packages")
        throw SecurityException("Caller is not trusted for CapApp Protocol v1")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return if (intent?.action == McpIntentConstants.ACTION_BIND_V1) binder else null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        if (!legacyIntentProtocolEnabled) {
            Log.w(TAG, "Rejected legacy CapApp v0 invocation: ${intent.action}")
            return START_NOT_STICKY
        }

        when (intent.action) {
            McpIntentConstants.ACTION_EXECUTE -> handleLegacyExecute(intent)
            McpIntentConstants.ACTION_LIST_TOOLS -> handleLegacyListTools(intent)
            else -> Log.w(TAG, "Unknown legacy action: ${intent.action}")
        }

        return START_NOT_STICKY
    }

    private fun handleLegacyExecute(intent: Intent) {
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
            val env = Envelope.fail(
                summary = "Tool not found: $toolName",
                hint = "Check tools/list for available names in this CapApp's namespace.",
            )
            sendLegacyEnvelope(replyTo, callbackId, env)
            return
        }

        scope.launch {
            try {
                val args = json.parseToJsonElement(argsJson).let { element ->
                    element as? JsonObject ?: throw IllegalArgumentException("arguments must be a JSON object")
                }
                val result = toolDef.handler(args)
                sendLegacyResult(
                    replyTo = replyTo,
                    callbackId = callbackId,
                    isError = result.isError,
                    data = json.encodeToString(ToolCallResult.serializer(), result),
                )
            } catch (e: Exception) {
                Log.e(TAG, "Tool execution failed: $toolName", e)
                val env = Envelope.fromException(
                    toolName = toolName,
                    metadata = toolDef.metadata,
                    exception = e,
                )
                sendLegacyEnvelope(replyTo, callbackId, env)
            }
        }
    }

    private fun sendLegacyEnvelope(replyTo: String, callbackId: String, env: Envelope) {
        val result = ToolCallResult(
            content = listOf(ContentBlock.text(env.renderText())),
            isError = env.status == EnvelopeStatus.FAIL,
        )
        sendLegacyResult(
            replyTo = replyTo,
            callbackId = callbackId,
            isError = result.isError,
            data = json.encodeToString(ToolCallResult.serializer(), result),
        )
    }

    /** Legacy v0 intentionally remains ToolInfo-only; rich policy metadata is a v1 feature. */
    private fun handleLegacyListTools(intent: Intent) {
        val callbackId = intent.getStringExtra(McpIntentConstants.EXTRA_CALLBACK_ID)
        val replyTo = intent.getStringExtra(McpIntentConstants.EXTRA_REPLY_TO)

        if (callbackId == null || replyTo == null) {
            Log.w(TAG, "LIST_TOOLS missing callback_id or reply_to")
            return
        }

        val tools = registry.list()
        val toolsJson = json.encodeToString(ListSerializer(ToolInfo.serializer()), tools)

        val reply = Intent(McpIntentConstants.ACTION_TOOL_RESULT).apply {
            setPackage(replyTo)
            putExtra(McpIntentConstants.EXTRA_CALLBACK_ID, callbackId)
            putExtra(McpIntentConstants.RESULT_KEY_TOOL_DEFINITIONS, toolsJson)
        }
        sendBroadcast(reply)
        Log.i(TAG, "LIST_TOOLS: sent ${tools.size} legacy tool definitions to $replyTo")
    }

    private fun sendLegacyResult(replyTo: String, callbackId: String, isError: Boolean, data: String) {
        val reply = Intent(McpIntentConstants.ACTION_TOOL_RESULT).apply {
            setPackage(replyTo)
            putExtra(McpIntentConstants.EXTRA_CALLBACK_ID, callbackId)
            putExtra(McpIntentConstants.RESULT_KEY_DATA, data)
            putExtra(McpIntentConstants.RESULT_KEY_IS_ERROR, isError)
        }
        sendBroadcast(reply)
        Log.i(TAG, "Legacy result sent for callback $callbackId to $replyTo (error=$isError)")
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        Log.i(TAG, "ToolAppService destroyed")
    }

    companion object {
        private const val TAG = "MCP-ToolApp"
        private const val ERROR_TOOL_NOT_FOUND = 404
        private const val TOOL_DESCRIPTOR_VERSION = 1
    }
}
