package com.rickeal.agent

import com.rickeal.agent.core.model.AgentLog
import com.rickeal.agent.core.model.AgentLogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [CrashSceneLogger] 纯函数面的 JVM 单测（W83-08；W84-07 订正期望值）。
 *
 * 只测 [CrashSceneLogger.crashSceneLines]——装 handler / 落盘 / 链式转交都碰
 * Android 与进程级单例，JVM 上不可断（那是真机判据：见 W83-07 验收批）。
 * 堆栈帧内容依赖调用点位置，只断「含本测试类名 / 含 cause 链」这类稳定形态。
 *
 * ⚠️ W84-07 起**帧数不是固定值**：`stackLine` 按 400 字符落盘预算反推实取帧数
 * （见 [CrashSceneLogger] 的 `LOG_LINE_BUDGET`），行内自报的「前 N 帧」才是真相。
 * 所以这里断的是「自报帧数 == 实际帧数」「每行都不越过 400 字符」这类**结构性**事实，
 * 不再钉 `前 10 帧` 这类会随预算漂移的字面量。
 */
class CrashSceneLoggerTest {

    @Test
    fun crashSceneLinesCarriesThreadExceptionAndTopFrames() {
        val throwable = RuntimeException("boom", IllegalStateException("root-cause"))
        val lines = CrashSceneLogger.crashSceneLines("main", throwable, emptyList())

        assertEquals(2, lines.size, "无历史日志时只有头两行")
        assertTrue(lines[0].contains("线程=main"), lines[0])
        assertTrue(lines[0].contains("java.lang.RuntimeException"), lines[0])
        assertTrue(lines[0].contains("boom"), lines[0])
        assertTrue(lines[1].startsWith("崩溃堆栈（前 "), lines[1])
        assertTrue(lines[1].contains("CrashSceneLoggerTest"), "堆栈应含本测试帧：${lines[1]}")
        // 头行 + 实取帧数：自报的 N 必须等于实际帧行数，否则「名义 = 实效」又断了。
        // ⚠️ 帧与表头**同一行**拼接（`"…前 N 帧）：" + 帧1`）⇒ 实际帧行数 = lines().size，
        // 不是 size-1（W84 首次实现按 -1 算，off-by-one 假红两条，见本波教训）。
        val reportedFrames = lines[1].substringAfter("（前 ").substringBefore(" 帧）").toInt()
        assertEquals(lines[1].lines().size, reportedFrames, "自报帧数应等于实际帧行数：${lines[1]}")
        assertTrue(reportedFrames in 1..6, "帧数应落在 1..6（STACK_FRAME_LIMIT 封顶），实际 $reportedFrames")
        // cause 链在**头行**（堆栈行的 "Caused by:" 落在帧预算之外，不可断）：
        assertTrue(
            lines[0].contains("（cause 链：java.lang.IllegalStateException: root-cause）"),
            "头行应带 cause 链：${lines[0]}",
        )
    }

    @Test
    fun crashSceneLinesWritesOneLinePerRecentLog() {
        val recent = (1..10).map { AgentLog(AgentLogLevel.INFO, "log$it", 0L) }
        val lines = CrashSceneLogger.crashSceneLines("worker", RuntimeException("x"), recent)

        // 头 2 行 + 10 条日志**各占一行**（旧实现 4 条拼一行、行尾条目被 400 字符截断吞掉）
        assertEquals(12, lines.size, "应为头 2 行 + 10 条各一行")
        assertTrue(lines[2].startsWith("崩溃前日志："), lines[2])
        assertTrue(lines[2].contains("log1"), lines[2])
        assertTrue(!lines[2].contains("log2"), "一条一行，第一行只应含 log1：${lines[2]}")
        assertTrue(lines[11].contains("log10"), lines[11])
    }

    @Test
    fun crashSceneLinesOmitsLogSectionWhenNoHistory() {
        val lines = CrashSceneLogger.crashSceneLines("t", RuntimeException("x"), emptyList())
        assertEquals(2, lines.size)
        assertTrue(lines.none { it.startsWith("崩溃前日志：") })
    }

    /**
     * 深栈 + 长包名（本仓真实形态）：堆栈行仍必须 ≤ 400 字符，且自报帧数与实际帧数一致。
     *
     * 这是 W84-07 的回归闸：旧实现固定取 10 帧 ⇒ 拼出来 ~1000 字符 ⇒ 被出口截到 400，
     * 实际只剩 3~4 帧而常量写着 10。
     */
    @Test
    fun crashSceneLinesKeepsStackLineWithinStoreBudget() {
        val deep = RuntimeException("deep").apply {
            stackTrace = (1..20).map { index ->
                StackTraceElement(
                    "com.rickeal.agent.core.engine.local.LiteRtLmEngine",
                    "streamGenerateRound$index",
                    "LiteRtLmEngine.kt",
                    500 + index,
                )
            }.toTypedArray()
        }
        val lines = CrashSceneLogger.crashSceneLines("main", deep, emptyList())

        assertTrue(lines[1].length <= 400, "堆栈行 ${lines[1].length} 字符，越过 400 字符落盘预算")
        assertTrue(lines[1].contains("LiteRtLmEngine"), lines[1])
        val reportedFrames = lines[1].substringAfter("（前 ").substringBefore(" 帧）").toInt()
        assertEquals(lines[1].lines().size, reportedFrames, "自报帧数应等于实际帧行数：${lines[1]}")
        assertTrue(reportedFrames in 1..6, "帧数应落在 1..6，实际 $reportedFrames")
    }

    /**
     * 业务日志接近出口上限（单条 ~300 字符）时：20 条仍**一条一行全落**，每行 ≤ 400。
     *
     * 旧实现 4 条拼一行（~1200 字符）⇒ 被截到 400 ⇒ 每行只剩 1 条，名义 20 条实际 5 条。
     */
    @Test
    fun crashSceneLinesKeepsEveryLogLineWithinStoreBudget() {
        val recent = (1..20).map { index ->
            AgentLog(AgentLogLevel.ERROR, "x".repeat(300) + "#$index", 0L)
        }
        val lines = CrashSceneLogger.crashSceneLines("worker", RuntimeException("x"), recent)

        assertEquals(22, lines.size, "头 2 行 + 20 条各一行")
        assertTrue(lines.all { it.length <= 400 }, "存在越过 400 字符的行：${lines.map { it.length }}")
        // 条数承诺：20 条一条不少，且尾部标记仍在（300 字符 + 前缀没触发截断）。
        val logLines = lines.drop(2)
        assertEquals(20, logLines.size)
        logLines.forEachIndexed { offset, line ->
            assertTrue(line.contains("#${offset + 1}"), "第 ${offset + 1} 条丢失或被截断：$line")
        }
    }
}
