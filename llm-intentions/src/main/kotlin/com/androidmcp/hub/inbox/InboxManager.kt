package com.androidmcp.hub.inbox

import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory inbox for messages sent to Claude via Android Intents or share sheet.
 *
 * Messages are stored until consumed or cleared. The inbox is FIFO.
 * Singleton — survives across provider refreshes.
 */
object InboxManager {

    data class Message(
        val id: Long,
        val sender: String,
        val content: String,
        val mimeType: String = "text/plain",
        val subject: String? = null,
        val timestamp: Long = System.currentTimeMillis(),
        val extras: Map<String, String> = emptyMap()
    ) {
        fun toDisplayString(): String = buildString {
            appendLine("[$id] From: $sender at ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(timestamp)}")
            subject?.let { appendLine("Subject: $it") }
            appendLine("Type: $mimeType")
            appendLine("Content: $content")
        }
    }

    private val messages = ConcurrentLinkedDeque<Message>()
    private val idCounter = AtomicLong(1)

    /**
     * Add a message to the inbox.
     */
    fun add(
        sender: String,
        content: String,
        mimeType: String = "text/plain",
        subject: String? = null,
        extras: Map<String, String> = emptyMap()
    ): Long {
        val id = idCounter.getAndIncrement()
        messages.addLast(Message(
            id = id,
            sender = sender,
            content = content,
            mimeType = mimeType,
            subject = subject,
            extras = extras
        ))
        return id
    }

    /**
     * Peek at messages without removing them.
     */
    fun peek(limit: Int = 50): List<Message> {
        return messages.take(limit)
    }

    /**
     * Read and remove messages from the inbox (FIFO).
     */
    fun consume(limit: Int = 50): List<Message> {
        val result = mutableListOf<Message>()
        repeat(limit) {
            val msg = messages.pollFirst() ?: return result
            result.add(msg)
        }
        return result
    }

    /**
     * Remove a specific message by ID.
     */
    fun remove(id: Long): Boolean {
        return messages.removeIf { it.id == id }
    }

    fun clear() {
        messages.clear()
    }

    fun size(): Int = messages.size

    fun isEmpty(): Boolean = messages.isEmpty()
}
