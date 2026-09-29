package com.rickeal.agent.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [truncateSafe] 的契约测试（Wave 35 D1）。
 *
 * 钉住的是「返回值恒为**合法 UTF-16**」这一条不变量 —— 半个代理对在落库 / 回灌 /
 * 上屏三处都会变成 U+FFFD，工具输出截断是最高频的用户可见路径，这条不变量一旦
 * 破掉就是日常可见的乱码。计数口径（UTF-16 code unit，与 [String.take] 一致）
 * 同样钉住：改成按码点计数会让各处既有预算在 emoji 密集内容上整体缩水近半，
 * 那是未经评估的行为变化。
 */
class TruncateSafeTest {

    // ""ab😀c"" 的 UTF-16 布局：a(0) / 高位(1) / 低位(2) / b(3) / 高位(4) / 低位(5) / c(6)
    private val sample = "a\uD83D\uDE00b\uD83D\uDE00c"

    @Test
    fun `恰好边界 —— 长度等于 max 时原样返回`() {
        assertEquals("abcd", "abcd".truncateSafe(4))
        assertEquals("abcde", "abcde".truncateSafe(5))
        // 长度小于 max 同样原样返回（不得补任何填充）。
        assertEquals("ab", "ab".truncateSafe(10))
    }

    @Test
    fun `切断代理对 —— 落点在高位代理上必须回退一格`() {
        // take(2) 会留下孤立高位代理 → U+FFFD；truncateSafe 必须退回 "a"。
        assertEquals("a", sample.truncateSafe(2))
        // 落点在低位代理上：配对完整，不回退。
        assertEquals("a\uD83D\uDE00", sample.truncateSafe(3))
        assertEquals("a\uD83D\uDE00b", sample.truncateSafe(4))
        assertEquals("a\uD83D\uDE00b", sample.truncateSafe(5))
        assertEquals("a\uD83D\uDE00b\uD83D\uDE00", sample.truncateSafe(6))
    }

    @Test
    fun `单个 emoji —— max 只够半个码点时返回空串而非半个代理对`() {
        val emoji = "\uD83D\uDE00"
        assertEquals(emoji, emoji.truncateSafe(2))
        assertEquals("", emoji.truncateSafe(1))
        assertEquals("", emoji.truncateSafe(0))
    }

    @Test
    fun `空串与 max 非正 —— 一律空串`() {
        assertEquals("", "".truncateSafe(4))
        assertEquals("", "".truncateSafe(0))
        assertEquals("", "abc".truncateSafe(0))
        assertEquals("", "abc".truncateSafe(-1))
        assertEquals("", "abc".truncateSafe(Int.MIN_VALUE))
    }

    @Test
    fun `病态输入 —— 孤立低位代理同样回退（返回值恒为合法 UTF-16）`() {
        // 输入本身非法（高位缺失）：低位代理结尾也必须是回退掉的。
        val lone = "a\uDE00b"
        assertEquals("a", lone.truncateSafe(2))
        assertEquals("a\uDE00b", lone.truncateSafe(3))
    }

    @Test
    fun `不变量 —— 任意落点都不产生孤立代理`() {
        val emojiRun = "\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00" // 6 个 code unit，3 个 emoji
        for (max in 0..emojiRun.length + 1) {
            val cut = emojiRun.truncateSafe(max)
            assertTrue(cut.length <= max, "max=$max 时长度越界：$cut")
            if (cut.isNotEmpty()) {
                val tail = cut.last()
                val lone = tail.isHighSurrogate() ||
                    (tail.isLowSurrogate() && (cut.length < 2 || !cut[cut.length - 2].isHighSurrogate()))
                assertTrue(!lone, "max=$max 时尾部留下孤立代理：$cut")
            }
        }
    }
}
