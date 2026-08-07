package com.androidmcp.hub.security

import android.content.Context
import com.androidmcp.core.policy.ConfirmingToolAuthorizer
import com.androidmcp.core.policy.PolicyRequestStateCodec
import com.androidmcp.core.policy.ToolAuditEvent
import com.androidmcp.core.policy.ToolAuditPhase
import com.androidmcp.core.policy.ToolAuditSink
import com.androidmcp.core.policy.ToolAuthorizationOutcome
import com.androidmcp.core.policy.ToolAuthorizationRequest
import com.androidmcp.core.policy.ToolCallAuthorizer
import com.androidmcp.core.policy.ToolGrantChecker

/** Android composition root for deterministic Hub tool authorization. */
class HubToolAuthorizer(
    context: Context,
    private val auditSink: ToolAuditSink? = null,
) : ToolCallAuthorizer {
    val grantStore = HubGrantStore(context.applicationContext)

    private val delegate = ConfirmingToolAuthorizer(
        grantChecker = ToolGrantChecker { principalId, toolName ->
            grantStore.hasGrant(principalId, toolName)
        },
        stateCodec = PolicyRequestStateCodec(
            secret = HubPolicySecretStore.getOrCreate(context.applicationContext),
        ),
        // H2 preserves the already-authenticated local developer workflow while metadata is being
        // backfilled. Remote providers never get this bypass.
        allowAuthenticatedLocalDeveloper = true,
    )

    override suspend fun authorize(request: ToolAuthorizationRequest): ToolAuthorizationOutcome {
        val outcome = delegate.authorize(request)
        val (label, reason) = when (outcome) {
            ToolAuthorizationOutcome.Allow -> "ALLOW" to null
            is ToolAuthorizationOutcome.Deny -> "DENY" to outcome.reason
            is ToolAuthorizationOutcome.InputRequired ->
                "INPUT_REQUIRED" to "One-time user confirmation required"
        }

        // Audit is observability, not an authorization dependency. Never leak arguments, tool
        // results, requestState, or elicitation contents into this path, and never turn a local I/O
        // failure into a different authorization decision.
        runCatching {
            auditSink?.record(
                ToolAuditEvent(
                    timestampMs = System.currentTimeMillis(),
                    phase = ToolAuditPhase.AUTHORIZATION,
                    principalId = request.securityContext.principalId,
                    origin = request.securityContext.origin,
                    toolName = request.toolName,
                    outcome = label,
                    reason = reason,
                )
            )
        }

        return outcome
    }
}
