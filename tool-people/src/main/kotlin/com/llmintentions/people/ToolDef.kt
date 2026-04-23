package com.llmintentions.people

data class ParamDef(val name: String, val description: String, val type: ParamType, val required: Boolean = true)
enum class ParamType { STRING, INTEGER, BOOLEAN }

data class ToolDef(val name: String, val description: String, val params: List<ParamDef>) {
    companion object {
        fun allTools(): List<ToolDef> = listOf(
            ToolDef("contacts_search", "Search contacts by name or phone number", listOf(
                ParamDef("query", "Name or number to search", ParamType.STRING))),
            ToolDef("contacts_list", "List contacts (paginated)", listOf(
                ParamDef("offset", "Start offset (default 0)", ParamType.INTEGER, required = false),
                ParamDef("limit", "Max results (default 20)", ParamType.INTEGER, required = false))),
            ToolDef("contact_details", "Get full details for a contact by ID", listOf(
                ParamDef("contact_id", "Contact ID", ParamType.STRING))),
            ToolDef("contact_add", "Add a new contact", listOf(
                ParamDef("name", "Contact display name", ParamType.STRING),
                ParamDef("phone", "Phone number", ParamType.STRING, required = false),
                ParamDef("email", "Email address", ParamType.STRING, required = false))),
            ToolDef("contact_delete", "Delete a contact by ID", listOf(
                ParamDef("contact_id", "Contact ID to delete", ParamType.STRING))),
            ToolDef("calendar_events", "List calendar events in a date range", listOf(
                ParamDef("start", "Start date (YYYY-MM-DD), default today", ParamType.STRING, required = false),
                ParamDef("end", "End date (YYYY-MM-DD), default 7 days", ParamType.STRING, required = false),
                ParamDef("limit", "Max results (default 20)", ParamType.INTEGER, required = false))),
            ToolDef("calendar_today", "List today's calendar events", emptyList()),
            ToolDef("event_create", "Create a calendar event", listOf(
                ParamDef("title", "Event title", ParamType.STRING),
                ParamDef("start", "Start time (YYYY-MM-DD HH:mm)", ParamType.STRING),
                ParamDef("end", "End time (YYYY-MM-DD HH:mm)", ParamType.STRING, required = false),
                ParamDef("location", "Event location", ParamType.STRING, required = false),
                ParamDef("description", "Event description", ParamType.STRING, required = false))),
            ToolDef("event_delete", "Delete a calendar event by ID", listOf(
                ParamDef("event_id", "Event ID to delete", ParamType.STRING))),
            ToolDef("calendars_list", "List available calendars on the device", emptyList())
        )
    }
}
