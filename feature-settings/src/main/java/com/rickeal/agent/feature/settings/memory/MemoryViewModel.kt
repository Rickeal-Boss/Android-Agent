package com.rickeal.agent.feature.settings.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rickeal.agent.core.agent.memory.MemorySection
import com.rickeal.agent.core.data.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 记忆页状态。
 *
 * [corrupted] 为 true 表示记忆文件解析失败（AgentMemory 三态判别的可见化）：
 * 此时增删一律被 core 层拒绝，UI 只展示修复指引，不再提供会静默失败的编辑入口。
 */
data class MemoryUiState(
    val sections: List<MemorySection> = emptyList(),
    val loading: Boolean = true,
    val corrupted: Boolean = false,
    /** 轻量操作回执（「已记住」「已删除」），复用各页的 notice 行为。 */
    val message: String? = null,
)

/**
 * 长期记忆管理 —— Wave4 UI 改造新增的一级页签。
 *
 * 此前记忆只有 `memory_write/read/delete` 三个模型工具入口（用户无法查看、无法纠错），
 * 六路审查（F-P2-6）把它列为「记忆投毒的残余面」：用户连自己被记住了什么都不看不了。
 * 本页补齐人工管理面：查看 / 新增 / 编辑 / 删除，直接调 `container.agentMemory`。
 */
class MemoryViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(MemoryUiState())
    val uiState: StateFlow<MemoryUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            // sections() 失败（文件损坏）时返回空 —— 与 AgentMemory 的写侧三态判别
            // 无法区分「真空」与「损坏」，所以用一次试探写外的手段：直接读文件不存在
            // 的损坏信号。简化：AgentMemory.sections 永不抛；损坏判定靠写侧回执。
            // 这里 loading 结束后若为空且文件存在，让用户按「刷新」重试即可，不额外
            // 引入 AgentMemory 的内部状态。
            val list = runCatching { container.agentMemory.sections() }.getOrDefault(emptyList())
            _uiState.update { it.copy(sections = list, loading = false, corrupted = false) }
        }
    }

    /** 新增或更新（按标题幂等）。返回是否成功（损坏时 core 拒写 → false）。 */
    fun upsert(title: String, content: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching { container.agentMemory.upsert(title, content) }.getOrDefault(false)
            if (ok) {
                _uiState.update { it.copy(message = "已记住：$title") }
                refresh()
            } else {
                _uiState.update { it.copy(corrupted = true, message = null) }
            }
            onDone(ok)
        }
    }

    fun remove(title: String) {
        viewModelScope.launch {
            val removed = runCatching { container.agentMemory.remove(title) }.getOrDefault(false)
            _uiState.update {
                it.copy(
                    message = if (removed) "已删除：$title" else null,
                    corrupted = if (!removed) it.corrupted else it.corrupted,
                )
            }
            refresh()
        }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
