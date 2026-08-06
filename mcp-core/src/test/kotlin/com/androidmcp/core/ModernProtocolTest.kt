package com.androidmcp.core

import com.androidmcp.core.protocol.*
import com.androidmcp.core.registry.McpToolDef
import com.androidmcp.core.registry.ToolRegistry
import com.androidmcp.core.registry.jsonSchema
import com.androidmcp.core.registry.textTool
import com.androidmcp.core.registry.toolMetadata
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModernProtocolTest {

    private fun modernMeta(): JsonObject = buildJsonObject {
        put("io.modelcontextprotocol/protocolVersion", MCP_MODERN_PROTOCOL_VERSION)
        put("io.modelcontextprotocol/clientInfo", buildJsonObject {
            put("name", "test-client")
            put("version", "1.0.0")
        })
        put("io.modelcontextprotocol/clientCapabilities", buildJsonObject { })
    }

    @Test
    fun `server discover advertises modern and legacy eras`() = runBlocking {
        val dispatcher = McpDispatcher(
            serverInfo = Implementation("TestServer", "2.0.0"),
            instructions = "Test instructions",
        )
        val response = dispatcher.dispatch(JsonRpcRequest(
            method = "server/discover",
            params = buildJsonObject { put("_meta", modernMeta()) },
            id = JsonPrimitive("discover"),
        ))!!

        val result = response.result!!.jsonObject
        assertEquals(MCP_RESULT_COMPLETE, result["resultType"]!!.jsonPrimitive.content)
        val supported = result["supportedVersions"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf(MCP_MODERN_PROTOCOL_VERSION, MCP_LEGACY_PROTOCOL_VERSION), supported)
        assertEquals(MCP_CACHE_SCOPE_PRIVATE, result["cacheScope"]!!.jsonPrimitive.content)
        assertTrue(result["ttlMs"]!!.jsonPrimitive.long > 0)

        val serverInfo = result["_meta"]!!.jsonObject["io.modelcontextprotocol/serverInfo"]!!.jsonObject
        assertEquals("TestServer", serverInfo["name"]!!.jsonPrimitive.content)
        assertEquals("2.0.0", serverInfo["version"]!!.jsonPrimitive.content)
    }

    @Test
    fun `modern tool list is deterministic cacheable and stamped`() = runBlocking {
        val registry = ToolRegistry()
        registry.register(McpToolDef(
            info = ToolInfo("zeta", "z", buildJsonObject { put("type", "object") }),
            handler = { ToolCallResult(content = listOf(ContentBlock.text("z"))) },
        ))
        registry.register(McpToolDef(
            info = ToolInfo("alpha", "a", buildJsonObject { put("type", "object") }),
            handler = { ToolCallResult(content = listOf(ContentBlock.text("a"))) },
        ))
        val dispatcher = McpDispatcher(toolRegistry = registry)

        val response = dispatcher.dispatch(JsonRpcRequest(
            method = "tools/list",
            params = buildJsonObject { put("_meta", modernMeta()) },
            id = JsonPrimitive(1),
        ))!!
        val result = response.result!!.jsonObject

        assertEquals(MCP_RESULT_COMPLETE, result["resultType"]!!.jsonPrimitive.content)
        assertEquals(listOf("alpha", "zeta"), result["tools"]!!.jsonArray.map {
            it.jsonObject["name"]!!.jsonPrimitive.content
        })
        assertTrue(result["ttlMs"]!!.jsonPrimitive.long > 0)
        assertNotNull(result["_meta"]!!.jsonObject["io.modelcontextprotocol/serverInfo"])
    }

    @Test
    fun `modern unknown tool is invalid params not unknown rpc method`() = runBlocking {
        val dispatcher = McpDispatcher(toolRegistry = ToolRegistry())
        val response = dispatcher.dispatch(JsonRpcRequest(
            method = "tools/call",
            params = buildJsonObject {
                put("name", "missing")
                put("arguments", buildJsonObject { })
                put("_meta", modernMeta())
            },
            id = JsonPrimitive(1),
        ))!!

        assertEquals(JsonRpcError.INVALID_PARAMS, response.error!!.code)
    }

    @Test
    fun `textTool maps rich metadata to standard MCP annotations`() {
        val registry = ToolRegistry()
        registry.textTool(
            name = "dangerous_write",
            description = "Test mutation",
            params = jsonSchema { },
            metadata = toolMetadata {
                destructive = true
                idempotent = false
            },
        ) { "ok" }

        val tool = registry.list().single()
        val annotations = assertNotNull(tool.annotations)
        assertTrue(annotations.destructiveHint == true)
        assertFalse(annotations.idempotentHint == true)
    }
}
