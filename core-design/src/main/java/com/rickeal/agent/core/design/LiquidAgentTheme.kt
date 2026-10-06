package com.rickeal.agent.core.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
 * 把玻璃配色**投影**到 Material3 [ColorScheme]（单一事实源）。
 *
 * ## 为什么需要它（Wave 51 F3）
 *
 * 此前 `darkColorScheme(...)` / `lightColorScheme(...)` 两分支**各只映射 5 个色角色**
 * （primary / onPrimary / surface / background / onBackground），其余角色走 Material3 默认。
 * 其中 **`inverseSurface` 未映射** ⇒ 深色主题下取 M3 默认 `#E6E0E9`（浅色）⇒ Snackbar
 * （`SnackbarHost`，全仓唯一，未传自定义 `snackbar`）在深色主题里渲染成**浅色块**。
 * 两个分支除函数名外逐字相同（复制粘贴形状），本函数抽出后共用一份映射，消除该重复。
 *
 * ## 权威与投影（取值原则）
 *
 * **玻璃色是权威、Material 是投影**：Material 角色只是把玻璃语义映射到 M3 组件消费的槽位，
 * 不是第二套独立配色。逐项：
 *  - `inverseSurface = glassTintElevated` —— 玻璃系统的「抬升面」色（深色 #22242E / 浅色
 *    #F2F3F8）。Snackbar 底色取它 ⇒ 深色下不再是浅色块，且观感与玻璃 elevated 面一致。
 *  - `inverseOnSurface = onGlass` —— 玻璃上的主文本色，保证在 `glassTintElevated` 上可读。
 *  - `onSurface = onGlass` / `onSurfaceVariant = onGlassMuted` —— 主/次文本映射。
 *  - `surfaceVariant = glassTintElevated` —— 次级容器面。
 *  - `error = danger` / `onError = onAccent` —— 玻璃系统无独立 onDanger，取 onAccent
 *    （饱和色上的可读色，深色近黑 / 浅色白）作投影。
 *  - `outline = glassBorderBottom` —— 玻璃系统的描边色（底部暗内描边）。
 *
 * ⚠️ 已知取舍（申报）：浅色主题下 `inverseSurface = glassTintElevated`(#F2F3F8) 与米白壁纸
 * （wallpaperTop #FAF6ED）对比度低 ⇒ 浅色 Snackbar 的**块边界**弱于 M3 默认（深色块），
 * 靠 `inverseOnSurface = onGlass` 保证文字可读。这是「与玻璃设计系统一致」换来的取舍。
 *
 * ⚠️ `surface = Color.Transparent` 沿用原值：玻璃底本就透出壁纸，Material surface 不另铺色。
 */
private fun bridgeGlassToMaterial(glassColors: GlassColorScheme, dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = glassColors.accent,
        onPrimary = glassColors.onAccent,
        surface = Color.Transparent,
        background = glassColors.wallpaperTop,
        onBackground = glassColors.onGlass,
        onSurface = glassColors.onGlass,
        onSurfaceVariant = glassColors.onGlassMuted,
        surfaceVariant = glassColors.glassTintElevated,
        inverseSurface = glassColors.glassTintElevated,
        inverseOnSurface = glassColors.onGlass,
        error = glassColors.danger,
        onError = glassColors.onAccent,
        outline = glassColors.glassBorderBottom,
    )
}

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
    val materialColors = bridgeGlassToMaterial(glassColors, darkTheme)
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
