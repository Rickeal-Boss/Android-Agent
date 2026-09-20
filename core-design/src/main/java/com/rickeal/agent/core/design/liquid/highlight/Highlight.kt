package com.rickeal.agent.core.design.liquid.highlight

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 高光参数：玻璃边缘那一圈亮边。
 *
 * @param width 描边宽度。默认 0.5dp（很细，模拟玻璃边缘的菲涅尔反射）。
 * @param blurRadius 模糊半径，默认 = width / 2（柔化描边，避免生硬）。
 * @param alpha 整体透明度。可用于按压反馈（按下时 alpha 拉高）。
 * @param style 高光样式（方向性 / 环境 / 纯色）。
 *
 * 端口自 Kyant0 backdrop 库（Apache-2.0）。
 */
@Immutable
data class Highlight(
    val width: Dp = 0.5f.dp,
    val blurRadius: Dp = width / 2f,
    val alpha: Float = 1f,
    val style: HighlightStyle = HighlightStyle.DefaultStyle
) {

    companion object {
        @Stable
        val Default: Highlight = Highlight()

        @Stable
        val Ambient: Highlight = Highlight(style = HighlightStyle.AmbientStyle)

        /** API 31~32 降级：纯色描边，不用 AGSL。 */
        @Stable
        val Plain: Highlight = Highlight(style = HighlightStyle.PlainStyle)
    }
}
