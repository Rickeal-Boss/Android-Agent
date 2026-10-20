package com.rickeal.agent.core.data.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RunForegroundPolicy] 的 JVM 单测（W77）。
 *
 * 为什么这些值值得被测试钉死：它们全部是**跨层契约**，改错的后果都不在编译期暴露 ——
 * - [RunForegroundPolicy.WAKE_LOCK_TIMEOUT_MILLIS] 调大 = 耗电兜底失效（锁可能长持）；
 * - [RunForegroundPolicy.ONGOING_NOTIFICATION_ID] 与 notifier 的 1001 分叉 = 通知栏两条；
 * - [RunForegroundPolicy.usesTypedForeground] 阈值写错 = A14+ 上缺类型位（SecurityException）
 *   或 A13 上传了平台不认识的位。
 *
 * 本测试只覆盖纯逻辑，不构造 Service / Context（Android 类不在 JVM 单测范围内）。
 */
class RunForegroundPolicyTest {

    @Test
    fun wakeLockTimeoutIsHardBudgetPlusMargin() {
        // 墙钟硬预算 300s + 余量 90s = 390s。任何一侧改动都必须同步这条断言。
        assertEquals(300_000L, RunForegroundPolicy.HARD_WALL_CLOCK_MILLIS)
        assertEquals(90_000L, RunForegroundPolicy.WAKE_LOCK_MARGIN_MILLIS)
        assertEquals(390_000L, RunForegroundPolicy.WAKE_LOCK_TIMEOUT_MILLIS)
        assertEquals(390_000L, RunForegroundPolicy.wakeLockTimeoutMillis())
    }

    @Test
    fun wakeLockTimeoutAcceptsExplicitBudget() {
        assertEquals(60_000L, RunForegroundPolicy.wakeLockTimeoutMillis(30_000L, 30_000L))
    }

    @Test
    fun typedForegroundOnlyFromApi34() {
        // 33 及以下：平台不认识 specialUse 位，不能传。
        assertFalse(RunForegroundPolicy.usesTypedForeground(31))
        assertFalse(RunForegroundPolicy.usesTypedForeground(32))
        assertFalse(RunForegroundPolicy.usesTypedForeground(33))
        // 34 起必须传（targetSdk≥34 的强制面）。
        assertTrue(RunForegroundPolicy.usesTypedForeground(34))
        assertTrue(RunForegroundPolicy.usesTypedForeground(35))
        assertTrue(RunForegroundPolicy.usesTypedForeground(36))
    }

    @Test
    fun startOnlyWhenBusyAndNotStarted() {
        assertTrue(RunForegroundPolicy.shouldStart(isBusy = true, started = false))
        assertFalse(RunForegroundPolicy.shouldStart(isBusy = true, started = true))
        assertFalse(RunForegroundPolicy.shouldStart(isBusy = false, started = false))
        assertFalse(RunForegroundPolicy.shouldStart(isBusy = false, started = true))
    }

    @Test
    fun stopOnlyWhenIdleAndStarted() {
        assertTrue(RunForegroundPolicy.shouldScheduleStop(isBusy = false, started = true))
        assertFalse(RunForegroundPolicy.shouldScheduleStop(isBusy = true, started = true))
        assertFalse(RunForegroundPolicy.shouldScheduleStop(isBusy = false, started = false))
        assertFalse(RunForegroundPolicy.shouldScheduleStop(isBusy = true, started = false))
    }

    @Test
    fun ongoingNotificationIdIsSharedWithSpeedNotifier() {
        // 与 AndroidGenerationNotifier.NOTIFICATION_ID 同源（1001）。分叉会在通知栏
        // 留下「生成中」+「后台运行中」两条常驻通知，且用户划不掉。
        assertEquals(1001, RunForegroundPolicy.ONGOING_NOTIFICATION_ID)
    }

    @Test
    fun graceWindowIsTenSeconds() {
        assertEquals(10_000L, RunForegroundPolicy.GRACE_MILLIS)
    }
}
