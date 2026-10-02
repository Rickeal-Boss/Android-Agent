package com.rickeal.agent.feature.chat

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [speedLabel] 的纯逻辑测试（Wave 47 项4「对话页生成速度状态行」的离线锚）。
 *
 * 钉住三件事：①`≈` 前缀必须在（粗估与气泡终态精确值的口径区分）；②首字用**秒**保留 1 位；
 * ③零数据 / 单指标时的降级文案。渲染形态（`usageText`）已有测试，本文件只测状态行文案。
 *
 * 断言收尾一律用返回 `Unit` 的函数（`assertEquals`）—— 避免 JUnit 4 误判非 `void`。
 */
class ChatSpeedIndicatorTest {

    /** 两指标都有：`生成中 · ≈ X.X tok/s · 首字 X.Xs`（`≈` 前缀 = 粗估口径）。 */
    @Test
    fun 双指标文案含约等号前缀与秒级首字() {
        assertEquals(
            "生成中 · ≈ 12.3 tok/s · 首字 0.4s",
            speedLabel(StreamingUsage(ttftMillis = 400L, tokensPerSecond = 12.3f)),
        )
    }

    /** 仅 tps（首字尚未到达）：省略首字段。 */
    @Test
    fun 仅速度时省略首字段() {
        assertEquals(
            "生成中 · ≈ 12.3 tok/s",
            speedLabel(StreamingUsage(ttftMillis = 0L, tokensPerSecond = 12.3f)),
        )
    }

    /** 仅首字（decode 尚未产出）：省略速度段。首字 1234ms ⇒ 1.2s。 */
    @Test
    fun 仅首字时省略速度段() {
        assertEquals(
            "生成中 · 首字 1.2s",
            speedLabel(StreamingUsage(ttftMillis = 1234L, tokensPerSecond = 0f)),
        )
    }

    /** 两指标都无：只返回「生成中」（调用方已在不渲染本行，此处穷尽防御）。 */
    @Test
    fun 零数据只返回生成中() {
        assertEquals("生成中", speedLabel(StreamingUsage()))
    }
}
