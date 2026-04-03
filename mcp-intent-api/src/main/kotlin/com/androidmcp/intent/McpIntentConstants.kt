package com.androidmcp.intent

/**
 * Constants for the MCP Intent protocol.
 *
 * Communication flow:
 *   Hub → App: startService() with ACTION_EXECUTE or ACTION_LIST_TOOLS
 *   App → Hub: sendBroadcast() with ACTION_TOOL_RESULT carrying the response
 *
 * Broadcast replies are used instead of ResultReceiver because ResultReceiver
 * loses its Binder callback when parceled across process boundaries via
 * startService(). Broadcasts work reliably cross-process.
 */
object McpIntentConstants {

    // --- Intent actions (Hub → App via startService) ---

    /** Sent by Hub to execute a tool on an app's ToolAppService. */
    const val ACTION_EXECUTE = "com.androidmcp.tool.EXECUTE"

    /** Sent by Hub to request an app's tool catalog. */
    const val ACTION_LIST_TOOLS = "com.androidmcp.tool.LIST_TOOLS"

    // --- Intent action (App → Hub via sendBroadcast) ---

    /** Sent by tool app back to Hub with the result. */
    const val ACTION_TOOL_RESULT = "com.androidmcp.tool.RESULT"

    // --- Intent extras (Hub → App) ---

    /** String: the tool name to execute (without namespace prefix). */
    const val EXTRA_TOOL_NAME = "com.androidmcp.extra.TOOL_NAME"

    /** String: JSON-encoded arguments object. */
    const val EXTRA_ARGUMENTS = "com.androidmcp.extra.ARGUMENTS"

    /** String: unique callback ID for correlating the response. */
    const val EXTRA_CALLBACK_ID = "com.androidmcp.extra.CALLBACK_ID"

    /** String: package name of the Hub, so the app knows where to send the reply. */
    const val EXTRA_REPLY_TO = "com.androidmcp.extra.REPLY_TO"

    // --- Intent extras (App → Hub via broadcast) ---

    /** String: JSON-encoded result from a tool call. */
    const val RESULT_KEY_DATA = "result"

    /** Boolean: true if the tool call produced an error. */
    const val RESULT_KEY_IS_ERROR = "is_error"

    /** String: JSON array of tool definitions (response to LIST_TOOLS). */
    const val RESULT_KEY_TOOL_DEFINITIONS = "tool_definitions"

    // --- Manifest metadata keys ---

    /** Boolean: declares this Service as an MCP tool provider. */
    const val META_TOOL_APP = "com.androidmcp.TOOL_APP"

    /** String: namespace for this app's tools (e.g., "taichi"). */
    const val META_NAMESPACE = "com.androidmcp.NAMESPACE"
}
