package com.androidmcp.core

import com.androidmcp.core.policy.ToolAuthorizationOutcome
import com.androidmcp.core.policy.ToolAuthorizationRequest
import com.androidmcp.core.policy.ToolCallAuthorizer
import com.androidmcp.core.policy.ToolInvocationSecurityContext
import com.androidmcp.core.protocol.*
import com.androidmcp.core.registry.ResourceRegistry
import com.androidmcp.core.registry.ToolRegistry
import kotlinx.serialization.json.*

/**
 * Handles incoming MCP JSON-RPC requests and dispatches to the appropriate handler.
 *
 * The dispatcher supports the stateless MCP 2026-07-28 core while retaining the
 * 2025-06-18 initialize path for deployed clients during migration.
 *
 * [toolAuthorizer] is optional so generic SDK consumers retain existing behavior. When an
 * authorizer is installed, every tools/call must also carry a [ToolInvocationSecurityContext]
 * supplied by a trusted transport; missing context fails closed before the handler is invoked.
 */
class McpDispatcher(
    private val serverInfo: Implementation = Implementation("android-mcp-sdk", "0.2.0"),
    private val toolRegistry: ToolRegistry = ToolRegistry(),
    private val resourceRegistry: ResourceRegistry = ResourceRegistry(),
    private val toolAuthorizer: ToolCallAuthorizer? = null,
    @Volatile var instructions: String? = null
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    fun getToolRegistry(): ToolRegistry = toolRegistry
    fun getResourceRegistry(): ResourceRegistry = resourceRegistry

    /**
     * Process a JSON-RPC request and return a response.
     * Returns null for legacy notifications (no id).
     *
     * [securityContext] must originate from the authenticated transport. Never construct it from
     * MCP request _meta/clientInfo, which is self-reported client data.
     */
    suspend fun dispatch(
        request: JsonRpcRequest,
        securityContext: ToolInvocationSecurityContext? = null,
    ): JsonRpcResponse? {
        if (request.id == null) {
            handleNotification(request)
            return null
        }

        return try {
            val requestedVersion = requestProtocolVersion(request)
            if (requestedVersion != null && requestedVersion !in SUPPORTED_MCP_PROTOCOL_VERSIONS) {
                throw UnsupportedProtocolVersionError(requestedVersion)
            }

            val modern = isModernRequest(request)
            if (modern) validateModernRequestMeta(request)

            val rawResult = when (request.method) {
                "server/discover" -> handleDiscover()
                "initialize" -> {
                    if (modern) throw MethodNotFoundError("initialize")
                    handleInitialize()
                }
                "ping" -> handlePing()
                "tools/list" -> handleToolsList()
                "tools/call" -> handleToolsCall(request, modern, securityContext)
                "resources/list" -> handleResourcesList()
                "resources/read" -> handleResourcesRead(request, modern)
                else -> throw MethodNotFoundError(request.method)
            }
            val result = if (modern) normalizeModernResult(rawResult) else rawResult
            JsonRpcResponse(id = request.id, result = result)
        } catch (e: McpError) {
            JsonRpcResponse(
                id = request.id,
                error = JsonRpcError(code = e.code, message = e.message ?: "Unknown error", data = e.data)
            )
        } catch (e: Exception) {
            JsonRpcResponse(
                id = request.id,
                error = JsonRpcError(
                    code = JsonRpcError.INTERNAL_ERROR,
                    message = e.message ?: "Internal error"
                )
            )
        }
    }

    private fun handleNotification(request: JsonRpcRequest) {
        when (request.method) {
            "notifications/initialized" -> { /* initialize-era client confirmed init */ }
            "notifications/cancelled" -> { /* initialize-era client cancelled a request */ }
        }
    }

    private fun capabilities(): ServerCapabilities = ServerCapabilities(
        tools = if (toolRegistry.size() > 0) ToolsCapability() else null,
        resources = if (resourceRegistry.size() > 0) ResourcesCapability() else null
    )

    private fun handleDiscover(): JsonElement {
        val result = DiscoverResult(
            supportedVersions = SUPPORTED_MCP_PROTOCOL_VERSIONS,
            capabilities = capabilities(),
            meta = serverInfoMeta(),
            instructions = instructions,
            ttlMs = 60_000,
            cacheScope = MCP_CACHE_SCOPE_PRIVATE,
        )
        return json.encodeToJsonElement(result)
    }

    private fun handleInitialize(): JsonElement {
        val result = InitializeResult(
            protocolVersion = MCP_LEGACY_PROTOCOL_VERSION,
            capabilities = capabilities(),
            serverInfo = serverInfo,
            instructions = instructions
        )
        return json.encodeToJsonElement(result)
    }

    private fun handlePing(): JsonElement = buildJsonObject { }

    private fun handleToolsList(): JsonElement {
        val result = ToolsListResult(
            tools = toolRegistry.list(),
            ttlMs = 30_000,
            cacheScope = MCP_CACHE_SCOPE_PRIVATE,
        )
        return json.encodeToJsonElement(result)
    }

    private suspend fun handleToolsCall(
        request: JsonRpcRequest,
        modern: Boolean,
        securityContext: ToolInvocationSecurityContext?,
    ): JsonElement {
        val params = request.params
            ?: throw InvalidParamsError("Missing params for tools/call")
        val callParams = json.decodeFromJsonElement<ToolCallParams>(params)

        val tool = toolRegistry.get(callParams.name)
        if (tool == null) {
            if (modern) throw InvalidParamsError("Unknown tool: ${callParams.name}")
            throw MethodNotFoundError("Tool not found: ${callParams.name}")
        }

        val arguments = callParams.arguments ?: buildJsonObject { }
        val authorizer = toolAuthorizer
        if (authorizer != null) {
            val trustedContext = securityContext
                ?: return json.encodeToJsonElement(policyDeniedResult(
                    "Tool authorization context is missing; refusing to execute ${callParams.name}"
                ))

            when (val outcome = authorizer.authorize(
                ToolAuthorizationRequest(
                    toolName = callParams.name,
                    metadata = tool.metadata,
                    arguments = arguments,
                    inputResponses = callParams.inputResponses,
                    requestState = callParams.requestState,
                    securityContext = trustedContext,
                    supportsInputRequired = modern,
                )
            )) {
                ToolAuthorizationOutcome.Allow -> { /* execute below */ }
                is ToolAuthorizationOutcome.Deny ->
                    return json.encodeToJsonElement(policyDeniedResult(outcome.reason))
                is ToolAuthorizationOutcome.InputRequired ->
                    return json.encodeToJsonElement(outcome.result)
            }
        }

        val result = tool.handler(arguments)
        return json.encodeToJsonElement(result)
    }

    private fun policyDeniedResult(reason: String): ToolCallResult = ToolCallResult(
        content = listOf(ContentBlock.text("Policy denied tool execution: $reason")),
        structuredContent = buildJsonObject {
            put("status", "denied")
            put("reason", reason)
        },
        isError = true,
    )

    private fun handleResourcesList(): JsonElement {
        val result = ResourcesListResult(
            resources = resourceRegistry.list(),
            ttlMs = 30_000,
            cacheScope = MCP_CACHE_SCOPE_PRIVATE,
        )
        return json.encodeToJsonElement(result)
    }

    private suspend fun handleResourcesRead(request: JsonRpcRequest, modern: Boolean): JsonElement {
        val params = request.params
            ?: throw InvalidParamsError("Missing params for resources/read")
        val readParams = json.decodeFromJsonElement<ReadResourceParams>(params)

        val resource = resourceRegistry.get(readParams.uri)
        if (resource == null) {
            if (modern) throw InvalidParamsError("Resource not found: ${readParams.uri}")
            throw ResourceNotFoundError(readParams.uri)
        }

        val result = resource.handler(readParams.uri)
        return json.encodeToJsonElement(result)
    }

    private fun validateModernRequestMeta(request: JsonRpcRequest) {
        val meta = request.params?.get("_meta") as? JsonObject
            ?: throw InvalidParamsError("Modern MCP requests require params._meta")

        val version = meta["io.modelcontextprotocol/protocolVersion"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?: throw InvalidParamsError(
                "Missing params._meta.io.modelcontextprotocol/protocolVersion"
            )
        if (version != MCP_MODERN_PROTOCOL_VERSION) {
            if (version !in SUPPORTED_MCP_PROTOCOL_VERSIONS) {
                throw UnsupportedProtocolVersionError(version)
            }
            throw InvalidParamsError("Per-request metadata does not select the modern MCP protocol")
        }

        val clientCapabilities = meta["io.modelcontextprotocol/clientCapabilities"]
        if (clientCapabilities !is JsonObject) {
            throw InvalidParamsError(
                "Missing or invalid params._meta.io.modelcontextprotocol/clientCapabilities"
            )
        }

        val clientInfo = meta["io.modelcontextprotocol/clientInfo"]
        if (clientInfo != null && clientInfo !is JsonObject) {
            throw InvalidParamsError(
                "params._meta.io.modelcontextprotocol/clientInfo must be an object when provided"
            )
        }
    }

    private fun isModernRequest(request: JsonRpcRequest): Boolean =
        requestProtocolVersion(request) == MCP_MODERN_PROTOCOL_VERSION || request.method == "server/discover"

    private fun requestProtocolVersion(request: JsonRpcRequest): String? =
        request.params
            ?.get("_meta")
            ?.let { it as? JsonObject }
            ?.get("io.modelcontextprotocol/protocolVersion")
            ?.jsonPrimitive
            ?.contentOrNull

    private fun serverInfoMeta(): JsonObject = buildJsonObject {
        put("io.modelcontextprotocol/serverInfo", json.encodeToJsonElement(serverInfo))
    }

    /** Every modern successful result is self-describing and stamped with server identity. */
    private fun normalizeModernResult(result: JsonElement): JsonElement {
        val obj = result as? JsonObject ?: return result
        val existingMeta = obj["_meta"] as? JsonObject ?: JsonObject(emptyMap())
        val mergedMeta = JsonObject(existingMeta + serverInfoMeta())
        val withResultType = if ("resultType" in obj) obj else {
            JsonObject(obj + ("resultType" to JsonPrimitive(MCP_RESULT_COMPLETE)))
        }
        return JsonObject(withResultType + ("_meta" to mergedMeta))
    }
}

open class McpError(
    val code: Int,
    message: String,
    val data: JsonElement? = null,
) : Exception(message)

class MethodNotFoundError(method: String) :
    McpError(JsonRpcError.METHOD_NOT_FOUND, "Method not found: $method")

class InvalidParamsError(message: String) :
    McpError(JsonRpcError.INVALID_PARAMS, message)

class UnsupportedProtocolVersionError(version: String) : McpError(
    JsonRpcError.UNSUPPORTED_PROTOCOL_VERSION,
    "Unsupported protocol version: $version",
    buildJsonObject {
        put("supported", JsonArray(SUPPORTED_MCP_PROTOCOL_VERSIONS.map(::JsonPrimitive)))
        put("requested", version)
    },
)

class ResourceNotFoundError(uri: String) :
    McpError(-32002, "Resource not found: $uri")
