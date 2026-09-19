package com.rickeal.agent.feature.settings.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rickeal.agent.core.data.AppContainer
import com.rickeal.agent.core.model.ToolParameter
import com.rickeal.agent.core.model.ToolParamType
import com.rickeal.agent.core.model.ToolResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class ToolSpecUi(
    val name: String,
    val description: String,
    val category: String,
    val enabled: Boolean,
    val dangerous: Boolean,
    val requiresConfirmation: Boolean,
    val parameters: List<ToolParameter>,
)

@Immutable
data class ToolsUiState(
    val tools: List<ToolSpecUi> = emptyList(),
    val testName: String? = null,
    val testArgs: String = "{}",
    val testResult: String? = null,
    val testOk: Boolean = true,
    val running: Boolean = false,
    val error: String? = null,
)

class ToolsViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ToolsUiState())
    val uiState: StateFlow<ToolsUiState> = _uiState.asStateFlow()

    init {
        reload()
    }

    private fun reload() {
        val registry = container.toolRegistry
        _uiState.update {
            it.copy(
                tools = registry.all().map { tool ->
                    ToolSpecUi(
                        name = tool.spec.name,
                        description = tool.spec.description,
                        category = tool.spec.category,
                        enabled = registry.isEnabled(tool.spec.name),
                        dangerous = tool.spec.dangerous,
                        requiresConfirmation = tool.spec.requiresConfirmation,
                        parameters = tool.spec.parameters,
                    )
                },
            )
        }
    }

    fun onToggle(name: String, enabled: Boolean) {
        container.toolRegistry.setEnabled(name, enabled)
        reload()
    }

    fun onSelectTest(name: String) {
        val spec = _uiState.value.tools.firstOrNull { it.name == name } ?: return
        val template = if (spec.parameters.isEmpty()) {
            "{}"
        } else {
            spec.parameters.joinToString(",", prefix = "{", postfix = "}") { param ->
                val value = when (param.type) {
                    ToolParamType.STRING -> "\"\""
                    ToolParamType.NUMBER -> "0"
                    ToolParamType.INTEGER -> "0"
                    ToolParamType.BOOLEAN -> "false"
                    ToolParamType.ARRAY -> "[]"
                    ToolParamType.OBJECT -> "{}"
                }
                "\"" + param.name + "\":" + value
            }
        }
        _uiState.update {
            it.copy(testName = name, testArgs = template, testResult = null, error = null)
        }
    }

    fun onTestArgsChange(text: String) {
        _uiState.update { it.copy(testArgs = text) }
    }

    fun onRunTest() {
        val name = _uiState.value.testName ?: return
        val args = _uiState.value.testArgs
        _uiState.update { it.copy(running = true, error = null, testResult = null) }
        viewModelScope.launch {
            val tool = container.toolRegistry.all().firstOrNull { it.spec.name == name }
            if (tool == null) {
                _uiState.update { it.copy(running = false, error = "工具不存在：$name") }
                return@launch
            }
            val outcome = runCatching { tool.invoke(args) }
            val result = outcome.getOrNull()
            if (result == null) {
                _uiState.update {
                    it.copy(
                        running = false,
                        error = outcome.exceptionOrNull()?.message ?: "执行异常",
                    )
                }
            } else {
                val text = buildString {
                    append(if (result.ok) "成功" else "失败")
                    append(" · ${result.elapsedMillis}ms")
                    if (result.truncated) append(" · 输出已截断")
                    append('\n')
                    append(result.output.ifBlank { result.errorMessage.orEmpty() })
                }
                _uiState.update {
                    it.copy(running = false, testResult = text, testOk = result.ok)
                }
            }
        }
    }

    fun onDismissError() {
        _uiState.update { it.copy(error = null) }
    }
}
