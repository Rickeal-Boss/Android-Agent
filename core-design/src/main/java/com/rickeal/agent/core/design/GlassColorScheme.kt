package com.rickeal.agent.core.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 玻璃配色。浅色优先，但深色必须同样精致（不是简单反色）。
 */
@Immutable
data class GlassColorScheme(
    /** 玻璃本体色（会乘以 material.backgroundAlpha） */
    val glassTint: Color,
    val glassTintElevated: Color,
    /** 内描边：顶部亮 */
    val glassBorderTop: Color,
    /** 内描边：底部暗 */
    val glassBorderBottom: Color,
    /** 折射高光 */
    val glassSpecular: Color,
    val glassShadow: Color,
    /** 玻璃上的主文本 */
    val onGlass: Color,
    val onGlassMuted: Color,
    val onGlassSubtle: Color,
    /**
     * 覆盖层（抽屉 / 工作区面板 / 推理参数 sheet / 对话框）上的主文本。
     *
     * **不是 onGlass 的直接搬运**：onGlass 随主题翻转（浅色深字 / 深色白字），
     * 而覆盖层表面**恒为深色** —— THICK 玻璃 + 内 scrim + 平台 dim 三层压暗，
     * 浅色模式下实测面板亮度仍只有 #59~#7F（overlayOpacity 0.02~0.45 全量程
     * 几乎相同）⇒ 深色 onGlass 在这些面板上对比度仅约 3.9:1，标题 / 正文不可读。
     * 故 onOverlay 恒取白色族，light / dark 两套同值；dark 模式原 onGlass 本就
     * 是白族，统一后观感不变（刻意收敛）。
     */
    val onOverlay: Color,
    /** 覆盖层上的次级文本（同族白色降 alpha）。 */
    val onOverlayMuted: Color,
    /** 覆盖层上的弱文本（同族白色再降 alpha）。 */
    val onOverlaySubtle: Color,
    /** 强调色（发送按钮、选中态） */
    val accent: Color,
    val accentMuted: Color,
    val onAccent: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    /** 主题底色（2026-09-26 起壁纸为纯色，三段仅保留历史命名；Material background 用 Top） */
    val wallpaperTop: Color,
    val wallpaperMid: Color,
    val wallpaperBottom: Color,
    val isDark: Boolean,
)

fun lightGlassColorScheme(): GlassColorScheme = GlassColorScheme(
    glassTint = Color(0xFFFCFCFE),
    glassTintElevated = Color(0xFFF2F3F8),
    glassBorderTop = Color(0xFFFFFFFF),
    glassBorderBottom = Color(0x33000000),
    glassSpecular = Color(0xFFFFFFFF),
    glassShadow = Color(0x1F0B1B3A),
    onGlass = Color(0xFF10121A),
    // 次级/弱文本的 alpha 不能再压：浅色玻璃叠在浅色壁纸上后，
    // 0x99（60%）≈ #8A8A8E、0x66（40%）≈ #96969A —— 后者对浅色底只有约 2.6:1，
    // 而它承担着大量 11sp labelSmall（小字号没有 3:1 的放宽，必须 4.5:1）。
    // 提到 0xCC / 0xB3 后实测约 8:1 / 6.6:1，达标。
    // 深色那套（0xB3 / 0x80 的白）本来就 ≈4.7:1，不动。
    onGlassMuted = Color(0xCC10121A),
    onGlassSubtle = Color(0xB310121A),
    // 覆盖层文字恒白（UI-1019 Batch 1）：覆盖层面板实测亮度 #59~#7F
    // （THICK 玻璃 + 内 scrim + 平台 dim，overlayOpacity 全量程几乎相同），
    // 深色 onGlass 在其上对比度仅约 3.9:1。理由与判据见 onOverlay 的 KDoc。
    onOverlay = Color(0xFFFFFFFF),
    onOverlayMuted = Color(0xB3FFFFFF),
    onOverlaySubtle = Color(0x8FFFFFFF),
    accent = Color(0xFF2B6BFF),
    accentMuted = Color(0x332B6BFF),
    onAccent = Color(0xFFFFFFFF),
    success = Color(0xFF1E9E62),
    warning = Color(0xFFD98314),
    danger = Color(0xFFE0403F),
    // Wave 9「浅色米白」：暖调米白（米黄 → 米白 → 米灰）。2026-09-26 起 GlassWallpaper
    // 改画**纯色**（取 wallpaperMid，用户需求"默认只米白色"，光斑场已去除），
    // 三段值保留：Material background 仍取 wallpaperTop，且留作回退。
    // 对比度已核算：onGlass(0xFF10121A) ≈15:1、onGlassSubtle(0xB3) ≈5.5:1，均达标。
    wallpaperTop = Color(0xFFFAF6ED),
    wallpaperMid = Color(0xFFF6F1E4),
    wallpaperBottom = Color(0xFFF1EFE4),
    isDark = false,
)

fun darkGlassColorScheme(): GlassColorScheme = GlassColorScheme(
    glassTint = Color(0xFF15161C),
    glassTintElevated = Color(0xFF22242E),
    glassBorderTop = Color(0x66FFFFFF),
    glassBorderBottom = Color(0x14000000),
    glassSpecular = Color(0xB3FFFFFF),
    glassShadow = Color(0x66000000),
    onGlass = Color(0xFFF4F5FA),
    onGlassMuted = Color(0xB3F4F5FA),
    onGlassSubtle = Color(0x80F4F5FA),
    // 覆盖层文字恒白：与 light 同值（覆盖层表面恒深色，见 onOverlay KDoc）。
    // dark 原 onGlass 本就是白族（#F4F5FA / 0xB3 / 0x80），统一为纯白族后
    // 主文本 #F4F5FA→#FFFFFF、弱文本 0x80→0x8F，亮度微升，属刻意收敛。
    onOverlay = Color(0xFFFFFFFF),
    onOverlayMuted = Color(0xB3FFFFFF),
    onOverlaySubtle = Color(0x8FFFFFFF),
    accent = Color(0xFF6E9BFF),
    accentMuted = Color(0x3D6E9BFF),
    onAccent = Color(0xFF0B0D12),
    success = Color(0xFF4FD08A),
    warning = Color(0xFFEBA94A),
    danger = Color(0xFFFF736F),
    wallpaperTop = Color(0xFF0B1020),
    wallpaperMid = Color(0xFF1A1430),
    wallpaperBottom = Color(0xFF071620),
    isDark = true,
)

/**
 * 覆盖层配色：文本族换成恒白的 [GlassColorScheme.onOverlay] 族。
 *
 * 用于抽屉 / 工作区面板 / 推理参数 sheet / 对话框这类**表面恒深色**的覆盖层：
 * 它们在浅色主题下也渲染为深灰板（THICK 玻璃 + 内 scrim + 平台 dim，实测亮度
 * #59~#7F，overlayOpacity 全量程几乎相同），onGlass 的深色文字对比度仅约
 * 3.9:1。只 copy onGlass 三件套 —— accent / glassShadow / glassTint 等一律
 * 不动，故 scrim、强调色、玻璃本体观感零变化。
 *
 * 典型用法（放在玻璃表面绘制之后、内容之前，不要包到玻璃修饰符外面）：
 * `CompositionLocalProvider(LocalGlassColors provides colors.asOverlayColors())`
 *
 * 幂等：对已覆盖化的 scheme 再调一次结果不变（onOverlay 字段不被 copy 触碰）。
 */
fun GlassColorScheme.asOverlayColors(): GlassColorScheme =
    copy(onGlass = onOverlay, onGlassMuted = onOverlayMuted, onGlassSubtle = onOverlaySubtle)
