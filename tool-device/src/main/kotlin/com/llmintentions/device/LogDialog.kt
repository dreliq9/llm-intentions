package com.llmintentions.device

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.llmintentions.device.databinding.ItemLogBinding

object LogDialog {
    fun show(context: Context) {
        val entries = ToolCallLog.entries()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 0)
        }

        if (entries.isEmpty()) {
            container.addView(TextView(context).apply {
                text = context.getString(R.string.log_empty)
                textSize = 14f
                setPadding(0, 32, 0, 32)
            })
        } else {
            for (entry in entries) {
                val binding = ItemLogBinding.inflate(LayoutInflater.from(context), container, false)
                binding.logSource.text = entry.source
                binding.logSource.setTextColor(
                    ContextCompat.getColor(context,
                        if (entry.source == "LLM") R.color.source_llm else R.color.source_ui))
                binding.logToolName.text = entry.toolName
                binding.logTime.text = entry.formattedTime()
                binding.logResultPreview.text = entry.resultPreview
                if (entry.isError) {
                    binding.logResultPreview.setTextColor(
                        ContextCompat.getColor(context, R.color.status_red))
                }
                container.addView(binding.root)
            }
        }

        val scrollView = ScrollView(context).apply { addView(container) }

        AlertDialog.Builder(context, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle(R.string.log_title)
            .setView(scrollView)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.clear_log) { _, _ -> ToolCallLog.clear() }
            .show()
    }
}
