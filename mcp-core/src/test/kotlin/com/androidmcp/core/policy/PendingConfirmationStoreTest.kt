package com.androidmcp.core.policy

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingConfirmationStoreTest {
    @Test
    fun `overflow evicts earliest expiry and fails closed`() {
        var now = 1_000L
        val store = InMemoryPendingConfirmationStore(
            maxEntries = 16,
            wallClockMs = { now },
        )

        for (i in 0 until 16) {
            store.register("nonce-$i", expiresAtMs = 10_000L + i)
        }
        store.register("nonce-new", expiresAtMs = 20_000L)

        assertFalse(store.consume("nonce-0", now))
        assertTrue(store.consume("nonce-new", now))
    }

    @Test
    fun `expired nonce cannot be consumed`() {
        var now = 1_000L
        val store = InMemoryPendingConfirmationStore(
            maxEntries = 16,
            wallClockMs = { now },
        )
        store.register("nonce", expiresAtMs = 2_000L)
        now = 2_001L
        assertFalse(store.consume("nonce", now))
    }
}
