package com.rickeal.agent.core.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val LocalGlassTokens = staticCompositionLocalOf { GlassTokens.Default }
val LocalGlassColors = staticCompositionLocalOf { lightGlassColorScheme() }
val LocalGlassConfig = staticCompositionLocalOf { GlassConfig() }
val LocalGlassBackdrop = staticCompositionLocalOf { defaultLightBackdrop() }
val LocalLiquidMotion = staticCompositionLocalOf { LiquidMotion.Default }

/** 便捷访问器：在 @Composable 中通过 `GlassTheme.colors` 取值。 */
object GlassTheme {
    val tokens: GlassTokens
        @Composable @ReadOnlyComposable get() = LocalGlassTokens.current

    val colors: GlassColorScheme
        @Composable @ReadOnlyComposable get() = LocalGlassColors.current

    val config: GlassConfig
        @Composable @ReadOnlyComposable get() = LocalGlassConfig.current

    val backdrop: GlassBackdrop
        @Composable @ReadOnlyComposable get() = LocalGlassBackdrop.current

    val motion: LiquidMotionSpec
        @Composable @ReadOnlyComposable get() = LocalLiquidMotion.current
}

private val BaseTypography = Typography()

/** SF 风格排版层级：标题更重、正文行高更松、大标题收紧字距。 */
val GlassTypography: Typography = BaseTypography.copy(
    displayLarge = BaseTypography.displayLarge.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.6).sp,
    ),
    displayMedium = BaseTypography.displayMedium.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp,
    ),
    titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = BaseTypography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = BaseTypography.bodyLarge.copy(lineHeight = 24.sp),
    labelLarge = BaseTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

/**
 * LiquidAgent 的根主题：在 Material3 之上叠加 Liquid Glass 的五个 CompositionLocal。
 * 组件只依赖 Local*，因此设计系统可以独立于业务模型演进。
 */
@Composable
fun LiquidAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    glassConfig: GlassConfig = GlassConfig(),
    backdrop: GlassBackdrop = defaultBackdrop(darkTheme),
    tokens: GlassTokens = GlassTokens.Default,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) darkGlassColorScheme() else lightGlassColorScheme()
    val motion = if (glassConfig.reduceMotion) LiquidMotion.Gentle else LiquidMotion.Default
    val materialColors = if (darkTheme) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.wallpaperBottom,
            surface = colors.glassTint,
            onBackground = colors.onGlass,
            onSurface = colors.onGlass,
            error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.wallpaperBottom,
            surface = colors.glassTint,
            onBackground = colors.onGlass,
            onSurface = colors.onGlass,
            error = colors.danger,
        )
    }

    CompositionLocalProvider(
        LocalGlassTokens provides tokens,
        LocalGlassColors provides colors,
        LocalGlassConfig provides glassConfig,
        LocalGlassBackdrop provides backdrop,
        LocalLiquidMotion provides motion,
    ) {
        MaterialTheme(
            colorScheme = materialColors,
            typography = GlassTypography,
            content = content,
        )
    }
}
