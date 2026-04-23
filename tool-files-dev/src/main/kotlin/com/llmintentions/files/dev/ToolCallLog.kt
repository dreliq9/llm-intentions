package com.llmintentions.files.dev

import java.text.SimpleDateFormat
import java.util.*

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val source: String, // "UI" or "LLM"
    val toolName: String,
    val isError: Boolean = false,
    val resultPreview: String = ""
) {
    fun formattedTime(): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestamp))
}

object ToolCallLog {
    private const val MAX_ENTRIES = 50
    private val entries = ArrayDeque<LogEntry>(MAX_ENTRIES)
    private val listeners = mutableListOf<() -> Unit>()

    @Synchronized
    fun add(entry: LogEntry) {
        if (entries.size >= MAX_ENTRIES) entries.removeLast()
        entries.addFirst(entry)
        listeners.forEach { it() }
    }

    @Synchronized
    fun add(source: String, toolName: String, result: String, isError: Boolean = false) {
        val preview = result.take(200).replace("\n", " ")
        add(LogEntry(source = source, toolName = toolName, isError = isError, resultPreview = preview))
    }

    @Synchronized
    fun entries(): List<LogEntry> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
        listeners.forEach { it() }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }
}
