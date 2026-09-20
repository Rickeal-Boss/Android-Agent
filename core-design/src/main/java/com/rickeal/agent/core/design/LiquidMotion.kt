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

object LiquidMotion {
    val Default = LiquidMotionSpec()

    /** M3E spatial 档：大位移用，慢而稳，几乎不过冲。 */
    val Gentle = LiquidMotionSpec(stiffness = Spring.StiffnessVeryLow, dampingRatio = 0.95f)

    /** M3E effects 档：小反馈用，快而弹。 */
    val Snappy = LiquidMotionSpec(stiffness = Spring.StiffnessMedium, dampingRatio = 0.68f)

    /** 通用弹簧规格（可用于 Dp/Offset/Color 等） */
    fun <T> spring(spec: LiquidMotionSpec = Default): SpringSpec<T> = spring(
        dampingRatio = spec.dampingRatio,
        stiffness = spec.stiffness,
    )

    fun floatSpring(spec: LiquidMotionSpec = Default): SpringSpec<Float> = spring(
        dampingRatio = spec.dampingRatio,
        stiffness = spec.stiffness,
    )
}
