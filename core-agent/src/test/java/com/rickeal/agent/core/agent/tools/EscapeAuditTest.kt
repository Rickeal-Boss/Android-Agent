package com.rickeal.agent.core.agent.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [EscapeAudit] 的节流窗口判定（W82-04）与分键节流行为（W83-04）。
 *
 * 窗口边界只测**纯函数** [EscapeAudit.withinWindow]：它是可穷举的确定性判据 —— 钉住
 * 它，就能防住「边界写成 `<=` / `>`」这类一眼看不出的退化（多算或少算 1ms 都会让
 * 「恰好 30s」那一侧的日志行为翻转）。
 *
 * 分键行为（record 的放行 / 抑制 / 省略计数）经 record 的**时钟与输出双缝**验证：
 * 默认值即生产路径（`System.currentTimeMillis()` + `AgentLogStore.error`），单测传
 * 固定 nowMillis 与收集器 —— 仍然不碰、不断言 `AgentLogStore` 这个进程级单例，
 * 只是把观察点从「日志实现细节」换成「本对象自己的节流决策」。各用例用互不重复的
 * 键与固定时钟，彼此隔离（object 级状态不构成顺序依赖）。
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

    // ── 分键节流（W83-04）────────────────────────────────────────────────────
    // record 的双缝：nowMillis 固定时钟、sink 收集器（默认值即生产路径，不碰
    // AgentLogStore）。以下各用例键互不重复 + 固定时钟 ⇒ 彼此完全隔离。

    @Test
    fun `不同工具与原因的组合互不抑制`() {
        val t0 = 1_700_000_000_000L
        val lines = mutableListOf<String>()
        // 同一时刻连记两个不同组合：旧全局窗口下第二条会被吞成「已省略」。
        EscapeAudit.record("file_read", "(empty)", "空路径", nowMillis = t0) { lines.add(it) }
        EscapeAudit.record("file_list", "/etc", "绝对路径", nowMillis = t0) { lines.add(it) }
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("工具=file_read"))
        assertTrue(lines[0].contains("原因=空路径"))
        assertTrue(lines[1].contains("工具=file_list"))
        assertTrue(lines[1].contains("原因=绝对路径"))
        // 两条都是各自键的首记，不带省略尾。
        assertFalse(lines[0].contains("已省略"))
        assertFalse(lines[1].contains("已省略"))
    }

    @Test
    fun `同一组合 30s 内仍只记一条且省略计数可累计`() {
        val t0 = 1_700_000_000_000L
        val lines = mutableListOf<String>()
        // 窗口内连记 3 次（t0 / +1s / +2s）：只放行第一条，其余计省略。
        listOf(0L, 1_000L, 2_000L).forEach { delta ->
            EscapeAudit.record("file_write", "docs/" + "a".repeat(300), "路径过长", nowMillis = t0 + delta) {
                lines.add(it)
            }
        }
        assertEquals(1, lines.size)
        // 恰好 30s：窗口结束，重新记，并带上本窗口被省略的 2 次。
        EscapeAudit.record("file_write", "docs/" + "a".repeat(300), "路径过长", nowMillis = t0 + 30_000L) {
            lines.add(it)
        }
        assertEquals(2, lines.size)
        assertTrue(lines[1].contains("另有 2 次已省略"), lines[1])
    }

    @Test
    fun `省略计数按组合键各自独立`() {
        val t0 = 1_700_000_000_000L
        val lines = mutableListOf<String>()
        // 两个键各自首记。
        EscapeAudit.record("file_read", "link", "符号链接逃逸", nowMillis = t0) { lines.add(it) }
        EscapeAudit.record("file_list", "../etc", "含 ..", nowMillis = t0) { lines.add(it) }
        // A 键窗口内再被拒 2 次；B 键 1 次 —— 两边都只计数不记日志。
        repeat(2) {
            EscapeAudit.record("file_read", "link", "符号链接逃逸", nowMillis = t0 + 1_000L) { lines.add(it) }
        }
        EscapeAudit.record("file_list", "../etc", "含 ..", nowMillis = t0 + 1_000L) { lines.add(it) }
        assertEquals(2, lines.size, "窗口内两个键各只记了首条")
        // 窗口后各自重记：省略数互不传染（A=2、B=1）。
        EscapeAudit.record("file_read", "link", "符号链接逃逸", nowMillis = t0 + 30_000L) { lines.add(it) }
        EscapeAudit.record("file_list", "../etc", "含 ..", nowMillis = t0 + 30_000L) { lines.add(it) }
        assertEquals(4, lines.size)
        assertTrue(lines[2].contains("另有 2 次已省略"), lines[2])
        assertTrue(lines[3].contains("另有 1 次已省略"), lines[3])
    }

    @Test
    fun `键数超过上限后淘汰最旧插入的键`() {
        val t0 = 1_700_000_000_000L
        val lines = mutableListOf<String>()
        // 用编号 reason 构造互异键（正常键数 = 工具数 × reason 种类数 ≈ 15），
        // 连续插 64 个后再插第 65 个 ⇒ 越过 MAX_KEYS=64，淘汰最旧的 k0。
        repeat(64) { i ->
            EscapeAudit.record("file_read", "p$i", "k$i", nowMillis = t0) { lines.add(it) }
        }
        assertEquals(64, lines.size)
        EscapeAudit.record("file_read", "p64", "k64", nowMillis = t0) { lines.add(it) }
        assertEquals(65, lines.size)
        // 未被淘汰的 k1：同一时刻再记仍被抑制（淘汰不影响其余键的防刷环）。
        EscapeAudit.record("file_read", "p1", "k1", nowMillis = t0) { lines.add(it) }
        assertEquals(65, lines.size)
        // 已被淘汰的 k0：状态丢失，同一时刻重新放行（计数一并丢 ⇒ 无省略尾）。
        EscapeAudit.record("file_read", "p0", "k0", nowMillis = t0) { lines.add(it) }
        assertEquals(66, lines.size)
        assertFalse(lines.last().contains("已省略"), lines.last())
    }
}
