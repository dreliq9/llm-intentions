package com.androidmcp.core.policy

import com.androidmcp.core.protocol.ElicitResult
import com.androidmcp.core.protocol.InputRequest
import com.androidmcp.core.protocol.InputRequiredResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

fun interface ToolGrantChecker {
    fun hasGrant(principalId: String, toolName: String): Boolean
}

interface PendingConfirmationStore {
    fun register(nonce: String, expiresAtMs: Long)
    /** Atomically consume a live nonce. Returns false for missing, expired, or replayed state. */
    fun consume(nonce: String, nowMs: Long): Boolean
}

class InMemoryPendingConfirmationStore : PendingConfirmationStore {
    private val pending = ConcurrentHashMap<String, Long>()

    override fun register(nonce: String, expiresAtMs: Long) {
        pending[nonce] = expiresAtMs
    }

    override fun consume(nonce: String, nowMs: Long): Boolean {
        cleanup(nowMs)
        val expires = pending.remove(nonce) ?: return false
        return expires > nowMs
    }

    private fun cleanup(nowMs: Long) {
        pending.entries.removeIf { (_, expiresAt) -> expiresAt <= nowMs }
    }
}

/**
 * Deterministic Hub-style authorizer with MCP 2026-07-28 MRTR confirmation.
 *
 * Local developer access can intentionally bypass the remote policy while the capability catalog
 * is being classified. Remote callers always require a persisted user grant before either
 * automatic execution or confirmation is possible.
 */
class ConfirmingToolAuthorizer(
    private val grantChecker: ToolGrantChecker,
    private val stateCodec: PolicyRequestStateCodec,
    private val pendingStore: PendingConfirmationStore = InMemoryPendingConfirmationStore(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val allowAuthenticatedLocalDeveloper: Boolean = true,
) : ToolCallAuthorizer {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun authorize(request: ToolAuthorizationRequest): ToolAuthorizationOutcome {
        if (request.securityContext.origin == InvocationOrigin.LOCAL_DEVELOPER &&
            allowAuthenticatedLocalDeveloper
        ) {
            return ToolAuthorizationOutcome.Allow
        }

        val decision = ToolPolicyEngine.evaluate(
            metadata = request.metadata,
            context = ToolPolicyContext(
                origin = request.securityContext.origin,
                hasGrant = grantChecker.hasGrant(
                    request.securityContext.principalId,
                    request.toolName,
                ),
            ),
        )

        return when (decision.type) {
            PolicyDecisionType.ALLOW -> ToolAuthorizationOutcome.Allow
            PolicyDecisionType.DENY -> ToolAuthorizationOutcome.Deny(decision.reason)
            PolicyDecisionType.CONFIRM -> handleConfirmation(request, decision.reason)
        }
    }

    private fun handleConfirmation(
        request: ToolAuthorizationRequest,
        reason: String,
    ): ToolAuthorizationOutcome {
        if (!request.supportsInputRequired) {
            return ToolAuthorizationOutcome.Deny(
                "$reason. This client does not support MCP input_required confirmation."
            )
        }

        val retrying = request.requestState != null || request.inputResponses != null
        if (!retrying) {
            val issued = stateCodec.issue(
                principalId = request.securityContext.principalId,
                toolName = request.toolName,
                arguments = request.arguments,
            )
            pendingStore.register(issued.state.nonce, issued.state.expiresAtMs)
            return ToolAuthorizationOutcome.InputRequired(
                InputRequiredResult(
                    inputRequests = mapOf(
                        CONFIRMATION_REQUEST_KEY to InputRequest(
                            method = "elicitation/create",
                            params = confirmationParams(request.toolName, reason),
                        )
                    ),
                    requestState = issued.token,
                )
            )
        }

        val verification = stateCodec.verify(
            token = request.requestState,
            principalId = request.securityContext.principalId,
            toolName = request.toolName,
            arguments = request.arguments,
        )
        val valid = verification as? PolicyRequestStateCodec.Verification.Valid
            ?: return ToolAuthorizationOutcome.Deny(
                (verification as PolicyRequestStateCodec.Verification.Invalid).reason
            )

        // Consume before interpreting the response. Any valid retry is single-use, including a
        // malformed/declined response, so a captured approval state cannot be replayed later.
        if (!pendingStore.consume(valid.state.nonce, nowMs())) {
            return ToolAuthorizationOutcome.Deny("Confirmation state is unknown, expired, or already used")
        }

        val responseObject = request.inputResponses
            ?.get(CONFIRMATION_REQUEST_KEY) as? JsonObject
            ?: return ToolAuthorizationOutcome.Deny("Missing confirmation response")

        val elicitation = try {
            json.decodeFromJsonElement(ElicitResult.serializer(), responseObject)
        } catch (_: Exception) {
            return ToolAuthorizationOutcome.Deny("Malformed confirmation response")
        }

        if (elicitation.action != "accept") {
            return ToolAuthorizationOutcome.Deny("User did not approve tool execution")
        }

        val approved = elicitation.content
            ?.get("approved")
            ?.jsonPrimitive
            ?.booleanOrNull
            ?: false
        if (!approved) {
            return ToolAuthorizationOutcome.Deny("User declined tool execution")
        }

        return ToolAuthorizationOutcome.Allow
    }

    private fun confirmationParams(toolName: String, reason: String): JsonObject = buildJsonObject {
        put("mode", "form")
        put(
            "message",
            "LLM Intentions wants to run '$toolName'. $reason Approve this exact invocation?",
        )
        put("requestedSchema", buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("approved", buildJsonObject {
                    put("type", "boolean")
                    put("title", "Approve this tool call")
                    put("description", "Allow this exact tool invocation once")
                })
            })
            put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("approved"))))
            put("additionalProperties", false)
        })
    }

    companion object {
        const val CONFIRMATION_REQUEST_KEY = "llm_intentions_confirmation"
    }
}
