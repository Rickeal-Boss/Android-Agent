package com.rickeal.agent.core.agent

import com.rickeal.agent.core.agent.approval.ToolApprovalHandler
import com.rickeal.agent.core.agent.journal.AgentRunJournal
import com.rickeal.agent.core.model.ChatMessage
import com.rickeal.agent.core.model.InferenceConfig
import com.rickeal.agent.core.model.ModelDescriptor
import com.rickeal.agent.core.model.ToolCall
import com.rickeal.agent.core.model.ToolResult
import com.rickeal.agent.core.model.ToolSpec
import com.rickeal.agent.core.model.AiCapabilityMode
import com.rickeal.agent.core.model.TokenUsage
import com.rickeal.agent.core.model.ToolDisclosureMode
import com.rickeal.agent.core.agent.token.RunTokenLedger

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
     * 主循环**丢弃了本轮已流出的文本**（轮内重复截断 / 重复回答提醒 / 空输出 nudge），
     * 即将在同一个 round 内重跑生成。
     *
     * 为什么需要这个事件：这三条处置路径只把文本写进 `working` 与 journal，**不发任何 UI
     * 事件**；而 `round++` 后继续的下一轮会照常 `emit(TextDelta)` —— UI 侧的流式缓冲不会
     * 被清（`resetStreamingText()` 只在 `Retrying` / `Failed` 两处调用），于是被丢弃的乱文
     * 会留在气泡里，下一轮输出**叠在它后面**，直到终态才消失。用户看到的是「乱码 + 新回答」
     * 粘在同一个气泡里（复审3 §4-1，P1）。
     *
     * 与 [RoundStarted] 的分工：`RoundStarted` 每轮都发（含正常轮），把清屏挂上去会误伤
     * 「正常轮之间的过渡话术」—— 模型上一轮的合法输出在被 `MessageCommitted` 之前不该被抹。
     * 本事件**只在真的丢弃了本轮文本时**发，语义精确，不误伤。
     *
     * UI 收到本事件应清空流式缓冲（streamingText / streamingThinking）且**不落库**：
     * 被丢弃的文本本就不该交付，与 [Retrying] 同理。
     */
    data class StreamReset(val reason: String) : AgentEvent

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
     * ZCode 命令审批语义移植）；null = 危险工具直接拒绝执行。
     */
    val approvalHandler: ToolApprovalHandler? = null,
    /**
     * 审批缓存（可选，「计划级授权」轻量降级）：用户显式授权过的
     * 「会话 × 工具 × 参数摘要」在 TTL 内免再弹卡（Octop 批量审批 + TTL 同构，
     * 拒绝永不缓存）。null = 每次都弹。子代理 run 应保持 null（不继承授权）。
     */
    val approvalCache: com.rickeal.agent.core.agent.approval.ToolApprovalCache? = null,
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
    /**
     * 用户给 AI 的**整体能力档位**（Wave 26 / Operit2 四层能力模型裁剪移植）。
     * 默认 [AiCapabilityMode.WORKSPACE_WRITE] = 与引入档位前的行为逐字节一致
     * （零行为回归）。[AiCapabilityMode.READ_ONLY] 时，WRITE 效果的工具会在执行前
     * 追加一次授权请求；档位**只收紧不放宽**，不构成任何提权。
     */
    val capabilityMode: AiCapabilityMode = AiCapabilityMode.WORKSPACE_WRITE,
    /**
     * 工具**披露模式**（Wave 27 / Operit「CLI 工具模式」裁剪移植）。
     * 默认 [ToolDisclosureMode.FULL] = 工具清单完整进提示词，与引入本模式前的行为
     * 逐字节一致（零行为回归）。[ToolDisclosureMode.ON_DEMAND] 时提示词只含
     * `search_tools` / `call_tool` 两个元工具，真实工具按需检索后转发执行。
     *
     * ⚠️ 披露模式**只是可见性**，不是权限：转发调用在解包后走与直接调用完全相同的
     * 守卫与审批链路（静态标志 ∪ 参数门控 ∪ 效果声明 ∪ [capabilityMode]）。
     */
    val disclosureMode: ToolDisclosureMode = ToolDisclosureMode.FULL,
    /**
     * run 级 token 账本（Wave 30，可选）。非 null 时主循环在两处单点回写：
     * 发送侧记账块结束后 [RunTokenLedger.onSendEstimated]（全仓唯一的
     * sentTokens → 账本回写点，KDoc 红线见记账块处注释）；引擎回报 usage 后
     * [RunTokenLedger.onEngineUsage]。账本是 [com.rickeal.agent.core.agent.RunState.sentTokens]
     * 的**读侧投影**，不替代不改动记账块本身（Wave 29 A1 刚终审的结构不动）。
     * null = 不记账（与历史行为一致）。子 run 应保持 null：子 run 独立短命，
     * 不进父 run 账本（与审批缓存「子 run 不继承」同一隔离纪律）。
     */
    val tokenLedger: RunTokenLedger? = null,
)
