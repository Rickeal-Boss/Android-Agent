package com.rickeal.agent.core.engine.local

import com.rickeal.agent.core.model.StreamRepetitionDetector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [thinkingCharBudgetFor] 的**跨模块单源不变式**（W64 K-1 治本）。
 *
 * core-engine 是唯一**同时可见** core-engine 派生函数（[resolveThinkingBudget] /
 * [thinkingCharBudgetFor]）与 core-model 检测器常量
 * （[StreamRepetitionDetector.THINKING_CHAR_BUDGET]）的模块 ⇒ 跨模块「单源」关系只能在此钉。
 *
 * 两条不变式：
 * 1. **晚于 native**：`thinkingCharBudgetFor(m, 0) > resolveThinkingBudget(0, m) * 8.4`
 *    （8.4 = 实测上界 chars/token，W62 真机：2048 字符 ≈244 token）—— 字符守卫必须晚于
 *    native 优雅路径（预算耗尽 ⇒ 强制吐思考结束符 ⇒ 转回正文生成），否则重现 W62
 *    「守卫抢跑、合法长推理被误判循环」病灶；
 * 2. **默认值单源**：`StreamRepetitionDetector.THINKING_CHAR_BUDGET == thinkingCharBudgetFor(1024, 0)`
 *    —— core-model 不可见 core-engine，只能取字面量 8192，其与派生式相等由此钉住
 *    （单源靠测试保证，勿让两处各自演化）。
 *
 * 纯 JVM：零协程 / 零 Android / 零 native。断言收尾一律用返回 `Unit` 的函数
 * （`assertEquals` / `assertTrue`），避免 JUnit 4 误判方法签名非 `void`。
 *
 * ⚠️ **覆盖边界如实申报（W64 审查 P3-c）**：本测试钉的是**代数不变式**（守卫 = native 预算
 * × C，且 C > 实测上界）与**默认值单源相等**（core-model 常量 == 派生式在 1024 档取值）。
 * 以下两点**不由本测试覆盖**：① `AgentRunner` 的**注入路径真的生效**（该点由代码审查 +
 * 真机日志覆盖）；② C **等于真实 chars/token**（只能由真机标定 —— W64 真机两 run 的
 * `thinklen`（gemma 2025 / R1 1598 字符）均**远低于**阈值 ⇒ 「守卫晚于 native」的运行时
 * 判据在本波 ⛔ **vacuous（未行使）**，不得据本测试宣称治本已真机验证）。
 */
class ThinkingBudgetInvariantTest {

    /** 实测上界：W62 真机 2048 字符 ≈244 token（见 `README.md` W62 段）。 */
    private val measuredCharsPerTokenCeil = 8.4

    /** 不变式 1：滑条全域（64..8192 步进 64），字符守卫恒严格大于 native 的实测字符当量。 */
    @Test
    fun `字符守卫严格晚于native预算的实测字符当量`() {
        for (maxTokens in 64..8192 step 64) {
            val nativeTokens = resolveThinkingBudget(configured = 0, maxTokens = maxTokens)
            val guardChars = thinkingCharBudgetFor(maxTokens, configuredThinkingBudget = 0)
            assertTrue(
                guardChars > nativeTokens * measuredCharsPerTokenCeil,
                "maxTokens=$maxTokens：字符守卫 $guardChars 必须 > native $nativeTokens token " +
                    "× $measuredCharsPerTokenCeil = ${nativeTokens * measuredCharsPerTokenCeil}",
            )
        }
    }

    /** 不变式：字符守卫对 maxTokens 单调不减（滑条调大不得让守卫反而更早触发）。 */
    @Test
    fun `字符守卫对maxTokens单调不减`() {
        var previous = thinkingCharBudgetFor(maxTokens = 64, configuredThinkingBudget = 0)
        for (maxTokens in 128..8192 step 64) {
            val current = thinkingCharBudgetFor(maxTokens = maxTokens, configuredThinkingBudget = 0)
            assertTrue(
                current >= previous,
                "maxTokens=$maxTokens：字符守卫 $current 不得小于前一档 $previous",
            )
            previous = current
        }
    }

    /** 单源钉：core-model 的默认常量 == 派生式在 maxTokens=1024 档的取值。 */
    @Test
    fun `检测器默认值等于派生式在1024档的取值`() {
        assertEquals(
            thinkingCharBudgetFor(maxTokens = 1024, configuredThinkingBudget = 0),
            StreamRepetitionDetector.THINKING_CHAR_BUDGET,
            "core-model 的 THINKING_CHAR_BUDGET 必须等于 thinkingCharBudgetFor(1024, 0)（单源靠本测试保证，勿让两处各自演化）",
        )
    }
}
