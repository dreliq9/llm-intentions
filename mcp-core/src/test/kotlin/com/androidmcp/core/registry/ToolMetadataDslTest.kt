package com.androidmcp.core.registry

import com.androidmcp.core.protocol.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolMetadataDslTest {
    @Test fun dsl_builds_fully_populated_metadata() {
        val md = toolMetadata {
            destructive = true
            idempotent = false
            latencyClass = LatencyClass.SLOW
            mutation = MutationClass.MUTATING
            sensitiveData = SensitiveDataClass.COMMUNICATIONS
            confirmation = ConfirmationMode.ALWAYS
            openWorld = true
            permission("POST_NOTIFICATIONS")
            permission("VIBRATE")
            failureMode(pattern = "denied", hint = "grant perm")
            failureMode(exceptionType = "TimeoutException", hint = "retry smaller")
            example(intent = "post a simple note") { args ->
                args["title"] = "Hi"
                args["body"] = "test"
            }
        }
        assertTrue(md.destructive)
        assertEquals(false, md.idempotent)
        assertEquals(LatencyClass.SLOW, md.latencyClass)
        assertEquals(MutationClass.MUTATING, md.mutation)
        assertEquals(SensitiveDataClass.COMMUNICATIONS, md.sensitiveData)
        assertEquals(ConfirmationMode.ALWAYS, md.confirmation)
        assertEquals(true, md.openWorld)
        assertEquals(listOf("POST_NOTIFICATIONS", "VIBRATE"), md.permissions)
        assertEquals(2, md.failureModes.size)
        assertEquals(1, md.examples.size)
        assertEquals("post a simple note", md.examples[0].intent)
    }

    @Test fun dsl_defaults_match_ToolMetadata_defaults_and_are_conservative() {
        val md = toolMetadata {}
        assertEquals(ToolMetadata(), md)
        assertEquals(MutationClass.UNKNOWN, md.mutation)
        assertEquals(SensitiveDataClass.UNKNOWN, md.sensitiveData)
        assertEquals(ConfirmationMode.POLICY, md.confirmation)
    }

    @Test fun example_args_capture_string_number_boolean() {
        val md = toolMetadata {
            example(intent = "mixed types") { args ->
                args["str"] = "hello"
                args["num"] = 42
                args["dbl"] = 3.14
                args["bool"] = true
            }
        }
        val obj = md.examples[0].args
        assertEquals("hello", obj["str"]?.jsonPrimitive?.content)
        assertEquals(42, obj["num"]?.jsonPrimitive?.int)
        assertEquals(3.14, obj["dbl"]?.jsonPrimitive?.double)
        assertEquals(true, obj["bool"]?.jsonPrimitive?.boolean)
    }

    @Test fun failureMode_requires_pattern_or_exceptionType() {
        try {
            toolMetadata { failureMode(hint = "something") }
            error("should have thrown")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("pattern") == true || e.message?.contains("exceptionType") == true)
        }
    }
}
