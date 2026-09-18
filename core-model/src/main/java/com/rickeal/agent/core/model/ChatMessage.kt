package com.rickeal.agent.core.model

import kotlinx.serialization.Serializable

/**
 * 一条消息。不可变：流式期间由上层累加后「替换最后一条」，而不是原地改字段。
 */
@Serializable
data class ChatMessage(
    val id: String = newId(),
    val role: Role = Role.USER,
    val text: String = "",
    val thinking: String? = null,
    val attachments: List<Attachment> = emptyList(),
    val toolCalls: List<ToolCall> = emptyList(),
    val toolResults: List<ToolResult> = emptyList(),
    val createdAtMillis: Long = System.currentTimeMillis(),
    val usage: TokenUsage? = null,
    val finishReason: FinishReason? = null,
    val modelRef: String? = null,
    val errorMessage: String? = null,
) {
    val isEmpty: Boolean
        get() = text.isBlank() && thinking.isNullOrBlank() &&
            attachments.isEmpty() && toolCalls.isEmpty()
}
