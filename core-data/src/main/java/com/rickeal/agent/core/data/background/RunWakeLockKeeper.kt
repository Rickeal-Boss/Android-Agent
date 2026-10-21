package com.rickeal.agent.core.data.background

import android.os.PowerManager
import android.os.SystemClock
import com.rickeal.agent.core.model.AgentLogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * run 期间的 `PARTIAL_WAKE_LOCK` 持有者 + 周期续期（W78 D9，C5 从服务外提，纯搬迁）。
 *
 * ## 为什么需要续期
 * 审批挂起无超时上限（`ToolApprovalHandler` 是裸 suspend）且挂起时长被加回预算 ⇒ run 的墙钟
 * 存活时间**无上界**；而锁原本只在 onCreate/onStartCommand 各 acquire 一次、390s 后静默自释
 * 且全仓无第二处 acquire ⇒ 用户放下手机超 6.5 分钟再批准，CPU 已深睡。
 *
 * ## 三个不变量（搬迁不许走样，改一条 = 判据失效）
 * 1. **物理断路器保留**：非引用计数 + `acquire(timeoutMillis)`，控制器全挂也不会变成耗电事故。
 * 2. **无条件 acquire**：OEM（如 ColorOS 的 `OplusProxyWakeLock`）在系统侧回收锁而客户端
 *    `isHeld` 仍为 true ⇒「只在 !isHeld 时补」判不出被回收，无条件重申请才既重置超时又抢回锁。
 * 3. **续期有硬上限**：到 [maxHoldMillis]（默认 30min）即 break，行为退回「今天」（锁自然
 *    到期）；否则续期会把第 1 条的断路器架空成「无限持有」。
 *
 * 周期 120s 的推导：容错比 390/120 = 3.25（连续错过 2 次续期仍不断锁）；下界 73s = 常规 run
 * 的生成 30–63s + 10s 宽限窗（周期小于它 = 每次普通 run 都白续一次）。日志条数由
 * `maxHoldMillis / renewIntervalMillis` 结构保证 ≤15 行，不冲掉 run 的诊断日志。
 * 诚实限定：续期**不能替代** OEM 白名单 —— 未开「允许完全后台行为」时它把「永远深睡」变成
 * 「每 120s 有 10s CPU 的间歇窗口」，是缓解不是修复。
 */
internal class RunWakeLockKeeper(
    private val powerManager: PowerManager?,
    private val tag: String = RunForegroundPolicy.WAKE_LOCK_TAG,
    private val timeoutMillis: Long = RunForegroundPolicy.WAKE_LOCK_TIMEOUT_MILLIS,
    private val renewIntervalMillis: Long = RunForegroundPolicy.WAKE_LOCK_RENEW_INTERVAL_MILLIS,
    private val maxHoldMillis: Long = RunForegroundPolicy.WAKE_LOCK_MAX_HOLD_MILLIS,
) {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var renewJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var wakeLockHeldSince: Long = 0L   // 持有期起点（elapsedRealtime），用于 maxHoldMillis 上限

    val isHeld: Boolean get() = wakeLock?.isHeld == true   // powerManager 为 null 时恒 false

    /** 幂等补锁：onCreate 与 onStartCommand 都会调（双保险）。 */
    fun acquire() {
        if (isHeld) return
        val manager = powerManager ?: run {
            AgentLogStore.warn("后台保活：PowerManager 不可用，锁屏后推理可能被挂起")
            return
        }
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag).apply {
            // 非引用计数：release() 一次必然彻底释放。带超时的 acquire = 物理兜底。
            setReferenceCounted(false)
            acquire(timeoutMillis)
        }
        AgentLogStore.info("WakeLock 已获取（超时 ${timeoutMillis}ms，held=$isHeld）")
    }

    /** 每 [renewIntervalMillis] 续一次，直到 [maxHoldMillis] 为止（幂等）。 */
    fun startRenewal() {
        if (renewJob != null) return
        // 无锁可续 ⇒ 不空转（PowerManager 缺失时 acquire() 已 warn 过，不重复刷屏）。
        if (powerManager == null) return
        wakeLockHeldSince = SystemClock.elapsedRealtime()
        renewJob = serviceScope.launch {
            while (isActive) {
                delay(renewIntervalMillis)
                val held = SystemClock.elapsedRealtime() - wakeLockHeldSince
                if (!RunForegroundPolicy.shouldRenewWakeLock(held)) {
                    AgentLogStore.info(
                        "WakeLock 续期已达上限（已持 ${held}ms / 上限 ${maxHoldMillis}ms），不再续期：锁将自然到期",
                    )
                    break
                }
                renew(held)
            }
        }
    }

    /**
     * 先取消续期、再释放锁 —— **顺序固定**：反过来续期协程可能在「已解锁」的窗口把锁抢回来
     * （[renew] 是无条件重申请的）。服务侧只调本方法，不要再单独 cancel job / scope。
     */
    fun release() {
        renewJob?.cancel()
        renewJob = null
        serviceScope.cancel()
        val lock = wakeLock ?: return
        AgentLogStore.info("WakeLock 释放（held=${lock.isHeld}）")
        runCatching { if (lock.isHeld) lock.release() }
        wakeLock = null
    }

    private fun renew(heldMillis: Long) {
        val lock = wakeLock ?: return acquire()
        // 非引用计数锁的 acquire(timeout)：AOSP 对 `!mRefCounted` 分支即使 mHeld==true 也会
        // 重新向系统申请并重置超时（原文：这类锁并不总能被正确释放）⇒ 正好是本处所需。
        runCatching { lock.acquire(timeoutMillis) }
            .onFailure { AgentLogStore.warn("WakeLock 续期失败（${it.javaClass.simpleName}）") }
        AgentLogStore.info("WakeLock 续期（已持 ${heldMillis}ms，held=${lock.isHeld}）")
    }
}
