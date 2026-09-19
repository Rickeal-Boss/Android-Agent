package com.rickeal.agent.core.agent

import com.rickeal.agent.core.model.ChatMessage
import com.rickeal.agent.core.model.InferenceConfig
import com.rickeal.agent.core.model.ModelDescriptor
import com.rickeal.agent.core.model.RemoteEndpoint
import com.rickeal.agent.core.model.ToolCall
import com.rickeal.agent.core.model.ToolResult
import com.rickeal.agent.core.model.TokenUsage

/**
 * 本轮为什么结束。让 UI / 日志能区分「模型自己停了」和「被轮次上限硬截断」。
 */
enum class TerminationReason {
    /** 模型给出了最终答案 —— 正常停止。 */
    ModelStopped,

    /** 达到 maxRounds 兜底截断（正常情况应该由模型自己停，走到这里说明它没停住）。 */
    MaxRounds,
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
)
