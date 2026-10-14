package com.rickeal.agent.core.agent

import com.rickeal.agent.core.engine.local.thinkingCharBudgetFor
import com.rickeal.agent.core.model.StreamRepetitionDetector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R-4 N-2 触发链**离线替身**单测（W68 A4，第十二审 R-4）。
 *
 * ## 为什么需要（真机唯一路径的解放）
 *
 * W68 真机批「2047 补丁臂」判据 = 临时把 `ModelSamplingProfiles` 的
 * `minThinkingTokenBudget` 由 1536 改到 2047 ⇒ native 思考吃满 2047 token、正文几乎必空
 * ⇒ 期望 N-2 warn 命中（`AgentRunner` 空答分支唯一落点）。该判据此前**只能真机行使**；
 * 本测试把同一条触发链搬成 JVM 可行使的替身：真机 2047 臂降级为最终回归确认。
 *
 * ## 触发链控制流（从现役源码读出；判据以源码为准）
 *
 *  1. `AgentRunner` 组装 `InferenceConfig` 后（AgentRunner.kt:764-769）以
 *     `thinkingCharBudgetFor(config.maxTokens, config.thinkingTokenBudget)` 派生**字符**
 *     预算，注入 `StreamRepetitionDetector(thinkingCharBudget = …)`（构造参数即判定③阈值；
 *     常量 `THINKING_CHAR_BUDGET=4608` 只是未注入时的默认/兜底）。
 *  2. 流式 thinking 文本经 `observeThinking` → `observe(…, inThinking=true)`：累计字符
 *     **超过注入预算** ⇒ `Verdict.LoopDetected(THINKING_BUDGET_MARKER)`（判定③，
 *     StreamRepetitionDetector.kt:278-282）。该路径 = **循环截断**：loopMarker 非空时
 *     `handlePostStreamSignals` 恒先 `NextRound`/`Terminal`（AgentRunner.kt:2200 注释：
 *     走到空答分支时 loopMarker 结构性为 null）⇒ **到不了 N-2**。
 *  3. loopMarker 为空且剥协议后 answer 空（:2230 `if (answer.isBlank())`）⇒ 空答分支调
 *     `thinkingOnlyEmptyDiagnostic(thinking, answer, hasLoopMarker=false)` ⇒ 命中即
 *     `AgentLogStore.warn`（:2244-2248）。判据 = 三合取 `thinking 非空 ∧ 正文空 ∧ 无
 *     loopMarker`（AgentRunner.kt:417-428）。
 *
 * ## 2047 臂的算术（本测试复现的正是这条链）
 *
 * minThinkingTokenBudget=2047 ⇒ InferenceConfig.thinkingTokenBudget 被顶到 2047
 * （maxTokens 档 2048）⇒ native 思考预算 2047 token。注入 detector 的字符预算 =
 * `thinkingCharBudgetFor(2048, 2047)` = 2047 × 9（C=9）= **18423**；而实测字符/token
 * 上界 r_max = 5.06（W67 离线重测）⇒ 2047 token ≈ 10357 字符 < 18423 ⇒ 判定③
 * **不触发**（守卫恒晚于 native 优雅路径 = W62 病灶治本的设计意图）⇒ loopMarker 空 ⇒
 * 正文空 + thinking 非空（10357 > 旧默认 4608）⇒ **N-2 warn 命中**。
 *
 * ## 断言口径
 *
 * 只钉「命中 / 不命中」的结构形态与判别特征词（`N-2` / `字符`），**不钉整句措辞**
 * （同 W66 B1 `ThinkingOnlyEmptyDiagnosticTest` 既有约定）。
 *
 * 纯 JVM：零协程 / 零 Android / 零 native；warn 落盘不在本函数内（调用点负责）。
 */
class ThinkingBudgetN2SubstituteTest {

    /** 无句界长铺陈 filler（同 `StreamRepetitionDetectorTest` 既有辅助：无句界 ⇒ 只可能撞判定③）。 */
    private fun filler(n: Int): String =
        "abcdefghij".repeat(n / 10) + "abcdefghij".take(n % 10)

    /** 2047 臂档案参数（minThinkingTokenBudget=1536→2047 后 appliedTo 写入的同款值）。 */
    private val armMaxTokens = 2048
    private val armConfiguredBudget = 2047

    /** 2047 臂下 AgentRunner 会注入 detector 的派生字符预算。 */
    private val armInjectedBudget: Int =
        thinkingCharBudgetFor(armMaxTokens, armConfiguredBudget)

    /** 伪造流：2047 token × r≈5 chars/token 的英文思维链（> 旧默认 4608，< 派生预算 18423）。 */
    private val fakeThinking: String = filler(2047 * 5)

    /**
     * ① 2047 臂主链：伪造流（> 4608 字符）喂注入派生预算的 detector ⇒ 判定③不触发
     *    （loopMarker 空）⇒ 空答分支 N-2 观测命中（含 N-2 / 字符特征词）。
     */
    @Test
    fun `2047臂伪造流走N2观测而非循环截断`() {
        assertTrue(
            fakeThinking.length > StreamRepetitionDetector.THINKING_CHAR_BUDGET,
            "fixture 必须真的跨过旧默认预算 4608（当前 ${fakeThinking.length}），否则本 case 空转",
        )
        assertEquals(
            18423,
            armInjectedBudget,
            "2047 臂派生预算必须为 2047 × C(9) = 18423（注入值算错则本链判据失真）",
        )

        val detector = StreamRepetitionDetector(thinkingCharBudget = armInjectedBudget)
        var loopMarker: String? = null
        for (chunk in fakeThinking.chunked(1024)) {
            val verdict = detector.observeThinking(chunk)
            if (verdict is StreamRepetitionDetector.Verdict.LoopDetected) {
                loopMarker = verdict.repeatedSignature
                break
            }
        }
        assertNull(
            loopMarker,
            "判定③ 不得触发：派生预算 18423 > 流长 ${fakeThinking.length}（r≈5 < C=9，守卫恒晚于 native）",
        )

        // 承接 AgentRunner 空答分支调用点（:2244）：hasLoopMarker=false 是结构性事实
        // （loopMarker != null 时 handlePostStreamSignals 恒先 return，到不了本分支）。
        val diag = thinkingOnlyEmptyDiagnostic(
            thinking = fakeThinking,
            answer = "",
            hasLoopMarker = false,
        )
        assertNotNull(diag, "thinking 非空 + 正文空 + 无 marker 必须命中 N-2 观测")
        assertTrue(diag.contains("N-2"), "命中文案须带 N-2 标识（不钉整句），实际：$diag")
        assertTrue(diag.contains("字符"), "命中文案须申报 token 为字符估算（软样本），实际：$diag")
    }

    /**
     * ② 互斥面：thinking 真超**派生**预算（native 失效兜底场景）⇒ 判定③先触发
     *    （THINKING_BUDGET_MARKER）⇒ 走循环截断路径、N-2 结构性不可达。
     *    钉「两条路径互斥」的另一半：loopMarker 存在 ⇒ thinkingOnlyEmptyDiagnostic 恒 null
     *    （另一半由 W66 B1 `带循环标记时不命中观测` 钉，两测试互为闭环）。
     */
    @Test
    fun `超派生预算时循环截断先于N2观测`() {
        val detector = StreamRepetitionDetector(thinkingCharBudget = armInjectedBudget)
        val runaway = filler(armInjectedBudget + 64)
        var loopMarker: String? = null
        for (chunk in runaway.chunked(4096)) {
            val verdict = detector.observeThinking(chunk)
            if (verdict is StreamRepetitionDetector.Verdict.LoopDetected) {
                loopMarker = verdict.repeatedSignature
                break
            }
        }
        assertEquals(
            StreamRepetitionDetector.THINKING_BUDGET_MARKER,
            loopMarker,
            "超派生预算必须命中判定③（thinking_char_budget），而非其它判定",
        )
        assertNull(
            thinkingOnlyEmptyDiagnostic(
                thinking = runaway,
                answer = "",
                hasLoopMarker = true,
            ),
            "loopMarker 非空 ⇒ 走循环截断路径，N-2 不得命中（互斥，防双重归因）",
        )
    }
}
