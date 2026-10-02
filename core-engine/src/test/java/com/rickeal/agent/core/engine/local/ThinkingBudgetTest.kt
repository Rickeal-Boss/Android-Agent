package com.rickeal.agent.core.engine.local

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [resolveThinkingBudget] 的纯逻辑测试（Wave 47 项1「thinking 独立 token 预算」的离线锚）。
 *
 * 为什么值得写：预算派生是「thinking 独立预算」唯一可离线验证的一环（native 是否真的执行
 * 预算必须真机验收）。判据写错的表现是「正文被过早截断」或「thinking 仍烧光预算」——
 * 两者都不抛异常、CI 抓不到，只能靠这里的边界用例钉住。
 *
 * 断言收尾一律用返回 `Unit` 的函数（`assertEquals`）—— `assertNotNull` / `assertIs`
 * 会返回值，以它们收尾会让 JUnit 4 误判方法签名非 `void`，整类 `initializationError`。
 */
class ThinkingBudgetTest {

    /** 自动（configured=0）：取 maxTokens 的一半，为正文保留等量余量。 */
    @Test
    fun 自动预算取maxTokens一半() {
        assertEquals(512, resolveThinkingBudget(configured = 0, maxTokens = 1024))
        assertEquals(32, resolveThinkingBudget(configured = 0, maxTokens = 64))
    }

    /** 显式值（configured>0）：原样采信（仍在合法区间内）。 */
    @Test
    fun 显式预算原样采信() {
        assertEquals(300, resolveThinkingBudget(configured = 300, maxTokens = 1024))
        assertEquals(1, resolveThinkingBudget(configured = 1, maxTokens = 1024))
    }

    /** 显式值 ≥ maxTokens：钳到 maxTokens-1，保证正文至少留 1 token 余量。 */
    @Test
    fun 显式预算超上界钳到maxTokens减一() {
        assertEquals(1023, resolveThinkingBudget(configured = 2000, maxTokens = 1024))
        assertEquals(1023, resolveThinkingBudget(configured = 1024, maxTokens = 1024))
    }

    /** 边界：maxTokens=1（配置已被 coerce 挡在 ≥64，此处穷尽防御）⇒ 恒返回 ≥1 的合法预算。 */
    @Test
    fun maxTokens等于一返回合法预算() {
        assertEquals(1, resolveThinkingBudget(configured = 0, maxTokens = 1))
        assertEquals(1, resolveThinkingBudget(configured = 5, maxTokens = 1))
    }
}
