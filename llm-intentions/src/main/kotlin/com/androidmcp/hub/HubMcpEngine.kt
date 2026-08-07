package com.androidmcp.hub

import android.content.Context
import android.content.IntentFilter
import android.os.Build
import com.androidmcp.core.McpDispatcher
import com.androidmcp.core.protocol.Implementation
import com.androidmcp.core.registry.ToolRegistry
import com.androidmcp.hub.discovery.DiscoveredApp
import com.androidmcp.hub.discovery.IntentAppDiscovery
import com.androidmcp.hub.discovery.PackageChangeReceiver
import com.androidmcp.hub.health.IntentHealthMonitor
import com.androidmcp.hub.inbox.InboxManager
import com.androidmcp.hub.inbox.InboxTools
import com.androidmcp.hub.intents.FileShareTools
import com.androidmcp.hub.intents.GenericIntentTools
import com.androidmcp.hub.intents.IntentEngine
import com.androidmcp.hub.intents.IntentScanner
import com.androidmcp.hub.intents.IntentToolDefinitions
import com.androidmcp.hub.meta.HubMetaTools
import com.androidmcp.hub.routing.IntentToolRouter
import com.androidmcp.hub.security.HubAuditLog
import com.androidmcp.hub.security.HubToolAuthorizer
import com.androidmcp.hub.system.DeviceControlTools
import com.androidmcp.hub.system.NotificationTools
import com.androidmcp.hub.system.SystemToolDefinitions
import java.util.concurrent.Executors

/** Core Hub orchestration and the single registry/dispatcher source of truth. */
class HubMcpEngine(private val context: Context) {

    val registry = ToolRegistry()
    val discovery = IntentAppDiscovery(context)
    val intentEngine = IntentEngine(context)
    val healthMonitor = IntentHealthMonitor(context)
    val auditLog = HubAuditLog(context.applicationContext)
    val toolAuthorizer = HubToolAuthorizer(context, auditLog)

    private val router = IntentToolRouter(context)
    private val refreshExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mcp-hub-refresh").apply { isDaemon = true }
    }
    private var packageReceiver: PackageChangeReceiver? = null

    @Volatile
    var discoveredApps: List<DiscoveredApp> = emptyList()
        private set

    lateinit var dispatcher: McpDispatcher
        private set

    fun refresh() {
        populateRegistry()
    }

    fun initialize() {
        populateRegistry()
        registerPackageReceiver()

        dispatcher = McpDispatcher(
            serverInfo = Implementation("LLM Intentions", "0.6.0"),
            toolRegistry = registry,
            toolAuthorizer = toolAuthorizer,
            instructions = buildInstructions(),
        )
    }

    @Synchronized
    private fun populateRegistry() {
        val newApps = discovery.discover()

        registry.clear()
        discoveredApps = newApps

        router.registerProxyTools(registry, newApps)

        IntentToolDefinitions(context).registerAll(registry)
        IntentScanner(context).registerDiscovered(registry)
        FileShareTools(context).registerAll(registry)
        GenericIntentTools(context, intentEngine).registerAll(registry)

        SystemToolDefinitions(context).registerAll(registry)
        DeviceControlTools(context).registerAll(registry)
        NotificationTools(context).registerAll(registry)

        HubMetaTools(
            healthMonitor = healthMonitor,
            getDiscoveredApps = { discoveredApps },
            refreshCallback = {
                val prevCount = discoveredApps.sumOf { it.tools.size }
                populateRegistry()
                HubMetaTools.RefreshResult(
                    previousCount = prevCount,
                    newCount = discoveredApps.sumOf { it.tools.size },
                    apps = discoveredApps
                )
            }
        ).registerAll(registry)

        InboxTools().registerAll(registry)

        if (::dispatcher.isInitialized) {
            dispatcher.instructions = buildInstructions()
        }
    }

    private fun buildInstructions(): String = buildString {
        appendLine("LLM Intentions v0.6.0 — trusted Android capability gateway.")
        appendLine("Local developer transport: authenticated streamable-http on 127.0.0.1:8379/mcp.")
        appendLine("Remote provider calls must arrive through a transport-authenticated principal and Hub policy.")
        appendLine()
        appendLine("Tools are namespaced by source:")
        appendLine("  - android.* — Share, Intent dispatch, maps, dialer, calendar, deep links, query apps")
        appendLine("  - system.* — Battery, clipboard, wifi, volume, notifications, torch, vibrate, media, brightness, ringer, toast")
        appendLine("  - hub.* — Status, health, refresh, inbox")
        for (app in discoveredApps) {
            appendLine(
                "  - ${app.namespace}.* — ${app.packageName} (${app.tools.size} tools, ${app.transport})"
            )
        }
        appendLine()
        appendLine("Total: ${registry.size()} tools from ${discoveredApps.size + 3} sources")
        appendLine()
        appendLine("Tool annotations are hints; deterministic Hub authorization is the execution gate.")
        appendLine("Use android.send_intent only when the requested Android action is explicit and appropriate.")
        appendLine("Use android.query_intent to discover available handlers.")
        val inboxCount = InboxManager.size()
        if (inboxCount > 0) {
            appendLine("  ** $inboxCount unread message(s) in inbox **")
        }
    }

    private fun registerPackageReceiver() {
        packageReceiver = PackageChangeReceiver { _, _ ->
            refreshExecutor.submit { populateRegistry() }
        }
        val filter = IntentFilter().apply {
            addAction(android.content.Intent.ACTION_PACKAGE_ADDED)
            addAction(android.content.Intent.ACTION_PACKAGE_REMOVED)
            addAction(android.content.Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 34) {
            context.registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(packageReceiver, filter)
        }
    }

    fun shutdown() {
        refreshExecutor.shutdownNow()
        packageReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
        }
    }
}
