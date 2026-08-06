package com.androidmcp.core.transport

import com.androidmcp.core.protocol.JsonRpcError
import com.androidmcp.core.protocol.JsonRpcRequest
import com.androidmcp.core.protocol.MCP_MODERN_PROTOCOL_VERSION
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpHttpRequestValidatorTest {

    private fun modernRequest(
        method: String = "tools/call",
        name: String? = "echo",
        includeCapabilities: Boolean = true,
    ): JsonRpcRequest = JsonRpcRequest(
        method = method,
        params = buildJsonObject {
            if (name != null) put("name", name)
            put("_meta", buildJsonObject {
                put("io.modelcontextprotocol/protocolVersion", MCP_MODERN_PROTOCOL_VERSION)
                put("io.modelcontextprotocol/clientInfo", buildJsonObject {
                    put("name", "test-client")
                    put("version", "1.0.0")
                })
                if (includeCapabilities) {
                    put("io.modelcontextprotocol/clientCapabilities", buildJsonObject { })
                }
            })
        },
        id = JsonPrimitive(1),
    )

    @Test
    fun `valid modern tool call passes mirrored header validation`() {
        val result = McpHttpRequestValidator.validate(
            modernRequest(),
            mapOf(
                "mcp-protocol-version" to MCP_MODERN_PROTOCOL_VERSION,
                "mcp-method" to "tools/call",
                "mcp-name" to "echo",
            ),
        )

        assertTrue(result.modern)
        assertNull(result.failure)
    }

    @Test
    fun `missing client capabilities is invalid params`() {
        val result = McpHttpRequestValidator.validate(
            modernRequest(includeCapabilities = false),
            mapOf(
                "mcp-protocol-version" to MCP_MODERN_PROTOCOL_VERSION,
                "mcp-method" to "tools/call",
                "mcp-name" to "echo",
            ),
        )

        assertEquals(400, result.failure!!.statusCode)
        assertEquals(JsonRpcError.INVALID_PARAMS, result.failure!!.errorCode)
    }

    @Test
    fun `header body method mismatch is rejected`() {
        val result = McpHttpRequestValidator.validate(
            modernRequest(),
            mapOf(
                "mcp-protocol-version" to MCP_MODERN_PROTOCOL_VERSION,
                "mcp-method" to "resources/read",
                "mcp-name" to "echo",
            ),
        )

        assertEquals(JsonRpcError.HEADER_MISMATCH, result.failure!!.errorCode)
    }

    @Test
    fun `base64 encoded Mcp Name is decoded before comparison`() {
        val encoded = "=?base64?SGVsbG8sIOS4lueVjA==?="
        val result = McpHttpRequestValidator.validate(
            modernRequest(name = "Hello, 世界"),
            mapOf(
                "mcp-protocol-version" to MCP_MODERN_PROTOCOL_VERSION,
                "mcp-method" to "tools/call",
                "mcp-name" to encoded,
            ),
        )

        assertNull(result.failure)
    }

    @Test
    fun `non loopback browser origins are rejected`() {
        assertFalse(McpHttpRequestValidator.isAllowedLocalOrigin("https://evil.example"))
        assertTrue(McpHttpRequestValidator.isAllowedLocalOrigin("http://127.0.0.1:8379"))
        assertTrue(McpHttpRequestValidator.isAllowedLocalOrigin("http://localhost:8379"))
        assertTrue(McpHttpRequestValidator.isAllowedLocalOrigin(null))
    }

    @Test
    fun `legacy request remains on compatibility path`() {
        val result = McpHttpRequestValidator.validate(
            JsonRpcRequest(method = "initialize", params = buildJsonObject { }, id = JsonPrimitive(1)),
            emptyMap(),
        )

        assertFalse(result.modern)
        assertNull(result.failure)
    }
}
