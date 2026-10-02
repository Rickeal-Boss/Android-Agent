package com.rickeal.agent.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [InferenceConfig.coerce] 对 `thinkingTokenBudget` 的归一测试（Wave 47 项1）。
 *
 * 钉住的硬不变量：预算**计入** `maxOutputToken`（thinking + 正文共享），故必须 `< maxTokens`
 * —— 否则正文仍可能被挤空。上界必须取 **maxTokens 归一后** 的值（maxTokens 越界时若用原值
 * 算上界会算错）。
 *
 * 断言收尾一律用返回 `Unit` 的函数（`assertEquals`）—— 避免 JUnit 4 误判非 `void`。
 */
class InferenceConfigTest {

    /** 默认值：预算 0 = 自动，coerce 不改（下界 0 保留「自动」语义）。 */
    @Test
    fun 默认预算零保持不变() {
        assertEquals(0, InferenceConfig().coerce().thinkingTokenBudget)
    }

    /** 合法区间内的显式值原样保留。 */
    @Test
    fun 区间内显式预算保留() {
        assertEquals(300, InferenceConfig(maxTokens = 1024, thinkingTokenBudget = 300).coerce().thinkingTokenBudget)
    }

    /** 显式值 ≥ maxTokens ⇒ 钳到 maxTokens-1。 */
    @Test
    fun 预算超上界钳到maxTokens减一() {
        assertEquals(
            1023,
            InferenceConfig(maxTokens = 1024, thinkingTokenBudget = 2000).coerce().thinkingTokenBudget,
        )
    }

    /** 负值 ⇒ 钳回 0（自动）。 */
    @Test
    fun 负预算钳回零() {
        assertEquals(0, InferenceConfig(thinkingTokenBudget = -5).coerce().thinkingTokenBudget)
    }

    /** 上界取归一后的 maxTokens：maxTokens=10 归一为 64 ⇒ 预算上界 63（而非用原值 10 算的 9）。 */
    @Test
    fun 上界取归一后的maxTokens() {
        val coerced = InferenceConfig(maxTokens = 10, thinkingTokenBudget = 500).coerce()
        assertEquals(64, coerced.maxTokens)
        assertEquals(63, coerced.thinkingTokenBudget)
    }
}
