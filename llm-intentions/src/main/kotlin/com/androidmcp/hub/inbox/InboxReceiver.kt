package com.androidmcp.hub.inbox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives messages from other Android apps via explicit Intent.
 *
 * Apps can send messages to Claude by broadcasting:
 *   val intent = Intent("com.androidmcp.SEND_MESSAGE")
 *   intent.setPackage("com.androidmcp.hub")
 *   intent.putExtra("message", "Analyze this: ...")
 *   intent.putExtra("subject", "Analysis request")  // optional
 *   sendBroadcast(intent)
 *
 * Messages are stored in the Hub's inbox for Claude to read
 * via the hub.inbox tool.
 */
class InboxReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "com.androidmcp.SEND_MESSAGE"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_SUBJECT = "subject"
        const val EXTRA_MIME_TYPE = "mime_type"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return

        val message = intent.getStringExtra(EXTRA_MESSAGE)
        if (message.isNullOrBlank()) {
            Log.w("MCP-Hub", "InboxReceiver: empty message, ignoring")
            return
        }

        val sender = intent.`package`
            ?: context.packageManager.getNameForUid(android.os.Binder.getCallingUid())
            ?: "unknown"
        val subject = intent.getStringExtra(EXTRA_SUBJECT)
        val mimeType = intent.getStringExtra(EXTRA_MIME_TYPE) ?: "text/plain"

        val id = InboxManager.add(
            sender = sender,
            content = message,
            mimeType = mimeType,
            subject = subject
        )

        Log.i("MCP-Hub", "InboxReceiver: message #$id from $sender (${message.length} chars)")
    }
}
