package com.androidmcp.hub.discovery

import android.content.ComponentName
import com.androidmcp.core.protocol.ToolInfo

data class DiscoveredApp(
    val packageName: String,
    val serviceComponent: ComponentName,
    val namespace: String,
    val tools: List<ToolInfo>
)
