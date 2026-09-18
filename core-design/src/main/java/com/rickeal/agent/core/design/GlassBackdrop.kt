package com.rickeal.agent.core.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 背景光斑场中的一个光斑。归一化坐标（0~1）。
 */
@Immutable
data class GlassBlob(
    val x: Float,
    val y: Float,
    val radiusFraction: Float,
    val color: Color,
)

/**
 * 背景光斑场。玻璃表面绘制时按自身尺寸缩放同一组光斑，
 * 从而在视觉上"透出背后的壁纸" —— 这就是我们的「背景内容联动模糊」方案。
 *
 * 好处：0 个不确定 API，100% 可编译，且在任何 API 级别表现一致。
 */
@Immutable
data class GlassBackdrop(
    val blobs: List<GlassBlob> = GlassWallpaperDefaults.blobs,
)

object GlassWallpaperDefaults {
    val blobs: List<GlassBlob> = listOf(
        GlassBlob(0.18f, 0.12f, 0.55f, Color(0xFF6E8BFF)),
        GlassBlob(0.82f, 0.28f, 0.48f, Color(0xFFB87BFF)),
        GlassBlob(0.32f, 0.78f, 0.62f, Color(0xFF5AD6C8)),
        GlassBlob(0.72f, 0.86f, 0.44f, Color(0xFFFF9BC2)),
    )
}
