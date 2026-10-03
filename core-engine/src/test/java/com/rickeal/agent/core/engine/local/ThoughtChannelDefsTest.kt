package com.rickeal.agent.core.engine.local

import com.rickeal.agent.core.model.ModelDescriptor
import com.rickeal.agent.core.model.ModelFamily
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [thoughtChannelDefsFor] 的 JVM 纯逻辑单测（Wave 48 N1）。
 *
 * 该函数是纯函数（无 native、无状态），故可离线钉住「模型身份 → 思考通道标记」的映射。
 * 最关键的一条：**`front()` 的 start 必须与该模型自己的思考通道一致** —— native
 * `ThinkingBudgetConstraint` 只认 `channels.front()`（`conversation.cc:371-392`），
 * 这是「按模型选 def（方案 B）」相对「append 第二 def（方案 A）」的唯一优势，必须锁死。
 *
 * ⚠️ JUnit4 纪律：所有 `@Test` 方法以返回 **void** 的断言收尾。
 */
class ThoughtChannelDefsTest {

    @Test
    fun `MiniCPM5 取 think 标记且 front 与家族一致`() {
        val defs = thoughtChannelDefsFor(
            ModelDescriptor(family = ModelFamily.MINICPM, fileName = "MiniCPM5-2B_int4.litertlm"),
        )
        assertEquals(1, defs.size)
        // 列表首元素（native 的 channels.front()）必须就是 MiniCPM5 自己的思考通道 —— 预算正确性的落点。
        assertEquals("thought", defs.first().channelName)
        assertEquals("<think>", defs.first().start)
        assertEquals("</think>", defs.first().end)
    }

    @Test
    fun `MiniCPM5 文件名大小写与路径末段回退`() {
        // 大小写不敏感（lowercase(Locale.ROOT)）。
        val upper = thoughtChannelDefsFor(
            ModelDescriptor(family = ModelFamily.MINICPM, fileName = "MINICPM5-2B.litertlm"),
        )
        assertEquals("<think>", upper.first().start)
        // fileName 为空 ⇒ 回退 path 末段（与 ModelHeuristics.applyTo 同源口径）。
        val fromPath = thoughtChannelDefsFor(
            ModelDescriptor(
                family = ModelFamily.MINICPM,
                fileName = "",
                path = "/data/models/MiniCPM5-2B_int4.litertlm",
            ),
        )
        assertEquals("<think>", fromPath.first().start)
    }

    @Test
    fun `MiniCPM-V 视觉系不命中 think 走 Gemma 标记`() {
        // family == MINICPM 但非 minicpm5（V 系无 thinking）⇒ 回退 Gemma 标记。
        val defs = thoughtChannelDefsFor(
            ModelDescriptor(family = ModelFamily.MINICPM, fileName = "MiniCPM-V-4-int8.litertlm"),
        )
        assertEquals("<|channel>thought", defs.first().start)
        assertEquals("<channel|>", defs.first().end)
    }

    @Test
    fun `其它家族走 Gemma 标记`() {
        val defs = thoughtChannelDefsFor(
            ModelDescriptor(family = ModelFamily.GEMMA_4, fileName = "gemma-4-E2B-it.litertlm"),
        )
        assertEquals("thought", defs.first().channelName)
        assertEquals("<|channel>thought", defs.first().start)
        assertEquals("<channel|>", defs.first().end)
    }

    @Test
    fun `null 模型回退 Gemma 标记`() {
        val defs = thoughtChannelDefsFor(null)
        assertEquals("thought", defs.first().channelName)
        assertEquals("<|channel>thought", defs.first().start)
        assertEquals("<channel|>", defs.first().end)
    }
}
