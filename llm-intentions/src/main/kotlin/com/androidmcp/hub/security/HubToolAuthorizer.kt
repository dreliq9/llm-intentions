package com.androidmcp.hub.security

import android.content.Context
import com.androidmcp.core.policy.ConfirmingToolAuthorizer
import com.androidmcp.core.policy.PolicyRequestStateCodec
import com.androidmcp.core.policy.ToolAuthorizationOutcome
import com.androidmcp.core.policy.ToolAuthorizationRequest
import com.androidmcp.core.policy.ToolCallAuthorizer
import com.androidmcp.core.policy.ToolGrantChecker

/** Android composition root for deterministic Hub tool authorization. */
class HubToolAuthorizer(context: Context) : ToolCallAuthorizer {
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

    override suspend fun authorize(request: ToolAuthorizationRequest): ToolAuthorizationOutcome =
        delegate.authorize(request)
}
