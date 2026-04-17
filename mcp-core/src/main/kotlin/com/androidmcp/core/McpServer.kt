package com.androidmcp.core

import com.androidmcp.core.protocol.*
import com.androidmcp.core.registry.*
import kotlinx.serialization.json.*

/**
 * Builder DSL for creating an MCP server with tools and resources.
 *
 * Tools are *actions* the agent can take. Resources are *state* the agent
 * can read on demand without burning a tool call.
 *
 * Usage:
 * ```kotlin
 * val server = McpServer("MyApp", "1.0.0") {
 *     instructions("MCP server for the demo app.")
 *
 *     tool("greet",
 *         description = "Say hello",
 *         params = jsonSchema { string("name", "Name to greet") }
 *     ) { args ->
 *         val name = args["name"]?.jsonPrimitive?.content ?: "World"
 *         ToolCallResult(content = listOf(ContentBlock.text("Hello, $name!")))
 *     }
 *
 *     textResource(
 *         uri = "myapp://about",
 *         name = "About",
 *         description = "Static info about this server",
 *         mimeType = "text/markdown",
 *     ) {
 *         "# MyApp\n\nA demo MCP server."
 *     }
 * }
 * ```
 */
class McpServerBuilder(
    private val name: String,
    private val version: String
) {
    internal val toolRegistry = ToolRegistry()
    internal val resourceRegistry = ResourceRegistry()
    internal var instructions: String? = null

    fun instructions(text: String) {
        instructions = text
    }

    // --- Tools ---

    fun tool(
        name: String,
        description: String,
        params: JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        },
        title: String? = null,
        outputSchema: JsonObject? = null,
        annotations: ToolAnnotations? = null,
        handler: suspend (JsonObject) -> ToolCallResult
    ) {
        toolRegistry.register(
            McpToolDef(
                info = ToolInfo(
                    name = name,
                    description = description,
                    inputSchema = params,
                    title = title,
                    outputSchema = outputSchema,
                    annotations = annotations,
                ),
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

    // --- Resources ---

    /**
     * Register a static-URI resource. The handler is invoked on each read;
     * cache server-side if the underlying state is expensive to fetch.
     */
    fun resource(
        uri: String,
        name: String,
        description: String? = null,
        mimeType: String? = null,
        title: String? = null,
        annotations: ResourceAnnotations? = null,
        handler: suspend (uri: String) -> ReadResourceResult
    ) {
        resourceRegistry.register(
            McpResourceDef(
                info = Resource(
                    uri = uri,
                    name = name,
                    title = title,
                    description = description,
                    mimeType = mimeType,
                    annotations = annotations,
                ),
                handler = handler
            )
        )
    }

    /**
     * Convenience: register a resource that returns a single text body.
     * Handler returns the body string; mimeType defaults to "text/plain".
     */
    fun textResource(
        uri: String,
        name: String,
        description: String? = null,
        mimeType: String? = "text/plain",
        title: String? = null,
        handler: suspend (uri: String) -> String
    ) {
        resource(uri, name, description, mimeType, title) { u ->
            ReadResourceResult(contents = listOf(ResourceContents.text(u, handler(u), mimeType)))
        }
    }

    fun build(): McpDispatcher {
        return McpDispatcher(
            serverInfo = Implementation(name, version),
            toolRegistry = toolRegistry,
            resourceRegistry = resourceRegistry,
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
