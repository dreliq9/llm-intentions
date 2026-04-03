package com.androidmcp.hub.inbox

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Invisible activity that handles ACTION_SEND intents (share sheet).
 * Adds the shared content to the Hub's inbox for Claude to read.
 *
 * Supports:
 * - text/plain — shared text (URLs, snippets, etc.)
 * - Other types — stores the URI as a string reference
 *
 * Finishes immediately after processing.
 */
class ShareReceiveActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when (intent.action) {
            Intent.ACTION_SEND -> handleSend()
            Intent.ACTION_SEND_MULTIPLE -> handleSendMultiple()
        }

        finish()
    }

    private fun handleSend() {
        val type = intent.type ?: "text/plain"
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        val sender = callingPackage ?: referrer?.host ?: "share"

        val content: String = when {
            type.startsWith("text/") -> {
                intent.getStringExtra(Intent.EXTRA_TEXT) ?: "(empty)"
            }
            else -> {
                // For non-text types (image, PDF, etc.), store the URI
                @Suppress("DEPRECATION")
                val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (uri != null) {
                    "file://$uri (type: $type)"
                } else {
                    "(no content)"
                }
            }
        }

        InboxManager.add(
            sender = sender,
            content = content,
            mimeType = type,
            subject = subject
        )

        Toast.makeText(this, "Sent to Claude", Toast.LENGTH_SHORT).show()
    }

    private fun handleSendMultiple() {
        val type = intent.type ?: "text/plain"
        val sender = callingPackage ?: referrer?.host ?: "share"

        @Suppress("DEPRECATION")
        val uris: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        }
        if (uris != null) {
            val content = uris.joinToString("\n") { "file://$it" }
            InboxManager.add(
                sender = sender,
                content = "$content\n(${uris.size} items, type: $type)",
                mimeType = type,
                subject = "Multiple items shared"
            )
        }

        Toast.makeText(this, "${uris?.size ?: 0} items sent to Claude", Toast.LENGTH_SHORT).show()
    }
}
