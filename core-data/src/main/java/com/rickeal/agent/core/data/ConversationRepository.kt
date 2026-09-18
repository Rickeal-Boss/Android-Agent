package com.rickeal.agent.core.data

import android.content.Context
import com.rickeal.agent.core.model.ChatMessage
import com.rickeal.agent.core.model.Conversation
import com.rickeal.agent.core.model.ConversationMeta
import com.rickeal.agent.core.model.toMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** 别名：与任务书里的命名对齐（真正实现的类名沿用架构文档 §6.3 的 ConversationRepository）。 */
typealias ConversationsRepository = ConversationRepository

/**
 * 会话存储：`index.json` 存列表摘要（列表页秒开），每个会话一个 `<id>.json` 存全文。
 *
 * 所有 IO 走 Dispatchers.IO；文件损坏时 load 返回 null，refresh 返回空列表，绝不崩溃。
 */
class ConversationRepository(context: Context) {
    private val store = JsonFileStore(File(context.filesDir, "conversations"))
    private val _metas = MutableStateFlow<List<ConversationMeta>>(emptyList())
    val metas: StateFlow<List<ConversationMeta>> = _metas.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val list = store.read("index.json", ListSerializer(ConversationMeta.serializer())) ?: emptyList()
        _metas.value = list.sortedByDescending { it.updatedAtMillis }
    }

    suspend fun load(id: String): Conversation? =
        store.read("$id.json", Conversation.serializer())

    suspend fun save(conversation: Conversation) = withContext(Dispatchers.IO) {
        store.write("${conversation.id}.json", conversation, Conversation.serializer())
        val next = (_metas.value.filter { it.id != conversation.id } + conversation.toMeta())
            .sortedByDescending { it.updatedAtMillis }
        _metas.value = next
        store.write("index.json", next, ListSerializer(ConversationMeta.serializer()))
    }

    suspend fun appendMessage(conversationId: String, message: ChatMessage) {
        val current = load(conversationId) ?: Conversation(id = conversationId)
        save(current.copy(messages = current.messages + message, updatedAtMillis = System.currentTimeMillis()))
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        store.delete("$id.json")
        val next = _metas.value.filter { it.id != id }
        _metas.value = next
        store.write("index.json", next, ListSerializer(ConversationMeta.serializer()))
    }

    suspend fun create(title: String = "新对话"): Conversation {
        val conversation = Conversation(title = title)
        save(conversation)
        return conversation
    }

    /** 首条用户消息到达时用它自动生成标题。 */
    suspend fun rename(id: String, title: String) {
        val current = load(id) ?: return
        save(current.copy(title = title, updatedAtMillis = System.currentTimeMillis()))
    }

    /** 用第一条用户消息的前 N 字作为标题（避免所有会话都叫「新对话」）。 */
    suspend fun autoTitle(id: String, fallback: String = "新对话") {
        val current = load(id) ?: return
        if (current.title != "新对话") return
        val firstUser = current.messages.firstOrNull { it.text.isNotBlank() }?.text ?: return
        val title = firstUser.trim().replace('\n', ' ').take(24)
        if (title.isBlank()) return
        rename(id, title.ifBlank { fallback })
    }
}
