package com.androidmcp.core.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * Canonical OK/WARN/FAIL envelope for every tool response.
 *
 * Rendered to MCP `content` text as:
 *     OK|WARN|FAIL: <summary>
 *     [Hint: <hint> if non-empty]
 *
 *     <data JSON pretty-printed if any>
 *     [Raw: <raw JSON> if non-OK]
 *
 * Hint text is the LLM's next action — drawn from a tool's ToolMetadata.failureModes.
 */
@Serializable
enum class EnvelopeStatus { OK, WARN, FAIL }

@Serializable
data class Envelope(
    val status: EnvelopeStatus,
    val summary: String,
    val hint: String = "",
    val data: JsonObject = JsonObject(emptyMap()),
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    fun renderText(): String {
        val prefix = status.name
        val sb = StringBuilder()
        sb.append("$prefix: $summary")
        if (hint.isNotEmpty()) {
            sb.append("\nHint: $hint")
        }
        if (data.isNotEmpty()) {
            sb.append("\n\n")
            sb.append(PRETTY.encodeToString(JsonObject.serializer(), data))
        }
        if (status != EnvelopeStatus.OK && raw.isNotEmpty()) {
            sb.append("\n\nRaw:\n")
            sb.append(PRETTY.encodeToString(JsonObject.serializer(), raw))
        }
        return sb.toString()
    }

    companion object {
        private val PRETTY = Json { prettyPrint = true }

        fun ok(summary: String, data: JsonObject = JsonObject(emptyMap()), raw: JsonObject = JsonObject(emptyMap())) =
            Envelope(EnvelopeStatus.OK, summary, "", data, raw)

        fun warn(summary: String, hint: String = "", data: JsonObject = JsonObject(emptyMap()), raw: JsonObject = JsonObject(emptyMap())) =
            Envelope(EnvelopeStatus.WARN, summary, hint, data, raw)

        fun fail(summary: String, hint: String = "", data: JsonObject = JsonObject(emptyMap()), raw: JsonObject = JsonObject(emptyMap())) =
            Envelope(EnvelopeStatus.FAIL, summary, hint, data, raw)

        /**
         * Convert a thrown exception into a FAIL envelope, consulting the tool's
         * metadata.failureModes for an actionable hint. Patterns regex-match against the
         * exception message; exceptionType matches against simple class name or canonical name.
         */
        fun fromException(toolName: String, metadata: ToolMetadata?, exception: Throwable): Envelope {
            val message = exception.message ?: exception.javaClass.simpleName
            val hint = metadata?.let { matchFailureMode(it, exception) } ?: ""
            val raw = buildJsonObject {
                put("exception", exception.javaClass.simpleName)
                put("message", message)
            }
            return fail(summary = "$toolName failed: $message", hint = hint, raw = raw)
        }

        private fun matchFailureMode(metadata: ToolMetadata, exception: Throwable): String {
            val message = exception.message ?: ""
            val simpleName = exception.javaClass.simpleName
            val canonicalName = exception.javaClass.canonicalName ?: simpleName
            for (fm in metadata.failureModes) {
                fm.pattern?.let { p ->
                    if (Regex(p).containsMatchIn(message)) return fm.hint
                }
                fm.exceptionType?.let { t ->
                    if (simpleName == t || canonicalName == t) return fm.hint
                }
            }
            return ""
        }
    }
}

// ---- Rich tool metadata (stub types; DSL builder added in a later task). ----

@Serializable
data class ToolMetadata(
    val examples: List<ToolExample> = emptyList(),
    val permissions: List<String> = emptyList(),
    val destructive: Boolean = false,
    val idempotent: Boolean = true,
    val latencyClass: LatencyClass = LatencyClass.FAST,
    val failureModes: List<FailureMode> = emptyList(),
)

@Serializable
data class ToolExample(val args: JsonObject, val intent: String)

@Serializable
data class FailureMode(
    val pattern: String? = null,
    val exceptionType: String? = null,
    val hint: String,
)

@Serializable
enum class LatencyClass { FAST, SLOW, VERY_SLOW }
