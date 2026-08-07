package com.androidmcp.hub.discovery

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.androidmcp.core.protocol.ToolInfo
import com.androidmcp.core.protocol.ToolMetadata
import com.androidmcp.intent.McpIntentConstants
import com.androidmcp.intent.v1.CapAppToolDescriptor
import com.androidmcp.intent.v1.ICapAppCallback
import com.androidmcp.intent.v1.ICapAppService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Discovers installed CapApps.
 *
 * Binder Protocol v1 is preferred. Already-deployed Intent Protocol v0 CapApps remain visible as
 * a migration fallback, but a component discovered on v1 is never duplicated through v0.
 */
class IntentAppDiscovery(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val pendingCallbacks = ConcurrentHashMap<String, CompletableDeferred<List<ToolInfo>>>()

    private data class BinderToolCatalog(
        val tools: List<ToolInfo> = emptyList(),
        val metadata: Map<String, ToolMetadata> = emptyMap(),
    )

    fun discover(): List<DiscoveredApp> {
        val v1Services = findToolServices(
            action = McpIntentConstants.ACTION_BIND_V1,
            requireProtocolV1 = true,
        )
        val v0Services = findToolServices(
            action = McpIntentConstants.ACTION_EXECUTE,
            requireProtocolV1 = false,
        )

        val usedNamespaces = mutableSetOf<String>()
        val claimedComponents = mutableSetOf<ComponentName>()
        val apps = mutableListOf<DiscoveredApp>()

        fun uniqueNamespace(rawNamespace: String): String {
            var namespace = rawNamespace
            if (namespace in usedNamespaces) {
                var counter = 2
                while ("$namespace$counter" in usedNamespaces) counter++
                namespace = "$namespace$counter"
            }
            usedNamespaces.add(namespace)
            return namespace
        }

        for ((componentName, rawNamespace) in v1Services) {
            if (componentName.packageName == context.packageName) continue

            val catalog = fetchToolsFromBinderService(componentName)
            if (catalog.tools.isNotEmpty()) {
                claimedComponents.add(componentName)
                apps.add(DiscoveredApp(
                    packageName = componentName.packageName,
                    serviceComponent = componentName,
                    namespace = uniqueNamespace(rawNamespace),
                    tools = catalog.tools,
                    toolMetadata = catalog.metadata,
                    transport = CapAppTransport.BINDER_V1,
                ))
            }
        }

        for ((componentName, rawNamespace) in v0Services) {
            if (componentName.packageName == context.packageName) continue
            if (componentName in claimedComponents) continue

            val tools = fetchToolsFromLegacyService(componentName)
            if (tools.isNotEmpty()) {
                apps.add(DiscoveredApp(
                    packageName = componentName.packageName,
                    serviceComponent = componentName,
                    namespace = uniqueNamespace(rawNamespace),
                    tools = tools,
                    toolMetadata = emptyMap(),
                    transport = CapAppTransport.INTENT_V0,
                ))
            }
        }

        val v1Count = apps.count { it.transport == CapAppTransport.BINDER_V1 }
        val richMetadataCount = apps.sumOf { it.toolMetadata.size }
        Log.i(
            TAG,
            "Discovered ${apps.size} CapApps (${v1Count} Binder v1), " +
                "${apps.sumOf { it.tools.size }} tools, $richMetadataCount rich descriptors",
        )
        return apps
    }

    private fun findToolServices(
        action: String,
        requireProtocolV1: Boolean,
    ): List<Pair<ComponentName, String>> {
        val results = mutableListOf<Pair<ComponentName, String>>()
        val intent = Intent(action)
        val resolvedServices = context.packageManager.queryIntentServices(intent, PackageManager.GET_META_DATA)

        for (resolveInfo in resolvedServices) {
            val serviceInfo = resolveInfo.serviceInfo ?: continue
            val meta = serviceInfo.metaData ?: continue

            val isToolApp = meta.getBoolean(McpIntentConstants.META_TOOL_APP, false) ||
                meta.getString(McpIntentConstants.META_TOOL_APP) == "true"
            if (!isToolApp) continue

            val protocolVersion = meta.getInt(
                McpIntentConstants.META_PROTOCOL_VERSION,
                McpIntentConstants.CAPAPP_PROTOCOL_V0,
            )
            if (requireProtocolV1 && protocolVersion < McpIntentConstants.CAPAPP_PROTOCOL_V1) continue

            val namespace = meta.getString(McpIntentConstants.META_NAMESPACE)
                ?: serviceInfo.packageName.substringAfterLast('.')
            results.add(ComponentName(serviceInfo.packageName, serviceInfo.name) to namespace)
        }

        return results
    }

    /**
     * Bind to a v1 service, authenticate through Binder, and fetch its in-memory tool catalog.
     *
     * H2 CapApps return CapAppToolDescriptor v1. For rolling upgrades, the Hub also accepts the
     * earlier H1 Binder payload (List<ToolInfo>) and treats its missing rich metadata conservatively.
     */
    private fun fetchToolsFromBinderService(component: ComponentName): BinderToolCatalog {
        return try {
            runBlocking {
                withTimeoutOrNull(BINDER_DISCOVERY_TIMEOUT_MS) {
                    val serviceDeferred = CompletableDeferred<ICapAppService>()
                    val toolsDeferred = CompletableDeferred<BinderToolCatalog>()

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
                                    IllegalStateException("Service disconnected before binding: $component")
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

                    val bindIntent = Intent(McpIntentConstants.ACTION_BIND_V1).apply {
                        this.component = component
                    }
                    val bound = context.bindService(bindIntent, connection, Context.BIND_AUTO_CREATE)
                    if (!bound) return@withTimeoutOrNull BinderToolCatalog()

                    try {
                        val service = serviceDeferred.await()
                        service.descriptorJson

                        val callback = object : ICapAppCallback.Stub() {
                            override fun onTools(toolsJson: String?) {
                                if (toolsDeferred.isCompleted) return
                                toolsDeferred.complete(parseBinderToolCatalog(component, toolsJson))
                            }

                            override fun onResult(requestId: String?, resultJson: String?) = Unit

                            override fun onError(requestId: String?, code: Int, message: String?) {
                                if (!toolsDeferred.isCompleted) {
                                    Log.w(TAG, "Binder discovery error from $component: $code $message")
                                    toolsDeferred.complete(BinderToolCatalog())
                                }
                            }
                        }

                        service.listTools(callback)
                        toolsDeferred.await()
                    } finally {
                        try { context.unbindService(connection) } catch (_: Exception) { }
                    }
                } ?: BinderToolCatalog()
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Binder CapApp rejected Hub identity for ${component.packageName}: ${e.message}")
            BinderToolCatalog()
        } catch (e: Exception) {
            Log.w(TAG, "Failed Binder discovery for ${component.packageName}: ${e.message}")
            BinderToolCatalog()
        }
    }

    private fun parseBinderToolCatalog(component: ComponentName, toolsJson: String?): BinderToolCatalog {
        if (toolsJson.isNullOrBlank()) return BinderToolCatalog()

        try {
            val descriptors = json.decodeFromString(
                ListSerializer(CapAppToolDescriptor.serializer()),
                toolsJson,
            )
            return BinderToolCatalog(
                tools = descriptors.map { it.tool },
                metadata = descriptors.associate { it.tool.name to it.metadata },
            )
        } catch (_: Exception) {
            // Rolling-upgrade compatibility with the H1 Binder payload.
        }

        return try {
            val tools = json.decodeFromString(ListSerializer(ToolInfo.serializer()), toolsJson)
            Log.i(
                TAG,
                "${component.packageName} uses H1 Binder descriptors; policy metadata defaults to unknown",
            )
            BinderToolCatalog(tools = tools)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Binder tools from ${component.packageName}", e)
            BinderToolCatalog()
        }
    }

    /** Send legacy LIST_TOOLS to a v0 service and await its broadcast reply. */
    private fun fetchToolsFromLegacyService(component: ComponentName): List<ToolInfo> {
        return try {
            runBlocking {
                withTimeoutOrNull(LEGACY_DISCOVERY_TIMEOUT_MS) {
                    val callbackId = UUID.randomUUID().toString()
                    val deferred = CompletableDeferred<List<ToolInfo>>()
                    pendingCallbacks[callbackId] = deferred

                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(ctx: Context, intent: Intent) {
                            val id = intent.getStringExtra(McpIntentConstants.EXTRA_CALLBACK_ID)
                            if (id != callbackId) return

                            val toolsJson = intent.getStringExtra(McpIntentConstants.RESULT_KEY_TOOL_DEFINITIONS)
                            val tools = if (toolsJson != null) {
                                try {
                                    json.decodeFromString<List<ToolInfo>>(toolsJson)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to parse legacy tools from ${component.packageName}", e)
                                    emptyList()
                                }
                            } else emptyList()

                            pendingCallbacks.remove(callbackId)?.complete(tools)
                            try { context.unregisterReceiver(this) } catch (_: Exception) { }
                        }
                    }

                    val filter = IntentFilter(McpIntentConstants.ACTION_TOOL_RESULT)
                    if (Build.VERSION.SDK_INT >= 34) {
                        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                    } else {
                        context.registerReceiver(receiver, filter)
                    }

                    val intent = Intent(McpIntentConstants.ACTION_LIST_TOOLS).apply {
                        this.component = component
                        putExtra(McpIntentConstants.EXTRA_CALLBACK_ID, callbackId)
                        putExtra(McpIntentConstants.EXTRA_REPLY_TO, context.packageName)
                    }
                    context.startService(intent)

                    val result = deferred.await()
                    try { context.unregisterReceiver(receiver) } catch (_: Exception) { }
                    result
                } ?: emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed legacy discovery from ${component.packageName}: ${e.message}")
            emptyList()
        }
    }

    companion object {
        private const val TAG = "MCP-IntentDiscovery"
        private const val BINDER_DISCOVERY_TIMEOUT_MS = 5_000L
        private const val LEGACY_DISCOVERY_TIMEOUT_MS = 5_000L
    }
}
