package com.androidmcp.core.async

import com.androidmcp.core.McpServer
import com.androidmcp.core.protocol.ContentBlock
import com.androidmcp.core.protocol.JsonRpcRequest
import com.androidmcp.core.protocol.ToolCallResult
import com.androidmcp.core.registry.jsonSchema
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.*

class JobManagerTest {

    private var jobManager: JobManager? = null

    private fun makeJobManager(toolDelayMs: Long = 0): JobManager {
        val dispatcher = McpServer("TestServer") {
            tool(
                name = "fast",
                description = "Returns immediately",
                params = jsonSchema { }
            ) { _ ->
                ToolCallResult(content = listOf(ContentBlock.text("done")))
            }

            tool(
                name = "slow",
                description = "Takes a while",
                params = jsonSchema {
                    integer("delay_ms", "How long to wait", required = false)
                }
            ) { args ->
                val ms = args["delay_ms"]?.jsonPrimitive?.intOrNull ?: toolDelayMs.toInt()
                delay(ms.toLong())
                ToolCallResult(content = listOf(ContentBlock.text("finished after ${ms}ms")))
            }

            tool(
                name = "crasher",
                description = "Always throws",
                params = jsonSchema { }
            ) { _ ->
                throw RuntimeException("boom")
            }
        }
        return JobManager(dispatcher).also { jobManager = it }
    }

    @AfterEach
    fun cleanup() {
        jobManager?.shutdown()
    }

    @Test
    fun `submit returns a job ID`() {
        val jm = makeJobManager()
        val request = JsonRpcRequest(
            method = "tools/call",
            params = buildJsonObject {
                put("name", "fast")
                put("arguments", buildJsonObject { })
            },
            id = JsonPrimitive(1)
        )

        val jobId = jm.submit(request)
        assertNotNull(jobId)
        assertTrue(jobId.isNotEmpty())
    }

    @Test
    fun `fast job completes quickly`() {
        val jm = makeJobManager()
        val request = JsonRpcRequest(
            method = "tools/call",
            params = buildJsonObject {
                put("name", "fast")
                put("arguments", buildJsonObject { })
            },
            id = JsonPrimitive(1)
        )

        val jobId = jm.submit(request)

        // Give it a moment to complete
        Thread.sleep(200)

        val pollResult = jm.poll(jobId)
        val pollData = Json.parseToJsonElement(pollResult).jsonObject

        assertEquals("completed", pollData["status"]?.jsonPrimitive?.content)
        assertNotNull(pollData["response"])
    }

    @Test
    fun `slow job shows running then completed`() {
        val jm = makeJobManager(toolDelayMs = 500)
        val request = JsonRpcRequest(
            method = "tools/call",
            params = buildJsonObject {
                put("name", "slow")
                put("arguments", buildJsonObject { put("delay_ms", 500) })
            },
            id = JsonPrimitive(2)
        )

        val jobId = jm.submit(request)

        // Check immediately — should be running
        Thread.sleep(50)
        val earlyPoll = Json.parseToJsonElement(jm.poll(jobId)).jsonObject
        assertEquals("running", earlyPoll["status"]?.jsonPrimitive?.content)

        // Wait for completion
        Thread.sleep(600)
        val latePoll = Json.parseToJsonElement(jm.poll(jobId)).jsonObject
        assertEquals("completed", latePoll["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `cancel stops a running job`() {
        val jm = makeJobManager(toolDelayMs = 5000)
        val request = JsonRpcRequest(
            method = "tools/call",
            params = buildJsonObject {
                put("name", "slow")
                put("arguments", buildJsonObject { put("delay_ms", 5000) })
            },
            id = JsonPrimitive(3)
        )

        val jobId = jm.submit(request)
        Thread.sleep(200) // Let the coroutine start

        val cancelled = jm.cancel(jobId)
        assertTrue(cancelled)

        Thread.sleep(200)
        val pollData = Json.parseToJsonElement(jm.poll(jobId)).jsonObject
        assertEquals("cancelled", pollData["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `cancel nonexistent job returns false`() {
        val jm = makeJobManager()
        assertFalse(jm.cancel("nonexistent"))
    }

    @Test
    fun `poll nonexistent job returns not_found`() {
        val jm = makeJobManager()
        val result = Json.parseToJsonElement(jm.poll("nonexistent")).jsonObject
        assertEquals("not_found", result["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `failed job reports error`() {
        val jm = makeJobManager()
        val request = JsonRpcRequest(
            method = "tools/call",
            params = buildJsonObject {
                put("name", "crasher")
                put("arguments", buildJsonObject { })
            },
            id = JsonPrimitive(4)
        )

        val jobId = jm.submit(request)
        Thread.sleep(500)

        val result = Json.parseToJsonElement(jm.poll(jobId)).jsonObject
        assertEquals("failed", result["status"]?.jsonPrimitive?.content)
        assertNotNull(result["response"])
    }

    @Test
    fun `list shows active jobs`() {
        val jm = makeJobManager(toolDelayMs = 2000)
        val request = JsonRpcRequest(
            method = "tools/call",
            params = buildJsonObject {
                put("name", "slow")
                put("arguments", buildJsonObject { put("delay_ms", 2000) })
            },
            id = JsonPrimitive(5)
        )

        jm.submit(request)
        jm.submit(request)

        Thread.sleep(100)
        val jobs = jm.list()
        assertEquals(2, jobs.size)

        // Clean up
        jobs.forEach { jm.cancel(it.id) }
    }

    @Test
    fun `completed response includes original request ID`() {
        val jm = makeJobManager()
        val request = JsonRpcRequest(
            method = "tools/call",
            params = buildJsonObject {
                put("name", "fast")
                put("arguments", buildJsonObject { })
            },
            id = JsonPrimitive(42)
        )

        val jobId = jm.submit(request)
        Thread.sleep(200)

        val pollData = Json.parseToJsonElement(jm.poll(jobId)).jsonObject
        val response = pollData["response"]!!.jsonObject
        assertEquals(42, response["id"]?.jsonPrimitive?.int)
    }
}
