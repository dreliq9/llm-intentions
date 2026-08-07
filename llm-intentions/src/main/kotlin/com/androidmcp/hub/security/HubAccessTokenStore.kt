package com.androidmcp.hub.security

import android.content.Context
import com.androidmcp.core.transport.BearerTokenAuthenticator
import java.io.File
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

    @Volatile
    private var cachedToken: String? = null

    fun getOrCreate(context: Context): String {
        cachedToken?.takeIf { it.length >= MIN_TOKEN_LENGTH }?.let { return it }

        return synchronized(lock) {
            cachedToken?.takeIf { it.length >= MIN_TOKEN_LENGTH }?.let {
                return@synchronized it
            }

            val file = tokenFile(context)
            val existing = if (file.exists()) file.readText(Charsets.UTF_8).trim() else ""
            val token = if (existing.length >= MIN_TOKEN_LENGTH) {
                existing
            } else {
                newToken().also { generated ->
                    file.parentFile?.mkdirs()
                    file.writeText(generated, Charsets.UTF_8)
                }
            }
            cachedToken = token
            token
        }
    }

    fun rotate(context: Context): String = synchronized(lock) {
        val token = newToken()
        val file = tokenFile(context)
        file.parentFile?.mkdirs()
        file.writeText(token, Charsets.UTF_8)
        cachedToken = token
        token
    }

    fun bearerHeader(context: Context): String = "Bearer ${getOrCreate(context)}"

    fun matchesBearer(expectedToken: String, authorizationHeader: String?): Boolean =
        BearerTokenAuthenticator.matches(expectedToken, authorizationHeader)

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun tokenFile(context: Context): File =
        File(context.noBackupFilesDir, TOKEN_FILE_NAME)

    private const val TOKEN_FILE_NAME = "local_mcp_access_token"
    private const val TOKEN_BYTES = 32
    private const val MIN_TOKEN_LENGTH = 32
}
