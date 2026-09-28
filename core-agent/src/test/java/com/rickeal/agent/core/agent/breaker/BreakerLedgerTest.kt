package com.rickeal.agent.core.agent.breaker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 断路器账本（[BreakerLedger]）行为锁定（Wave 30 §2.4）。
 *
 * 重点钉住 §3.2(d) 的口径：**失败连击只针对执行失败**（recordAttempt 只收真的跑过的
 * 调用），成功即清零；trip 只记账不做中断决策（中断是调用点控制流的事）。
 */
class BreakerLedgerTest {

    private fun recordOk(ledger: BreakerLedger, tool: String = "file_read") =
        ledger.recordAttempt(tool, "d-$tool-1", ok = true, error = null, elapsedMillis = 10L)

    private fun recordFail(ledger: BreakerLedger, tool: String = "file_read", error: String = "boom") =
        ledger.recordAttempt(tool, "d-$tool-1", ok = false, error = error, elapsedMillis = 20L)

    // ── trip 记账 ────────────────────────────────────────────────────────────

    @Test
    fun `trip 只入账并返回该条记录`() {
        val ledger = BreakerLedger()
        val t = ledger.trip(BreakerKind.ToolFailureStreak, 3, tool = "file_read", evidence = "e1")
        assertEquals(listOf(t), ledger.trips)
        assertEquals(BreakerKind.ToolFailureStreak, t.kind)
        assertEquals(3, t.round)
        assertEquals("file_read", t.tool)
        assertEquals(0L, t.atElapsedMillis)
    }

    @Test
    fun `trip 可重复登记同 kind —— 墙钟 SOFT 与 HARD 是两条 evidence`() {
        val ledger = BreakerLedger()
        ledger.trip(BreakerKind.WallClockBudget, 2, evidence = "软预算")
        ledger.trip(BreakerKind.WallClockBudget, 4, evidence = "硬预算")
        assertEquals(2, ledger.trips.size)
    }

    // ── firstHard ────────────────────────────────────────────────────────────

    @Test
    fun `纯 SOFT 账本 firstHard 返回 null`() {
        val ledger = BreakerLedger()
        ledger.trip(BreakerKind.TokenBudget, 1, evidence = "soft")
        assertNull(ledger.firstHard())
    }

    @Test
    fun `firstHard 取第一条 HARD 触发`() {
        val ledger = BreakerLedger()
        ledger.trip(BreakerKind.TokenBudget, 1, evidence = "soft")
        val hard = ledger.trip(BreakerKind.ToolFailureStreak, 2, evidence = "hard")
        ledger.trip(BreakerKind.ToolCallOscillation, 3, evidence = "hard2")
        assertEquals(hard, ledger.firstHard())
    }

    // ── recordAttempt 与失败连击 ─────────────────────────────────────────────

    @Test
    fun `失败连击逐次累加`() {
        val ledger = BreakerLedger()
        recordFail(ledger)
        recordFail(ledger)
        recordFail(ledger)
        assertEquals(3, ledger.failureStreak("file_read"))
    }

    @Test
    fun `成功即清零该工具的连击`() {
        val ledger = BreakerLedger()
        recordFail(ledger)
        recordFail(ledger)
        recordOk(ledger)
        assertEquals(0, ledger.failureStreak("file_read"))
    }

    @Test
    fun `连击按工具隔离`() {
        val ledger = BreakerLedger()
        recordFail(ledger, tool = "file_read")
        recordFail(ledger, tool = "file_write")
        recordFail(ledger, tool = "file_read")
        assertEquals(2, ledger.failureStreak("file_read"))
        assertEquals(1, ledger.failureStreak("file_write"))
        assertEquals(0, ledger.failureStreak("never_called"), "未入账的工具连击恒为 0")
    }

    @Test
    fun `attempt 账按工具聚合且 lastError 取最近一次失败`() {
        val ledger = BreakerLedger()
        ledger.recordAttempt("file_read", "d1", ok = false, error = "旧错误", elapsedMillis = 1L)
        recordOk(ledger)
        ledger.recordAttempt("file_read", "d1", ok = false, error = "新错误", elapsedMillis = 2L)
        recordOk(ledger, tool = "file_write")

        val summary = ledger.attemptSummary()
        assertEquals(2, summary.size)
        val read = summary.first { it.tool == "file_read" }
        assertEquals(3, read.calls)
        assertEquals(1, read.successes)
        assertEquals("新错误", read.lastError)
    }

    @Test
    fun `lastFailureError 取最后一次失败 attempt 的错误`() {
        val ledger = BreakerLedger()
        assertNull(ledger.lastFailureError())
        recordFail(ledger, error = "第一次")
        recordOk(ledger)
        recordFail(ledger, error = "第二次")
        assertEquals("第二次", ledger.lastFailureError())
    }

    // ── 墙钟换算纯函数 ───────────────────────────────────────────────────────

    @Test
    fun `elapsedMillisSince 把 nanoTime 偏移换算成毫秒`() {
        val now = System.nanoTime()
        val millis = elapsedMillisSince(now - 5_000_000L)
        // 下界只是单调性的必然结论；真正咬住换算系数的是**上界**：
        // 除数写成 1_000（微秒）会得到 5000，写成 1_000_000_000（秒）会得到 0 ——
        // 只断言 >= 5 的话，这两种错数都会静默通过。
        assertTrue(millis >= 5L, "偏移 5ms 应至少换算 5ms，实际 $millis")
        assertTrue(millis < 5_000L, "换算系数错误：5ms 被放大成 $millis")
        // 未来刻度（不应出现）不能给出正数。偏移放大到 5 秒：CI runner 的
        // nanoTime 采样间隔可能被调度拉到毫秒级，5ms 偏移会被抖动吃掉导致假失败
        //（实测 d79cb1f 上发生过）；5s 偏移对亚毫秒采样误差免疫。
        assertTrue(elapsedMillisSince(now + 5_000_000_000L) <= 0L)
    }

    // ── 空账本与快照纪律 ──────────────────────────────────────────────────────

    @Test
    fun `空账本 firstHard 与 lastFailureError 均为 null 且 attemptSummary 为空`() {
        val ledger = BreakerLedger()
        assertNull(ledger.firstHard())
        assertNull(ledger.lastFailureError())
        assertEquals(emptyList<ToolAttemptSummary>(), ledger.attemptSummary())
    }

    @Test
    fun `trips 取出来是快照 —— 后续 trip 不污染已取出的列表`() {
        val ledger = BreakerLedger()
        ledger.trip(BreakerKind.TokenBudget, 1, evidence = "soft")
        val snapshot = ledger.trips
        ledger.trip(BreakerKind.ToolFailureStreak, 2, evidence = "hard")
        assertEquals(1, snapshot.size, "trips 必须是防御拷贝，不能把内部可变列表泄出去")
        assertEquals(2, ledger.trips.size)
    }

    @Test
    fun `全部成功的 attempt 不产生 lastError 且 successes 等于 calls`() {
        val ledger = BreakerLedger()
        recordOk(ledger)
        recordOk(ledger, tool = "file_write")
        assertNull(ledger.lastFailureError(), "没有失败就没有失败摘要")
        val summary = ledger.attemptSummary()
        assertEquals(2, summary.size)
        assertTrue(summary.all { it.lastError == null }, "全成功账不应带 lastError")
        assertTrue(summary.all { it.successes == it.calls }, "全成功账 successes 应等于 calls")
    }
}
