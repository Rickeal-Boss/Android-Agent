package com.rickeal.agent.feature.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rickeal.agent.core.data.AppContainer
import com.rickeal.agent.core.engine.local.ModelHealthProbe
import com.rickeal.agent.core.model.AgentLogStore
import com.rickeal.agent.core.model.CriterionHit
import com.rickeal.agent.core.model.ModelHealthVerdict
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 「模型自检」的 UI 状态机（Wave 44 P0-1）。
 *
 * 五态与 [ModelHealthProbe.ProbeOutcome] 一一对应，**质量结论与执行结论严格分离**：
 *  - [Idle] / [Running]：未跑 / 跑中；
 *  - [Completed]：探针跑通（`verdict` 是**质量结论**，BAD/DEGRADED 才是「模型有问题」）；
 *  - [NotRun]：前置不满足（引擎未加载 / 正在生成 / 无激活模型）—— **不是**质量结论；
 *  - [Failed]：执行失败（异常 / 超时）—— **不是**「模型坏」的结论。
 */
@Immutable
sealed interface ProbeUiState {
    data object Idle : ProbeUiState

    data object Running : ProbeUiState

    data class Completed(
        val verdict: ModelHealthVerdict,
        val hits: List<CriterionHit>,
        val sample: String,
        val elapsedMs: Long,
    ) : ProbeUiState

    data class NotRun(val reason: ModelHealthProbe.NotRunReason) : ProbeUiState

    data class Failed(val message: String) : ProbeUiState
}

/**
 * 诊断页的 ViewModel（Wave 44 P0-1 新增）。
 *
 * ## 为什么现在**有** ViewModel（改写自「为什么没有 ViewModel」）
 *
 * 此前诊断页刻意无 ViewModel，理由是「日志收集器在 `:core-model`、那里没有协程依赖、
 * 只能取只读快照」。该理由**已不成立**：模型自检探针引入了**异步 + 可变状态**
 * （running / 结果 / 错误 / 取消），需要一个有生命周期的持有者。对齐同模块惯例
 * （`StorageViewModel` / `ToolsViewModel` / `MemoryViewModel` 均经 `viewModelFactory { Xxx(container) }`
 * 构造），探针状态机归本 VM。
 *
 * ## 单飞
 *
 * [onRunProbe] 在 [ProbeUiState.Running] 时直接返回（双击只跑一次）。探针类另持
 * 单飞标志作第二道防线。
 *
 * ## 持久化：不新增设施，复用 [AgentLogStore]
 *
 * 每次自检结果记一条日志：PASS=`info` / DEGRADED=`warn` / BAD=`error`。`AgentLogFileStore`
 * 的 sink 只把 ERROR 落盘 ⇒ **BAD 自动落盘**（崩溃/重启后仍可查），PASS/DEGRADED 仅存内存
 * （诊断页可见）。零新增文件格式、零迁移。
 */
class DiagnosticsViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val _probeState = MutableStateFlow<ProbeUiState>(ProbeUiState.Idle)
    val probeState: StateFlow<ProbeUiState> = _probeState.asStateFlow()

    /** 用户点「运行自检」。 */
    fun onRunProbe() {
        if (_probeState.value is ProbeUiState.Running) return
        _probeState.value = ProbeUiState.Running
        viewModelScope.launch {
            // 解析当前激活模型 + 推理配置（与对话链路同一事实来源）。
            val modelId = container.settingsRepository.activeModelIdSync()
            val model = container.modelRepository.find(modelId)
            val config = container.settingsRepository.snapshot()
            val outcome = container.modelHealthProbe.run(model, config)
            _probeState.value = outcome.toUiState()
        }
    }

    private fun ModelHealthProbe.ProbeOutcome.toUiState(): ProbeUiState = when (this) {
        is ModelHealthProbe.ProbeOutcome.Completed -> {
            recordLog(verdict, hits, elapsedMs)
            ProbeUiState.Completed(verdict, hits, sample, elapsedMs)
        }
        is ModelHealthProbe.ProbeOutcome.NotRun -> {
            AgentLogStore.info("模型自检未运行：${reason.name}")
            ProbeUiState.NotRun(reason)
        }
        is ModelHealthProbe.ProbeOutcome.Failed -> {
            AgentLogStore.warn("模型自检执行失败：$message")
            ProbeUiState.Failed(message)
        }
    }

    /** 记一条自检结论日志（PASS=info / DEGRADED=warn / BAD=error ⇒ BAD 经既有 sink 落盘）。 */
    private fun recordLog(verdict: ModelHealthVerdict, hits: List<CriterionHit>, elapsedMs: Long) {
        val hitText = if (hits.isEmpty()) {
            "无命中"
        } else {
            hits.joinToString("、") { "${it.id}[${it.severity.name}]" }
        }
        when (verdict) {
            ModelHealthVerdict.PASS ->
                AgentLogStore.info("模型自检通过（耗时 ${elapsedMs}ms）")
            ModelHealthVerdict.DEGRADED ->
                AgentLogStore.warn("模型自检降级：$hitText（耗时 ${elapsedMs}ms）")
            ModelHealthVerdict.BAD ->
                AgentLogStore.error("模型自检失败：$hitText（耗时 ${elapsedMs}ms）")
        }
    }
}
