package com.rickeal.agent.core.data.perf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [FrameStatsCollector.computeFrameStats] 纯函数行为锁定（W81，审查 P0-1 的 07a 档）。
 *
 * 只测纯函数（不触 Android、不起 Choreographer —— 帧到达由设备决定，JVM 上无从复现）：
 * 帧间隔表 → frames / jankyFrames / windowNanos 的口径与 [FrameStatsCollector.FrameStats]
 * 派生属性（fps / jankyPercent）的防御形态。实例级会话语义（start / stop / 累计）走 W82
 * 新增的构造缝，见 `FrameStatsSessionTest`。
 */
class FrameStatsCollectorTest {

    @Test
    fun `空表返回 null —— 没有帧间隔样本不等于零帧`() {
        assertNull(FrameStatsCollector.computeFrameStats(emptyList()))
    }

    @Test
    fun `单帧 —— 窗口即该帧间隔`() {
        val stats = FrameStatsCollector.computeFrameStats(listOf(16_666_666L))
        assertEquals(1L, stats!!.frames)
        assertEquals(0L, stats.jankyFrames)
        assertEquals(16_666_666L, stats.windowNanos)
    }

    @Test
    fun `全快帧 60fps —— 无掉帧`() {
        // 120 帧 @16.67ms（= 2s 窗口，与滑动窗口容量对齐）。
        val deltas = List(120) { 16_666_667L }
        val stats = FrameStatsCollector.computeFrameStats(deltas)!!
        assertEquals(120L, stats.frames)
        assertEquals(0L, stats.jankyFrames)
        assertEquals(120 * 16_666_667L, stats.windowNanos)
        assertEquals(60f, stats.fps, 0.01f)
        assertEquals(0f, stats.jankyPercent, 0f)
    }

    @Test
    fun `混合快慢帧 —— 掉帧计数与占比按超阈帧算`() {
        // 10 帧：7 快（16.7ms）+ 3 慢（30ms）⇒ 掉帧率 30%。
        val deltas = List(7) { 16_666_667L } + List(3) { 30_000_000L }
        val stats = FrameStatsCollector.computeFrameStats(deltas)!!
        assertEquals(10L, stats.frames)
        assertEquals(3L, stats.jankyFrames)
        assertEquals(30f, stats.jankyPercent, 0.001f)
    }

    @Test
    fun `阈值边界 —— 恰等于 20ms 不算掉帧，超过 1ns 即算`() {
        // 判据是**严格大于** JANK_THRESHOLD_NANOS（20ms）：恰等不算，+1ns 算。
        val atThreshold = FrameStatsCollector.computeFrameStats(listOf(20_000_000L))!!
        assertEquals(0L, atThreshold.jankyFrames, "恰等于阈值不得判掉帧")

        val overThreshold = FrameStatsCollector.computeFrameStats(listOf(20_000_001L))!!
        assertEquals(1L, overThreshold.jankyFrames, "超过阈值 1ns 即判掉帧")
    }

    @Test
    fun `windowNanos 为 0 时 fps 防御为 0 不出 NaN`() {
        // 采集路径会丢弃 delta <= 0，正常到不了这里；防的是将来误喂 0 间隔。
        val zero = FrameStatsCollector.FrameStats(frames = 3, jankyFrames = 0, windowNanos = 0)
        assertEquals(0f, zero.fps, 0f)
        assertTrue(zero.fps.isFinite(), "fps 必须是有限值")
    }

    @Test
    fun `frames 为 0 时 jankyPercent 防御为 0 不出 NaN`() {
        val empty = FrameStatsCollector.FrameStats(frames = 0, jankyFrames = 0, windowNanos = 0)
        assertEquals(0f, empty.jankyPercent, 0f)
        assertTrue(empty.jankyPercent.isFinite(), "jankyPercent 必须是有限值")
    }
}
