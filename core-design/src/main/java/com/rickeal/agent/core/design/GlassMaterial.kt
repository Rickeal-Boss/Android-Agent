package com.rickeal.agent.core.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 玻璃材质分层。差异体现在「底色 alpha + 真实背景模糊半径 + 高光强度」，不是简单的深浅。
 * 使用规范见 docs/01-architecture.md §8.10。
 *
 * ## ⚠️ 参数已按「液态玻璃」而非「磨砂玻璃」重新标定（真机反馈后修正）
 *
 * 原值是按传统 frosted glass 给的：**重模糊（14~40dp）+ 厚底色（0.34~0.52）**。
 * 这套参数下背景被磨成一坨糊、又被厚底色盖住，折射"移动像素"这件事**根本看不见** ——
 * 这正是「看起来和液态玻璃不一样」的根因。
 *
 * 对照 Kyant0 各组件的实测参数（dp）：
 * | 组件 | blur | lens(高, 强度) | 色散 |
 * |---|---|---|---|
 * | LiquidButton | **2** | 12, 24 | false |
 * | LiquidSlider | **8** | 10, 14 | **true** |
 * | LiquidBottomTabs | **8** | 24, 24 | — |
 * | LiquidToggle | **8** | 5, 10 | **true** |
 *
 * 规律很清楚：**液态玻璃 = 极轻模糊 + 强折射 + 薄底色**。
 * 模糊只是"柔化"，折射才是主角；底色要薄到能让折射透出来。
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
    // blurRadius：从 14~40 降到 3~12，对齐 Kyant0 的 2~8。
    // 模糊只是柔化，必须轻到让折射的"像素位移"看得见。
    // backgroundAlpha：从 0.14~0.92 降到 0.07~0.72。
    // 底色要薄 —— 它每厚一分，折射就被盖掉一分。
    // specularAlpha / borderAlpha 略提：底色变薄后，边缘高光与描边成为"玻璃存在感"的主要来源。
    val UltraThin = GlassMaterialSpec(
        backgroundAlpha = 0.07f,
        blurRadius = 3.dp,
        borderAlpha = 0.34f,
        specularAlpha = 0.22f,
        noiseAlpha = 0.016f,
        shadowElevation = 2.dp,
    )
    val Thin = GlassMaterialSpec(
        backgroundAlpha = 0.11f,
        blurRadius = 5.dp,
        borderAlpha = 0.44f,
        specularAlpha = 0.28f,
        noiseAlpha = 0.020f,
        shadowElevation = 4.dp,
    )
    val Regular = GlassMaterialSpec(
        backgroundAlpha = 0.16f,
        blurRadius = 7.dp,
        borderAlpha = 0.58f,
        specularAlpha = 0.36f,
        noiseAlpha = 0.026f,
        shadowElevation = 8.dp,
    )
    val Thick = GlassMaterialSpec(
        backgroundAlpha = 0.26f,
        blurRadius = 9.dp,
        borderAlpha = 0.72f,
        specularAlpha = 0.44f,
        noiseAlpha = 0.030f,
        shadowElevation = 16.dp,
    )
    val Opaque = GlassMaterialSpec(
        backgroundAlpha = 0.72f,
        blurRadius = 12.dp,
        borderAlpha = 0.24f,
        specularAlpha = 0.14f,
        noiseAlpha = 0.014f,
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
