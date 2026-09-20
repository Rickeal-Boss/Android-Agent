package com.rickeal.agent.core.design.liquid.shadow

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.graphics.lerp

/**
 * 内阴影：在玻璃**内侧**边缘画一圈暗边，做出"厚度"感。
 *
 * 这是液态玻璃"有实体"的关键 —— 只有外阴影会显得像贴纸，
 * 加上内阴影才有"一块有厚度的玻璃"的错觉。
 *
 * 端口自 Kyant0 backdrop 库（Apache-2.0）。
 */
@Immutable
data class InnerShadow(
    val radius: Dp = 24f.dp,
    val offset: DpOffset = DpOffset(0f.dp, radius),
    val color: Color = Color.Black.copy(alpha = 0.15f),
    val alpha: Float = 1f,
    val blendMode: BlendMode = DrawScope.DefaultBlendMode
) {

    companion object {
        @Stable
        val Default: InnerShadow = InnerShadow()
    }
}

@Stable
fun lerp(start: InnerShadow, stop: InnerShadow, fraction: Float): InnerShadow {
    return InnerShadow(
        radius = lerp(start.radius, stop.radius, fraction),
        offset = lerp(start.offset, stop.offset, fraction),
        color = lerp(start.color, stop.color, fraction),
        // Float 的 lerp 在 androidx.compose.ui.util（不是 unit/graphics），
        // 这里直接手算，避免引入第三个同名 lerp 造成解析歧义。
        alpha = start.alpha + (stop.alpha - start.alpha) * fraction,
        blendMode = if (fraction < 0.5f) start.blendMode else stop.blendMode
    )
}
