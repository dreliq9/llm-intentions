package com.androidmcp.core.registry

import com.androidmcp.core.protocol.*
import kotlinx.serialization.json.*

/** A registered MCP tool with its metadata and handler function. */
data class McpToolDef(
    val info: ToolInfo,
    val metadata: ToolMetadata? = null,
    val handler: suspend (JsonObject) -> ToolCallResult,
)

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

    /** Stable ordering improves MCP list caching and upstream LLM prompt-cache reuse. */
    fun list(): List<ToolInfo> = tools.values.map { it.info }.sortedBy { it.name }

    /** Full definitions for trusted local descriptor/policy plumbing. */
    fun definitions(): List<McpToolDef> = tools.values.sortedBy { it.info.name }

    fun size(): Int = tools.size
}

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

fun jsonSchema(block: JsonSchemaBuilder.() -> Unit): JsonObject =
    JsonSchemaBuilder().apply(block).build()

/**
 * MCP ToolAnnotations are client-facing hints, not the authorization source of truth. Derive them
 * only when our richer metadata has an explicit semantic answer. UNKNOWN deliberately leaves the
 * hint absent so MCP's pessimistic defaults apply rather than falsely labelling a mutation as read.
 */
private fun ToolMetadata.toMcpAnnotations(): ToolAnnotations = ToolAnnotations(
    readOnlyHint = when (mutation) {
        MutationClass.READ_ONLY -> true
        MutationClass.MUTATING -> false
        MutationClass.UNKNOWN -> null
    },
    destructiveHint = when {
        mutation == MutationClass.READ_ONLY -> null
        destructive -> true
        mutation == MutationClass.MUTATING -> false
        else -> null
    },
    idempotentHint = when (mutation) {
        MutationClass.MUTATING -> idempotent
        MutationClass.READ_ONLY, MutationClass.UNKNOWN -> null
    },
    openWorldHint = openWorld,
)

fun ToolRegistry.textTool(
    name: String,
    description: String,
    params: JsonObject,
    metadata: ToolMetadata? = null,
    handler: suspend (JsonObject) -> String,
) {
    register(McpToolDef(
        info = ToolInfo(
            name = name,
            description = description,
            inputSchema = params,
            annotations = metadata?.toMcpAnnotations(),
        ),
        metadata = metadata,
        handler = { args ->
            val text = handler(args)
            val env = Envelope.ok(
                summary = "$name succeeded",
                data = JsonObject(mapOf("output" to JsonPrimitive(text))),
            )
            ToolCallResult(
                content = listOf(ContentBlock.text(env.renderText())),
                isError = false,
            )
        },
    ))
}

fun ToolRegistry.envelopeTool(
    name: String,
    description: String,
    params: JsonObject,
    metadata: ToolMetadata? = null,
    handler: suspend (JsonObject) -> Envelope,
) {
    register(McpToolDef(
        info = ToolInfo(
            name = name,
            description = description,
            inputSchema = params,
            annotations = metadata?.toMcpAnnotations(),
        ),
        metadata = metadata,
        handler = { args ->
            val env = handler(args)
            ToolCallResult(
                content = listOf(ContentBlock.text(env.renderText())),
                isError = env.status == EnvelopeStatus.FAIL,
            )
        },
    ))
}

/** DSL builder for trusted LLM Intentions policy metadata. */
class ToolMetadataBuilder {
    var destructive: Boolean = false
    var idempotent: Boolean = true
    var latencyClass: LatencyClass = LatencyClass.FAST
    var mutation: MutationClass = MutationClass.UNKNOWN
    var sensitiveData: SensitiveDataClass = SensitiveDataClass.UNKNOWN
    var confirmation: ConfirmationMode = ConfirmationMode.POLICY
    var openWorld: Boolean? = null

    private val permissions = mutableListOf<String>()
    private val failureModes = mutableListOf<FailureMode>()
    private val examples = mutableListOf<ToolExample>()

    fun permission(name: String) { permissions.add(name) }

    fun failureMode(pattern: String? = null, exceptionType: String? = null, hint: String) {
        require(pattern != null || exceptionType != null) {
            "failureMode needs a pattern or an exceptionType"
        }
        failureModes.add(FailureMode(pattern, exceptionType, hint))
    }

    fun example(intent: String, argsBuilder: (MutableMap<String, Any>) -> Unit) {
        val map = mutableMapOf<String, Any>()
        argsBuilder(map)
        val args = buildJsonObject {
            for ((k, v) in map) {
                when (v) {
                    is String -> put(k, v)
                    is Int -> put(k, v)
                    is Long -> put(k, v)
                    is Double -> put(k, v)
                    is Float -> put(k, v)
                    is Boolean -> put(k, v)
                    else -> put(k, v.toString())
                }
            }
        }
        examples.add(ToolExample(args = args, intent = intent))
    }

    fun build(): ToolMetadata = ToolMetadata(
        examples = examples.toList(),
        permissions = permissions.toList(),
        destructive = destructive,
        idempotent = idempotent,
        latencyClass = latencyClass,
        failureModes = failureModes.toList(),
        mutation = mutation,
        sensitiveData = sensitiveData,
        confirmation = confirmation,
        openWorld = openWorld,
    )
}

fun toolMetadata(block: ToolMetadataBuilder.() -> Unit): ToolMetadata =
    ToolMetadataBuilder().apply(block).build()
