package com.androidmcp.core.transport

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BearerTokenAuthenticatorTest {
    private val token = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFG"

    @Test
    fun `accepts exact bearer token`() {
        assertTrue(BearerTokenAuthenticator.matches(token, "Bearer $token"))
    }

    @Test
    fun `bearer scheme is case insensitive`() {
        assertTrue(BearerTokenAuthenticator.matches(token, "bearer $token"))
    }

    @Test
    fun `rejects missing authorization`() {
        assertFalse(BearerTokenAuthenticator.matches(token, null))
    }

    @Test
    fun `rejects wrong token`() {
        assertFalse(BearerTokenAuthenticator.matches(token, "Bearer ${token}x"))
    }

    @Test
    fun `rejects non bearer authorization`() {
        assertFalse(BearerTokenAuthenticator.matches(token, "Basic $token"))
    }

    @Test
    fun `rejects empty bearer credential`() {
        assertFalse(BearerTokenAuthenticator.matches(token, "Bearer   "))
    }
}
