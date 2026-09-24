package com.rickeal.agent.core.design

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/** 离散档位连续跳变时的最小间隔：比这更密的 tick 会连成"嗡鸣"。 */
private const val TICK_THROTTLE_MS = 100L

/**
 * 玻璃体系的触感反馈出口。
 *
 * 组件里一律用 [rememberGlassHaptics] 取实例，不要在组合期 new。
 *
 * ## 三条必须先知道的事实（否则将来会被误判为"没加上"）
 *
 * 1. **不需要任何权限**。走的是 `View.performHapticFeedback` 平台通道
 *    （Compose 内部 `ViewCompat.performHapticFeedback`），与 `Vibrator` 服务
 *    是两条路 —— **Manifest 不声明 `VIBRATE`**，arch-guard 的"纯端侧声明面"
 *    守卫也不会被触发。
 * 2. **自动尊重系统「触感反馈」设置**。用户在系统设置里关掉触感后，平台直接静默，
 *    App 侧拿不到也无需判断 ⇒ 本波**不做 App 内开关**（做了就是第二个事实来源）。
 * 3. **API 等级分层**：`HapticFeedbackType` 只是 `HapticFeedbackConstants` 的 int 包装，
 *    最终由**平台**解释，低版本上未知常量是静默 no-op（不崩溃、不影响功能）。
 *    - `GestureEnd`(13) / `Confirm`(16) / `Reject`(17)：**API 30** 常量，
 *      minSdk 31 ⇒ **全版本必响**。
 *    - `ToggleOn`(21) / `ToggleOff`(22) / `SegmentTick`(26)：**API 34** 常量，
 *      Android 12/13（API 31–33）上**静默**，Android 14+ 才有反馈。
 *    ⇒ 看到"12 上开关不震"不要以为是接线漏了，那是平台常量未定义。
 *
 * ## ⚠️ 只能在**用户动作位点**发（Wave 6c 确立，与 [GlassSegmented] /
 * [LiquidBottomTabs] 的 onSelected 同一条纪律）
 *
 * 绝不能挂在 `LaunchedEffect(回显值)` 或 `snapshotFlow` 收集器里：
 * 回显写（导航返回、外部状态回流）同样会触达收集器，于是"没点也震"
 * "返回上一页也震"。触点只有两类：
 *   - 手势提交口（`onDragStopped` / `commitValue`）
 *   - 点击提交口（`onClick` / `toggleable` 的 `onValueChange`）
 *
 * 含可变节流时间戳，故**不标 `@Immutable`**（标了是撒谎，Compose 会据此做错误跳过）。
 */
class GlassHaptics internal constructor(
    private val feedback: HapticFeedback,
) {
    /** 开关类控件的提交：on/off 用两个不同常量，手指能分辨方向。 */
    fun toggle(on: Boolean) = feedback.performHapticFeedback(
        if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff
    )

    /**
     * 离散档位"走过一格"（滑块过档 / 分段换项 / 页签切换）。
     *
     * 带 [TICK_THROTTLE_MS] 节流：连续拖动时每帧都可能有新档位，
     * 不节流就是高频嗡鸣（HIG：触感要"可分辨"，不是"一直在震"）。
     */
    fun tick() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTickAt < TICK_THROTTLE_MS) return
        lastTickAt = now
        feedback.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }

    /** 一次性离散动作（点轨道跳转 / 键盘步进 / 无障碍 SetProgress）——不受节流。 */
    fun tickForced() = feedback.performHapticFeedback(HapticFeedbackType.SegmentTick)

    /** 用户动作被接受（授权、同意）。 */
    fun confirm() = feedback.performHapticFeedback(HapticFeedbackType.Confirm)

    /** 用户动作被拒绝 / 失败（拒绝、不同意）。 */
    fun reject() = feedback.performHapticFeedback(HapticFeedbackType.Reject)

    /** 连续手势结束（连续滑块松手）。 */
    fun gestureEnd() = feedback.performHapticFeedback(HapticFeedbackType.GestureEnd)

    private var lastTickAt: Long = 0L
}

/** 取当前组合的触感实例。key 用 `feedback`：换宿主时重建，不闭包住旧引用。 */
@Composable
fun rememberGlassHaptics(): GlassHaptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { GlassHaptics(feedback) }
}
