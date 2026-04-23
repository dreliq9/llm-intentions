package com.llmintentions.notify

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.llmintentions.notify.databinding.ItemToolBinding

class ToolAdapter(
    private val tools: List<ToolDef>,
    private val onClick: (ToolDef) -> Unit
) : RecyclerView.Adapter<ToolAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemToolBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemToolBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val tool = tools[position]
        holder.binding.toolName.text = tool.name
        holder.binding.toolDescription.text = tool.description
        holder.binding.toolParamCount.text = if (tool.params.isEmpty()) {
            holder.itemView.context.getString(R.string.no_params)
        } else {
            holder.itemView.context.getString(R.string.param_count, tool.params.size)
        }
        holder.itemView.setOnClickListener { onClick(tool) }
    }

    override fun getItemCount() = tools.size
}
