package com.rickeal.agent.core.agent.history

import kotlinx.serialization.Serializable

/**
 * 回合状态机（Octop history_v2 的回合终态对齐）。
 * COMPLETE=正常交付；INTERRUPTED=取消/中断；PAUSED=审批等长间隔挂起（预留）；
 * FAILED=引擎失败；PARTIAL=碎片完整性不足（本轮不产生，读侧降级处理）。
 */
@Serializable
enum class TurnState {
    COMPLETE,
    INTERRUPTED,
    PAUSED,
    FAILED,
    PARTIAL,
}

/** 一个已归档正文的内容寻址引用。 */
@Serializable
data class BlobRef(
    val sha256: String,
    val chars: Int,
)

/**
 * 一个回合（一次 run）的归档记录 —— history_v2 的回合粒度端侧降级
 * （Octop SegmentedHistoryStore 同构；Wave3 裁决：turn 粒度整段 blob，
 * 不做流式切块，AgentRunner 零感知，写入在宿主终态后进行）。
 *
 * 全字段带默认值（serialization 兼容纪律）；结构消息（工具调用/结果）不入池，
 * 权威仍在 AgentRunJournal —— 本记录是「过程可读摘要 + 正文寻址」层。
 */
@Serializable
data class TurnRecord(
    /** turnId = journal runId（一 run 一回合，天然对齐）。 */
    val turnId: String,
    val state: TurnState = TurnState.INTERRUPTED,
    /** 任务输入正文（user_input 行）。 */
    val userTextRef: BlobRef? = null,
    /** 过程消息正文（message 行中有可见文本的），按 journal 行序。 */
    val messageRefs: List<BlobRef> = emptyList(),
    /** 最终回答正文（最后一条 MODEL 可见文本）。 */
    val finalTextRef: BlobRef? = null,
    /** 终止原因名（ModelStopped/MaxRounds/Failed/Cancelled/ProviderStop）。 */
    val termination: String? = null,
    val toolCallCount: Int = 0,
    val startedAtMillis: Long = 0L,
    val settledAtMillis: Long? = null,
    /**
     * 前缀分叉血统（线性降级：起点回合 id）。**本轮无消费方**（s3 审查裁决：
     * journal 天然每 run 一文件，fork 是元数据不是机制），字段预留，树形分叉
     * 阶段再接线。
     */
    val forkedFrom: String? = null,
)
