package com.androidmcp.intent.v1

import com.androidmcp.core.protocol.ToolInfo
import com.androidmcp.core.protocol.ToolMetadata
import kotlinx.serialization.Serializable

/**
 * Trusted local descriptor sent from an authenticated CapApp to the Hub.
 *
 * [tool] contains the standard MCP-facing definition/annotations. [metadata] is richer local
 * policy information and is never treated as a substitute for Hub authorization.
 */
@Serializable
data class CapAppToolDescriptor(
    val tool: ToolInfo,
    val metadata: ToolMetadata = ToolMetadata(),
)
