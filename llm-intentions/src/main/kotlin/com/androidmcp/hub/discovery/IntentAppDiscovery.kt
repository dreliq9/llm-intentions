package com.androidmcp.hub.discovery

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.androidmcp.core.protocol.ToolInfo
import com.androidmcp.intent.McpIntentConstants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Discovers apps that implement the MCP Intent protocol.
 *
 * Scans for Services with ACTION_EXECUTE intent-filter and META_TOOL_APP metadata.
 * Fetches tool catalogs by sending LIST_TOOLS and listening for broadcast replies.
 */
class IntentAppDiscovery(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val pendingCallbacks = ConcurrentHashMap<String, CompletableDeferred<List<ToolInfo>>>()

    fun discover(): List<DiscoveredApp> {
        val services = findToolServices()
        val usedNamespaces = mutableSetOf<String>()
        val apps = mutableListOf<DiscoveredApp>()

        for ((componentName, rawNamespace) in services) {
            if (componentName.packageName == context.packageName) continue

            var namespace = rawNamespace
            if (namespace in usedNamespaces) {
                var counter = 2
                while ("$namespace$counter" in usedNamespaces) counter++
                namespace = "$namespace$counter"
            }
            usedNamespaces.add(namespace)

            val tools = fetchToolsFromService(componentName)

            if (tools.isNotEmpty()) {
                apps.add(DiscoveredApp(
                    packageName = componentName.packageName,
                    serviceComponent = componentName,
                    namespace = namespace,
                    tools = tools
                ))
            }
        }

        Log.i(TAG, "Discovered ${apps.size} tool apps with ${apps.sumOf { it.tools.size }} total tools")
        return apps
    }

    private fun findToolServices(): List<Pair<ComponentName, String>> {
        val results = mutableListOf<Pair<ComponentName, String>>()
        val pm = context.packageManager

        val intent = Intent(McpIntentConstants.ACTION_EXECUTE)
        val resolvedServices = pm.queryIntentServices(intent, PackageManager.GET_META_DATA)

        for (resolveInfo in resolvedServices) {
            val serviceInfo = resolveInfo.serviceInfo ?: continue
            val meta = serviceInfo.metaData ?: continue

            val isToolApp = meta.getBoolean(McpIntentConstants.META_TOOL_APP, false) ||
                    meta.getString(McpIntentConstants.META_TOOL_APP) == "true"

            if (isToolApp) {
                val namespace = meta.getString(McpIntentConstants.META_NAMESPACE)
                    ?: serviceInfo.packageName.substringAfterLast('.')
                val component = ComponentName(serviceInfo.packageName, serviceInfo.name)
                results.add(component to namespace)
            }
        }

        return results
    }

    /**
     * Send LIST_TOOLS to a service and await the broadcast reply.
     */
    private fun fetchToolsFromService(component: ComponentName): List<ToolInfo> {
        return try {
            runBlocking {
                withTimeoutOrNull(5_000) {
                    val callbackId = UUID.randomUUID().toString()
                    val deferred = CompletableDeferred<List<ToolInfo>>()
                    pendingCallbacks[callbackId] = deferred

                    // Register receiver for the reply
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(ctx: Context, intent: Intent) {
                            val id = intent.getStringExtra(McpIntentConstants.EXTRA_CALLBACK_ID)
                            if (id != callbackId) return

                            val toolsJson = intent.getStringExtra(McpIntentConstants.RESULT_KEY_TOOL_DEFINITIONS)
                            val tools = if (toolsJson != null) {
                                try {
                                    json.decodeFromString<List<ToolInfo>>(toolsJson)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to parse tools from ${component.packageName}", e)
                                    emptyList()
                                }
                            } else emptyList()

                            pendingCallbacks.remove(callbackId)?.complete(tools)
                            try { context.unregisterReceiver(this) } catch (_: Exception) {}
                        }
                    }

                    val filter = IntentFilter(McpIntentConstants.ACTION_TOOL_RESULT)
                    if (Build.VERSION.SDK_INT >= 34) {
                        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                    } else {
                        context.registerReceiver(receiver, filter)
                    }

                    // Send LIST_TOOLS to the service
                    val intent = Intent(McpIntentConstants.ACTION_LIST_TOOLS).apply {
                        this.component = component
                        putExtra(McpIntentConstants.EXTRA_CALLBACK_ID, callbackId)
                        putExtra(McpIntentConstants.EXTRA_REPLY_TO, context.packageName)
                    }
                    context.startService(intent)

                    val result = deferred.await()
                    try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
                    result
                } ?: emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch tools from ${component.packageName}: ${e.message}")
            emptyList()
        }
    }

    companion object {
        private const val TAG = "MCP-IntentDiscovery"
    }
}
