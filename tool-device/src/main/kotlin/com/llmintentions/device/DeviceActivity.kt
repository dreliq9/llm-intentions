package com.llmintentions.device

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class DeviceActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val svc = Intent(this, KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)

        setContentView(TextView(this).apply {
            text = "LLM Device Tools\n\n15 tools: battery, device info, storage, memory, clipboard, flashlight, vibrate, volume, ringer, TTS, sensors\n\nNo permissions required.\nForeground service running."
            textSize = 16f
            setPadding(48, 48, 48, 48)
        })
    }
}
