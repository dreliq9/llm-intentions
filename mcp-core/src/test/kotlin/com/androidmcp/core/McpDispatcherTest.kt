package com.androidmcp.core

import com.androidmcp.core.protocol.*
import com.androidmcp.core.registry.McpToolDef
import com.androidmcp.core.registry.ToolRegistry
import com.androidmcp.core.registry.jsonSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class McpDispatcherTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun makeDispatcher(
        instructions: String? = null,
        block: ToolRegistry.() -> Unit = {}
    ): McpDispatcher {
        val registry = ToolRegistry().apply(block)
        return McpDispatcher(
            serverInfo = Implementation("TestServer", "1.0.0"),
            toolRegistry = registry,
            instructions = instructions
        )
    }

    private fun greetTool() = McpToolDef(
        info = ToolInfo(
            name = "greet",
            description = "Say hello",
            inputSchema = jsonSchema { string("name", "Name to greet") }
        ),
        handler = { args ->
            val name = args["name"]?.jsonPrimitive?.content ?: "World"
            ToolCallResult(content = listOf(ContentBlock.text("Hello, $name!")))
        }
    )

    // --- Initialize ---

    @Test
    fun `initialize returns protocol version and server info`() = runBlocking {
        val dispatcher = makeDispatcher(instructions = "Test instructions")
        val request = JsonRpcRequest(
            method = "initialize",
            params = buildJsonObject {
                put("protocolVersion", "2024-11-05")
                put("capabilities", buildJsonObject { })
            },
            id = JsonPrimitive(1)
        )

        val response = dispatcher.dispatch(request)!!
        assertNull(response.error)
        assertNotNull(response.result)

        val result = response.result!!.jsonObject
        assertEquals("2024-11-05", result["protocolVersion"]?.jsonPrimitive?.content)
        assertEquals("TestServer", result["serverInfo"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
        assertEquals("1.0.0", result["serverInfo"]?.jsonObject?.get("version")?.jsonPrimitive?.content)
        assertEquals("Test instructions", result["instructions"]?.jsonPrimitive?.content)
    }

    @Test
    fun `initialize reports tool capability when tools exist`() = runBlocking {
        val dispatcher = makeDispatcher { register(greetTool()) }
        val request = JsonRpcRequest(
            method = "initialize",
            params = buildJsonObject {
                put("protocolVersion", "2024-11-05")
            },
            id = JsonPrimitive(1)
        )

        val response = dispatcher.dispatch(request)!!
        val capabilities = response.result!!.jsonObject["capabilities"]!!.jsonObject
        assertNotNull(capabilities["tools"])
    }

    @Test
    fun `initialize omits tool capability when no tools`() = runBlocking {
        val dispatcher = makeDispatcher()
        val request = JsonRpcRequest(
            method = "initialize",
            params = buildJsonObject {
                put("protocolVersion", "2024-11-05")
            },
            id = JsonPrimitive(1)
        )

        val response = dispatcher.dispatch(request)!!
        val capabilities = response.result!!.jsonObject["capabilities"]!!.jsonObject
        // tools should be null (omitted) when no tools are registered
        assertTrue(
            capabilities["tools"] == null || capabilities["tools"] is JsonNull,
            "tools capability should be null when registry is empty"
        )
    }

    // --- Tools ---

    @Test
    fun `tools list returns registered tools`() = runBlocking {
        val dispatcher = makeDispatcher { register(greetTool()) }
        val request = JsonRpcRequest(method = "tools/list", id = JsonPrimitive(1))

        val response = dispatcher.dispatch(request)!!
        val tools = response.result!!.jsonObject["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        assertEquals("greet", tools[0].jsonObject["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `tools list is empty when no tools`() = runBlocking {
        val dispatcher = makeDispatcher()
        val request = JsonRpcRequest(method = "tools/list", id = JsonPrimitive(1))

        val response = dispatcher.dispatch(request)!!
        val tools = response.result!!.jsonObject["tools"]!!.jsonArray
        assertEquals(0, tools.size)
    }

    @Test
    fun `tools call executes handler`() = runBlocking {
        val dispatcher = makeDispatcher { register(greetTool()) }
        val request = JsonRpcRequest(
            method = "tools/call",
            params = buildJsonObject {
                put("name", "greet")
                put("arguments", buildJsonObject { put("name", "Android") })
            },
            id = JsonPrimitive(1)
        )

        val response = dispatcher.dispatch(request)!!
        assertNull(response.error)
        val content = response.result!!.jsonObject["content"]!!.jsonArray
        assertEquals("Hello, Android!", content[0].jsonObject["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `tools call with missing arguments uses empty object`() = runBlocking {
        val dispatcher = makeDispatcher { register(greetTool()) }
        val request = JsonRpcRequest(
            method = "tools/call",
            params = buildJsonObject { put("name", "greet") },
            id = JsonPrimitive(1)
        )

        val response = dispatcher.dispatch(request)!!
        assertNull(response.error)
        val content = response.result!!.jsonObject["content"]!!.jsonArray
        assertEquals("Hello, World!", content[0].jsonObject["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `tools call for unknown tool returns error`() = runBlocking {
        val dispatcher = makeDispatcher { register(greetTool()) }
        val request = JsonRpcRequest(
            method = "tools/call",
            params = buildJsonObject {
                put("name", "nonexistent")
                put("arguments", buildJsonObject { })
            },
            id = JsonPrimitive(1)
        )

        val response = dispatcher.dispatch(request)!!
        assertNotNull(response.error)
        assertEquals(JsonRpcError.METHOD_NOT_FOUND, response.error!!.code)
        assertTrue(response.error!!.message.contains("nonexistent"))
    }

    @Test
    fun `tools call without params returns error`() = runBlocking {
        val dispatcher = makeDispatcher { register(greetTool()) }
        val request = JsonRpcRequest(
            method = "tools/call",
            id = JsonPrimitive(1)
        )

        val response = dispatcher.dispatch(request)!!
        assertNotNull(response.error)
        assertEquals(JsonRpcError.INVALID_PARAMS, response.error!!.code)
    }

    // --- Error handling ---

    @Test
    fun `unknown method returns METHOD_NOT_FOUND`() = runBlocking {
        val dispatcher = makeDispatcher()
        val request = JsonRpcRequest(method = "nonexistent", id = JsonPrimitive(1))

        val response = dispatcher.dispatch(request)!!
        assertNotNull(response.error)
        assertEquals(JsonRpcError.METHOD_NOT_FOUND, response.error!!.code)
    }

    @Test
    fun `ping returns empty object`() = runBlocking {
        val dispatcher = makeDispatcher()
        val request = JsonRpcRequest(method = "ping", id = JsonPrimitive(1))

        val response = dispatcher.dispatch(request)!!
        assertNull(response.error)
        assertNotNull(response.result)
    }

    // --- Notifications (no id) ---

    @Test
    fun `notification returns null`() = runBlocking {
        val dispatcher = makeDispatcher()
        val request = JsonRpcRequest(
            method = "notifications/initialized",
            id = null
        )

        val response = dispatcher.dispatch(request)
        assertNull(response, "Notifications should return null (no response)")
    }

    // --- Response ID matching ---

    @Test
    fun `response id matches request id`() = runBlocking {
        val dispatcher = makeDispatcher()
        val request = JsonRpcRequest(method = "ping", id = JsonPrimitive(42))

        val response = dispatcher.dispatch(request)!!
        assertEquals(JsonPrimitive(42), response.id)
    }

    @Test
    fun `response id matches string request id`() = runBlocking {
        val dispatcher = makeDispatcher()
        val request = JsonRpcRequest(method = "ping", id = JsonPrimitive("abc-123"))

        val response = dispatcher.dispatch(request)!!
        assertEquals(JsonPrimitive("abc-123"), response.id)
    }

    // --- Mutable instructions ---

    @Test
    fun `instructions can be updated after creation`() = runBlocking {
        val dispatcher = makeDispatcher(instructions = "v1")

        // First initialize returns v1
        val req1 = JsonRpcRequest(
            method = "initialize",
            params = buildJsonObject { put("protocolVersion", "2024-11-05") },
            id = JsonPrimitive(1)
        )
        val resp1 = dispatcher.dispatch(req1)!!
        assertEquals("v1", resp1.result!!.jsonObject["instructions"]?.jsonPrimitive?.content)

        // Update instructions
        dispatcher.instructions = "v2 — new tools available"

        // Second initialize returns v2
        val req2 = JsonRpcRequest(
            method = "initialize",
            params = buildJsonObject { put("protocolVersion", "2024-11-05") },
            id = JsonPrimitive(2)
        )
        val resp2 = dispatcher.dispatch(req2)!!
        assertEquals("v2 — new tools available", resp2.result!!.jsonObject["instructions"]?.jsonPrimitive?.content)
    }

    // --- Tool handler exception ---

    @Test
    fun `tool handler exception returns internal error`() = runBlocking {
        val dispatcher = makeDispatcher {
            register(McpToolDef(
                info = ToolInfo("crasher", "Always crashes", buildJsonObject {}),
                handler = { throw RuntimeException("boom") }
            ))
        }

        val request = JsonRpcRequest(
            method = "tools/call",
            params = buildJsonObject {
                put("name", "crasher")
                put("arguments", buildJsonObject {})
            },
            id = JsonPrimitive(1)
        )

        val response = dispatcher.dispatch(request)!!
        assertNotNull(response.error)
        assertEquals(JsonRpcError.INTERNAL_ERROR, response.error!!.code)
        assertTrue(response.error!!.message.contains("boom"))
    }

    // --- Dynamic registry ---

    @Test
    fun `tools list reflects registry changes`() = runBlocking {
        val registry = ToolRegistry()
        val dispatcher = McpDispatcher(toolRegistry = registry)

        // Empty initially
        val req1 = JsonRpcRequest(method = "tools/list", id = JsonPrimitive(1))
        val resp1 = dispatcher.dispatch(req1)!!
        assertEquals(0, resp1.result!!.jsonObject["tools"]!!.jsonArray.size)

        // Add a tool
        registry.register(greetTool())

        val resp2 = dispatcher.dispatch(req1.copy(id = JsonPrimitive(2)))!!
        assertEquals(1, resp2.result!!.jsonObject["tools"]!!.jsonArray.size)

        // Remove it
        registry.clear()

        val resp3 = dispatcher.dispatch(req1.copy(id = JsonPrimitive(3)))!!
        assertEquals(0, resp3.result!!.jsonObject["tools"]!!.jsonArray.size)
    }
}
