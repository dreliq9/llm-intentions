package com.androidmcp.core.transport

import com.androidmcp.core.protocol.JsonRpcError
import com.androidmcp.core.protocol.JsonRpcRequest
import com.androidmcp.core.protocol.MCP_MODERN_PROTOCOL_VERSION
import com.androidmcp.core.protocol.SUPPORTED_MCP_PROTOCOL_VERSIONS
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.util.Base64

/**
 * Pure-JVM validation for MCP 2026-07-28 Streamable HTTP metadata.
 *
 * Header names passed to [validate] must already be normalized to lowercase. The
 * validator intentionally does not validate x-mcp-header tool parameters yet; the Hub
 * currently publishes no such annotations. Add schema-aware parameter validation before
 * any CapApp begins exposing x-mcp-header.
 */
object McpHttpRequestValidator {

    data class Failure(
        val statusCode: Int,
        val statusText: String,
        val errorCode: Int,
        val message: String,
        val data: JsonObject? = null,
    )

    data class Result(
        val modern: Boolean,
        val failure: Failure? = null,
    )

    fun validate(request: JsonRpcRequest, headers: Map<String, String>): Result {
        val headerVersion = headers[HEADER_PROTOCOL_VERSION]
        val meta = request.params?.get("_meta") as? JsonObject
        val bodyVersion = meta
            ?.get(META_PROTOCOL_VERSION)
            ?.jsonPrimitive
            ?.contentOrNull

        val modern = headerVersion == MCP_MODERN_PROTOCOL_VERSION ||
            bodyVersion == MCP_MODERN_PROTOCOL_VERSION ||
            request.method == "server/discover"

        if (!modern) {
            // Keep the deployed initialize-era path permissive. Once compatibility mode
            // becomes opt-in, legacy transport validation can be tightened separately.
            return Result(modern = false)
        }

        // Required per-request protocol fields are body semantics, not mirrored-header
        // semantics. Missing fields are Invalid params (-32602), not HeaderMismatch.
        if (meta == null) {
            return invalidParams("Modern MCP requests require params._meta")
        }
        if (bodyVersion == null) {
            return invalidParams("Missing params._meta.$META_PROTOCOL_VERSION")
        }
        if (meta[META_CLIENT_CAPABILITIES] !is JsonObject) {
            return invalidParams("Missing or invalid params._meta.$META_CLIENT_CAPABILITIES")
        }
        val clientInfo = meta[META_CLIENT_INFO]
        if (clientInfo != null && clientInfo !is JsonObject) {
            return invalidParams("params._meta.$META_CLIENT_INFO must be an object when provided")
        }

        if (headerVersion == null) {
            return headerMismatch("Missing required MCP-Protocol-Version header")
        }
        if (headerVersion != bodyVersion) {
            return headerMismatch(
                "MCP-Protocol-Version '$headerVersion' does not match body version '$bodyVersion'"
            )
        }

        if (bodyVersion !in SUPPORTED_MCP_PROTOCOL_VERSIONS) {
            return Result(
                modern = true,
                failure = Failure(
                    statusCode = 400,
                    statusText = "Bad Request",
                    errorCode = JsonRpcError.UNSUPPORTED_PROTOCOL_VERSION,
                    message = "Unsupported protocol version: $bodyVersion",
                    data = buildJsonObject {
                        put("supported", JsonArray(SUPPORTED_MCP_PROTOCOL_VERSIONS.map(::JsonPrimitive)))
                        put("requested", bodyVersion)
                    },
                ),
            )
        }

        if (bodyVersion != MCP_MODERN_PROTOCOL_VERSION) {
            // A request carrying a supported legacy version in per-request metadata is not
            // a valid modern request. Legacy clients use initialize-era semantics instead.
            return invalidParams("Per-request metadata does not select the modern MCP protocol")
        }

        val methodHeader = headers[HEADER_METHOD]
            ?: return headerMismatch("Missing required Mcp-Method header")
        if (methodHeader != request.method) {
            return headerMismatch(
                "Mcp-Method '$methodHeader' does not match body method '${request.method}'"
            )
        }

        if (request.method in NAME_REQUIRED_METHODS) {
            val expectedName = when (request.method) {
                "tools/call", "prompts/get" ->
                    request.params?.get("name")?.jsonPrimitive?.contentOrNull
                "resources/read" ->
                    request.params?.get("uri")?.jsonPrimitive?.contentOrNull
                else -> null
            }
            val rawNameHeader = headers[HEADER_NAME]
                ?: return headerMismatch("Missing required Mcp-Name header")
            val decodedName = decodeHeaderValue(rawNameHeader)
                ?: return headerMismatch("Mcp-Name contains malformed Base64 sentinel encoding")
            if (expectedName != null && decodedName != expectedName) {
                return headerMismatch(
                    "Mcp-Name '$decodedName' does not match body value '$expectedName'"
                )
            }
        }

        return Result(modern = true)
    }

    fun isAllowedLocalOrigin(origin: String?): Boolean {
        if (origin == null) return true
        return try {
            val uri = URI(origin)
            val host = uri.host?.lowercase() ?: return false
            val scheme = uri.scheme?.lowercase() ?: return false
            (scheme == "http" || scheme == "https") &&
                (host == "localhost" || host == "127.0.0.1" || host == "::1")
        } catch (_: Exception) {
            false
        }
    }

    fun decodeHeaderValue(value: String): String? {
        if (!value.startsWith(BASE64_PREFIX) || !value.endsWith(BASE64_SUFFIX)) return value
        val payload = value.substring(BASE64_PREFIX.length, value.length - BASE64_SUFFIX.length)
        return try {
            String(Base64.getDecoder().decode(payload), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun invalidParams(message: String): Result = Result(
        modern = true,
        failure = Failure(
            statusCode = 400,
            statusText = "Bad Request",
            errorCode = JsonRpcError.INVALID_PARAMS,
            message = message,
        ),
    )

    private fun headerMismatch(message: String): Result = Result(
        modern = true,
        failure = Failure(
            statusCode = 400,
            statusText = "Bad Request",
            errorCode = JsonRpcError.HEADER_MISMATCH,
            message = "Header mismatch: $message",
        ),
    )

    private const val HEADER_PROTOCOL_VERSION = "mcp-protocol-version"
    private const val HEADER_METHOD = "mcp-method"
    private const val HEADER_NAME = "mcp-name"

    private const val META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion"
    private const val META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo"
    private const val META_CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities"

    private val NAME_REQUIRED_METHODS = setOf("tools/call", "resources/read", "prompts/get")

    private const val BASE64_PREFIX = "=?base64?"
    private const val BASE64_SUFFIX = "?="
}
