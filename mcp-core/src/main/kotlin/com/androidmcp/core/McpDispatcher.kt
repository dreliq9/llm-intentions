package com.androidmcp.core

import com.androidmcp.core.protocol.*
import com.androidmcp.core.registry.ToolRegistry
import kotlinx.serialization.json.*

/**
 * Handles incoming MCP JSON-RPC requests and dispatches to the appropriate handler.
 */
class McpDispatcher(
    private val serverInfo: Implementation = Implementation("android-mcp-sdk", "0.1.0"),
    private val toolRegistry: ToolRegistry = ToolRegistry(),
    @Volatile var instructions: String? = null
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    fun getToolRegistry(): ToolRegistry = toolRegistry

    /**
     * Process a JSON-RPC request and return a response.
     * Returns null for notifications (no id).
     */
    suspend fun dispatch(request: JsonRpcRequest): JsonRpcResponse? {
        // Notifications have no id and expect no response
        if (request.id == null) {
            handleNotification(request)
            return null
        }

        return try {
            val result = when (request.method) {
                "initialize" -> handleInitialize(request)
                "ping" -> handlePing()
                "tools/list" -> handleToolsList()
                "tools/call" -> handleToolsCall(request)
                else -> throw MethodNotFoundError(request.method)
            }
            JsonRpcResponse(id = request.id, result = result)
        } catch (e: McpError) {
            JsonRpcResponse(
                id = request.id,
                error = JsonRpcError(code = e.code, message = e.message ?: "Unknown error")
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
            "notifications/initialized" -> { /* client confirmed init */ }
            "notifications/cancelled" -> { /* client cancelled a request */ }
        }
    }

    private fun handleInitialize(request: JsonRpcRequest): JsonElement {
        val result = InitializeResult(
            protocolVersion = MCP_PROTOCOL_VERSION,
            capabilities = ServerCapabilities(
                tools = if (toolRegistry.size() > 0) ToolsCapability() else null
            ),
            serverInfo = serverInfo,
            instructions = instructions
        )
        return json.encodeToJsonElement(result)
    }

    private fun handlePing(): JsonElement {
        return buildJsonObject { }
    }

    private fun handleToolsList(): JsonElement {
        val result = ToolsListResult(tools = toolRegistry.list())
        return json.encodeToJsonElement(result)
    }

    private suspend fun handleToolsCall(request: JsonRpcRequest): JsonElement {
        val params = request.params
            ?: throw InvalidParamsError("Missing params for tools/call")
        val callParams = json.decodeFromJsonElement<ToolCallParams>(params)

        val tool = toolRegistry.get(callParams.name)
            ?: throw MethodNotFoundError("Tool not found: ${callParams.name}")

        val result = tool.handler(callParams.arguments ?: buildJsonObject { })
        return json.encodeToJsonElement(result)
    }
}

// --- Error types ---

open class McpError(val code: Int, message: String) : Exception(message)

class MethodNotFoundError(method: String) :
    McpError(JsonRpcError.METHOD_NOT_FOUND, "Method not found: $method")

class InvalidParamsError(message: String) :
    McpError(JsonRpcError.INVALID_PARAMS, message)
