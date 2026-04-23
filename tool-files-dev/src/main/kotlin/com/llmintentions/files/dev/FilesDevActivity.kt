package com.llmintentions.files.dev

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.androidmcp.core.registry.ToolRegistry
import com.llmintentions.files.dev.databinding.ActivityMainBinding
import kotlinx.serialization.json.JsonObject

class FilesDevActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val tools = ToolDef.allTools()

    // We use the service's registry to execute tools in-process
    private val localRegistry = ToolRegistry()
    private var registryReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Start keep-alive service
        val svc = Intent(this, KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)

        setupToolbar()
        setupStatusCard()
        setupToolList()

        // Register tools locally so the UI can execute them directly
        FilesDevToolRegistrar.register(localRegistry, applicationContext)
        registryReady = true
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun setupToolbar() {
        binding.logButton.setOnClickListener {
            LogDialog.show(this)
        }
    }

    private fun setupStatusCard() {
        updatePermissionStatus()
        binding.grantButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 30) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
        }
    }

    private fun updatePermissionStatus() {
        val hasAccess = if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else true

        val dot = binding.statusDot.background as? GradientDrawable
        if (hasAccess) {
            dot?.setColor(ContextCompat.getColor(this, R.color.status_green))
            binding.statusText.text = getString(R.string.status_granted)
            binding.grantButton.visibility = View.GONE
        } else {
            dot?.setColor(ContextCompat.getColor(this, R.color.status_red))
            binding.statusText.text = getString(R.string.status_denied)
            binding.grantButton.visibility = View.VISIBLE
        }
    }

    private fun setupToolList() {
        binding.toolCount.text = "${tools.size} tools available"
        binding.toolList.layoutManager = LinearLayoutManager(this)
        binding.toolList.adapter = ToolAdapter(tools) { tool -> openExecuteSheet(tool) }
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
