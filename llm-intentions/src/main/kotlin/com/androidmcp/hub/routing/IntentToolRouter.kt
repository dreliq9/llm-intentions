package com.androidmcp.hub.routing

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import com.androidmcp.core.protocol.ContentBlock
import com.androidmcp.core.protocol.ToolCallResult
import com.androidmcp.core.protocol.ToolInfo
import com.androidmcp.core.registry.McpToolDef
import com.androidmcp.core.registry.ToolRegistry
import com.androidmcp.hub.discovery.CapAppTransport
import com.androidmcp.hub.discovery.DiscoveredApp
import com.androidmcp.intent.McpIntentConstants
import com.androidmcp.intent.v1.ICapAppCallback
import com.androidmcp.intent.v1.ICapAppService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * Builds Hub proxy handlers for installed CapApps.
 *
 * Binder v1 is preferred and returns results through an authenticated Binder callback. The v0
 * Intent/broadcast path is retained only for CapApps that have not migrated yet.
 */
class IntentToolRouter(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun registerProxyTools(registry: ToolRegistry, apps: List<DiscoveredApp>) {
        for (app in apps) {
            val component = app.serviceComponent

            for (tool in app.tools) {
                val namespacedName = "${app.namespace}.${tool.name}"
                val proxyHandler = when (app.transport) {
                    CapAppTransport.BINDER_V1 -> createBinderProxyHandler(
                        component = component,
                        originalToolName = tool.name,
                    )
                    CapAppTransport.INTENT_V0 -> createLegacyProxyHandler(
                        packageName = app.packageName,
                        serviceName = component.className,
                        originalToolName = tool.name,
                    )
                }

                registry.register(McpToolDef(
                    info = ToolInfo(
                        name = namespacedName,
                        description = "[${app.namespace}] ${tool.description}",
                        inputSchema = tool.inputSchema,
                        title = tool.title,
                        outputSchema = tool.outputSchema,
                        annotations = tool.annotations,
                    ),
                    handler = proxyHandler,
                ))
            }
        }
    }

    private fun createBinderProxyHandler(
        component: ComponentName,
        originalToolName: String,
    ): suspend (JsonObject) -> ToolCallResult {
        return handler@{ args ->
            val requestId = UUID.randomUUID().toString()
            val serviceDeferred = CompletableDeferred<ICapAppService>()
            val resultDeferred = CompletableDeferred<ToolCallResult>()

            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val service = binder?.let(ICapAppService.Stub::asInterface)
                    if (service != null) serviceDeferred.complete(service)
                    else serviceDeferred.completeExceptionally(
                        IllegalStateException("Null Binder interface from $component")
                    )
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    if (!serviceDeferred.isCompleted) {
                        serviceDeferred.completeExceptionally(
                            IllegalStateException("CapApp disconnected before binding: $component")
                        )
                    }
                }

                override fun onNullBinding(name: ComponentName?) {
                    if (!serviceDeferred.isCompleted) {
                        serviceDeferred.completeExceptionally(
                            IllegalStateException("Null binding from $component")
                        )
                    }
                }

                override fun onBindingDied(name: ComponentName?) {
                    if (!serviceDeferred.isCompleted) {
                        serviceDeferred.completeExceptionally(
                            IllegalStateException("Binding died for $component")
                        )
                    }
                }
            }

            try {
                val bindIntent = Intent(McpIntentConstants.ACTION_BIND_V1).apply {
                    this.component = component
                }
                if (!context.bindService(bindIntent, connection, Context.BIND_AUTO_CREATE)) {
                    return@handler ToolCallResult(
                        content = listOf(ContentBlock.text("Unable to bind CapApp: ${component.packageName}")),
                        isError = true,
                    )
                }

                val service = withTimeout(BINDER_CONNECT_TIMEOUT_MS) { serviceDeferred.await() }
                val callback = object : ICapAppCallback.Stub() {
                    override fun onTools(toolsJson: String?) = Unit

                    override fun onResult(callbackRequestId: String?, resultJson: String?) {
                        if (callbackRequestId != requestId || resultDeferred.isCompleted) return
                        if (resultJson == null) {
                            resultDeferred.complete(ToolCallResult(
                                content = listOf(ContentBlock.text("CapApp returned no result data")),
                                isError = true,
                            ))
                            return
                        }

                        try {
                            resultDeferred.complete(json.decodeFromString<ToolCallResult>(resultJson))
                        } catch (_: Exception) {
                            resultDeferred.complete(ToolCallResult(
                                content = listOf(ContentBlock.text(resultJson)),
                                isError = true,
                            ))
                        }
                    }

                    override fun onError(callbackRequestId: String?, code: Int, message: String?) {
                        if (callbackRequestId != requestId || resultDeferred.isCompleted) return
                        resultDeferred.complete(ToolCallResult(
                            content = listOf(ContentBlock.text(
                                "CapApp error $code calling ${component.packageName}.$originalToolName: ${message ?: "unknown error"}"
                            )),
                            isError = true,
                        ))
                    }
                }

                service.execute(
                    requestId,
                    originalToolName,
                    json.encodeToString(JsonObject.serializer(), args),
                    callback,
                )

                withTimeout(TOOL_TIMEOUT_MS) { resultDeferred.await() }
            } catch (e: Exception) {
                ToolCallResult(
                    content = listOf(ContentBlock.text(
                        "Error calling ${component.packageName}.$originalToolName over Binder: ${e.message}"
                    )),
                    isError = true,
                )
            } finally {
                try { context.unbindService(connection) } catch (_: Exception) { }
            }
        }
    }

    private fun createLegacyProxyHandler(
        packageName: String,
        serviceName: String,
        originalToolName: String,
    ): suspend (JsonObject) -> ToolCallResult {
        return { args ->
            try {
                val callbackId = UUID.randomUUID().toString()
                val deferred = CompletableDeferred<ToolCallResult>()

                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        val id = intent.getStringExtra(McpIntentConstants.EXTRA_CALLBACK_ID)
                        if (id != callbackId) return

                        val resultJson = intent.getStringExtra(McpIntentConstants.RESULT_KEY_DATA)
                        if (resultJson != null) {
                            try {
                                deferred.complete(json.decodeFromString<ToolCallResult>(resultJson))
                            } catch (_: Exception) {
                                deferred.complete(ToolCallResult(
                                    content = listOf(ContentBlock.text(resultJson)),
                                    isError = intent.getBooleanExtra(
                                        McpIntentConstants.RESULT_KEY_IS_ERROR,
                                        false,
                                    ),
                                ))
                            }
                        } else {
                            deferred.complete(ToolCallResult(
                                content = listOf(ContentBlock.text("No result data from $packageName")),
                                isError = true,
                            ))
                        }

                        try { context.unregisterReceiver(this) } catch (_: Exception) { }
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
                    putExtra(
                        McpIntentConstants.EXTRA_ARGUMENTS,
                        json.encodeToString(JsonObject.serializer(), args),
                    )
                    putExtra(McpIntentConstants.EXTRA_CALLBACK_ID, callbackId)
                    putExtra(McpIntentConstants.EXTRA_REPLY_TO, context.packageName)
                }

                context.startService(intent)

                try {
                    withTimeout(TOOL_TIMEOUT_MS) { deferred.await() }
                } finally {
                    try { context.unregisterReceiver(receiver) } catch (_: Exception) { }
                }
            } catch (e: Exception) {
                ToolCallResult(
                    content = listOf(ContentBlock.text(
                        "Error calling $packageName.$originalToolName: ${e.message}"
                    )),
                    isError = true,
                )
            }
        }
    }

    companion object {
        private const val BINDER_CONNECT_TIMEOUT_MS = 5_000L
        private const val TOOL_TIMEOUT_MS = 60_000L
    }
}
