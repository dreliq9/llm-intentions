package com.llmintentions.notify

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class NotifyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val svc = Intent(this, KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        layout.addView(TextView(this).apply {
            text = "LLM Notification Tools"
            textSize = 24f
        })

        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true

        layout.addView(TextView(this).apply {
            text = if (enabled) {
                "\nNotification access: ENABLED\n\n8 tools: list, details, dismiss, dismiss all, reply, history, filter, watch\n\nForeground service running."
            } else {
                "\nNotification access: NOT ENABLED\n\nTap below to open Settings and enable notification access for this app."
            }
            textSize = 16f
            setPadding(0, 24, 0, 24)
        })

        if (!enabled) {
            layout.addView(TextView(this).apply {
                text = "Open Notification Settings →"
                textSize = 18f
                setTextColor(android.graphics.Color.BLUE)
                setPadding(0, 24, 0, 0)
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            })
        }

        setContentView(layout)
    }
}
