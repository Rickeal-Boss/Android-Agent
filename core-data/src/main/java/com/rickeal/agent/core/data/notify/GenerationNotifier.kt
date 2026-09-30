package com.rickeal.agent.core.data.notify

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.rickeal.agent.core.data.perf.PerfSample
import com.rickeal.agent.core.model.AgentLogStore
import java.util.Locale

/**
 * 「端侧生成速度」通知的抽象出口（Wave 9 需求 5）。
 *
 * 为什么是个接口而不是直接类：通知是**平台能力**，而 [ChatViewModel] / 引擎回调
 * 只该知道"有个地方要汇报速度"。接口让单测可以塞 no-op 实现，也让将来换成
 * 应用内指示条（不走系统通知）时 ViewModel 零改动。
 *
 * ## 权限与降级
 *
 * 需要 `POST_NOTIFICATIONS`（API 33+ 运行时权限）。用户拒绝时的策略是**功能降级**：
 * `enabled` 不持久化、App 内开关回弹，功能本身（端侧推理）完全不受影响 ——
 * 通知只是观测窗口，不是能力。 Manifest 的注释里也写了同一条承诺。
 *
 * 「降级」不等于**静默**：没权限 / 通知被关时 [AndroidGenerationNotifier] 会落一条
 * WARN（**每类原因各一次**、上界 3 行/进程，不刷屏），否则用户在设置里开了开关却永远
 * 等不到通知，诊断页也查不到原因 —— 那正是「功能静默失效」的形态。
 *
 * ⚠️ Wave 36 E1 行为变化（有意交付）：三条降级出口（缺权限 / 通知被关 / `notify` 抛异常）
 * 此前共用**单个进程级布尔**，第一条命中的出口置闩后另外两条永不落痕；现按
 * [NotifyWarnReason] 三分类各闩一次 —— 同一进程内 warn 行数由「恒 1 行」变为「最多 3 行」。
 * 这是修复本质：三个出口互不隶属，任一被观测到都不该遮蔽另外两个。
 */
interface GenerationNotifier {
    /**
     * 汇报一次流式指标。实现方自行节流（数据流本身 ~120ms 一次，通知 1s 一次即可），
     * 未启用时必须 no-op 且**不得**产生任何平台调用。
     */
    fun onTick(tps: Float, ttftMillis: Long)

    /** 撤掉通知（run 终态 / 会话销毁）。幂等：没有在显示的通知时调用是无害 no-op。 */
    fun stop()

    /** 总开关。关闭时 [onTick] 必须是纯 no-op（连时间戳都不更新）。
     *  注意：`@Volatile` 只能标在**有 backing field 的实现属性**上（接口属性没有，
     *  标在接口上会编译红 —— R3 的教训），实现类负责跨线程可见性。 */
    var enabled: Boolean
}

/**
 * 基于 `NotificationManagerCompat` 的实现。
 *
 * 同一个 `NOTIFICATION_ID` 反复 `notify()` 是**覆盖**语义：通知栏上始终只有一条，
 * 内容随速度刷新 —— 这就是"吐字速度"的实现方式，不需要前台服务
 * （推理在进程内进行，进程活着通知就有效；进程死亡后的残留由
 * `LiquidAgentApplication.onCreate` 的兜底 `stop()` 清理 —— ongoing 通知在
 * Android 13 及以下用户划不掉，不能依赖用户手动清理）。
 *
 * @param smallIconRes 状态栏小图标。**必须由 app 层传入**（core-data 不能引 app 的 R），
 *   状态栏小图标要求纯白 + alpha 的单色矢量。
 * @param perfSampleProvider 性能样本读取口（Wave 30 §2.2）：通知文案追加
 *   `· CPU xx% · PSS xx MB`。观测窗口纪律 —— 只在 [onTick] 真正要 notify 时才读
 *   （enabled=false / 节流未到点都是零读）；无观测者时 provider 返回 null，文案回落
 *   既有形态。
 */
class AndroidGenerationNotifier(
    private val context: Context,
    private val smallIconRes: Int,
    private val perfSampleProvider: () -> PerfSample? = { null },
) : GenerationNotifier {

    /** 上次真正 notify 的时刻（节流用）。[enabled] 关闭时不更新，避免重新打开后立刻补发旧节奏。 */
    private var lastEmitAt: Long = 0L

    // 写点只有 UI 侧收集设置流的一处，读点在 onTick；挂 @Volatile 防止跨线程可见性问题。
    // ⚠️ @Volatile 只能标有 backing field 的属性，所以放在实现类、不能上提到接口。
    @Volatile
    override var enabled: Boolean = false

    /**
     * 「通知发不出去」的 WARN 闩（onTick 每秒一次，逐次落会把诊断页刷满）。
     *
     * Wave 36 E1：由单个进程级布尔改为按 [NotifyWarnReason] 三分类各闩一次 —— 三条降级
     * 出口互不隶属，共用一把闩会让第一条命中的出口遮蔽另外两条。闩类自带同步，见
     * [NotificationWarnLatch]。
     */
    private val warnLatch = NotificationWarnLatch()

    override fun onTick(tps: Float, ttftMillis: Long) {
        if (!enabled) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastEmitAt < THROTTLE_MS) return
        lastEmitAt = now

        // 「能发通知」要同时满足两条，缺一条就放弃本次并**留痕**：
        //  ① API 33+ 的运行时权限 POST_NOTIFICATIONS 已授予（用户在系统设置里撤销后
        //     notify() 会抛 SecurityException，而 runCatching 会把异常吞成「静默不弹」）；
        //  ② 通知没被用户在渠道 / 应用级关掉（areNotificationsEnabled()）。
        // 通知是观测窗口不是能力 —— 宁可放弃也绝不把推理打崩
        // （fail-open 给推理、fail-closed 给通知），但放弃必须可见。
        if (!hasPostNotificationsPermission()) {
            warnNoNotificationOnce(NotifyWarnReason.PERMISSION, "缺少 POST_NOTIFICATIONS 权限")
            return
        }
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            warnNoNotificationOnce(NotifyWarnReason.CHANNEL_DISABLED, "通知已被系统 / 渠道关闭")
            return
        }

        // 文案主体 = 既有形态（逐字节不变）；有性能样本时追加观测后缀（Wave 30）。
        // 文案变更属有意交付（方案 §2.2），commit message 已申报。
        var text = String.format(Locale.US, "%.1f token/s · 首字 %d ms", tps, ttftMillis)
        val perfSample = perfSampleProvider()
        if (perfSample != null) {
            perfSample.cpuPercent?.let { percent ->
                text += String.format(Locale.US, " · CPU %.0f%%", percent)
            }
            text += " · PSS ${perfSample.pssKb / 1024} MB"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIconRes)
            .setContentTitle("端侧生成中")
            .setContentText(text)
            // 同 id 覆盖刷新，不能每秒都响一声 / 弹横幅（渠道本身 IMPORTANCE_LOW 已无声，
            // 这里再叠一层保险，防止渠道被系统降级重排后行为回退）。
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { throwable ->
                // 走到这里通常是权限被撤销 / 渠道被删（SecurityException）。通知失败不落
                // ERROR 级（会刷屏），但也不能完全无声 —— 用户开了开关却看不到通知时，
                // 诊断页这一行是唯一的解释。
                warnNoNotificationOnce(
                    NotifyWarnReason.NOTIFY_FAILED,
                    "notify 失败：${throwable::class.java.simpleName}",
                )
            }
    }

    override fun stop() {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
        // 归零：下次 run 从头开始节流窗口，避免旧节奏把首条通知吞掉。
        lastEmitAt = 0L
    }

    /**
     * API 33+ 的 `POST_NOTIFICATIONS` 是否已授予。
     *
     * 32 及以下该权限是安装时授予的（无运行时权限），一律视为已授予。
     */
    private fun hasPostNotificationsPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            PERMISSION_POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 每类原因在同一个进程内只落一次「通知发不出去」的 WARN（onTick 是每秒一次的节流流）。
     *
     * Wave 36 E1：闩按 [reason] 三分类（上界 3 行/进程），不再共用单个布尔。日志文案主体
     * 逐字节不变 —— 只是把「分类」从文案前移到了闩的维度。
     */
    private fun warnNoNotificationOnce(reason: NotifyWarnReason, detail: String) {
        if (!warnLatch.shouldWarn(reason)) return
        AgentLogStore.warn("生成速度通知已跳过（$detail）：端侧推理不受影响")
    }

    companion object {
        /**
         * `POST_NOTIFICATIONS` 的权限名（API 33 新增）。
         *
         * 刻意写成字符串字面量而不是 `Manifest.permission.POST_NOTIFICATIONS`：
         * 后者是 API 33 才有的常量，本模块 minSdk 31，lint 会把这类「新增于高
         * API 的编译期常量」判为 InlinedApi（Warning 级），而本仓 lint 已开
         * warningsAsErrors —— 字符串形态语义完全相同且零 API 级别代价。
         */
        private const val PERMISSION_POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

        /** 渠道 id：与 LiquidAgentApplication.onCreate 里创建的渠道一致，改名 = 老用户丢设置。 */
        const val CHANNEL_ID = "generation_speed"

        /** 同一 id 反复 notify = 覆盖刷新（通知栏上恒一条）。 */
        private const val NOTIFICATION_ID = 1001

        /** 通知刷新节流：onTick 数据流 ~120ms 一次，通知 1s 一次足够，再密就是状态栏闪烁。 */
        private const val THROTTLE_MS = 1000L
    }
}
