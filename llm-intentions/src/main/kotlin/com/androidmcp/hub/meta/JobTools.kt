package com.androidmcp.hub.meta

import com.androidmcp.core.async.JobManager
import com.androidmcp.core.protocol.*
import com.androidmcp.core.registry.McpToolDef
import com.androidmcp.core.registry.ToolRegistry
import com.androidmcp.core.registry.jsonSchema
import kotlinx.serialization.json.*

/**
 * MCP tools for inspecting and managing async jobs.
 * Registered under the "hub" namespace.
 */
class JobTools(
    private val getJobManager: () -> JobManager
) {

    fun registerAll(registry: ToolRegistry) {
        registerJobsList(registry)
        registerCancelJob(registry)
    }

    private fun registerJobsList(registry: ToolRegistry) {
        registry.register(McpToolDef(
            info = ToolInfo(
                name = "hub.jobs",
                description = "List active and recent async jobs (long-running tool executions)",
                inputSchema = jsonSchema { }
            ),
            handler = { _ ->
                val jobs = getJobManager().list()
                if (jobs.isEmpty()) {
                    ToolCallResult(content = listOf(ContentBlock.text("No active or recent jobs.")))
                } else {
                    val text = buildString {
                        appendLine("Jobs (${jobs.size}):")
                        appendLine()
                        for (job in jobs) {
                            val elapsed = System.currentTimeMillis() - job.startedAt
                            val elapsedStr = if (elapsed < 1000) "${elapsed}ms"
                                else "${"%.1f".format(elapsed / 1000.0)}s"
                            appendLine("  [${job.status.name}] ${job.id} — ${job.method} ($elapsedStr)")
                        }
                    }
                    ToolCallResult(content = listOf(ContentBlock.text(text)))
                }
            }
        ))
    }

    private fun registerCancelJob(registry: ToolRegistry) {
        registry.register(McpToolDef(
            info = ToolInfo(
                name = "hub.cancel_job",
                description = "Cancel a running async job by its ID",
                inputSchema = jsonSchema {
                    string("job_id", "The job ID to cancel")
                }
            ),
            handler = { args ->
                val jobId = args["job_id"]?.jsonPrimitive?.content ?: ""
                val cancelled = getJobManager().cancel(jobId)
                if (cancelled) {
                    ToolCallResult(content = listOf(ContentBlock.text("Job $jobId cancelled.")))
                } else {
                    ToolCallResult(
                        content = listOf(ContentBlock.text(
                            "Could not cancel job $jobId (not found or not running)."
                        )),
                        isError = true
                    )
                }
            }
        ))
    }
}
