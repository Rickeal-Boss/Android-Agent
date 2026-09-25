package com.rickeal.agent.feature.settings.storage

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rickeal.agent.core.data.AppContainer
import com.rickeal.agent.core.data.StorageBucket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class StorageUiState(
    val loading: Boolean = true,
    /** 全部分桶（顺序与 [AppContainer.storageUsageStore] 的源列表一致）。 */
    val buckets: List<StorageBucket> = emptyList(),
    /**
     * 引擎是否正忙（有在途生成 / run 持有引擎）。来自 [AppContainer.agentRunner] 的 `isBusy`。
     *
     * 忙时**禁止一切清除**：`cache` 桶清的就是 `context.cacheDir`，而引擎的
     * `engineEnvironment.cacheDir` 也指向它 —— 运行中清理等于拆引擎的地基（见 [StorageViewModel] KDoc）。
     */
    val engineBusy: Boolean = false,
    /** 非空表示：一个**可清除**分桶正在等待二次确认。 */
    val pendingClear: StorageBucket? = null,
    /** 非空表示：一个**用户资产**分桶正在展示详情。 */
    val detail: StorageBucket? = null,
    val message: String? = null,
) {
    val totalBytes: Long get() = buckets.sumOf { it.bytes }
    val clearableBuckets: List<StorageBucket> get() = buckets.filter { it.clearable }
    val assetBuckets: List<StorageBucket> get() = buckets.filterNot { it.clearable }
}

/** 引擎忙时清除被拒的统一提示。 */
private const val ENGINE_BUSY_MESSAGE = "正在生成，暂不可清理"

/**
 * 存储空间页（Wave 10 Phase 2b C-2）。
 *
 * 所有用量都来自 [AppContainer.storageUsageStore]（路径唯一事实来源），本 VM 只负责
 * 「取数 → 二次确认 → 清除 → 重新取数」这条闭环。
 *
 * ## 引擎忙时不清理（R1）
 *
 * `cache` 桶清的是 `context.cacheDir`，而 `engineEnvironment.cacheDir` **也指向它** ——
 * 模型加载 / 生成过程中清缓存可能扰动 LiteRT 的编译产物。所以清除加**双保险**：
 *  - UI 便利层：忙时「清除」按钮禁用、并在可清除段上方给提示；
 *  - **权威闸门**：本 VM 的 [onRequestClear] / [onConfirmClear] 各判一次
 *    （确认框打开期间引擎可能刚好转忙，UI 的 enabled 只是打开那一刻的快照）。
 */
class StorageViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StorageUiState())
    val uiState: StateFlow<StorageUiState> = _uiState.asStateFlow()

    init {
        refresh()
        // 持续观察引擎忙态：它是「清除」能否使用的依据。
        // 判据用 agentRunner.isBusy（StateFlow，与 runMutex 同源）—— 不必像
        // ModelsViewModel.isEngineBusy() 那样额外探引擎：那个额外分支是为「引擎已创建但
        // 尚未进入 runner」的窗口准备的，与「清理时不能拆地基」无关。
        viewModelScope.launch {
            container.agentRunner.isBusy.collect { busy ->
                _uiState.update { it.copy(engineBusy = busy) }
            }
        }
    }

    /** 重新统计全部用量（首次进入 / 清除后）。 */
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            val buckets = container.storageUsageStore.usage()
            _uiState.update { it.copy(loading = false, buckets = buckets) }
        }
    }

    /**
     * 用户点了**可清除**分桶的「清除」→ 弹二次确认。
     *
     * 非可清除（用户资产）的分桶在这里被忽略：清除入口在 UI 上就不该出现，
     * 这里再挡一层是为了防「将来 UI 接错」—— 数据销毁动作宁可多一道冗余。
     *
     * 引擎忙时**不打开确认弹窗**，直接回一句可行动的提示（与 UI 按钮禁用互为冗余）。
     */
    fun onRequestClear(bucket: StorageBucket) {
        if (!bucket.clearable) return
        // ⚠️ 权威判据读 StateFlow 的**当前值**，不读 UI 副本 `engineBusy` —— 后者由上面的
        // collect 写入，相对源流有一次派发延迟。`engineBusy` 字段保留供 UI 显示用。
        if (container.agentRunner.isBusy.value) {
            _uiState.update { it.copy(message = ENGINE_BUSY_MESSAGE) }
            return
        }
        _uiState.update { it.copy(pendingClear = bucket) }
    }

    fun onDismissClear() {
        _uiState.update { it.copy(pendingClear = null) }
    }

    /**
     * 确认清除：删除该桶内容后**立刻重新统计**，让数值当场归零。
     *
     * ⚠️ 这里的忙判是**权威闸门**：确认框打开期间用户可能刚好点了发送、引擎转忙，
     * 而 UI 的 `enabled` 只是打开弹窗那一刻的快照。数据销毁动作的最后一道防线必须在 VM。
     */
    fun onConfirmClear() {
        val bucket = _uiState.value.pendingClear ?: return
        // 同上：权威判据读源流当前值，不读 collect 副本。
        if (container.agentRunner.isBusy.value) {
            // ⚠️ 刻意**不**在这里清 `pendingClear`：弹窗关闭统一由动作区的
            // `dismiss()` → `onDismissRequest` → [onDismissClear] 收尾，出场动画才能播完。
            // 在这里同步清会让调用方先撤掉组合，动画被切断（StorageScreen 动作区注释的承诺会落空）。
            _uiState.update { it.copy(message = ENGINE_BUSY_MESSAGE) }
            return
        }
        viewModelScope.launch {
            // clear() 的返回语义 = "是否至少有一个 target 真的删掉了"。必须据此分支文案：
            // 删除全失败（文件被占用）时若仍提示"已清除"，用户会看到提示说清了、数值却没归零。
            val cleared = container.storageUsageStore.clear(bucket.id)
            val buckets = container.storageUsageStore.usage()
            _uiState.update {
                it.copy(
                    // 同样不清 pendingClear：交给 dismiss() → onDismissClear 收尾（见上）。
                    loading = false,
                    buckets = buckets,
                    message = if (cleared) {
                        "已清除「${bucket.title}」"
                    } else {
                        "未能清除「${bucket.title}」，文件可能被占用"
                    },
                )
            }
        }
    }

    /** 用户点了**用户资产**分桶 → 弹详情（说明这是用户数据、给出管理入口）。 */
    fun onShowDetail(bucket: StorageBucket) {
        if (!bucket.clearable) _uiState.update { it.copy(detail = bucket) }
    }

    fun onDismissDetail() {
        _uiState.update { it.copy(detail = null) }
    }

    fun onDismissMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
