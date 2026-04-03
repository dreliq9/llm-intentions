package com.androidmcp.hub.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.androidmcp.hub.databinding.ItemInboxMessageBinding
import com.androidmcp.hub.inbox.InboxManager
import java.text.SimpleDateFormat
import java.util.Locale

class InboxAdapter(
    private val onDismiss: (Long) -> Unit
) : RecyclerView.Adapter<InboxAdapter.MessageViewHolder>() {

    private var messages: List<InboxManager.Message> = emptyList()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun submitMessages(newMessages: List<InboxManager.Message>) {
        messages = newMessages
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val binding = ItemInboxMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun getItemCount() = messages.size

    inner class MessageViewHolder(private val binding: ItemInboxMessageBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: InboxManager.Message) {
            binding.senderText.text = message.sender
            binding.timeText.text = timeFormat.format(message.timestamp)
            binding.contentText.text = message.content

            if (message.subject != null) {
                binding.subjectText.text = message.subject
                binding.subjectText.visibility = View.VISIBLE
            } else {
                binding.subjectText.visibility = View.GONE
            }

            binding.dismissButton.setOnClickListener {
                onDismiss(message.id)
            }
        }
    }
}
