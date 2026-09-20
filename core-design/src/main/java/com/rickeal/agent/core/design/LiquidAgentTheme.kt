package com.rickeal.agent.core.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
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

/**
 * 应用主题。
 *
 * ## Material 3 Expressive
 *
 * 走 [MaterialExpressiveTheme] 而非普通 [androidx.compose.material3.MaterialTheme]，
 * 并显式指定 [MotionScheme.expressive] —— 这就是 M3E 的核心差异：
 *  - **标准 M3**：动效用缓动曲线（tween），克制、中性；
 *  - **M3E**：动效用**弹簧**（spring），有过冲与回弹，UI"有生命"；
 *    同时形状更圆润大胆、字号层级差异更明显。
 *
 * 版本依据（已实测核验，非推测）：
 *  BOM 2026.02.00 锁 material3 = **1.4.0**，而 M3E 在 1.4.0 已稳定 ——
 *  解压 material3-android-1.4.0.aar 的 classes.jar 可确认存在
 *  `MaterialExpressiveTheme` / `MotionScheme` / `ExpressiveMotionSchemeImpl` /
 *  `ExperimentalMaterial3ExpressiveApi`。
 *  因此**不需要**升级到 1.5.0-alpha（那会违反 libs.versions.toml 的版本锁定铁律）。
 *
 * [MaterialExpressiveTheme] 与 [androidx.compose.material3.MaterialTheme] 提供同一套
 * CompositionLocal（colorScheme / typography / shapes），所以全项目既有的
 * `MaterialTheme.typography.xxx` 调用点无需任何改动。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
        MaterialExpressiveTheme(
            colorScheme = materialColors,
            typography = LiquidTypography,
            // M3E 的灵魂：弹簧动效。不传则用 M3E 默认的 expressive scheme，
            // 显式写出是为了让"我们用的是 M3E"这件事在代码里可读。
            motionScheme = MotionScheme.expressive(),
            content = content,
        )
    }
}
