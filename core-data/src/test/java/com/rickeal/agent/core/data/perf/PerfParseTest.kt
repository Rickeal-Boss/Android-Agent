package com.rickeal.agent.core.data.perf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * /proc 解析纯函数行为锁定（Wave 30 §2.2，R8-1）。
 *
 * /proc/self/stat 第 2 列 comm 可能含空格甚至括号 —— 这是 /proc 解析的头号错位点，
 * 本测试用含空格 / 含括号进程名样本钉住「substringAfterLast(')') 再切分」的口径。
 */
class PerfParseTest {

    // ── parseProcSelfStatCpuSeconds ──────────────────────────────────────────

    @Test
    fun `普通进程名解析 utime 加 stime 除以 clkTck`() {
        // substringAfterLast(')') → "S 1 2 3 4 5 6 7 8 9 10 120 80 ..."
        // 切分后 index: 0=state，1..10 = ppid..cmajflt（stat 第 4..13 列，共 10 个），
        // 11=utime（第 14 列） 12=stime（第 15 列）。
        assertEquals(
            (120L + 80L) / 100.0,
            PerfParseTestAccess.parse("1234 (main) S 1 2 3 4 5 6 7 8 9 10 120 80 0 0 0 0"),
        )
    }

    @Test
    fun `真实 stat 形态（11 个前置列）索引不错位`() {
        // 真机 /proc/self/stat 真实列序：pid comm state ppid pgrp session tty_nr tpgid
        // flags minflt cminflt majflt cmajflt utime stime ...（utime/stime 为第 14/15 列）。
        val line = "1 (init) S 0 0 0 0 -1 4194304 100 0 0 0 30 120 0 0 20 0 1 0 1 3 0 1234"
        assertEquals((30L + 120L) / 100.0, PerfParseTestAccess.parse(line))
    }

    @Test
    fun `多空格与制表符分隔仍解析一致`() {
        val compact = "1 (init) S 0 0 0 0 -1 4194304 100 0 0 0 30 120 0 0"
        val loose = "1 (init)   S\t0  0 0 0 -1 4194304 100 0 0 0    30   120 0 0"
        assertEquals(PerfParseTestAccess.parse(compact), PerfParseTestAccess.parse(loose))
    }

    @Test
    fun `comm 含空格不发生列错位`() {
        // 真实案例形态：进程名带空格（如 "perf sampler"）。若不先按最后一个 ')' 截断，
        // 按空白切分 utime 会向后错位 2 列。
        val line = "1234 (perf sampler) S 1 2 3 4 5 6 7 8 9 10 120 80 0 0 0 0"
        assertEquals((120L + 80L) / 100.0, PerfParseTestAccess.parse(line))
    }

    @Test
    fun `comm 含括号仍以最后一个右括号为界`() {
        val line = "1234 (weird (name)) S 1 2 3 4 5 6 7 8 9 10 50 25 0 0 0 0"
        assertEquals((50L + 25L) / 100.0, PerfParseTestAccess.parse(line))
    }

    @Test
    fun `字段不足或非数字返回 null`() {
        assertNull(PerfParseTestAccess.parse("1234 (x) S 1 2 3"))
        assertNull(PerfParseTestAccess.parse("1234 (x) S a b c d e f g h i j k"))
        assertNull(PerfParseTestAccess.parse(""))
    }

    @Test
    fun `有 utime 但缺 stime 判废（不拿半截数）`() {
        // 切分后只到 index 11（utime），stime 缺失。
        assertNull(PerfParseTestAccess.parse("1234 (x) S 1 2 3 4 5 6 7 8 9 10 120"))
    }

    @Test
    fun `无右括号的畸形行判废`() {
        // 无 ')' → substringAfterLast 缺失值 "" → 列数为 1 → null（不会把 pid 当 utime）。
        assertNull(PerfParseTestAccess.parse("1234 main S 1 2 3 4 5 6 7 8 9 10 120 80 0 0"))
    }

    @Test
    fun `clkTck 非正数判废`() {
        val line = "1234 (x) S 1 2 3 4 5 6 7 8 9 10 120 80 0 0 0 0"
        assertNull(PerfParseTestAccess.parse(line, clkTck = 0))
        assertNull(PerfParseTestAccess.parse(line, clkTck = -1))
    }

    // ── parseProcStatCpuJiffies ──────────────────────────────────────────────

    @Test
    fun `proc_stat 首行 1 加 10 列求和`() {
        // cpu user nice system idle iowait irq softirq steal guest guest_nice = 10 列
        val line = "cpu  100 0 50 900 10 0 5 0 0 0"
        assertEquals(1065L, PerfParseTestAccess.jiffies(line))
    }

    @Test
    fun `proc_stat 列数异常判废（跳过 percent 保留 cpuSeconds 的防御）`() {
        assertNull(PerfParseTestAccess.jiffies("cpu  100 0 50 900 10 0 5 0 0"))
        assertNull(PerfParseTestAccess.jiffies("cpu  100 0 50 900 10 0 5 0 0 0 1 2"))
        assertNull(PerfParseTestAccess.jiffies("cpu0  100 0 50 900 10 0 5 0 0 0"))
        assertNull(PerfParseTestAccess.jiffies("garbage"))
        assertNull(PerfParseTestAccess.jiffies(""))
    }

    @Test
    fun `proc_stat 列数合规但含非数字整行判废`() {
        assertNull(PerfParseTestAccess.jiffies("cpu  100 0 50 900 10 0 5 0 abc 0"))
        assertNull(PerfParseTestAccess.jiffies("cpu  100 0 50 900 10 0 5 0 0 -x"))
    }

    @Test
    fun `proc_stat 前导与中间多重空格等价`() {
        assertEquals(1065L, PerfParseTestAccess.jiffies("cpu    100  0 50 900 10 0 5 0 0 0"))
        assertEquals(1065L, PerfParseTestAccess.jiffies("cpu\t100 0 50 900 10 0 5 0 0 0"))
    }
}

/** 被测对象是 Android 类构造的，纯函数经 companion 直调 —— 拆一层避免构造 Context。 */
private object PerfParseTestAccess {
    fun parse(line: String, clkTck: Long = 100): Double? =
        PerformanceMonitorManager.parseProcSelfStatCpuSeconds(line, clkTck)

    fun jiffies(line: String): Long? = PerformanceMonitorManager.parseProcStatCpuJiffies(line)
}
