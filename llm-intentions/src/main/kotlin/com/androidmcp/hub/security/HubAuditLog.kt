package com.androidmcp.hub.security

import android.content.Context
import com.androidmcp.core.policy.ToolAuditEvent
import com.androidmcp.core.policy.ToolAuditSink
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * App-private rolling JSONL audit log for authorization/execution decisions.
 *
 * Events deliberately omit arguments, tool output, requestState, and elicitation content. This log
 * is for trust/debug observability, not a shadow copy of user data.
 */
class HubAuditLog(context: Context) : ToolAuditSink {
    private val lock = Any()
    private val file = File(context.noBackupFilesDir, FILE_NAME)
    private val previousFile = File(context.noBackupFilesDir, PREVIOUS_FILE_NAME)

    override fun record(event: ToolAuditEvent) {
        val line = buildJsonObject {
            put("timestamp_ms", event.timestampMs)
            put("phase", event.phase.name)
            event.principalId?.let { put("principal_id", it) }
            event.origin?.let { put("origin", it.name) }
            put("tool", event.toolName)
            put("outcome", event.outcome)
            event.reason?.let { put("reason", it) }
        }.toString()

        synchronized(lock) {
            file.parentFile?.mkdirs()
            rotateIfNeeded(line.length + 1L)
            file.appendText(line + "\n", Charsets.UTF_8)
        }
    }

    fun readRecent(limit: Int = 100): List<String> = synchronized(lock) {
        if (!file.exists()) return@synchronized emptyList()
        file.readLines(Charsets.UTF_8).takeLast(limit.coerceIn(1, 500))
    }

    fun clear() = synchronized(lock) {
        file.delete()
        previousFile.delete()
    }

    private fun rotateIfNeeded(incomingChars: Long) {
        if (file.exists() && file.length() + incomingChars > MAX_BYTES) {
            previousFile.delete()
            file.renameTo(previousFile)
        }
    }

    companion object {
        private const val FILE_NAME = "policy_audit_v1.jsonl"
        private const val PREVIOUS_FILE_NAME = "policy_audit_v1.previous.jsonl"
        private const val MAX_BYTES = 512L * 1024L
    }
}
