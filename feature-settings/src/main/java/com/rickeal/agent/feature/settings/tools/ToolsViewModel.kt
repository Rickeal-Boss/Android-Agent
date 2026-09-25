package com.rickeal.agent.feature.settings.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rickeal.agent.core.data.AppContainer
import com.rickeal.agent.core.model.AgentLogStore
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
    /**
     * 经搜索 / 分类 / 启用筛选后要展示的列表（[tools] 的子集）。
     *
     * 派生字段集中在 ViewModel 里重算（见文件末尾的 `refiltered()`），UI 只读不算 ——
     * 避免筛选逻辑散落在组合函数里、与状态更新脱节。
     */
    val visibleTools: List<ToolSpecUi> = emptyList(),
    /** 当前**实际存在**的分类（规范顺序，见 [TOOL_CATEGORY_ORDER]）。 */
    val categories: List<String> = emptyList(),
    /** 搜索关键词：匹配工具名 / 描述，大小写不敏感。 */
    val query: String = "",
    /** 选中的分类；null = 全部分类。 */
    val category: String? = null,
    /** 只看已启用的工具（顶部分段「已启用」）。 */
    val enabledOnly: Boolean = false,
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
                // 重算派生列表：工具增删 / 启停后，筛选结果与分类集合都要跟着更新。
            ).refiltered()
        }
    }

    fun onQueryChange(text: String) {
        _uiState.update { it.copy(query = text).refiltered() }
    }

    /** [category] 传 null 表示「全部分类」。 */
    fun onCategoryChange(category: String?) {
        _uiState.update { it.copy(category = category).refiltered() }
    }

    fun onEnabledOnlyChange(enabledOnly: Boolean) {
        _uiState.update { it.copy(enabledOnly = enabledOnly).refiltered() }
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
                        // 工具实现里抛的异常消息可能含它自己拼的请求头 / URL，
                        // 上屏前必须脱敏（这里是要展示给用户看的，所以过 sanitizeUserFacing，
                        // 不走 AgentLogStore.error —— 那条是给诊断页的，规则不同）。
                        error = outcome.exceptionOrNull()?.message
                            ?.let { AgentLogStore.sanitizeUserFacing(it) }
                            ?: "执行异常",
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

/**
 * 依据当前筛选态重算派生列表（[ToolsUiState.visibleTools] / [ToolsUiState.categories]）。
 *
 * 派生字段只在**这一处**重算：`tools` 变化（reload）与任一筛选态变化（query / category /
 * enabledOnly）都汇入这里，杜绝「筛选态改了但列表没跟着变」这类分散更新的漏洞。
 *
 * 分类集合 = 规范顺序中**存在**的那些 + 表外分类（按字典序追加，防止将来新增分类
 * 因不在 [TOOL_CATEGORY_ORDER] 里而漏出 chip 行）。
 */
private fun ToolsUiState.refiltered(): ToolsUiState {
    val present = tools.map { it.category }.toSet()
    val ordered = TOOL_CATEGORY_ORDER.filter { it in present } +
        present.filterNot { it in TOOL_CATEGORY_ORDER }.sorted()
    val keyword = query.trim()
    val visible = tools.filter { tool ->
        (category == null || tool.category == category) &&
            (!enabledOnly || tool.enabled) &&
            (
                keyword.isEmpty() ||
                    tool.name.contains(keyword, ignoreCase = true) ||
                    tool.description.contains(keyword, ignoreCase = true)
                )
    }
    return copy(visibleTools = visible, categories = ordered)
}
