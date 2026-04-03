package com.androidmcp.core

import com.androidmcp.core.protocol.*
import com.androidmcp.core.registry.*
import kotlinx.serialization.json.*

/**
 * Builder DSL for creating an MCP server with tools.
 *
 * Usage:
 * ```kotlin
 * val server = McpServer("MyApp", "1.0.0") {
 *     tool("greet",
 *         description = "Say hello",
 *         params = jsonSchema { string("name", "Name to greet") }
 *     ) { args ->
 *         val name = args["name"]?.jsonPrimitive?.content ?: "World"
 *         ToolCallResult(content = listOf(ContentBlock.text("Hello, $name!")))
 *     }
 * }
 * ```
 */
class McpServerBuilder(
    private val name: String,
    private val version: String
) {
    internal val toolRegistry = ToolRegistry()
    internal var instructions: String? = null

    fun instructions(text: String) {
        instructions = text
    }

    fun tool(
        name: String,
        description: String,
        params: JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        },
        handler: suspend (JsonObject) -> ToolCallResult
    ) {
        toolRegistry.register(
            McpToolDef(
                info = ToolInfo(name = name, description = description, inputSchema = params),
                handler = handler
            )
        )
    }

    /**
     * Convenience: register a tool that returns a simple text string.
     */
    fun textTool(
        name: String,
        description: String,
        params: JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        },
        handler: suspend (JsonObject) -> String
    ) {
        tool(name, description, params) { args ->
            ToolCallResult(content = listOf(ContentBlock.text(handler(args))))
        }
    }

    fun build(): McpDispatcher {
        return McpDispatcher(
            serverInfo = Implementation(name, version),
            toolRegistry = toolRegistry,
            instructions = instructions
        )
    }
}

fun McpServer(
    name: String,
    version: String = "0.1.0",
    block: McpServerBuilder.() -> Unit
): McpDispatcher {
    return McpServerBuilder(name, version).apply(block).build()
}
