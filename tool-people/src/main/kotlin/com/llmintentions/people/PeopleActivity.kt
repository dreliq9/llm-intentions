package com.llmintentions.people

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class PeopleActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val svc = Intent(this, KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)

        val perms = arrayOf(
            Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR
        )
        val needed = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)

        setContentView(TextView(this).apply {
            text = "LLM People Tools\n\n12 tools: contacts search/list/add/delete, calendar events/today/create/delete, calendars list\n\nPermissions: Contacts + Calendar\nForeground service running."
            textSize = 16f
            setPadding(48, 48, 48, 48)
        })
    }
}
