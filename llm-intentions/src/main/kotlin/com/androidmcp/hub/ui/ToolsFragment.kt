package com.androidmcp.hub.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.androidmcp.hub.databinding.FragmentToolsBinding

class ToolsFragment : Fragment() {

    private var _binding: FragmentToolsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HubViewModel by activityViewModels()
    private lateinit var adapter: ToolsAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentToolsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ToolsAdapter()
        binding.toolsRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.toolsRecycler.adapter = adapter

        viewModel.toolsByNamespace.observe(viewLifecycleOwner) { groups ->
            adapter.submitGroups(groups)
            binding.emptyText.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
            binding.toolsRecycler.visibility = if (groups.isEmpty()) View.GONE else View.VISIBLE
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
