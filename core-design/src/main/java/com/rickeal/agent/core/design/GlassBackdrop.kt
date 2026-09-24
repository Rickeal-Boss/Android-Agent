package com.rickeal.agent.core.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 背景光斑场中的一个光斑。归一化坐标（0~1）。
 *
 * 这是「壁纸长什么样」的声明式描述（见 [GlassBackdrop]）。
 * 玻璃的真实背景模糊由新引擎承担：壁纸经 `GlassScaffold` 的 `layerBackdrop`
 * 录制进 [com.rickeal.agent.core.design.liquid.backdrops.LayerBackdrop]，
 * 玻璃节点（`liquidGlass`）通过 `LocalBackdrop` 取用并做模糊 / 折射 / 色散。
 */
@Immutable
data class GlassBlob(
    val x: Float,
    val y: Float,
    val radiusFraction: Float,
    val color: Color,
)

/**
 * 壁纸光斑场：`GlassScaffold` 铺底时按屏幕尺寸缩放绘制。
 * 玻璃节点通过 `LocalBackdrop`（新引擎）把这块壁纸当作真实背景采样。
 */
@Immutable
data class GlassBackdrop(
    val blobs: List<GlassBlob> = GlassWallpaperDefaults.blobs,
)

object GlassWallpaperDefaults {
    /**
     * 光斑场。
     *
     * 原为 4 个低饱和马卡龙色、半径很大（0.44~0.62）→ 整屏糊成一片柔和浅色，
     * 折射"移动像素"在上面**完全看不出来**。
     *
     * 改为 7 个、更饱和、半径更小（0.26~0.44）：让背景有明确的色彩块与边界，
     * 玻璃边缘压过去时折射与色散才有东西可"弯折"。
     * 这是让液态玻璃看起来像液态玻璃的**前提条件**，不是锦上添花。
     */
    val blobs: List<GlassBlob> = listOf(
        GlassBlob(0.14f, 0.10f, 0.42f, Color(0xFF5B7BFF)),
        GlassBlob(0.86f, 0.22f, 0.36f, Color(0xFFB45CFF)),
        GlassBlob(0.30f, 0.44f, 0.30f, Color(0xFF4FE3C8)),
        GlassBlob(0.72f, 0.62f, 0.34f, Color(0xFFFF6FA8)),
        GlassBlob(0.08f, 0.72f, 0.38f, Color(0xFFFFB04F)),
        GlassBlob(0.92f, 0.90f, 0.26f, Color(0xFF4FD0FF)),
        GlassBlob(0.48f, 0.92f, 0.44f, Color(0xFF8A6BFF)),
    )
}
