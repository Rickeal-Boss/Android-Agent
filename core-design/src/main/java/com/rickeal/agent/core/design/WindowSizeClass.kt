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

// ⚠️ 两个分档函数此前是 `private`：分档阈值（600/840 / 480/900）是 Material 3 的
// 窗口尺寸等级断点，写错一档的后果是「平板被判成手机 → 两栏布局永不出现」，
// 而这类错误在单手/折叠屏真机上才发现得到。改 `internal` 只为让 JVM 单测能直接
// 钉住断点两侧，**函数体一字未动**（纯 Int → 枚举，零 Android / Compose 依赖，
// 所以 :core-design 的 JVM 单测无需 Robolectric）。
internal fun widthClassOf(widthDp: Int): WindowWidthClass = when {
    widthDp < 600 -> WindowWidthClass.COMPACT
    widthDp < 840 -> WindowWidthClass.MEDIUM
    else -> WindowWidthClass.EXPANDED
}

internal fun heightClassOf(heightDp: Int): WindowHeightClass = when {
    heightDp < 480 -> WindowHeightClass.COMPACT
    heightDp < 900 -> WindowHeightClass.MEDIUM
    else -> WindowHeightClass.EXPANDED
}

/**
 * 依据当前配置推断窗口尺寸等级，用于平板 / 折叠屏布局切换。
 *
 * 这里用 `LocalConfiguration.screenWidthDp/screenHeightDp` 是**正确口径**：
 * `LocalConfiguration` 由 Activity 重建驱动，与 `MainActivity` 的配置变更处理一致；
 * 改用 `LocalWindowInfo.current.containerSize`（px）需要 `LocalDensity` 换算，且语义
 * 从「窗口 dp」变成「容器 dp」，在断点边界可能翻档 —— 那是需真机验证的行为变更，故不改。
 * 断点单测（600/840/480/900）也钉在 dp 语义上。抑制 lint 的 ConfigurationScreenWidthHeight。
 */
@Suppress("ConfigurationScreenWidthHeight")
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
