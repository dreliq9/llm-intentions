package com.androidmcp.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class EnvelopeTest {

    @Test fun ok_has_empty_hint() {
        val env = Envelope.ok(summary = "clipboard updated")
        assertEquals(EnvelopeStatus.OK, env.status)
        assertEquals("clipboard updated", env.summary)
        assertEquals("", env.hint)
    }

    @Test fun fail_carries_hint() {
        val env = Envelope.fail(summary = "permission denied", hint = "grant screen recording")
        assertEquals(EnvelopeStatus.FAIL, env.status)
        assertEquals("grant screen recording", env.hint)
    }

    @Test fun warn_carries_hint_and_partial_data() {
        val env = Envelope.warn(summary = "partial", hint = "3 of 5", data = buildJsonObject { put("count", 3) })
        assertEquals(EnvelopeStatus.WARN, env.status)
    }

    @Test fun failureMode_pattern_matches_stderr_and_injects_hint() {
        val manifest = ToolMetadata(
            failureModes = listOf(
                FailureMode(pattern = "not authorized", hint = "Grant POST_NOTIFICATIONS permission.")
            )
        )
        val env = Envelope.fromException(
            toolName = "notify",
            metadata = manifest,
            exception = SecurityException("not authorized: POST_NOTIFICATIONS"),
        )
        assertEquals(EnvelopeStatus.FAIL, env.status)
        assertEquals("Grant POST_NOTIFICATIONS permission.", env.hint)
    }

    @Test fun failureMode_exceptionType_matches() {
        val manifest = ToolMetadata(
            failureModes = listOf(
                FailureMode(exceptionType = "TimeoutException", hint = "Retry with smaller scope.")
            )
        )
        val env = Envelope.fromException(
            toolName = "slow_tool",
            metadata = manifest,
            exception = java.util.concurrent.TimeoutException("timed out after 30s"),
        )
        assertEquals("Retry with smaller scope.", env.hint)
    }

    @Test fun render_ok_starts_with_prefix_and_includes_data() {
        val env = Envelope.ok(summary = "found 3 items", data = buildJsonObject { put("count", 3) })
        val text = env.renderText()
        assertTrue(text.startsWith("OK: found 3 items"))
        assertTrue(text.contains("\"count\": 3") || text.contains("\"count\":3"))
    }

    @Test fun render_fail_includes_hint_line() {
        val env = Envelope.fail(summary = "denied", hint = "grant perm")
        val text = env.renderText()
        assertTrue(text.startsWith("FAIL: denied"))
        assertTrue(text.contains("Hint: grant perm"))
    }

    @Test fun render_ok_omits_raw_block() {
        val env = Envelope.ok(summary = "done", raw = buildJsonObject { put("stdout", "hi") })
        val text = env.renderText()
        assertTrue(!text.contains("Raw:"))
    }

    @Test fun render_fail_includes_raw_block_when_present() {
        val env = Envelope.fail(
            summary = "bad",
            hint = "",
            raw = buildJsonObject { put("stderr", "boom") },
        )
        val text = env.renderText()
        assertTrue(text.contains("Raw:"))
        assertTrue(text.contains("boom"))
    }
}
