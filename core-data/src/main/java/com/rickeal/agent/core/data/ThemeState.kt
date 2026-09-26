package com.rickeal.agent.core.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/** 深色模式策略。SYSTEM = 跟随系统（默认）。 */
@Serializable
enum class DarkMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/**
 * 主题与玻璃质感设置（:core-design 消费，:core-data 只负责持久化）。
 *
 * 标注 @Immutable 是为了让 Compose 编译器把 SettingsUiState 判为稳定类型，
 * 从而启用智能重组（架构文档 §7.6 第 4 条）。
 */
@Immutable
@Serializable
data class ThemeState(
    val darkMode: DarkMode = DarkMode.SYSTEM,
    val reduceMotion: Boolean = false,
    val glassIntensity: Float = 1f,
    val enableNoise: Boolean = true,
    /**
     * 触感反馈强度档位名（"OFF"/"LIGHT"/"STANDARD"/"STRONG"）。
     *
     * 存 **String** 不存枚举：:core-data 不能 import :core-design（依赖方向倒置，
     * 见架构 §1.4）—— 与 [darkMode] 不同，DarkMode 定义在 core-data 侧所以能用枚举，
     * 而 GlassHapticLevel 定义在 core-design 侧，这里只能拿名字，由 app 层
     * `GlassHapticLevel.valueOf(...)` 还原（解析失败回退 STANDARD）。
     */
    val hapticLevel: String = "STANDARD",
    /**
     * 覆盖层 scrim 不透明度 0~1（设置页「覆盖层不透明度」驱动，默认 0.45）。
     *
     * 走 **Float** 通道（同 [glassIntensity]）：它是纯原生类型，不存在 hapticLevel
     * 那种「枚举定义在 :core-design」的依赖倒置问题，无需降级成 String。
     * @Serializable 字段带默认值 ⇒ 旧 JSON 反序列化向后兼容。
     */
    val overlayOpacity: Float = 0.45f,
    /**
     * 覆盖层背景深度模糊的**满量程半径**（dp，设置页「覆盖层背景模糊」驱动，默认 20f）。
     *
     * 同 [overlayOpacity] 走 Float 通道；@Serializable 带默认值 ⇒ 旧数据向后兼容。
     * 默认 20f 只是起点（本机无真机可验证观感），真机上由滑条在 8~32dp 之间定档。
     */
    val overlayBlurRadius: Float = 20f,
)
