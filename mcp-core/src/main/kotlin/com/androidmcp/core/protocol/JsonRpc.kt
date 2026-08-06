package com.androidmcp.core.protocol

import kotlinx.serialization.*
import kotlinx.serialization.json.*

/**
 * JSON-RPC 2.0 message types for MCP protocol.
 */

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonObject? = null,
    val id: JsonElement? = null
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
    val id: JsonElement? = null
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
) {
    companion object {
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603

        // MCP 2026-07-28 protocol-defined errors.
        const val HEADER_MISMATCH = -32020
        const val MISSING_REQUIRED_CLIENT_CAPABILITY = -32021
        const val UNSUPPORTED_PROTOCOL_VERSION = -32022
    }
}

@Serializable
data class JsonRpcNotification(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonObject? = null
)
