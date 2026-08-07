package com.androidmcp.hub.relay

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

class RelayEnrollmentClient(context: Context) {
    private val appContext = context.applicationContext
    private val store = RelayEnrollmentStore(appContext)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Redeem a one-time relay token and persist only the resulting non-secret enrollment metadata.
     * The returned enrollment is disabled until the user explicitly enables the relay.
     */
    suspend fun enroll(
        relayBaseUrl: String,
        oneTimeToken: String,
        deviceLabel: String,
    ): RelayEnrollment = withContext(Dispatchers.IO) {
        RelayEnrollmentStore.requireSecureRelayBaseUrl(relayBaseUrl)
        val token = oneTimeToken.trim()
        require(token.length in 20..256) { "Enrollment token has an invalid length" }
        require(deviceLabel.isNotBlank() && deviceLabel.length <= 128) {
            "Device label must be 1..128 characters"
        }

        val publicKey = RelayDeviceIdentity.publicKeySpkiB64()
        val nonce = RelayDeviceIdentity.randomNonceB64()
        val proof = RelayDeviceIdentity.signDerB64(
            RelayDeviceIdentity.enrollmentProofMessage(token, publicKey, nonce)
        )
        val requestBody = RelayEnrollmentRequest(
            token = token,
            deviceLabel = deviceLabel.trim(),
            publicKeySpkiB64 = publicKey,
            clientNonceB64 = nonce,
            signatureDerB64 = proof,
        )
        val encoded = json.encodeToString(RelayEnrollmentRequest.serializer(), requestBody)

        val request = Request.Builder()
            .url(RelayEnrollmentStore.enrollmentUrl(relayBaseUrl))
            .post(encoded.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .build()

        RelayNetwork.client.newCall(request).execute().use { response ->
            val body = readBoundedBody(response.body?.byteStream(), MAX_ENROLLMENT_RESPONSE_BYTES)
            if (!response.isSuccessful) {
                val detail = body.take(512).ifBlank { "HTTP ${response.code}" }
                error("Relay enrollment rejected: $detail")
            }
            val enrollmentResponse = json.decodeFromString<RelayEnrollmentResponse>(body)
            val enrollment = RelayEnrollment(
                relayBaseUrl = relayBaseUrl.trimEnd('/'),
                deviceId = enrollmentResponse.deviceId,
                accountId = enrollmentResponse.accountId,
                deviceKeyId = RelayDeviceIdentity.keyId(),
                enabled = false,
            )
            store.save(enrollment)
            enrollment
        }
    }

    private fun readBoundedBody(
        input: java.io.InputStream?,
        maxBytes: Int,
    ): String {
        if (input == null) return ""
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                require(total <= maxBytes) { "Relay enrollment response exceeded $maxBytes bytes" }
                output.write(buffer, 0, read)
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_ENROLLMENT_RESPONSE_BYTES = 64 * 1024
    }
}
