package com.rickeal.agent.core.data.background

import android.os.PowerManager
import android.os.SystemClock
import com.rickeal.agent.core.model.AgentLogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * run 期间的 `PARTIAL_WAKE_LOCK` 持有者 + 周期续期（W78 D9，C5 从服务外提）。
 *
 * ⚠️ **不得在字段初始化阶段构造本类**（W78 C6 P0）：Service 构造期 `attachBaseContext()`
 * 未调用 ⇒ 字段里 `getSystemService()` 拿到 null base Context ⇒ NPE 被包成
 * `Unable to create service` 打死进程。宿主必须在 `onCreate` 注入 `PowerManager?`。
 *
 * ## 为什么需要续期
 * 审批挂起无超时（裸 suspend）且挂起时长加回预算 ⇒ run 墙钟存活无上界；锁原本只 acquire
 * 一次、390s 自释 ⇒ 放下手机超 6.5min 再批准，CPU 已深睡。
 *
 * ## 三个不变量（搬迁不许走样，改一条 = 判据失效）
 * 1. **物理断路器保留**：非引用计数 + `acquire(timeoutMillis)`，控制器全挂也不会变成耗电事故。
 * 2. **无条件 acquire**：OEM（如 ColorOS 的 `OplusProxyWakeLock`）在系统侧回收锁而客户端
 *    `isHeld` 仍为 true ⇒「只在 !isHeld 时补」判不出被回收，无条件重申请才既重置超时又抢回锁。
 * 3. **续期有硬上限**：到 [maxHoldMillis]（默认 30min）即 break，行为退回「今天」（锁自然
 *    到期）；否则续期会把第 1 条的断路器架空成「无限持有」。
 *
 * 周期 120s 推导：容错比 390/120 = 3.25（连错 2 次仍不断锁）；下界 73s = 常规 run 生成
 * 30–63s + 宽限 10s（周期小于它 = 每轮白续一次）。日志条数结构保证 ≤15 行。
 * 诚实限定（2026-10-22 真机修订）：续期**不能对抗** OEM 冻结。ColorOS 灭屏约 10s 会冻结
 * 本进程（cgroup 级，见 docs/w78-bg-run-design.md R15），冻结期间协程不执行、锁也进不去；
 * 解冻后本循环立即补锁。故续期的正确定位是「冻结窗口外保持 + 解冻后补锁」。
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
            setReferenceCounted(false)
            acquire(timeoutMillis)
        }
        AgentLogStore.info("WakeLock 已获取（超时 ${timeoutMillis}ms，held=$isHeld）")
    }

    /** 每 [renewIntervalMillis] 续一次直到 [maxHoldMillis]（幂等）；无锁可续则不空转。 */
    fun startRenewal() {
        if (renewJob != null) return
        if (powerManager == null) return   // acquire() 已 warn 过，不重复刷屏
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
     *
     * 🔴 **不 cancel [serviceScope] 的原因 = 同实例可复活**（复审 34 P3-5）：Service 被
     * `stopForeground`/重启后在**同一实例**上再次 `onStartCommand` ⇒ `acquire()` 能补锁，
     * 但 scope 一旦被 cancel，`startRenewal()` 里 `launch` 只会返回一个已取消的 Job
     * （`renewJob != null` 判据也失效不了）⇒ 续期**静默失效**，锁在 390s 后自然到期而
     * 无人补，退回「放下手机 6.5min 后 CPU 深睡」。scope 空转（无子 Job）无任何开销。
     */
    fun release() {
        renewJob?.cancel()
        renewJob = null
        val lock = wakeLock ?: return
        AgentLogStore.info("WakeLock 释放（held=${lock.isHeld}）")
        runCatching { if (lock.isHeld) lock.release() }
        wakeLock = null
    }

    private fun renew(heldMillis: Long) {
        val lock = wakeLock ?: return acquire()
        // 非引用计数锁：AOSP 对 !mRefCounted 分支即使 mHeld==true 也重新申请并重置超时。
        runCatching { lock.acquire(timeoutMillis) }
            .onFailure { AgentLogStore.warn("WakeLock 续期失败（${it.javaClass.simpleName}）") }
        AgentLogStore.info("WakeLock 续期（已持 ${heldMillis}ms，held=${lock.isHeld}）")
    }
}
