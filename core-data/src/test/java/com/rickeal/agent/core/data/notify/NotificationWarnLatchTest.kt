package com.rickeal.agent.core.data.notify

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [NotificationWarnLatch] 的三条语义钉（Wave 36 E1）。
 *
 * 为什么能跑在 JVM 上：[NotificationWarnLatch] 是**零 Android 依赖**的纯 Kotlin（只有一个
 * `Int` 位掩码 + `@Synchronized`），本仓无 Robolectric / mockk，这是唯一能直接单测的缝隙。
 *
 * 钉住的是「按原因分闩」这一修复本质 —— 三条降级出口（缺权限 / 通知被关 / `notify` 抛异常）
 * 互不隶属，任一条被观测到都不该遮蔽另外两条：
 * 1. 同一 reason 重复调用只放行一次（防 onTick 每秒一次刷屏）；
 * 2. 三个 reason 互不干扰、各放行一次（合计 3）—— 单布尔旧实现会在此恒得 1；
 * 3. 并发下每个 reason 恰好放行一次（`@Synchronized` 的 check-then-set 不得有竞态窗口）。
 */
class NotificationWarnLatchTest {

    @Test
    fun `同一 reason 重复调用只放行一次`() {
        val latch = NotificationWarnLatch()
        assertTrue(latch.shouldWarn(NotifyWarnReason.PERMISSION))
        assertFalse(latch.shouldWarn(NotifyWarnReason.PERMISSION))
        assertFalse(latch.shouldWarn(NotifyWarnReason.PERMISSION))
    }

    @Test
    fun `三个 reason 互不干扰，各放行一次合计 3 次`() {
        val latch = NotificationWarnLatch()
        // 逐一轮询，验证「任一条先行不遮蔽后续」；单布尔旧实现会在这里得到 1（首条置闩后全拒）。
        var granted = 0
        for (reason in NotifyWarnReason.values()) {
            if (latch.shouldWarn(reason)) granted++
        }
        assertEquals(3, granted)
        // 第二轮全部拒绝：闩永不复位。
        var secondRound = 0
        for (reason in NotifyWarnReason.values()) {
            if (latch.shouldWarn(reason)) secondRound++
        }
        assertEquals(0, secondRound)
    }

    @Test
    fun `并发下每个 reason 恰好放行一次`() {
        val latch = NotificationWarnLatch()
        val threadCount = 16
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)
        val granted = AtomicInteger(0)
        // 16 个线程同时冲同一 reason：CountDownLatch 保证它们尽量同时进入临界区，
        // 放行数必须恰为 1（若 @Synchronized 的 check-then-set 有窗口，这里会 > 1）。
        val workers = (1..threadCount).map {
            Thread {
                start.await()
                if (latch.shouldWarn(NotifyWarnReason.NOTIFY_FAILED)) granted.incrementAndGet()
                done.countDown()
            }
        }
        workers.forEach { it.start() }
        start.countDown()
        done.await()
        workers.forEach { it.join() }
        assertEquals(1, granted.get())
    }
}
