package com.rickeal.agent.core.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 设计令牌。所有尺寸/圆角/描边都从这里取，禁止在 UI 里写魔法数字。
 */
@Immutable
data class GlassTokens(
    // ---- 圆角（连续曲率阶梯；W79 各档约 −15% 收敛，radiusFull 不动）----
    val radiusXs: Dp = 7.dp,
    val radiusSm: Dp = 12.dp,
    val radiusMd: Dp = 16.dp,
    val radiusLg: Dp = 24.dp,
    val radiusXl: Dp = 30.dp,
    val radiusFull: Dp = 999.dp,
    // ---- 描边 / 高光 ----
    val borderWidth: Dp = 1.dp,
    val highlightStrokeWidth: Dp = 1.5.dp,
    /** 方向性边缘光的衰减带比例（相对形状短边） */
    val specularBandRatio: Float = 0.34f,
    /**
     * 方向性边缘光的光源方位角（度，屏幕坐标系：0° = 正右，90° = 正下，顺时针）。
     * 45° 表示光从左上方射入 —— 玻璃的左上边缘最亮，右下边缘最暗。
     */
    val specularAngle: Float = 45f,
    /** 边缘光向内的衰减强度：越大，光晕越贴边（1 = 线性衰减）。 */
    val specularFalloff: Float = 1f,
    // ---- 模糊 / 折射 ----
    /** 真实背景模糊的兜底半径（玻璃节点用材质自带的 blurRadius） */
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
    /**
     * 卡片↔卡片之间的列表间距（W79）。
     *
     * 刻意**不复用 [gapSm]**：`gapSm`（8dp）34 个消费点里 31 个是**卡内** `Spacer` /
     * `padding`，改它的值会把卡内行距一起放大（与「只放大卡片间距」的需求直接相反）。
     */
    val cardGap: Dp = 14.dp,
    // ---- 控件尺寸 ----
    val minTouchTarget: Dp = 48.dp,
    val topBarHeight: Dp = 56.dp,
    val bottomBarHeight: Dp = 72.dp,
    val fabSize: Dp = 56.dp,
)

object GlassDefaults {
    val Tokens = GlassTokens()

    /**
     * 大圆角（= `GlassTokens.radiusLg`）。
     *
     * ⚠️ 与 `GlassTokens.radiusLg` 是**两个独立字面量**（历史原因），改一处必须同改另一处，
     * 否则同一屏出现两种曲率。W79 同步 28 → 24（回到 `docs/01-architecture.md` 的
     * `GlassCard` 默认 24dp 口径）。
     */
    val RadiusLg = 24.dp
    val ContentPadding = 16.dp
}
