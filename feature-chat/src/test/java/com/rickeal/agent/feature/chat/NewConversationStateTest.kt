package com.rickeal.agent.feature.chat

import com.rickeal.agent.core.model.AiCapabilityMode
import com.rickeal.agent.core.model.InferenceBackend
import com.rickeal.agent.core.model.InferenceConfig
import com.rickeal.agent.core.model.ToolDisclosureMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「新会话」状态重建的 JVM 单测（W84-03 / P1 安全向）。
 *
 * 被测 = 生产路径实际调用的纯函数 [ChatUiState.forNewConversation]
 * （[ChatViewModel.onNewConversation] 直接调用它）。纯 JVM：入参只有 core-model 的
 * 纯数据类与两个纯枚举，无 Android / Compose / ViewModel 依赖。
 *
 * 钉住两组语义：
 * 1. **独立冷流字段必须存活**（`capabilityMode` / `disclosureMode`）：它们各由一条
 *    `settingsRepository.*` 冷流一次性订阅后写回，「点新对话」时上游不变 ⇒ 流不重发 ⇒
 *    重建时打回默认值就**无人恢复**。`capabilityMode` 回落默认 = `READ_ONLY → WORKSPACE_WRITE`
 *    （权限面被悄悄放宽，安全向回归）；`disclosureMode` 回落默认 = 工具清单全量进提示词。
 * 2. **会话级瞬态必须清掉**（`activeModelWarning`）：上一次会话的提示卡不跨会话残留。
 *
 * 回归背景（本类存在的理由）：W68 B2-R1 已在 `activeModelKnownBroken` 上踩过同坑一次，
 * W84-03 是同一个坑的第二次（`capabilityMode` / `disclosureMode`）。断言写成
 * 「取非默认值 → 重建后仍相等」，故任何人日后把这些字段从重建参数里删掉都会判红。
 */
class NewConversationStateTest {

    /**
     * 全部字段都取**非默认值**的状态。
     *
     * 刻意不用默认值：若用默认值，即便重建时把这些字段整个丢掉、回落构造默认，
     * 断言也会绿 —— 那是典型的 vacuous 假绿（判据没被行使）。
     */
    private fun nonDefaultState(): ChatUiState = ChatUiState(
        title = "上一会话",
        capabilityMode = AiCapabilityMode.READ_ONLY,
        disclosureMode = ToolDisclosureMode.ON_DEMAND,
        activeModelKnownBroken = "已知坏签名",
        activeModelWarning = "上一会话的旧提示",
        config = InferenceConfig(backend = InferenceBackend.GPU, maxTokens = 2048),
        agentMaxRounds = 12,
        toolsEnabled = false,
        error = "上一会话的错误",
        notice = "上一会话的自愈提示",
    )

    @Test
    fun `新会话保留能力档位（READ_ONLY 不得回落 WORKSPACE_WRITE）`() {
        val rebuilt = nonDefaultState().forNewConversation()
        assertEquals(
            AiCapabilityMode.READ_ONLY,
            rebuilt.capabilityMode,
            "能力档位由独立冷流写回、新会话不重发 ⇒ 必须沿用上一值（回落默认 = 权限面变宽）",
        )
    }

    @Test
    fun `新会话保留工具披露模式（ON_DEMAND 不得回落 FULL）`() {
        val rebuilt = nonDefaultState().forNewConversation()
        assertEquals(
            ToolDisclosureMode.ON_DEMAND,
            rebuilt.disclosureMode,
            "披露模式同属独立冷流字段 ⇒ 必须沿用上一值（回落默认 = 工具清单全量进提示词）",
        )
    }

    @Test
    fun `新会话保留已知坏签名且清掉旧提示卡`() {
        val rebuilt = nonDefaultState().forNewConversation()
        assertEquals("已知坏签名", rebuilt.activeModelKnownBroken, "派生流不重发 ⇒ 必须沿用上一值")
        assertNull(rebuilt.activeModelWarning, "上一次会话的提示卡不得跨会话残留")
    }

    @Test
    fun `新会话保留配置与工具开关（参数面板预览态的载体）`() {
        val rebuilt = nonDefaultState().forNewConversation()
        assertEquals(12, rebuilt.agentMaxRounds, "Agent 最大轮次来自 inferenceConfig 冷流 ⇒ 必须保留")
        assertFalse(rebuilt.toolsEnabled, "工具开关来自 inferenceConfig 冷流 ⇒ 必须保留")
        assertEquals(InferenceBackend.GPU, rebuilt.config.backend, "整份 config 必须沿用")
        assertEquals(2048, rebuilt.config.maxTokens, "整份 config 必须沿用（含滑条预览值）")
    }

    @Test
    fun `新会话清掉消息与终态信息`() {
        val rebuilt = nonDefaultState().forNewConversation()
        assertTrue(rebuilt.messages.isEmpty(), "新会话必须清空消息")
        assertEquals("新对话", rebuilt.title, "标题回到默认（不是上一会话的标题）")
        assertNull(rebuilt.error, "上一次的错误不得跨会话残留")
        assertNull(rebuilt.notice, "上一次的自愈提示不得跨会话残留")
    }
}
