package com.rickeal.agent.core.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [sampleSizeFor] 的 JVM 纯函数单测：最长边判据、2 的幂、退化输入。
 *
 * [sampleSizeFor] 已提升为文件级 `internal` 纯函数（不依赖 [WallpaperStore] 实例 /
 * Android Context），所以这里可以直接调用，无需 Robolectric。
 */
class WallpaperSamplingTest {

    @Test
    fun `最长边超过目标时按 2 的幂降采样`() {
        // 4000x3000 → 最长边 4000；4000/1 > 2048 → 砍半到 2（4000/2 = 2000 ≤ 2048 停）。
        assertEquals(2, sampleSizeFor(4000, 3000, 2048))
    }

    @Test
    fun `判据是最长边而非两维都超目标`() {
        // 4000x1000 的短边 1000 < 目标 2048：若按"两维都 ≥ 目标"会得到 1（= 全尺寸解码），
        // 按最长边判据应得到 2。这条用例专门锁死"最长边"口径，防止判据被改回而回归。
        assertEquals(2, sampleSizeFor(4000, 1000, 2048))
    }

    @Test
    fun `最长边正好等于目标时不降采样`() {
        assertEquals(1, sampleSizeFor(2048, 500, 2048))
    }

    @Test
    fun `最长边远大于目标时持续砍半`() {
        // 8000/1 > 1000 → 2；8000/2 = 4000 > 1000 → 4；8000/4 = 2000 > 1000 → 8；
        // 8000/8 = 1000 ≤ 1000 停 → 8（= 2^3）。
        assertEquals(8, sampleSizeFor(8000, 4000, 1000))
    }

    @Test
    fun `退化输入返回 1`() {
        assertEquals(1, sampleSizeFor(0, 0, 2048))
        assertEquals(1, sampleSizeFor(-1, 3000, 2048))
        assertEquals(1, sampleSizeFor(4000, -1, 2048))
        assertEquals(1, sampleSizeFor(4000, 3000, 0))
        assertEquals(1, sampleSizeFor(4000, 3000, -5))
    }
}
