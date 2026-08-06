package com.androidmcp.core.protocol

import kotlinx.serialization.*
import kotlinx.serialization.json.*

/** Current stateless MCP protocol version. */
const val MCP_PROTOCOL_VERSION = "2026-07-28"

/** Legacy version retained while deployed clients migrate. */
const val MCP_LEGACY_PROTOCOL_VERSION = "2025-06-18"

val SUPPORTED_MCP_PROTOCOL_VERSIONS = listOf(MCP_PROTOCOL_VERSION, MCP_LEGACY_PROTOCOL_VERSION)

const val MCP_RESULT_COMPLETE = "complete"
const val MCP_CACHE_SCOPE_PRIVATE = "private"
const val MCP_CACHE_SCOPE_PUBLIC = "public"

// --- Initialize (legacy compatibility path) ---

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
    val protocolVersion: String = MCP_LEGACY_PROTOCOL_VERSION,
    val capabilities: ServerCapabilities = ServerCapabilities(),
    val serverInfo: Implementation = Implementation("android-mcp-sdk", "0.1.0"),
    val instructions: String? = null
)

/** 2026-07-28 stateless discovery result. */
@Serializable
data class DiscoverResult(
    val resultType: String = MCP_RESULT_COMPLETE,
    val supportedVersions: List<String> = SUPPORTED_MCP_PROTOCOL_VERSIONS,
    val capabilities: ServerCapabilities = ServerCapabilities(),
    @SerialName("_meta") val meta: JsonObject = JsonObject(emptyMap()),
    val instructions: String? = null,
    val ttlMs: Long = 60_000,
    val cacheScope: String = MCP_CACHE_SCOPE_PRIVATE,
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

/**
 * Tool annotations describing behavior to clients (advisory; clients should
 * not rely on these for security-critical decisions).
 */
@Serializable
data class ToolAnnotations(
    val title: String? = null,
    val readOnlyHint: Boolean? = null,
    val destructiveHint: Boolean? = null,
    val idempotentHint: Boolean? = null,
    val openWorldHint: Boolean? = null
)

@Serializable
data class ToolInfo(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val title: String? = null,
    val outputSchema: JsonObject? = null,
    val annotations: ToolAnnotations? = null
)

@Serializable
data class ToolsListResult(
    val resultType: String = MCP_RESULT_COMPLETE,
    val tools: List<ToolInfo>,
    val nextCursor: String? = null,
    val ttlMs: Long = 30_000,
    val cacheScope: String = MCP_CACHE_SCOPE_PRIVATE,
)

@Serializable
data class ToolCallParams(
    val name: String,
    val arguments: JsonObject? = null,
    val inputResponses: JsonObject? = null,
    val requestState: String? = null,
)

/**
 * Tool result payload.
 *
 * - [content]: human-readable text/image/resource blocks (always present).
 * - [structuredContent]: typed JSON shape for clients that understand the
 *   tool's outputSchema. Optional.
 * - [isError]: true if the tool failed; clients should surface this rather
 *   than treating the response as success.
 */
@Serializable
data class ToolCallResult(
    val resultType: String = MCP_RESULT_COMPLETE,
    val content: List<ContentBlock>,
    val structuredContent: JsonObject? = null,
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

// --- Resources ---

/**
 * A discoverable resource the server exposes for clients to read.
 * Resources are state to read rather than actions to take.
 */
@Serializable
data class Resource(
    val uri: String,
    val name: String,
    val title: String? = null,
    val description: String? = null,
    val mimeType: String? = null,
    val size: Long? = null,
    val annotations: ResourceAnnotations? = null
)

@Serializable
data class ResourceAnnotations(
    val audience: List<String>? = null,
    val priority: Double? = null
)

@Serializable
data class ResourcesListResult(
    val resultType: String = MCP_RESULT_COMPLETE,
    val resources: List<Resource>,
    val nextCursor: String? = null,
    val ttlMs: Long = 30_000,
    val cacheScope: String = MCP_CACHE_SCOPE_PRIVATE,
)

@Serializable
data class ReadResourceParams(
    val uri: String
)

@Serializable
data class ReadResourceResult(
    val resultType: String = MCP_RESULT_COMPLETE,
    val contents: List<ResourceContents>,
    val ttlMs: Long = 0,
    val cacheScope: String = MCP_CACHE_SCOPE_PRIVATE,
)

/**
 * Contents returned by resources/read. Either [text] or [blob] is set,
 * never both. [blob] is base64-encoded when present.
 */
@Serializable
data class ResourceContents(
    val uri: String,
    val mimeType: String? = null,
    val text: String? = null,
    val blob: String? = null
) {
    companion object {
        fun text(uri: String, text: String, mimeType: String? = "text/plain") =
            ResourceContents(uri = uri, text = text, mimeType = mimeType)

        fun blob(uri: String, blobBase64: String, mimeType: String) =
            ResourceContents(uri = uri, blob = blobBase64, mimeType = mimeType)
    }
}
