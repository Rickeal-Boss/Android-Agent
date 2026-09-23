package com.rickeal.agent.core.agent.journal

import com.rickeal.agent.core.model.AgentJson
import com.rickeal.agent.core.model.ChatMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * 一次 Agent run 的追加式日志（JSONL）—— 移植自 ZCode dynamic-workflow 的 Journal：
 * 「每一次有副作用的操作都带 site identity 进 journal，崩溃后可以 replay 已完成节点」。
 *
 * Android 的独特性让它比桌面端**更重要**：进程随时可能被系统杀掉（后台内存回收、
 * 崩溃、用户划掉应用）。当前实现里一次 run 只活在内存 `working` 列表里 —— 进程一死，
 * 已完成的推理轮、已执行的工具结果全部丢失，重启后模型只能从头再来（4B 推理一轮
 * 几十秒，重来的代价极高）。
 *
 * 本类把 run 的关键节点落成 append-only JSONL：
 *  - `run_started`   run 元信息（不含任何端点/密钥信息）
 *  - `round_started` 第 n 轮开始
 *  - `message`       一条进入 working 上下文的消息（含工具调用与工具结果）
 *  - `settled`       终态（ModelStopped / MaxRounds / Failed / Cancelled）
 *
 * 恢复路径：[committedMessages] 返回已落库的全部消息，调用方把它们当作 `history`
 * 传给下一次 run（[com.rickeal.agent.core.agent.AgentRequest.history]），即可从
 * 崩溃点继续 —— 不 replay 引擎内部状态（LiteRT Conversation 无法跨进程存活），
 * 但已花的推理与工具结果一分不丢。
 *
 * 可靠性边界（诚实声明）：
 *  - **journal 永远不是失败源**：所有写入 best-effort，失败只记日志、绝不向上抛，
 *    主循环的行为与没有 journal 时完全一致；
 *  - 追加写不做 fsync（Android 文件系统层面进程被杀时 write 已进内核页缓存，
 *    只有整机掉电才可能丢尾部），用「低开销 + 行级原子追加」换取每轮都写的性价比；
 *  - 不存密钥：endpoint/model 只记 id。
 */
class AgentRunJournal private constructor(
    private val file: File,
    private val ioDispatcher: CoroutineDispatcher,
) {

    /** 序号与互斥：JSONL 行号即恢复顺序，乱序写入会让 replay 语义失效。 */
    private val mutex = Mutex()
    private var seq: Long = 0L

    @Serializable
    data class JournalLine(
        val seq: Long,
        val atMillis: Long,
        val kind: String,
        val payload: JsonObject,
    )

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    suspend fun append(kind: String, payload: JsonObject): Unit = withContext(ioDispatcher) {
        // 构造与写入必须同锁：写在锁外时，先拿序号的协程可能后落盘，
        // 文件行序与 seq 乱序，replay 语义失效（Wave2 遗留：只锁了序号分配）。
        // 代价是锁内做阻塞 IO —— 但 journal 写入本来就应该是串行的，这正是语义要求。
        mutex.withLock {
            val line = JournalLine(
                seq = ++seq,
                atMillis = System.currentTimeMillis(),
                kind = kind,
                payload = payload,
            )
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(AgentJson.Default.encodeToString(JournalLine.serializer(), line) + "\n")
            }.onFailure {
                com.rickeal.agent.core.model.AgentLogStore.warn(
                    "journal 写入失败（忽略，不影响运行）：${it.javaClass.simpleName}"
                )
            }
        }
    }

    /** 记录一条进入上下文的消息（消息体用 ChatMessage 的标准序列化形态）。 */
    suspend fun appendMessage(message: ChatMessage) {
        chatMessagePayload(message)?.let { append(KIND_MESSAGE, it) }
    }

    /** 记录本次 run 的任务输入（崩溃恢复据此找回「被打断的任务是什么」）。 */
    suspend fun appendUserInput(message: ChatMessage) {
        chatMessagePayload(message)?.let { append(KIND_USER_INPUT, it) }
    }

    /** 记录「无进展检测」的合成提醒（独立行类型，恢复时不混入用户输入）。 */
    suspend fun appendReminder(message: ChatMessage) {
        chatMessagePayload(message)?.let { append(KIND_REMINDER, it) }
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    /** 同步读全量（journal 是小文件；调用方在 IO 线程调用）。 */
    fun readLines(): List<JournalLine> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines().mapNotNull { raw ->
                if (raw.isBlank()) return@mapNotNull null
                runCatching {
                    AgentJson.Default.decodeFromString(JournalLine.serializer(), raw)
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 已提交进上下文的消息（run_started/settled 之外的所有 `message` 行），
     * 按序重建 —— 传给下一次 run 的 [AgentRequest.history][com.rickeal.agent.core.agent.AgentRequest.history]。
     *
     * 注意「过程」与「任务」是两类行：本 run 的任务输入走 [KIND_USER_INPUT]
     * （[readUserInputSync]），合成提醒走 [KIND_REMINDER]——都不在这里返回，
     * 调用方按语义各取所需（Wave2 曾把提醒记成 message 行，恢复时被误判成用户输入）。
     */
    fun committedMessagesSync(): List<ChatMessage> =
        readLines().asSequence()
            .filter { it.kind == KIND_MESSAGE }
            .mapNotNull { line ->
                runCatching {
                    AgentJson.Default.decodeFromString(ChatMessage.serializer(), line.payload.toString())
                }.getOrNull()
            }
            .toList()

    /**
     * 本 run 的任务输入（最后一次 `user_input` 行；恢复 run 续写时可能是
     * 合成的「继续」消息）。没有该行 = 升级前崩溃的旧 journal，返回 null，
     * 调用方退化到合成「继续」输入。
     */
    fun readUserInputSync(): ChatMessage? =
        readLines().asSequence()
            .filter { it.kind == KIND_USER_INPUT }
            .lastOrNull()
            ?.let { line ->
                runCatching {
                    AgentJson.Default.decodeFromString(ChatMessage.serializer(), line.payload.toString())
                }.getOrNull()
            }

    // ------------------------------------------------------------------
    // 恢复处置
    // ------------------------------------------------------------------

    /**
     * 用户选择「不继续」时把 journal 改名归档而非删除：过程记录里可能有排查需要的
     * 工具结果，删除不可逆；改名后 [findUnsettled] 不再命中（恢复提示消失）。
     *
     * 目标名用 `.dismissed.jsonl` 结尾（保持 jsonl 结尾便于目录浏览），与
     * [findUnsettled] 的过滤后缀严格一致 —— Wave2 曾用 `.jsonl.dismissed` 结尾，
     * 而过滤匹配的是 `.dismissed.jsonl`，改名后的文件仍会被扫描成「未完成 run」。
     * 另外 `File.renameTo` 失败时返回 false 而**不抛异常**，`runCatching` 抓不到
     * —— 必须显式检查返回值，否则「丢弃」静默失败、恢复卡永远复活。
     */
    fun markDismissed() {
        val renamed = runCatching {
            file.renameTo(File(file.parentFile, file.nameWithoutExtension + ".dismissed.jsonl"))
        }.getOrDefault(false)
        if (!renamed && file.exists()) {
            com.rickeal.agent.core.model.AgentLogStore.warn(
                "journal 归档失败（目标已存在或被占用）：${file.name}"
            )
        }
    }

    // ------------------------------------------------------------------
    // 工厂
    // ------------------------------------------------------------------

    companion object {
        const val KIND_RUN_STARTED = "run_started"
        const val KIND_ROUND_STARTED = "round_started"
        const val KIND_MESSAGE = "message"
        const val KIND_SETTLED = "settled"

        /**
         * 本 run 的任务输入（独立于 message：恢复时由 [readUserInputSync] 单独取用，
         * 不混进「过程消息」）。Wave2 之前从不记录任务输入，恢复上下文里没有
         * 「被打断的任务是什么」，4B 模型只能对着工具残骸盲猜 —— 这是恢复质量的
         * 最大缺口。
         */
        const val KIND_USER_INPUT = "user_input"

        /**
         * 「无进展检测」的合成提醒（独立于 message）：提醒是一次性行为矫正，
         * 不是用户说的话，混进 message 会让恢复流程把「你的最新回复重复了…」
         * 当成用户输入渲染进界面。
         */
        const val KIND_REMINDER = "reminder"

        /** ChatMessage → journal payload 的统一序列化形态（message / user_input 共用）。 */
        fun chatMessagePayload(message: ChatMessage): JsonObject? = runCatching {
            AgentJson.Default.parseToJsonElement(
                AgentJson.Default.encodeToString(ChatMessage.serializer(), message)
            ) as? JsonObject
        }.getOrNull()

        /** 一个可恢复 run 的摘要（给 UI 出「继续/丢弃」选择用）。 */
        data class UnsettledRun(
            val runId: String,
            /** journal 里已落盘的上下文消息数（含工具调用与结果）。 */
            val messageCount: Int,
            val lastAtMillis: Long,
            val journal: AgentRunJournal,
        )

        /**
         * 扫描某会话的 journal 目录，找出**没有 settled 行**（= 进程死亡即
         * TerminationReason.Interrupted）且至少有一条上下文消息的 run；
         * 返回最近的一个，没有则 null。调用方在 IO 线程调用。
         */
        fun findUnsettled(runDir: File): UnsettledRun? {
            if (!runDir.isDirectory) return null
            var best: UnsettledRun? = null
            val files = runDir.listFiles { file -> file.isFile && file.name.endsWith(".jsonl") } ?: return null
            for (file in files) {
                // 归档文件跳过。两种历史后缀都要挡：`.dismissed.jsonl`（现行为）与
                // `.jsonl.dismissed`（Wave2 旧命名，目录里可能还有存量）。
                if (file.name.endsWith(".dismissed.jsonl") || file.name.endsWith(".jsonl.dismissed")) continue
                val runId = file.name.removeSuffix(".jsonl")
                val journal = open(runDir, runId)
                val lines = journal.readLines()
                if (lines.isEmpty()) continue
                if (lines.last().kind == KIND_SETTLED) continue
                val messageCount = lines.count { it.kind == KIND_MESSAGE }
                if (messageCount == 0) continue
                val candidate = UnsettledRun(runId, messageCount, lines.last().atMillis, journal)
                if (best == null || candidate.lastAtMillis > best.lastAtMillis) best = candidate
            }
            return best
        }

        /**
         * 打开（或新建）一个 run 的 journal。
         *
         * @param runDir 目录，约定 `<filesDir>/journal/<conversationId>/`
         * @param runId  run 标识；调用方用 `时间戳` 或消息 id 即可，只需同一 run 内稳定
         */
        fun open(runDir: File, runId: String, ioDispatcher: CoroutineDispatcher = Dispatchers.IO): AgentRunJournal {
            val safeId = runId.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80).ifBlank { "run" }
            return AgentRunJournal(File(runDir, "$safeId.jsonl"), ioDispatcher)
        }

        /** run_started 的标准 payload（不含端点 URL / 密钥等敏感信息）。 */
        fun runStartedPayload(conversationId: String?, modelRef: String?): JsonObject =
            buildJsonObject {
                put("conversationId", conversationId)
                put("modelRef", modelRef)
            }

        fun roundStartedPayload(round: Int, maxRounds: Int): JsonObject = buildJsonObject {
            put("round", round)
            put("maxRounds", maxRounds)
        }

        fun settledPayload(termination: String, rounds: Int): JsonObject = buildJsonObject {
            put("termination", termination)
            put("rounds", rounds)
        }
    }
}
