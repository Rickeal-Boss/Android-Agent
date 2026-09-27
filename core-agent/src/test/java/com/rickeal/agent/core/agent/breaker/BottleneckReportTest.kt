package com.rickeal.agent.core.agent.breaker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 诊断卡装配与卡点归因（[buildBottleneckReport] / [resolveBlocker] / [BottleneckReport.render]）
 * 行为锁定（Wave 30 §2.5）。
 *
 * 优先级 if 链是本测试的主体：归因错了用户会看到南辕北辙的建议。
 * 文案 = 编译期常量 + 占位符渲染，禁止模型参与 —— 渲染结果的确定性在这里钉死。
 */
class BottleneckReportTest {

    private fun ledgerWith(vararg kinds: BreakerKind): BreakerLedger {
        val ledger = BreakerLedger()
        kinds.forEachIndexed { i, kind -> ledger.trip(kind, i + 1, evidence = "${kind.name} 证据") }
        return ledger
    }

    // ── resolveBlocker 优先级链 ──────────────────────────────────────────────

    @Test
    fun `DenialCircuit trip 最高优先归因 PermissionDenied`() {
        // 同时存在预算族与退化族 trip，DenialCircuit 仍压过它们。
        val b = resolveBlocker(
            ledgerWith(BreakerKind.WallClockBudget, BreakerKind.DenialCircuit).trips,
            engineCause = null,
            lastToolError = null,
        )
        assertEquals(Blocker.PermissionDenied, b)
    }

    @Test
    fun `未注册工具线索归因 ToolUnavailable`() {
        val b = resolveBlocker(
            emptyList(),
            engineCause = null,
            lastToolError = "未注册的工具：file_read",
        )
        assertEquals(Blocker.ToolUnavailable, b)
    }

    @Test
    fun `退化族 trip 归因 ModelDegraded`() {
        for (kind in listOf(
            BreakerKind.EmptyOutput, BreakerKind.StreamLoop, BreakerKind.PromptEcho,
            BreakerKind.SameParamDeadlock, BreakerKind.ToolCallOscillation,
        )) {
            assertEquals(
                Blocker.ModelDegraded,
                resolveBlocker(ledgerWith(kind).trips, null, null),
                "kind=$kind 应归因 ModelDegraded",
            )
        }
    }

    @Test
    fun `预算族 trip 归因 BudgetExhausted`() {
        for (kind in listOf(
            BreakerKind.WallClockBudget, BreakerKind.TokenBudget,
            BreakerKind.RoundBudget, BreakerKind.ThermalThrottle,
        )) {
            assertEquals(
                Blocker.BudgetExhausted,
                resolveBlocker(ledgerWith(kind).trips, null, null),
                "kind=$kind 应归因 BudgetExhausted",
            )
        }
    }

    @Test
    fun `无 trip 但有引擎异常归因 EngineFailure`() {
        assertEquals(
            Blocker.EngineFailure,
            resolveBlocker(emptyList(), engineCause = RuntimeException("load failed"), lastToolError = null),
        )
    }

    @Test
    fun `兜底归因 MissingInput`() {
        assertEquals(
            Blocker.MissingInput,
            resolveBlocker(emptyList(), engineCause = null, lastToolError = null),
        )
        assertEquals(
            Blocker.MissingInput,
            resolveBlocker(emptyList(), engineCause = null, lastToolError = "工具执行超时（1000ms）"),
        )
    }

    @Test
    fun `优先级序 —— 退化族压过预算族与引擎异常`() {
        val b = resolveBlocker(
            ledgerWith(BreakerKind.WallClockBudget, BreakerKind.StreamLoop).trips,
            engineCause = RuntimeException("x"),
            lastToolError = null,
        )
        assertEquals(Blocker.ModelDegraded, b)
    }

    // ── buildBottleneckReport 装配 ───────────────────────────────────────────

    @Test
    fun `装配取账本全量 trip 与尝试聚合`() {
        val ledger = ledgerWith(BreakerKind.ToolFailureStreak)
        ledger.recordAttempt("file_read", "d1", ok = false, error = "boom", elapsedMillis = 5L)

        val report = buildBottleneckReport(
            task = "整理文档",
            rounds = 4,
            elapsedMillis = 310_000L,
            ledger = ledger,
            registeredToolNames = setOf("file_read", "file_write"),
        )

        assertEquals("整理文档", report.task)
        assertEquals(4, report.rounds)
        assertEquals(1, report.tripped.size)
        assertEquals(1, report.triedTools.size)
        assertEquals(Blocker.ModelDegraded, report.blocker)
        // ModelDegraded 模板是纯常量文案（无占位符）；{elapsed} 渲染由下方
        // BudgetExhausted 用例覆盖。断言用实现模板的真实特征词。
        assertTrue(report.suggestions.single().contains("模型输出退化"))
    }

    @Test
    fun `BudgetExhausted 模板渲染轮次与耗时占位符`() {
        val report = buildBottleneckReport(
            task = "t",
            rounds = 8,
            elapsedMillis = 300_500L,
            ledger = ledgerWith(BreakerKind.WallClockBudget),
            registeredToolNames = emptySet(),
        )
        val s = report.suggestions.single()
        assertTrue(s.contains("8 轮"), "应含轮次占位：$s")
        assertTrue(s.contains("300 秒"), "应含耗时占位：$s")
    }

    @Test
    fun `ToolUnavailable 模板渲染可用工具清单`() {
        // 直接触发 ToolUnavailable 的线索路径：lastToolError 由账本失败记录供给
        // —— 这里用带线索前缀的失败 attempt 模拟。
        val ledger = BreakerLedger()
        ledger.recordAttempt("x", "d", ok = false, error = "不能直接调用工具「y」", elapsedMillis = 1L)
        val report = buildBottleneckReport(
            task = "t",
            rounds = 1,
            elapsedMillis = 1_000L,
            ledger = ledger,
            registeredToolNames = setOf("search_tools", "call_tool"),
        )
        assertEquals(Blocker.ToolUnavailable, report.blocker)
        assertTrue(report.suggestions.single().contains("search_tools"), report.suggestions.single())
    }

    @Test
    fun `MissingInput 模板渲染最近失败摘要且截断到 80 字符`() {
        val longError = "错".repeat(200)
        val ledger = BreakerLedger()
        ledger.recordAttempt("file_read", "d", ok = false, error = longError, elapsedMillis = 1L)
        val report = buildBottleneckReport("t", 1, 1_000L, ledger, emptySet())
        assertTrue(report.suggestions.single().contains("错".repeat(80)))
        assertTrue(!report.suggestions.single().contains("错".repeat(81)))
    }

    // ── render 文本渲染 ──────────────────────────────────────────────────────

    @Test
    fun `render 输出分节文本且含证据行`() {
        val ledger = ledgerWith(BreakerKind.ToolFailureStreak)
        ledger.recordAttempt("file_read", "d", ok = false, error = "boom", elapsedMillis = 5L)
        val text = buildBottleneckReport("整理文档", 3, 65_000L, ledger, setOf("file_read")).render()
        assertTrue(text.startsWith("【任务诊断】"))
        assertTrue(text.contains("任务：整理文档"))
        assertTrue(text.contains("轮次：3｜耗时：65 秒"))
        assertTrue(text.contains("file_read ×1（成功 0）"))
        assertTrue(text.contains("- [ToolFailureStreak] ToolFailureStreak 证据"))
        assertTrue(text.contains("卡点："))
        assertTrue(text.contains("建议："))
    }

    @Test
    fun `render 对空账本不崩且标注无记录`() {
        val text = buildBottleneckReport("t", 0, 0L, BreakerLedger(), emptySet()).render()
        assertTrue(text.contains("（无工具调用记录）"))
        assertTrue(text.contains("（无）"))
    }
}
