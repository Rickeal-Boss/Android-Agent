package com.rickeal.agent.core.design.liquid.internal

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorMatrixColorFilter
import androidx.compose.ui.graphics.RenderEffect

/**
 * RenderEffect 链：把已有的 RenderEffect 与新效果链在一起。
 * Compose 的 RenderEffect 不可变，每次 chain 都返回新实例。
 *
 * 端口自 Kyant0 backdrop 库（Apache-2.0）。
 */
internal fun RenderEffect?.chain(other: RenderEffect): RenderEffect {
    return if (this == null) other else RenderEffect.createChainEffect(this, other)!!
}

/**
 * 把 [colorFilter] 应用到 [renderEffect] 的输出上。
 */
internal fun RenderEffect?.applyColorFilter(colorFilter: ColorFilter): RenderEffect {
    val effect = this ?: return RenderEffect.createColorFilterEffect(colorFilter)
    return RenderEffect.createChainEffect(
        effect,
        RenderEffect.createColorFilterEffect(colorFilter)
    )!!
}

/**
 * 在 RenderEffect 上叠 1 个 `RuntimeShaderEffect`（uniform shader）。
 */
internal fun RenderEffect?.applyRuntimeShader(
    runtimeShader: com.rickeal.agent.core.design.liquid.platform.RuntimeShader,
    uniformShaderName: String
): RenderEffect {
    // Compose 的 Shader 在 Android 上就是 android.graphics.Shader（typealias），
    // 可直接传给 RenderEffect.createRuntimeShaderEffect，无需转换。
    val shaderEffect = RenderEffect.createRuntimeShaderEffect(
        runtimeShader.asComposeShader(),
        uniformShaderName
    )
    return chain(shaderEffect!!)
}

/**
 * Vibrancy：饱和度提升 + 轻微亮度提升，让玻璃里的颜色更鲜艳。
 * 这是 iOS 26 Liquid Glass 的标配效果。
 */
internal fun RenderEffect?.vibrancy(saturation: Float = 1.5f, brightness: Float = 0f): RenderEffect {
    val invSat = 1f - saturation
    val r = 0.213f * invSat
    val g = 0.715f * invSat
    val b = 0.072f * invSat

    val c = 1f
    val t = (0.5f - c * 0.5f + brightness) * 255f
    val s = saturation

    val cr = c * r
    val cg = c * g
    val cb = c * b
    val cs = c * s

    val colorMatrix = ColorMatrix(
        floatArrayOf(
            cr + cs, cg, cb, 0f, t,
            cr, cg + cs, cb, 0f, t,
            cr, cg, cb + cs, 0f, t,
            0f, 0f, 0f, 1f, 0f
        )
    )
    return applyColorFilter(ColorMatrixColorFilter(colorMatrix))
}

/**
 * 在 RenderEffect 上叠 alpha 透明度（线性）。
 */
internal fun RenderEffect?.opacity(alpha: Float): RenderEffect {
    val colorMatrix = ColorMatrix(
        floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, alpha, 0f
        )
    )
    return applyColorFilter(ColorMatrixColorFilter(colorMatrix))
}
