package com.rickeal.agent.core.data

import com.rickeal.agent.core.model.AgentLog
import com.rickeal.agent.core.model.AgentLogLevel
import com.rickeal.agent.core.model.AgentLogStore
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * ERROR 级日志的**崩溃幸存**落盘（app 私有目录里的单文件、限长、环形覆盖）。
 *
 * ## 它解决什么
 * [AgentLogStore] 是纯内存的环形缓冲，进程一死就全没了 —— 而端侧最常见的故障就是崩溃
 * （模型加载 OOM 是典型）。不落盘的话，「重启后诊断页一片空白」会出现在最需要日志的场景里。
 *
 * ## 三条刻意的约束
 *  - **只落 ERROR 级**：error 本来就是「用户看到失败」的少数事件，量小；而崩溃前最后一条
 *    往往正好是它。warn / info 保持纯内存，不承担任何 IO 风险。
 *  - **限长 [MAX_ENTRIES] 条**：超出后压缩重写，只保留最近若干条，文件不会无限增长。
 *  - **写入即脱敏**：落盘的内容来自 [AgentLogStore.record] 里已 `sanitize()` 过的文本
 *    （`Bearer …` / `sk-…` / `?key=…` 等都已替换成 `***`）。磁盘上的日志比内存里的更危险 ——
 *    它可能通过备份、文件分享离开设备，所以脱敏必须发生在写之前，而不是读的时候。
 *
 * ## 为什么是**同步**写
 * 崩溃可能就发生在写日志的下一行代码。异步/缓冲写会把「崩溃前最后一条」一起丢掉，
 * 那恰恰是本类唯一要保住的东西。ERROR 是低频事件，一次几毫秒的追加换「崩溃有迹可循」是划算的。
 * 同理，[append] 全程 try/catch：它跑在「已经出错的路径」上，再抛异常只会把原始错误顶掉。
 */
class AgentLogFileStore(private val file: File) {

    /**
     * 把本存储装成 [AgentLogStore] 的出口。由 AppContainer 在启动时调用一次。
     *
     * 只转发 ERROR：warn / info 的「有异常但我兜住了」不值得我们付出磁盘 IO。
     */
    fun install() {
        AgentLogStore.setSink { level, message, atMillis ->
            if (level == AgentLogLevel.ERROR) append(atMillis, level, message)
        }
    }

    /**
     * 读回上次落盘的记录（按时间正序，最旧 → 最新）。
     *
     * 解析失败的行一律**跳过**而不是整体失败：崩溃时最后一行很可能被撕成半截，
     * 不能因为最后一行坏了就把前面完好的记录一起丢掉。文件不存在 / 读不到都返回空列表。
     */
    @Synchronized
    fun read(): List<AgentLog> {
        return runCatching {
            if (!file.exists()) return@runCatching emptyList<AgentLog>()
            // 只保留最后 MAX_ENTRIES 条：上一次压缩可能被崩溃打断而让文件略超上限，
            // 读侧再兜一次底，保证 UI 拿到的永远是有界的。
            val kept = ArrayDeque<AgentLog>()
            file.forEachLine { raw ->
                val entry = parse(raw) ?: return@forEachLine
                if (kept.size >= MAX_ENTRIES) kept.removeFirst()
                kept.addLast(entry)
            }
            kept.toList()
        }.getOrDefault(emptyList<AgentLog>())
    }

    /** 清空落盘记录（诊断页的「清空」按钮）。 */
    @Synchronized
    fun clear() {
        runCatching { file.delete() }
    }

    /**
     * 追加一条记录。
     *
     * **必须加锁**：`ensureLineBoundary()` 与 `appendText()` 是「读长度 → 补换行 → 追加」三步，
     * 两条日志并发进来会互相穿插 —— 两条都变成半截行，而 [read] 的解析是「坏行整条跳过」，
     * 结果就是**崩溃前的两条关键记录一起消失**，恰恰是本类最该保住的东西。
     * 用 `@Synchronized` 而不是协程 Mutex：本方法是同步写（不能挂起），且可能被非协程线程调用。
     *
     * 仍然是**同步**写（不改成异步、不加缓冲）：崩溃可能就发生在写日志的下一行代码，
     * 异步写会把「崩溃前最后一条」一起丢掉。
     */
    @Synchronized
    private fun append(atMillis: Long, level: AgentLogLevel, message: String) {
        runCatching {
            file.parentFile?.mkdirs()
            // 先保证「行边界」：崩溃可能把上一行写成半截且**没有换行**，
            // 这时直接 append 会把新记录拼到那半截后面，导致这条新记录也解析不出来 ——
            // 等于崩溃后重启的第一次报错被静默吞掉，恰恰是本类最该保住的场景。
            ensureLineBoundary()
            // append=true + 立即 close：数据在 close 时进入内核页缓存，
            // 进程崩溃（非掉电）不会丢；崩溃最多撕坏最后一行，而 read() 会跳过坏行。
            file.appendText(buildLine(atMillis, level, message) + "\n")
            compactIfNeeded()
        }
    }

    /** 文件末尾不是换行就先补一个（只读最后一个字节，不整文件扫描）。 */
    private fun ensureLineBoundary() {
        if (file.length() == 0L) return
        val lastByte = runCatching {
            RandomAccessFile(file, "r").use { handle ->
                handle.seek(file.length() - 1)
                handle.read()
            }
        }.getOrNull() ?: return
        if (lastByte != '\n'.code) file.appendText("\n")
    }

    /** 一行一条、制表符分隔。换行/制表符必须压成空格，否则会把「一行一条」的格式撕碎。 */
    private fun buildLine(atMillis: Long, level: AgentLogLevel, message: String): String {
        val flat = message
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('\t', ' ')
        return "$atMillis\t${level.name}\t$flat"
    }

    /**
     * 超过上限就压缩：保留最后 [MAX_ENTRIES] 行，先写临时文件再原子改名。
     *
     * 用「先写 tmp 再 rename」而不是就地截断：压缩过程中崩溃不能让整个文件变成半截 ——
     * 我们要保住的正是「崩溃前的记录」，压缩本身把记录写坏就本末倒置了。
     */
    private fun compactIfNeeded() {
        val lines = runCatching { file.readLines() }.getOrNull() ?: return
        if (lines.size <= MAX_ENTRIES) return
        val tmp = File(file.parentFile, "${file.name}.${System.nanoTime()}.tmp")
        // 刻意压到 MAX_ENTRIES - COMPACT_SLACK 而不是正好 MAX_ENTRIES：
        // 压到正好 50 之后，下一条 ERROR 立刻又是 51 → 又触发一次全文重写。
        // 稳定状态下每写一条都要读全文 + 写全文，而这里恰恰跑在「刚出错、可能马上又崩」的路径上 ——
        // 留 10 条余量把重写频率降到十分之一。
        val keep = (MAX_ENTRIES - COMPACT_SLACK).coerceAtLeast(1)
        tmp.writeText(lines.takeLast(keep).joinToString(separator = "\n", postfix = "\n"))
        try {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (t: Throwable) {
            // 部分文件系统不支持 ATOMIC_MOVE，退化为普通 rename（仍是元数据操作）
            runCatching {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            runCatching { tmp.delete() }
        }
    }

    /** `atMillis \t LEVEL \t message`；任何字段缺失或格式不对都返回 null（跳过该行）。 */
    private fun parse(raw: String): AgentLog? {
        if (raw.isBlank()) return null
        val firstTab = raw.indexOf('\t')
        if (firstTab <= 0) return null
        val secondTab = raw.indexOf('\t', firstTab + 1)
        if (secondTab <= firstTab) return null
        val atMillis = raw.substring(0, firstTab).toLongOrNull() ?: return null
        val level = runCatching {
            AgentLogLevel.valueOf(raw.substring(firstTab + 1, secondTab))
        }.getOrNull() ?: return null
        return AgentLog(level = level, message = raw.substring(secondTab + 1), atMillis = atMillis)
    }

    private companion object {
        /** 磁盘上最多保留多少条 ERROR。崩溃现场通常只看最后几条，50 条足够且体积可控。 */
        const val MAX_ENTRIES = 50

        /** 压缩时留出的余量：避免压到正好上限后「每写一条都全文重写」，见 [compactIfNeeded]。 */
        const val COMPACT_SLACK = 10
    }
}
