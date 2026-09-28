package com.rickeal.agent.core.design.liquid.interactive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [isVerticalDragIntent] / [dampedDragProgress] 的 JVM 纯函数单测
 * （Wave 32 · 流 B：为 :core-design 点亮测试源集 —— 此前该模块 src/test 为空，NO-SOURCE）。
 *
 * 为什么这两个函数值得先钉住：它们是 [DampedDragAnimation] 里**唯一不依赖 Compose /
 * Android 运行时**的两段判据，却各自守着一个真机踩过的坑：
 *  1. **轴向让位**（[isVerticalDragIntent]）：设置页 13 个滑块 / 开关全都躺在
 *     `verticalScroll` 里。判据写成 45°（`|dy| > |dx|`）会在 30°~45° 斜滑留死区 ——
 *     手势消费掉事件、父级起不来滚 ⇒「手指按在滑块上页面滚不动」。判据写反
 *     （恒真）则横向拖动动不动就让位 ⇒「有时拖不动」。
 *  2. **进度归一化**（[dampedDragProgress]）：thumb 位置与轨道填充宽度直接读它，
 *     退化量程（span ≤ 0）除零会得到 NaN，而 NaN 尺寸在 Compose 布局里是致命的
 *     （节点直接不绘制 —— 表现为"滑块整个消失"，而不是"停在 0"）。
 *
 * 刻意**不构造** [DampedDragAnimation] 实例：它需要 CoroutineScope + Animatable
 * （Compose 运行时），JVM 上不可测。这里只测被它调用的那两个纯函数。
 */
class DragGesturePredicatesTest {

    // ── 纵向意图判据 ────────────────────────────────────────────────────────
    // 阈值是 tan30° ≈ 0.577：|dy| > |dx| × 0.577。

    @Test
    fun `纯纵向拖动判为纵向意图`() {
        assertTrue(isVerticalDragIntent(accumulatedX = 0f, accumulatedY = 40f))
    }

    @Test
    fun `纯横向拖动不判为纵向意图`() {
        assertFalse(isVerticalDragIntent(accumulatedX = 40f, accumulatedY = 0f))
    }

    @Test
    fun `35度斜滑判为纵向 —— 简化成45度会漏判这一段`() {
        // |dy| = 70 > |dx| = 100 × 0.577 ≈ 57.7 ⇒ 纵向，让位给父级滚动。
        // 若把判据"简化"成 |dy| > |dx|（45°），这里会得到 false —— 父级滚不起来。
        assertTrue(isVerticalDragIntent(accumulatedX = 100f, accumulatedY = 70f))
    }

    @Test
    fun `30度临界斜滑判为纵向`() {
        // tan30° × 100 = 57.735，严格大于阈值常量 0.577f×100（≈57.700）⇒ 判为纵向。
        assertTrue(isVerticalDragIntent(accumulatedX = 100f, accumulatedY = 57.735f))
    }

    @Test
    fun `略小于30度的斜滑判为横向`() {
        assertFalse(isVerticalDragIntent(accumulatedX = 100f, accumulatedY = 57f))
    }

    @Test
    fun `零位移不是纵向意图`() {
        // 严格大于：`0 > 0` 为假 —— 亚像素抖动不该触发让位。
        assertFalse(isVerticalDragIntent(accumulatedX = 0f, accumulatedY = 0f))
    }

    @Test
    fun `判据只看位移绝对值不看方向`() {
        // 向上与向下（向左与向右）必须同一判定：分轴累加的是**有符号**净位移。
        assertEquals(
            isVerticalDragIntent(accumulatedX = 100f, accumulatedY = -70f),
            isVerticalDragIntent(accumulatedX = -100f, accumulatedY = 70f),
        )
        assertTrue(isVerticalDragIntent(accumulatedX = -100f, accumulatedY = -70f))
    }

    // ── 进度归一化 ──────────────────────────────────────────────────────────

    @Test
    fun `量程内线性映射到0到1`() {
        assertEquals(0f, dampedDragProgress(value = 0f, valueRange = 0f..10f))
        assertEquals(0.5f, dampedDragProgress(value = 5f, valueRange = 0f..10f))
        assertEquals(1f, dampedDragProgress(value = 10f, valueRange = 0f..10f))
    }

    @Test
    fun `非零起点的量程按起点折算`() {
        assertEquals(0f, dampedDragProgress(value = 2f, valueRange = 2f..6f))
        assertEquals(0.5f, dampedDragProgress(value = 4f, valueRange = 2f..6f))
        assertEquals(1f, dampedDragProgress(value = 6f, valueRange = 2f..6f))
    }

    @Test
    fun `越界值被钳制在0到1`() {
        // 拖动期外部 snap 可能把值写到量程之外，不钳制 thumb 会画到轨道外。
        assertEquals(0f, dampedDragProgress(value = -3f, valueRange = 0f..10f))
        assertEquals(1f, dampedDragProgress(value = 13f, valueRange = 0f..10f))
    }

    @Test
    fun `退化量程返回0而不是NaN`() {
        // span == 0：除零会得 NaN，NaN 尺寸在 Compose 布局里会让节点直接不绘制。
        val degenerate = 5f..5f
        assertEquals(0f, dampedDragProgress(value = 5f, valueRange = degenerate))
        assertEquals(0f, dampedDragProgress(value = 99f, valueRange = degenerate))
    }
}
