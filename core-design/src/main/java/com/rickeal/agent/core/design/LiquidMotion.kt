package com.rickeal.agent.core.design
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Immutable

/**
 * 液态弹簧规格。禁止线性 Tween —— 所有状态切换都必须有回弹。
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
    val Gentle = LiquidMotionSpec(stiffness = Spring.StiffnessVeryLow, dampingRatio = 0.95f)
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
