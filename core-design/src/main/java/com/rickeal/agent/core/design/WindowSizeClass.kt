package com.rickeal.agent.core.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * 零依赖的窗口尺寸分类（刻意不引 `material3-window-size-class`）。
 */
@Immutable
enum class WindowWidthClass { COMPACT, MEDIUM, EXPANDED }

@Immutable
enum class WindowHeightClass { COMPACT, MEDIUM, EXPANDED }

@Immutable
data class WindowSizeClass(
    val width: WindowWidthClass = WindowWidthClass.COMPACT,
    val height: WindowHeightClass = WindowHeightClass.MEDIUM,
) {
    val isExpanded: Boolean get() = width == WindowWidthClass.EXPANDED

    /** 折叠屏展开 / 平板横屏 */
    val useTwoPane: Boolean get() = width >= WindowWidthClass.MEDIUM

    /** 三栏（列表 + 对话 + 常驻参数面板） */
    val useThreePane: Boolean get() = width == WindowWidthClass.EXPANDED &&
        height != WindowHeightClass.COMPACT
}

@Composable
fun rememberWindowSizeClass(): WindowSizeClass {
    val configuration = LocalConfiguration.current
    val w = configuration.screenWidthDp
    val h = configuration.screenHeightDp
    val width = when {
        w < 600 -> WindowWidthClass.COMPACT
        w < 840 -> WindowWidthClass.MEDIUM
        else -> WindowWidthClass.EXPANDED
    }
    val height = when {
        h < 480 -> WindowHeightClass.COMPACT
        h < 900 -> WindowHeightClass.MEDIUM
        else -> WindowHeightClass.EXPANDED
    }
    return WindowSizeClass(width, height)
}
