package com.androidmcp.hub.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.androidmcp.hub.databinding.ItemAppBinding
import com.androidmcp.hub.discovery.DiscoveredApp
import com.androidmcp.hub.health.IntentHealthMonitor

class AppsAdapter : RecyclerView.Adapter<AppsAdapter.AppViewHolder>() {

    private var apps: List<DiscoveredApp> = emptyList()
    private var healthMap: Map<String, IntentHealthMonitor.HealthStatus> = emptyMap()

    fun submitApps(newApps: List<DiscoveredApp>) {
        apps = newApps
        notifyDataSetChanged()
    }

    fun submitHealth(statuses: Map<String, IntentHealthMonitor.HealthStatus>) {
        healthMap = statuses
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AppViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(apps[position], healthMap[apps[position].packageName])
    }

    override fun getItemCount() = apps.size

    class AppViewHolder(private val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(app: DiscoveredApp, health: IntentHealthMonitor.HealthStatus?) {
            binding.namespaceText.text = app.namespace
            binding.packageText.text = app.packageName
            binding.toolCountText.text = "${app.tools.size} tools"

            when {
                health == null -> {
                    binding.healthDot.setBackgroundColor(0xFF9E9E9E.toInt()) // gray
                    binding.latencyText.text = "..."
                }
                health.alive -> {
                    binding.healthDot.setBackgroundColor(0xFF4CAF50.toInt()) // green
                    binding.latencyText.text = "${health.roundTripMs}ms"
                }
                else -> {
                    binding.healthDot.setBackgroundColor(0xFFF44336.toInt()) // red
                    binding.latencyText.text = health.error ?: "Error"
                }
            }
        }
    }
}
