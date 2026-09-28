package com.rickeal.agent.feature.chat

import com.rickeal.agent.core.agent.AgentEvent
import com.rickeal.agent.core.agent.TerminationReason
import com.rickeal.agent.core.agent.breaker.Blocker
import com.rickeal.agent.core.agent.breaker.BottleneckReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [ChatUiState.applyTerminalEvent] 的纯逻辑测试（Wave 31 流2 为 :feature-chat 点亮
 * 的测试源集 —— 此前该模块 src/test 为空，NO-SOURCE）。
 *
 * 为什么能跑在 JVM 上：被测的 `applyTerminalEvent` 是纯函数（只做 data class copy），
 * 其入参 [AgentEvent] / [BottleneckReport] / [TerminationReason] 全是 core-agent /
 * core-model 的纯 Kotlin 类型，无任何 Android / Compose 依赖；[ChatUiState] 虽带
 * `@Immutable`（Compose 注解，BINARY 保留，JVM 不在类加载期解析注解），但其默认构造
 * 只用到 core-model 的纯数据类，因此无需 Robolectric / returnDefaultValues。
 *
 * 钉住「事件 → 状态」终态映射的四条语义（Wave 30 诊断卡 + Wave 31 terminatedBy）：
 * 1. Finished 带 report ⇒ lastReport 被赋值；
 * 2. Failed 带 report ⇒ lastReport 被赋值；
 * 3. Finished 无 report ⇒ lastReport 为 null（正常结束不显示诊断卡）；
 * 4. terminatedBy 落进 lastTermination。
 */
class ChatUiStateTest {

    /** 造一张最小的诊断卡（字段取合法默认，内容不参与断言 —— 只比对整个对象）。 */
    private fun report() = BottleneckReport(
        task = "任务",
        rounds = 3,
        elapsedMillis = 1_000L,
        triedTools = emptyList(),
        tripped = emptyList(),
        blocker = Blocker.ModelDegraded,
        suggestions = listOf("建议"),
    )

    @Test
    fun `Finished 带 report 时 lastReport 被赋值`() {
        val state = ChatUiState().applyTerminalEvent(
            AgentEvent.Finished(text = "done", rounds = 2, usage = null, report = report()),
        )
        assertEquals(report(), state.lastReport)
    }

    @Test
    fun `Failed 带 report 时 lastReport 被赋值`() {
        val state = ChatUiState().applyTerminalEvent(
            AgentEvent.Failed(message = "boom", report = report()),
        )
        assertEquals(report(), state.lastReport)
    }

    @Test
    fun `Finished 无 report 时 lastReport 为 null`() {
        val state = ChatUiState().applyTerminalEvent(
            AgentEvent.Finished(text = "done", rounds = 2, usage = null),
        )
        assertNull(state.lastReport)
    }

    @Test
    fun `terminatedBy 落进 lastTermination`() {
        val finished = ChatUiState().applyTerminalEvent(
            AgentEvent.Finished(
                text = "done",
                rounds = 2,
                usage = null,
                terminatedBy = TerminationReason.MaxRounds,
            ),
        )
        assertEquals(TerminationReason.MaxRounds, finished.lastTermination)

        val failed = ChatUiState().applyTerminalEvent(
            AgentEvent.Failed(message = "boom", terminatedBy = TerminationReason.ModelStopped),
        )
        assertEquals(TerminationReason.ModelStopped, failed.lastTermination)
    }
}
