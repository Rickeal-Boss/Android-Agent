package com.rickeal.agent.core.agent

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [thinkingOnlyEmptyDiagnostic] 的 JVM 纯函数单测（W66 B1，N-2 观测式兜底）。
 *
 * 判据 = `thinking 非空 ∧ 正文空 ∧ 无 loopMarker`。纯 JVM：入参是 String / Boolean，
 * 零协程 / 零引擎依赖（warn 落盘由调用点负责，不在本函数）。
 *
 * ⚠️ 断言只钉「命中 / 不命中」的结构形态与判别特征词，不钉整句措辞 —— 文案可微调，
 * 但「只有 thinking 非空且正文空且无 marker 才命中」这条语义不许回归（fail-open 三断言）。
 */
class ThinkingOnlyEmptyDiagnosticTest {

    @Test
    fun `思考非空正文空且无标记时命中观测`() {
        val diag = thinkingOnlyEmptyDiagnostic(
            thinking = "让我先分析一下用户的问题，逐步推导……",
            answer = "",
            hasLoopMarker = false,
        )
        assertNotNull(diag, "thinking 非空 + 正文空 + 无 marker 必须命中 N-2 观测")
        assertTrue(diag.contains("N-2"), "命中文案须带 N-2 标识，实际：$diag")
        assertTrue(diag.contains("字符"), "命中文案须申报 token 为字符估算（软样本），实际：$diag")
    }

    @Test
    fun `思考为空时不命中观测`() {
        val diag = thinkingOnlyEmptyDiagnostic(
            thinking = "",
            answer = "",
            hasLoopMarker = false,
        )
        assertNull(diag, "thinking 为空 ⇒ 模型真的没产出，走既有空输出路径，不得命中 N-2")
    }

    @Test
    fun `正文非空时不命中观测`() {
        val diag = thinkingOnlyEmptyDiagnostic(
            thinking = "思考一段",
            answer = "这是正文答案",
            hasLoopMarker = false,
        )
        assertNull(diag, "正文非空 ⇒ native 已切回正文，不得命中 N-2")
    }

    @Test
    fun `带循环标记时不命中观测`() {
        val diag = thinkingOnlyEmptyDiagnostic(
            thinking = "思考一段",
            answer = "",
            hasLoopMarker = true,
        )
        assertNull(diag, "loopMarker 非空 ⇒ 走我方循环截断路径，不得命中 N-2（fail-open 排除项）")
    }
}
