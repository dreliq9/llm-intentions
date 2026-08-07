package com.androidmcp.hub.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.androidmcp.hub.databinding.FragmentDashboardBinding
import com.androidmcp.hub.security.HubAccessTokenStore
import com.androidmcp.hub.stdio.HubHttpService

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HubViewModel by activityViewModels()
    private var suppressToggle = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.isServiceRunning.observe(viewLifecycleOwner) { running ->
            binding.statusText.text = if (running) "Running" else "Stopped"
            binding.statusDot.setBackgroundColor(
                if (running) 0xFF4CAF50.toInt() else 0xFFF44336.toInt()
            )
            suppressToggle = true
            binding.serviceToggle.isChecked = running
            suppressToggle = false
        }

        viewModel.toolCount.observe(viewLifecycleOwner) { count ->
            binding.toolCountText.text = "Tools: $count"
        }

        viewModel.discoveredApps.observe(viewLifecycleOwner) { apps ->
            binding.appCountText.text = "Connected apps: ${apps.size}"
        }

        viewModel.isRefreshing.observe(viewLifecycleOwner) { refreshing ->
            binding.refreshButton.isEnabled = !refreshing
            binding.refreshButton.text = if (refreshing) "Refreshing..." else "Refresh Apps"
        }

        binding.serviceToggle.setOnCheckedChangeListener { _, isChecked ->
            if (suppressToggle) return@setOnCheckedChangeListener
            if (isChecked) viewModel.startService() else viewModel.stopService()
        }

        binding.refreshButton.setOnClickListener {
            viewModel.refreshApps()
        }

        binding.portText.text = "Port: ${HubHttpService.PORT} (localhost only)"

        val port = HubHttpService.PORT
        val accessToken = HubAccessTokenStore.getOrCreate(requireContext())
        val localConfig = buildString {
            appendLine("{")
            appendLine("  \"mcpServers\": {")
            appendLine("    \"hub\": {")
            appendLine("      \"type\": \"http\",")
            appendLine("      \"url\": \"http://127.0.0.1:$port/mcp\",")
            appendLine("      \"headers\": {")
            appendLine("        \"Authorization\": \"Bearer $accessToken\"")
            appendLine("      }")
            appendLine("    }")
            appendLine("  }")
            append("}")
        }

        binding.configLabel.text = "Authenticated Local MCP Config"
        binding.configJson.text = localConfig
        binding.configJson.setOnClickListener(null)

        binding.copyConfigButton.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("LLM Intentions MCP Config", localConfig))
            Toast.makeText(requireContext(), "Authenticated local config copied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
