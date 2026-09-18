package com.rickeal.agent.core.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration

enum class WindowWidthClass { COMPACT, MEDIUM, EXPANDED }

enum class WindowHeightClass { COMPACT, MEDIUM, EXPANDED }

data class WindowSizeClass(
    val width: WindowWidthClass,
    val height: WindowHeightClass,
) {
    /** 平板 / 折叠屏展开态：可同时容纳「列表 + 内容」。 */
    val useTwoPane: Boolean
        get() = width >= WindowWidthClass.MEDIUM

    /** 大屏：可同时容纳「列表 + 内容 + 常驻参数面板」。 */
    val useThreePane: Boolean
        get() = width == WindowWidthClass.EXPANDED
}

private fun widthClassOf(widthDp: Int): WindowWidthClass = when {
    widthDp < 600 -> WindowWidthClass.COMPACT
    widthDp < 840 -> WindowWidthClass.MEDIUM
    else -> WindowWidthClass.EXPANDED
}

private fun heightClassOf(heightDp: Int): WindowHeightClass = when {
    heightDp < 480 -> WindowHeightClass.COMPACT
    heightDp < 900 -> WindowHeightClass.MEDIUM
    else -> WindowHeightClass.EXPANDED
}

/** 依据当前配置推断窗口尺寸等级，用于平板 / 折叠屏布局切换。 */
@Composable
fun rememberWindowSizeClass(): WindowSizeClass {
    val configuration = LocalConfiguration.current
    val widthDp = configuration.screenWidthDp
    val heightDp = configuration.screenHeightDp
    return remember(widthDp, heightDp) {
        WindowSizeClass(
            width = widthClassOf(widthDp),
            height = heightClassOf(heightDp),
        )
    }
}
