package com.rickeal.agent.core.agent.memory

import com.rickeal.agent.core.model.AgentJson
import com.rickeal.agent.core.model.AgentLogStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
data class MemorySection(
    val title: String = "",
    val content: String = "",
    val updatedAtMillis: Long = 0L,
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
     * title 也必须单行化（不只是 content）：title 是唯一键，模型可以往里写换行，
     * 换行会把注入段切成多条伪 section 头，伪造「系统提示词结构」—— 注入面收紧。
     */
    suspend fun renderForPrompt(maxChars: Int = PROMPT_MAX_CHARS): String? {
        val list = sections()
        if (list.isEmpty()) return null
        val text = list.joinToString("\n") { section ->
            "- [${section.title.replace("\n", " ")}] ${section.content.replace("\n", " ")}"
        }
        return if (text.length <= maxChars) text else text.take(maxChars) + "…(已截断)"
    }

    // ------------------------------------------------------------------
    // 写（工具路径）
    // ------------------------------------------------------------------

    suspend fun upsert(title: String, content: String): Boolean = withContext(ioDispatcher) {
        mutex.withLock {
            val trimmedTitle = title.trim()
            val trimmedContent = content.trim()
            // Wave4 审查（E-P0-2）：三态判别 —— 文件存在但解析失败时**拒写**。
            // 此前 readSync 把「损坏」坍缩成「空列表」，upsert 会把整份记忆文件覆写成
            // 只含刚写入的一条：用户手工编辑 memory.json 打错一个逗号，多年沉淀的
            // 偏好与项目事实在下一次 memory_write 时全部蒸发，且无备份不可恢复。
            // KDoc 明确邀请用户手工编辑本文件，这个暴露面必须 fail-closed。
            val current = when (val state = readState()) {
                is ReadState.Ok -> state.sections.toMutableList()
                is ReadState.Corrupted -> {
                    AgentLogStore.error(
                        "记忆文件解析失败（${file.name}），已拒绝写入以保护原文件；请人工修复或删除该文件"
                    )
                    return@withContext false
                }
                ReadState.Absent -> mutableListOf()
            }
            val index = current.indexOfFirst { it.title == trimmedTitle }
            val section = MemorySection(
                title = trimmedTitle,
                content = trimmedContent,
                updatedAtMillis = System.currentTimeMillis(),
            )
            if (index >= 0) current[index] = section else current.add(section)
            // 只保留**最新**的 MAX_SECTIONS 条（takeLast）：超限淘汰方向曾写反成
            // take(...) —— 保留最旧、丢掉刚写入的新记忆，模型写的第 65 条永远
            // 不生效且无任何提示。
            writeSync(current.takeLast(MAX_SECTIONS))
            true
        }
    }

    suspend fun remove(title: String): Boolean = withContext(ioDispatcher) {
        mutex.withLock {
            val current = when (val state = readState()) {
                is ReadState.Ok -> state.sections.toMutableList()
                is ReadState.Corrupted -> {
                    AgentLogStore.error("记忆文件解析失败（${file.name}），已拒绝删除写入")
                    return@withContext false
                }
                ReadState.Absent -> return@withContext false
            }
            val removed = current.removeAll { it.title == title.trim() }
            if (removed) writeSync(current)
            removed
        }
    }

    // ------------------------------------------------------------------

    /** 读三态：文件不存在 / 正常 / 损坏。「损坏」绝不能坍缩成「空」，否则写路径会覆写掉原文件。 */
    private sealed interface ReadState {
        data object Absent : ReadState
        data class Ok(val sections: List<MemorySection>) : ReadState
        data object Corrupted : ReadState
    }

    private fun readState(): ReadState {
        if (!file.exists()) return ReadState.Absent
        return runCatching {
            val raw = file.readText()
            if (raw.isBlank()) return ReadState.Absent
            ReadState.Ok(AgentJson.Default.decodeFromString(ListSerializer(MemorySection.serializer()), raw))
        }.getOrElse { ReadState.Corrupted }
    }

    /** 兼容旧读法：不区分损坏与为空（仅用于只读渲染路径，写路径必须走 [readState]）。 */
    private fun readSync(): List<MemorySection> =
        (readState() as? ReadState.Ok)?.sections ?: emptyList()

    private fun writeSync(sections: List<MemorySection>) {
        runCatching {
            file.parentFile?.mkdirs()
            // 原子写（tmp + ATOMIC_MOVE）：直接 writeText 覆写时进程死在半路会留下
            // 半截 JSON，下次读解析失败 → 静默清零（readSync 的 getOrDefault）——
            // 用户全部长期记忆凭空蒸发。Wave2 遗留缺陷。
            val tmp = File(file.parentFile, file.name + "." + System.nanoTime() + ".tmp")
            tmp.writeText(AgentJson.Default.encodeToString(ListSerializer(MemorySection.serializer()), sections))
            try {
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (t: Throwable) {
                // 个别文件系统不支持 ATOMIC_MOVE，退化普通 rename（仍是元数据操作）
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure {
            AgentLogStore.warn(
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
