package com.androidmcp.core

import com.androidmcp.core.protocol.*
import com.androidmcp.core.registry.ResourceRegistry
import com.androidmcp.core.registry.ToolRegistry
import kotlinx.serialization.json.*

/**
 * Handles incoming MCP JSON-RPC requests and dispatches to the appropriate handler.
 *
 * The dispatcher supports the stateless MCP 2026-07-28 core while retaining the
 * 2025-06-18 initialize path for deployed clients during migration.
 */
class McpDispatcher(
    private val serverInfo: Implementation = Implementation("android-mcp-sdk", "0.2.0"),
    private val toolRegistry: ToolRegistry = ToolRegistry(),
    private val resourceRegistry: ResourceRegistry = ResourceRegistry(),
    @Volatile var instructions: String? = null
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    fun getToolRegistry(): ToolRegistry = toolRegistry
    fun getResourceRegistry(): ResourceRegistry = resourceRegistry

    /**
     * Process a JSON-RPC request and return a response.
     * Returns null for legacy notifications (no id).
     */
    suspend fun dispatch(request: JsonRpcRequest): JsonRpcResponse? {
        if (request.id == null) {
            handleNotification(request)
            return null
        }

        return try {
            val rawResult = when (request.method) {
                "server/discover" -> handleDiscover()
                "initialize" -> handleInitialize()
                "ping" -> handlePing()
                "tools/list" -> handleToolsList()
                "tools/call" -> handleToolsCall(request)
                "resources/list" -> handleResourcesList()
                "resources/read" -> handleResourcesRead(request)
                else -> throw MethodNotFoundError(request.method)
            }
            val result = if (isModernRequest(request)) stampServerInfo(rawResult) else rawResult
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
        // Retained only for compatibility with initialize-era clients.
        when (request.method) {
            "notifications/initialized" -> { /* client confirmed init */ }
            "notifications/cancelled" -> { /* legacy client cancelled a request */ }
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
        // initialize belongs to the legacy protocol era. Keep its public version stable
        // while modern clients use server/discover and per-request metadata.
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

    private suspend fun handleToolsCall(request: JsonRpcRequest): JsonElement {
        val params = request.params
            ?: throw InvalidParamsError("Missing params for tools/call")
        val callParams = json.decodeFromJsonElement<ToolCallParams>(params)

        val tool = toolRegistry.get(callParams.name)
        if (tool == null) {
            if (isModernRequest(request)) {
                throw InvalidParamsError("Tool not found: ${callParams.name}")
            }
            throw MethodNotFoundError("Tool not found: ${callParams.name}")
        }

        val result = tool.handler(callParams.arguments ?: buildJsonObject { })
        return json.encodeToJsonElement(result)
    }

    private fun handleResourcesList(): JsonElement {
        val result = ResourcesListResult(
            resources = resourceRegistry.list(),
            ttlMs = 30_000,
            cacheScope = MCP_CACHE_SCOPE_PRIVATE,
        )
        return json.encodeToJsonElement(result)
    }

    private suspend fun handleResourcesRead(request: JsonRpcRequest): JsonElement {
        val params = request.params
            ?: throw InvalidParamsError("Missing params for resources/read")
        val readParams = json.decodeFromJsonElement<ReadResourceParams>(params)

        val resource = resourceRegistry.get(readParams.uri)
        if (resource == null) {
            if (isModernRequest(request)) {
                throw InvalidParamsError("Resource not found: ${readParams.uri}")
            }
            throw ResourceNotFoundError(readParams.uri)
        }

        val result = resource.handler(readParams.uri)
        return json.encodeToJsonElement(result)
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

    private fun stampServerInfo(result: JsonElement): JsonElement {
        val obj = result as? JsonObject ?: return result
        val existingMeta = obj["_meta"] as? JsonObject ?: JsonObject(emptyMap())
        val mergedMeta = JsonObject(existingMeta + serverInfoMeta())
        return JsonObject(obj + ("_meta" to mergedMeta))
    }
}

// --- Error types ---

open class McpError(
    val code: Int,
    message: String,
    val data: JsonElement? = null,
) : Exception(message)

class MethodNotFoundError(method: String) :
    McpError(JsonRpcError.METHOD_NOT_FOUND, "Method not found: $method")

class InvalidParamsError(message: String) :
    McpError(JsonRpcError.INVALID_PARAMS, message)

class ResourceNotFoundError(uri: String) :
    McpError(-32002, "Resource not found: $uri")
