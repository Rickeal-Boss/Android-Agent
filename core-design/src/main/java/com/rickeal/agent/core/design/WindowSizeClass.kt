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

    /**
     * 横屏手机：**宽度**够到双栏，但**高度**只有 COMPACT（典型 800×360dp）。
     *
     * 为什么必须有这个派生位（W88 实测）：`useTwoPane` 只看宽度，于是手机横屏
     * （1080×2400 @480dpi 转 90° ⇒ w764dp / h308~344dp）与平板被归成同一档，
     * 而两者的**高度预算**差一倍。直接拿 `useTwoPane` 当"宽屏"用，在横屏手机上会：
     *  - 砍掉会话抽屉与「本地工作区」面板（抽屉有 Rail 替代，工作区面板**没有任何替代入口**
     *    ⇒ 功能整体丢失）；
     *  - 让参数面板按 0.78 屏高展开（≈268dp，弹键盘后可视区 ≈5dp）。
     *
     * 判据刻意**不改断点、不加枚举**：600/840 与 480/900 是 Material 3 的窗口尺寸等级，
     * 已被 [WindowSizeClassTest] 逐条钉住，动它们会让「平板被判成手机」这类错误
     * 在折叠屏真机上才暴露。这里只做**派生**：宽 ≥ MEDIUM 且 高 = COMPACT。
     *
     * 竖屏恒 false（竖屏手机高度 ≥ MEDIUM，平板更不用说）⇒ 竖屏行为逐字节不变。
     */
    val isLandscapePhone: Boolean
        get() = width >= WindowWidthClass.MEDIUM && height == WindowHeightClass.COMPACT
}

/**
 * 一级导航（5 个页签）在三种窗口形态下的落点。
 *
 * 抽成枚举 + 纯函数（而不是在 `MainShell` 里写三个散落的布尔）的唯一理由是
 * **可 JVM 测**：三档判据写错的表现全是"不报错但形态错"（竖屏冒出侧栏、
 * 横屏又回到左栏挤掉正文宽度），只有钉住三条分支才能防回归。
 */
enum class TabBarPlacement {
    /** 竖屏手机：底部悬浮玻璃条（既有形态）。 */
    Bottom,

    /** 平板 / 折叠屏展开 / 桌面：左侧常驻竖栏（图标 + 文字）。 */
    Rail,

    /**
     * 横屏手机：**顶部横向条**（W89 用户需求）。
     *
     * 为什么不是 Rail：横屏手机的高度只有 308~344dp，左侧竖栏虽然省高度，
     * 但它把正文的**横向**宽度切掉 88dp + 两侧内边距，而横屏恰恰是横向最富余、
     * 纵向最紧张 —— 竖栏让整页看起来"不是全屏"。改成顶部横条后：
     *  - 正文横向吃满（视觉全屏，与用户原话一致）；
     *  - 页签形态与竖屏底部条**完全一致**（同一枚 [LiquidBottomTabs]），不引入第二套排版；
     *  - 顶部让位由 `LocalTopBarOverlay` 下发给各屏 `GlassScaffold`，
     *    内容不会钻到条下面。
     */
    Top,
}

/**
 * 一级导航落点判定。三档互斥且穷尽，**不要**在调用点再用 `if (useTwoPane)` 兜底
 * （那正是横屏手机被当成平板、整块工作区面板消失的根因，见 [isLandscapePhone]）。
 */
fun WindowSizeClass.tabBarPlacement(): TabBarPlacement = when {
    isLandscapePhone -> TabBarPlacement.Top
    useTwoPane -> TabBarPlacement.Rail
    else -> TabBarPlacement.Bottom
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
