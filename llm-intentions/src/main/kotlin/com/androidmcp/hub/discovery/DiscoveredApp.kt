package com.androidmcp.hub.discovery

import android.content.ComponentName
import com.androidmcp.core.protocol.ToolInfo

enum class CapAppTransport {
    INTENT_V0,
    BINDER_V1,
}

data class DiscoveredApp(
    val packageName: String,
    val serviceComponent: ComponentName,
    val namespace: String,
    val tools: List<ToolInfo>,
    val transport: CapAppTransport = CapAppTransport.INTENT_V0,
)
