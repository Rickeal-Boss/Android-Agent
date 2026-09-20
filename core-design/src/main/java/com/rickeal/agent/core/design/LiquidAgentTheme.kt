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
 * M3 Expressive 形状档位。
 *
 * 与标准 M3 的关键差异：**圆角整体更大，且层级跨度更夸张**
 * （标准 M3 是 4/8/12/16/28，M3E 抬到 8/12/16/28/48）。
 * 大圆角 + 液态玻璃的折射边缘是绝配 —— 折射带在圆角处最明显。
 *
 * 这也是我们在 M3E 公开 API 仍为 internal 的情况下，
 * 自己实现 M3E 设计语言的一环（详见 [LiquidAgentTheme] 的 M3E 结论）。
 */
val LiquidShapes = androidx.compose.material3.Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(48.dp),
)

/**
 * SF 风格排版层级：
 *  - 大标题负字距（-0.6sp ~ -0.3sp）
 *  - 正文 +0.1sp
 *  - 次级文字降低对比（颜色）而非降低字号
 *
 * 负字距 + 大号标题也是 M3E 的排版主张（层级差异更明显、更有个性）。
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
 * ## 关于 Material 3 Expressive（重要结论，已实测）
 *
 * M3E 的公开 API **暂时用不了**，两条路都堵死，原因如下（都是 CI 实测，不是推测）：
 *
 *  1. **1.4.0（当前 BOM 锁定版本）**：`MaterialExpressiveTheme` / `MotionScheme` /
 *     `ExperimentalMaterial3ExpressiveApi` 三个类**确实存在**
 *     （解压 material3-android-1.4.0.aar → classes.jar 可 grep 到），
 *     但全部标记为 **`internal`** —— 编译器直接拒绝：
 *     "Cannot access 'fun MaterialExpressiveTheme(...)': it is internal in file"。
 *     错误里还顺带给出了它的完整签名：
 *     `MaterialExpressiveTheme(colorScheme?, motionScheme?, shapes?, typography?, content)`。
 *
 *  2. **1.5.0-alphaXX**：M3E 已作为 `ExperimentalMaterial3ExpressiveApi` 公开，
 *     但至今（2026-09）仍是 alpha，且升级会违反
 *     `libs.versions.toml` 顶部的「版本矩阵锁死、任何人不得升降级」铁律。
 *
 * **因此本项目的取法**：不引 M3E 的 alpha API，而是**用 M3E 的设计语言自己实现** ——
 * M3E 相对标准 M3 的核心差异就三处，我们都能自控：
 *  - **动效**：用弹簧（有过冲回弹）而非缓动曲线 → [LiquidMotion]（本来就是 spring）
 *  - **形状**：更圆润大胆、圆角层级跨度更大 → [GlassTokens] 的 radiusXs…radiusFull
 *  - **字体**：大标题负字距、层级差异更明显 → [LiquidTypography]
 *
 * 若将来 BOM 升到 M3E 稳定版，只需把下面的 `MaterialTheme(...)` 换成
 * `MaterialExpressiveTheme(colorScheme, motionScheme = MotionScheme.expressive(), ...)`，
 * 其余代码零改动（两者提供同一套 CompositionLocal）。
 */
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
            shapes = LiquidShapes,
            content = content,
        )
    }
}
