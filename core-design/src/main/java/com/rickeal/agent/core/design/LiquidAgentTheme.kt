package com.rickeal.agent.core.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * SF 风格排版层级：
 *  - 大标题负字距（-0.6sp ~ -0.3sp）
 *  - 正文 +0.1sp
 *  - 次级文字降低对比（颜色）而非降低字号
 */
val LiquidTypography = Typography(
    displayLarge = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
    displayMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    displaySmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    headlineMedium = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0f.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.1.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.1.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.1.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
)

@Composable
fun LiquidAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    glassConfig: GlassConfig = GlassConfig(),
    backdrop: GlassBackdrop = GlassBackdrop(),
    tokens: GlassTokens = GlassDefaults.Tokens,
    content: @Composable () -> Unit,
) {
    val glassColors = if (darkTheme) darkGlassColorScheme() else lightGlassColorScheme()
    val materialColors = if (darkTheme) {
        darkColorScheme(
            primary = glassColors.accent,
            onPrimary = glassColors.onAccent,
            surface = Color.Transparent,
            background = glassColors.wallpaperTop,
            onBackground = glassColors.onGlass,
        )
    } else {
        lightColorScheme(
            primary = glassColors.accent,
            onPrimary = glassColors.onAccent,
            surface = Color.Transparent,
            background = glassColors.wallpaperTop,
            onBackground = glassColors.onGlass,
        )
    }
    CompositionLocalProvider(
        LocalGlassColors provides glassColors,
        LocalGlassConfig provides glassConfig,
        LocalGlassBackdrop provides backdrop,
        LocalGlassTokens provides tokens,
        LocalLiquidMotion provides if (glassConfig.reduceMotion) LiquidMotion.Gentle else LiquidMotion.Default,
    ) {
        MaterialTheme(
            colorScheme = materialColors,
            typography = LiquidTypography,
            content = content,
        )
    }
}
