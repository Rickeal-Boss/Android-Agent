package com.rickeal.agent.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class FinishReason {
    STOP,
    LENGTH,
    TOOL_CALLS,
    CANCELLED,
    ERROR,
    FILTER,
}

@Serializable
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val tokensPerSecond: Float = 0f,
    val firstTokenLatencyMillis: Long = 0L,
    val decodeMillis: Long = 0L,
)

/**
 * 一次流式回调的最小增量。
 * 设计原则：所有字段都有默认值，引擎只填自己有的字段；上层用「非空即追加」的方式合并。
 *
 * 不加 @Serializable：这是进程内运行时对象，不落盘。落盘的是合并后的 ToolCall。
 */
data class GenerationChunk(
    val textDelta: String = "",
    val thinkingDelta: String = "",
    val toolCallDelta: ToolCallDelta? = null,
    val finishReason: FinishReason? = null,
    val usage: TokenUsage? = null,
)

/**
 * 增量式工具调用片段（对齐 OpenAI 的 delta.tool_calls）。
 * argumentsFragment 是 JSON 的「碎片」，需要按 index 累积后再整体解析。
 */
data class ToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val name: String? = null,
    val argumentsFragment: String = "",
)
