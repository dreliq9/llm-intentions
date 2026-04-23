package com.llmintentions.notify

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.androidmcp.core.registry.ToolRegistry
import com.llmintentions.notify.databinding.ActivityMainBinding
import kotlinx.serialization.json.JsonObject

class NotifyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val tools = ToolDef.allTools()
    private val localRegistry = ToolRegistry()
    private var registryReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val svc = Intent(this, KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)

        setupUI()

        NotifyToolRegistrar.register(localRegistry, applicationContext)
        registryReady = true
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun setupUI() {
        binding.logButton.setOnClickListener { LogDialog.show(this) }

        binding.grantButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        updatePermissionStatus()

        binding.toolCount.text = "${tools.size} tools available"
        binding.toolList.layoutManager = LinearLayoutManager(this)
        binding.toolList.adapter = ToolAdapter(tools) { tool -> openExecuteSheet(tool) }
    }

    private fun updatePermissionStatus() {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true

        val dot = binding.statusDot.background as? GradientDrawable
        if (enabled) {
            dot?.setColor(ContextCompat.getColor(this, R.color.status_green))
            binding.statusText.text = getString(R.string.status_enabled)
            binding.grantButton.visibility = View.GONE
        } else {
            dot?.setColor(ContextCompat.getColor(this, R.color.status_red))
            binding.statusText.text = getString(R.string.status_disabled)
            binding.grantButton.visibility = View.VISIBLE
        }
    }

    private fun openExecuteSheet(tool: ToolDef) {
        if (!registryReady) return
        val mcpTool = localRegistry.get(tool.name) ?: return
        val handler: suspend (JsonObject) -> String = { args ->
            val result = mcpTool.handler(args)
            result.content.joinToString("\n") { it.text ?: "" }
        }
        ToolExecuteSheet.newInstance(tool, handler)
            .show(supportFragmentManager, "execute_${tool.name}")
    }
}
