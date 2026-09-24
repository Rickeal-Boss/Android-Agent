package com.rickeal.agent.core.agent.history

import com.rickeal.agent.core.model.AgentJson
import com.rickeal.agent.core.model.AgentLogStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 会话级分段历史（Octop SegmentedHistoryStore 降级）：每会话一个目录，
 * `segments.jsonl` 追加式回合索引（同 turnId 以**最后一条**为准 —— commitTurn
 * 重放天然幂等）+ [pool] 正文池。
 *
 * 归档协议（s3 审查定序）：宿主在 run 终态后先 [commitTurn] 成功、再 rename
 * journal 为 `.archived` —— 反序窗口会让回合记录与 journal 双双丢失。
 * `.jsonl.archived` 后缀不匹配 [AgentRunJournal.findUnsettled] 的 `.jsonl`
 * 白名单，归档文件天然退出恢复扫描，journal 代码零改动。
 */
class SegmentedHistoryStore(
    private val dir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    val pool: ContentAddressedPool = ContentAddressedPool(File(dir, "blobs"), ioDispatcher)

    private val segmentsFile: File = File(dir, "segments.jsonl")

    /**
     * 归档互斥（Wave4 审查 C-P1-2 / E-P1-2）：`commitTurn` 有两个并发入口 ——
     * `onStop` 的取消路径与四终态的归档路径都在 `viewModelScope.launch(Dispatchers.IO)`
     * 上触发，且每次 `open()` 都产生新实例（无共享锁）。O_APPEND 的并发 appendText
     * 在行长超过内核原子写上限时交错出半行 JSON，读侧 mapNotNull 把整条回合记录
     * **静默丢弃**。这里用实例级 Mutex 串行「构造行 + 一次 appendText」。
     */
    private val commitMutex = Mutex()

    /** 追加一条回合记录（同 turnId 重放幂等：读侧取最后一条）。失败只记日志。 */
    suspend fun commitTurn(record: TurnRecord) = withContext(ioDispatcher) {
        commitMutex.withLock {
            runCatching {
                dir.mkdirs()
                segmentsFile.appendText(AgentJson.Default.encodeToString(TurnRecord.serializer(), record) + "\n")
            }.onFailure {
                AgentLogStore.warn("回合归档失败（忽略，不影响运行）：${it.javaClass.simpleName}")
            }
        }
    }

    /** 全量回合（按 turnId 去重取最后一条；损坏行跳过但**必须可见**）。 */
    fun listTurnsSync(): List<TurnRecord> {
        if (!segmentsFile.exists()) return emptyList()
        return runCatching {
            val lines = segmentsFile.readLines().filter { it.isNotBlank() }
            // 损坏行可见化（外部审查报告2 §4.3）：旧实现 mapNotNull 静默丢行，
            // 并发 append 交错出的半行 JSON 会让回合记录无声消失 —— 排障时一行日志都没有。
            // 跳过仍是正确处置（归档层绝不成为恢复的失败源），但必须留下痕迹。
            val (ok, bad) = lines.partition { line ->
                runCatching { AgentJson.Default.decodeFromString(TurnRecord.serializer(), line) }.isSuccess
            }
            if (bad.isNotEmpty()) {
                AgentLogStore.warn("回合归档损坏：跳过 ${bad.size} 行（${segmentsFile.name}）")
            }
            ok.mapNotNull { line ->
                runCatching {
                    AgentJson.Default.decodeFromString(TurnRecord.serializer(), line)
                }.getOrNull()
            }
                .associateBy { it.turnId }
                .values
                .sortedBy { it.startedAtMillis }
        }.getOrDefault(emptyList())
    }

    /**
     * 从归档重建正文序列（**本轮备而不用**：恢复权威仍是 journal，Wave 3.5 接管时启用）。
     * 正文缺失（blob 损坏/被清）的回合降级跳过 —— 归档层绝不成为恢复的失败源。
     */
    fun rebuildHistorySync(uptoTurnId: String? = null): List<String> {
        val turns = listTurnsSync()
        val end = uptoTurnId?.let { id -> turns.indexOfFirst { it.turnId == id } }
            ?.takeIf { it >= 0 }
            ?.plus(1)
            ?: turns.size
        val out = ArrayList<String>()
        for (turn in turns.take(end)) {
            turn.finalTextRef?.let { pool.getSync(it) }?.let { out += it }
        }
        return out
    }

    /**
     * 把 settled 的 journal 归档（改名 `.jsonl.archived`）。
     * **调用契约**：必须在本回合 [commitTurn] 成功之后（硬顺序，见类注释）；
     * renameTo 失败返回 false 而不抛异常（AgentRunJournal.markDismissed 的教训）——
     * 显式检查返回值，失败只记日志（journal 留在原地，无害）。
     */
    fun archiveJournalSync(journalFile: File): Boolean {
        val renamed = runCatching {
            journalFile.renameTo(
                File(journalFile.parentFile, journalFile.nameWithoutExtension + ".jsonl.archived")
            )
        }.getOrDefault(false)
        if (!renamed && journalFile.exists()) {
            AgentLogStore.warn("journal 归档失败（目标可能已存在）：${journalFile.name}")
        }
        return renamed
    }

    companion object {
        /** 按会话打开（惰性建目录 —— 首次 commitTurn 才 mkdirs，不产生空目录）。 */
        fun open(historyRoot: File, conversationId: String, ioDispatcher: CoroutineDispatcher = Dispatchers.IO): SegmentedHistoryStore =
            SegmentedHistoryStore(File(historyRoot, conversationId), ioDispatcher)
    }
}
