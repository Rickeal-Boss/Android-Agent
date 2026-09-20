package com.rickeal.agent.core.design.liquid.highlight

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.LayoutDirection
import com.rickeal.agent.core.design.liquid.AmbientHighlightShader
import com.rickeal.agent.core.design.liquid.DefaultHighlightShader
import com.rickeal.agent.core.design.liquid.platform.RuntimeShader
import com.rickeal.agent.core.design.liquid.platform.RuntimeShaderCache
import com.rickeal.agent.core.design.liquid.platform.isRuntimeShaderSupported
import kotlin.math.PI

/**
 * 高光样式：决定玻璃边缘那一圈亮边**长什么样**。
 *
 * 三种预设（对齐 Kyant0，也对齐 iOS 26 的观感）：
 *  - [Default]：**方向性**高光 —— 迎光侧亮、背光侧透明。液态玻璃的默认选择。
 *  - [Ambient]：环境高光 —— 柔和整体提亮，用于按压反馈。
 *  - [Plain]：纯色描边（无 shader）—— API 31~32 的降级方案，或极简风格。
 *
 * 端口自 Kyant0 backdrop 库（Apache-2.0）。
 */
@Immutable
interface HighlightStyle {

    val color: Color

    val blendMode: BlendMode

    fun DrawScope.createShader(
        shape: Shape,
        runtimeShaderCache: RuntimeShaderCache
    ): RuntimeShader?

    /** 纯色描边：不需要 AGSL，任何 API 都可用。 */
    @Immutable
    data class Plain(
        override val color: Color = Color.White.copy(alpha = 0.38f),
        override val blendMode: BlendMode = BlendMode.Plus
    ) : HighlightStyle {
        override fun DrawScope.createShader(
            shape: Shape,
            runtimeShaderCache: RuntimeShaderCache
        ): RuntimeShader? = null
    }

    /** 方向性高光：沿 [angle] 方向，迎光侧亮。 */
    @Immutable
    data class Default(
        override val color: Color = Color.White.copy(alpha = 0.5f),
        override val blendMode: BlendMode = BlendMode.Plus,
        /** 光源方位角（度）。0° = 正右，90° = 正下，顺时针。45° 表示光从左上方射入。 */
        val angle: Float = 45f,
        /** 衰减指数：越大，亮边越贴边；1 = 线性。 */
        val falloff: Float = 1f
    ) : HighlightStyle {
        override fun DrawScope.createShader(
            shape: Shape,
            runtimeShaderCache: RuntimeShaderCache
        ): RuntimeShader? {
            return if (isRuntimeShaderSupported()) {
                runtimeShaderCache.obtainRuntimeShader(
                    "Default",
                    DefaultHighlightShader
                ).apply {
                    setFloatUniform("size", size.width, size.height)
                    setFloatUniform("cornerRadii", getCornerRadii(shape))
                    setColorUniform("color", color.copy(alpha = 1f))
                    setFloatUniform("angle", angle * (PI / 180f).toFloat())
                    setFloatUniform("falloff", falloff)
                }
            } else {
                null
            }
        }
    }

    /** 环境高光：柔和整体提亮。 */
    @Immutable
    data class Ambient(
        val intensity: Float = 0.38f
    ) : HighlightStyle {
        override val color: Color = Color.White.copy(alpha = intensity)
        override val blendMode: BlendMode = DrawScope.DefaultBlendMode

        override fun DrawScope.createShader(
            shape: Shape,
            runtimeShaderCache: RuntimeShaderCache
        ): RuntimeShader? {
            return if (isRuntimeShaderSupported()) {
                runtimeShaderCache.obtainRuntimeShader(
                    "Ambient",
                    AmbientHighlightShader
                ).apply {
                    setFloatUniform("size", size.width, size.height)
                    setFloatUniform("cornerRadii", getCornerRadii(shape))
                    setFloatUniform("angle", 45f * (PI / 180f).toFloat())
                    setFloatUniform("falloff", 1f)
                }
            } else {
                null
            }
        }
    }

    companion object {
        @Stable
        val Default: Default = Default()

        @Stable
        val Ambient: Ambient = Ambient()

        @Stable
        val Plain: Plain = Plain()
    }
}

/** 从 shape 提取四角半径（供高光 shader 做 SDF）。 */
private fun DrawScope.getCornerRadii(shape: Shape): FloatArray {
    val size = size
    val maxRadius = size.minDimension / 2f
    val shape = shape as? CornerBasedShape ?: return FloatArray(4) { maxRadius }
    val isLtr = layoutDirection == LayoutDirection.Ltr
    val topLeft = if (isLtr) shape.topStart.toPx(size, this) else shape.topEnd.toPx(size, this)
    val topRight = if (isLtr) shape.topEnd.toPx(size, this) else shape.topStart.toPx(size, this)
    val bottomRight = if (isLtr) shape.bottomEnd.toPx(size, this) else shape.bottomStart.toPx(size, this)
    val bottomLeft = if (isLtr) shape.bottomStart.toPx(size, this) else shape.bottomEnd.toPx(size, this)
    return floatArrayOf(
        topLeft.coerceAtMost(maxRadius),
        topRight.coerceAtMost(maxRadius),
        bottomRight.coerceAtMost(maxRadius),
        bottomLeft.coerceAtMost(maxRadius)
    )
}
