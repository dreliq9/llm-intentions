package com.llmintentions.device

import com.androidmcp.core.registry.McpToolDef
import com.androidmcp.core.registry.ToolRegistry
import com.androidmcp.intent.ToolAppService
import kotlinx.serialization.json.JsonObject

class DeviceToolService : ToolAppService() {

    /** Device Tools is the Binder v1 canary; unauthenticated v0 execution is disabled. */
    protected override val legacyIntentProtocolEnabled: Boolean = false

    override fun onCreateTools(registry: ToolRegistry) {
        DeviceToolRegistrar.register(registry, applicationContext)

        // Wrap every tool to log LLM calls
        val originalTools = registry.list().map { it.name to registry.get(it.name)!! }
        for ((name, tool) in originalTools) {
            val originalHandler = tool.handler
            val loggingHandler: suspend (JsonObject) -> com.androidmcp.core.protocol.ToolCallResult = { args ->
                val result = originalHandler(args)
                val text = result.content.joinToString("\n") { it.text ?: "" }
                ToolCallLog.add("LLM", name, text, result.isError)
                result
            }
            registry.register(McpToolDef(info = tool.info, metadata = tool.metadata, handler = loggingHandler))
        }
    }

    override fun onDestroy() {
        DeviceToolRegistrar.shutdown()
        super.onDestroy()
    }
}
