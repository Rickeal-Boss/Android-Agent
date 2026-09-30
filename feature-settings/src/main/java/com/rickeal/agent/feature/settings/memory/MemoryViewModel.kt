package com.rickeal.agent.feature.settings.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rickeal.agent.core.agent.memory.AgentMemory
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
 * 两个失败通道分工明确（Wave 36 E5）：
 *  - [message]：列表区底部的轻量回执（「已记住 / 已删除 / 未找到」），复用各页的 notice 行为，
 *    常驻可见（`MemoryScreen` 的 notice `Box` 位于 LazyColumn 之外的外层 Column，不随列表滚动）；
 *  - [editError]：**编辑对话框内联**的写失败原因。之所以不复用 [message]：写失败的原因属于
 *    **编辑上下文**，应与输入框同屏就近显示；且失败时对话框不关以保留用户输入 —— 故用独立通道
 *    内联在对话框内，而不是复用列表回执通道。
 */
data class MemoryUiState(
    val sections: List<MemorySection> = emptyList(),
    val loading: Boolean = true,
    /** 轻量操作回执（「已记住」「已删除」「未找到」），复用各页的 notice 行为。 */
    val message: String? = null,
    /** 编辑对话框内的写失败原因（`AgentMemory.upsertFailureReason` 的产物）；成功 / 取消时清空。 */
    val editError: String? = null,
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
            _uiState.update { it.copy(sections = list, loading = false) }
        }
    }

    /**
     * 新增或更新（按标题幂等）。返回是否成功（正文超限或文件损坏时 core 拒写 → false）。
     *
     * 失败时把原因写入 [MemoryUiState.editError] —— 由编辑对话框**内联展示且不关对话框**，
     * 用户输入得以保留；成功时写 [MemoryUiState.message] 并清 [MemoryUiState.editError]。
     * 是否关闭对话框由 `MemoryScreen` 依据 `onDone(ok)` 的 ok 决定。
     */
    fun upsert(title: String, content: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching { container.agentMemory.upsert(title, content) }.getOrDefault(false)
            if (ok) {
                _uiState.update { it.copy(message = "已记住：$title", editError = null) }
                refresh()
            } else {
                // 失败必须可见、且不能吞掉输入：原因回给编辑对话框内联显示（父层据此不关对话框），
                // 列表底部的 message 置空以免两处重复提示。
                _uiState.update {
                    it.copy(editError = AgentMemory.upsertFailureReason(content.trim().length), message = null)
                }
            }
            onDone(ok)
        }
    }

    fun remove(title: String) {
        viewModelScope.launch {
            val removed = runCatching { container.agentMemory.remove(title) }.getOrDefault(false)
            // remove 回 false 的语义是「条目 / 文件不存在」或「文件损坏」，**不是损坏专属**，
            // 故只给中性回执，不置任何损坏标志（旧写法是自反 no-op：两个分支同值）。
            _uiState.update { it.copy(message = if (removed) "已删除：$title" else "未找到：$title") }
            refresh()
        }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    /** 清空编辑对话框内的失败原因（成功写入、取消、或切换编辑目标时调用）。 */
    fun dismissEditError() {
        _uiState.update { it.copy(editError = null) }
    }
}
