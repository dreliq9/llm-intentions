package com.androidmcp.core.protocol

import kotlinx.serialization.*
import kotlinx.serialization.json.*

/** MCP protocol version */
const val MCP_PROTOCOL_VERSION = "2025-03-26"

// --- Initialize ---

@Serializable
data class InitializeParams(
    val protocolVersion: String,
    val capabilities: ClientCapabilities = ClientCapabilities(),
    val clientInfo: Implementation? = null
)

@Serializable
data class ClientCapabilities(
    val roots: RootsCapability? = null,
    val sampling: JsonObject? = null
)

@Serializable
data class RootsCapability(
    val listChanged: Boolean = false
)

@Serializable
data class InitializeResult(
    val protocolVersion: String = MCP_PROTOCOL_VERSION,
    val capabilities: ServerCapabilities = ServerCapabilities(),
    val serverInfo: Implementation = Implementation("android-mcp-sdk", "0.1.0"),
    val instructions: String? = null
)

@Serializable
data class ServerCapabilities(
    val tools: ToolsCapability? = ToolsCapability(),
    val resources: ResourcesCapability? = null,
    val prompts: PromptsCapability? = null
)

@Serializable
data class ToolsCapability(
    val listChanged: Boolean = false
)

@Serializable
data class ResourcesCapability(
    val subscribe: Boolean = false,
    val listChanged: Boolean = false
)

@Serializable
data class PromptsCapability(
    val listChanged: Boolean = false
)

@Serializable
data class Implementation(
    val name: String,
    val version: String
)

// --- Tools ---

@Serializable
data class ToolInfo(
    val name: String,
    val description: String,
    val inputSchema: JsonObject
)

@Serializable
data class ToolsListResult(
    val tools: List<ToolInfo>
)

@Serializable
data class ToolCallParams(
    val name: String,
    val arguments: JsonObject? = null
)

@Serializable
data class ToolCallResult(
    val content: List<ContentBlock>,
    val isError: Boolean = false
)

@Serializable
data class ContentBlock(
    val type: String = "text",
    val text: String? = null
) {
    companion object {
        fun text(value: String) = ContentBlock(type = "text", text = value)
    }
}
