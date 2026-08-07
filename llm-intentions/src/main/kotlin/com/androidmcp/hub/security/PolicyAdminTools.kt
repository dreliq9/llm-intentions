package com.androidmcp.hub.security

import com.androidmcp.core.protocol.ConfirmationMode
import com.androidmcp.core.protocol.LatencyClass
import com.androidmcp.core.protocol.MutationClass
import com.androidmcp.core.protocol.SensitiveDataClass
import com.androidmcp.core.registry.ToolRegistry
import com.androidmcp.core.registry.jsonSchema
import com.androidmcp.core.registry.textTool
import com.androidmcp.core.registry.toolMetadata
import kotlinx.serialization.json.jsonPrimitive

/**
 * Local developer administration for H2 grants.
 *
 * These tools are tagged CREDENTIALS so H2's hard remote rule denies them even if a principal/tool
 * grant were mistakenly created. The authenticated localhost developer path can use them to set up
 * synthetic relay tests without editing app-private preferences by hand.
 */
class PolicyAdminTools(private val grants: HubGrantStore) {
    fun registerAll(registry: ToolRegistry) {
        registry.textTool(
            name = "hub.policy_grant",
            description = "LOCAL ADMIN: grant an authenticated remote principal access to one exact namespaced tool",
            params = jsonSchema {
                string("principal_id", "Transport-authenticated principal ID, e.g. test:relay")
                string("tool_name", "Exact namespaced tool name, e.g. hub.relay_echo")
            },
            metadata = adminMetadata(mutation = MutationClass.MUTATING),
        ) { args ->
            val principal = args["principal_id"]?.jsonPrimitive?.content?.trim().orEmpty()
            val tool = args["tool_name"]?.jsonPrimitive?.content?.trim().orEmpty()
            require(principal.isNotBlank() && principal.length <= 256) { "Invalid principal_id" }
            require(tool.isNotBlank() && tool.length <= 256) { "Invalid tool_name" }
            grants.grant(principal, tool)
            "Granted $principal -> $tool"
        }

        registry.textTool(
            name = "hub.policy_revoke",
            description = "LOCAL ADMIN: revoke an authenticated remote principal's access to one exact namespaced tool",
            params = jsonSchema {
                string("principal_id", "Transport-authenticated principal ID")
                string("tool_name", "Exact namespaced tool name")
            },
            metadata = adminMetadata(mutation = MutationClass.MUTATING),
        ) { args ->
            val principal = args["principal_id"]?.jsonPrimitive?.content?.trim().orEmpty()
            val tool = args["tool_name"]?.jsonPrimitive?.content?.trim().orEmpty()
            require(principal.isNotBlank() && principal.length <= 256) { "Invalid principal_id" }
            require(tool.isNotBlank() && tool.length <= 256) { "Invalid tool_name" }
            grants.revoke(principal, tool)
            "Revoked $principal -> $tool"
        }

        registry.textTool(
            name = "hub.policy_check",
            description = "LOCAL ADMIN: check whether one principal/tool grant exists",
            params = jsonSchema {
                string("principal_id", "Transport-authenticated principal ID")
                string("tool_name", "Exact namespaced tool name")
            },
            metadata = adminMetadata(mutation = MutationClass.READ_ONLY),
        ) { args ->
            val principal = args["principal_id"]?.jsonPrimitive?.content?.trim().orEmpty()
            val tool = args["tool_name"]?.jsonPrimitive?.content?.trim().orEmpty()
            require(principal.isNotBlank() && principal.length <= 256) { "Invalid principal_id" }
            require(tool.isNotBlank() && tool.length <= 256) { "Invalid tool_name" }
            "grant=${grants.hasGrant(principal, tool)} principal=$principal tool=$tool"
        }
    }

    private fun adminMetadata(mutation: MutationClass) = toolMetadata {
        destructive = false
        idempotent = true
        latencyClass = LatencyClass.FAST
        this.mutation = mutation
        sensitiveData = SensitiveDataClass.CREDENTIALS
        confirmation = ConfirmationMode.NEVER
        openWorld = false
    }
}
