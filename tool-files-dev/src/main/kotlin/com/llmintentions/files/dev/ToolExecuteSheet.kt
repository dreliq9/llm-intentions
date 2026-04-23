package com.llmintentions.files.dev

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.llmintentions.files.dev.databinding.SheetExecuteBinding
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

class ToolExecuteSheet : BottomSheetDialogFragment() {

    private var _binding: SheetExecuteBinding? = null
    private val binding get() = _binding!!

    private lateinit var toolDef: ToolDef
    private var toolHandler: (suspend (JsonObject) -> String)? = null

    private val paramInputs = mutableMapOf<String, Any>() // TextInputEditText or MaterialSwitch
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        fun newInstance(tool: ToolDef, handler: suspend (JsonObject) -> String): ToolExecuteSheet {
            return ToolExecuteSheet().apply {
                this.toolDef = tool
                this.toolHandler = handler
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetExecuteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        // Expand sheet fully so results are visible and taps don't miss
        val bottomSheet = (dialog as? BottomSheetDialog)
            ?.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let {
            val behavior = BottomSheetBehavior.from(it)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
            // Allow sheet to take up to 85% of screen
            it.layoutParams.height = (resources.displayMetrics.heightPixels * 0.85).toInt()
            it.requestLayout()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.sheetToolName.text = toolDef.name
        binding.sheetToolDesc.text = toolDef.description

        buildParamInputs()

        binding.executeButton.setOnClickListener { execute() }
    }

    private fun buildParamInputs() {
        val container = binding.paramsContainer
        container.removeAllViews()

        for (param in toolDef.params) {
            when (param.type) {
                ParamType.BOOLEAN -> {
                    val switch = MaterialSwitch(requireContext()).apply {
                        text = "${param.name}${if (!param.required) " (optional)" else ""}"
                        isChecked = false
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = 8 }
                    }
                    container.addView(switch)
                    paramInputs[param.name] = switch
                }
                ParamType.STRING, ParamType.INTEGER -> {
                    val til = TextInputLayout(
                        requireContext(),
                        null,
                        com.google.android.material.R.attr.textInputOutlinedStyle
                    ).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = 8 }
                        hint = "${param.name}${if (!param.required) " (optional)" else ""}"
                        helperText = param.description
                    }
                    val editText = TextInputEditText(requireContext()).apply {
                        if (param.type == ParamType.INTEGER) {
                            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                                    android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                        }
                    }
                    til.addView(editText)
                    container.addView(til)
                    paramInputs[param.name] = editText
                }
            }
        }
    }

    private fun execute() {
        val handler = toolHandler ?: return
        binding.executeButton.isEnabled = false
        binding.progress.visibility = View.VISIBLE
        binding.resultLabel.visibility = View.GONE
        binding.resultScroll.visibility = View.GONE

        val args = buildJsonObject {
            for (param in toolDef.params) {
                val input = paramInputs[param.name] ?: continue
                when (input) {
                    is MaterialSwitch -> {
                        put(param.name, input.isChecked)
                    }
                    is TextInputEditText -> {
                        val text = input.text?.toString()?.trim() ?: ""
                        if (text.isEmpty()) continue
                        when (param.type) {
                            ParamType.INTEGER -> {
                                text.toLongOrNull()?.let { put(param.name, it) }
                            }
                            else -> put(param.name, text)
                        }
                    }
                }
            }
        }

        scope.launch {
            var isError = false
            val result = try {
                withContext(Dispatchers.IO) {
                    handler(args)
                }
            } catch (e: Exception) {
                isError = true
                "Error: ${e.message}"
            }

            if (_binding == null) return@launch

            binding.executeButton.isEnabled = true
            binding.progress.visibility = View.GONE
            binding.resultLabel.visibility = View.VISIBLE
            binding.resultScroll.visibility = View.VISIBLE

            // Pretty-print JSON if possible
            val displayResult = try {
                val json = Json { prettyPrint = true }
                val element = Json.parseToJsonElement(result)
                json.encodeToString(JsonElement.serializer(), element)
            } catch (_: Exception) {
                result
            }

            binding.resultText.text = displayResult

            ToolCallLog.add("UI", toolDef.name, result, isError)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
        _binding = null
    }
}
