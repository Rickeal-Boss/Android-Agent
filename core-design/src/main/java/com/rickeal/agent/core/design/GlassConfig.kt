package com.rickeal.agent.core.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 玻璃渲染开关。全部由设置页驱动，一处改动全局生效。
 */
@Immutable
data class GlassConfig(
    /**
     * 真实背景模糊（`GraphicsLayer.record` + `BlurEffect`，纯 Compose 实现）。
     * 默认开启：minSdk 31 起 `RenderEffect.isSupported()` 恒为 true，无需运行时判断。
     * 关掉则玻璃只叠加底色 / 内描边 / 边缘光。
     */
    val enableBackdropBlur: Boolean = true,
    val enableNoise: Boolean = true,
    val enableSpecular: Boolean = true,
    val reduceMotion: Boolean = false,
    /** 全局材质强度 0.5~1.5，设置页可调 */
    val intensity: Float = 1f,
)

val LocalGlassTokens: ProvidableCompositionLocal<GlassTokens> =
    staticCompositionLocalOf { GlassDefaults.Tokens }

val LocalGlassColors: ProvidableCompositionLocal<GlassColorScheme> =
    staticCompositionLocalOf { lightGlassColorScheme() }

val LocalGlassConfig: ProvidableCompositionLocal<GlassConfig> =
    staticCompositionLocalOf { GlassConfig() }

val LocalGlassBackdrop: ProvidableCompositionLocal<GlassBackdrop> =
    staticCompositionLocalOf { GlassBackdrop() }

/**
 * 背景录制层。由 `GlassScaffold` 在壁纸那一层提供，玻璃节点据此做真实背景模糊。
 * 为 null 表示当前子树没有可用的背景源（例如 Dialog 的独立窗口），此时玻璃退化为纯玻璃。
 */
val LocalGlassBackdropState: ProvidableCompositionLocal<GlassBackdropState?> =
    staticCompositionLocalOf<GlassBackdropState?> { null }

val LocalLiquidMotion: ProvidableCompositionLocal<LiquidMotionSpec> =
    staticCompositionLocalOf { LiquidMotion.Default }
