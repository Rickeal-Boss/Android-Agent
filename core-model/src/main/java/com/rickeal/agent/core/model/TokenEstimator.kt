package com.rickeal.agent.core.model

import kotlin.math.ceil

/**
 * LiteRT-LM 0.11.0 没暴露 tokenizer，远程端点也不一定返回 usage。
 * 统一用「3.2 字符 ≈ 1 token」的英语近似（中文按 1 字 ≈ 1 token 更准，这里取折中）。
 * 用途只有一个：上下文窗口裁剪的预算判断。不要拿它给用户看精确数字。
 */
object TokenEstimator {
    private const val CHARS_PER_TOKEN = 3.2f

    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        return ceil(text.length / CHARS_PER_TOKEN).toInt().coerceAtLeast(1)
    }

    fun estimate(message: ChatMessage): Int {
        var total = estimate(message.text) + estimate(message.thinking.orEmpty())
        for (attachment in message.attachments) {
            total += when (attachment) {
                is Attachment.Image -> 256
                is Attachment.Audio -> 128
                is Attachment.File -> 64
                is Attachment.Text -> estimate(attachment.text)
            }
        }
        for (call in message.toolCalls) total += estimate(call.argumentsJson)
        for (result in message.toolResults) total += estimate(result.output)
        return total
    }

    fun estimate(messages: List<ChatMessage>): Int = messages.sumOf { estimate(it) }
}
