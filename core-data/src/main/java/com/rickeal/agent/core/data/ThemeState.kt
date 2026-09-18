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
)
