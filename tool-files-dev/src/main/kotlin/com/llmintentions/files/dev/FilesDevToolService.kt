package com.llmintentions.files.dev

import com.androidmcp.core.registry.McpToolDef
import com.androidmcp.core.registry.ToolRegistry
import com.androidmcp.intent.ToolAppService
import kotlinx.serialization.json.JsonObject

/**
 * Dev version with MANAGE_EXTERNAL_STORAGE — full filesystem access.
 * Namespace: "fs" (distinguishes from the sandboxed "files" app)
 *
 * Tool logic lives in FilesDevToolRegistrar so it can be shared
 * with the Activity for in-process UI execution.
 */
class FilesDevToolService : ToolAppService() {

    override fun onCreateTools(registry: ToolRegistry) {
        FilesDevToolRegistrar.register(registry, applicationContext)

        // Wrap every tool handler to log LLM calls
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
