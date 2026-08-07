package com.androidmcp.core

import com.androidmcp.core.policy.InvocationOrigin
import com.androidmcp.core.policy.ToolAuthorizationOutcome
import com.androidmcp.core.policy.ToolCallAuthorizer
import com.androidmcp.core.policy.ToolInvocationSecurityContext
import com.androidmcp.core.protocol.ContentBlock
import com.androidmcp.core.protocol.InputRequest
import com.androidmcp.core.protocol.InputRequiredResult
import com.androidmcp.core.protocol.MCP_MODERN_PROTOCOL_VERSION
import com.androidmcp.core.protocol.MCP_RESULT_INPUT_REQUIRED
import com.androidmcp.core.protocol.ToolCallResult
import com.androidmcp.core.protocol.ToolInfo
import com.androidmcp.core.registry.McpToolDef
import com.androidmcp.core.registry.ToolRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolAuthorizationDispatcherTest {

    private fun modernParams(name: String = "danger"): kotlinx.serialization.json.JsonObject =
        buildJsonObject {
            put("name", name)
            put("arguments", buildJsonObject { put("value", 1) })
            put("_meta", buildJsonObject {
                put("io.modelcontextprotocol/protocolVersion", MCP_MODERN_PROTOCOL_VERSION)
                put("io.modelcontextprotocol/clientCapabilities", buildJsonObject { })
            })
        }

    private val remoteContext = ToolInvocationSecurityContext(
        origin = InvocationOrigin.REMOTE_PROVIDER,
        principalId = "relay:user-123",
    )

    private fun registry(counter: AtomicInteger): ToolRegistry = ToolRegistry().apply {
        register(McpToolDef(
            info = ToolInfo(
                name = "danger",
                description = "test tool",
                inputSchema = buildJsonObject { put("type", "object") },
            ),
            handler = {
                counter.incrementAndGet()
                ToolCallResult(content = listOf(ContentBlock.text("executed")))
            },
        ))
    }

    @Test
    fun `deny outcome prevents handler execution`() = runBlocking {
        val calls = AtomicInteger()
        val dispatcher = McpDispatcher(
            toolRegistry = registry(calls),
            toolAuthorizer = ToolCallAuthorizer {
                ToolAuthorizationOutcome.Deny("not granted")
            },
        )

        val response = dispatcher.dispatch(
            com.androidmcp.core.protocol.JsonRpcRequest(
                method = "tools/call",
                params = modernParams(),
                id = JsonPrimitive(1),
            ),
            remoteContext,
        )!!

        assertEquals(0, calls.get())
        val result = response.result!!.jsonObject
        assertTrue(result["isError"]!!.jsonPrimitive.boolean)
        assertEquals("denied", result["structuredContent"]!!.jsonObject["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `missing trusted transport context fails closed`() = runBlocking {
        val calls = AtomicInteger()
        val dispatcher = McpDispatcher(
            toolRegistry = registry(calls),
            toolAuthorizer = ToolCallAuthorizer { ToolAuthorizationOutcome.Allow },
        )

        val response = dispatcher.dispatch(
            com.androidmcp.core.protocol.JsonRpcRequest(
                method = "tools/call",
                params = modernParams(),
                id = JsonPrimitive(1),
            )
        )!!

        assertEquals(0, calls.get())
        assertTrue(response.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `input required returns before handler and preserves result type`() = runBlocking {
        val calls = AtomicInteger()
        val dispatcher = McpDispatcher(
            toolRegistry = registry(calls),
            toolAuthorizer = ToolCallAuthorizer { request ->
                assertTrue(request.supportsInputRequired)
                ToolAuthorizationOutcome.InputRequired(
                    InputRequiredResult(
                        inputRequests = mapOf(
                            "confirm" to InputRequest(
                                method = "elicitation/create",
                                params = buildJsonObject {
                                    put("mode", "form")
                                    put("message", "Approve?")
                                    put("requestedSchema", buildJsonObject {
                                        put("type", "object")
                                    })
                                },
                            )
                        ),
                        requestState = "opaque-state",
                    )
                )
            },
        )

        val response = dispatcher.dispatch(
            com.androidmcp.core.protocol.JsonRpcRequest(
                method = "tools/call",
                params = modernParams(),
                id = JsonPrimitive(1),
            ),
            remoteContext,
        )!!

        assertEquals(0, calls.get())
        val result = response.result!!.jsonObject
        assertEquals(MCP_RESULT_INPUT_REQUIRED, result["resultType"]!!.jsonPrimitive.content)
        assertEquals("opaque-state", result["requestState"]!!.jsonPrimitive.content)
    }

    @Test
    fun `allow outcome executes handler once`() = runBlocking {
        val calls = AtomicInteger()
        val dispatcher = McpDispatcher(
            toolRegistry = registry(calls),
            toolAuthorizer = ToolCallAuthorizer { ToolAuthorizationOutcome.Allow },
        )

        val response = dispatcher.dispatch(
            com.androidmcp.core.protocol.JsonRpcRequest(
                method = "tools/call",
                params = modernParams(),
                id = JsonPrimitive(1),
            ),
            remoteContext,
        )!!

        assertEquals(1, calls.get())
        assertFalse(response.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
    }
}
