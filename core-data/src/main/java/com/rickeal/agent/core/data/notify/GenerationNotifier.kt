package com.rickeal.agent.core.data.notify

import android.content.Context
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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
 * 需要 `POST_NOTIFICATIONS`（API 33+ 运行时权限）。用户拒绝时的策略是**静默降级**：
 * `enabled` 不持久化、App 内开关回弹，功能本身（端侧推理）完全不受影响 ——
 * 通知只是观测窗口，不是能力。 Manifest 的注释里也写了同一条承诺。
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
 */
class AndroidGenerationNotifier(
    private val context: Context,
    private val smallIconRes: Int,
) : GenerationNotifier {

    /** 上次真正 notify 的时刻（节流用）。[enabled] 关闭时不更新，避免重新打开后立刻补发旧节奏。 */
    private var lastEmitAt: Long = 0L

    // 写点只有 UI 侧收集设置流的一处，读点在 onTick；挂 @Volatile 防止跨线程可见性问题。
    // ⚠️ @Volatile 只能标有 backing field 的属性，所以放在实现类、不能上提到接口。
    @Volatile
    override var enabled: Boolean = false

    override fun onTick(tps: Float, ttftMillis: Long) {
        if (!enabled) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastEmitAt < THROTTLE_MS) return
        lastEmitAt = now

        // 权限被用户在系统设置里撤销时 areNotificationsEnabled() 会变 false，
        // 此时 notify() 在 API 33+ 会抛 SecurityException —— 通知是观测窗口不是能力，
        // 宁可静默放弃也绝不把推理打崩（fail-open 给推理、fail-closed 给通知）。
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val text = String.format(Locale.US, "%.1f token/s · 首字 %d ms", tps, ttftMillis)
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
            .onFailure {
                // 通知失败不落 ERROR 级（会刷屏），这是可预期的运行环境问题（权限/渠道被关）。
            }
    }

    override fun stop() {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
        // 归零：下次 run 从头开始节流窗口，避免旧节奏把首条通知吞掉。
        lastEmitAt = 0L
    }

    companion object {
        /** 渠道 id：与 LiquidAgentApplication.onCreate 里创建的渠道一致，改名 = 老用户丢设置。 */
        const val CHANNEL_ID = "generation_speed"

        /** 同一 id 反复 notify = 覆盖刷新（通知栏上恒一条）。 */
        private const val NOTIFICATION_ID = 1001

        /** 通知刷新节流：onTick 数据流 ~120ms 一次，通知 1s 一次足够，再密就是状态栏闪烁。 */
        private const val THROTTLE_MS = 1000L
    }
}
