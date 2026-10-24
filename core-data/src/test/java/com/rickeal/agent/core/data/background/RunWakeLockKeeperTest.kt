package com.rickeal.agent.core.data.background

import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * [RunWakeLockKeeper] 的 JVM 单测（销 W78-06）。
 *
 * 为什么能跑在 JVM 上：`powerManager = null` 是既有测试缝 —— 三条公开入口
 * （[RunWakeLockKeeper.acquire] / [RunWakeLockKeeper.startRenewal] /
 * [RunWakeLockKeeper.release]）在 null PM 下全部走早退 / 空转路径，不触
 * `PowerManager.WakeLock`、不触 `SystemClock`（`startRenewal` 的
 * `SystemClock.elapsedRealtime()` 排在 null 早退**之后**），只剩
 * [RunWakeLockKeeper.isHeld] 恒 false 的口径可钉。
 *
 * 钉的是三条「零 PM 不变量」：acquire 不炸且不假报持有、startRenewal 幂等空转、
 * release 幂等（连续调用不炸、无副作用）。这是 W78-06 挂账的销项：此前该文件
 * 零测试覆盖，null 缝的行为只活在注释里。
 */
class RunWakeLockKeeperTest {

    @Test
    fun `acquire 无 PowerManager 时不崩且 isHeld 恒 false`() {
        val keeper = RunWakeLockKeeper(powerManager = null)
        keeper.acquire()
        assertFalse(keeper.isHeld)
    }

    @Test
    fun `startRenewal 无 PowerManager 时早退幂等，调两次也不崩`() {
        val keeper = RunWakeLockKeeper(powerManager = null)
        keeper.startRenewal()
        keeper.startRenewal() // 幂等：第二次同样早退，不重复 warn、不建协程
        assertFalse(keeper.isHeld)
    }

    @Test
    fun `release 幂等，连续调两次不崩且无副作用`() {
        val keeper = RunWakeLockKeeper(powerManager = null)
        keeper.acquire()
        keeper.startRenewal()
        keeper.release()
        keeper.release() // 连续第二次：renewJob 已置空、scope 重复 cancel 无副作用
        assertFalse(keeper.isHeld)
    }
}
