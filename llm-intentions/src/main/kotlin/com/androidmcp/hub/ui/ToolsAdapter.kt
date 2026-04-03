package com.androidmcp.hub.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.androidmcp.core.protocol.ToolInfo
import com.androidmcp.hub.databinding.ItemToolBinding
import com.androidmcp.hub.databinding.ItemToolGroupBinding

class ToolsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    data class GroupEntry(val namespace: String, val tools: List<ToolInfo>)

    private var groups: List<GroupEntry> = emptyList()
    private val expandedGroups = mutableSetOf<String>()
    private var flatList: List<Any> = emptyList() // mix of GroupEntry and ToolInfo

    fun submitGroups(toolsByNamespace: Map<String, List<ToolInfo>>) {
        groups = toolsByNamespace.entries
            .sortedBy { it.key }
            .map { GroupEntry(it.key, it.value.sortedBy { t -> t.name }) }
        rebuildFlatList()
    }

    private fun rebuildFlatList() {
        val list = mutableListOf<Any>()
        for (group in groups) {
            list.add(group)
            if (group.namespace in expandedGroups) {
                list.addAll(group.tools)
            }
        }
        flatList = list
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return if (flatList[position] is GroupEntry) VIEW_TYPE_GROUP else VIEW_TYPE_TOOL
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_GROUP) {
            GroupViewHolder(ItemToolGroupBinding.inflate(inflater, parent, false))
        } else {
            ToolViewHolder(ItemToolBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is GroupViewHolder -> {
                val group = flatList[position] as GroupEntry
                holder.bind(group, group.namespace in expandedGroups)
                holder.itemView.setOnClickListener {
                    if (group.namespace in expandedGroups) {
                        expandedGroups.remove(group.namespace)
                    } else {
                        expandedGroups.add(group.namespace)
                    }
                    rebuildFlatList()
                }
            }
            is ToolViewHolder -> {
                holder.bind(flatList[position] as ToolInfo)
            }
        }
    }

    override fun getItemCount() = flatList.size

    class GroupViewHolder(private val binding: ItemToolGroupBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(group: GroupEntry, expanded: Boolean) {
            binding.groupName.text = "${group.namespace}.*"
            binding.groupCount.text = "${group.tools.size} tools"
            binding.expandIcon.rotation = if (expanded) 180f else 0f
        }
    }

    class ToolViewHolder(private val binding: ItemToolBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(tool: ToolInfo) {
            binding.toolName.text = tool.name
            binding.toolDescription.text = tool.description
        }
    }

    companion object {
        private const val VIEW_TYPE_GROUP = 0
        private const val VIEW_TYPE_TOOL = 1
    }
}
