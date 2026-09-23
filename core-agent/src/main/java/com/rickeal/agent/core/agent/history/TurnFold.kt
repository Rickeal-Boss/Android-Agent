package com.rickeal.agent.core.agent.history

import com.rickeal.agent.core.agent.journal.AgentRunJournal
import com.rickeal.agent.core.model.AgentJson
import com.rickeal.agent.core.model.ChatMessage
import com.rickeal.agent.core.model.Role

/**
 * journal → TurnRecord 折叠器（纯函数式：读 journal 行 + 写正文池，产出回合记录）。
 *
 * 全链 best-effort：任何一步失败返回 null，宿主据此跳过归档 —— 记录层永远
 * 不是主流程的失败源（与 AgentRunJournal 同纪律）。
 */
object TurnFold {

    /**
     * 折叠一次 run 的 journal 为回合记录。
     *
     * 正文字段只取**有可见文本**的行：user_input 行 → [TurnRecord.userTextRef]；
     * message 行中有文本的 → messageRefs（TOOL 消息正文为空，跳过，工具结构
     * 的权威在 journal 本体）；最后一条有文本的 MODEL 行 → finalTextRef。
     */
    suspend fun fromJournal(
        pool: ContentAddressedPool,
        journal: AgentRunJournal,
        state: TurnState,
        termination: String?,
        startedAtMillis: Long = 0L,
    ): TurnRecord? {
        return runCatching {
            val lines = journal.readLines()
            if (lines.isEmpty()) return null
            var userRef: BlobRef? = null
            val messageRefs = ArrayList<BlobRef>()
            var finalRef: BlobRef? = null
            var toolCallCount = 0
            for (line in lines) {
                when (line.kind) {
                    AgentRunJournal.KIND_USER_INPUT -> {
                        val ref = decodeMessage(line)?.let { m -> putIfText(pool, m.text) }
                        if (ref != null) userRef = ref
                    }
                    AgentRunJournal.KIND_MESSAGE -> {
                        val message = decodeMessage(line) ?: continue
                        if (message.role == Role.MODEL && message.toolCalls.isNotEmpty()) {
                            toolCallCount += message.toolCalls.size
                        }
                        val ref = putIfText(pool, message.text) ?: continue
                        messageRefs += ref
                        if (message.role == Role.MODEL) finalRef = ref
                    }
                }
            }
            if (userRef == null && messageRefs.isEmpty() && finalRef == null) return null
            TurnRecord(
                turnId = journal.runId,
                state = state,
                userTextRef = userRef,
                messageRefs = messageRefs,
                finalTextRef = finalRef,
                termination = termination,
                toolCallCount = toolCallCount,
                startedAtMillis = startedAtMillis,
            )
        }.getOrNull()
    }

    private fun putIfText(pool: ContentAddressedPool, text: String): BlobRef? {
        if (text.isBlank()) return null
        return pool.put(text)
    }

    private fun decodeMessage(line: AgentRunJournal.JournalLine): ChatMessage? = runCatching {
        AgentJson.Default.decodeFromString(ChatMessage.serializer(), line.payload.toString())
    }.getOrNull()
}
