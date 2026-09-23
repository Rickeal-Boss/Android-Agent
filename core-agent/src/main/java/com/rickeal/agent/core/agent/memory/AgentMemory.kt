package com.rickeal.agent.core.agent.memory

import com.rickeal.agent.core.model.AgentJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

@Serializable
data class MemorySection(
    val title: String,
    val content: String,
    val updatedAtMillis: Long,
)

/**
 * Agent 长期记忆 —— 移植自 Octop harness-memory（「记忆随工作区迁移」）与
 * ZCode 的 project memory：一个按标题组织的持久化要点集。
 *
 * 与 run journal 的分工（两者都是 JSONL/JSON 文件，但语义完全不同）：
 *  - Journal：单次 run 的**过程**记录，用于崩溃恢复，run 结束后只读；
 *  - Memory：跨 run、跨会话的**结论**沉淀（用户的偏好、项目事实、长期约定），
 *    由模型通过 memory_* 工具显式读写，人也可以在文件里直接改。
 *
 * 存储：单 JSON 文件（filesDir/agent_memory/memory.json），全互斥读改写。
 * 端侧规模（几十条 × 每条几百字符）下文件方案足够；做检索/向量化属于
 * Wave 3（RAG 知识库），不在这层偷跑。
 */
class AgentMemory(
    private val file: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val mutex = Mutex()

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    suspend fun sections(): List<MemorySection> = withContext(ioDispatcher) {
        readSync()
    }

    /**
     * 渲染成注入 system prompt 的文本；空记忆返回 null（不注入，省 token）。
     * 刻意用紧凑格式：端侧 4B 的系统提示词每一个 token 都在挤占工作记忆。
     */
    suspend fun renderForPrompt(maxChars: Int = PROMPT_MAX_CHARS): String? {
        val list = sections()
        if (list.isEmpty()) return null
        val text = list.joinToString("\n") { section ->
            "- [${section.title}] ${section.content.replace("\n", " ")}"
        }
        return if (text.length <= maxChars) text else text.take(maxChars) + "…(已截断)"
    }

    // ------------------------------------------------------------------
    // 写（工具路径）
    // ------------------------------------------------------------------

    suspend fun upsert(title: String, content: String): Unit = withContext(ioDispatcher) {
        mutex.withLock {
            val trimmedTitle = title.trim()
            val trimmedContent = content.trim()
            val current = readSync().toMutableList()
            val index = current.indexOfFirst { it.title == trimmedTitle }
            val section = MemorySection(
                title = trimmedTitle,
                content = trimmedContent,
                updatedAtMillis = System.currentTimeMillis(),
            )
            if (index >= 0) current[index] = section else current.add(section)
            writeSync(current.take(MAX_SECTIONS))
        }
    }

    suspend fun remove(title: String): Boolean = withContext(ioDispatcher) {
        mutex.withLock {
            val current = readSync().toMutableList()
            val removed = current.removeAll { it.title == title.trim() }
            if (removed) writeSync(current)
            removed
        }
    }

    // ------------------------------------------------------------------

    private fun readSync(): List<MemorySection> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val raw = file.readText()
            if (raw.isBlank()) return emptyList()
            AgentJson.Default.decodeFromString(ListSerializer(MemorySection.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private fun writeSync(sections: List<MemorySection>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(AgentJson.Default.encodeToString(ListSerializer(MemorySection.serializer()), sections))
        }.onFailure {
            com.rickeal.agent.core.model.AgentLogStore.warn(
                "记忆写入失败：${it.javaClass.simpleName}"
            )
        }
    }

    companion object {
        /** 条数上限：防跑飞的记忆工具把文件写成无界增长。 */
        const val MAX_SECTIONS = 64

        /** 注入 prompt 的默认预算（字符）。 */
        const val PROMPT_MAX_CHARS = 1200
    }
}
