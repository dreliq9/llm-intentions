package com.androidmcp.hub.inbox

import com.androidmcp.core.protocol.*
import com.androidmcp.core.registry.McpToolDef
import com.androidmcp.core.registry.ToolRegistry
import com.androidmcp.core.registry.jsonSchema
import kotlinx.serialization.json.*

/**
 * MCP tools for reading the Hub's message inbox.
 *
 * Other Android apps can send messages to Claude via:
 * 1. Share sheet → ShareReceiveActivity (any app's share button)
 * 2. Broadcast Intent → InboxReceiver (automation apps like Tasker)
 *
 * Claude reads messages via these tools.
 */
class InboxTools {

    fun registerAll(registry: ToolRegistry) {
        registerInbox(registry)
        registerInboxClear(registry)
    }

    private fun registerInbox(registry: ToolRegistry) {
        registry.register(McpToolDef(
            info = ToolInfo(
                name = "hub.inbox",
                description = "Check the inbox for messages sent to Claude from Android apps " +
                    "(via share sheet or broadcast intent). " +
                    "Returns pending messages. Set consume=true to remove them after reading.",
                inputSchema = jsonSchema {
                    boolean("consume", "If true, remove messages after reading (default: false)")
                    integer("limit", "Max messages to return (default: 20)", required = false)
                }
            ),
            handler = { args ->
                val consume = args["consume"]?.jsonPrimitive?.booleanOrNull ?: false
                val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 20

                val messages = if (consume) {
                    InboxManager.consume(limit)
                } else {
                    InboxManager.peek(limit)
                }

                if (messages.isEmpty()) {
                    ToolCallResult(content = listOf(ContentBlock.text(
                        "Inbox empty. No messages from Android apps."
                    )))
                } else {
                    val text = buildString {
                        appendLine("${messages.size} message(s)${if (!consume) " (peek — not consumed)" else " (consumed)"}:")
                        appendLine()
                        for (msg in messages) {
                            append(msg.toDisplayString())
                            appendLine()
                        }
                        if (InboxManager.size() > messages.size) {
                            appendLine("(${InboxManager.size() - messages.size} more in inbox)")
                        }
                    }
                    ToolCallResult(content = listOf(ContentBlock.text(text)))
                }
            }
        ))
    }

    private fun registerInboxClear(registry: ToolRegistry) {
        registry.register(McpToolDef(
            info = ToolInfo(
                name = "hub.inbox_clear",
                description = "Clear all messages from the inbox",
                inputSchema = jsonSchema { }
            ),
            handler = { _ ->
                val count = InboxManager.size()
                InboxManager.clear()
                ToolCallResult(content = listOf(ContentBlock.text(
                    "Inbox cleared ($count message(s) removed)"
                )))
            }
        ))
    }
}
