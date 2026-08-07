package com.androidmcp.hub.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
        renderLocalConfig()

        binding.copyConfigButton.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText("LLM Intentions MCP Config", currentLocalConfig())
            )
            Toast.makeText(requireContext(), "Authenticated local config copied", Toast.LENGTH_SHORT).show()
        }

        binding.rotateTokenButton.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Rotate local access token?")
                .setMessage(
                    "Previously copied local MCP configurations will stop working immediately. " +
                        "You will need to copy the new configuration to any CLI or developer client."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Rotate") { _, _ ->
                    HubAccessTokenStore.rotate(requireContext())
                    renderLocalConfig()
                    Toast.makeText(
                        requireContext(),
                        "Local token rotated. Previous configs are revoked.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                .show()
        }
    }

    private fun renderLocalConfig() {
        binding.configLabel.text = "Authenticated Local MCP Config"
        binding.configJson.text = currentLocalConfig()
        binding.configJson.setOnClickListener(null)
    }

    private fun currentLocalConfig(): String {
        val port = HubHttpService.PORT
        val accessToken = HubAccessTokenStore.getOrCreate(requireContext())
        return buildString {
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
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshState()
        if (_binding != null) renderLocalConfig()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
