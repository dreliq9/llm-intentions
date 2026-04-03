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
import java.net.NetworkInterface
import androidx.fragment.app.activityViewModels
import com.androidmcp.hub.databinding.FragmentDashboardBinding
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

        binding.portText.text = "Port: ${HubHttpService.PORT}"

        val networkIp = getReachableIp()
        val port = HubHttpService.PORT
        val localConfig = buildString {
            appendLine("{")
            appendLine("  \"mcpServers\": {")
            appendLine("    \"hub\": {")
            appendLine("      \"type\": \"http\",")
            appendLine("      \"url\": \"http://127.0.0.1:$port/mcp\"")
            appendLine("    }")
            appendLine("  }")
            append("}")
        }
        val remoteConfig = buildString {
            appendLine("{")
            appendLine("  \"mcpServers\": {")
            appendLine("    \"hub\": {")
            appendLine("      \"type\": \"http\",")
            appendLine("      \"url\": \"http://$networkIp:$port/mcp\"")
            appendLine("    }")
            appendLine("  }")
            append("}")
        }

        var showingLocal = true
        fun updateConfigDisplay() {
            binding.configJson.text = if (showingLocal) localConfig else remoteConfig
        }
        updateConfigDisplay()

        binding.configLabel.text = if (networkIp != "127.0.0.1")
            "Claude Code Config  (local)" else "Claude Code Config"

        binding.configJson.setOnClickListener {
            if (networkIp == "127.0.0.1") return@setOnClickListener
            showingLocal = !showingLocal
            binding.configLabel.text = "Claude Code Config  (${if (showingLocal) "local" else "remote"})"
            updateConfigDisplay()
        }

        binding.copyConfigButton.setOnClickListener {
            val text = if (showingLocal) localConfig else remoteConfig
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("MCP Config", text))
            val label = if (showingLocal) "Local config copied" else "Remote config copied"
            Toast.makeText(requireContext(), label, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshState()
    }

    private fun getReachableIp(): String {
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                // Prefer Tailscale interface
                if (iface.name.startsWith("tun") || iface.name.startsWith("tailscale") || iface.name.startsWith("utun")) {
                    for (addr in iface.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            return addr.hostAddress ?: continue
                        }
                    }
                }
            }
            // Fall back to any non-loopback IPv4
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
