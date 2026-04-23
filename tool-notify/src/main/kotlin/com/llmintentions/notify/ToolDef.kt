package com.llmintentions.notify

data class ParamDef(
    val name: String,
    val description: String,
    val type: ParamType,
    val required: Boolean = true
)

enum class ParamType { STRING, INTEGER, BOOLEAN }

data class ToolDef(
    val name: String,
    val description: String,
    val params: List<ParamDef>
) {
    companion object {
        fun allTools(): List<ToolDef> = listOf(
            ToolDef("notifications_list", "List all currently active notifications on the device", listOf(
                ParamDef("limit", "Max results (default 50)", ParamType.INTEGER, required = false)
            )),
            ToolDef("notification_details", "Get full details for a notification by key", listOf(
                ParamDef("key", "Notification key", ParamType.STRING)
            )),
            ToolDef("notification_dismiss", "Dismiss a notification by key", listOf(
                ParamDef("key", "Notification key to dismiss", ParamType.STRING)
            )),
            ToolDef("notification_dismiss_all", "Dismiss all dismissable notifications", emptyList()),
            ToolDef("notification_reply", "Reply to a notification that has a reply action", listOf(
                ParamDef("key", "Notification key", ParamType.STRING),
                ParamDef("reply", "Reply text", ParamType.STRING)
            )),
            ToolDef("notification_history", "Get recent notification history (last 200, stored in memory)", listOf(
                ParamDef("limit", "Max results (default 50)", ParamType.INTEGER, required = false),
                ParamDef("package_filter", "Filter by package name", ParamType.STRING, required = false),
                ParamDef("text_filter", "Filter by text content", ParamType.STRING, required = false)
            )),
            ToolDef("notification_filter", "Search active notifications by app or text", listOf(
                ParamDef("package_filter", "Filter by package name", ParamType.STRING, required = false),
                ParamDef("text_filter", "Search in title/text", ParamType.STRING, required = false)
            ))
        )
    }
}
