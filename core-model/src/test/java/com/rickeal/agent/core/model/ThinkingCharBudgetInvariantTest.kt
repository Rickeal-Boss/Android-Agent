package com.rickeal.agent.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [StreamRepetitionDetector.THINKING_CHAR_BUDGET] 与 native 思考预算的**字符当量不变式**
 * （W63 刀B）。
 *
 * 推导链（⚠️ `resolveThinkingBudget` 是 core-engine 的 `internal` 纯函数，Kotlin
 * `internal` 按**模块**隔离，core-model 不可见 ⇒ 在可见层面钉常量关系，推导链以注释
 * 写死，断言写死 4096 与其来源）：
 * 1. native 思考预算 = `resolveThinkingBudget(configured = 0, maxTokens = 1024)`
 *    = maxTokens/2 = **512 token**（core-engine `ThinkingBudget.kt`「半预算」语义；
 *    该公式与其单测 `ThinkingBudgetTest` 已在 core-engine 侧钉 512）；
 * 2. 英语最坏约 4 chars/token ⇒ 512 token ≈ 2048 字符；
 * 3. 检测器阈值取 **2× 余量** ⇒ 512 × 4 × 2 = **4096**。
 *
 * 目的：将来有人单独动 `THINKING_CHAR_BUDGET`，或动 core-engine 侧「半预算」派生公式、
 * maxTokens 默认值时，本测试会红并把他引回上面的推导链重新标定，而不是静默漂移
 * （同族历史坑：两份口径各自演化 ⇒ 检测/守卫静默失效）。本测试**不**钉
 * [StreamRepetitionDetector.TEXT_RUNAWAY_CHARS] —— 它与 thinking 预算同值 4096 但
 * 语义独立（text 流撞的是 maxOutputToken 硬截、无 native 兜底），两值不得联动同步
 * 调整（见两者 KDoc 的交叉注记），钉在一起反而会诱导联动。
 *
 * 纯 JVM：零协程 / 零 Android 依赖。
 */
class ThinkingCharBudgetInvariantTest {

    @Test
    fun `思考字符预算钉在 native 半预算的字符当量`() {
        // 推导链（见类 KDoc）：resolveThinkingBudget(0, 1024) = 512 token × 4 chars/token × 2 余量。
        val nativeThinkingTokens = 512
        val charsPerToken = 4
        val margin = 2
        assertEquals(
            nativeThinkingTokens * charsPerToken * margin,
            StreamRepetitionDetector.THINKING_CHAR_BUDGET,
            "THINKING_CHAR_BUDGET 必须保持「maxTokens=1024 下 native 半预算（512 token）× 4 chars/token × 2 余量 = 4096」的字符当量；改值前先读 StreamRepetitionDetector KDoc 的适用区间注记与 ThinkingBudget.kt 派生公式",
        )
    }
}
