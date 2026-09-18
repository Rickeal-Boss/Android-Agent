package com.rickeal.agent.core.agent

import com.rickeal.agent.core.model.ChatMessage
import com.rickeal.agent.core.model.Role
import com.rickeal.agent.core.model.TokenEstimator

interface ContextCompressor {
    suspend fun compress(messages: List<ChatMessage>, budgetTokens: Int): List<ChatMessage>
}

/**
 * 朴素滑动窗口：
 *  - 系统消息与「最近 K 条」永不丢
 *  - 从最新往回贪心装填，装不下就丢最老的
 */
class WindowContextCompressor(
    private val keepRecent: Int = 8,
) : ContextCompressor {

    override suspend fun compress(messages: List<ChatMessage>, budgetTokens: Int): List<ChatMessage> {
        if (messages.isEmpty()) return messages
        val system = messages.filter { it.role == Role.SYSTEM }
        val rest = messages.filter { it.role != Role.SYSTEM }
        if (TokenEstimator.estimate(messages) <= budgetTokens) return messages

        val kept = ArrayList<ChatMessage>()
        var used = TokenEstimator.estimate(system)
        val recent = rest.takeLast(keepRecent)
        for (message in recent.reversed()) {
            val cost = TokenEstimator.estimate(message)
            if (used + cost > budgetTokens) break
            kept.add(0, message)
            used += cost
        }
        // 还有预算就把更早的按"从新到旧"继续补
        val older = rest.dropLast(keepRecent)
        for (message in older.reversed()) {
            val cost = TokenEstimator.estimate(message)
            if (used + cost > budgetTokens) break
            kept.add(0, message)
            used += cost
        }
        return system + kept
    }
}

/**
 * 摘要版：先滑窗，若仍超预算，把「被丢弃的中间段」交给 summarizer 压缩成一条 SYSTEM 消息。
 * summarizer 由上层注入（通常就是引擎本身跑一次"请总结以下对话"）。
 * 这是一个**可实现**的朴素方案，不追求最优。
 */
class SummarizingContextCompressor(
    private val window: ContextCompressor = WindowContextCompressor(),
    private val summarizer: suspend (String) -> String?,
) : ContextCompressor {

    override suspend fun compress(messages: List<ChatMessage>, budgetTokens: Int): List<ChatMessage> {
        val windowed = window.compress(messages, budgetTokens)
        if (TokenEstimator.estimate(windowed) <= budgetTokens) return windowed
        val keptIds = windowed.map { it.id }.toSet()
        val dropped = messages.filter { it.id !in keptIds }
        if (dropped.isEmpty()) return windowed
        val digest = dropped.joinToString("\n") { "${it.role}: ${it.text.take(300)}" }
        val summary = try {
            summarizer(digest)
        } catch (t: Throwable) {
            null
        }
        if (summary.isNullOrBlank()) return windowed
        val summaryMessage = ChatMessage(
            role = Role.SYSTEM,
            text = "以下是较早对话的摘要，请参考：\n$summary",
        )
        return listOf(summaryMessage) + windowed
    }
}
