package com.androidmcp.hub.relay

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI

@Serializable
data class RelayEnrollment(
    val relayBaseUrl: String,
    val deviceId: String,
    val accountId: String,
    val deviceKeyId: String,
    val enabled: Boolean = false,
)

/**
 * Stores only non-secret relay enrollment metadata in noBackupFilesDir.
 *
 * The enrollment token is never persisted. The private device key lives in AndroidKeyStore.
 * Keeping the device ID/config out of backup also avoids restoring an enrollment onto a device
 * where the non-exportable private key does not exist.
 */
class RelayEnrollmentStore(context: Context) {
    private val file = File(context.applicationContext.noBackupFilesDir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    fun load(): RelayEnrollment? = synchronized(lock) {
        if (!file.exists()) return@synchronized null
        runCatching { json.decodeFromString<RelayEnrollment>(file.readText(Charsets.UTF_8)) }
            .getOrNull()
    }

    fun save(enrollment: RelayEnrollment) = synchronized(lock) {
        requireSecureRelayBaseUrl(enrollment.relayBaseUrl)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeText(json.encodeToString(RelayEnrollment.serializer(), enrollment), Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            temp.delete()
            error("Unable to atomically save relay enrollment")
        }
    }

    fun setEnabled(enabled: Boolean): RelayEnrollment? = synchronized(lock) {
        val current = load() ?: return@synchronized null
        val updated = current.copy(enabled = enabled)
        save(updated)
        updated
    }

    fun clear() = synchronized(lock) {
        file.delete()
        File(file.parentFile, "$FILE_NAME.tmp").delete()
    }

    companion object {
        private const val FILE_NAME = "relay_enrollment_v1.json"

        fun requireSecureRelayBaseUrl(value: String): URI {
            val uri = URI(value.trim())
            require(uri.scheme.equals("https", ignoreCase = true)) {
                "Relay URL must use https://"
            }
            require(uri.host != null && uri.userInfo == null && uri.fragment == null) {
                "Relay URL must contain a host and must not contain user-info or a fragment"
            }
            require(uri.query == null) { "Relay base URL must not contain a query" }
            return uri
        }

        fun webSocketUrl(baseUrl: String, deviceId: String): String {
            val base = requireSecureRelayBaseUrl(baseUrl)
            val normalizedPath = base.path.orEmpty().trimEnd('/')
            return URI(
                "wss",
                null,
                base.host,
                base.port,
                "$normalizedPath/v1/device/connect/$deviceId",
                null,
                null,
            ).toString()
        }

        fun enrollmentUrl(baseUrl: String): String {
            val base = requireSecureRelayBaseUrl(baseUrl)
            val normalizedPath = base.path.orEmpty().trimEnd('/')
            return URI(
                "https",
                null,
                base.host,
                base.port,
                "$normalizedPath/v1/device/enroll",
                null,
                null,
            ).toString()
        }
    }
}
