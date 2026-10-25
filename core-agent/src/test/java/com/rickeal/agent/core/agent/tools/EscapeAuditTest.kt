package com.rickeal.agent.core.agent.tools

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [EscapeAudit] 的节流窗口判定（W82-04）。
 *
 * 只测**纯函数** [EscapeAudit.withinWindow]：`record()` 会写 `AgentLogStore` 这个
 * 进程级单例，副作用跨用例不可清理，断言它只会把测试耦合到日志实现细节。窗口边界
 * 本身是可穷举的确定性判据 —— 钉住它，就能防住「边界写成 `<=` / `>`」这类一眼看不出的
 * 退化（多算或少算 1ms 都会让「恰好 30s」那一侧的日志行为翻转）。
 */
class EscapeAuditTest {

    @Test
    fun `节流窗口在 30s 边界两侧翻转`() {
        // windowStart=0 表示「从未记过」：真实时钟量级（≈1.7e12）下差值远大于窗口 ⇒ 放行。
        assertFalse(EscapeAudit.withinWindow(nowMillis = 1_700_000_000_000L, windowStartMillis = 0L))
        // 窗口起点之后 1ms / 29_999ms：仍在窗口内 ⇒ 只计数不记日志。
        assertTrue(EscapeAudit.withinWindow(nowMillis = 1L, windowStartMillis = 0L))
        assertTrue(EscapeAudit.withinWindow(nowMillis = 29_999L, windowStartMillis = 0L))
        // 恰好 30_000ms：窗口已结束，必须重新记（边界是左闭右开）。
        assertFalse(EscapeAudit.withinWindow(nowMillis = 30_000L, windowStartMillis = 0L))
        // 真实时间戳量级下同样成立（防「只在小数值上对」）。
        val base = 1_700_000_000_000L
        assertTrue(EscapeAudit.withinWindow(nowMillis = base + 19_999L, windowStartMillis = base))
        assertFalse(EscapeAudit.withinWindow(nowMillis = base + 30_000L, windowStartMillis = base))
    }
}
