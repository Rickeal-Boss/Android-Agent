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
 *
 * ## 「容纳大量文本的容器」该选哪一档（HIG materials：大量文本组件必须用 regular 变体）
 *
 * ULTRA_THIN（blur 3）压不住背后的动态光斑，长文滚动/展开时字面会发飘。判据三条：
 *
 * 1. **文本量**：容器内正文 **≥3 行**（`maxLines ≥ 3` 或需滚动）→ **禁用 ULTRA_THIN**。
 * 2. **性能**：容器在 `LazyColumn` / `LazyVerticalGrid` 的 **item 内** → 取 **THIN**
 *    （下限达标即止；模糊成本 ×N，逐条渲染下 blur 7 会明显吃帧）。
 *    **独立容器**（协议卡、弹窗、详情正文）→ 取 **REGULAR**。
 *    THIN 与 REGULAR 的 alpha 几乎同量级（0.21 / 0.22，Wave 9 调整后方向已捋正），
 *    真正的差别是 **blur 5 → 7**，这一步才是"把光斑磨平"的关键。
 * 3. **豁免**：功能层（顶栏 / 底栏 / 页签 / FAB / 开关 / 分段）、纯图像容器、
 *    单行文本（chip、气泡标题）—— 不适用本判据。功能层本来就该是悬浮玻璃，
 *    按质感选档，不要拿文本量去约束它。
 *
 * ## ⚠️ 同档材质**嵌套**时，层次信号来自描边 / 高光，不是底色
 *
 * ThinkingBlock 抬到 THIN 后，它外层那条 AI 气泡也是 THIN（气泡是
 * `isUser ? REGULAR : THIN`）⇒ 两者 `backgroundAlpha` **完全相同**（0.21），
 * 底色不提供任何层次。接缝靠的是 `borderAlpha` / `specularAlpha` —— 这俩才是真正的
 * 档位信号（THIN 0.44 / 0.28 → REGULAR 0.58 / 0.36，逐档递增）。
 *
 * 后人再往同档容器里叠同档容器时，别指望"换个材质档"能分出层次：要么靠描边 / 高光，
 * 要么让内外层不同档，否则就是一块看不出边界的玻璃。
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
    /**
     * 折射带高度（dp）：从边缘向内算，玻璃"厚度渐变"的范围。
     * 逐材质给默认值；具体组件可用 `liquidGlass(refractionHeight = ...)` 覆盖。
     */
    val refractionHeight: Dp = 12.dp,
    /**
     * 折射强度（dp）：背景被弯折的像素位移量。越大越"鼓"。
     * Kyant0 各组件的 amount 通常 ≥ height（12→24、24→24、10→14、5→10），比例约 1.4~2x；
     * amount < height 会让折射带又窄又弱，看起来没效果。
     */
    val refractionAmount: Dp = 24.dp,
)

object GlassMaterials {
    // blurRadius：从 14~40 降到 3~12，对齐 Kyant0 的 2~8。
    // 模糊只是柔化，必须轻到让折射的"像素位移"看得见。
    // backgroundAlpha：从 0.14~0.92 降到 0.09~0.72。
    // 底色要薄 —— 它每厚一分，折射就被盖掉一分。
    // specularAlpha / borderAlpha 略提：底色变薄后，边缘高光与描边成为"玻璃存在感"的主要来源。
    //
    // ⚠️ Wave 9「卡片更实」：五档 alpha 0.07/0.18/0.16/0.34 → 0.09/0.21/0.22/0.36（Opaque 0.72 不动）。
    // 真机反馈卡片在亮背景下"压不住"、内容发飘 —— 整体加厚一档。顺带捋直了
    // Thin(0.18) > Regular(0.16) 的历史倒挂：底色序列现在单调递增
    // 0.09 → 0.21 → 0.22 → 0.36 → 0.72，档位语义与数值方向一致。
    // blur / border / specular / noise / 折射全部不动，只动 backgroundAlpha 这一个旋钮
    // —— 波及面收在一处，真机不满意只回退一个参数。
    //
    // ⚠️ 退路（真机回看后若 REGULAR 折射被底色盖掉过多）：Regular 0.22 → 0.20，
    // 同时 refractionAmount 24 → 28（用折射强度补偿底色变薄，而不是简单减底色）。
    val UltraThin = GlassMaterialSpec(
        backgroundAlpha = 0.09f,
        blurRadius = 3.dp,
        borderAlpha = 0.34f,
        specularAlpha = 0.22f,
        noiseAlpha = 0.016f,
        shadowElevation = 2.dp,
        refractionHeight = 8.dp,
        refractionAmount = 14.dp,
    )
    val Thin = GlassMaterialSpec(
        // 0.11 → 0.18（Wave 8 真机反馈）→ 0.21（Wave 9「卡片更实」）。
        // blur / lens / 折射一律不动，只加厚底色这一层。
        backgroundAlpha = 0.21f,
        blurRadius = 5.dp,
        borderAlpha = 0.44f,
        specularAlpha = 0.28f,
        noiseAlpha = 0.020f,
        shadowElevation = 4.dp,
        refractionHeight = 10.dp,
        refractionAmount = 18.dp,
    )
    // 12/24 就是 Kyant0 LiquidButton 的 lens(12f.dp, 24f.dp)，作为全局默认档。
    // 0.16 → 0.22（Wave 9「卡片更实」）：原值比 Thin(0.18) 还薄，是历史倒挂 ——
    // 名义上"常规档"却比 THIN 更透，本波一并捋直。退路见上方注释。
    val Regular = GlassMaterialSpec(
        backgroundAlpha = 0.22f,
        blurRadius = 7.dp,
        borderAlpha = 0.58f,
        specularAlpha = 0.36f,
        noiseAlpha = 0.026f,
        shadowElevation = 8.dp,
        refractionHeight = 12.dp,
        refractionAmount = 24.dp,
    )
    val Thick = GlassMaterialSpec(
        // 0.26 → 0.34（Wave 8 真机反馈）→ 0.36（Wave 9「卡片更实」）。
        // 卡片（GlassCard / 弹层）多走 Thick，底色加厚后折射仍在（refractionAmount 未动）。
        backgroundAlpha = 0.36f,
        blurRadius = 9.dp,
        borderAlpha = 0.72f,
        specularAlpha = 0.44f,
        noiseAlpha = 0.030f,
        shadowElevation = 16.dp,
        refractionHeight = 16.dp,
        refractionAmount = 28.dp,
    )
    val Opaque = GlassMaterialSpec(
        backgroundAlpha = 0.72f,
        blurRadius = 12.dp,
        borderAlpha = 0.24f,
        specularAlpha = 0.14f,
        noiseAlpha = 0.014f,
        shadowElevation = 24.dp,
        refractionHeight = 20.dp,
        refractionAmount = 32.dp,
    )

    fun of(material: GlassMaterial): GlassMaterialSpec = when (material) {
        GlassMaterial.ULTRA_THIN -> UltraThin
        GlassMaterial.THIN -> Thin
        GlassMaterial.REGULAR -> Regular
        GlassMaterial.THICK -> Thick
        GlassMaterial.OPAQUE -> Opaque
    }
}
