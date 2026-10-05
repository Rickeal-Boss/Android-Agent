package com.rickeal.agent.core.engine.local

import com.rickeal.agent.core.model.ChannelSyntax
import com.rickeal.agent.core.model.ModelDescriptor
import com.rickeal.agent.core.model.ModelFamily
import com.rickeal.agent.core.model.ModelHeuristics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [thoughtChannelDefsFor] 的 JVM 纯逻辑单测（Wave 48 N1 → **Wave 49 R-A 数据驱动化**）。
 *
 * 该函数是纯函数（无 native、无状态），故可离线钉住「模型身份 → 思考通道 def」的映射。
 *
 * ## R-A 后语义变了，本测试的锚点随之变了
 *
 * 改前：**恒发非空字面量**（MiniCPM5 → `<think>`，其余 → Gemma），未知模型回退 Gemma。
 * 改后：**默认 `null`（不下发 channels、信任容器元数据）**，只有 [ModelDescriptor.thoughtChannelSyntax]
 * 非 null（当前仅 Gemma-4 系）才发**单元素** def。
 * ⇒ 最关键的锚从「front() 的 start 与家族一致」变成两条：
 *  1. **`null` 是默认**（未知模型 / MiniCPM5 / MiniCPM-V / 老 Gemma ⇒ 一律 `null`）—— 这是 R-A
 *     本体：元数据自带思考通道的模型不再被错误字面量覆盖；
 *  2. **非空时恒单元素且 `front()` 即该模型自己的思考通道** —— native `ThinkingBudgetConstraint`
 *     只认 `channels.front()`（`conversation.cc:371-392`）。
 *
 * 多处用例走 [ModelHeuristics.applyTo]（fileName → 家族归类 → syntax）⇒ 覆盖的是**真实管线**
 * 而非手填字段的镜像。
 *
 * ⚠️ JUnit4 纪律：所有 `@Test` 方法以返回 **void** 的断言收尾。
 */
class ThoughtChannelDefsTest {

    /** 走真实管线：fileName → 启发式归类 → 思考通道 def。 */
    private fun defsFor(fileName: String) = thoughtChannelDefsFor(
        ModelHeuristics.applyTo(ModelDescriptor(fileName = fileName, path = "/x/$fileName")),
    )

    // ───────────────────────── null 是默认（R-A 本体）─────────────────────────

    @Test
    fun `MiniCPM5 走 null 信任元数据`() {
        // 元数据自带 thought 通道（<think>/</think>，RM_MiniCPM5-2B.md:106/163）
        // ⇒ 不下发 channels，避免「错误字面量覆盖正确元数据」= N1 本体。
        assertNull(defsFor("MiniCPM5-2B_int4.litertlm"))
    }

    @Test
    fun `MiniCPM5 文件名大小写与路径末段回退均为 null`() {
        // 归类已移到 ModelHeuristics（大小写/路径回退在那里）；此处只断言管线末端仍是 null。
        assertNull(defsFor("MINICPM5-2B.litertlm"))
        assertNull(defsFor("MiniCPM5-2B_int8.litertlm"))
    }

    @Test
    fun `MiniCPM-V 视觉系为 null`() {
        assertNull(defsFor("MiniCPM-V-4-int8.litertlm"))
    }

    @Test
    fun `非思考模型与未知模型为 null`() {
        assertNull(defsFor("Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm"))
        assertNull(defsFor("Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm"))
        assertNull(defsFor("some-unknown-model.litertlm"))
    }

    @Test
    fun `老 Gemma 3n 与 3 不下发字面量`() {
        // 防「顺手」给老 Gemma 也发 Gemma 字面量：只有 Gemma-4 有一手泄漏证据。
        assertNull(defsFor("gemma-3n-E2B-it.litertlm"))
        assertNull(defsFor("gemma-3-4b-it.litertlm"))
    }

    @Test
    fun `null 模型为 null`() {
        assertNull(thoughtChannelDefsFor(null))
    }

    // ───────────────────── 非空：单元素 + front() 与身份一致 ─────────────────────

    @Test
    fun `Gemma-4 走 GEMMA 字面量且 front 与身份一致`() {
        val defs = defsFor("gemma-4-E2B-it.litertlm")
        assertEquals(1, defs?.size)
        // 列表首元素（native 的 channels.front()）必须就是该模型自己的思考通道 —— 预算正确性的落点。
        assertEquals("thought", defs?.first()?.channelName)
        assertEquals("<|channel>thought", defs?.first()?.start)
        assertEquals("<channel|>", defs?.first()?.end)
    }

    @Test
    fun `Gemma-4 E4B 与 GPU 变体同走 GEMMA`() {
        for (name in listOf("gemma-4-E4B-it-gpu.litertlm", "gemma-4-E2B-it-gpu.litertlm")) {
            val defs = defsFor(name)
            assertEquals(1, defs?.size, "name=$name")
            assertEquals("<|channel>thought", defs?.first()?.start, "name=$name")
        }
    }

    // ─────────────────── 数据驱动：函数只认字段，不认 family ───────────────────

    @Test
    fun `函数只认 thoughtChannelSyntax 字段不认 family`() {
        // family = GEMMA_4 但字段为 null（默认）⇒ 仍是 null ⇒ 证明判定口径是**数据**而非身份分支。
        assertNull(
            thoughtChannelDefsFor(
                ModelDescriptor(family = ModelFamily.GEMMA_4, fileName = "gemma-4-E2B-it.litertlm"),
            ),
        )
        // 反之：显式给 THINK（回退档）⇒ 立刻生效（一行切回 MiniCPM5 的预案）。
        val think = thoughtChannelDefsFor(
            ModelDescriptor(family = ModelFamily.MINICPM, thoughtChannelSyntax = ChannelSyntax.THINK),
        )
        assertEquals(1, think?.size)
        assertEquals("<think>", think?.first()?.start)
        assertEquals("</think>", think?.first()?.end)
    }
}
