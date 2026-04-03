package com.androidmcp.hub.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.androidmcp.hub.databinding.FragmentAppsBinding

class AppsFragment : Fragment() {

    private var _binding: FragmentAppsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HubViewModel by activityViewModels()
    private lateinit var adapter: AppsAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAppsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = AppsAdapter()
        binding.appsRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.appsRecycler.adapter = adapter

        viewModel.discoveredApps.observe(viewLifecycleOwner) { apps ->
            adapter.submitApps(apps)
            binding.emptyText.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
            binding.appsRecycler.visibility = if (apps.isEmpty()) View.GONE else View.VISIBLE
        }

        viewModel.healthStatuses.observe(viewLifecycleOwner) { statuses ->
            adapter.submitHealth(statuses)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshState()
        viewModel.checkHealth()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
