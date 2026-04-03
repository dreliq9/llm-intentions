package com.androidmcp.core.async

import com.androidmcp.core.McpDispatcher
import com.androidmcp.core.protocol.JsonRpcError
import com.androidmcp.core.protocol.JsonRpcRequest
import com.androidmcp.core.protocol.JsonRpcResponse
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages asynchronous MCP tool execution.
 *
 * Tools submitted via [submit] run in a background coroutine.
 * Callers poll [poll] for status/results, or [cancel] to abort.
 *
 * Completed jobs are retained for [retentionMs] (default 5 min)
 * then cleaned up automatically.
 */
class JobManager(
    private val dispatcher: McpDispatcher,
    private val retentionMs: Long = 300_000L
) {
    private val scope = CoroutineScope(
        Dispatchers.Default + SupervisorJob() + CoroutineName("mcp-jobs")
    )
    private val jobs = ConcurrentHashMap<String, ManagedJob>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    data class ManagedJob(
        val id: String,
        val method: String,
        val startedAt: Long = System.currentTimeMillis(),
        @Volatile var status: JobStatus = JobStatus.RUNNING,
        @Volatile var response: JsonRpcResponse? = null,
        @Volatile var completedAt: Long = 0,
        val coroutineJob: Job
    )

    enum class JobStatus { RUNNING, COMPLETED, FAILED, CANCELLED }

    /**
     * Submit a JSON-RPC request for async execution.
     * Returns a job ID immediately.
     */
    fun submit(request: JsonRpcRequest): String {
        cleanup() // Opportunistic cleanup of old jobs

        val jobId = UUID.randomUUID().toString().take(12)
        val coroutineJob = scope.launch {
            try {
                val result = dispatcher.dispatch(request)
                // Don't overwrite if already cancelled
                jobs[jobId]?.let { job ->
                    if (job.status == JobStatus.RUNNING) {
                        job.response = result
                        job.completedAt = System.currentTimeMillis()
                        job.status = if (result?.error != null) JobStatus.FAILED else JobStatus.COMPLETED
                    }
                }
            } catch (_: CancellationException) {
                // cancel() already set CANCELLED — nothing to do
            } catch (e: Exception) {
                jobs[jobId]?.let { job ->
                    if (job.status == JobStatus.RUNNING) {
                        job.response = JsonRpcResponse(
                            error = JsonRpcError(
                                code = JsonRpcError.INTERNAL_ERROR,
                                message = e.message ?: "Internal error"
                            ),
                            id = request.id
                        )
                        job.completedAt = System.currentTimeMillis()
                        job.status = JobStatus.FAILED
                    }
                }
            }
        }

        jobs[jobId] = ManagedJob(
            id = jobId,
            method = request.method,
            coroutineJob = coroutineJob
        )

        return jobId
    }

    /**
     * Poll a job's status.
     *
     * Returns a JSON string:
     * - Running: {"jobId":"x","status":"running","elapsed_ms":N}
     * - Completed: {"jobId":"x","status":"completed","elapsed_ms":N,"response":{...json-rpc...}}
     * - Failed: {"jobId":"x","status":"failed","elapsed_ms":N,"response":{...json-rpc error...}}
     * - Cancelled: {"jobId":"x","status":"cancelled"}
     * - Not found: {"jobId":"x","status":"not_found"}
     */
    fun poll(jobId: String): String {
        val job = jobs[jobId] ?: return buildJsonObject {
            put("jobId", jobId)
            put("status", "not_found")
        }.toString()

        val elapsed = System.currentTimeMillis() - job.startedAt

        return buildJsonObject {
            put("jobId", jobId)
            put("status", job.status.name.lowercase())
            put("elapsed_ms", elapsed)

            when (job.status) {
                JobStatus.COMPLETED, JobStatus.FAILED -> {
                    job.response?.let { resp ->
                        put("response", json.encodeToJsonElement(
                            JsonRpcResponse.serializer(), resp
                        ))
                    }
                }
                else -> { }
            }
        }.toString()
    }

    /**
     * Cancel a running job.
     */
    fun cancel(jobId: String): Boolean {
        val job = jobs[jobId] ?: return false
        if (job.status != JobStatus.RUNNING) return false
        job.coroutineJob.cancel()
        job.status = JobStatus.CANCELLED
        job.completedAt = System.currentTimeMillis()
        return true
    }

    /**
     * List all jobs (for hub.jobs tool).
     */
    fun list(): List<ManagedJob> = jobs.values.toList()

    /**
     * Remove completed/failed/cancelled jobs older than retention period.
     */
    fun cleanup() {
        val cutoff = System.currentTimeMillis() - retentionMs
        jobs.entries.removeIf { (_, job) ->
            job.status != JobStatus.RUNNING && job.completedAt < cutoff && job.completedAt > 0
        }
    }

    fun shutdown() {
        scope.cancel()
        jobs.clear()
    }
}
