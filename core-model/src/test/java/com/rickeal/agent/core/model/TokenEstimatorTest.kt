package com.rickeal.agent.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [TokenEstimator] 的契约测试（Wave 28）。
 *
 * 估算器是**上下文压缩门禁的唯一输入**：旧实现统一 3.2 字符/token，对中文系统性
 * 低估 2~3 倍（KDoc 自认「中文按 1 字 ≈ 1 token 更准，取折中」），导致压缩门禁在
 * 引擎真实 KV 已越顶后仍认为有余量 —— litertlm 直接硬报错。本测试锁定：
 * ① CJK 1 字 ≈ 1 token 的保守上界；② 非 CJK 维持 3.2 字符/token；③ 非空文本 ≥ 1。
 * 改动估算口径前先读 TokenEstimator KDoc 的「两个方向不对称」论证。
 */
class TokenEstimatorTest {

    @Test
    fun emptyTextIsZeroTokens() {
        assertEquals(0, TokenEstimator.estimate(""))
    }

    @Test
    fun cjkCountsOneTokenPerChar() {
        // 4 个汉字 = 4 token（旧实现是 ceil(4/3.2)=2 —— 低估一半）。
        assertEquals(4, TokenEstimator.estimate("你好世界"))
    }

    @Test
    fun asciiKeepsLegacyRatio() {
        // 8 个 ASCII 字符 ≈ ceil(8/3.2) = 3，与旧口径一致（零回归面）。
        assertEquals(3, TokenEstimator.estimate("abcdefgh"))
    }

    @Test
    fun mixedScriptSumsBothRules() {
        // "你好" = 2 CJK + "world" = 5/3.2 ≈ 1.5625 → ceil(3.5625) = 4。
        assertEquals(4, TokenEstimator.estimate("你好world"))
    }

    @Test
    fun cjkPunctuationAndFullwidthCountAsCjk() {
        // 全角逗号（0xFF0C）在 0xFF00..0xFFEF 桶内按 1 token 计：4 汉字 + 1 全角标点 = 5。
        assertEquals(5, TokenEstimator.estimate("你好，世界"))
    }

    @Test
    fun nonEmptyWhitespaceIsAtLeastOneToken() {
        assertTrue(TokenEstimator.estimate(" ") >= 1)
    }

    @Test
    fun estimateIsMonotonicInTextLength() {
        val short = TokenEstimator.estimate("提示词")
        val long = TokenEstimator.estimate("提示词提示词提示词提示词")
        assertTrue(long > short, "更长文本（$long）必须大于短文本（$short）")
    }
}
