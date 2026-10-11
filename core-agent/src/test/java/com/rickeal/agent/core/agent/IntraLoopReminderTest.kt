package com.rickeal.agent.core.agent

import com.rickeal.agent.core.model.StreamRepetitionDetector
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [intraLoopReminderFor] 的 JVM 纯函数单测（W62 外提）。
 *
 * 钉住「思考预算耗尽 ≠ 重复循环」的语义分流：原实现两种 verdict 共用
 * INTRA_LOOP_REMINDER（「你刚才的输出陷入重复循环」）⇒ 模型据此道歉、用户看不到答案
 * （W62 真机定案：gemma 图片问答，thinking 合法长推理被误判为循环）。
 *
 * 纯 JVM：入参是 String?，零协程 / 零 Android 依赖。
 *
 * ⚠️ 断言只钉「两文案互斥的判别特征词」（思考过长 / 重复循环），不钉整句措辞 ——
 * 文案可微调，但「思考预算 marker 不得复用重复循环文案」这条语义不许回归。
 */
class IntraLoopReminderTest {

    @Test
    fun `思考预算标记返回专用文案且不含重复循环字样`() {
        val reminder = intraLoopReminderFor(StreamRepetitionDetector.THINKING_BUDGET_MARKER)
        assertTrue(
            reminder.contains("思考过程过长"),
            "thinking_char_budget 必须命中思考过长专用文案，实际：$reminder",
        )
        assertTrue(
            reminder.contains("请直接给出最终答案"),
            "思考过长文案必须直陈「请直接给出最终答案」（空正文情形由措辞天然覆盖），实际：$reminder",
        )
        assertFalse(
            reminder.contains("重复循环"),
            "思考预算耗尽不得复用「重复循环」文案（W62 定案），实际：$reminder",
        )
    }

    @Test
    fun `真重复循环标记返回通用重复循环文案`() {
        // 覆盖两条非预算 marker：短签名连击（char_run）与 text 流铺陈（text_runaway_sentence）。
        val markers = listOf(
            StreamRepetitionDetector.CHAR_RUN_MARKER,
            StreamRepetitionDetector.TEXT_RUNAWAY_MARKER,
        )
        for (marker in markers) {
            val reminder = intraLoopReminderFor(marker)
            assertTrue(
                reminder.contains("重复循环"),
                "marker=$marker 必须命中通用「重复循环」文案，实际：$reminder",
            )
            assertFalse(
                reminder.contains("思考过程过长"),
                "marker=$marker 不得命中思考过长专用文案，实际：$reminder",
            )
        }
    }

    @Test
    fun `空标记兜底返回通用重复循环文案`() {
        val reminder = intraLoopReminderFor(null)
        assertTrue(
            reminder.contains("重复循环"),
            "marker=null 兜底必须走通用「重复循环」文案，实际：$reminder",
        )
        assertFalse(
            reminder.contains("思考过程过长"),
            "marker=null 不得命中思考过长专用文案，实际：$reminder",
        )
    }
}
