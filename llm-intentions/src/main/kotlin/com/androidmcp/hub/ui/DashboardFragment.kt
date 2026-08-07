package com.androidmcp.hub.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.androidmcp.hub.databinding.FragmentDashboardBinding
import com.androidmcp.hub.relay.RelayEnrollmentClient
import com.androidmcp.hub.relay.RelayEnrollmentStore
import com.androidmcp.hub.security.HubAccessTokenStore
import com.androidmcp.hub.stdio.HubHttpService
import kotlinx.coroutines.launch

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
        renderRelayState()

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

        binding.relayEnrollButton.setOnClickListener {
            showRelayEnrollmentDialog()
        }

        binding.relayEnableButton.setOnClickListener {
            val store = RelayEnrollmentStore(requireContext())
            val current = store.load() ?: return@setOnClickListener
            store.setEnabled(!current.enabled)
            HubHttpService.sharedRelayClient?.refreshFromEnrollment()
            renderRelayState()
            Toast.makeText(
                requireContext(),
                if (current.enabled) "Outbound relay disabled" else "Outbound relay enabled",
                Toast.LENGTH_SHORT,
            ).show()
        }

        binding.relayForgetButton.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Forget relay enrollment?")
                .setMessage(
                    "This disables the outbound tunnel and removes the local relay device/account mapping. " +
                        "It does not by itself revoke the server-side device record; revoke that enrollment on the relay as well."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Forget") { _, _ ->
                    val store = RelayEnrollmentStore(requireContext())
                    store.setEnabled(false)
                    HubHttpService.sharedRelayClient?.refreshFromEnrollment()
                    store.clear()
                    renderRelayState()
                    Toast.makeText(requireContext(), "Local relay enrollment removed", Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }

    private fun showRelayEnrollmentDialog() {
        val context = requireContext()
        val pad = (20 * resources.displayMetrics.density).toInt()
        val fields = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad)
        }
        val baseUrl = EditText(context).apply {
            hint = "Relay HTTPS base URL"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val token = EditText(context).apply {
            hint = "One-time enrollment token"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val label = EditText(context).apply {
            hint = "Device label"
            setText(Build.MODEL ?: "Android device")
            inputType = InputType.TYPE_CLASS_TEXT
        }
        fields.addView(baseUrl)
        fields.addView(token)
        fields.addView(label)

        val dialog = AlertDialog.Builder(context)
            .setTitle("Enroll Intentions Relay")
            .setMessage(
                "Enrollment proves possession of this phone's non-exportable relay signing key. " +
                    "The tunnel remains disabled after enrollment."
            )
            .setView(fields)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Enroll", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val relayUrl = baseUrl.text.toString().trim()
                val oneTimeToken = token.text.toString().trim()
                val deviceLabel = label.text.toString().trim()
                if (relayUrl.isBlank() || oneTimeToken.isBlank() || deviceLabel.isBlank()) {
                    Toast.makeText(context, "All enrollment fields are required", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = runCatching {
                        RelayEnrollmentClient(context).enroll(
                            relayBaseUrl = relayUrl,
                            oneTimeToken = oneTimeToken,
                            deviceLabel = deviceLabel,
                        )
                    }
                    // Never retain the one-time token in the view longer than necessary.
                    token.text?.clear()
                    result.onSuccess { enrollment ->
                        dialog.dismiss()
                        renderRelayState()
                        Toast.makeText(
                            context,
                            "Enrolled ${enrollment.deviceId.take(8)}…; outbound relay is still disabled",
                            Toast.LENGTH_LONG,
                        ).show()
                    }.onFailure { error ->
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        Toast.makeText(
                            context,
                            "Relay enrollment failed: ${error.message ?: "unknown error"}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun renderRelayState() {
        val enrollment = RelayEnrollmentStore(requireContext()).load()
        if (enrollment == null) {
            binding.relayStatusText.text = "Not enrolled"
            binding.relayEnrollButton.text = "Enroll relay"
            binding.relayEnableButton.isEnabled = false
            binding.relayEnableButton.text = "Enable outbound relay"
            binding.relayForgetButton.isEnabled = false
            return
        }

        binding.relayStatusText.text = buildString {
            append(if (enrollment.enabled) "Enabled" else "Enrolled, disabled")
            append(" • account ")
            append(enrollment.accountId.take(24))
            append(" • device ")
            append(enrollment.deviceId.take(8))
            append("…")
        }
        binding.relayEnrollButton.text = "Replace enrollment"
        binding.relayEnableButton.isEnabled = true
        binding.relayEnableButton.text = if (enrollment.enabled) {
            "Disable outbound relay"
        } else {
            "Enable outbound relay"
        }
        binding.relayForgetButton.isEnabled = true
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
        if (_binding != null) {
            renderLocalConfig()
            renderRelayState()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
