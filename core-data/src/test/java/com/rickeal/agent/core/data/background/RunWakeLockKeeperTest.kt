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
 * 钉的是四条「零 PM 不变量」：acquire 不炸且不假报持有、startRenewal 幂等空转、
 * release 幂等（连续调用不炸、无副作用）、以及 W82 新增的「release 是终态」。
 * 这是 W78-06 挂账的销项：此前该文件零测试覆盖，null 缝的行为只活在注释里。
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

    /**
     * W82 P1：`released` 是**终态**，释放之后任何路径都不得再建锁。
     *
     * 修的是什么：`release()` 里 `renewJob?.cancel()` 是**异步**的，而在途 `renew()` 是
     * 非挂起函数、不会被 cancel 打断 —— 它可能在 release 之后执行到 `lock.acquire(...)`
     * （锁复活、无人释放、等 390s 超时），或走旧的 `?: return acquire()` 兜底**新建一把锁**。
     * 现在的保证是 `release()` 首行置 `released = true` + `renew()` 首行早退 + 删掉兜底新建。
     *
     * ⚠️ 本用例只能钉「零 PM 缝下释放后再调进来不崩、不假报持有」：`renew()` 是 private，
     * 且 `released` 的并发语义需要 fake `PowerManager`，本模块无 Robolectric ⇒ JVM 侧不可达。
     * 注意 `acquire()` **没有**加 `released` 闸门（刻意：宿主生命周期入口一旦变成静默 no-op，
     * 将来真出现「同实例复用」路径就会静默不加锁，比多一把等超时的锁更难查）——
     * 所以本用例断言的是「不崩 + isHeld 仍 false」，不是「acquire 被拦住」。
     * 真机判据：`adb shell dumpsys power` 里释放后不应再有本 App tag 的锁。
     */
    @Test
    fun `release 之后重复调 acquire 与 startRenewal 不崩且不假报持有`() {
        val keeper = RunWakeLockKeeper(powerManager = null)
        keeper.acquire()
        keeper.startRenewal()
        keeper.release()
        // released = true 之后的重复调用：既不能抛，也不能把 isHeld 变回 true。
        keeper.acquire()
        keeper.startRenewal()
        assertFalse(keeper.isHeld)
    }
}
