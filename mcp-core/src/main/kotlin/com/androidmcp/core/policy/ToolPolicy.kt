package com.androidmcp.core.policy

import com.androidmcp.core.protocol.ConfirmationMode
import com.androidmcp.core.protocol.MutationClass
import com.androidmcp.core.protocol.SensitiveDataClass
import com.androidmcp.core.protocol.ToolMetadata

/** Where a tool invocation entered the Hub policy boundary. */
enum class InvocationOrigin {
    LOCAL_DEVELOPER,
    REMOTE_PROVIDER,
}

enum class PolicyDecisionType {
    ALLOW,
    CONFIRM,
    DENY,
}

data class ToolPolicyContext(
    val origin: InvocationOrigin,
    /** User has explicitly enabled this provider/tool scope. Local developer mode may set true. */
    val hasGrant: Boolean,
)

data class ToolPolicyDecision(
    val type: PolicyDecisionType,
    val reason: String,
)

/**
 * Deterministic baseline policy. MCP annotations may inform UI but never override this policy.
 *
 * This engine is intentionally conservative: UNKNOWN metadata cannot auto-authorize a remote call.
 * A future persisted grant model may narrow individual tools/argument ranges without weakening the
 * hard denials below.
 */
object ToolPolicyEngine {

    fun evaluate(metadata: ToolMetadata?, context: ToolPolicyContext): ToolPolicyDecision {
        val md = metadata ?: ToolMetadata()

        // Hard denial first. An author-supplied confirmation=NEVER must never override credential
        // exfiltration policy for a remote provider.
        if (context.origin == InvocationOrigin.REMOTE_PROVIDER &&
            md.sensitiveData == SensitiveDataClass.CREDENTIALS
        ) {
            return deny("Credential-bearing tools are not remotely exportable by default")
        }

        if (!context.hasGrant) {
            return deny("No user grant exists for this invocation scope")
        }

        if (md.confirmation == ConfirmationMode.ALWAYS) {
            return confirm("Tool metadata requires confirmation for every invocation")
        }

        if (context.origin == InvocationOrigin.LOCAL_DEVELOPER) {
            return evaluateGrantedLocal(md)
        }

        return evaluateGrantedRemote(md)
    }

    private fun evaluateGrantedRemote(md: ToolMetadata): ToolPolicyDecision {
        if (md.mutation == MutationClass.UNKNOWN || md.sensitiveData == SensitiveDataClass.UNKNOWN) {
            return confirm("Remote invocation has incomplete safety metadata")
        }

        if (md.destructive) {
            return confirm("Destructive remote operation requires confirmation")
        }

        if (md.mutation == MutationClass.MUTATING) {
            return confirm("Remote state mutation requires confirmation")
        }

        if (md.sensitiveData != SensitiveDataClass.NONE) {
            return confirm("Remote read may expose ${md.sensitiveData.name.lowercase()} data")
        }

        if (md.confirmation == ConfirmationMode.NEVER && md.mutation == MutationClass.READ_ONLY) {
            return allow("Explicit read-only, non-sensitive tool is granted for remote use")
        }

        if (md.mutation == MutationClass.READ_ONLY) {
            return allow("Granted read-only, non-sensitive remote tool")
        }

        return confirm("Remote invocation does not meet automatic-allow criteria")
    }

    private fun evaluateGrantedLocal(md: ToolMetadata): ToolPolicyDecision {
        if (md.destructive) {
            return confirm("Destructive local operation requires confirmation")
        }

        if (md.confirmation == ConfirmationMode.NEVER) {
            return allow("Granted local tool explicitly does not require confirmation")
        }

        if (md.mutation == MutationClass.MUTATING) {
            return confirm("Local state mutation requires confirmation by policy")
        }

        if (md.mutation == MutationClass.UNKNOWN) {
            return confirm("Local invocation has unknown mutation semantics")
        }

        return allow("Granted local read-only tool")
    }

    private fun allow(reason: String) = ToolPolicyDecision(PolicyDecisionType.ALLOW, reason)
    private fun confirm(reason: String) = ToolPolicyDecision(PolicyDecisionType.CONFIRM, reason)
    private fun deny(reason: String) = ToolPolicyDecision(PolicyDecisionType.DENY, reason)
}
