package com.androidmcp.hub.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.androidmcp.hub.databinding.FragmentInboxBinding

class InboxFragment : Fragment() {

    private var _binding: FragmentInboxBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HubViewModel by activityViewModels()
    private lateinit var adapter: InboxAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentInboxBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = InboxAdapter { id -> viewModel.dismissMessage(id) }
        binding.inboxRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.inboxRecycler.adapter = adapter

        viewModel.inboxMessages.observe(viewLifecycleOwner) { messages ->
            adapter.submitMessages(messages)
            val empty = messages.isEmpty()
            binding.emptyText.visibility = if (empty) View.VISIBLE else View.GONE
            binding.inboxRecycler.visibility = if (empty) View.GONE else View.VISIBLE
            binding.clearButton.visibility = if (empty) View.GONE else View.VISIBLE
        }

        binding.clearButton.setOnClickListener {
            viewModel.clearInbox()
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshInbox()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
