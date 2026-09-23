package com.rickeal.agent.core.agent.subagent

import com.rickeal.agent.core.model.ChatMessage

/**
 * Actor 会话存储：按「会话 × Actor 名」累积上下文（ZCode 语义：同一个 Actor 上连续
 * 执行多个 ask 时上下文持续累积）。
 *
 * Wave 1 是**进程内存储**：App 被杀后 Actor 上下文归零，下次 ask 等价于一个新 Actor。
 * 这是与 ZCode（Journal 持久化 Actor）最显著的差距，后续把会话落成 JSONL 即可补齐
 * （复用 core-agent/journal 的写路径），刻意不在这轮做 —— 先把「串行 + 累积 + 隔离
 * 系统提示词」的语义立起来，持久化属于独立增量。
 *
 * 上限防御：端侧 4B 的上下文极有限，每个 Actor 只保留最近 [MAX_MESSAGES_PER_ACTOR]
 * 条消息；超限丢最老的（滑窗语义，与 ContextCompressor 的思路一致但更简单 —— Actor
 * 场景不需要工具配对完整性，因为恢复时整段作为 history 重发前会过 sanitizeForProvider）。
 */
class SubagentSessionStore(
    private val maxMessagesPerActor: Int = DEFAULT_MAX_MESSAGES,
) {

    data class ActorSession(
        val actorName: String,
        val messages: ArrayDeque<ChatMessage>,
    )

    private val sessions = HashMap<String, ActorSession>()

    @Synchronized
    fun session(key: String, actorName: String): ActorSession =
        sessions.getOrPut(key) { ActorSession(actorName, ArrayDeque()) }

    @Synchronized
    fun append(key: String, message: ChatMessage) {
        val session = sessions.getOrPut(key) { ActorSession(key, ArrayDeque()) }
        session.messages.addLast(message)
        while (session.messages.size > maxMessagesPerActor) {
            session.messages.removeFirst()
        }
    }

    @Synchronized
    fun snapshot(key: String): List<ChatMessage> =
        sessions[key]?.messages?.toList().orEmpty()

    /** 丢弃某会话的全部 Actor 上下文（会话删除时调用；Wave 2 接 ConversationRepository）。 */
    @Synchronized
    fun clearConversation(conversationKey: String) {
        sessions.keys.removeAll { it.startsWith("$conversationKey::") }
    }

    companion object {
        const val DEFAULT_MAX_MESSAGES = 24

        /** 「会话 × Actor」的键格式。 */
        fun key(conversationId: String?, actorName: String): String =
            "${conversationId.orEmpty()}::$actorName"
    }
}
