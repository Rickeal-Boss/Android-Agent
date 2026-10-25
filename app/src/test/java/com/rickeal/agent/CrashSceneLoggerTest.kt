package com.rickeal.agent

import com.rickeal.agent.core.model.AgentLog
import com.rickeal.agent.core.model.AgentLogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [CrashSceneLogger] 纯函数面的 JVM 单测（W83-08）。
 *
 * 只测 [CrashSceneLogger.crashSceneLines]——装 handler / 落盘 / 链式转交都碰
 * Android 与进程级单例，JVM 上不可断（那是真机判据：见 W83-07 验收批）。
 * 堆栈帧内容依赖调用点位置，只断「含本测试类名 / 含 cause 链」这类稳定形态。
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
        assertTrue(lines[1].startsWith("崩溃堆栈（前 10 帧）："), lines[1])
        assertTrue(lines[1].contains("CrashSceneLoggerTest"), "堆栈应含本测试帧：${lines[1]}")
        // cause 链在**头行**（堆栈行的 "Caused by:" 超出前 10 帧预算，不可断）：
        assertTrue(
            lines[0].contains("（cause 链：java.lang.IllegalStateException: root-cause）"),
            "头行应带 cause 链：${lines[0]}",
        )
    }

    @Test
    fun crashSceneLinesChunksRecentLogsByFour() {
        val recent = (1..10).map { AgentLog(AgentLogLevel.INFO, "log$it", 0L) }
        val lines = CrashSceneLogger.crashSceneLines("worker", RuntimeException("x"), recent)

        // 头 2 行 + ceil(10/4)=3 行历史日志
        assertEquals(5, lines.size)
        assertTrue(lines[2].startsWith("崩溃前日志："), lines[2])
        assertTrue(lines[2].contains("log1") && lines[2].contains("log4"), lines[2])
        assertTrue(!lines[2].contains("log5"), "第一块只应含 1-4 条：${lines[2]}")
        assertTrue(lines[4].contains("log9") && lines[4].contains("log10"), lines[4])
    }

    @Test
    fun crashSceneLinesOmitsLogSectionWhenNoHistory() {
        val lines = CrashSceneLogger.crashSceneLines("t", RuntimeException("x"), emptyList())
        assertEquals(2, lines.size)
        assertTrue(lines.none { it.startsWith("崩溃前日志：") })
    }
}
