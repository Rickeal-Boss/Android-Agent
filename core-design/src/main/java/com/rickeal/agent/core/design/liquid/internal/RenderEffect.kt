package com.rickeal.agent.core.design.liquid.internal

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asAndroidColorFilter
import androidx.compose.ui.graphics.asComposeRenderEffect
import com.rickeal.agent.core.design.liquid.platform.RuntimeShader
import com.rickeal.agent.core.design.liquid.platform.asAndroidRuntimeShader

/**
 * 把两个 RenderEffect 链在一起。
 *
 * ⚠️ Compose 的 [RenderEffect] 只是包装类，**工厂方法全在平台侧**
 * `android.graphics.RenderEffect` 上，必须来回转换：
 *   - compose → platform：[asAndroidRenderEffect]
 *   - platform → compose：[asComposeRenderEffect]
 *
 * 顺序：新效果 [other] 在外层，已有效果在里层（与 Kyant0 完全一致）。
 *
 * 端口自 Kyant0 backdrop 库（Apache-2.0）。
 */
@RequiresApi(Build.VERSION_CODES.S)
internal fun RenderEffect?.chain(other: RenderEffect): RenderEffect {
    return if (this != null) {
        android.graphics.RenderEffect.createChainEffect(
            other.asAndroidRenderEffect(),
            this.asAndroidRenderEffect()
        ).asComposeRenderEffect()
    } else {
        other
    }
}

/**
 * 在 RenderEffect 上叠一层 ColorFilter（vibrancy / opacity 都走这里）。
 */
@RequiresApi(Build.VERSION_CODES.S)
internal fun RenderEffect?.applyColorFilter(colorFilter: ColorFilter): RenderEffect {
    return if (this != null) {
        android.graphics.RenderEffect.createColorFilterEffect(
            colorFilter.asAndroidColorFilter(),
            this.asAndroidRenderEffect()
        ).asComposeRenderEffect()
    } else {
        android.graphics.RenderEffect.createColorFilterEffect(
            colorFilter.asAndroidColorFilter()
        ).asComposeRenderEffect()
    }
}

/**
 * 在 RenderEffect 上叠一层 AGSL RuntimeShader（lens 折射 / 色散走这里）。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun RenderEffect?.applyRuntimeShader(
    runtimeShader: RuntimeShader,
    uniformShaderName: String
): RenderEffect {
    return chain(
        android.graphics.RenderEffect.createRuntimeShaderEffect(
            runtimeShader.asAndroidRuntimeShader(),
            uniformShaderName
        ).asComposeRenderEffect()
    )
}

/**
 * **Vibrancy（鲜艳度提升）** —— iOS 26 Liquid Glass 的标配。
 *
 * 玻璃会"吸走"背景饱和度看起来发灰，用 ColorMatrix 把饱和度拉回来，
 * 让透过玻璃看到的颜色依然鲜活。这是"廉价磨砂"与"高级液态玻璃"的分水岭。
 */
@RequiresApi(Build.VERSION_CODES.S)
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

    val colorMatrix = androidx.compose.ui.graphics.ColorMatrix(
        floatArrayOf(
            cr + cs, cg, cb, 0f, t,
            cr, cg + cs, cb, 0f, t,
            cr, cg, cb + cs, 0f, t,
            0f, 0f, 0f, 1f, 0f
        )
    )
    return applyColorFilter(androidx.compose.ui.graphics.ColorMatrixColorFilter(colorMatrix))
}

/**
 * 在 RenderEffect 上叠线性 alpha 透明度。
 */
@RequiresApi(Build.VERSION_CODES.S)
internal fun RenderEffect?.opacity(alpha: Float): RenderEffect {
    val colorMatrix = androidx.compose.ui.graphics.ColorMatrix(
        floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, alpha, 0f
        )
    )
    return applyColorFilter(androidx.compose.ui.graphics.ColorMatrixColorFilter(colorMatrix))
}
