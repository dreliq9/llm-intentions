package com.androidmcp.hub.discovery

import android.content.ComponentName
import com.androidmcp.core.protocol.ToolInfo
import com.androidmcp.core.protocol.ToolMetadata

enum class CapAppTransport {
    INTENT_V0,
    BINDER_V1,
}

data class DiscoveredApp(
    val packageName: String,
    val serviceComponent: ComponentName,
    val namespace: String,
    val tools: List<ToolInfo>,
    /** Rich authenticated v1 metadata keyed by the CapApp-local tool name. Empty for legacy v0. */
    val toolMetadata: Map<String, ToolMetadata> = emptyMap(),
    val transport: CapAppTransport = CapAppTransport.INTENT_V0,
)
