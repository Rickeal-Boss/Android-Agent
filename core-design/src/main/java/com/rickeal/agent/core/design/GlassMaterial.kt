package com.rickeal.agent.core.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 玻璃材质分层。差异体现在「底色 alpha + 真实背景模糊半径 + 高光强度」，不是简单的深浅。
 * 使用规范见 docs/01-architecture.md §8.10。
 */
@Immutable
enum class GlassMaterial {
    ULTRA_THIN,
    THIN,
    REGULAR,
    THICK,
    OPAQUE,
}

@Immutable
data class GlassMaterialSpec(
    /** 材质底色的不透明度（决定"玻璃有多厚"） */
    val backgroundAlpha: Float,
    /**
     * 真实背景模糊半径（dp）。直接喂给 `BlurEffect`，
     * 与材质厚度正相关：越厚的玻璃，背景被磨得越平。
     * 静态值 —— 不做逐帧动画（动画半径会逐帧重建 RenderEffect，开销随半径×面积增长）。
     */
    val blurRadius: Dp,
    /** 内描边（顶亮底暗）的峰值 alpha */
    val borderAlpha: Float,
    /** 方向性边缘光的峰值 alpha */
    val specularAlpha: Float,
    /** 噪点 alpha */
    val noiseAlpha: Float,
    /** 外阴影高度 */
    val shadowElevation: Dp,
)

object GlassMaterials {
    val UltraThin = GlassMaterialSpec(
        backgroundAlpha = 0.14f,
        blurRadius = 14.dp,
        borderAlpha = 0.30f,
        specularAlpha = 0.16f,
        noiseAlpha = 0.020f,
        shadowElevation = 2.dp,
    )
    val Thin = GlassMaterialSpec(
        backgroundAlpha = 0.22f,
        blurRadius = 20.dp,
        borderAlpha = 0.38f,
        specularAlpha = 0.20f,
        noiseAlpha = 0.026f,
        shadowElevation = 4.dp,
    )
    val Regular = GlassMaterialSpec(
        backgroundAlpha = 0.34f,
        blurRadius = 28.dp,
        borderAlpha = 0.50f,
        specularAlpha = 0.28f,
        noiseAlpha = 0.032f,
        shadowElevation = 8.dp,
    )
    val Thick = GlassMaterialSpec(
        backgroundAlpha = 0.52f,
        blurRadius = 36.dp,
        borderAlpha = 0.62f,
        specularAlpha = 0.34f,
        noiseAlpha = 0.038f,
        shadowElevation = 16.dp,
    )
    val Opaque = GlassMaterialSpec(
        backgroundAlpha = 0.92f,
        blurRadius = 40.dp,
        borderAlpha = 0.18f,
        specularAlpha = 0.10f,
        noiseAlpha = 0.016f,
        shadowElevation = 24.dp,
    )

    fun of(material: GlassMaterial): GlassMaterialSpec = when (material) {
        GlassMaterial.ULTRA_THIN -> UltraThin
        GlassMaterial.THIN -> Thin
        GlassMaterial.REGULAR -> Regular
        GlassMaterial.THICK -> Thick
        GlassMaterial.OPAQUE -> Opaque
    }
}
