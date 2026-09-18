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
    /** 强调色（发送按钮、选中态） */
    val accent: Color,
    val accentMuted: Color,
    val onAccent: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    /** 壁纸三色渐变 */
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
    onGlassMuted = Color(0x9910121A),
    onGlassSubtle = Color(0x6610121A),
    accent = Color(0xFF2B6BFF),
    accentMuted = Color(0x332B6BFF),
    onAccent = Color(0xFFFFFFFF),
    success = Color(0xFF1E9E62),
    warning = Color(0xFFD98314),
    danger = Color(0xFFE0403F),
    wallpaperTop = Color(0xFFE9EDFB),
    wallpaperMid = Color(0xFFF6EFFA),
    wallpaperBottom = Color(0xFFE7F1FA),
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
