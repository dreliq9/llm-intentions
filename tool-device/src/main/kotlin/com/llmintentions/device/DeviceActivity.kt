package com.llmintentions.device

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.androidmcp.core.registry.ToolRegistry
import com.llmintentions.device.databinding.ActivityMainBinding
import kotlinx.serialization.json.JsonObject

class DeviceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val tools = ToolDef.allTools()
    private val localRegistry = ToolRegistry()
    private var registryReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Start keep-alive service
        val svc = Intent(this, KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)

        setupUI()

        DeviceToolRegistrar.register(localRegistry, applicationContext)
        registryReady = true
    }

    private fun setupUI() {
        binding.logButton.setOnClickListener { LogDialog.show(this) }
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

    override fun onDestroy() {
        DeviceToolRegistrar.shutdown()
        super.onDestroy()
    }
}
