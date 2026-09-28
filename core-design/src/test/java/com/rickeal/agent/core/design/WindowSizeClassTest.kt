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
}
