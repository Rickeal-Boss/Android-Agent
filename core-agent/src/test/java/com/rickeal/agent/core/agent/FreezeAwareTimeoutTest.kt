package com.rickeal.agent.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * 固化「冻结感知超时」不变量（W78-12；W79 补齐跨 run 污染口径）。
 *
 * 根因：ColorOS 等国产 OEM 在灭屏约 10s 后做 cgroup 级冻结，线程全停但单调钟照走。
 * 旧 `withTimeout(generationTimeoutMillis)` 的截止时刻由单调钟决定，长锁屏后解冻瞬间
 * 「墙钟流逝 > 预算」会误取消 —— 即「模型思考 / 首字延迟期间锁屏被掐断」。
 *
 * [freezeAwareTimeout] 把冻结时段从计时剔除：看门狗只看
 * [FreezeClock.runnableNanosSince]（= 墙钟流逝 − **本次 run** 的 frozen 增量）。
 *
 * 覆盖六态：
 *  1) 单调钟（无冻结）：超预算 ⇒ 如期超时（不退化成无限等待）；
 *  2) 预算内：正常完成（不误杀）；
 *  3) 全程冻结：frozen 跟随墙钟 ⇒ 可运行时长恒 ~0，不超时（首字延迟 / 思考期锁屏存活）；
 *  4) 🔴 历史累计 416s：frozen 恒定在 416s ⇒ 起点基线也是 416s ⇒ **本轮预算完整**，
 *     墙钟超预算照常超时（**旧实现此例不会超时 = 本 P1 的红转绿锚**）；
 *  5) 本轮内新增灭屏：frozen 从构造后 100ms 起增长 ⇒ 预算顺延（runnable 饱和 ~100ms）；
 *  6) 顺延不是无限豁免：同 5 的 fake 但预算更小 ⇒ 仍超时。
 */
class FreezeAwareTimeoutTest {

    /** 单调钟：无冻结，runnable 视角 = 真实流逝。 */
    private class MonotonicFakeClock : FreezeClock {
        override fun nowNanos(): Long = System.nanoTime()
    }

    /** 全程冻结：frozen 跟随真实流逝（自构造起），故 runnable ≈ 0，不消耗任何预算。 */
    private class FrozenFakeClock : FreezeClock {
        private val born = System.nanoTime()
        override fun nowNanos(): Long = System.nanoTime()
        override fun frozenNanos(): Long = System.nanoTime() - born
    }

    /** 历史累计冻结：frozen 恒定在 [frozenNanos]（起点前的灭屏，永不增长）。 */
    private class HistoricalFrozenFakeClock(private val frozenNanos: Long) : FreezeClock {
        override fun nowNanos(): Long = System.nanoTime()
        override fun frozenNanos(): Long = frozenNanos
    }

    /**
     * 本轮内才发生的灭屏：以**首次 [frozenNanos] 调用**（= run 起点取基线那一刻）为锚，
     * frozen = `max(0, 自锚流逝 − 200ms)` ⇒ 前 200ms 正常计时，之后 frozen 与墙钟同速增长
     * ⇒ runnable = `min(自锚流逝, 200ms)`，饱和在 200ms（与构造→启动间隔无关，不受 CI 抖动影响）。
     * 饱和值取 200ms 而非 100ms：看门狗 tick = 250ms，留 ≥100ms 余量，避免慢 CI 上边界抖动。
     */
    private class MidRunFreezeFakeClock : FreezeClock {
        private var anchorNanos = -1L
        override fun nowNanos(): Long = System.nanoTime()
        override fun frozenNanos(): Long {
            val now = System.nanoTime()
            if (anchorNanos < 0) anchorNanos = now
            return (now - anchorNanos - 200_000_000L).coerceAtLeast(0L)
        }
    }

    @Test
    fun monotonicClockFiresWhenBudgetExceeded(): Unit = runBlocking {
        assertFailsWith<GenerationTimeoutCancelledException> {
            freezeAwareTimeout(150, MonotonicFakeClock()) {
                delay(2000)
            }
        }
    }

    @Test
    fun completesWhenWithinBudget(): Unit = runBlocking {
        val result = freezeAwareTimeout(5000, MonotonicFakeClock()) {
            delay(100)
            "ok"
        }
        assertEquals("ok", result)
    }

    @Test
    fun frozenIntervalDoesNotConsumeBudget(): Unit = runBlocking {
        // 首字延迟 / 思考期间锁屏：frozen 跟随墙钟增长 ⇒ 可运行时长恒 ~0，2s 墙钟流逝不触发超时。
        val result = freezeAwareTimeout(150, FrozenFakeClock()) {
            delay(2000)
            "survived"
        }
        assertEquals("survived", result)
    }

    @Test
    fun historicalFreezeDoesNotConsumeThisRunBudget(): Unit = runBlocking {
        // 起点前已累计冻结 416s：基线同取 416s ⇒ 增量从 0 起算，本轮 300ms 预算完整。
        // 旧实现（单参 runnableElapsedNanos 减全量 accum）此例不会超时 ⇒ 红转绿锚点。
        // 预算取 300ms（> tick 250ms）⇒ 第 2 个 tick 必触发，不依赖第 1 个 tick 的余量。
        assertFailsWith<GenerationTimeoutCancelledException> {
            freezeAwareTimeout(300, HistoricalFrozenFakeClock(416_000_000_000L)) {
                delay(2000)
            }
        }
    }

    @Test
    fun midRunFreezeExtendsBudget(): Unit = runBlocking {
        // 本轮内才开始灭屏：前 200ms 计入，其后冻结顺延 ⇒ runnable 饱和 200ms < 300ms ⇒ 正常返回。
        val result = freezeAwareTimeout(300, MidRunFreezeFakeClock()) {
            delay(600)
            "survived"
        }
        assertEquals("survived", result)
    }

    @Test
    fun midRunFreezeIsNotUnlimitedExemption(): Unit = runBlocking {
        // 同 fake 但预算 80ms < 饱和值 200ms ⇒ 仍然超时（顺延不是无限豁免）。
        assertFailsWith<GenerationTimeoutCancelledException> {
            freezeAwareTimeout(80, MidRunFreezeFakeClock()) {
                delay(600)
            }
        }
    }
}
