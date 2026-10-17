package com.rickeal.agent.feature.settings

import com.rickeal.agent.core.engine.local.ModelHealthProbe
import com.rickeal.agent.core.model.ModelHealthVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [probePresentation] 的 JVM 纯函数单测（W71 B4：产品件 T-2/I-2 的展示判据）。
 *
 * 为什么能跑在 JVM 上：被测对象是**展示层纯函数**（无 Compose / 无 Android / 无
 * 协程依赖）—— 它只读 [ProbeUiState] + `knownBrokenOn` 字符串，产出全部文案与
 * [ProbeTone] 配色意图（颜色对象本身的映射在 Composable 侧 [probeToneColor]）。
 * 刻意**不构造** [DiagnosticsViewModel]：它依赖 AppContainer / 协程，JVM 上不可测。
 *
 * 钉住三组语义（W71 冻结判据）：
 *  1. PASS ∧ broken=null ⇒ headline / 第二行**逐字维持** W71 前文案（⛔ 不得把
 *     「未登记」显示成「已登记坏」）；
 *  2. PASS ∧ broken=非空 ⇒ headline 改「结论：通过（自检面）」+ 并列登记行
 *     （warning 标记，与 DEGRADED 同口径）；
 *  3. BAD / DEGRADED / NotRun / Failed / Idle / Running 文案**不受** `knownBrokenOn`
 *     影响（broken 在机也不叠加登记行 / 免责行）。
 */
class DiagnosticsProbePresentationTest {

    private fun completed(verdict: ModelHealthVerdict) = ProbeUiState.Completed(
        verdict = verdict,
        hits = emptyList(),
        sample = "样例输出",
        elapsedMs = 1234L,
    )

    // ── ① PASS ∧ broken=null ⇒ 原文案（未登记 ≠ 已登记坏） ─────────────────

    @Test
    fun passWithKnownBrokenNullKeepsVerbatimHeadlineAndDetail() {
        val p = probePresentation(completed(ModelHealthVerdict.PASS), knownBrokenOn = null)
        // headline 逐字维持 W71 前文案（「结论：通过（PASS）」，不是「自检面」）
        assertEquals("结论：通过（PASS）", p.headline)
        assertEquals(ProbeTone.NEUTRAL, p.headlineTone)
        // 第二行（耗时·采样预览）逐字维持
        assertEquals("耗时 1234ms · 采样：样例输出", p.detail?.text)
        assertEquals(ProbeTone.SUBTLE, p.detail?.tone)
        // ⛔ 未登记 ⇒ 禁止渲染登记行
        assertNull(p.knownBroken, "knownBrokenOn=null 时不得渲染登记行")
        // 免责行对任意 PASS 生效（含未登记）：假阴性盲区对健康模型同样成立
        assertEquals(
            "PASS = 未检出已知失败面，不构成输出质量保证",
            p.disclaimer?.text,
        )
        assertEquals(ProbeTone.WARNING, p.disclaimer?.tone)
    }

    // ── ② PASS ∧ broken=非空 ⇒ 并列文案 + warning 标记 ─────────────────────

    @Test
    fun passWithKnownBrokenShowsSelfCheckHeadlineAndParallelLine() {
        val broken = "2026-10-06 起 GPU 容器加载失败"
        val p = probePresentation(completed(ModelHealthVerdict.PASS), knownBrokenOn = broken)
        // 结论行降为「自检面」口径：本次自检通过 ≠ 模型整体健康
        assertEquals("结论：通过（自检面）", p.headline)
        assertEquals(ProbeTone.NEUTRAL, p.headlineTone)
        // 并列登记行 + warning 标记（与 DEGRADED 同口径），原文逐字带上
        assertEquals("该模型已登记已知不可用：$broken", p.knownBroken?.text)
        assertEquals(ProbeTone.WARNING, p.knownBroken?.tone)
        // 既有的耗时·采样第二行不受影响（登记行是追加，不是替换）
        assertEquals("耗时 1234ms · 采样：样例输出", p.detail?.text)
        assertEquals(ProbeTone.SUBTLE, p.detail?.tone)
        // 免责行与登记行同家族（warning）口径
        assertEquals(
            "PASS = 未检出已知失败面，不构成输出质量保证",
            p.disclaimer?.text,
        )
        assertEquals(ProbeTone.WARNING, p.disclaimer?.tone)
    }

    // ── ③ BAD / DEGRADED 文案不受影响（broken 在机也不叠加） ───────────────

    @Test
    fun degradedVerdictUnaffectedByKnownBroken() {
        val p = probePresentation(
            completed(ModelHealthVerdict.DEGRADED),
            knownBrokenOn = "2026-10-06 起 GPU 容器加载失败",
        )
        assertEquals("结论：降级（DEGRADED）", p.headline)
        assertEquals(ProbeTone.WARNING, p.headlineTone)
        assertEquals("耗时 1234ms · 采样：样例输出", p.detail?.text)
        assertNull(p.knownBroken, "非 PASS 态不得追加登记行")
        assertNull(p.disclaimer, "免责行仅 PASS 态渲染")
    }

    @Test
    fun badVerdictUnaffectedByKnownBroken() {
        val p = probePresentation(
            completed(ModelHealthVerdict.BAD),
            knownBrokenOn = "2026-10-06 起 GPU 容器加载失败",
        )
        assertEquals("结论：异常（BAD）", p.headline)
        assertEquals(ProbeTone.DANGER, p.headlineTone)
        assertEquals("耗时 1234ms · 采样：样例输出", p.detail?.text)
        assertNull(p.knownBroken, "非 PASS 态不得追加登记行")
        assertNull(p.disclaimer, "免责行仅 PASS 态渲染")
    }

    // ── ③ NotRun / Failed / Idle / Running 文案不受影响 ────────────────────

    @Test
    fun notRunFailedIdleRunningUnaffectedByKnownBroken() {
        val broken = "2026-10-06 起 GPU 容器加载失败"
        val notLoaded = probePresentation(
            ProbeUiState.NotRun(ModelHealthProbe.NotRunReason.ENGINE_NOT_LOADED),
            broken,
        )
        assertEquals("请先在模型页加载模型，再运行自检", notLoaded.headline)
        assertNull(notLoaded.detail)
        assertNull(notLoaded.knownBroken)
        assertNull(notLoaded.disclaimer)

        val busy = probePresentation(
            ProbeUiState.NotRun(ModelHealthProbe.NotRunReason.ENGINE_BUSY),
            broken,
        )
        assertEquals("正在生成，请稍后再试", busy.headline)
        assertNull(busy.knownBroken)
        assertNull(busy.disclaimer)

        val noActive = probePresentation(
            ProbeUiState.NotRun(ModelHealthProbe.NotRunReason.NO_ACTIVE_MODEL),
            broken,
        )
        assertEquals("当前没有激活模型", noActive.headline)
        assertNull(noActive.knownBroken)
        assertNull(noActive.disclaimer)

        val failed = probePresentation(ProbeUiState.Failed("boom"), broken)
        assertEquals("自检未跑通：boom", failed.headline)
        assertEquals(ProbeTone.WARNING, failed.headlineTone)
        assertEquals(
            "执行失败（非质量结论）：这不代表模型坏，请确认模型已加载、设备未过热后重试",
            failed.detail?.text,
        )
        assertNull(failed.knownBroken)
        assertNull(failed.disclaimer)

        val idle = probePresentation(ProbeUiState.Idle, broken)
        assertEquals("尚未运行", idle.headline)
        assertEquals(ProbeTone.SUBTLE, idle.headlineTone)
        assertNull(idle.detail)
        assertNull(idle.knownBroken)
        assertNull(idle.disclaimer)

        val running = probePresentation(ProbeUiState.Running, broken)
        assertEquals("正在自检…", running.headline)
        assertEquals(ProbeTone.ONGLASS, running.headlineTone)
        assertNull(running.detail)
        assertNull(running.knownBroken)
        assertNull(running.disclaimer)
    }
}
