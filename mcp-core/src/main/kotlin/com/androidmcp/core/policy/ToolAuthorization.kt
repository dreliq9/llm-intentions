package com.androidmcp.core.policy

import com.androidmcp.core.protocol.InputRequiredResult
import com.androidmcp.core.protocol.ToolMetadata
import kotlinx.serialization.json.JsonObject

/**
 * Security identity supplied by a trusted transport, never by MCP request _meta.
 *
 * [principalId] is stable only within the authenticating transport. Examples:
 * - local developer bearer endpoint: `local:developer`
 * - future relay: a verified relay/provider/account subject
 */
data class ToolInvocationSecurityContext(
    val origin: InvocationOrigin,
    val principalId: String,
)

data class ToolAuthorizationRequest(
    val toolName: String,
    val metadata: ToolMetadata?,
    val arguments: JsonObject,
    val inputResponses: JsonObject?,
    val requestState: String?,
    val securityContext: ToolInvocationSecurityContext,
    /** True only for the MCP 2026-07-28 path that understands input_required. */
    val supportsInputRequired: Boolean,
)

sealed interface ToolAuthorizationOutcome {
    data object Allow : ToolAuthorizationOutcome
    data class Deny(val reason: String) : ToolAuthorizationOutcome
    data class InputRequired(val result: InputRequiredResult) : ToolAuthorizationOutcome
}

/**
 * Optional dispatcher authorization hook. Generic MCP SDK users need not install one.
 *
 * When installed, McpDispatcher fails closed if a tools/call arrives without a transport-provided
 * [ToolInvocationSecurityContext].
 */
fun interface ToolCallAuthorizer {
    suspend fun authorize(request: ToolAuthorizationRequest): ToolAuthorizationOutcome
}
