package com.rickeal.agent.core.data

import android.content.Context
import com.rickeal.agent.core.model.AgentLogStore
import com.rickeal.agent.core.model.ChatMessage
import com.rickeal.agent.core.model.Conversation
import com.rickeal.agent.core.model.ConversationMeta
import com.rickeal.agent.core.model.toMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
class ConversationRepository(
    context: Context,
    /**
     * 会话删除后的清理钩子（W58 修补 B 卫生位；**W69 C3 激活并扩为级联清理**）。
     *
     * [delete] 落盘完成（会话文件 + `index.json` 已更新、`_metas` 已刷新）后回调，
     * 由 AppContainer 注入 `AppContainer.cleanupConversationArtifacts` 执行多步清理：
     * 进程级韧性快照 / 审批缓存 / 子代理会话 / journal 与 history 目录
     * （逐步清单见该方法 KDoc）。默认实现为空 `{}`（JVM 单测可无副作用构造）。
     * ⚠️ **回调在 [writeMutex] 锁外执行**（W69 C3，审查 P2）：清理含**递归删目录**的阻塞 I/O，
     * 放锁内会卡住其他会话的 [appendMessage] / [rename] / [delete]；删除的权威结果已在锁内落定。
     *
     * ⚠️ **订正（W69 C3，2026-10）**：本 KDoc 此前写「[delete] 当前**零生产调用点**、
     * 钩子休眠，不是用户可见修复」——该描述**已过时**。C3「会话删除 UI」立项上线后，
     * 抽屉删除入口（`ConversationDrawer` 删除确认对话框 → `LiquidAgentApp` 接线）
     * 会调用 [delete]，本钩子**已被激活**。历史沿革：W58 预埋卫生位 → W59 H-3
     * 成文「休眠」→ W68-D 挂账 W68-05「等 C3」→ **W69 C3 过会并上线，上述休眠描述作废**。
     */
    private val onConversationDeleted: (String) -> Unit = {},
) {
    /**
     * 会话目录（`filesDir/conversations`）。
     *
     * 对 [AppContainer] 暴露为存储用量统计的**单一事实来源**（[StorageUsageStore]）。
     */
    val directory: File = File(context.filesDir, "conversations")
    private val store = JsonFileStore(directory)
    private val _metas = MutableStateFlow<List<ConversationMeta>>(emptyList())
    val metas: StateFlow<List<ConversationMeta>> = _metas.asStateFlow()

    /**
     * 串行化「读全文 → 改 → 写全文 + 写索引」的复合操作。
     *
     * 只加在 [appendMessage] / [rename] / [autoTitle] / [delete] 这些"读-改-写"入口上，
     * **[save] 本身不加锁**：它是这些入口的公共落盘步骤，加锁会让它们自锁。
     * （`Mutex` **不可重入**：同一协程二次 `withLock` 会永久挂起，不是抛异常——
     * 所以下面每个加锁的入口都直接调 [save]，绝不改调另一个加锁的方法。
     * [autoTitle] 尤其要注意：它曾经转调 [rename]，加互斥后必须改成自己 save。）
     */
    private val writeMutex = Mutex()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val list = store.read("index.json", ListSerializer(ConversationMeta.serializer())) ?: emptyList()
        _metas.value = list.sortedByDescending { it.updatedAtMillis }
    }

    suspend fun load(id: String): Conversation? =
        store.read("$id.json", Conversation.serializer())

    /**
     * 「读-改-写」入口共用的损坏熔断：文件**存在但解析失败**时返回 false，调用方拒写。
     *
     * Wave4 六路审查（E-P0-1）：此前 `appendMessage` 用 `load() ?: Conversation(id)` 把
     * 「文件不存在（新会话）」与「文件存在但损坏」坍缩成同一种结果，后者的下一次写入会用
     * 一条新消息的 `Conversation` **覆盖**整份损坏文件 —— 用户几个月的历史在下一次按发送键
     * 时无声消失，界面一切正常、日志零记录。同仓库的 `ModelRepository` 早就用
     * `exists()` 判别两种 null 并写下「parseFailed 时不写回一个字节」的不变式，
     * 这里是同一范式的补课。
     *
     * 返回 true = 可以继续读改写；false = 已判定损坏，调用方直接放弃本次写入并提示。
     */
    private suspend fun writableOrCorrupted(id: String): Boolean {
        if (!store.exists("$id.json")) return true   // 新会话：放心写
        if (load(id) != null) return true            // 解析正常：放心写
        AgentLogStore.error("会话文件解析失败（conversations/$id.json），已拒绝写入以保护原文件")
        return false
    }

    /**
     * 落盘：索引 `index.json` + 全文 `<id>.json`。
     *
     * **顺序是「先索引、后全文」**：两次写之间进程被杀，宁可留下
     * "列表里有一条、点进去是空的"（用户看得见，还能从中恢复/删掉），
     * 也不要"列表里根本没有这条"（整段会话无声消失，用户以为从没存过）。
     *
     * 不加 [writeMutex]，见那里的说明。
     */
    suspend fun save(conversation: Conversation) = withContext(Dispatchers.IO) {
        val next = (_metas.value.filter { it.id != conversation.id } + conversation.toMeta())
            .sortedByDescending { it.updatedAtMillis }
        store.write("index.json", next, ListSerializer(ConversationMeta.serializer()))
        _metas.value = next
        store.write("${conversation.id}.json", conversation, Conversation.serializer())
    }

    suspend fun appendMessage(conversationId: String, message: ChatMessage) {
        writeMutex.withLock {
            if (!writableOrCorrupted(conversationId)) return@withLock
            val current = load(conversationId) ?: Conversation(id = conversationId)
            save(
                current.copy(
                    messages = current.messages + message,
                    updatedAtMillis = System.currentTimeMillis(),
                )
            )
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            store.delete("$id.json")
            val next = _metas.value.filter { it.id != id }
            _metas.value = next
            store.write("index.json", next, ListSerializer(ConversationMeta.serializer()))
        }
        // 清理钩子（W58 卫生位）**移到锁外**（W69 C3，审查 P2）：本波把它从休眠激活为
        // 「多步级联清理 + 递归删 journal/history 目录」，锁内做递归 I/O 会阻塞其他会话的
        // appendMessage / rename / delete。删除的权威结果（会话文件 + index.json + _metas）
        // 已在锁内完成；清理是卫生位，锁外执行且失败不影响删除语义。
        onConversationDeleted(id)
    }

    suspend fun create(title: String = "新对话"): Conversation {
        val conversation = Conversation(title = title)
        save(conversation)
        return conversation
    }

    /** 首条用户消息到达时用它自动生成标题。 */
    suspend fun rename(id: String, title: String) {
        writeMutex.withLock {
            val current = load(id) ?: return@withLock
            save(current.copy(title = title, updatedAtMillis = System.currentTimeMillis()))
        }
    }

    /** 用第一条用户消息的前 N 字作为标题（避免所有会话都叫「新对话」）。 */
    suspend fun autoTitle(id: String, fallback: String = "新对话") {
        writeMutex.withLock {
            val current = load(id) ?: return@withLock
            if (current.title != "新对话") return@withLock
            val firstUser = current.messages.firstOrNull { it.text.isNotBlank() }?.text ?: return@withLock
            val title = firstUser.trim().replace('\n', ' ').take(24)
            if (title.isBlank()) return@withLock
            // 这里**必须自己 save，不能转调 rename()**：writeMutex 不可重入，
            // rename 里的那次 withLock 会把自己永久挂起。
            save(current.copy(title = title.ifBlank { fallback }, updatedAtMillis = System.currentTimeMillis()))
        }
    }
}
