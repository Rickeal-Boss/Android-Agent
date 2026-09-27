package com.rickeal.agent.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AI 能力档位与工具效果声明（Wave 26）。
 *
 * 这组用例锁住的是**能力判定的真值表**与**默认值的 fail-closed 方向** —— 两者都是
 * 「改错了不会报错、只会静默放宽权限」的类型，必须由测试钉住：
 * - 档位与效果是二维判定，三档 × 两效果 = 6 个格子的期望值必须逐个写死；
 * - `ToolSpec.effect` 的默认值必须是 WRITE，否则新增工具会**默认可写**；
 * - 枚举的序列化名是落盘契约（DataStore 存 `mode.name`），改名即等于静默重置用户设置。
 */
class AiCapabilityModeTest {

    // ── 判定真值表：三档 × 两效果 ────────────────────────────────────────────

    @Test
    fun `ReadOnly 对 WRITE 要求授权`() {
        assertTrue(AiCapabilityMode.READ_ONLY.requiresApprovalFor(ToolEffect.WRITE))
    }

    @Test
    fun `ReadOnly 对 READ 不要求授权`() {
        // 只读档位的语义是「收窄写」，不是「什么都问」—— 把 READ 也纳入授权会制造
        // 噪音弹卡，训练用户无脑点同意，反而降低真实风险的辨识度。
        assertEquals(false, AiCapabilityMode.READ_ONLY.requiresApprovalFor(ToolEffect.READ))
    }

    @Test
    fun `WorkspaceWrite 是默认档且零行为变化`() {
        // 默认档必须与「引入档位之前」的行为逐字节一致：只由工具自身的危险标记
        // 与参数门控决定弹卡，档位本身不追加任何授权。
        assertEquals(false, AiCapabilityMode.WORKSPACE_WRITE.requiresApprovalFor(ToolEffect.READ))
        assertEquals(false, AiCapabilityMode.WORKSPACE_WRITE.requiresApprovalFor(ToolEffect.WRITE))
    }

    @Test
    fun `Full 不追加授权`() {
        assertEquals(false, AiCapabilityMode.FULL.requiresApprovalFor(ToolEffect.READ))
        assertEquals(false, AiCapabilityMode.FULL.requiresApprovalFor(ToolEffect.WRITE))
    }

    @Test
    fun `只有 ReadOnly 档会收紧`() {
        // 反向断言：除 READ_ONLY 外，任何档位都不得对 WRITE 追加授权 ——
        // 防止将来有人给 WORKSPACE_WRITE 也加上门控，那会直接改变所有存量用户的行为。
        val modes = AiCapabilityMode.entries.filter { it != AiCapabilityMode.READ_ONLY }
        for (m in modes) {
            assertEquals(false, m.requiresApprovalFor(ToolEffect.WRITE), "档位 $m 不应收紧 WRITE")
        }
    }

    // ── 默认值的 fail-closed 方向 ────────────────────────────────────────────

    @Test
    fun `ToolSpec 的 effect 默认是 WRITE`() {
        // 新增工具若忘了声明 effect，必须落在「写」这一侧（保守），
        // 而不是被静默当作只读工具放行。
        val spec = ToolSpec(name = "some_new_tool", description = "d")
        assertEquals(ToolEffect.WRITE, spec.effect)
    }

    @Test
    fun `显式声明 READ 可覆盖默认值`() {
        val spec = ToolSpec(name = "reader", description = "d", effect = ToolEffect.READ)
        assertEquals(ToolEffect.READ, spec.effect)
    }

    // ── 落盘契约 ────────────────────────────────────────────────────────────

    @Test
    fun `档位枚举名是落盘契约`() {
        // SettingsRepository 存 mode.name（与 HAPTIC_LEVEL 同款）。改名 = 存量用户
        // 设置静默回退到默认档，属于破坏性变更，必须显式改这条断言才可能发生。
        assertEquals("READ_ONLY", AiCapabilityMode.READ_ONLY.name)
        assertEquals("WORKSPACE_WRITE", AiCapabilityMode.WORKSPACE_WRITE.name)
        assertEquals("FULL", AiCapabilityMode.FULL.name)
    }

    @Test
    fun `效果枚举名是落盘契约`() {
        assertEquals("READ", ToolEffect.READ.name)
        assertEquals("WRITE", ToolEffect.WRITE.name)
    }

    @Test
    fun `枚举顺序稳定`() {
        // 顺序参与 entries/ordinal，改序会影响任何按序号持久化的实现（当前没有，
        // 但把顺序钉住比事后排查便宜）。
        assertEquals(
            listOf(AiCapabilityMode.READ_ONLY, AiCapabilityMode.WORKSPACE_WRITE, AiCapabilityMode.FULL),
            AiCapabilityMode.entries.toList(),
        )
        assertEquals(listOf(ToolEffect.READ, ToolEffect.WRITE), ToolEffect.entries.toList())
    }
}
