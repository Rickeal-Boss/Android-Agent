package com.rickeal.agent.core.agent.memory

import com.rickeal.agent.core.model.AgentJson
import com.rickeal.agent.core.model.AgentLogStore
import com.rickeal.agent.core.model.truncateSafe
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
        // 代理对安全截断（Wave 35 D1）：注入 system prompt 的文本若停在半个代理对上，
        // 会在落库 / 回灌 / 上屏三处变成 U+FFFD 乱码。
        return if (text.length <= maxChars) text else text.truncateSafe(maxChars) + "…(已截断)"
    }

    /**
     * 渲染成注入 system prompt 的**标题索引**（Wave 34 题 B：记忆 pull 化）。
     *
     * 与 [renderForPrompt] 的形态差异是刻意的：这里**只输出标题**，正文不再进提示词 ——
     * 正文由模型按需用 `memory_search` 检索（再由 `memory_read` 看全文）。两个收益：
     *  ① 端侧 4B 的工作记忆不再被与当前任务无关的正文挤占；
     *  ② 更硬的一条 —— 正文随 `memory_write` 变化会让 `systemText` 变化，而 `systemText`
     *     是引擎的**会话重建判据**；改成标题索引后，只有「记忆条目增删」才会让提示词变，
     *     单纯改写某条正文不再触发全量 re-prefill（4B 秒级，Wave 24 验收点）。
     *
     * 空记忆返回 **null**（不注入、省 token，与今天一致）。
     * 超预算时截断并附「还有 N 条，用 memory_search 检索」—— 这条尾巴是 pull 化的
     * **入口声明**：模型在提示词里看到索引被截断时，才知道有检索这条路可走。
     * 无论预算多小都至少渲染一条（索引全空会让模型以为自己没有记忆）。
     *
     * 文件格式零改动：`memory.json` 仍是 [MemorySection] 列表，`renderForPrompt` 也保留
     * （`memory_read` 走的是 [sections] 直读，不经过这里；该渲染面留作兜底与人工核对）。
     */
    suspend fun renderIndex(maxChars: Int = INDEX_MAX_CHARS): String? {
        val list = sections()
        if (list.isEmpty()) return null
        val lines = ArrayList<String>(list.size)
        var used = 0
        var omitted = 0
        for (section in list) {
            // title 必须单行化（同 renderForPrompt）：title 是唯一键，模型可以往里写换行，
            // 换行会把注入段切成多条伪 section 头，伪造「系统提示词结构」。
            val line = "- [${section.title.replace("\n", " ")}]"
            val cost = if (lines.isEmpty()) line.length else line.length + 1
            // lines 为空时无条件放行：保证至少渲染一条。
            if (lines.isNotEmpty() && used + cost > maxChars) {
                omitted = list.size - lines.size
                break
            }
            lines.add(line)
            used += cost
        }
        val text = lines.joinToString("\n")
        return if (omitted > 0) {
            "$text\n…（还有 $omitted 条，用 memory_search 检索）"
        } else {
            text
        }
    }

    // ------------------------------------------------------------------
    // 写（工具路径）
    // ------------------------------------------------------------------

    /**
     * 写入 / 更新一条记忆（按标题 upsert）。返回是否**写入成功**。
     *
     * 返回 false 的两种原因**互不隶属**，调用方不得把后者当前者处理：
     *  ① `memory.json` 解析失败（[ReadState.Corrupted]）—— 拒写是为了保护原文件；
     *  ② content 超过 [MAX_CONTENT_CHARS] —— 存储面无界防护（见该常量 KDoc）。
     *
     * ⚠️ **已知消费端缺口（Wave 35 挂账，不在本波改）**：`feature-settings` 的
     * `MemoryViewModel.upsert` 把 `false` 一律解释成「文件损坏」（`corrupted = true`），
     * 故人在设置页粘贴超长正文时会看到「文件已损坏」的误报。修它要动 UI 文案，
     * 与 core 侧的本次改动解耦，单独挂账。模型侧不受影响：`MemoryWriteTool` 在
     * 调用本方法之前就用自己的前置校验拦下超限并回精确文案。
     */
    suspend fun upsert(title: String, content: String): Boolean = withContext(ioDispatcher) {
        mutex.withLock {
            val trimmedTitle = title.trim()
            val trimmedContent = content.trim()
            // Wave 35 D6：单条正文上限 —— 超限**拒绝**而非静默截断。
            // 判据见 [MAX_CONTENT_CHARS]；此处是存储层的兜底（防的是「换条路写进来」），
            // 模型路径的精确报错由 MemoryWriteTool 的前置校验给出（它在调本方法之前就拦下），
            // 故这里只落一条面向人的日志 + 回 false。绝不能静默截断：模型会以为 100KB
            // 全记住了，而注入面只读前 1200 字符，剩下的既存了又用不上还看不见。
            if (trimmedContent.length > MAX_CONTENT_CHARS) {
                AgentLogStore.error(
                    "记忆正文过长（${trimmedContent.length} 字符，上限 $MAX_CONTENT_CHARS），" +
                        "已拒绝写入「$trimmedTitle」：请拆成多条或压缩成结论"
                )
                return@withContext false
            }
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

        /**
         * 单条记忆正文的字符上限（Wave 35 D6）。**唯一定义处** —— `MemoryWriteTool`
         * 的前置校验引用本常量，两处不得各写一个 2000（历史上这类双份常量必然漂移）。
         *
         * 为什么必须有这个数：注入面早已有界（[MAX_SECTIONS] 条 × [PROMPT_MAX_CHARS]
         * 截断），但**存储面**此前无界 —— 写一条 100KB 正文会让 `memory.json` 无限膨胀，
         * 而每次会话真正用上的只有前 1200 字符：存了 100KB、用了 1200、且剩余部分
         * 用户在设置页也看不到（列表只渲染摘要）。磁盘与人眼两头都无收益。
         *
         * 为什么取 2000：是 [PROMPT_MAX_CHARS] 的一倍余量，足够「一到三句话的结论」
         * （工具描述就是这么要求模型的），又远低于「把细节搬进记忆」的规模。
         *
         * 为什么**拒绝**而不是截断：静默截断会让模型以为自己记住了全文（工具回
         * `ok = true`），实际只落了前 2000 字符 —— 这是比「写失败」更糟的结果，
         * 因为错误不可观测。拒绝并把上限写进报错文案，模型才知道该拆分或压缩。
         */
        const val MAX_CONTENT_CHARS = 2000

        /** 注入 prompt 的默认预算（字符）。 */
        const val PROMPT_MAX_CHARS = 1200

        /**
         * 标题索引的默认预算（字符）。
         *
         * 比 [PROMPT_MAX_CHARS] 小一半是刻意的：索引只含标题（每条十几字符），
         * 端侧几十条记忆的标题总量远低于 600；省下的 token 直接还给工作记忆。
         */
        const val INDEX_MAX_CHARS = 600
    }
}
