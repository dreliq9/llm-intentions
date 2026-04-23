package com.llmintentions.people

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.androidmcp.core.registry.ToolRegistry
import com.llmintentions.people.databinding.ActivityMainBinding
import kotlinx.serialization.json.JsonObject

class PeopleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val tools = ToolDef.allTools()
    private val localRegistry = ToolRegistry()
    private var registryReady = false

    private val requiredPermissions = arrayOf(
        Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS,
        Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val svc = Intent(this, KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)

        setupUI()

        PeopleToolRegistrar.register(localRegistry, applicationContext)
        registryReady = true
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun setupUI() {
        binding.logButton.setOnClickListener { LogDialog.show(this) }
        binding.grantButton.setOnClickListener {
            val needed = requiredPermissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
        }
        updatePermissionStatus()
        binding.toolCount.text = "${tools.size} tools available"
        binding.toolList.layoutManager = LinearLayoutManager(this)
        binding.toolList.adapter = ToolAdapter(tools) { tool -> openExecuteSheet(tool) }
    }

    private fun updatePermissionStatus() {
        val allGranted = requiredPermissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        val dot = binding.statusDot.background as? GradientDrawable
        if (allGranted) {
            dot?.setColor(ContextCompat.getColor(this, R.color.status_green))
            binding.statusText.text = getString(R.string.status_granted)
            binding.grantButton.visibility = View.GONE
        } else {
            dot?.setColor(ContextCompat.getColor(this, R.color.status_amber))
            binding.statusText.text = getString(R.string.status_partial)
            binding.grantButton.visibility = View.VISIBLE
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updatePermissionStatus()
    }

    private fun openExecuteSheet(tool: ToolDef) {
        if (!registryReady) return
        val mcpTool = localRegistry.get(tool.name) ?: return
        val handler: suspend (JsonObject) -> String = { args ->
            val result = mcpTool.handler(args)
            result.content.joinToString("\n") { it.text ?: "" }
        }
        ToolExecuteSheet.newInstance(tool, handler).show(supportFragmentManager, "execute_${tool.name}")
    }
}
