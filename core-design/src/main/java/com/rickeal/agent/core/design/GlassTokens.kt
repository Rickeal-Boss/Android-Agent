package com.rickeal.agent.core.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 设计令牌。所有尺寸/圆角/描边都从这里取，禁止在 UI 里写魔法数字。
 */
@Immutable
data class GlassTokens(
    // ---- 圆角（连续曲率阶梯）----
    val radiusXs: Dp = 8.dp,
    val radiusSm: Dp = 14.dp,
    val radiusMd: Dp = 20.dp,
    val radiusLg: Dp = 28.dp,
    val radiusXl: Dp = 36.dp,
    val radiusFull: Dp = 999.dp,
    // ---- 描边 / 高光 ----
    val borderWidth: Dp = 1.dp,
    val highlightStrokeWidth: Dp = 1.5.dp,
    val specularBandRatio: Float = 0.34f,
    // ---- 模糊 / 折射 ----
    val blurRadius: Dp = 28.dp,
    val refractionSpread: Dp = 6.dp,
    // ---- 阴影 ----
    val shadowElevation: Dp = 8.dp,
    val ambientShadowAlpha: Float = 0.18f,
    // ---- 内边距 ----
    val paddingXs: Dp = 6.dp,
    val paddingSm: Dp = 10.dp,
    val paddingMd: Dp = 14.dp,
    val paddingLg: Dp = 18.dp,
    // ---- 间距 ----
    val gapSm: Dp = 8.dp,
    val gapMd: Dp = 12.dp,
    val gapLg: Dp = 20.dp,
    // ---- 控件尺寸 ----
    val minTouchTarget: Dp = 48.dp,
    val topBarHeight: Dp = 56.dp,
    val bottomBarHeight: Dp = 72.dp,
    val fabSize: Dp = 56.dp,
)

object GlassDefaults {
    val Tokens = GlassTokens()
    val RadiusLg = 28.dp
    val ContentPadding = 16.dp
}
