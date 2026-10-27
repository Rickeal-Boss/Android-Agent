package com.rickeal.agent.core.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [widthClassOf] / [heightClassOf] / [WindowSizeClass] 派生位 的 JVM 纯函数单测
 * （Wave 32 · 流 B：为 :core-design 点亮测试源集）。
 *
 * 为什么值得钉住：这两个分档函数是「平板 / 折叠屏要不要开两栏 / 三栏」的唯一入口，
 * 而本仓**没有平板真机可回归**。断点写错一档（`< 600` 写成 `<= 600`）在手机上完全
 * 看不出来，却会让一类设备的布局永远停在单栏 —— 属于典型的「改了没人发现」。
 *
 * 纯 Int → 枚举、零 Android / Compose 依赖 ⇒ JVM 单测可直接跑，无需 Robolectric。
 * 断点值取自 Material 3 窗口尺寸等级（宽 600 / 840，高 480 / 900）。
 */
class WindowSizeClassTest {

    // ── 宽度分档 ────────────────────────────────────────────────────────────

    @Test
    fun `宽度断点下沿为 COMPACT`() {
        assertEquals(WindowWidthClass.COMPACT, widthClassOf(0))
        assertEquals(WindowWidthClass.COMPACT, widthClassOf(599))
    }

    @Test
    fun `宽度600起为 MEDIUM`() {
        // 严格小于才算 COMPACT：600 是 MEDIUM 的第一档，写成 <= 会让 600dp 平板掉回单栏。
        assertEquals(WindowWidthClass.MEDIUM, widthClassOf(600))
        assertEquals(WindowWidthClass.MEDIUM, widthClassOf(839))
    }

    @Test
    fun `宽度840起为 EXPANDED`() {
        assertEquals(WindowWidthClass.EXPANDED, widthClassOf(840))
        assertEquals(WindowWidthClass.EXPANDED, widthClassOf(1600))
    }

    // ── 高度分档 ────────────────────────────────────────────────────────────

    @Test
    fun `高度断点下沿为 COMPACT`() {
        assertEquals(WindowHeightClass.COMPACT, heightClassOf(0))
        assertEquals(WindowHeightClass.COMPACT, heightClassOf(479))
    }

    @Test
    fun `高度480起为 MEDIUM 且900起为 EXPANDED`() {
        assertEquals(WindowHeightClass.MEDIUM, heightClassOf(480))
        assertEquals(WindowHeightClass.MEDIUM, heightClassOf(899))
        assertEquals(WindowHeightClass.EXPANDED, heightClassOf(900))
    }

    // ── 派生位：两栏 / 三栏 ────────────────────────────────────────────────

    @Test
    fun `MEDIUM 及以上开两栏 但只有 EXPANDED 开三栏`() {
        val compact = WindowSizeClass(widthClassOf(400), heightClassOf(800))
        assertFalse(compact.useTwoPane)
        assertFalse(compact.useThreePane)

        val medium = WindowSizeClass(widthClassOf(700), heightClassOf(800))
        assertTrue(medium.useTwoPane)
        assertFalse(medium.useThreePane)

        val expanded = WindowSizeClass(widthClassOf(900), heightClassOf(800))
        assertTrue(expanded.useTwoPane)
        assertTrue(expanded.useThreePane)
    }

    @Test
    fun `高度等级不参与两栏判定`() {
        // 两栏/三栏只看宽度：折叠屏展开态的高度可能仍是 COMPACT，不能因此退回单栏。
        val shortButWide = WindowSizeClass(WindowWidthClass.EXPANDED, WindowHeightClass.COMPACT)
        assertTrue(shortButWide.useTwoPane)
        assertTrue(shortButWide.useThreePane)
    }

    // ── 派生位：横屏手机（W88）──────────────────────────────────────────────

    @Test
    fun `横屏手机判据只认 宽MEDIUM以上 且 高COMPACT`() {
        // 真机实测：OPPO PDRM00 1080×2400 @480dpi 转 90° ⇒ w764dp / h344dp。
        // 这一档必须为 true —— 它是「工作区面板不能被 useTwoPane 砍掉」的唯一开关。
        assertTrue(
            WindowSizeClass(widthClassOf(764), heightClassOf(344)).isLandscapePhone,
        )
    }

    @Test
    fun `同宽但高度够的平板不是横屏手机`() {
        // 800×1000dp 的折叠屏展开态：宽度同档，但高度 MEDIUM ⇒ 是真平板，
        // 必须继续享受「双栏 + 无抽屉」的既有语义。
        assertFalse(
            WindowSizeClass(widthClassOf(800), heightClassOf(1000)).isLandscapePhone,
        )
    }

    @Test
    fun `窄而矮的窗口也不是横屏手机`() {
        // 宽度不够 MEDIUM（分屏小窗 500×400）：既没有双栏，也不该走"横屏手机"分支。
        assertFalse(
            WindowSizeClass(widthClassOf(500), heightClassOf(400)).isLandscapePhone,
        )
        // 平板横屏（1200×344）：宽度 EXPANDED 但高度 COMPACT ⇒ 仍算"矮宽"，
        // 走横屏手机分支（高度预算与手机横屏同病：面板/弹窗都放不下）。
        assertTrue(
            WindowSizeClass(widthClassOf(1200), heightClassOf(344)).isLandscapePhone,
        )
    }

    // ── 一级导航落点（W89）──────────────────────────────────────────────────

    @Test
    fun `竖屏手机页签在底部`() {
        // 真机竖屏：360×748dp。
        assertEquals(
            TabBarPlacement.Bottom,
            WindowSizeClass(widthClassOf(360), heightClassOf(748)).tabBarPlacement(),
        )
        // 极窄小窗也不能掉到别的分支。
        assertEquals(
            TabBarPlacement.Bottom,
            WindowSizeClass(widthClassOf(320), heightClassOf(640)).tabBarPlacement(),
        )
    }

    @Test
    fun `横屏手机页签在顶部`() {
        // 真机横屏：w764dp / h344dp（OPPO PDRM00 转 90°）。这一档必须是 Top ——
        // 它同时是"工作区面板不能被砍掉"的判据（isLandscapePhone），两件事同源。
        assertEquals(
            TabBarPlacement.Top,
            WindowSizeClass(widthClassOf(764), heightClassOf(344)).tabBarPlacement(),
        )
    }

    @Test
    fun `平板保持左侧竖栏`() {
        // 竖屏平板（800×1000）：宽 MEDIUM、高 MEDIUM ⇒ 不是横屏手机 ⇒ 竖栏。
        assertEquals(
            TabBarPlacement.Rail,
            WindowSizeClass(widthClassOf(800), heightClassOf(1000)).tabBarPlacement(),
        )
        // 横屏平板（1280×800）：高 MEDIUM ⇒ 竖栏（高度够，不需要为省高度把条挪到顶部）。
        assertEquals(
            TabBarPlacement.Rail,
            WindowSizeClass(widthClassOf(1280), heightClassOf(800)).tabBarPlacement(),
        )
        // 高度正好 480dp（MEDIUM 下沿）——断点两侧：
        assertEquals(
            TabBarPlacement.Top,
            WindowSizeClass(widthClassOf(764), heightClassOf(479)).tabBarPlacement(),
        )
        assertEquals(
            TabBarPlacement.Rail,
            WindowSizeClass(widthClassOf(764), heightClassOf(480)).tabBarPlacement(),
        )
    }
}
