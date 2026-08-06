package com.androidmcp.core.registry

import com.androidmcp.core.protocol.*

/**
 * A registered MCP resource with its metadata and read handler.
 *
 * The handler is invoked when a client calls `resources/read` for this URI.
 * It receives the URI (the same one stored in [info.uri]) and returns the
 * contents to send back. Handlers should not assume any side effects from
 * being called — clients may read a resource zero, one, or many times.
 */
data class McpResourceDef(
    val info: Resource,
    val handler: suspend (uri: String) -> ReadResourceResult
)

/**
 * Registry of static-URI resources exposed by an MCP server.
 *
 * Thread-safe via ConcurrentHashMap. Individual operations are atomic;
 * compound clear+repopulate is not.
 *
 * MVP supports static URIs only (lookup by exact match). URI templates
 * (e.g. `notify://thread/{id}`) are out of scope for this version — add
 * a separate ResourceTemplateRegistry when needed.
 */
class ResourceRegistry {
    private val resources = java.util.concurrent.ConcurrentHashMap<String, McpResourceDef>()

    fun register(resource: McpResourceDef) {
        resources[resource.info.uri] = resource
    }

    fun unregister(uri: String) {
        resources.remove(uri)
    }

    fun clear() {
        resources.clear()
    }

    fun get(uri: String): McpResourceDef? = resources[uri]

    /** Stable ordering is required for predictable cache and prompt behavior. */
    fun list(): List<Resource> = resources.values.map { it.info }.sortedBy { it.uri }

    fun size(): Int = resources.size
}
