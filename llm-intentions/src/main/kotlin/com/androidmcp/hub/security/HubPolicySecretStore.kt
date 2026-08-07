package com.androidmcp.hub.security

import android.content.Context
import java.io.File
import java.security.SecureRandom
import java.util.Base64

/** App-private key material for HMAC-authenticating MCP confirmation requestState. */
object HubPolicySecretStore {
    private val lock = Any()
    private val random = SecureRandom()

    @Volatile
    private var cached: ByteArray? = null

    fun getOrCreate(context: Context): ByteArray {
        cached?.let { return it.copyOf() }

        return synchronized(lock) {
            cached?.let { return@synchronized it.copyOf() }

            val file = File(context.noBackupFilesDir, FILE_NAME)
            val existing = if (file.exists()) {
                try {
                    Base64.getUrlDecoder().decode(file.readText(Charsets.UTF_8).trim())
                } catch (_: Exception) {
                    ByteArray(0)
                }
            } else ByteArray(0)

            val secret = if (existing.size >= SECRET_BYTES) {
                existing
            } else {
                ByteArray(SECRET_BYTES).also { bytes ->
                    random.nextBytes(bytes)
                    file.parentFile?.mkdirs()
                    file.writeText(
                        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
                        Charsets.UTF_8,
                    )
                }
            }
            cached = secret.copyOf()
            secret.copyOf()
        }
    }

    private const val FILE_NAME = "policy_confirmation_hmac_secret_v1"
    private const val SECRET_BYTES = 32
}
