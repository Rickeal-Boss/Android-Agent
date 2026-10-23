package com.rickeal.agent.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 固化 [FreezeAccumulator] 的跨窗口语义（W79）。
 *
 * 累加逻辑从 `:core-data` 的 `ScreenStateMonitor` 上提到 `:core-agent` 正是为了能在这里
 * JVM 直测：`offAt`/`accum` 的同锁读写、`frozenNanos()` **含在途灭屏窗口**、以及
 * 「基线落在窗口内 ⇒ 增量不含起点前那段」——这三条是唯一有竞态/跨窗口语义的地方。
 *
 * `nowNanos` 注入 ⇒ 全程无真实等待（毫秒级断言，不受 CI 负载抖动影响）。
 */
class FreezeAccumulatorTest {

    /** 可注入、可推进的假单调钟。 */
    private class FakeNow(var nanos: Long = 1_000_000_000L) {
        fun advanceMs(ms: Long) { nanos += ms * 1_000_000L }
    }

    @Test
    fun offWindowIsAccumulatedExactlyOnce() {
        val now = FakeNow()
        val acc = FreezeAccumulator { now.nanos }

        assertEquals(0L, acc.frozenNanos(), "亮屏无窗口时累计为 0")

        acc.onScreenOff()
        now.advanceMs(200)
        assertEquals(200_000_000L, acc.frozenNanos(), "灭屏期间 live 读应含在途窗口")
        // W80-05：onScreenOn 返回 (delta, total)，total 必须与同状态的 frozenNanos() 读数一致。
        val (delta, total) = acc.onScreenOn()
        assertEquals(200_000_000L, delta, "delta 为本次窗口的纳秒数")
        assertEquals(200_000_000L, total, "total 为含在途窗口语义的绝对累计")
        assertEquals(200_000_000L, acc.frozenNanos(), "亮屏瞬间数值必须连续（不跳变、不丢段）")
        assertEquals(total, acc.frozenNanos(), "total 与亮屏后的 frozenNanos() 读数必须一致")

        now.advanceMs(500)
        assertEquals(200_000_000L, acc.frozenNanos(), "亮屏后继续流逝不再累加")
    }

    @Test
    fun baselineInsideOffWindowExcludesEarlierSegment() {
        val now = FakeNow()
        val acc = FreezeAccumulator { now.nanos }

        acc.onScreenOff()
        now.advanceMs(100)
        // 起点落在灭屏窗口内：基线已含起点前那 100ms。
        val baseline = acc.frozenNanos()
        assertEquals(100_000_000L, baseline)

        now.advanceMs(100)
        val (delta, total) = acc.onScreenOn()
        // 窗口起点在最早那次 onScreenOff（t=1.0s），亮屏在 t=1.2s ⇒ 整段 delta = 200ms；
        // 基线（t=1.1s 取）只覆盖前 100ms，增量语义由下面的 frozen-baseline 断言钉住。
        assertEquals(200_000_000L, delta, "delta 为整段灭屏窗口的纳秒数")
        assertEquals(200_000_000L, total, "total 为含在途窗口语义的绝对累计")
        // 增量恰 = 起点之后那段 100ms，**不含**起点前的 100ms。
        assertEquals(100_000_000L, acc.frozenNanos() - baseline)
        assertEquals(acc.frozenNanos(), total, "total 与亮屏后的 frozenNanos() 读数必须一致")
    }

    @Test
    fun frozenStrictlyIncreasesWhileScreenStaysOff() {
        val now = FakeNow()
        val acc = FreezeAccumulator { now.nanos }

        acc.onScreenOff()
        val first = acc.frozenNanos()
        now.advanceMs(50)
        val second = acc.frozenNanos()
        now.advanceMs(50)
        val third = acc.frozenNanos()

        assertTrue(first < second, "未亮屏期间第一次到第二次读必须严格递增")
        assertTrue(second < third, "未亮屏期间第二次到第三次读必须严格递增")
    }
}
