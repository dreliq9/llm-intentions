package com.androidmcp.core.policy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-authenticated opaque requestState for policy confirmations.
 *
 * The token binds a confirmation to the authenticated principal, tool name, canonical arguments,
 * issue/expiry time, and a random nonce. Anti-replay is completed by the Hub authorizer, which
 * tracks issued nonces and consumes each one on the first valid retry.
 */
class PolicyRequestStateCodec(
    secret: ByteArray,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val random: SecureRandom = SecureRandom(),
) {
    private val key = secret.copyOf()

    init {
        require(key.size >= MIN_SECRET_BYTES) { "Policy request-state secret must be at least $MIN_SECRET_BYTES bytes" }
        require(ttlMs in 1..MAX_TTL_MS) { "Policy request-state ttlMs is out of range" }
    }

    data class State(
        val principalId: String,
        val toolName: String,
        val argumentsSha256: String,
        val issuedAtMs: Long,
        val expiresAtMs: Long,
        val nonce: String,
    )

    data class Issued(
        val token: String,
        val state: State,
    )

    sealed interface Verification {
        data class Valid(val state: State) : Verification
        data class Invalid(val reason: String) : Verification
    }

    fun issue(principalId: String, toolName: String, arguments: JsonObject): Issued {
        require(principalId.isNotBlank()) { "principalId is required" }
        require(toolName.isNotBlank()) { "toolName is required" }

        val issuedAt = nowMs()
        val state = State(
            principalId = principalId,
            toolName = toolName,
            argumentsSha256 = hashArguments(arguments),
            issuedAtMs = issuedAt,
            expiresAtMs = issuedAt + ttlMs,
            nonce = randomNonce(),
        )
        val payload = buildJsonObject {
            put("v", VERSION)
            put("principal", state.principalId)
            put("tool", state.toolName)
            put("args_sha256", state.argumentsSha256)
            put("issued_at_ms", state.issuedAtMs)
            put("expires_at_ms", state.expiresAtMs)
            put("nonce", state.nonce)
        }.toString().toByteArray(Charsets.UTF_8)

        val encodedPayload = B64_ENCODER.encodeToString(payload)
        val signature = sign(encodedPayload.toByteArray(Charsets.US_ASCII))
        return Issued(
            token = "$encodedPayload.${B64_ENCODER.encodeToString(signature)}",
            state = state,
        )
    }

    fun verify(
        token: String?,
        principalId: String,
        toolName: String,
        arguments: JsonObject,
    ): Verification {
        if (token.isNullOrBlank()) return Verification.Invalid("Missing requestState")
        val parts = token.split('.')
        if (parts.size != 2 || parts.any { it.isBlank() }) {
            return Verification.Invalid("Malformed requestState")
        }

        val encodedPayload = parts[0]
        val actualSignature = try {
            B64_DECODER.decode(parts[1])
        } catch (_: IllegalArgumentException) {
            return Verification.Invalid("Malformed requestState signature")
        }
        val expectedSignature = sign(encodedPayload.toByteArray(Charsets.US_ASCII))
        if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
            return Verification.Invalid("requestState signature mismatch")
        }

        val payload = try {
            String(B64_DECODER.decode(encodedPayload), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return Verification.Invalid("Malformed requestState payload")
        }
        val obj = try {
            Json.parseToJsonElement(payload).jsonObject
        } catch (_: Exception) {
            return Verification.Invalid("Invalid requestState JSON")
        }

        val version = obj["v"]?.jsonPrimitive?.longOrNullCompat()
            ?: return Verification.Invalid("Missing requestState version")
        if (version != VERSION.toLong()) return Verification.Invalid("Unsupported requestState version")

        val state = try {
            State(
                principalId = obj.requiredString("principal"),
                toolName = obj.requiredString("tool"),
                argumentsSha256 = obj.requiredString("args_sha256"),
                issuedAtMs = obj.requiredLong("issued_at_ms"),
                expiresAtMs = obj.requiredLong("expires_at_ms"),
                nonce = obj.requiredString("nonce"),
            )
        } catch (e: IllegalArgumentException) {
            return Verification.Invalid(e.message ?: "Invalid requestState fields")
        }

        val now = nowMs()
        if (state.issuedAtMs > now + MAX_CLOCK_SKEW_MS) {
            return Verification.Invalid("requestState issue time is in the future")
        }
        if (state.expiresAtMs <= now) return Verification.Invalid("requestState expired")
        if (state.expiresAtMs <= state.issuedAtMs || state.expiresAtMs - state.issuedAtMs > MAX_TTL_MS) {
            return Verification.Invalid("requestState lifetime is invalid")
        }
        if (state.principalId != principalId) return Verification.Invalid("requestState principal mismatch")
        if (state.toolName != toolName) return Verification.Invalid("requestState tool mismatch")
        if (state.argumentsSha256 != hashArguments(arguments)) {
            return Verification.Invalid("requestState arguments mismatch")
        }

        return Verification.Valid(state)
    }

    fun hashArguments(arguments: JsonObject): String {
        val bytes = canonicalJson(arguments).toByteArray(Charsets.UTF_8)
        return B64_ENCODER.encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
    }

    private fun sign(bytes: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac.doFinal(bytes)
    }

    private fun randomNonce(): String {
        val bytes = ByteArray(NONCE_BYTES)
        random.nextBytes(bytes)
        return B64_ENCODER.encodeToString(bytes)
    }

    private fun canonicalJson(element: JsonElement): String = when (element) {
        is JsonObject -> element.entries
            .sortedBy { it.key }
            .joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
                "${JsonPrimitive(key)}:${canonicalJson(value)}"
            }
        is JsonArray -> element.joinToString(prefix = "[", postfix = "]", separator = ",") {
            canonicalJson(it)
        }
        is JsonPrimitive -> element.toString()
        JsonNull -> "null"
        else -> element.toString()
    }

    private fun JsonObject.requiredString(name: String): String =
        this[name]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing requestState field: $name")

    private fun JsonObject.requiredLong(name: String): Long =
        this[name]?.jsonPrimitive?.longOrNullCompat()
            ?: throw IllegalArgumentException("Missing requestState field: $name")

    private fun JsonPrimitive.longOrNullCompat(): Long? = try {
        long
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val VERSION = 1
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val MIN_SECRET_BYTES = 32
        private const val NONCE_BYTES = 18
        private const val DEFAULT_TTL_MS = 5 * 60_000L
        private const val MAX_TTL_MS = 30 * 60_000L
        private const val MAX_CLOCK_SKEW_MS = 60_000L

        private val B64_ENCODER = Base64.getUrlEncoder().withoutPadding()
        private val B64_DECODER = Base64.getUrlDecoder()
    }
}
