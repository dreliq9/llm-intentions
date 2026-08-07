package com.androidmcp.hub.security

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * App-private bearer credential for the localhost MCP developer endpoint.
 *
 * The token lives in noBackupFilesDir so it is neither world-readable nor silently restored onto
 * another device. The relay will use its own device identity; this token is only for explicit local
 * HTTP clients such as CLI agents.
 */
object HubAccessTokenStore {
    private val lock = Any()
    private val random = SecureRandom()

    fun getOrCreate(context: Context): String = synchronized(lock) {
        val file = tokenFile(context)
        val existing = if (file.exists()) file.readText(Charsets.UTF_8).trim() else ""
        if (existing.length >= MIN_TOKEN_LENGTH) return@synchronized existing

        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        file.parentFile?.mkdirs()
        file.writeText(token, Charsets.UTF_8)
        token
    }

    fun rotate(context: Context): String = synchronized(lock) {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        tokenFile(context).writeText(token, Charsets.UTF_8)
        token
    }

    fun bearerHeader(context: Context): String = "Bearer ${getOrCreate(context)}"

    fun matchesBearer(expectedToken: String, authorizationHeader: String?): Boolean {
        val candidate = authorizationHeader
            ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
            ?.substring(BEARER_PREFIX.length)
            ?.trim()
            ?: return false

        val expectedBytes = expectedToken.toByteArray(Charsets.UTF_8)
        val candidateBytes = candidate.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(expectedBytes, candidateBytes)
    }

    private fun tokenFile(context: Context): File =
        File(context.noBackupFilesDir, TOKEN_FILE_NAME)

    private const val TOKEN_FILE_NAME = "local_mcp_access_token"
    private const val TOKEN_BYTES = 32
    private const val MIN_TOKEN_LENGTH = 32
    private const val BEARER_PREFIX = "Bearer "
}
