package com.androidmcp.hub.relay

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

const val RELAY_MAX_TEXT_FRAME_BYTES = 256 * 1024
const val RELAY_MAX_IN_FLIGHT = 8
const val RELAY_MAX_PRINCIPAL_CHARS = 256
const val RELAY_MAX_REQUEST_ID_CHARS = 128
const val RELAY_MAX_DEADLINE_AHEAD_MS = 5 * 60 * 1000L

@Serializable
data class RelayEnrollmentRequest(
    val token: String,
    @SerialName("device_label") val deviceLabel: String,
    @SerialName("public_key_spki_b64") val publicKeySpkiB64: String,
    @SerialName("client_nonce_b64") val clientNonceB64: String,
    @SerialName("signature_der_b64") val signatureDerB64: String,
)

@Serializable
data class RelayEnrollmentResponse(
    @SerialName("device_id") val deviceId: String,
    @SerialName("account_id") val accountId: String,
)

@Serializable
data class RelayAuthChallenge(
    @SerialName("device_id") val deviceId: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("nonce_b64") val nonceB64: String,
    @SerialName("expires_at_ms") val expiresAtMs: Long,
)

@Serializable
data class RelayAuthChallengeFrame(
    val type: String = "auth_challenge",
    val challenge: RelayAuthChallenge,
)

@Serializable
data class RelayAuthResponseFrame(
    val type: String = "auth_response",
    @SerialName("signature_der_b64") val signatureDerB64: String,
)

@Serializable
data class RelayAuthenticatedFrame(
    val type: String = "authenticated",
    @SerialName("device_id") val deviceId: String,
    @SerialName("session_id") val sessionId: String,
)

@Serializable
data class RelayDispatchRequestFrame(
    val type: String = "dispatch_request",
    @SerialName("request_id") val requestId: String,
    @SerialName("principal_id") val principalId: String,
    @SerialName("deadline_ms") val deadlineMs: Long,
    val request: JsonElement,
)

@Serializable
data class RelayDispatchResponseFrame(
    val type: String = "dispatch_response",
    @SerialName("request_id") val requestId: String,
    val response: JsonElement,
)

@Serializable
data class RelayDispatchErrorFrame(
    val type: String = "dispatch_error",
    @SerialName("request_id") val requestId: String,
    val code: String,
    val message: String,
)

@Serializable
data class RelaySessionReplacedFrame(
    val type: String = "session_replaced",
    val reason: String,
)
