package com.rickeal.agent.core.data.perf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [FrameStatsSession] 会话累计行为锁定（W82）。
 *
 * 怎么做到 JVM 可测：采集器有两个**构造缝**（`postToMain` / `elapsedMillis` 参数，默认值即
 * 生产路径）。这里传空投递 + 假时钟 ⇒ start / stop 只翻转状态，不起真 Choreographer；帧入账
 * 走 `onFrameDelta`（doFrame 与单测共用的**同一条**路径）—— 断言的是生产代码的真实语义，
 * 不是另写一份影子逻辑。
 *
 * 为什么不测真 vsync：帧到达由设备决定，JVM 上无从复现；判据（计数 / 阈值 / 防御形态）已由
 * 这些确定性用例覆盖。
 */
class FrameStatsSessionTest {

    /** 采集器替身：投递块丢弃、时钟固定为 [now] 毫秒。 */
    private fun collector(now: Long = 1_000L): FrameStatsCollector = FrameStatsCollector(
        isDebuggable = true,
        // 丢弃投递块：不触 Choreographer / Looper（JVM 单测没有 Android 桩）。
        postToMain = { },
        elapsedMillis = { now },
    )

    @Test
    fun `会话累计帧数与掉帧数正确`() {
        val c = collector()
        c.start()
        // 7 帧快（16.67ms）+ 3 帧慢（30ms > 20ms 阈值）⇒ 10 帧 / 3 掉帧。
        var now = 300_000_000L // 首帧即越过 250ms 发布窗 ⇒ 立刻发布一次
        repeat(7) {
            c.onFrameDelta(16_666_667L, now)
            now += 16_666_667L
        }
        repeat(2) {
            c.onFrameDelta(30_000_000L, now)
            now += 30_000_000L
        }
        // 末帧前先空转一段（无帧 ⇒ vsync 时间戳跳远），使末帧落在发布窗之外：这样发布出的
        // 快照一定包含全部 10 帧，断言不依赖「恰好落在哪个节流点」。
        now += 300_000_000L
        c.onFrameDelta(30_000_000L, now)

        val s = c.session.value
        assertTrue(s != null, "会话快照必须已发布")
        assertEquals(10L, s?.totalFrames)
        assertEquals(3L, s?.totalJankyFrames)
        assertEquals(7 * 16_666_667L + 3 * 30_000_000L, s?.sessionNanos)
    }

    @Test
    fun `stop 后会话非空且数值保留`() {
        val c = collector()
        c.start()
        c.onFrameDelta(16_666_667L, 300_000_000L)
        c.stop()
        // 窗口清空（「未采集」语义），会话保留 —— 用户停下后还要能读这次的总数。
        assertNull(c.stats.value, "stop 后窗口快照应回 null")
        assertTrue(c.session.value != null, "stop 不得清空会话")
        assertEquals(1L, c.session.value?.totalFrames)
        assertEquals(16_666_667L, c.session.value?.sessionNanos)
        assertEquals(1_000L, c.session.value?.startedAtElapsedMillis)
        assertFalse(c.active.value, "stop 后采集态应为 false")
    }

    @Test
    fun `start 把会话重置为 0`() {
        val c = collector(now = 7_000L)
        c.start()
        c.onFrameDelta(30_000_000L, 300_000_000L)
        assertEquals(1L, c.session.value?.totalFrames)
        // stop → start = 新一次采集 = 新会话（start 本身幂等：running 时直接 return）。
        c.stop()
        c.start()
        assertEquals(0L, c.session.value?.totalFrames)
        assertEquals(0L, c.session.value?.totalJankyFrames)
        assertEquals(0L, c.session.value?.sessionNanos)
        assertEquals(7_000L, c.session.value?.startedAtElapsedMillis)
        assertTrue(c.active.value, "start 后采集态应为 true")
    }

    @Test
    fun `totalFrames 为 0 时会话掉帧率与 fps 防御为 0`() {
        val empty = FrameStatsSession(
            totalFrames = 0L,
            totalJankyFrames = 0L,
            sessionNanos = 0L,
            startedAtElapsedMillis = 1_000L,
        )
        assertEquals(0f, empty.jankyPercent, 0f)
        assertEquals(0f, empty.fps, 0f)
        assertTrue(empty.jankyPercent.isFinite() && empty.fps.isFinite(), "不得出现 NaN 或 Inf")
    }

    @Test
    fun `会话 fps 按帧覆盖时长折算`() {
        // 240 帧 / 4s = 60fps；24 掉帧 ⇒ 10%。不设上限：帧数用 Long，翻倍后口径不变。
        val s = FrameStatsSession(
            totalFrames = 240L,
            totalJankyFrames = 24L,
            sessionNanos = 4_000_000_000L,
            startedAtElapsedMillis = 0L,
        )
        assertEquals(60f, s.fps, 0.01f)
        assertEquals(10f, s.jankyPercent, 0.001f)
    }
}
