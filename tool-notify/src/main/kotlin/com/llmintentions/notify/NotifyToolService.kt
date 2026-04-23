package com.llmintentions.notify

import com.androidmcp.core.registry.McpToolDef
import com.androidmcp.core.registry.ToolRegistry
import com.androidmcp.intent.ToolAppService
import kotlinx.serialization.json.JsonObject

class NotifyToolService : ToolAppService() {

    override fun onCreateTools(registry: ToolRegistry) {
        NotifyToolRegistrar.register(registry, applicationContext)

        val originalTools = registry.list().map { it.name to registry.get(it.name)!! }
        for ((name, tool) in originalTools) {
            val originalHandler = tool.handler
            val loggingHandler: suspend (JsonObject) -> com.androidmcp.core.protocol.ToolCallResult = { args ->
                val result = originalHandler(args)
                val text = result.content.joinToString("\n") { it.text ?: "" }
                ToolCallLog.add("LLM", name, text, result.isError)
                result
            }
            registry.register(McpToolDef(info = tool.info, handler = loggingHandler))
        }
    }
}
