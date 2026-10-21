package com.rickeal.agent.core.data.background

import com.rickeal.agent.core.data.notify.OngoingNotificationSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RunForegroundPolicy] 的 JVM 单测（W77）。
 *
 * 为什么这些值值得被测试钉死：它们全部是**跨层契约**，改错的后果都不在编译期暴露 ——
 * - [RunForegroundPolicy.WAKE_LOCK_TIMEOUT_MILLIS] 调大 = 耗电兜底失效（锁可能长持）；
 * - [RunForegroundPolicy.ONGOING_NOTIFICATION_ID] 与速度通知 id 同值 = 「第二轮起 FGS 无通知」
 *   （W78 D2 改为 `assertNotEquals`，钉属性而不是钉 1001 这个手段）；
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
    fun ongoingNotificationIdMustDifferFromSpeedNotificationId() {
        // 属性：FGS 通知 id 与速度通知 id 必须不同 —— 速度通知的 stop() 会 cancel 自己的 id，
        // 共用即「第二轮起 FGS 无通知」。改回同值 = 这条红。
        assertNotEquals(
            OngoingNotificationSlot.SPEED_NOTIFICATION_ID,
            RunForegroundPolicy.ONGOING_NOTIFICATION_ID,
        )
    }

    @Test
    fun graceWindowIsTenSeconds() {
        assertEquals(10_000L, RunForegroundPolicy.GRACE_MILLIS)
    }

    @Test
    fun wakeLockRenewIntervalToleratesTwoMissedRenewals() {
        // 属性：390s 超时 / 120s 周期 ≥ 3 —— 连续错过 2 次续期（Doze 推迟 delay / OEM 抖动）
        // 仍不断锁。周期改大到 ≥195s 或超时改小 = 这条红。
        val tolerance = RunForegroundPolicy.WAKE_LOCK_TIMEOUT_MILLIS /
            RunForegroundPolicy.WAKE_LOCK_RENEW_INTERVAL_MILLIS
        assertTrue("容错比 $tolerance < 3：一次续期丢失就可能断锁", tolerance >= 3)
    }

    @Test
    fun wakeLockRenewalStopsAtHoldCap() {
        // 属性：续期有硬上限，到点必须停 —— 否则物理断路器被续期架空。
        assertTrue(RunForegroundPolicy.shouldRenewWakeLock(0L))
        assertTrue(
            RunForegroundPolicy.shouldRenewWakeLock(
                RunForegroundPolicy.WAKE_LOCK_MAX_HOLD_MILLIS - 1,
            ),
        )
        assertFalse(
            RunForegroundPolicy.shouldRenewWakeLock(
                RunForegroundPolicy.WAKE_LOCK_MAX_HOLD_MILLIS,
            ),
        )
        // 上限必须显著大于单次超时（否则续期毫无意义），且 ≤ 6 个墙钟预算（故障侧耗电有界）。
        assertTrue(
            RunForegroundPolicy.WAKE_LOCK_MAX_HOLD_MILLIS >
                RunForegroundPolicy.WAKE_LOCK_TIMEOUT_MILLIS,
        )
        assertTrue(
            RunForegroundPolicy.WAKE_LOCK_MAX_HOLD_MILLIS <=
                6 * RunForegroundPolicy.HARD_WALL_CLOCK_MILLIS,
        )
    }

    @Test
    fun wakeLockRenewalLogVolumeIsBounded() {
        // 属性：续期日志条数由结构保证有界 —— AgentLogStore 只有 200 条环形缓冲，
        // 若上限/周期被改成几百条，run 的诊断日志会被冲掉。
        val maxLines = RunForegroundPolicy.WAKE_LOCK_MAX_HOLD_MILLIS /
            RunForegroundPolicy.WAKE_LOCK_RENEW_INTERVAL_MILLIS
        assertTrue("续期日志上限 $maxLines 条，超过环形缓冲容量的 1/10", maxLines <= 20)
    }
}
