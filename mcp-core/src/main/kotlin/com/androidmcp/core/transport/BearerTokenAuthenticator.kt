package com.androidmcp.core.transport

import java.security.MessageDigest

/** Pure-JVM bearer verification used by the Android localhost MCP transport. */
object BearerTokenAuthenticator {
    fun matches(expectedToken: String, authorizationHeader: String?): Boolean {
        val candidate = authorizationHeader
            ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
            ?.substring(BEARER_PREFIX.length)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return false

        val expectedBytes = expectedToken.toByteArray(Charsets.UTF_8)
        val candidateBytes = candidate.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(expectedBytes, candidateBytes)
    }

    private const val BEARER_PREFIX = "Bearer "
}
