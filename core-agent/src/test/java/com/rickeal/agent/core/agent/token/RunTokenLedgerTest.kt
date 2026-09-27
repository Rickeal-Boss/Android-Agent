package com.rickeal.agent.core.agent.token

import com.rickeal.agent.core.model.TokenUsage
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * run 级 token 账本（[InMemoryRunTokenLedger]）行为锁定（Wave 30 ① RunTokenLedger）。
 *
 * 重点钉住两条接线纪律的实现侧兑现：
 * 1. **镜像口径**：[RunTokenLedger.onSendEstimated] 收「累计总量」覆盖写，不做累加 ——
 *    压缩触发全量重记使累计值回落时，投影必须如实镜像而不是被「只增不减」钳住
 *    （否则账本与 RunState.sentTokens 永久分叉）；
 * 2. **null 跳过**：引擎未回报 usage 时零写入（时间戳也不动）。
 *
 * 并发语义由 MutableStateFlow.update 的 CAS 循环保证，不在 JVM 单测范围（无
 * coroutines-test，与 core-agent 既有测试源集配置一致）。
 */
class RunTokenLedgerTest {

    private fun ledger(clock: () -> Long = { 0L }): InMemoryRunTokenLedger = InMemoryRunTokenLedger(clock)

    // ── 初始状态 ─────────────────────────────────────────────────────────────

    @Test
    fun `初始快照全零`() {
        assertEquals(RunTokenSnapshot(), ledger().snapshot.value)
    }

    @Test
    fun `snapshot 每次访问返回同一个 StateFlow 实例`() {
        val l = ledger()
        val flow: StateFlow<RunTokenSnapshot> = l.snapshot
        assertSame(flow, l.snapshot)
    }

    // ── 发送侧估算（镜像覆盖写） ─────────────────────────────────────────────

    @Test
    fun `onSendEstimated 覆盖写累计总量而非累加`() {
        val l = ledger()
        l.onSendEstimated(100L)
        assertEquals(100L, l.snapshot.value.sentTokens)
        l.onSendEstimated(250L)
        assertEquals(250L, l.snapshot.value.sentTokens)
    }

    @Test
    fun `压缩后全量重记回落时投影如实镜像不被钳住`() {
        // 全量重记（sentTokens = 整个窗口的估算）可能小于此前的增量累计 ——
        // 若实现里做了 max 只增不减，账本会与 sentTokens 永久分叉。
        val l = ledger()
        l.onSendEstimated(500L)
        l.onSendEstimated(120L)
        assertEquals(120L, l.snapshot.value.sentTokens)
    }

    // ── 引擎回报侧（null 跳过 + 累加） ───────────────────────────────────────

    @Test
    fun `onEngineUsage 传 null 时完全跳过`() {
        val l = ledger()
        l.onSendEstimated(100L)
        l.onEngineUsage(null)
        assertEquals(RunTokenSnapshot(sentTokens = 100L), l.snapshot.value)
    }

    @Test
    fun `onEngineUsage 逐轮累加 prompt 与 completion`() {
        val l = ledger()
        l.onEngineUsage(TokenUsage(promptTokens = 100, completionTokens = 20, totalTokens = 120))
        l.onEngineUsage(TokenUsage(promptTokens = 80, completionTokens = 30, totalTokens = 110))
        assertEquals(180L, l.snapshot.value.cumulativeIn)
        assertEquals(50L, l.snapshot.value.cumulativeOut)
        assertEquals(230L, l.snapshot.value.engineTotalTokens)
    }

    // ── 双口径互不干扰 ───────────────────────────────────────────────────────

    @Test
    fun `发送侧估算与引擎回报两个口径互不覆盖`() {
        val l = ledger()
        l.onSendEstimated(1000L)
        l.onEngineUsage(TokenUsage(promptTokens = 900, completionTokens = 100))
        l.onSendEstimated(1100L)
        assertEquals(1100L, l.snapshot.value.sentTokens)
        assertEquals(900L, l.snapshot.value.cumulativeIn)
        assertEquals(100L, l.snapshot.value.cumulativeOut)
    }

    // ── 时间戳 ───────────────────────────────────────────────────────────────

    @Test
    fun `每次回写都刷新 updatedAtElapsedMillis`() {
        var now = 42L
        val l = ledger { now }
        l.onSendEstimated(1L)
        assertEquals(42L, l.snapshot.value.updatedAtElapsedMillis)
        now = 43L
        l.onEngineUsage(TokenUsage(promptTokens = 1))
        assertEquals(43L, l.snapshot.value.updatedAtElapsedMillis)
    }

    @Test
    fun `onEngineUsage 传 null 时不刷新时间戳`() {
        var now = 42L
        val l = ledger { now }
        l.onSendEstimated(1L)
        now = 99L
        l.onEngineUsage(null)
        assertEquals(42L, l.snapshot.value.updatedAtElapsedMillis)
    }
}
