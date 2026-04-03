package com.androidmcp.core.registry

import com.androidmcp.core.protocol.*
import kotlinx.serialization.json.*

/**
 * A registered MCP tool with its metadata and handler function.
 */
data class McpToolDef(
    val info: ToolInfo,
    val handler: suspend (JsonObject) -> ToolCallResult
)

/**
 * Registry that holds all MCP tools. Thread-safe via ConcurrentHashMap.
 * Individual operations are atomic; compound clear+repopulate is not.
 */
class ToolRegistry {
    private val tools = java.util.concurrent.ConcurrentHashMap<String, McpToolDef>()

    fun register(tool: McpToolDef) {
        tools[tool.info.name] = tool
    }

    fun unregister(name: String) {
        tools.remove(name)
    }

    fun clear() {
        tools.clear()
    }

    fun get(name: String): McpToolDef? = tools[name]

    fun list(): List<ToolInfo> = tools.values.map { it.info }

    fun size(): Int = tools.size
}

/**
 * DSL builder for JSON Schema objects used in tool inputSchema.
 */
class JsonSchemaBuilder {
    private val properties = mutableMapOf<String, JsonObject>()
    private val required = mutableListOf<String>()

    fun string(name: String, description: String, required: Boolean = true) {
        properties[name] = buildJsonObject {
            put("type", "string")
            put("description", description)
        }
        if (required) this.required.add(name)
    }

    fun number(name: String, description: String, required: Boolean = true) {
        properties[name] = buildJsonObject {
            put("type", "number")
            put("description", description)
        }
        if (required) this.required.add(name)
    }

    fun integer(name: String, description: String, required: Boolean = true) {
        properties[name] = buildJsonObject {
            put("type", "integer")
            put("description", description)
        }
        if (required) this.required.add(name)
    }

    fun boolean(name: String, description: String, required: Boolean = false) {
        properties[name] = buildJsonObject {
            put("type", "boolean")
            put("description", description)
        }
        if (required) this.required.add(name)
    }

    fun enum(name: String, description: String, values: List<String>, required: Boolean = true) {
        properties[name] = buildJsonObject {
            put("type", "string")
            put("description", description)
            put("enum", JsonArray(values.map { JsonPrimitive(it) }))
        }
        if (required) this.required.add(name)
    }

    fun build(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(properties))
        if (required.isNotEmpty()) {
            put("required", JsonArray(required.map { JsonPrimitive(it) }))
        }
    }
}

fun jsonSchema(block: JsonSchemaBuilder.() -> Unit): JsonObject {
    return JsonSchemaBuilder().apply(block).build()
}
