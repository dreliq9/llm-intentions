package com.androidmcp.core.policy

/**
 * Privacy-preserving audit events. Raw arguments, tool outputs, requestState, and user responses are
 * intentionally absent; callers can correlate policy behavior without creating a second sensitive
 * data store.
 */
enum class ToolAuditPhase {
    AUTHORIZATION,
    EXECUTION,
}

data class ToolAuditEvent(
    val timestampMs: Long,
    val phase: ToolAuditPhase,
    val principalId: String?,
    val origin: InvocationOrigin?,
    val toolName: String,
    val outcome: String,
    val reason: String? = null,
)

fun interface ToolAuditSink {
    fun record(event: ToolAuditEvent)
}
