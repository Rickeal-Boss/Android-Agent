package com.rickeal.agent.core.model

import kotlin.math.ceil

/**
 * LiteRT-LM 0.11.0 没暴露 tokenizer，远程端点也不一定返回 usage。
 * 统一用「3.2 字符 ≈ 1 token」的英语近似；**CJK 字符按 1 字 ≈ 1 token**（Wave 28 修正）。
 *
 * ## 为什么必须分语种（Wave 28）
 *
 * 旧实现对全文统一 3.2 字符/token —— KDoc 自己注明「中文按 1 字 ≈ 1 token 更准，
 * 这里取折中」，但折中的方向是**系统性低估中文 2~3 倍**（Qwen 系 tokenizer 中文约
 * 0.7~1.2 token/字符）。低估的全部代价由上下文预算承担：压缩门禁以为还有余量时，
 * 引擎的真实 KV（输入+输出总和）早已越顶，litertlm 直接硬报错
 * （"Input token ids are too long"）。估算偏大的后果只是「压缩早一点触发」——
 * 两个方向不对称，必须偏保守。
 *
 * 用途只有一个：上下文窗口裁剪的预算判断。不要拿它给用户看精确数字。
 */
object TokenEstimator {
    private const val CHARS_PER_TOKEN = 3.2f

    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        for (c in text) {
            if (isCjk(c)) cjk++ else other++
        }
        // CJK 1 字 = 1 token（保守上界：宁可高估触发压缩，不可低估撞 KV 硬报错）；
        // 非 CJK 维持 3.2 字符/token 的英语近似；整体 ceil 保持「非空文本至少 1 token」。
        return ceil(cjk + other / CHARS_PER_TOKEN).toInt().coerceAtLeast(1)
    }

    /** CJK 统一表意区 + 谚文 + 全角 forms（标点/汉字混排的中文 prompt 主桶）。 */
    private fun isCjk(c: Char): Boolean {
        val v = c.code
        return v in 0x2E80..0x9FFF || // CJK 部首/注音/日文假名/汉字主区
            v in 0x3400..0x4DBF || // 扩展 A
            v in 0xF900..0xFAFF || // 兼容表意
            v in 0xFF00..0xFFEF || // 全角标点与符号
            v in 0xAC00..0xD7AF // 谚文
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
