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
import com.androidmcp.hub.system.DeviceControlTools
import com.androidmcp.hub.system.NotificationTools
import com.androidmcp.hub.system.SystemToolDefinitions
import java.util.concurrent.Executors

/**
 * Core Hub orchestration — extracted from HubMcpProvider.
 *
 * Plain class (not a ContentProvider) that takes a Context, discovers apps,
 * registers all tools, and produces a configured McpDispatcher.
 *
 * Used by HubStdioService as the single source of truth for the Hub's
 * MCP capabilities.
 */
class HubMcpEngine(private val context: Context) {

    val registry = ToolRegistry()
    val discovery = IntentAppDiscovery(context)
    val intentEngine = IntentEngine(context)
    val healthMonitor = IntentHealthMonitor(context)

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

    /**
     * Initialize the engine: discover apps, register tools, create dispatcher.
     */
    /**
     * Re-discover apps and rebuild the tool registry.
     */
    fun refresh() {
        populateRegistry()
    }

    fun initialize() {
        populateRegistry()
        registerPackageReceiver()

        dispatcher = McpDispatcher(
            serverInfo = Implementation("LLM Intentions", "0.5.0"),
            toolRegistry = registry,
            instructions = buildInstructions()
        )
    }

    @Synchronized
    private fun populateRegistry() {
        // Phase 1: Discover Intent-based tool apps
        val newApps = discovery.discover()

        // Phase 2: Clear and rebuild
        registry.clear()
        discoveredApps = newApps

        // Register proxied tools from discovered apps (via Intent)
        router.registerProxyTools(registry, newApps)

        // Android capability tools (existing)
        IntentToolDefinitions(context).registerAll(registry)
        IntentScanner(context).registerDiscovered(registry)
        FileShareTools(context).registerAll(registry)

        // Generic Intent tools (new — universal Intent interface)
        GenericIntentTools(context, intentEngine).registerAll(registry)

        // System tools
        SystemToolDefinitions(context).registerAll(registry)
        DeviceControlTools(context).registerAll(registry)
        NotificationTools(context).registerAll(registry)

        // Hub meta-tools
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

        // Inbox tools
        InboxTools().registerAll(registry)

        // Update instructions if dispatcher already exists
        if (::dispatcher.isInitialized) {
            dispatcher.instructions = buildInstructions()
        }
    }

    private fun buildInstructions(): String = buildString {
        appendLine("LLM Intentions v0.5.0 — universal Intent gateway for Android.")
        appendLine("Transport: streamable-http on localhost:8379/mcp.")
        appendLine()
        appendLine("Tools are namespaced by source:")
        appendLine("  - android.* — Share, Intent dispatch, maps, dialer, calendar, deep links, query apps")
        appendLine("  - system.* — Battery, clipboard, wifi, volume, notifications, torch, vibrate, media, brightness, ringer, toast")
        appendLine("  - hub.* — Status, health, refresh, inbox (messages from Android apps)")
        for (app in discoveredApps) {
            appendLine("  - ${app.namespace}.* — ${app.packageName} (${app.tools.size} tools)")
        }
        appendLine()
        appendLine("Total: ${registry.size()} tools from ${discoveredApps.size + 3} sources")
        appendLine()
        appendLine("Use android.send_intent to send arbitrary Intents to any Android app.")
        appendLine("Use android.query_intent to discover what apps handle a given action.")
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
