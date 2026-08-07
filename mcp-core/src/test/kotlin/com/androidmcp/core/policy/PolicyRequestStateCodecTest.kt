package com.androidmcp.core.policy

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PolicyRequestStateCodecTest {
    private val secret = ByteArray(32) { index -> (index + 1).toByte() }
    private var now = 1_800_000_000_000L
    private val codec = PolicyRequestStateCodec(
        secret = secret,
        nowMs = { now },
        ttlMs = 60_000L,
    )

    private fun argsA() = buildJsonObject {
        put("message", "hello")
        put("count", 2)
    }

    @Test
    fun `issued state verifies for same principal tool and arguments`() {
        val issued = codec.issue("provider:user-1", "notify.post", argsA())
        val result = codec.verify(
            issued.token,
            "provider:user-1",
            "notify.post",
            argsA(),
        )

        val valid = assertIs<PolicyRequestStateCodec.Verification.Valid>(result)
        assertEquals(issued.state.nonce, valid.state.nonce)
    }

    @Test
    fun `argument object key order does not change binding`() {
        val issued = codec.issue("p", "tool", argsA())
        val reordered = buildJsonObject {
            put("count", 2)
            put("message", "hello")
        }

        assertIs<PolicyRequestStateCodec.Verification.Valid>(
            codec.verify(issued.token, "p", "tool", reordered)
        )
    }

    @Test
    fun `changed arguments are rejected`() {
        val issued = codec.issue("p", "tool", argsA())
        val changed = buildJsonObject {
            put("message", "goodbye")
            put("count", 2)
        }

        val invalid = assertIs<PolicyRequestStateCodec.Verification.Invalid>(
            codec.verify(issued.token, "p", "tool", changed)
        )
        assertTrue(invalid.reason.contains("arguments"))
    }

    @Test
    fun `principal transplant is rejected`() {
        val issued = codec.issue("provider:alice", "tool", argsA())
        val invalid = assertIs<PolicyRequestStateCodec.Verification.Invalid>(
            codec.verify(issued.token, "provider:bob", "tool", argsA())
        )
        assertTrue(invalid.reason.contains("principal"))
    }

    @Test
    fun `tool transplant is rejected`() {
        val issued = codec.issue("p", "contacts.delete", argsA())
        val invalid = assertIs<PolicyRequestStateCodec.Verification.Invalid>(
            codec.verify(issued.token, "p", "files.delete", argsA())
        )
        assertTrue(invalid.reason.contains("tool"))
    }

    @Test
    fun `tampered state is rejected`() {
        val issued = codec.issue("p", "tool", argsA())
        val first = if (issued.token.first() == 'A') 'B' else 'A'
        val tampered = first + issued.token.substring(1)

        assertIs<PolicyRequestStateCodec.Verification.Invalid>(
            codec.verify(tampered, "p", "tool", argsA())
        )
    }

    @Test
    fun `expired state is rejected`() {
        val issued = codec.issue("p", "tool", argsA())
        now += 60_001L

        val invalid = assertIs<PolicyRequestStateCodec.Verification.Invalid>(
            codec.verify(issued.token, "p", "tool", argsA())
        )
        assertEquals("requestState expired", invalid.reason)
    }
}
