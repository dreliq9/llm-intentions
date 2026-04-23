package com.llmintentions.people

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
import com.llmintentions.people.databinding.SheetExecuteBinding
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

class ToolExecuteSheet : BottomSheetDialogFragment() {
    private var _binding: SheetExecuteBinding? = null
    private val binding get() = _binding!!
    private lateinit var toolDef: ToolDef
    private var toolHandler: (suspend (JsonObject) -> String)? = null
    private val paramInputs = mutableMapOf<String, Any>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        fun newInstance(tool: ToolDef, handler: suspend (JsonObject) -> String) = ToolExecuteSheet().apply { toolDef = tool; toolHandler = handler }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetExecuteBinding.inflate(inflater, container, false); return binding.root
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)?.let {
            BottomSheetBehavior.from(it).apply { state = BottomSheetBehavior.STATE_EXPANDED; skipCollapsed = true }
            it.layoutParams.height = (resources.displayMetrics.heightPixels * 0.85).toInt(); it.requestLayout()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.sheetToolName.text = toolDef.name; binding.sheetToolDesc.text = toolDef.description
        buildParamInputs(); binding.executeButton.setOnClickListener { execute() }
    }

    private fun buildParamInputs() {
        binding.paramsContainer.removeAllViews()
        for (param in toolDef.params) {
            if (param.type == ParamType.BOOLEAN) {
                val sw = MaterialSwitch(requireContext()).apply {
                    text = "${param.name}${if (!param.required) " (optional)" else ""}"
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8 }
                }
                binding.paramsContainer.addView(sw); paramInputs[param.name] = sw
            } else {
                val til = TextInputLayout(requireContext(), null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8 }
                    hint = "${param.name}${if (!param.required) " (optional)" else ""}"; helperText = param.description
                }
                val et = TextInputEditText(requireContext()).apply {
                    if (param.type == ParamType.INTEGER) inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                }
                til.addView(et); binding.paramsContainer.addView(til); paramInputs[param.name] = et
            }
        }
    }

    private fun execute() {
        val handler = toolHandler ?: return
        binding.executeButton.isEnabled = false; binding.progress.visibility = View.VISIBLE
        binding.resultLabel.visibility = View.GONE; binding.resultScroll.visibility = View.GONE
        val args = buildJsonObject {
            for (p in toolDef.params) {
                val input = paramInputs[p.name] ?: continue
                when (input) {
                    is MaterialSwitch -> put(p.name, input.isChecked)
                    is TextInputEditText -> { val t = input.text?.toString()?.trim() ?: ""; if (t.isEmpty()) continue; if (p.type == ParamType.INTEGER) t.toLongOrNull()?.let { put(p.name, it) } else put(p.name, t) }
                }
            }
        }
        scope.launch {
            var isError = false
            val result = try { withContext(Dispatchers.IO) { handler(args) } } catch (e: Exception) { isError = true; "Error: ${e.message}" }
            if (_binding == null) return@launch
            binding.executeButton.isEnabled = true; binding.progress.visibility = View.GONE
            binding.resultLabel.visibility = View.VISIBLE; binding.resultScroll.visibility = View.VISIBLE
            binding.resultText.text = try { Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(result)) } catch (_: Exception) { result }
            ToolCallLog.add("UI", toolDef.name, result, isError)
        }
    }

    override fun onDestroyView() { super.onDestroyView(); scope.cancel(); _binding = null }
}
