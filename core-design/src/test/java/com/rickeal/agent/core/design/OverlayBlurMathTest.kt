package com.rickeal.agent.core.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 覆盖层模糊管线的**纯数学**部分单测（W89）。
 *
 * 为什么这两条值得钉住：它们决定「背景模糊在动画中途会不会闪一下」，
 * 而这类缺陷**没有任何报错**、也不影响功能，只能靠真机逐帧看或靠单测。
 * W89 的真机取证（10s 慢拖 + 逐帧截图）证明旧实现在 `p<1` 全程重画内容，
 * 既产生"双重曝光"观感，又每帧多付 ~17ms（`gfxinfo` 50th 36ms → 关掉总闸 19ms）。
 *
 * 本测试只覆盖不依赖 Compose / Android 的判据；像素级结论以真机 `gfxinfo` 为准。
 */
class OverlayBlurMathTest {

    // ── coverThresholdOf ────────────────────────────────────────────────────

    @Test
    fun `覆盖阈值随半径增大而变小`() {
        // 同一缩放比下：半径越大，同样比例的 p 已经糊得越厉害 ⇒ 可以更早用缩略图替代内容。
        val scale = 0.237f
        val small = coverThresholdOf(scale, 15f)
        val large = coverThresholdOf(scale, 120f)
        assertTrue(large < small, "半径 120px 的阈值($large) 应小于 15px 的($small)")
    }

    @Test
    fun `覆盖阈值按 1-s`() {
        // 推导：(1/scale)/radiusPx 夹在 [0.03, 0.35]。
        // 真机默认档：scale≈0.237（1080px 宽 / 256px 缩略图）、19dp@3x = 57px
        // ⇒ (1/0.237)/57 ≈ 0.074 —— 即"交叉淡入只占前 7.4% 的进度"。
        val v = coverThresholdOf(0.237f, 57f)
        assertEquals(0.074f, v, 0.004f)
    }

    @Test
    fun `覆盖阈值在退化输入下夹到上界`() {
        // 半径为 0 / 缩放为 0 时不能返回 0 或 NaN：那会让"内容绘制"永远不被跳过
        // （0 阈值 ⇒ 除零）或让 alpha 恒为 1（一开就跳变）。
        assertEquals(0.35f, coverThresholdOf(0.237f, 0f))
        assertEquals(0.35f, coverThresholdOf(0f, 57f))
        // 极小半径 ⇒ 夹到上界，而不是算出一个 >1 的阈值。
        assertEquals(0.35f, coverThresholdOf(0.237f, 1f))
        // 极大半径 ⇒ 夹到下界，而不是 0。
        assertEquals(0.03f, coverThresholdOf(0.237f, 100_000f))
    }

    // ── thumbnailCoverAlpha ─────────────────────────────────────────────────

    @Test
    fun `不透明度两端精确`() {
        val cover = 0.074f
        assertEquals(0f, thumbnailCoverAlpha(0f, cover))
        // p 达到阈值即完全盖住 —— 之后必须严格为 1，否则会一直重画内容（性能回归）。
        assertEquals(1f, thumbnailCoverAlpha(cover, cover))
        assertEquals(1f, thumbnailCoverAlpha(1f, cover))
    }

    @Test
    fun `不透明度在区间内单调不减`() {
        val cover = 0.074f
        var prev = -1f
        var p = 0f
        while (p <= 0.2f) {
            val a = thumbnailCoverAlpha(p, cover)
            assertTrue(a >= prev, "p=$p 处 alpha 回退了（$prev → $a）")
            assertTrue(a in 0f..1f, "p=$p 处 alpha 越界：$a")
            prev = a
            p += 0.002f
        }
    }

    @Test
    fun `阈值为零时直接全覆盖`() {
        // coverFrom ≤ 0 属退化输入（半径 0 的档位）：不能除零成 NaN。
        assertEquals(1f, thumbnailCoverAlpha(0.5f, 0f))
        assertEquals(1f, thumbnailCoverAlpha(0.5f, -1f))
    }
}
