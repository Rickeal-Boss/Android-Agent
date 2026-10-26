package com.rickeal.agent.feature.chat

import com.rickeal.agent.core.model.ChatMessage
import com.rickeal.agent.core.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `isFirstRoundWithoutToolOutput` 回归测试（W84-21）：纯函数，JVM 直测，不依赖 Compose / Android。
 *
 * 三条判据（与 [isFirstRoundWithoutToolOutput] KDoc 一致）：
 *  - 有工具轨迹 ⇒ 非首轮（false）；
 *  - 无工具轨迹且消息里没有 MODEL/TOOL 角色、也没有非空 toolResults ⇒ 首轮（true）；
 *  - 消息含 MODEL/TOOL 角色 ⇒ 非首轮（false）。
 */
class FirstRoundWithoutToolOutputTest {

    @Test
    fun `withToolTrace_returnsFalse`() {
        val messages = listOf(ChatMessage(role = Role.USER))
        val traces = listOf(ToolTrace(id = "t1", name = "x", arguments = "{}"))
        assertEquals(false, isFirstRoundWithoutToolOutput(messages, traces))
    }

    @Test
    fun `firstRoundNoToolOutput_returnsTrue`() {
        val messages = listOf(ChatMessage(role = Role.USER))
        assertEquals(true, isFirstRoundWithoutToolOutput(messages, emptyList()))
    }

    @Test
    fun `messageWithModelRole_returnsFalse`() {
        val messages = listOf(ChatMessage(role = Role.MODEL))
        assertEquals(false, isFirstRoundWithoutToolOutput(messages, emptyList()))
    }
}
