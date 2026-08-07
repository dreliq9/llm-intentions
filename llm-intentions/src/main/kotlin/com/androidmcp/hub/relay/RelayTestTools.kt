package com.androidmcp.hub.relay

import com.androidmcp.core.protocol.ConfirmationMode
import com.androidmcp.core.protocol.LatencyClass
import com.androidmcp.core.protocol.MutationClass
import com.androidmcp.core.protocol.SensitiveDataClass
import com.androidmcp.core.registry.ToolRegistry
import com.androidmcp.core.registry.jsonSchema
import com.androidmcp.core.registry.textTool
import com.androidmcp.core.registry.toolMetadata
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * Harmless synthetic tools used to prove the relay + H2 policy path before any personal CapApp is
 * granted to a remote principal.
 */
class RelayTestTools {
    fun registerAll(registry: ToolRegistry) {
        registry.textTool(
            name = "hub.relay_echo",
            description = "Synthetic read-only relay test. Echoes caller-supplied text and touches no device/user data.",
            params = jsonSchema {
                string("message", "Synthetic message to echo", required = false)
            },
            metadata = toolMetadata {
                destructive = false
                idempotent = true
                latencyClass = LatencyClass.FAST
                mutation = MutationClass.READ_ONLY
                sensitiveData = SensitiveDataClass.NONE
                confirmation = ConfirmationMode.NEVER
                openWorld = false
            },
        ) { args ->
            val message = args["message"]?.jsonPrimitive?.content ?: "relay-ok"
            "relay_echo execution=${UUID.randomUUID()} message=$message"
        }

        registry.textTool(
            name = "hub.relay_confirm_echo",
            description = "Synthetic relay test that always requires one-time user confirmation before execution.",
            params = jsonSchema {
                string("message", "Synthetic message to echo after approval", required = false)
            },
            metadata = toolMetadata {
                destructive = false
                idempotent = true
                latencyClass = LatencyClass.FAST
                mutation = MutationClass.READ_ONLY
                sensitiveData = SensitiveDataClass.NONE
                confirmation = ConfirmationMode.ALWAYS
                openWorld = false
            },
        ) { args ->
            val message = args["message"]?.jsonPrimitive?.content ?: "confirmed-relay-ok"
            "relay_confirm_echo execution=${UUID.randomUUID()} message=$message"
        }
    }
}
