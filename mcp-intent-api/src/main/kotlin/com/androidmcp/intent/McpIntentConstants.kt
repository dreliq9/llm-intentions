package com.androidmcp.intent

/**
 * Constants for CapApp local IPC.
 *
 * Protocol v1 uses an authenticated bound Binder service. The v0 Intent/broadcast constants
 * remain during migration so the Hub can fall back to already-deployed CapApps.
 */
object McpIntentConstants {

    // --- CapApp Protocol v1 ---

    /** Explicit bind action for authenticated Binder CapApps. */
    const val ACTION_BIND_V1 = "com.androidmcp.capapp.BIND_V1"

    /** Integer manifest metadata declaring the highest supported local CapApp protocol. */
    const val META_PROTOCOL_VERSION = "com.androidmcp.PROTOCOL_VERSION"

    const val CAPAPP_PROTOCOL_V0 = 0
    const val CAPAPP_PROTOCOL_V1 = 1

    // --- Protocol v0 Intent actions (Hub → App via startService) ---

    /** Sent by Hub to execute a tool on a legacy ToolAppService. */
    const val ACTION_EXECUTE = "com.androidmcp.tool.EXECUTE"

    /** Sent by Hub to request a legacy app's tool catalog. */
    const val ACTION_LIST_TOOLS = "com.androidmcp.tool.LIST_TOOLS"

    // --- Protocol v0 reply action (App → Hub via sendBroadcast) ---

    /** Sent by a legacy tool app back to Hub with the result. */
    const val ACTION_TOOL_RESULT = "com.androidmcp.tool.RESULT"

    // --- Protocol v0 extras (Hub → App) ---

    const val EXTRA_TOOL_NAME = "com.androidmcp.extra.TOOL_NAME"
    const val EXTRA_ARGUMENTS = "com.androidmcp.extra.ARGUMENTS"
    const val EXTRA_CALLBACK_ID = "com.androidmcp.extra.CALLBACK_ID"

    /** Legacy only. v1 callbacks return directly over Binder and never accept a reply target. */
    const val EXTRA_REPLY_TO = "com.androidmcp.extra.REPLY_TO"

    // --- Protocol v0 extras (App → Hub via broadcast) ---

    const val RESULT_KEY_DATA = "result"
    const val RESULT_KEY_IS_ERROR = "is_error"
    const val RESULT_KEY_TOOL_DEFINITIONS = "tool_definitions"

    // --- Shared manifest metadata keys ---

    /** Boolean: declares this Service as an MCP/CapApp tool provider. */
    const val META_TOOL_APP = "com.androidmcp.TOOL_APP"

    /** String: namespace for this app's tools (e.g., "taichi"). */
    const val META_NAMESPACE = "com.androidmcp.NAMESPACE"
}
