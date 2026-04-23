package com.llmintentions.people

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.llmintentions.people.databinding.ItemToolBinding

class ToolAdapter(private val tools: List<ToolDef>, private val onClick: (ToolDef) -> Unit) : RecyclerView.Adapter<ToolAdapter.VH>() {
    class VH(val b: ItemToolBinding) : RecyclerView.ViewHolder(b.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(ItemToolBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: VH, position: Int) {
        val tool = tools[position]
        holder.b.toolName.text = tool.name; holder.b.toolDescription.text = tool.description
        holder.b.toolParamCount.text = if (tool.params.isEmpty()) holder.itemView.context.getString(R.string.no_params) else holder.itemView.context.getString(R.string.param_count, tool.params.size)
        holder.itemView.setOnClickListener { onClick(tool) }
    }
    override fun getItemCount() = tools.size
}
