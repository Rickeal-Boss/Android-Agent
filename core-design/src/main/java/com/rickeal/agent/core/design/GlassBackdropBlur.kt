package com.rickeal.agent.core.design

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * P2 增强：真实背景模糊（Android 12+ `RenderEffect.createBlurEffect`）。
 *
 * **可删文件**：整块能力只在这一处，且 `GlassConfig.enableBackdropBlur` 默认 false。
 * 若 CI 报 `renderEffect` unresolved，直接删除本文件并把 `LiquidGlassModifier.kt` 里
 * 对 `glassBackdropBlur` 的那一次调用删掉即可，其余代码零影响。
 *
 * 注意调用顺序：它必须**先于** `liquidGlass` 应用 —— 这样被模糊的是「背后的内容」，
 * 而玻璃底色 / 折射高光 / 噪点仍画在清晰层上，观感才对。
 */
fun Modifier.glassBackdropBlur(
    radius: Dp,
    cornerRadius: Dp = GlassDefaults.RadiusLg,
    enabled: Boolean = true,
): Modifier {
    if (!enabled || radius <= 0.dp) return this
    if (Build.VERSION.SDK_INT < 31) return this
    return this.graphicsLayer {
        // 注意：这里必须用 Compose 自己的 RenderEffect（androidx.compose.ui.graphics），
        // 不是 android.graphics.RenderEffect —— graphicsLayer 的 renderEffect 属性是前者。
        renderEffect = android.graphics.RenderEffect.createBlurEffect(
                radius.toPx(),
                radius.toPx(),
                android.graphics.Shader.TileMode.CLAMP,
            )
            .asComposeRenderEffect()
        shape = RoundedCornerShape(cornerRadius)
        clip = true
    }
}
