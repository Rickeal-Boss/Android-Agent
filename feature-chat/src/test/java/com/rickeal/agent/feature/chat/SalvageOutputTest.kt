package com.rickeal.agent.feature.chat

import com.rickeal.agent.core.agent.TerminationReason
import com.rickeal.agent.core.agent.breaker.Blocker
import com.rickeal.agent.core.agent.breaker.BottleneckReport
import com.rickeal.agent.core.agent.breaker.BreakerKind
import com.rickeal.agent.core.agent.breaker.Trip
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [shouldSalvageOutput] 的纯逻辑测试（Wave 47 项2「熔断时保留已见输出」的回归锚）。
 *
 * 为什么能跑在 JVM 上：被测的是纯 Kotlin `internal` 顶层函数，**无任何 Android / Compose /
 * ViewModel 依赖**。生产路径 `ChatRunCoordinator` 的 `AgentEvent.Failed` 分支真实调用它，故测试
 * 覆盖的是**真实逻辑而非镜像**（与本仓既有范式一致）。
 *
 * 覆盖 9 个 `Failed` 发射点的语义分类：预算 / 外部型熔断（应保留）vs 内容型熔断（乱文 / 空，
 * 不该保留）vs 真失败（无「用户已见有效输出」语义，不保留）。
 *
 * 断言收尾一律用返回 `Unit` 的函数（`assertTrue` / `assertFalse`）—— 避免 JUnit 4 误判非 `void`。
 */
class SalvageOutputTest {

    /** 构造只关心 tripped 的诊断卡（其余字段填占位）。 */
    private fun reportOf(vararg kinds: BreakerKind): BottleneckReport = BottleneckReport(
        task = "t",
        rounds = 1,
        elapsedMillis = 1000L,
        triedTools = emptyList(),
        tripped = kinds.mapIndexed { i, k -> Trip(kind = k, round = i + 1, evidence = k.name) },
        blocker = Blocker.BudgetExhausted,
        suggestions = emptyList(),
    )

    /** 预算型熔断（墙钟）+ 非空文本 ⇒ 应保留（真机 r5 场景）。 */
    @Test
    fun 熔断墙钟预算且文本非空应保留() {
        assertTrue(
            shouldSalvageOutput(
                terminatedBy = TerminationReason.BreakerTripped,
                report = reportOf(BreakerKind.WallClockBudget),
                salvageText = "已经输出的半截回答",
            ),
        )
    }

    /** 同条件但文本为空 / 全空白 ⇒ 不保留（无可保留的已见输出）。 */
    @Test
    fun 同条件但文本为空不保留() {
        assertFalse(
            shouldSalvageOutput(
                terminatedBy = TerminationReason.BreakerTripped,
                report = reportOf(BreakerKind.WallClockBudget),
                salvageText = "",
            ),
        )
        assertFalse(
            shouldSalvageOutput(
                terminatedBy = TerminationReason.BreakerTripped,
                report = reportOf(BreakerKind.WallClockBudget),
                salvageText = "   ",
            ),
        )
    }

    /** 内容型熔断（StreamLoop）：输出是被判定的乱文，清掉是设计意图 ⇒ 不保留。 */
    @Test
    fun 内容型熔断流式循环不保留() {
        assertFalse(
            shouldSalvageOutput(
                terminatedBy = TerminationReason.BreakerTripped,
                report = reportOf(BreakerKind.StreamLoop),
                salvageText = "被判定的乱文",
            ),
        )
    }

    /** 内容型熔断（EmptyOutput）：本就无输出 ⇒ 不保留。 */
    @Test
    fun 内容型熔断空输出不保留() {
        assertFalse(
            shouldSalvageOutput(
                terminatedBy = TerminationReason.BreakerTripped,
                report = reportOf(BreakerKind.EmptyOutput),
                salvageText = "某段文本",
            ),
        )
    }

    /** 真失败（非熔断 / terminatedBy 缺失）：无「用户已见有效输出」语义 ⇒ 不保留。 */
    @Test
    fun 真失败不保留() {
        assertFalse(
            shouldSalvageOutput(
                terminatedBy = TerminationReason.ModelStopped,
                report = null,
                salvageText = "半截文本",
            ),
        )
        assertFalse(
            shouldSalvageOutput(
                terminatedBy = null,
                report = reportOf(BreakerKind.WallClockBudget),
                salvageText = "半截文本",
            ),
        )
    }

    /** 其余四种预算 / 外部型熔断（热 / 振荡 / 失败连击 / 生成超时）均保留。 */
    @Test
    fun 其余四种预算外部型熔断均保留() {
        val kinds = listOf(
            BreakerKind.ThermalThrottle,
            BreakerKind.ToolCallOscillation,
            BreakerKind.ToolFailureStreak,
            BreakerKind.GenerationTimeout,
        )
        for (kind in kinds) {
            assertTrue(
                shouldSalvageOutput(
                    terminatedBy = TerminationReason.BreakerTripped,
                    report = reportOf(kind),
                    salvageText = "半截文本",
                ),
                "kind=$kind 应保留已见输出",
            )
        }
    }

    /** 终止者取 tripped 里**最后一个 HARD**：最后一个 HARD 是内容型 ⇒ 不保留。 */
    @Test
    fun 终止者取最后一个hard熔断() {
        assertTrue(
            shouldSalvageOutput(
                terminatedBy = TerminationReason.BreakerTripped,
                report = reportOf(BreakerKind.WallClockBudget, BreakerKind.ToolCallOscillation),
                salvageText = "半截文本",
            ),
        )
        assertFalse(
            shouldSalvageOutput(
                terminatedBy = TerminationReason.BreakerTripped,
                report = reportOf(BreakerKind.WallClockBudget, BreakerKind.StreamLoop),
                salvageText = "半截文本",
            ),
        )
    }
}
