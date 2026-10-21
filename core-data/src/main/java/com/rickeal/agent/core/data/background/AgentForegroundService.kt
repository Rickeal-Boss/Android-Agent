package com.rickeal.agent.core.data.background

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.rickeal.agent.core.data.notify.OngoingNotificationSlot
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
 * 「run 期间保活」的前台服务壳（W77 / W78）。
 *
 * ## 它只做四件事
 *
 * 1. `startForeground` —— 把进程从 cached 态抬到前台服务优先级，**免于 A12+ 的
 *    应用冻结器**（这是「切后台继续跑」的正面机制，不是「防杀」的银弹）；
 * 2. 持 `PARTIAL_WAKE_LOCK` —— 锁屏后 CPU 不进入 suspend，推理不中断；
 * 3. **周期续期 WakeLock**（W78 D9）—— 审批挂起无超时上限（`ToolApprovalHandler` 是裸
 *    suspend），run 的墙钟存活时间可远超 390s，而锁原本只在 onCreate/onStartCommand 各
 *    acquire 一次、390s 后静默自释且无重取路径 ⇒ 用户放下手机超过 6.5 分钟再回来批准，
 *    CPU 已深睡。续期见 [startWakeLockRenewal]。
 * 4. 终态释放 —— `onDestroy` 里撤通知 + 解锁，**且锁本身仍带 6.5 分钟超时兜底**。
 *
 * ## 通知所有权（W78 D2）
 *
 * FGS 通知 id 是 1002（[RunForegroundPolicy.ONGOING_NOTIFICATION_ID]），与速度通知的
 * 1001（`OngoingNotificationSlot.SPEED_NOTIFICATION_ID`）**分离**。速度文案不另发一条，
 * 而是经 [OngoingNotificationSlot] 借道本服务刷新**同一条** —— 本服务是这条通知的
 * **唯一写者**（两个 builder 写同一个 id 会把 FGS 通知刷成点不动的死通知）。
 *
 * ## 刻意不做的事
 *
 * - **不接管 run 生命周期**：run 仍挂在 `ChatRunCoordinator` 的 `viewModelScope` 上。
 *   用户划掉任务 = 显式终止意图 ⇒ `stopWithTask`（默认 true）让服务随任务销毁。
 * - **不 sticky**：推理不可盲重启，进程真死了走既有 journal 恢复卡（W2/W47）。
 * - **不申请电池优化豁免**：见 [BackgroundRestrictionStatus] 的 KDoc。
 *
 * ## 类型声明（合规面，真机不可验）
 *
 * 本机是 Android 13（SDK 33），平台**不校验** FGS 类型 ⇒ 只能靠 manifest 声明 + 守卫 #43 钉死。
 */
class AgentForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    /** 状态栏小图标（`onStartCommand` 写入）：刷新常驻通知时要复用同一个值。 */
    private var iconRes: Int = 0

    // ---- W78 D9：续期协程的作用域（服务私有，onDestroy 必取消）----

    /**
     * 续期协程的作用域。私有 + `onDestroy` 里 cancel ⇒ **结构上不可能比服务活得久**
     * （不接 `applicationScope`、不接任何长生命周期作用域）。
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var renewJob: Job? = null

    /** 本次服务生命期开始持锁的时刻（`elapsedRealtime` 基准），用于 30min 总持有上限。 */
    private var wakeLockHeldSince: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AgentLogStore.info("后台保活服务创建（前台化前）")
        acquireWakeLock()
        startWakeLockRenewal()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        iconRes = intent?.getIntExtra(RunForegroundPolicy.EXTRA_SMALL_ICON, 0) ?: 0
        // 幂等补锁：若 onCreate 的 acquire 因任何原因没生效（例如被系统回收 / 上一轮
        // onDestroy 与本轮 onCreate 交错），这里补一次 —— 保活的核心是「run 期间 CPU 不睡」，
        // 不能因为一次 acquire 失败就整轮失效。
        acquireWakeLock()
        return try {
            startInForeground(iconRes, BASE_TEXT)
            // W78 D2：登记速度文案刷新口。幂等 —— 重复 onStartCommand 覆盖同一个 sink。
            OngoingNotificationSlot.attach(this) { text -> refreshOngoing(text) }
            AgentLogStore.info("后台保活服务已进入前台（startId=$startId）")
            START_NOT_STICKY
        } catch (e: RuntimeException) {
            // 走到这里通常是 ① API 34+ 缺 FOREGROUND_SERVICE_SPECIAL_USE 权限 / manifest
            // 类型不成对 ⇒ SecurityException（守卫 #43 就是为这条存在的）② 极窄竞态下
            // startForeground 超时。降级语义：本次 run 退化成「没有保活」，与 W77 之前一致，
            // 不会更差；但必须留痕，否则用户只看到「切后台就停」而查不到原因。
            AgentLogStore.warn(
                "后台保活服务未能进入前台（${e.javaClass.simpleName}）：本次 run 可能在切后台后被系统冻结",
            )
            releaseWakeLock()
            stopSelf(startId)
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        // 顺序（W78）：先停续期与注销槽，再撤前台，最后解锁 —— 反过来的话续期协程可能在
        // 「已解锁」的窗口里把锁又抢回来（D9 的泄漏面）。
        renewJob?.cancel()
        renewJob = null
        // 结构上保证续期协程不活得比服务久（SupervisorJob + cancel 双保险）。
        serviceScope.cancel()
        // 注销刷新口：不清掉的话 notifier 会把速度文案投进已销毁的服务（R1 僵尸 sink）。
        OngoingNotificationSlot.detach(this)
        AgentLogStore.info("后台保活服务销毁（撤前台 + 解锁）")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        releaseWakeLock()
        super.onDestroy()
    }

    // ---- W78 D9：WakeLock 周期续期 ----

    /**
     * 每 [RunForegroundPolicy.WAKE_LOCK_RENEW_INTERVAL_MILLIS] 续一次，直到
     * [RunForegroundPolicy.WAKE_LOCK_MAX_HOLD_MILLIS]（30min）为止。
     *
     * **为什么无条件 acquire 而不是「只在 !isHeld 时补」**：OEM（如 ColorOS 的
     * `OplusProxyWakeLock`）是在系统侧把锁回收，客户端 `isHeld` 仍为 true ⇒
     * 「按 isHeld 判断」永远判不出被回收。无条件重申请才既重置超时又抢回被回收的锁。
     *
     * **日志有界**：30min / 120s = 最多 15 行（`AgentLogStore` 是 200 条环形缓冲），
     * 不会冲掉 run 的诊断日志。
     *
     * **诚实限定**：续期不能替代 OEM 白名单 —— 未开「允许完全后台行为」时，它把
     * 「永远深睡」变成「每 120s 有 10s CPU 的间歇窗口」：是缓解，不是修复。
     */
    private fun startWakeLockRenewal() {
        if (renewJob != null) return
        wakeLockHeldSince = SystemClock.elapsedRealtime()
        renewJob = serviceScope.launch {
            while (isActive) {
                delay(RunForegroundPolicy.WAKE_LOCK_RENEW_INTERVAL_MILLIS)
                val held = SystemClock.elapsedRealtime() - wakeLockHeldSince
                if (!RunForegroundPolicy.shouldRenewWakeLock(held)) {
                    AgentLogStore.info(
                        "WakeLock 续期已达上限（已持 ${held}ms / 上限 " +
                            "${RunForegroundPolicy.WAKE_LOCK_MAX_HOLD_MILLIS}ms），不再续期：锁将自然到期",
                    )
                    break
                }
                renewWakeLock(held)
            }
        }
    }

    private fun renewWakeLock(heldMillis: Long) {
        val lock = wakeLock ?: run {
            // 锁对象本身被回收（releaseWakeLock 置空）⇒ 重新申请一个，下一周期再续。
            acquireWakeLock()
            return
        }
        // 非引用计数锁的 acquire(timeout)：AOSP 对 `!mRefCounted` 分支即使 mHeld==true
        // 也会重新向系统申请并重置超时（注释原文：这类锁并不总能被正确释放）⇒ 正好是本处所需。
        runCatching { lock.acquire(RunForegroundPolicy.WAKE_LOCK_TIMEOUT_MILLIS) }
            .onFailure { AgentLogStore.warn("WakeLock 续期失败（${it.javaClass.simpleName}）") }
        AgentLogStore.info("WakeLock 续期（已持 ${heldMillis}ms，held=${lock.isHeld}）")
    }

    // ---- 前台化 ----

    private fun startInForeground(iconRes: Int, text: String) {
        val notification = buildNotification(iconRes, text)
        if (RunForegroundPolicy.usesTypedForeground(Build.VERSION.SDK_INT)) {
            // lint 的 NewApi 只认同一方法内的字面量 SDK_INT 比较，无法跨方法跟踪
            // `usesTypedForeground()`（分派判据由 RunForegroundPolicyTest 钉死）⇒ 就地 @Suppress。
            @Suppress("NewApi")
            startTypedForeground(notification)
        } else {
            // API 31~33：不传类型位（平台不认识 specialUse，传了反而可能被拒）。
            startForeground(RunForegroundPolicy.ONGOING_NOTIFICATION_ID, notification)
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun startTypedForeground(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            RunForegroundPolicy.ONGOING_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    /**
     * 刷新常驻通知文本（W78 D2 的借道出口）。返回 false ⇒ notifier 回落自己发 1001。
     *
     * 用 `startForeground` 而不是 `notify`：前者同时保持「前台服务 ↔ 通知」的绑定，
     * 且 [startInForeground] 的 `usesTypedForeground` 分派可复用（A14+ 类型位不丢）。
     */
    private fun refreshOngoing(text: String): Boolean =
        runCatching {
            startInForeground(iconRes, text)
            true
        }
            .onFailure {
                AgentLogStore.warn("后台保活通知刷新失败（${it.javaClass.simpleName}）")
            }
            .getOrDefault(false)

    /** FGS 的常驻通知：id 1002，渠道 [NOTIFICATION_CHANNEL_ID]（独立于速度通知渠道）。 */
    private fun buildNotification(iconRes: Int, text: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            // 兜底图标：app 层没传时（理论上不会）也不能传 0，否则 startForeground 直接抛。
            .setSmallIcon(if (iconRes != 0) iconRes else android.R.drawable.stat_sys_download)
            .setContentTitle("端侧生成中")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .apply { contentIntent?.let { setContentIntent(it) } }
            .build()
    }

    // ---- WakeLock ----

    private fun acquireWakeLock() {
        // 幂等：onCreate 与 onStartCommand 都会调（双保险），已持有则直接返回。
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java) ?: run {
            AgentLogStore.warn("后台保活：PowerManager 不可用，锁屏后推理可能被挂起")
            return
        }
        wakeLock = powerManager
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, RunForegroundPolicy.WAKE_LOCK_TAG)
            .apply {
                // 非引用计数：onDestroy 一次 release 必然彻底释放（多次 acquire 语义
                // 会让「谁忘了 release」变成难查的泄漏源）。带超时的 acquire = 物理兜底；
                // W78 D9 的续期**不动这个超时**，只在「被重置」上做文章。
                setReferenceCounted(false)
                acquire(RunForegroundPolicy.WAKE_LOCK_TIMEOUT_MILLIS)
            }
        AgentLogStore.info(
            "WakeLock 已获取（超时 ${RunForegroundPolicy.WAKE_LOCK_TIMEOUT_MILLIS}ms，held=${wakeLock?.isHeld}）",
        )
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        AgentLogStore.info("WakeLock 释放（held=${lock.isHeld}）")
        runCatching { if (lock.isHeld) lock.release() }
        wakeLock = null
    }

    companion object {
        /**
         * FGS 常驻通知渠道 id（W78 D2）。**独立于** `AndroidGenerationNotifier.CHANNEL_ID`
         * （"generation_speed"）：用户关掉速度通知渠道时 FGS 通知不能跟着消失 —— 那正是
         * 「已前台化但用户不可见」的形态。渠道由 `LiquidAgentApplication.onCreate` 幂等创建。
         * 放这里而不是 `RunForegroundPolicy`：后者是 internal，app 模块看不见。
         */
        const val NOTIFICATION_CHANNEL_ID: String = "background_run"

        /**
         * 冷启动清理 FGS 常驻通知（W78 D10①）。
         *
         * 普通 `notify()` 通知跨进程存活，FGS 通知则通常随服务销毁由 AMS 撤掉 ⇒ 本方法是
         * **纵深防御**，不是缺陷修复（依据与既有 P2-1 同口径：冷启动时不可能有合法的
         * 「生成中」，任何残留都是脏数据）。调用点：`LiquidAgentApplication.onCreate`。
         */
        fun clearStaleNotification(context: Context) {
            runCatching {
                NotificationManagerCompat.from(context)
                    .cancel(RunForegroundPolicy.ONGOING_NOTIFICATION_ID)
            }
        }

        private const val BASE_TEXT = "正在后台运行 · 点击返回"
    }
}
