package com.rickeal.agent.core.data.background

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.rickeal.agent.core.data.notify.OngoingNotificationSlot
import com.rickeal.agent.core.model.AgentLogStore

/**
 * 「run 期间保活」的前台服务壳（W77 / W78）。
 *
 * 做四件事：① `startForeground` 把进程抬到前台服务优先级（免于 A12+ 应用冻结器 —— 这是
 * 「切后台继续跑」的正面机制，不是「防杀」银弹）；② 持 `PARTIAL_WAKE_LOCK`（锁屏后 CPU
 * 不 suspend）；③ **周期续期 WakeLock**（W78 D9：审批挂起无超时上限 ⇒ run 的墙钟存活可
 * 远超 390s，而锁只 acquire 一次、390s 后静默自释且无重取路径 ⇒ 见 [startWakeLockRenewal]）；
 * ④ 终态释放（`onDestroy` 撤通知 + 解锁，锁仍带 6.5 分钟超时兜底）。
 *
 * ## 通知所有权（W78 D2）
 *
 * FGS 通知 id = 1002（[RunForegroundPolicy.ONGOING_NOTIFICATION_ID]），与速度通知的 1001
 * （`OngoingNotificationSlot.SPEED_NOTIFICATION_ID`）**分离**。速度文案经
 * [OngoingNotificationSlot] 借道本服务刷新**同一条** —— 本服务是它的唯一写者（两个 builder
 * 写同一个 id 会把 FGS 通知刷成点不动的死通知）。
 *
 * ## 刻意不做
 *
 * 不接管 run 生命周期（run 仍挂 `viewModelScope`，划掉任务 = 显式终止 ⇒ `stopWithTask`）；
 * 不 sticky（推理不可盲重启，进程真死了走 journal 恢复卡 W2/W47）；不申请电池优化豁免。
 *
 * 合规面：本机 Android 13（SDK 33）平台**不校验** FGS 类型 ⇒ 类型合规只能靠 manifest 声明
 * + 守卫 #43 静态钉死，真机到 A14+ 补测。
 */
class AgentForegroundService : Service() {

    /**
     * WakeLock 持有者 + 续期（W78 D9）。C5 从本服务外提为独立类 —— 逻辑逐字搬迁，行为零变更：
     * 服务回到「只做壳」，[RunWakeLockKeeper] 负责锁与 30min 上限（判据见该类 KDoc）。
     */
    private val wakeLockKeeper = RunWakeLockKeeper(getSystemService(PowerManager::class.java))

    /** 状态栏小图标（`onStartCommand` 写入）：刷新常驻通知时要复用同一个值。 */
    private var iconRes: Int = 0

    // ---- W78 硬点 1：通知刷新的线程模型（主线程串行 + 合并 + 销毁后不复活）----

    private val mainHandler = Handler(Looper.getMainLooper())

    // 合并：只留最新一帧；队列里最多一个 Runnable；销毁即作废（见 [refreshOngoing]）。
    @Volatile
    private var pendingText: String? = null

    @Volatile
    private var refreshScheduled: Boolean = false

    @Volatile
    private var destroyed: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AgentLogStore.info("后台保活服务创建（前台化前）")
        wakeLockKeeper.acquire()
        wakeLockKeeper.startRenewal()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        iconRes = intent?.getIntExtra(RunForegroundPolicy.EXTRA_SMALL_ICON, 0) ?: 0
        // 幂等补锁：onCreate 的 acquire 可能因系统回收 / 与上一轮 onDestroy 交错而没生效。
        wakeLockKeeper.acquire()
        return try {
            startInForeground(iconRes, BASE_TEXT)
            // W78 D2：登记速度文案刷新口。幂等 —— 重复 onStartCommand 覆盖同一个 sink。
            OngoingNotificationSlot.attach(this) { text -> refreshOngoing(text) }
            AgentLogStore.info("后台保活服务已进入前台（startId=$startId）")
            START_NOT_STICKY
        } catch (e: RuntimeException) {
            // ① API 34+ 缺权限 / manifest 类型不成对 ⇒ SecurityException（守卫 #43）② 竞态下
            // startForeground 超时。降级 = 本次 run「没有保活」（与 W77 前一致），但必须留痕。
            AgentLogStore.warn(
                "后台保活服务未能进入前台（${e.javaClass.simpleName}）：本次 run 可能在切后台后被系统冻结",
            )
            wakeLockKeeper.release()
            stopSelf(startId)
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        // 顺序不得调换：① 置 destroyed + 清队列（让已 post 的刷新自我作废）② 注销槽
        // ③ 撤前台 ④ 释放锁（keeper 内部先停续期再解锁，见 RunWakeLockKeeper.release）。
        // 若先 stopForeground 再置 destroyed，队列里的刷新会把通知「复活」成常驻通知。
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        // 注销刷新口：不清掉的话 notifier 会把速度文案投进已销毁的服务（僵尸 sink）。
        OngoingNotificationSlot.detach(this)
        AgentLogStore.info("后台保活服务销毁（撤前台 + 解锁）")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        wakeLockKeeper.release()
        super.onDestroy()
    }

    // ---- 前台化（startForeground 的两条分支）----

    private fun startInForeground(iconRes: Int, text: String) {
        val notification = buildNotification(iconRes, text)
        if (RunForegroundPolicy.usesTypedForeground(Build.VERSION.SDK_INT)) {
            // lint 的 NewApi 只认同一方法内的字面量 SDK_INT 比较，无法跨方法跟踪
            // `usesTypedForeground()`（判据由 RunForegroundPolicyTest 钉死）⇒ 就地 @Suppress。
            @Suppress("NewApi")
            startTypedForeground(notification)
        } else {
            // API 31~33：不传类型位（平台不认识 specialUse）。
            startForeground(RunForegroundPolicy.ONGOING_NOTIFICATION_ID, notification)
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun startTypedForeground(notification: Notification) = ServiceCompat.startForeground(
        this,
        RunForegroundPolicy.ONGOING_NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
    )

    /**
     * 刷新常驻通知文本（**主线程串行 + 合并**，W78 硬点 1）。
     *
     * 返回值 = **「已受理」而非「已上屏」**：`false` 只表示「无活跃 FGS / 已销毁」⇒ notifier
     * 才回落发 1001。**刷新失败不回 false** —— 否则 notifier 再发一条 1001 ⇒ 两条常驻通知，
     * 比丢一帧速度文案糟得多（1s 后下一 tick 自愈）。
     *
     * 必须自己切主线程：`publish` 可能在任意线程，而后台线程的 `startForeground` 与主线程
     * `onDestroy` 的 `stopForeground(REMOVE)` 并发时，刷新可能落在撤前台**之后**把通知「复活」。
     * `pendingText` + `refreshScheduled` 保证主线程队列长度恒 ≤1（合并，不堆积）。
     */
    private fun refreshOngoing(text: String): Boolean {
        if (destroyed) return false
        pendingText = text
        if (refreshScheduled) return true
        refreshScheduled = true
        mainHandler.post {
            refreshScheduled = false
            val latest = pendingText ?: return@post
            // 双重检查：从 post 到执行之间服务可能已销毁 ⇒ 不能把通知「复活」。
            if (destroyed) return@post
            runCatching { startInForeground(iconRes, latest) }
                .onFailure {
                    // OEM 限流 / 渠道被关 / 竞态：只留痕，不重试、不改道去发 1001。
                    AgentLogStore.warn("后台保活通知刷新失败（${it.javaClass.simpleName}）：停留上一帧")
                }
        }
        return true
    }

    /** FGS 的常驻通知：id 1002，渠道 [NOTIFICATION_CHANNEL_ID]（独立于速度通知渠道）。 */
    private fun buildNotification(iconRes: Int, text: String): Notification {
        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            // 兜底图标：app 层没传时也不能传 0，否则 startForeground 直接抛。
            .setSmallIcon(if (iconRes != 0) iconRes else android.R.drawable.stat_sys_download)
            .setContentTitle("端侧生成中")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .apply { contentIntent?.let { setContentIntent(it) } }
            .build()
    }

    companion object {
        /**
         * FGS 常驻通知渠道 id（W78 D2）。**独立于** `AndroidGenerationNotifier.CHANNEL_ID`：
         * 用户关掉速度通知渠道时 FGS 通知不能跟着消失（那正是「已前台化但用户不可见」）。
         * 放这里而非 `RunForegroundPolicy`：后者 internal，app 模块看不见。
         */
        const val NOTIFICATION_CHANNEL_ID: String = "background_run"

        /**
         * 冷启动清理 FGS 常驻通知（W78 D10①）。FGS 通知通常随服务销毁由 AMS 撤掉 ⇒ 本方法
         * 是**纵深防御**而非缺陷修复（同 P2-1 口径：冷启动时不可能有合法的「生成中」）。
         * 调用点：`LiquidAgentApplication.onCreate`。
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
