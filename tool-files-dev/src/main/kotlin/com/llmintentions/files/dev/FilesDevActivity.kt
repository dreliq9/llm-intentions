package com.llmintentions.files.dev

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class FilesDevActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val svc = Intent(this, KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        layout.addView(TextView(this).apply {
            text = "LLM File Tools (Dev)"
            textSize = 24f
        })

        val hasAccess = if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else true

        layout.addView(TextView(this).apply {
            text = if (hasAccess) {
                "\nFull filesystem access: GRANTED\n\n15 tools: fs_list, fs_read, fs_read_bytes, fs_write, fs_delete, fs_move, fs_copy, fs_mkdir, fs_find, fs_stat, fs_tree, download, media images/videos/audio\n\nNamespace: fs.*\nForeground service running."
            } else {
                "\nFull filesystem access: NOT GRANTED\n\nTap below to grant 'All files access' permission.\nThis is required for deep filesystem tools."
            }
            textSize = 16f
            setPadding(0, 24, 0, 24)
        })

        if (!hasAccess && Build.VERSION.SDK_INT >= 30) {
            layout.addView(TextView(this).apply {
                text = "Grant All Files Access →"
                textSize = 18f
                setTextColor(android.graphics.Color.BLUE)
                setPadding(0, 24, 0, 0)
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    })
                }
            })
        }

        setContentView(layout)
    }
}
