package com.androidmcp.core.registry

import com.androidmcp.core.protocol.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class EnvelopeToolTest {

    @Test fun textTool_wraps_string_in_ok_envelope() = runBlocking {
        val reg = ToolRegistry()
        reg.textTool("greet", "say hi", jsonSchema {}) { _ -> "hello" }
        val def = reg.get("greet")!!
        val result = def.handler(buildJsonObject {})
        assertFalse(result.isError)
        val text = result.content.first().text ?: ""
        assertTrue(text.startsWith("OK: greet succeeded"), "expected OK prefix, got: $text")
        assertTrue(text.contains("hello"))
    }

    @Test fun envelopeTool_preserves_fail_status() = runBlocking {
        val reg = ToolRegistry()
        reg.envelopeTool("check", "validate", jsonSchema {}, metadata = ToolMetadata()) { _ ->
            Envelope.fail(summary = "input invalid", hint = "pass a non-empty string")
        }
        val result = reg.get("check")!!.handler(buildJsonObject {})
        assertTrue(result.isError)
        val text = result.content.first().text ?: ""
        assertTrue(text.startsWith("FAIL: input invalid"))
        assertTrue(text.contains("Hint: pass a non-empty string"))
    }

    @Test fun envelopeTool_metadata_is_retrievable_for_framework_error_handling() {
        val reg = ToolRegistry()
        val md = ToolMetadata(
            destructive = true,
            failureModes = listOf(FailureMode(pattern = "denied", hint = "grant perm")),
        )
        reg.envelopeTool("risky", "edits DB", jsonSchema {}, metadata = md) { Envelope.ok("done") }
        val def = reg.get("risky")!!
        assertEquals(true, def.metadata?.destructive)
        assertEquals(1, def.metadata?.failureModes?.size)
    }

    @Test fun textTool_metadata_retrievable() {
        val reg = ToolRegistry()
        val md = ToolMetadata(latencyClass = LatencyClass.SLOW)
        reg.textTool("slow", "slow op", jsonSchema {}, metadata = md) { "ok" }
        val def = reg.get("slow")!!
        assertEquals(LatencyClass.SLOW, def.metadata?.latencyClass)
    }
}
