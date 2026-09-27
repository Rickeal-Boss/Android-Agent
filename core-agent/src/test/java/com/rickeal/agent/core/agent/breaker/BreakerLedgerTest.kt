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
        assertEquals(0, ledger.failureStreak("file_read" + "_other"))
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
    fun `elapsedMillisSince 对非负差值给出毫秒`() {
        val now = System.nanoTime()
        assertTrue(elapsedMillisSince(now) >= 0L)
        assertTrue(elapsedMillisSince(now - 5_000_000L) >= 5L)
    }
}
