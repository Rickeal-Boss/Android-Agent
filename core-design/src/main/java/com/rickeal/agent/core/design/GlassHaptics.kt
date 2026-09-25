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
 * 触感强度档位（设置页「触感反馈强度」驱动）。
 *
 * 只决定"用哪个平台常量、要不要响"，不改变触感的**语义归属** ——
 * confirm 永远是 confirm（见 [GlassHaptics] 的档位×出口映射表）。
 */
enum class GlassHapticLevel {
    /** 全部出口 no-op（不读时钟、不碰平台通道）。 */
    OFF,

    /** 轻：全部走 [HapticFeedbackType.TextHandleMove]（API 29，minSdk 31 全版本必响）。 */
    LIGHT,

    /** 标准：各出口的"原生"常量（现状行为，默认值 ⇒ 未接线时行为不变）。 */
    STANDARD,

    /** 强：全部换成更重的常量（LongPress / ContextClick）。 */
    STRONG,
}

/**
 * 玻璃体系的触感反馈出口。
 *
 * 组件里一律用 [rememberGlassHaptics] 取实例，不要在组合期 new。
 *
 * ## 四条必须先知道的事实（否则将来会被误判为"没加上"）
 *
 * 1. **不需要任何权限**。走的是 `View.performHapticFeedback` 平台通道
 *    （Compose 内部 `ViewCompat.performHapticFeedback`），与 `Vibrator` 服务
 *    是两条路 —— **Manifest 不声明 `VIBRATE`**，arch-guard 的"纯端侧声明面"
 *    守卫也不会被触发。
 * 2. **自动尊重系统「触感反馈」设置**。用户在系统设置里关掉触感后，平台直接静默，
 *    App 侧拿不到也无需判断。
 *    ⚠️ **系统开关是总闸，本类的 [GlassHapticLevel] 只是 App 内的分档**：档位只在
 *    总闸开启时生效。用户在系统里关了触感，无论 App 档位调到哪都是静默 ——
 *    这也是 App 内**不做 on/off 开关、只做强度分档**的原因（做了就是第二个事实来源）。
 * 3. **API 等级分层**：`HapticFeedbackType` 只是 `HapticFeedbackConstants` 的 int 包装，
 *    最终由**平台**解释，低版本上未知常量是静默 no-op（不崩溃、不影响功能）。
 *    - `GestureEnd`(13) / `Confirm`(16) / `Reject`(17)：**API 30**，minSdk 31 全版本必响。
 *    - `SegmentTick`(26) / `ToggleOn`(21) / `ToggleOff`(22)：**API 34**，Android 12/13
 *      （API 31–33）上**静默**。
 *    - LIGHT 档的 `TextHandleMove`(29) 与 STRONG 档的 `ContextClick`(23) / `LongPress`(3)
 *      都低于 31 ⇒ **这两档在 Android 12/13 上也能响**，反而是 STANDARD 的
 *      SegmentTick 有静默窗口 —— 这是 LIGHT/STRONG 在旧机上的隐藏收益，不是缺陷。
 *    ⇒ 看到"12 上开关不震"不要以为是接线漏了，那是平台常量未定义。
 * 4. **只能在**用户动作位点**发**（Wave 6c 确立，与 [GlassSegmented] /
 *    [LiquidBottomTabs] 的 onSelected 同一条纪律）。绝不能挂在
 *    `LaunchedEffect(回显值)` 或 `snapshotFlow` 收集器里：回显写（导航返回、
 *    外部状态回流）同样会触达收集器，于是"没点也震""返回上一页也震"。
 *
 * ## 档位 × 出口映射
 *
 * | 出口 | LIGHT | STANDARD | STRONG |
 * |---|---|---|---|
 * | toggle | TextHandleMove | ToggleOn/ToggleOff | LongPress |
 * | tick / tickForced | TextHandleMove | SegmentTick | ContextClick |
 * | confirm / reject | **Confirm / Reject（不变）** | 同左 | 同左 |
 * | gestureEnd | TextHandleMove | GestureEnd | LongPress |
 *
 * 三条设计理由：
 * 1. **confirm / reject 不随档位升降**：它们是**语义事件**（授权成功 / 拒绝），
 *    不是强度反馈。降到 LIGHT 会把"成功"和"划过一格"变成同一个感觉，用户分不清
 *    自己刚才到底确认了什么；升到 STRONG 又会把"成功"做得像"出错"。
 * 2. **LIGHT 失去 on/off 方向感是可接受的**：TextHandleMove 没有 on/off 两个常量，
 *    开与关感觉一样。选轻档的用户要的就是"知道自己碰到了、但不吵"，方向感由
 *    开关本身的视觉位移动画承担（thumb 本来就会滑过去）。
 * 3. **STRONG 的 tick 用 ContextClick 而不是 LongPress**：tick 是**高频**出口
 *    （离散滑块逐档、键盘长按自动重复），LongPress 在多数机型上是明显偏重的"长按"
 *    感，连发就是嗡鸣/震手；ContextClick 更短更脆，"强"但可分辨。
 *
 * 含可变节流时间戳，故**不标 `@Immutable`**（标了是撒谎，Compose 会据此做错误跳过）。
 */
class GlassHaptics internal constructor(
    private val feedback: HapticFeedback,
    private val level: GlassHapticLevel = GlassHapticLevel.STANDARD,
) {
    /** 开关类控件的提交：on/off 用两个不同常量，手指能分辨方向（LIGHT 档除外，见上）。 */
    fun toggle(on: Boolean) {
        if (level == GlassHapticLevel.OFF) return
        feedback.performHapticFeedback(
            when (level) {
                GlassHapticLevel.LIGHT -> HapticFeedbackType.TextHandleMove
                GlassHapticLevel.STRONG -> HapticFeedbackType.LongPress
                // OFF 已在上面拦截，else 只会是 STANDARD。
                else -> if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff
            }
        )
    }

    /**
     * 离散档位"走过一格"（滑块过档 / 分段换项 / 页签切换）。
     *
     * 带 [TICK_THROTTLE_MS] 节流：连续拖动时每帧都可能有新档位，
     * 不节流就是高频嗡鸣（HIG：触感要"可分辨"，不是"一直在震"）。
     */
    fun tick() {
        if (level == GlassHapticLevel.OFF) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastTickAt < TICK_THROTTLE_MS) return
        lastTickAt = now
        feedback.performHapticFeedback(tickConstant)
    }

    /** 一次性离散动作（点轨道跳转 / 无障碍 SetProgress）——不受节流。 */
    fun tickForced() {
        if (level == GlassHapticLevel.OFF) return
        feedback.performHapticFeedback(tickConstant)
    }

    /** 用户动作被接受（授权、同意）。语义事件，**恒为 Confirm**，不随档位升降。 */
    fun confirm() {
        if (level == GlassHapticLevel.OFF) return
        feedback.performHapticFeedback(HapticFeedbackType.Confirm)
    }

    /** 用户动作被拒绝 / 失败（拒绝、不同意）。语义事件，**恒为 Reject**，不随档位升降。 */
    fun reject() {
        if (level == GlassHapticLevel.OFF) return
        feedback.performHapticFeedback(HapticFeedbackType.Reject)
    }

    /** 连续手势结束（滑块松手）。 */
    fun gestureEnd() {
        if (level == GlassHapticLevel.OFF) return
        feedback.performHapticFeedback(gestureEndConstant)
    }

    /** tick 系出口的档位映射。 */
    private val tickConstant: HapticFeedbackType
        get() = when (level) {
            GlassHapticLevel.LIGHT -> HapticFeedbackType.TextHandleMove
            GlassHapticLevel.STRONG -> HapticFeedbackType.ContextClick
            else -> HapticFeedbackType.SegmentTick
        }

    /** gestureEnd 出口的档位映射。 */
    private val gestureEndConstant: HapticFeedbackType
        get() = when (level) {
            GlassHapticLevel.LIGHT -> HapticFeedbackType.TextHandleMove
            GlassHapticLevel.STRONG -> HapticFeedbackType.LongPress
            else -> HapticFeedbackType.GestureEnd
        }

    private var lastTickAt: Long = 0L
}

/** 取当前组合的触感实例。key 用 `feedback` 与 `level`：换宿主或换档位时重建，不闭包旧引用。 */
@Composable
fun rememberGlassHaptics(): GlassHaptics {
    val feedback = LocalHapticFeedback.current
    val level = LocalGlassConfig.current.hapticLevel
    return remember(feedback, level) { GlassHaptics(feedback, level) }
}
