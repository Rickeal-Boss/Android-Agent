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
    /**
     * 触感强度档位（[GlassHapticLevel]），设置页「触感反馈强度」驱动。
     * 默认 STANDARD：保证尚未接线时行为与旧版完全一致 —— 所有
     * `rememberGlassHaptics()` 调用点零改动。系统「触感反馈」开关是总闸，
     * 这里的档位只在总闸开启时生效 —— 见 [GlassHaptics] KDoc。
     */
    val hapticLevel: GlassHapticLevel = GlassHapticLevel.STANDARD,
)

val LocalGlassTokens: ProvidableCompositionLocal<GlassTokens> =
    staticCompositionLocalOf { GlassDefaults.Tokens }

val LocalGlassColors: ProvidableCompositionLocal<GlassColorScheme> =
    staticCompositionLocalOf { lightGlassColorScheme() }

val LocalGlassConfig: ProvidableCompositionLocal<GlassConfig> =
    staticCompositionLocalOf { GlassConfig() }

val LocalGlassBackdrop: ProvidableCompositionLocal<GlassBackdrop> =
    staticCompositionLocalOf { GlassBackdrop() }

val LocalLiquidMotion: ProvidableCompositionLocal<LiquidMotionSpec> =
    staticCompositionLocalOf { LiquidMotion.Default }
