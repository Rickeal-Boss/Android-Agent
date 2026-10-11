package com.rickeal.agent.core.agent

import com.rickeal.agent.core.model.StreamRepetitionDetector
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [loopFailurePhraseFor] / [loopFailureUserMessageFor] 的 JVM 纯函数单测（W63 刀A）。
 *
 * 钉住「终态口径也要分流」：W62 只分流了提醒文案（[intraLoopReminderFor]），超限收尾的
 * 终止日志 / `BreakerKind.StreamLoop` evidence / `AgentEvent.Failed` 消息仍硬编码
 * 「重复循环」⇒ 思考预算耗尽的 run 与真循环在日志与诊断卡上不可区分（K-2 残留）。
 *
 * 纯 JVM：入参是 String?，零协程 / 零 Android 依赖。
 *
 * ⚠️ 断言只钉「两口径互斥的判别特征词」（思考预算耗尽 / 重复循环），不钉整句措辞 ——
 * 措辞可微调，但「思考预算 marker 的终态口径不得复用重复循环语义」这条不许回归。
 * 真循环路径的原句（「轮内重复循环」/「模型输出陷入重复循环，已停止本轮任务」）为既有
 * 语义，测试同时钉住不被误伤。
 */
class LoopFailurePhraseTest {

    @Test
    fun `思考预算标记的终态短语不含重复循环字样`() {
        val phrase = loopFailurePhraseFor(StreamRepetitionDetector.THINKING_BUDGET_MARKER)
        assertTrue(
            phrase.contains("思考预算耗尽"),
            "thinking_char_budget 的终态短语必须命中思考预算耗尽语义，实际：$phrase",
        )
        assertFalse(
            phrase.contains("重复循环"),
            "思考预算耗尽的终态短语不得复用「重复循环」语义（W63 刀A 定案），实际：$phrase",
        )
    }

    @Test
    fun `思考预算标记的失败消息不含重复循环字样`() {
        val message = loopFailureUserMessageFor(StreamRepetitionDetector.THINKING_BUDGET_MARKER)
        assertTrue(
            message.contains("思考预算耗尽"),
            "thinking_char_budget 的 Failed 消息必须命中思考预算耗尽语义，实际：$message",
        )
        assertFalse(
            message.contains("重复循环"),
            "思考预算耗尽的 Failed 消息不得复用「重复循环」语义，实际：$message",
        )
    }

    @Test
    fun `真重复循环标记的终态口径保持重复循环语义`() {
        // 覆盖两条非预算 marker：短签名连击（char_run）与 text 流铺陈（text_runaway_sentence）。
        val markers = listOf(
            StreamRepetitionDetector.CHAR_RUN_MARKER,
            StreamRepetitionDetector.TEXT_RUNAWAY_MARKER,
        )
        for (marker in markers) {
            val phrase = loopFailurePhraseFor(marker)
            assertTrue(
                phrase.contains("重复循环"),
                "marker=$marker 的终态短语必须保持重复循环语义，实际：$phrase",
            )
            assertFalse(
                phrase.contains("思考预算耗尽"),
                "marker=$marker 的终态短语不得命中思考预算语义，实际：$phrase",
            )
            val message = loopFailureUserMessageFor(marker)
            assertTrue(
                message.contains("重复循环"),
                "marker=$marker 的 Failed 消息必须保持重复循环语义，实际：$message",
            )
        }
    }

    @Test
    fun `空标记兜底的终态口径保持重复循环语义`() {
        val phrase = loopFailurePhraseFor(null)
        assertTrue(
            phrase.contains("重复循环"),
            "marker=null 的终态短语兜底必须走重复循环语义，实际：$phrase",
        )
        assertFalse(
            phrase.contains("思考预算耗尽"),
            "marker=null 的终态短语兜底不得命中思考预算语义，实际：$phrase",
        )
        val message = loopFailureUserMessageFor(null)
        assertTrue(
            message.contains("重复循环"),
            "marker=null 的 Failed 消息兜底必须走重复循环语义，实际：$message",
        )
    }
}
