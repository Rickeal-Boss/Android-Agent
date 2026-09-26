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
    /**
     * 全局材质强度 0.5~1.5，设置页「玻璃质感强度」可调。
     *
     * 2026-09-26 接线补全（用户真机反馈"只调了背景壁纸"）：现乘入
     *  - 玻璃底色/描边/噪点/高光的 **alpha**（LiquidGlassModifier，原有）；
     *  - **模糊半径与折射量**（LiquidGlassModifier effects，新增 —— 最直观的两项）；
     *  - 程序化壁纸光斑 alpha（GlassWallpaper，原有；光斑已随"纯色米白"需求移除）。
     * 所有用玻璃的组件（卡片/按钮/底栏/滑块）经 `LocalGlassConfig` 一处生效。
     */
    val intensity: Float = 1f,
    /**
     * 触感强度档位（[GlassHapticLevel]），设置页「触感反馈强度」驱动。
     * 默认 STANDARD：保证尚未接线时行为与旧版完全一致 —— 所有
     * `rememberGlassHaptics()` 调用点零改动。系统「触感反馈」开关是总闸，
     * 这里的档位只在总闸开启时生效 —— 见 [GlassHaptics] KDoc。
     */
    val hapticLevel: GlassHapticLevel = GlassHapticLevel.STANDARD,
    /**
     * 覆盖层自身压实 scrim 的 alpha，0=纯玻璃 1=完全不透明。
     *
     * 三类覆盖层（推理参数面板 / 新增记忆对话框 / 会话抽屉）共用；设置页
     * 「覆盖层不透明度」驱动。与 [intensity]（玻璃**材质**强度）正交：那条管
     * 模糊 / 折射 / 底色 alpha，这条只管覆盖层**背后那层压暗 scrim** 的浓度。
     * 默认 0.45：现状等效透出约 0.25，0.45 为可读性明显改善又不闷死玻璃的中点。
     */
    val overlayOpacity: Float = 0.45f,
    /**
     * 覆盖层背景的**深度模糊半径**（dp，强度 1 时的满量程），默认 20f。
     *
     * 2026-09-27 用户需求：「覆盖层打开时都需要把除了覆盖层以外的整个背景完全加入深度模糊
     * （随动画逐渐加强度）」。三类覆盖层（会话抽屉 / 对话框 / 推理参数面板）与
     * [overlayOpacity] 共用同一份判据，实施见 `OverlayBackdropBlur.kt`。
     *
     * 与 [intensity] 正交：[intensity] 是**玻璃材质**的强度（底色/描边/模糊半径 alpha），
     * 这条是**背景被覆盖层压住时**的满量程模糊半径。
     *
     * ⚠️ **不要拿 `GlassMaterial` 的 3~12dp 当标尺**：那是**玻璃自身**的模糊（刻意压低到
     * 「柔化」档，为的是让折射的像素位移还看得见）；这条是**覆盖层背后的景深隔离**，
     * 目的是让背景内容不可读 —— 两者目的相反，量级不同属正常。
     *
     * 默认 20f 是**起点而非定论**：本机无法验证观感（无 SDK / 无真机），所以同步在设置页
     * 挂了「覆盖层背景模糊」滑条（8~32dp），真机上一档就准。若反馈「糊成一坨」往下调到
     * 12~16；若反馈「不够深」往上到 24~28。
     */
    val overlayBlurRadius: Float = 20f,
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
