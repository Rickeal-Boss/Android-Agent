package com.rickeal.agent.core.agent.token

import com.rickeal.agent.core.model.TokenUsage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * run 级 token 账本快照（Wave 30 RunTokenLedger 的读侧投影）。
 *
 * 两个口径**刻意分开**，不做换算也不做对账：
 * - [sentTokens]：发送侧估算累计（[com.rickeal.agent.core.model.TokenEstimator] 口径），
 *   由 AgentRunner 发送侧记账块回写 —— 是「我们打算给模型多少上下文」的预估；
 * - [cumulativeIn] / [cumulativeOut]：引擎逐轮回报的 promptTokens / completionTokens
 *   累计 —— 是「引擎真实吃了多少」。两者的偏差（估算 vs 真实）本身就是观测信号，
 *   在这里合并或校准只会制造第三种口径。
 *
 * 「只增不减」是语义目标而非实现约束：压缩触发全量重记时 sentTokens 会如实回落，
 * 投影方不做二次加工（见 [RunTokenLedger.onSendEstimated] KDoc）。
 */
data class RunTokenSnapshot(
    val sentTokens: Long = 0L,
    val cumulativeIn: Long = 0L,
    val cumulativeOut: Long = 0L,
    val updatedAtElapsedMillis: Long = 0L,
) {
    /** 引擎回报侧的真实消耗（进+出），与估算口径的 [sentTokens] 并列呈现。 */
    val engineTotalTokens: Long get() = cumulativeIn + cumulativeOut
}

/**
 * run 级 token 账本（Wave 30）。纯读侧投影：主循环的 [com.rickeal.agent.core.agent.AgentRunner]
 * [com.rickeal.agent.core.agent.RunState.sentTokens] 记账块**保持不变**（Wave 29 A1 刚
 * 终审的 24 字段结构不动），账本只在其后镜像回写 —— 防止重开 A1 Step 2 的 review 面。
 *
 * 接线纪律（KDoc 红线）：AgentRunner 中 sentTokens → 账本的回写点是**全仓唯一**的；
 * 今后任何新的 sentTokens 写点必须同步回写，否则 UI / 后续断路器（TokenBudget）消费的
 * 口径会静默漂移。
 *
 * 消费顺序约定（Wave 30 方案 §2.3）：本波账本只做读侧投影，摘要触发（B.4 双阈值）与
 * 压缩门控口径**不接** —— 摘要门控架构上不能安全接线（runMutex 不可重入），压缩门控
 * 的挂账口径见 AgentRunner 记账块 KDoc 的「只写不读」自认。
 *
 * 纯 Kotlin：StateFlow 来自 kotlinx.coroutines（core-agent 既有依赖），TokenUsage 来自
 * core-model（@Serializable 纯 Kotlin）—— 无任何 Android 类型，可 JVM 直接单测
 * （arch-guard 第 10 条：core-agent 不依赖 core-data / framework）。
 */
interface RunTokenLedger {
    /** 账本快照流。每次回写后 value 立即更新（同线程读无需等待）。 */
    val snapshot: StateFlow<RunTokenSnapshot>

    /**
     * 发送侧估算回写。AgentRunner 发送侧记账块是**唯一调用点**。
     *
     * 参数语义是「当前累计总量」而非本轮增量：实现方**覆盖写**（镜像口径），
     * 不做累加 —— 压缩触发全量重记时调用方给的值可能小于旧累计，投影必须如实
     * 镜像而不是被「只增不减」钳住（否则账本会与 sentTokens 永久分叉）。
     */
    fun onSendEstimated(totalSentTokens: Long)

    /**
     * 引擎回报回写。AgentRunner 生成完成分诊段（accumulator.usage 落 state.lastUsage
     * 处）调用；[usage] 为 null（引擎未回报）时跳过，不产生任何写入。
     */
    fun onEngineUsage(usage: TokenUsage?)
}

/**
 * [RunTokenLedger] 的进程内实现：[MutableStateFlow] + 两个写方法。
 *
 * 线程安全：[kotlinx.coroutines.flow.update] 的 CAS 循环保证两个回写入口并发时
 * 各自的增量不丢（发送侧回写与引擎回报回写可能来自不同协程）。
 *
 * 时间戳口径：账本按会话池化（AppContainer），**没有 run 起点锚**，因此
 * [RunTokenSnapshot.updatedAtElapsedMillis] 记录的是最后一次写入的进程墙钟
 * （[System.currentTimeMillis]）——语义是「这个账本最后活跃在什么时候」，不参与
 * 任何预算/耗时计算（字段名沿用方案 §2.3 原名，语义按池化实际如实申报）。
 * 测试可注入 [clock] 钉死时间行为。
 */
class InMemoryRunTokenLedger(
    private val clock: () -> Long = System::currentTimeMillis,
) : RunTokenLedger {

    private val _snapshot = MutableStateFlow(RunTokenSnapshot())
    override val snapshot: StateFlow<RunTokenSnapshot> = _snapshot.asStateFlow()

    override fun onSendEstimated(totalSentTokens: Long) {
        _snapshot.update { it.copy(sentTokens = totalSentTokens, updatedAtElapsedMillis = clock()) }
    }

    override fun onEngineUsage(usage: TokenUsage?) {
        if (usage == null) return
        _snapshot.update {
            it.copy(
                cumulativeIn = it.cumulativeIn + usage.promptTokens,
                cumulativeOut = it.cumulativeOut + usage.completionTokens,
                updatedAtElapsedMillis = clock(),
            )
        }
    }
}
