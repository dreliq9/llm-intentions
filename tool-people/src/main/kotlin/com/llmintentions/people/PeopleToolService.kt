package com.llmintentions.people

import com.androidmcp.core.registry.McpToolDef
import com.androidmcp.core.registry.ToolRegistry
import com.androidmcp.intent.ToolAppService
import kotlinx.serialization.json.JsonObject

class PeopleToolService : ToolAppService() {
    override fun onCreateTools(registry: ToolRegistry) {
        PeopleToolRegistrar.register(registry, applicationContext)
        val originalTools = registry.list().map { it.name to registry.get(it.name)!! }
        for ((name, tool) in originalTools) {
            val originalHandler = tool.handler
            val loggingHandler: suspend (JsonObject) -> com.androidmcp.core.protocol.ToolCallResult = { args ->
                val result = originalHandler(args)
                ToolCallLog.add("LLM", name, result.content.joinToString("\n") { it.text ?: "" }, result.isError)
                result
            }
            registry.register(McpToolDef(info = tool.info, handler = loggingHandler))
        }
    }
}
