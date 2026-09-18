package com.rickeal.agent.core.engine.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class ChatRequestDto(
    val model: String,
    val messages: List<MessageDto>,
    val stream: Boolean = true,
    val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val seed: Int? = null,
    @SerialName("frequency_penalty") val frequencyPenalty: Float? = null,
    val tools: List<ToolDto>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
    @SerialName("enable_thinking") val enableThinking: Boolean? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("chat_template_kwargs") val chatTemplateKwargs: JsonObject? = null,
)

@Serializable
internal data class ToolDto(
    val type: String = "function",
    val function: FunctionDto,
)

@Serializable
internal data class FunctionDto(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
internal data class MessageDto(
    val role: String,
    val content: JsonElement? = null,
    val name: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDto>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

@Serializable
internal data class ToolCallDto(
    val id: String,
    val type: String = "function",
    val function: ToolFunctionDto,
)

@Serializable
internal data class ToolFunctionDto(
    val name: String? = null,
    val arguments: String? = null,
)

@Serializable
internal data class StreamChunkDto(
    val id: String? = null,
    val choices: List<StreamChoiceDto> = emptyList(),
    val usage: StreamUsageDto? = null,
)

@Serializable
internal data class StreamChoiceDto(
    val index: Int = 0,
    val delta: StreamDeltaDto? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
internal data class StreamDeltaDto(
    val content: String? = null,
    /** DeepSeek / vLLM */
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    /** 另一派命名 */
    val reasoning: String? = null,
    @SerialName("tool_calls") val toolCalls: List<StreamToolCallDto>? = null,
)

@Serializable
internal data class StreamToolCallDto(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: StreamToolFunctionDto? = null,
)

@Serializable
internal data class StreamToolFunctionDto(
    val name: String? = null,
    val arguments: String? = null,
)

@Serializable
internal data class StreamUsageDto(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)
