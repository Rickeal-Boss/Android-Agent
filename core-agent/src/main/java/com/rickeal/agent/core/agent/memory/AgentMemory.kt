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
 * 一次记忆写入的结果（Wave 37）。
 *
 * 为什么不是 Boolean：写入有**五种**互不隶属的结局 —— 成功 / 正文超限 / 文件损坏拒写 /
 * **落盘失败** / **读不了既有文件**。Boolean 只能表达「成功 / 非成功」，而 `writeSync` 曾把
 * 落盘异常吞进日志后让 `upsert` 回 `true` ⇒ 磁盘满时用户与模型都以为记住了，实际一个字节都
 * 没落盘（静默成功）。把结局做成类型，调用方就无法「顺手」把它当成成功。
 */
sealed interface MemoryWriteResult {
    /** 面向人的失败原因；成功时为 null。 */
    val userMessage: String?

    data object Ok : MemoryWriteResult {
        override val userMessage: String? = null
    }

    /** 正文超 [AgentMemory.MAX_CONTENT_CHARS]。 */
    data class TooLong(val length: Int) : MemoryWriteResult {
        override val userMessage: String
            get() = "内容过长（$length 字符，上限 ${AgentMemory.MAX_CONTENT_CHARS}）：请拆成多条或压缩成结论"
    }

    /** `memory.json` 解析失败，拒写以保护原文件。 */
    data object Corrupted : MemoryWriteResult {
        override val userMessage: String
            get() = "记忆文件解析失败，已拒绝写入以保护原文件；请人工修复或删除 agent_memory/memory.json"
    }

    /** **落盘失败**（磁盘满 / 权限 / IO）。此前被 `writeSync` 吞掉且 `upsert` 仍回 true。 */
    data class WriteFailed(val cause: String) : MemoryWriteResult {
        override val userMessage: String
            get() = "记忆写入磁盘失败（$cause）：本次未保存，请检查存储空间后重试"
    }

    /**
     * 记忆文件**读不了**（权限 / IO）：为避免覆盖不可读的既有内容，本次未写入。
     *
     * 与 [WriteFailed] 的区别：那条是「读到了、写不进去」，本条是「连读都读不了」。
     * Wave 38 新增 —— 此前 `readState()` 把「读不了」与「解析失败」同归 Corrupted，
     * 于是权限问题也被报成「文件解析失败，请修复或删除」，而「删除」对权限问题是有害建议
     * （会诱导用户删掉一个内容完好的文件）。
     */
    data class Unreadable(val cause: String) : MemoryWriteResult {
        override val userMessage: String
            get() = "无法读取记忆文件（$cause）：为避免覆盖既有内容，本次未写入。请检查存储权限后重试。"
    }
}

/** 一次记忆删除的结果（Wave 37，与 [MemoryWriteResult] 同款动机）。 */
sealed interface MemoryRemoveResult {
    val userMessage: String?

    data object Removed : MemoryRemoveResult {
        override val userMessage: String? = null
    }

    /** 条目不存在，或记忆文件不存在。 */
    data object NotFound : MemoryRemoveResult {
        override val userMessage: String get() = "未找到该记忆条目"
    }

    data object Corrupted : MemoryRemoveResult {
        override val userMessage: String
            get() = "记忆文件解析失败，已拒绝删除写入；请人工修复或删除 agent_memory/memory.json"
    }

    data class WriteFailed(val cause: String) : MemoryRemoveResult {
        override val userMessage: String
            get() = "记忆删除未能落盘（$cause）：本次未保存，请检查存储空间后重试"
    }

    /**
     * 记忆文件**读不了**（权限 / IO）：本次未删除。
     *
     * 与 [Corrupted] 的区别同 [MemoryWriteResult.Unreadable]：Corrupted 是「能读、但解析失败」，
     * 处置是「修复或删除文件」；本条是「连读都读不了」，该处置有害。Wave 38 新增。
     */
    data class Unreadable(val cause: String) : MemoryRemoveResult {
        override val userMessage: String
            get() = "无法读取记忆文件（$cause）：本次未删除。请检查存储权限后重试。"
    }
}

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

    // ── renderForPrompt（全量注入版）已于 Wave 36 E3 判死删除 ────────────────────
    // 删了什么：`suspend fun renderForPrompt(maxChars: Int = PROMPT_MAX_CHARS): String?`
    //   （连同其上方 KDoc）—— 把**全部**条目正文（截断 1200 字符）渲染进 system prompt。
    // 为什么可删：全仓零引用（含测试源集逐文件核验），从未被真实调用，仅存 KDoc 互引。
    //   它已被 [renderIndex] 取代 —— Wave 34 题 B 把记忆从「全量注入」改成「标题索引注入
    //   + 按需检索」，实际注入走 feature-chat 的 ChatRunCoordinator.renderMemoryIndex()。
    //   留着一个「正文进 systemText」的渲染面，只会让这条**已被否决**的路径（正文变化会
    //   改变 systemText ⇒ 引擎会话重建判据每轮命中 ⇒ 4B 秒级 re-prefill）看起来仍可用。
    // 恢复路径：git 历史可回溯，不留死代码占位。
    // 注意：本函数曾唯一引用常量 [PROMPT_MAX_CHARS]，该常量因是 [MAX_CONTENT_CHARS] 的
    //   推导参照而保留（见其 KDoc），非死代码。

    /**
     * 渲染成注入 system prompt 的**标题索引**（Wave 34 题 B：记忆 pull 化）。
     *
     * 与**全量注入**形态的差异是刻意的：这里**只输出标题**，正文不再进提示词 ——
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
     * 文件格式零改动：`memory.json` 仍是 [MemorySection] 列表（`memory_read` 走的是
     * [sections] 直读，不经过任何渲染面）。原「全量注入版」`renderForPrompt` 已判死删除
     * （见上方留痕），注入面只剩本方法这一条路径。
     */
    suspend fun renderIndex(maxChars: Int = INDEX_MAX_CHARS): String? {
        val list = sections()
        if (list.isEmpty()) return null
        val lines = ArrayList<String>(list.size)
        var used = 0
        var omitted = 0
        for (section in list) {
            // title 必须单行化：title 是唯一键，模型可以往里写换行，
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
     * 写入 / 更新一条记忆（按标题 upsert），返回 [MemoryWriteResult] —— 五种结局：
     *  - [MemoryWriteResult.Ok]：已落盘；
     *  - [MemoryWriteResult.TooLong]：正文超 [MAX_CONTENT_CHARS]（存储面无界防护，见该常量 KDoc）；
     *  - [MemoryWriteResult.Corrupted]：`memory.json` 解析失败（[ReadState.Corrupted]）—— 拒写以保护原文件；
     *  - [MemoryWriteResult.Unreadable]：`memory.json` **读不了**（[ReadState.Unreadable]，权限 / IO）—— 拒写以免覆盖不可读内容。Wave 38 新增；
     *  - [MemoryWriteResult.WriteFailed]：**落盘失败**（磁盘满 / 权限 / IO）。Wave 37 新增。
     *
     * 为什么不是 Boolean（Wave 37）：Boolean 只能表达「成功 / 非成功」，无法区分上面五条互不隶属
     * 的出口；更糟的是 `writeSync` 曾把落盘异常吞进日志后让本方法回 `true` ⇒ 磁盘满时用户与模型
     * 都以为记住了，实际一个字节都没落盘（静默成功）。把结局做成类型后，调用方无法把它当成成功。
     *
     * Wave 36 的「按正文长度反推原因」启发式（旧 `upsertFailureReason`）**已被结果类型取代** ——
     * 那是更强的做法：不再靠「长度 / 解析失败」二选一的推断还原原因，而是由本方法直接把结局
     * 交给调用方。UI 侧消费通道是 `MemoryUiState.editError`（编辑对话框内联、失败不关以保留输入），
     * 模型侧是 `MemoryWriteTool`（把 [MemoryWriteResult.userMessage] 作为工具失败回执透传）。
     */
    suspend fun upsert(title: String, content: String): MemoryWriteResult = withContext(ioDispatcher) {
        mutex.withLock {
            val trimmedTitle = title.trim()
            val trimmedContent = content.trim()
            // Wave 35 D6：单条正文上限 —— 超限**拒绝**而非静默截断。
            // 判据见 [MAX_CONTENT_CHARS]；此处是存储层的兜底（防的是「换条路写进来」），
            // 模型路径的精确报错由 MemoryWriteTool 的前置校验给出（它在调本方法之前就拦下），
            // 故这里只落一条面向人的日志 + 回 TooLong。绝不能静默截断：模型会以为 100KB
            // 全记住了，而注入面只读前 1200 字符，剩下的既存了又用不上还看不见。
            if (trimmedContent.length > MAX_CONTENT_CHARS) {
                AgentLogStore.error(
                    "记忆正文过长（${trimmedContent.length} 字符，上限 $MAX_CONTENT_CHARS），" +
                        "已拒绝写入「$trimmedTitle」：请拆成多条或压缩成结论"
                )
                return@withContext MemoryWriteResult.TooLong(trimmedContent.length)
            }
            // Wave4 审查（E-P0-2）：读态判别 —— 文件存在但解析失败时**拒写**。
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
                    return@withContext MemoryWriteResult.Corrupted
                }
                is ReadState.Unreadable -> {
                    // Wave 38：与 Corrupted 分开报 —— 读不了（权限 / IO）时若复用 Corrupted 的
                    // 「请删除文件」文案，会诱导用户删掉一个内容完好的文件（读不了 ≠ 坏了）。
                    AgentLogStore.error(
                        "记忆文件读不了（${file.name}，${state.cause}），已拒绝写入以避免覆盖既有内容；请检查存储权限"
                    )
                    return@withContext MemoryWriteResult.Unreadable(state.cause)
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
            // Wave 37：writeSync 不再吞异常 —— 落盘失败时把原因映射成 WriteFailed 交给调用方，
            // 而不是无条件回 Ok（那正是「磁盘满却被告知已记住」的静默成功根因）。
            val failure = writeSync(current.takeLast(MAX_SECTIONS))
            if (failure == null) {
                MemoryWriteResult.Ok
            } else {
                MemoryWriteResult.WriteFailed(failure.javaClass.simpleName)
            }
        }
    }

    suspend fun remove(title: String): MemoryRemoveResult = withContext(ioDispatcher) {
        mutex.withLock {
            val current = when (val state = readState()) {
                is ReadState.Ok -> state.sections.toMutableList()
                is ReadState.Corrupted -> {
                    AgentLogStore.error("记忆文件解析失败（${file.name}），已拒绝删除写入")
                    return@withContext MemoryRemoveResult.Corrupted
                }
                is ReadState.Unreadable -> {
                    // Wave 38：读不了（权限 / IO）如实回 Unreadable —— 不复用 Corrupted 的
                    // 「请删除文件」建议（对权限问题有害），也不谎报成 NotFound。
                    AgentLogStore.error("记忆文件读不了（${file.name}，${state.cause}），已拒绝删除；请检查存储权限")
                    return@withContext MemoryRemoveResult.Unreadable(state.cause)
                }
                ReadState.Absent -> return@withContext MemoryRemoveResult.NotFound
            }
            val removed = current.removeAll { it.title == title.trim() }
            if (!removed) return@withContext MemoryRemoveResult.NotFound
            val failure = writeSync(current)
            if (failure == null) {
                MemoryRemoveResult.Removed
            } else {
                MemoryRemoveResult.WriteFailed(failure.javaClass.simpleName)
            }
        }
    }

    // ------------------------------------------------------------------

    /**
     * 读四态：文件不存在 / 正常 / **能读但解析失败** / **读不了**（权限 / IO）。
     *
     * 「损坏」与「读不了」都绝不能坍缩成「空」，否则写路径会覆写掉原文件；而两者之间也不能
     * 再坍缩成一个 —— 处置建议相反：解析失败可「修复或删除文件」，读不了则该建议有害
     * （Wave 38 修的读态坍缩）。
     */
    private sealed interface ReadState {
        data object Absent : ReadState
        data class Ok(val sections: List<MemorySection>) : ReadState
        data object Corrupted : ReadState

        /**
         * 文件存在但**读不了**（权限 / IO）。与 [Corrupted]（能读但解析失败）语义不同：
         * [Corrupted] 的处置建议是「修复或删除文件」，而 IO 问题下该建议有害（可能诱导用户删掉好文件）。
         */
        data class Unreadable(val cause: String) : ReadState
    }

    private fun readState(): ReadState {
        if (!file.exists()) return ReadState.Absent
        // 读与解析**分开捕获**：`readText()` 抛（权限 / IO）与 decode 抛（JSON 非法）是两类
        // 互不隶属的结局，此前 `runCatching{ 读+解析 }` 把两者同归 Corrupted ⇒ 权限问题被报成
        // 「解析失败，请修复或删除文件」（Wave 38）。两条既有行为一字不变：不存在 / 空白仍回 Absent。
        val raw = runCatching { file.readText() }
            .getOrElse { return ReadState.Unreadable(it.javaClass.simpleName) }
        if (raw.isBlank()) return ReadState.Absent
        return runCatching {
            ReadState.Ok(AgentJson.Default.decodeFromString(ListSerializer(MemorySection.serializer()), raw))
        }.getOrElse { ReadState.Corrupted }
    }

    /**
     * 兼容旧读法：不区分损坏与为空（仅用于只读渲染路径，写路径必须走 [readState]）。
     *
     * Wave 38：新增的 [ReadState.Unreadable]（权限 / IO 读不了）在此坍缩成空列表 —— 于是
     * 「记忆页空白 / 提示词索引为空」在权限问题下无解释。此处**刻意不落日志**：本方法在渲染
     * 路径上可能被高频调用，而 AgentMemory 内没有「只报一次」的一次性闩，逐次 warn 会刷屏；
     * 宁可静默并在此注明 —— 写路径的 upsert / remove 对同一状态各落一条 error，可观测性不丢。
     */
    private fun readSync(): List<MemorySection> =
        (readState() as? ReadState.Ok)?.sections ?: emptyList()

    /**
     * 原子写整份记忆文件；**成功返回 null，失败返回那个异常**。
     *
     * Wave 37：此前吞掉异常并让调用方回成功（`runCatching{...}.onFailure{ warn }` 后
     * `upsert` 无条件 `return true`）；现把原因交给调用方映射成 [MemoryWriteResult.WriteFailed]，
     * 磁盘满 / 权限 / IO 失败不再是不可观测的静默成功。
     */
    private fun writeSync(sections: List<MemorySection>): Throwable? {
        // tmp 提到 try 之外：失败时要能引用到它，尽力回收残留的 .tmp（见下 onFailure）。
        var tmp: File? = null
        return runCatching {
            file.parentFile?.mkdirs()
            // 原子写（tmp + ATOMIC_MOVE）：直接 writeText 覆写时进程死在半路会留下
            // 半截 JSON，下次读解析失败 → 静默清零（readSync 的 getOrDefault）——
            // 用户全部长期记忆凭空蒸发。Wave2 遗留缺陷。
            val tmpFile = File(file.parentFile, file.name + "." + System.nanoTime() + ".tmp")
            tmp = tmpFile
            tmpFile.writeText(AgentJson.Default.encodeToString(ListSerializer(MemorySection.serializer()), sections))
            try {
                Files.move(
                    tmpFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (t: Throwable) {
                // 个别文件系统不支持 ATOMIC_MOVE，退化普通 rename（仍是元数据操作）
                Files.move(tmpFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure {
            // Wave 37：失败会静默留下永不回收的 .tmp —— 尽力删掉（尽力而为，删除失败不掩盖原异常）。
            runCatching { tmp?.takeIf { it.exists() }?.delete() }
            AgentLogStore.warn(
                "记忆写入失败：${it.javaClass.simpleName}"
            )
        }.exceptionOrNull()
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

        /**
         * 历史设计基准（[MAX_CONTENT_CHARS] 的推导参照），**当前无代码引用**。
         *
         * 它曾是「全量注入版」`renderForPrompt` 的默认预算；该函数已于 Wave 36 E3 判死删除
         * （见类内留痕）。保留本常量是因为 [MAX_CONTENT_CHARS] 的取值（2000 = 本值的一倍余量）
         * 以它为参照推导 —— 删掉会让那段理由失去可引用的锚。**不要因为「无引用」而删除它。**
         */
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
