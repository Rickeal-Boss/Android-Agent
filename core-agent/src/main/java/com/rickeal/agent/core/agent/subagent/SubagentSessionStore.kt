package com.rickeal.agent.core.agent.subagent

import com.rickeal.agent.core.model.AgentJson
import com.rickeal.agent.core.model.ChatMessage
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * Actor 会话存储：按「会话 × Actor 名」累积上下文（ZCode 语义：同一个 Actor 上连续
 * 执行多个 ask 时上下文持续累积）。
 *
 * Wave 2 起支持**文件持久化**（[persistDir] 非空）：每个（会话×Actor）一个 JSON 文件，
 * append 即落盘（写入点在工具执行的 IO 调度器上，文件很小 ≤24 条消息）——App 被杀后
 * Actor 上下文还在，长任务委派不再因进程死亡退化为「新 Actor」。这是向 ZCode
 * 持久化 Actor 对齐的一步；仍不做跨设备迁移（那是工作区同步的范畴）。
 *
 * 上限防御：每个 Actor 只保留最近 [maxMessagesPerActor] 条消息（滑窗）；
 * 恢复时整段作为 history 重发前会过 sanitizeForProvider，工具配对完整性有保证。
 */
class SubagentSessionStore(
    private val persistDir: File? = null,
    private val maxMessagesPerActor: Int = DEFAULT_MAX_MESSAGES,
) {

    data class ActorSession(
        val actorName: String,
        val messages: ArrayDeque<ChatMessage>,
    )

    private val sessions = HashMap<String, ActorSession>()

    @Synchronized
    fun session(key: String, actorName: String): ActorSession {
        val existing = sessions[key]
        if (existing != null) return existing
        val loaded = loadSync(key)
        val session = ActorSession(actorName, ArrayDeque(loaded))
        sessions[key] = session
        return session
    }

    @Synchronized
    fun append(key: String, message: ChatMessage) {
        val session = sessions.getOrPut(key) { ActorSession(key, ArrayDeque(loadSync(key))) }
        session.messages.addLast(message)
        while (session.messages.size > maxMessagesPerActor) {
            session.messages.removeFirst()
        }
        persistSync(key, session.messages.toList())
    }

    @Synchronized
    fun snapshot(key: String): List<ChatMessage> {
        val existing = sessions[key]
        if (existing != null) return existing.messages.toList()
        val loaded = loadSync(key)
        sessions[key] = ActorSession(key, ArrayDeque(loaded))
        return loaded
    }

    /** 丢弃某会话的全部 Actor 上下文（内存 + 磁盘）。 */
    @Synchronized
    fun clearConversation(conversationKey: String) {
        val prefix = "$conversationKey::"
        sessions.keys.removeAll { it.startsWith(prefix) }
        val dir = persistDir ?: return
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.name.startsWith(fileKey(conversationKey) + "__")) {
                runCatching { file.delete() }
            }
        }
    }

    // ------------------------------------------------------------------
    // 持久化
    // ------------------------------------------------------------------

    private fun fileFor(key: String): File? {
        val dir = persistDir ?: return null
        return File(dir, fileKey(key) + ".json")
    }

    private fun fileKey(key: String): String =
        key.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(120).ifBlank { "default" }

    private fun loadSync(key: String): List<ChatMessage> {
        val file = fileFor(key) ?: return emptyList()
        if (!file.exists()) return emptyList()
        return runCatching {
            val raw = file.readText()
            if (raw.isBlank()) return emptyList()
            AgentJson.Default.decodeFromString(MessagesSerializer, raw)
        }.getOrDefault(emptyList())
    }

    private fun persistSync(key: String, messages: List<ChatMessage>) {
        val file = fileFor(key) ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(AgentJson.Default.encodeToString(MessagesSerializer, messages))
        }.onFailure {
            com.rickeal.agent.core.model.AgentLogStore.warn(
                "Actor 会话写入失败（忽略，不影响运行）：${it.javaClass.simpleName}"
            )
        }
    }

    companion object {
        const val DEFAULT_MAX_MESSAGES = 24
        private val MessagesSerializer = ListSerializer(ChatMessage.serializer())

        /** 「会话 × Actor」的键格式。 */
        fun key(conversationId: String?, actorName: String): String =
            "${conversationId.orEmpty()}::$actorName"
    }
}
