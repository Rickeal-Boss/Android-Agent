package com.rickeal.agent.core.agent

import com.rickeal.agent.core.agent.approval.ToolApprovalHandler
import com.rickeal.agent.core.agent.journal.AgentRunJournal
import com.rickeal.agent.core.model.ChatMessage
import com.rickeal.agent.core.model.InferenceConfig
import com.rickeal.agent.core.model.ModelDescriptor
import com.rickeal.agent.core.model.RemoteEndpoint
import com.rickeal.agent.core.model.ToolCall
import com.rickeal.agent.core.model.ToolResult
import com.rickeal.agent.core.model.ToolSpec
import com.rickeal.agent.core.model.TokenUsage

/**
 * 本轮为什么结束。让 UI / 日志能区分「模型自己停了」和「被轮次上限硬截断」。
 */
enum class TerminationReason {
    /** 模型给出了最终答案 —— 正常停止。 */
    ModelStopped,

    /** 达到 maxRounds 兜底截断（正常情况应该由模型自己停，走到这里说明它没停住）。 */
    MaxRounds,

    /**
     * 确定性模型侧故障（认证失效 / 配额耗尽 / 模型不可用）—— ZCode RunSettlement 的
     * `stopped(provider)` 语义。重试大概率无用，UI 应引导检查端点配置而非盲目重试。
     * 当前仅用于 journal 终态分类（UI 仍走 Failed 事件），后续接端点诊断页。
     */
    ProviderStop,

    /**
     * 宿主/进程级中断（进程被杀、App 关闭）—— ZCode 的 `stopped(interrupted)` 语义。
     * 这个值**不会**被主动写入 journal：进程死亡时来不及写，「journal 无 settled 行」
     * 就是它的判据（恢复路径据此提示用户继续）。
     */
    Interrupted,
}

sealed interface AgentEvent {
    data class RoundStarted(val round: Int, val maxRounds: Int) : AgentEvent
    data class TextDelta(val text: String) : AgentEvent
    data class ThinkingDelta(val text: String) : AgentEvent
    data class ToolCallStarted(val call: ToolCall) : AgentEvent
    data class ToolResultReceived(val result: ToolResult) : AgentEvent
    data class ToolSkipped(val call: ToolCall, val reason: String) : AgentEvent
    /** 一条完整消息落库（UI 用它把 streaming 气泡转成正式气泡） */
    data class MessageCommitted(val message: ChatMessage) : AgentEvent
    data class Finished(
        val text: String,
        val rounds: Int,
        val usage: TokenUsage?,
        /** 终止原因。带默认值，兼容既有调用方。 */
        val terminatedBy: TerminationReason = TerminationReason.ModelStopped,
    ) : AgentEvent
    data class Failed(val message: String, val cause: Throwable? = null) : AgentEvent
    data class Cancelled(val partialText: String) : AgentEvent

    /**
     * 引擎重建成功、即将重试。
     *
     * 为什么需要这个事件：重试是在**同一个 round 内部**重跑 `generateStream`，不会重新经过
     * `RoundStarted`，UI 因此收不到任何「新一轮开始」的信号，会一直显示上一轮已经流出的半截文本，
     * 两轮输出叠在一起。UI 收到本事件应清空流式缓冲（streamingText / streamingThinking）。
     */
    data class Retrying(val reason: String) : AgentEvent

    /**
     * 一次工具调用等待用户裁决（Octop tool_guard / ZCode 命令审批语义移植）。
     * UI 收到后应展示确认界面，并通过 [AgentRequest.approvalHandler] 给出的通道回填
     * APPROVED / DENIED；主循环会挂起等待，直到裁决或整个 run 被取消。
     */
    data class ApprovalRequested(val call: ToolCall, val spec: ToolSpec) : AgentEvent

    /**
     * 执行计划发生变化（plan_set / plan_update 工具触发；ZCode Phase Graph 降级移植）。
     * UI 据此渲染计划时间线；同一轮内多次变化合并为事件流上的多次更新。
     */
    data class PlanUpdated(val steps: List<com.rickeal.agent.core.agent.plan.PlanStep>) : AgentEvent
}

data class AgentRequest(
    val conversationId: String? = null,
    val history: List<ChatMessage> = emptyList(),
    val userInput: ChatMessage,
    val config: InferenceConfig = InferenceConfig(),
    val model: ModelDescriptor? = null,
    val endpoint: RemoteEndpoint? = null,
    /** null = 使用全部已启用工具；否则只用白名单内的 */
    val toolNames: Set<String>? = null,
    val policy: AgentPolicy = AgentPolicy(),
    /**
     * 运行日志（可选）。传入时主循环把关键节点（run_started / round_started /
     * 每条上下文消息 / settled）追加落盘 —— Android 进程随时可能被系统杀掉，
     * journal 让下一次 run 能从已完成的推理与工具结果处继续（ZCode Journal 语义移植）。
     * null = 关闭（与历史行为一致）。写入永远 best-effort，绝不影响主流程。
     */
    val journal: AgentRunJournal? = null,
    /**
     * 工具审批通道（可选）。非 null 时，危险工具（`dangerous`）与声明需确认的工具
     * （`requiresConfirmation`）会在执行前通过它请求用户裁决（Octop tool_guard +
     * ZCode 命令审批语义移植）；null = 维持历史行为（危险工具直接拒绝执行）。
     */
    val approvalHandler: ToolApprovalHandler? = null,
    /**
     * 长期记忆片段（harness-memory 移植）。非空时追加为系统提示词的「长期记忆」节；
     * 由调用方在发请求前从 [com.rickeal.agent.core.agent.memory.AgentMemory] 渲染取得。
     */
    val memoryText: String? = null,
    /**
     * 会话级计划仓库（ZCode Phase Graph 降级移植）。非 null 时工具循环内检测 plan_set /
     * plan_update 引起的版本变化并发 [AgentEvent.PlanUpdated]；null = 本 run 不感知计划。
     */
    val planStore: com.rickeal.agent.core.agent.plan.AgentPlanStore? = null,
)
