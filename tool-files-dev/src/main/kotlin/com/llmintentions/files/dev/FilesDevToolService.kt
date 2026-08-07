package com.llmintentions.files.dev

import com.androidmcp.core.registry.McpToolDef
import com.androidmcp.core.registry.ToolRegistry
import com.androidmcp.intent.ToolAppService
import kotlinx.serialization.json.JsonObject

/**
 * Dev version with MANAGE_EXTERNAL_STORAGE — full filesystem access.
 * Namespace: "fs" (distinguishes from the sandboxed "files" app)
 */
class FilesDevToolService : ToolAppService() {

    protected override val legacyIntentProtocolEnabled: Boolean = false

    override fun onCreateTools(registry: ToolRegistry) {
        FilesDevToolRegistrar.register(registry, applicationContext)

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
}
