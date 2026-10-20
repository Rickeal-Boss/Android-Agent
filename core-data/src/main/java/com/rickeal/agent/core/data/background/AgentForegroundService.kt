package com.rickeal.agent.core.data.background

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.rickeal.agent.core.data.notify.AndroidGenerationNotifier
import com.rickeal.agent.core.model.AgentLogStore

/**
 * 「run 期间保活」的前台服务壳（W77）。
 *
 * ## 它只做三件事
 *
 * 1. `startForeground` —— 把进程从 cached 态抬到前台服务优先级，**免于 A12+ 的
 *    应用冻结器**（这是「切后台继续跑」的正面机制，不是「防杀」的银弹）；
 * 2. 持 `PARTIAL_WAKE_LOCK` —— 锁屏后 CPU 不进入 suspend，推理不中断；
 * 3. 终态释放 —— `onDestroy` 里撤通知 + 解锁，**且锁本身带 6.5 分钟超时兜底**。
 *
 * ## 刻意不做的事（每条都有理由）
 *
 * - **不接管 run 生命周期**：run 仍挂在 `ChatRunCoordinator` 的 `viewModelScope` 上。
 *   服务只是「进程别被冻住」，用户划掉任务 = 显式终止意图 ⇒ `stopWithTask`（默认 true）
 *   让服务随任务一起销毁，run 走 `onStop()` 干净收尾。
 * - **不 sticky**：推理不可盲重启。进程真死了走既有 journal 恢复卡（W2/W47），
 *   这是本仓一贯的诚实路径 —— 假装能续跑比承认中断更糟。
 * - **不申请电池优化豁免**：见 [BackgroundRestrictionStatus] 的 KDoc。
 *
 * ## 类型声明（合规面，真机不可验）
 *
 * `specialUse` 的 Runtime prerequisites 官方为 **None**（只需权限 + property + Play 人审）。
 * 本机是 Android 13（SDK 33），平台**不校验** FGS 类型 ⇒ 「类型合规」这一命题在本机
 * 无法取证，只能靠 manifest 声明 + 守卫 #43 静态钉死。真机到 A14+ 时补测。
 */
class AgentForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AgentLogStore.info("后台保活服务创建（前台化前）")
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val iconRes = intent?.getIntExtra(RunForegroundPolicy.EXTRA_SMALL_ICON, 0) ?: 0
        // 幂等补锁：若 onCreate 的 acquire 因任何原因没生效（例如被系统回收 / 上一轮
        // onDestroy 与本轮 onCreate 交错），这里补一次 —— 保活的核心是「run 期间 CPU 不睡」，
        // 不能因为一次 acquire 失败就整轮失效。
        acquireWakeLock()
        return try {
            startInForeground(iconRes)
            AgentLogStore.info("后台保活服务已进入前台（startId=$startId）")
            START_NOT_STICKY
        } catch (e: RuntimeException) {
            // 走到这里通常有两种原因，且都不该把 App 打崩：
            //  ① API 34+ 缺 FOREGROUND_SERVICE_SPECIAL_USE 权限 / manifest 类型不成对
            //     ⇒ SecurityException（守卫 #43 就是为这条存在的）；
            //  ② 极窄竞态下 startForeground 超时。
            // 降级语义：本次 run 退化成「没有保活」——与 W77 之前的现状完全一致，
            // 不会更差；但必须留痕，否则用户只会看到「切后台就停」而查不到原因。
            AgentLogStore.warn(
                "后台保活服务未能进入前台（${e.javaClass.simpleName}）：本次 run 可能在切后台后被系统冻结",
            )
            releaseWakeLock()
            stopSelf(startId)
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        // 顺序：先撤前台状态（移除通知），再解锁。反过来会让通知在无锁的空窗里继续显示。
        AgentLogStore.info("后台保活服务销毁（撤前台 + 解锁）")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        releaseWakeLock()
        super.onDestroy()
    }

    // ---- 前台化 ----

    private fun startInForeground(iconRes: Int) {
        val notification = buildNotification(iconRes)
        if (RunForegroundPolicy.usesTypedForeground(Build.VERSION.SDK_INT)) {
            // lint 的 NewApi 只认同一方法内的字面量 SDK_INT 比较，无法跨方法跟踪
            // `usesTypedForeground()`（纯策略函数，分派判据由 RunForegroundPolicyTest
            // 单测钉死）⇒ 就地 @Suppress 而非把 SDK 判断摊回这里摊薄测试面。
            // API 31~33 走 else 三参重载（type 被 ServiceCompat 兼容层忽略）。
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
     * FGS 的「底稿」通知：与速度通知**同一个 id**（1001）。
     *
     * 用户开着「生成速度通知」时，`AndroidGenerationNotifier.onTick` 每秒
     * `notify(1001, …)` 把它覆盖成速度文案；关着时停留在这条底稿上。
     * 两条路都不会让通知栏出现「生成中」+「后台运行中」两条重复通知。
     */
    private fun buildNotification(iconRes: Int): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return NotificationCompat.Builder(this, AndroidGenerationNotifier.CHANNEL_ID)
            // 兜底图标：app 层没传时（理论上不会）也不能传 0，否则 startForeground 直接抛。
            .setSmallIcon(if (iconRes != 0) iconRes else android.R.drawable.stat_sys_download)
            .setContentTitle("端侧生成中")
            .setContentText("正在后台运行 · 点击返回")
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
                // 会让「谁忘了 release」变成难查的泄漏源）。
                setReferenceCounted(false)
                // 带超时的 acquire —— 物理兜底，控制器全挂也不会变成耗电事故。
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
}
