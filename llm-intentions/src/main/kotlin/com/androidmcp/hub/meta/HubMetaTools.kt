package com.androidmcp.hub.meta

import com.androidmcp.core.protocol.*
import com.androidmcp.core.registry.McpToolDef
import com.androidmcp.core.registry.ToolRegistry
import com.androidmcp.core.registry.jsonSchema
import com.androidmcp.hub.discovery.DiscoveredApp
import com.androidmcp.hub.health.IntentHealthMonitor
import kotlinx.serialization.json.*

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
    }

    private fun registerStatus(registry: ToolRegistry) {
        registry.register(McpToolDef(
            info = ToolInfo(
                name = "hub.status",
                description = "Show Hub status: discovered apps, tool counts per source, total tools",
                inputSchema = jsonSchema { }
            ),
            handler = { _ ->
                val apps = getDiscoveredApps()
                val text = buildString {
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
                ToolCallResult(content = listOf(ContentBlock.text(text)))
            }
        ))
    }

    private fun registerHealth(registry: ToolRegistry) {
        registry.register(McpToolDef(
            info = ToolInfo(
                name = "hub.health",
                description = "Ping all discovered tool apps and report their health status",
                inputSchema = jsonSchema { }
            ),
            handler = { _ ->
                val apps = getDiscoveredApps()
                if (apps.isEmpty()) {
                    ToolCallResult(content = listOf(ContentBlock.text(
                        "No external tool apps discovered."
                    )))
                } else {
                    val results = healthMonitor.checkAll(apps)

                    val text = buildString {
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
                    ToolCallResult(content = listOf(ContentBlock.text(text)))
                }
            }
        ))
    }

    private fun registerRefresh(registry: ToolRegistry) {
        registry.register(McpToolDef(
            info = ToolInfo(
                name = "hub.refresh",
                description = "Force re-discovery of all tool apps and rebuild tool registry",
                inputSchema = jsonSchema { }
            ),
            handler = { _ ->
                val result = refreshCallback()
                val text = buildString {
                    appendLine("Tool registry refreshed.")
                    appendLine("Previous external tools: ${result.previousCount}")
                    appendLine("New external tools: ${result.newCount}")
                    appendLine()
                    appendLine("Discovered apps:")
                    for (app in result.apps) {
                        appendLine("  ${app.namespace} — ${app.tools.size} tools (${app.packageName})")
                    }
                }
                ToolCallResult(content = listOf(ContentBlock.text(text)))
            }
        ))
    }
}
