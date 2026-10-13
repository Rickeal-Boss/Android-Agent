package com.rickeal.agent.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [StreamRepetitionDetector.THINKING_CHAR_BUDGET] 作为**派生式默认值**的取值钉（W64 K-1 治本）。
 *
 * ⚠️ **真正的跨模块单源钉在 core-engine 的 `ThinkingBudgetInvariantTest`**：
 * ```
 * StreamRepetitionDetector.THINKING_CHAR_BUDGET == thinkingCharBudgetFor(1024, 0)
 * ```
 * core-engine 是**唯一**同时可见 `resolveThinkingBudget` / `thinkingCharBudgetFor` 与
 * core-model 检测器常量的模块。core-model **不可见** core-engine（零引擎依赖）⇒ 本测试
 * 只能按下方推导链钉**字面量**，作用退化为「守住 core-model 侧常量不被单独改动」。
 *
 * 推导链（W64 立、W67 B1 按离线重测重标定；禁无据拍值）：
 * 1. native 思考预算 = `resolveThinkingBudget(0, 1024)` = **512 token**（core-engine 半预算
 *    语义；该公式与其单测 `ThinkingBudgetTest` 已在 core-engine 侧钉住）；
 * 2. **实测 r_max = 5.06 chars/token**（W67 离线重测：W62/W65/W66 三波 31 样本官方
 *    tokenizer 精确计数；W62 历史声称的 8.4 系口径伪影 —— 同样本 2051 字符 = 469 token，
 *    r=4.37 —— 已证伪）⇒ 取字符当量上界系数 **C=9**（安全窗口 (5.06, 10.11) 上部带余量取值，
 *    故障不对称要求向上取）；
 * 3. 默认值 = 512 × 9 = **4608**（= `thinkingCharBudgetFor(1024, 0)`）。
 *
 * 目的：将来有人单独动 `THINKING_CHAR_BUDGET`（或 core-engine 侧「半预算」公式 / 系数 C）
 * 时，本测试或 core-engine 的不变式测试会红，把他引回上面的推导链重新标定，而非静默漂移
 * （同族历史坑：两份口径各自演化 ⇒ 检测/守卫静默失效）。
 *
 * 本测试**不**钉 [StreamRepetitionDetector.TEXT_RUNAWAY_CHARS] —— 它与 thinking 预算语义
 * 独立（text 流撞的是 maxOutputToken 硬截、无 native 兜底），两值不得联动调整（见两者
 * KDoc 的交叉注记），钉在一起反而会诱导联动。
 *
 * 纯 JVM：零协程 / 零 Android 依赖。
 */
class ThinkingCharBudgetInvariantTest {

    @Test
    fun `思考字符预算默认值等于1024档派生式取值`() {
        // 推导链（见类 KDoc）：resolveThinkingBudget(0, 1024) = 512 token × C=9 = 4608。
        val nativeThinkingTokens = 512
        val charsPerTokenCeil = 9
        assertEquals(
            nativeThinkingTokens * charsPerTokenCeil,
            StreamRepetitionDetector.THINKING_CHAR_BUDGET,
            "THINKING_CHAR_BUDGET 必须保持「maxTokens=1024 下 native 半预算（512 token）× 字符当量上界系数 C=9 = 4608」的派生默认值；改值前先读 StreamRepetitionDetector KDoc 与 core-engine ThinkingBudgetInvariantTest（跨模块单源钉）",
        )
    }
}
