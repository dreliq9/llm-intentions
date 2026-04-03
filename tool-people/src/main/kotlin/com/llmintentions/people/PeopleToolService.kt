package com.llmintentions.people

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.provider.ContactsContract
import com.androidmcp.core.registry.ToolRegistry
import com.androidmcp.core.registry.jsonSchema
import com.androidmcp.intent.ToolAppService
import com.androidmcp.intent.textTool
import kotlinx.serialization.json.*
import java.text.SimpleDateFormat
import java.util.*

class PeopleToolService : ToolAppService() {

    override fun onCreateTools(registry: ToolRegistry) {
        val ctx = applicationContext

        // --- contacts_search ---
        registry.textTool("contacts_search", "Search contacts by name or phone number",
            jsonSchema { string("query", "Name or number to search") }
        ) { args ->
            val query = args["query"]?.jsonPrimitive?.content ?: ""
            val uri = ContactsContract.Contacts.CONTENT_URI
            val cursor = ctx.contentResolver.query(
                uri,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                    ContactsContract.Contacts.HAS_PHONE_NUMBER),
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?",
                arrayOf("%$query%"),
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC"
            )
            val contacts = mutableListOf<JsonObject>()
            cursor?.use {
                while (it.moveToNext() && contacts.size < 20) {
                    val id = it.getLong(0)
                    val name = it.getString(1) ?: ""
                    val hasPhone = it.getInt(2) > 0
                    val phones = if (hasPhone) getPhones(ctx, id) else emptyList()
                    contacts.add(buildJsonObject {
                        put("id", id)
                        put("name", name)
                        put("phones", JsonArray(phones.map { p -> JsonPrimitive(p) }))
                    })
                }
            }
            JsonArray(contacts).toString()
        }

        // --- contacts_list ---
        registry.textTool("contacts_list", "List contacts (paginated)",
            jsonSchema {
                integer("offset", "Start offset (default 0)", required = false)
                integer("limit", "Max results (default 20)", required = false)
            }
        ) { args ->
            val offset = args["offset"]?.jsonPrimitive?.intOrNull ?: 0
            val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 20
            val cursor = ctx.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                null, null,
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC LIMIT $limit OFFSET $offset"
            )
            val contacts = mutableListOf<JsonObject>()
            cursor?.use {
                while (it.moveToNext()) {
                    contacts.add(buildJsonObject {
                        put("id", it.getLong(0))
                        put("name", it.getString(1) ?: "")
                    })
                }
            }
            buildJsonObject {
                put("offset", offset)
                put("count", contacts.size)
                put("contacts", JsonArray(contacts))
            }.toString()
        }

        // --- contact_details ---
        registry.textTool("contact_details", "Get full details for a contact by ID",
            jsonSchema { string("contact_id", "Contact ID") }
        ) { args ->
            val contactId = args["contact_id"]?.jsonPrimitive?.content ?: ""
            val phones = getPhones(ctx, contactId.toLongOrNull() ?: 0)
            val emails = getEmails(ctx, contactId.toLongOrNull() ?: 0)
            buildJsonObject {
                put("id", contactId)
                put("phones", JsonArray(phones.map { JsonPrimitive(it) }))
                put("emails", JsonArray(emails.map { JsonPrimitive(it) }))
            }.toString()
        }

        // --- contact_add ---
        registry.textTool("contact_add", "Add a new contact",
            jsonSchema {
                string("name", "Contact display name")
                string("phone", "Phone number", required = false)
                string("email", "Email address", required = false)
            }
        ) { args ->
            val name = args["name"]?.jsonPrimitive?.content ?: ""
            val phone = args["phone"]?.jsonPrimitive?.contentOrNull
            val email = args["email"]?.jsonPrimitive?.contentOrNull

            val ops = ArrayList<android.content.ContentProviderOperation>()
            val rawIdx = ops.size
            ops.add(android.content.ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build())
            ops.add(android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIdx)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                .build())
            if (phone != null) {
                ops.add(android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIdx)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build())
            }
            if (email != null) {
                ops.add(android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIdx)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                    .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_HOME)
                    .build())
            }
            ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            "Contact added: $name"
        }

        // --- contact_delete ---
        registry.textTool("contact_delete", "Delete a contact by ID",
            jsonSchema { string("contact_id", "Contact ID to delete") }
        ) { args ->
            val id = args["contact_id"]?.jsonPrimitive?.content ?: ""
            val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id.toLong())
            val deleted = ctx.contentResolver.delete(uri, null, null)
            if (deleted > 0) "Contact $id deleted" else "Contact $id not found"
        }

        // --- calendar_events ---
        registry.textTool("calendar_events", "List calendar events in a date range",
            jsonSchema {
                string("start", "Start date (YYYY-MM-DD), default today", required = false)
                string("end", "End date (YYYY-MM-DD), default 7 days from start", required = false)
                integer("limit", "Max results (default 20)", required = false)
            }
        ) { args ->
            val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val startStr = args["start"]?.jsonPrimitive?.contentOrNull
            val endStr = args["end"]?.jsonPrimitive?.contentOrNull
            val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 20

            val startCal = Calendar.getInstance()
            if (startStr != null) try { startCal.time = df.parse(startStr)!! } catch (_: Exception) {}
            startCal.set(Calendar.HOUR_OF_DAY, 0); startCal.set(Calendar.MINUTE, 0)
            val startMs = startCal.timeInMillis

            val endCal = Calendar.getInstance()
            if (endStr != null) try { endCal.time = df.parse(endStr)!! } catch (_: Exception) {}
            else { endCal.timeInMillis = startMs; endCal.add(Calendar.DAY_OF_YEAR, 7) }
            endCal.set(Calendar.HOUR_OF_DAY, 23); endCal.set(Calendar.MINUTE, 59)
            val endMs = endCal.timeInMillis

            val cursor = ctx.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events._ID, CalendarContract.Events.TITLE,
                    CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND,
                    CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.DESCRIPTION),
                "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?",
                arrayOf(startMs.toString(), endMs.toString()),
                "${CalendarContract.Events.DTSTART} ASC"
            )
            val events = mutableListOf<JsonObject>()
            val dtf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            cursor?.use {
                while (it.moveToNext() && events.size < limit) {
                    events.add(buildJsonObject {
                        put("id", it.getLong(0))
                        put("title", it.getString(1) ?: "")
                        put("start", dtf.format(Date(it.getLong(2))))
                        it.getLong(3).let { end -> if (end > 0) put("end", dtf.format(Date(end))) }
                        it.getString(4)?.let { loc -> put("location", loc) }
                        it.getString(5)?.let { desc -> put("description", desc) }
                    })
                }
            }
            JsonArray(events).toString()
        }

        // --- calendar_today ---
        registry.textTool("calendar_today", "List today's calendar events",
            jsonSchema { }
        ) { args ->
            val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val today = df.format(Date())
            // Re-use calendar_events logic by calling the handler with today's date
            val todayArgs = buildJsonObject { put("start", today); put("end", today) }
            val cursor = ctx.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART,
                    CalendarContract.Events.DTEND, CalendarContract.Events.EVENT_LOCATION),
                "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?",
                arrayOf(
                    Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis.toString(),
                    Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59) }.timeInMillis.toString()
                ),
                "${CalendarContract.Events.DTSTART} ASC"
            )
            val dtf = SimpleDateFormat("HH:mm", Locale.US)
            val events = mutableListOf<String>()
            cursor?.use {
                while (it.moveToNext()) {
                    val title = it.getString(0) ?: "(no title)"
                    val start = dtf.format(Date(it.getLong(1)))
                    events.add("$start — $title")
                }
            }
            if (events.isEmpty()) "No events today" else "Today's events:\n${events.joinToString("\n")}"
        }

        // --- event_create ---
        registry.textTool("event_create", "Create a calendar event",
            jsonSchema {
                string("title", "Event title")
                string("start", "Start time (YYYY-MM-DD HH:mm)")
                string("end", "End time (YYYY-MM-DD HH:mm)", required = false)
                string("location", "Event location", required = false)
                string("description", "Event description", required = false)
            }
        ) { args ->
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            val title = args["title"]?.jsonPrimitive?.content ?: ""
            val startMs = try { df.parse(args["start"]?.jsonPrimitive?.content ?: "")?.time } catch (_: Exception) { null } ?: System.currentTimeMillis()
            val endMs = try { args["end"]?.jsonPrimitive?.contentOrNull?.let { df.parse(it)?.time } } catch (_: Exception) { null } ?: (startMs + 3600000)

            // Get the first calendar
            val calCursor = ctx.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID), null, null, null
            )
            val calId = calCursor?.use { if (it.moveToFirst()) it.getLong(0) else null } ?: return@textTool "No calendar found"

            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, startMs)
                put(CalendarContract.Events.DTEND, endMs)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                args["location"]?.jsonPrimitive?.contentOrNull?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
                args["description"]?.jsonPrimitive?.contentOrNull?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            }
            val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            "Event created: $title (${uri})"
        }

        // --- event_delete ---
        registry.textTool("event_delete", "Delete a calendar event by ID",
            jsonSchema { string("event_id", "Event ID to delete") }
        ) { args ->
            val id = args["event_id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@textTool "Invalid ID"
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
            val deleted = ctx.contentResolver.delete(uri, null, null)
            if (deleted > 0) "Event $id deleted" else "Event $id not found"
        }

        // --- calendars_list ---
        registry.textTool("calendars_list", "List available calendars on the device",
            jsonSchema { }
        ) {
            val cursor = ctx.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.VISIBLE),
                null, null, null
            )
            val calendars = mutableListOf<JsonObject>()
            cursor?.use {
                while (it.moveToNext()) {
                    calendars.add(buildJsonObject {
                        put("id", it.getLong(0))
                        put("name", it.getString(1) ?: "")
                        put("account", it.getString(2) ?: "")
                        put("visible", it.getInt(3) == 1)
                    })
                }
            }
            JsonArray(calendars).toString()
        }
    }

    private fun getPhones(ctx: Context, contactId: Long): List<String> {
        val phones = mutableListOf<String>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?", arrayOf(contactId.toString()), null
        )?.use { while (it.moveToNext()) phones.add(it.getString(0) ?: "") }
        return phones
    }

    private fun getEmails(ctx: Context, contactId: Long): List<String> {
        val emails = mutableListOf<String>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
            "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?", arrayOf(contactId.toString()), null
        )?.use { while (it.moveToNext()) emails.add(it.getString(0) ?: "") }
        return emails
    }
}
