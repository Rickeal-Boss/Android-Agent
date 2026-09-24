package com.rickeal.agent.core.design

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Immutable

/**
 * 液态弹簧规格。禁止线性 Tween —— 所有状态切换都必须有回弹。
 *
 * ## 与 Material 3 Expressive 的对齐
 *
 * M3E 的核心主张是「动效即表达」：用**弹簧**而非缓动曲线，让 UI 有物理感和个性。
 * 本文件按 M3E 的 motion scheme 调整了默认参数：
 *  - `Default`：对应 M3E `MotionScheme.expressive()` 的标准弹簧 —— 中低刚度 + 微欠阻尼，
 *    切换时有一点点过冲，是"有生命"的来源。
 *  - `Gentle`：对应 M3E 的 **spatial** 慢速档，用于大位移（页面切换 / 底部 sheet）。
 *  - `Snappy`：对应 M3E 的 **effects** 快速档，用于微小的即时反馈（按压 / 选中）。
 *
 * 依赖层面：BOM 2026.02.00 锁 material3 = 1.4.0，M3E 已在该版本稳定，
 * 因此**无需升级依赖**（升级会违反 libs.versions.toml 的版本锁定铁律）。
 * 这里用纯 Compose 的 spring() 复刻 M3E 的手感，避免依赖 alpha API。
 */
@Immutable
data class LiquidMotionSpec(
    val stiffness: Float = Spring.StiffnessMediumLow,
    val dampingRatio: Float = 0.82f,
    val pressScale: Float = 0.965f,
    val hoverScale: Float = 1.02f,
    val enterDurationMillis: Int = 420,
    val exitDurationMillis: Int = 240,
)

/**
 * M3E expressive 档的阻尼比。
 *
 * Material 3 Expressive 的 `MotionScheme.expressive()` 用 **spatial spring**，
 * dampingRatio 明显 < 1（约 0.55）—— 切换时有一点点过冲，这是"有生命"的来源。
 * 普通档（0.82）已经接近临界阻尼，看起来是"稳"但没有个性。
 */
private const val ExpressiveDampingRatio = 0.55f

object LiquidMotion {
    val Default = LiquidMotionSpec()

    /** M3E spatial 档：大位移用，慢而稳，几乎不过冲。 */
    val Gentle = LiquidMotionSpec(stiffness = Spring.StiffnessVeryLow, dampingRatio = 0.95f)

    /** M3E effects 档：小反馈用，快而弹。 */
    val Snappy = LiquidMotionSpec(stiffness = Spring.StiffnessMedium, dampingRatio = 0.68f)

    /**
     * M3E expressive 档：小反馈用，快而**弹**（欠阻尼，会过冲一点）。
     * 用于"切换"而不是"位移" —— 选中态、展开/收起这类需要被注意到的变化。
     */
    val Expressive = LiquidMotionSpec(
        stiffness = Spring.StiffnessMediumLow,
        dampingRatio = ExpressiveDampingRatio
    )

    /**
     * 页签"点击切换"的胶囊滑动规格（[LiquidBottomTabs] 指示胶囊 / [GlassSegmented]）。
     *
     * **2026-09-24 真机修正（Wave 6，对齐上游）**：此前为"看得见的液态滑动"取
     * `stiffness=400 / ζ=0.8`（慢 + 略欠阻尼），真机录屏实证两个病理：
     *  1. **不跟手** —— 慢弹簧让胶囊滞后选中态一整拍（录屏 4.893s 帧：选中"设置"、
     *     胶囊还停在"记忆"）；
     *  2. **漂移越界** —— ζ=0.8 的过冲在快速连点（方向反复反转）下被放大，胶囊
     *     冲出玻璃条两端边界（录屏 6.060s / 6.193s 帧）。
     *
     * 上游 Kyant0 `DampedDragAnimation` 的 `valueAnimationSpec` 是
     * `spring(dampingRatio = 1f, stiffness = 1000f)`：临界阻尼、无过冲、感知收敛
     * ≈120ms——"液态感"来自**速度各向异性拉伸与按压缩放**（那两处上游与我们一致），
     * 而不是慢弹簧。本规格与其完全对齐；胶囊先于 ~300ms 的 NavHost 内容过渡到位，
     * 指示器快于内容层是正确的次序（点哪儿、哪儿先亮）。
     */
    val TabSwitch = LiquidMotionSpec(
        stiffness = 1000f,
        dampingRatio = Spring.DampingRatioNoBouncy,
    )

    /** 通用弹簧规格（可用于 Dp/Offset/Color 等） */
    fun <T> spring(spec: LiquidMotionSpec = Default): SpringSpec<T> = spring(
        dampingRatio = spec.dampingRatio,
        stiffness = spec.stiffness,
    )

    fun floatSpring(spec: LiquidMotionSpec = Default): SpringSpec<Float> = spring(
        dampingRatio = spec.dampingRatio,
        stiffness = spec.stiffness,
    )

    /**
     * M3E expressive 弹簧（通用）。
     *
     * 这是 M3E 的**自控等价实现**：material3 1.4.0 里 `MaterialExpressiveTheme` /
     * `MotionScheme` / `ExperimentalMaterial3ExpressiveApi` 全是 internal，
     * 编译器拒绝访问；1.5.0 至今是 alpha，升级会违反 libs.versions.toml 的版本锁定铁律。
     * 所以这里用纯 Compose 的 `spring()` 复刻 M3E expressive 的手感（阻尼比 0.55）。
     */
    fun <T> expressiveSpring(): SpringSpec<T> = spring(
        dampingRatio = ExpressiveDampingRatio,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** [expressiveSpring] 的 Float 特化，省掉调用点的类型推断歧义。 */
    fun floatExpressiveSpring(): SpringSpec<Float> = expressiveSpring()
}
