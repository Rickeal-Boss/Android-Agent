package com.rickeal.agent.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * 固化「冻结感知超时」不变量（W78-12）。
 *
 * 根因：ColorOS 等国产 OEM 在灭屏约 10s 后做 cgroup 级冻结，线程全停但单调钟照走。
 * 旧 `withTimeout(generationTimeoutMillis)` 的截止时刻由单调钟决定，长锁屏后解冻瞬间
 * 「墙钟流逝 > 预算」会误取消 —— 即「模型思考 / 首字延迟期间锁屏被掐断」。
 *
 * [freezeAwareTimeout] 把冻结时段从计时剔除：看门狗只看 [FreezeClock.runnableElapsedNanos]。
 * 本测试用受控 FakeClock 覆盖三态：
 *  1) 单调钟（无冻结）：超预算 ⇒ 如期超时（不退化成无限等待）；
 *  2) 预算内：正常完成（不误杀）；
 *  3) 冻结视角恒为 0：即便真实墙钟流逝 2s，也不超时（首字延迟 / 思考期间锁屏存活）。
 */
class FreezeAwareTimeoutTest {

    /** 单调钟：runnable 视角 = 真实流逝，无冻结剔除。 */
    private class MonotonicFakeClock : FreezeClock {
        override fun nowNanos(): Long = System.nanoTime()
        override fun runnableElapsedNanos(startedNanos: Long): Long = System.nanoTime() - startedNanos
    }

    /** 冻结钟：runnable 视角恒为 0（进程全程被冻结，不消耗任何预算）。 */
    private class FrozenFakeClock : FreezeClock {
        override fun nowNanos(): Long = System.nanoTime()
        override fun runnableElapsedNanos(startedNanos: Long): Long = 0L
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
        // 首字延迟 / 思考期间锁屏：冻结视角下可运行时长恒为 0，2s 墙钟流逝不应触发超时。
        val result = freezeAwareTimeout(150, FrozenFakeClock()) {
            delay(2000)
            "survived"
        }
        assertEquals("survived", result)
    }
}
