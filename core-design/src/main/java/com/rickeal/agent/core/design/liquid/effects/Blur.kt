package com.rickeal.agent.core.design.liquid.effects

import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import com.rickeal.agent.core.design.liquid.BackdropEffectScope
import com.rickeal.agent.core.design.liquid.platform.isRenderEffectSupported

/**
 * 背景高斯模糊。
 *
 * @param radius 模糊半径（px）。必须 > 0 才生效。
 * @param edgeTreatment 边缘处理。`TileMode.Clamp` 时边缘不扩边；
 *   其它模式（或已有其它 effect 时）需要额外 padding，否则边缘会被截断。
 *
 * 端口自 Kyant0 backdrop 库（Apache-2.0）。
 */
fun BackdropEffectScope.blur(
    radius: Float,
    edgeTreatment: TileMode = TileMode.Clamp
) {
    if (!isRenderEffectSupported()) return
    if (radius <= 0f) return

    if (edgeTreatment != TileMode.Clamp || renderEffect != null) {
        if (radius > padding) {
            padding = radius
        }
    }

    renderEffect = BlurEffect(
        renderEffect,
        radius,
        radius,
        edgeTreatment
    )
}
