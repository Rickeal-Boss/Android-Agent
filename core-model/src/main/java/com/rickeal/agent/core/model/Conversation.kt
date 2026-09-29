package com.rickeal.agent.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Conversation(
    val id: String = newId(),
    val title: String = "新对话",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val messages: List<ChatMessage> = emptyList(),
    val modelId: String? = null,
    val config: InferenceConfig = InferenceConfig(),
    val summary: String? = null,
    val pinned: Boolean = false,
)

/** 列表页用的轻量摘要，避免把整条会话读进内存。 */
@Serializable
data class ConversationMeta(
    val id: String = newId(),
    val title: String = "新对话",
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val messageCount: Int = 0,
    val preview: String = "",
    val modelId: String? = null,
    val pinned: Boolean = false,
)

fun Conversation.toMeta(): ConversationMeta = ConversationMeta(
    id = id,
    title = title,
    updatedAtMillis = updatedAtMillis,
    messageCount = messages.size,
    // 代理对安全截断（Wave 35 D1）：preview 直接上屏（会话列表），
    // 停在半个代理对上就是一个 U+FFFD 方块。
    preview = messages.lastOrNull { it.role == Role.MODEL }?.text?.truncateSafe(80)
        ?: messages.lastOrNull()?.text?.truncateSafe(80).orEmpty(),
    modelId = modelId,
    pinned = pinned,
)
