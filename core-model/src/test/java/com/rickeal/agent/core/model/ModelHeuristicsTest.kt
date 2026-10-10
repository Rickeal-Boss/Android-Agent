package com.rickeal.agent.core.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [ModelHeuristics.applyTo] / [ModelHeuristics.resolveCapabilities] 的 JVM 纯逻辑单测（Wave 48）。
 *
 * 钉住的硬不变量：能力位**来源优先** —— 用户显式编辑过（`USER`）的位不被启发式改写；
 * 无来源 / 启发式来源的条目按启发式**重算**（替换而非并集）；旧数据 `null` 迁移为 `HEURISTIC`。
 *
 * ⚠️ 本测试**不得触达 `AgentLogStore`**（纯 JVM 无 mock，`android.util.Log` not-mocked）——
 * 故 `ModelHeuristics` 保持零 `Log`，迁移日志落在 `ModelRepository.refresh()`。
 *
 * ⚠️ JUnit4 纪律：所有 `@Test` 方法以返回 **void** 的断言收尾。
 */
class ModelHeuristicsTest {

    private fun descriptor(
        fileName: String,
        capabilities: ModelCapabilities = ModelCapabilities(),
        source: CapabilitySource? = null,
    ) = ModelDescriptor(
        fileName = fileName,
        path = "/x/$fileName",
        capabilities = capabilities,
        capabilitiesSource = source,
    )

    @Test
    fun `用户显式关闭的能力位不被启发式抬回`() {
        // gemma-4 启发式 audio=true；用户显式关掉 ⇒ source=USER ⇒ 必须保持关（不并集）。
        val persisted = ModelCapabilities(
            image = false,
            audio = false,
            thinking = false,
            toolCalling = false,
        )
        val out = ModelHeuristics.applyTo(
            descriptor("gemma-4-E2B-it-gpu.litertlm", persisted, CapabilitySource.USER),
        )
        assertEquals(persisted, out.capabilities)
        assertEquals(CapabilitySource.USER, out.capabilitiesSource)
    }

    @Test
    fun `无来源或启发式来源的条目被启发式重算`() {
        val wrong = ModelCapabilities(image = true, audio = true, thinking = true)
        val heuristic = ModelHeuristics.infer("Qwen2.5-1.5B-Instruct_q8_ekv4096.litertlm").capabilities
        // 先确认启发式本身：OTHER 家族、无 vl/audio 标记 ⇒ image/audio 关。
        assertEquals(false, heuristic.image)
        assertEquals(false, heuristic.audio)

        val fromNull = ModelHeuristics.applyTo(
            descriptor("Qwen2.5-1.5B-Instruct_q8_ekv4096.litertlm", wrong, null),
        )
        assertEquals(false, fromNull.capabilities.image)
        assertEquals(false, fromNull.capabilities.audio)
        assertEquals(CapabilitySource.HEURISTIC, fromNull.capabilitiesSource)

        val fromHeuristic = ModelHeuristics.applyTo(
            descriptor("Qwen2.5-1.5B-Instruct_q8_ekv4096.litertlm", wrong, CapabilitySource.HEURISTIC),
        )
        assertEquals(fromNull.capabilities, fromHeuristic.capabilities)
    }

    @Test
    fun `applyTo 幂等`() {
        // 覆盖两态：null（Qwen/OTHER）与 非 null（Gemma-4）。thoughtChannelSyntax 每次无条件重算，
        // 重算结果与输入无关 ⇒ 两次 applyTo 必须相等（幂等）。
        val once = ModelHeuristics.applyTo(descriptor("Qwen2.5-1.5B-Instruct_q8_ekv4096.litertlm"))
        assertEquals(once, ModelHeuristics.applyTo(once))

        val gemmaOnce = ModelHeuristics.applyTo(descriptor("gemma-4-E2B-it.litertlm"))
        assertEquals(ChannelSyntax.GEMMA, gemmaOnce.thoughtChannelSyntax)
        assertEquals(gemmaOnce, ModelHeuristics.applyTo(gemmaOnce))
    }

    @Test
    fun `思考通道语法归类 仅Gemma4给字面量`() {
        // R-A 本体：只有 Gemma-4 有一手泄漏证据（元数据不含该通道）⇒ 显式下发；其余一律 null
        //（信任容器元数据）。见 ChannelSyntax / inferChannelSyntax KDoc。
        assertEquals(
            ChannelSyntax.GEMMA,
            ModelHeuristics.infer("gemma-4-E2B-it.litertlm").thoughtChannelSyntax,
        )
        assertEquals(
            ChannelSyntax.GEMMA,
            ModelHeuristics.infer("gemma-4-E4B-it-gpu.litertlm").thoughtChannelSyntax,
        )
        // MiniCPM5：元数据自带 <think>/</think> ⇒ null（不下发，避免覆盖正确元数据 = N1 本体）。
        assertNull(ModelHeuristics.infer("MiniCPM5-2B_int4.litertlm").thoughtChannelSyntax)
        // MiniCPM-V 视觉系、未知模型、老 Gemma 3n/3、Qwen、Phi：全部 null。
        assertNull(ModelHeuristics.infer("MiniCPM-V-4-int8.litertlm").thoughtChannelSyntax)
        assertNull(ModelHeuristics.infer("some-unknown-model.litertlm").thoughtChannelSyntax)
        assertNull(ModelHeuristics.infer("gemma-3n-E2B-it.litertlm").thoughtChannelSyntax)
        assertNull(ModelHeuristics.infer("gemma-3-4b-it.litertlm").thoughtChannelSyntax)
        assertNull(ModelHeuristics.infer("Qwen2.5-1.5B-Instruct_q8_ekv4096.litertlm").thoughtChannelSyntax)
        assertNull(ModelHeuristics.infer("Phi-4-mini-instruct_q8.litertlm").thoughtChannelSyntax)
    }

    @Test
    fun `思考通道语法不采信持久化值每次重算`() {
        // 误写过的存量值（给 MiniCPM5 错填 GEMMA）在 refresh 时必须被重算回 null，
        // 否则一次误写会永久覆盖正确元数据（applyTo KDoc 明示）。
        val wrong = ModelDescriptor(
            fileName = "MiniCPM5-2B_int4.litertlm",
            path = "/x/MiniCPM5-2B_int4.litertlm",
            thoughtChannelSyntax = ChannelSyntax.GEMMA,
        )
        assertNull(ModelHeuristics.applyTo(wrong).thoughtChannelSyntax)
    }

    @Test
    fun `旧 JSON 无来源字段反序列化为 null 并迁移为 HEURISTIC`() {
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString(
            ModelDescriptor.serializer(),
            """{"id":"x","fileName":"Qwen2.5-1.5B-Instruct_q8.litertlm","capabilities":{"image":true,"audio":true}}""",
        )
        assertNull(old.capabilitiesSource)
        val migrated = ModelHeuristics.applyTo(old)
        assertEquals(CapabilitySource.HEURISTIC, migrated.capabilitiesSource)
        assertEquals(false, migrated.capabilities.image)
    }

    @Test
    fun `resolveCapabilities 三态`() {
        val persisted = ModelCapabilities(image = true, audio = false)
        val heuristic = ModelCapabilities(image = false, audio = true)
        assertEquals(
            persisted,
            ModelHeuristics.resolveCapabilities(persisted, CapabilitySource.USER, heuristic),
        )
        assertEquals(
            heuristic,
            ModelHeuristics.resolveCapabilities(persisted, CapabilitySource.HEURISTIC, heuristic),
        )
        assertEquals(
            heuristic,
            ModelHeuristics.resolveCapabilities(persisted, null, heuristic),
        )
    }

    // --- W61：OTHER 分支按推理标识识别 thinking（DeepSeek-R1 蒸馏系修复） ---

    @Test
    fun `DeepSeek-R1 蒸馏系落 OTHER 且识别为 thinking`() {
        // DeepSeek-R1-Distill-Qwen-1.5B：文件名含 qwen 但不含 qwen3 ⇒ 落 OTHER；
        // OTHER 分支按推理标识（r1 / reasoner / deepseek+distill）识别 thinking = true。
        val r = ModelHeuristics.infer(
            "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
        )
        assertEquals(ModelFamily.OTHER, r.family)
        assertEquals(true, r.capabilities.thinking)
    }

    @Test
    fun `Qwen2_5 落 OTHER 但不误判 thinking`() {
        // 防误伤：Qwen2.5-1.5B 亦落 OTHER，但不含 r1 / reasoner / （deepseek+distill）
        // ⇒ thinking 必须为 false（不得用裸 `think` 或裸 `distill` 误伤）。
        val r = ModelHeuristics.infer("Qwen2.5-1.5B-Instruct_q8_ekv4096.litertlm")
        assertEquals(ModelFamily.OTHER, r.family)
        assertEquals(false, r.capabilities.thinking)
    }

    @Test
    fun `随机非推理名落 OTHER 且 thinking=false`() {
        val r = ModelHeuristics.infer("some-random-other.litertlm")
        assertEquals(ModelFamily.OTHER, r.family)
        assertEquals(false, r.capabilities.thinking)
    }
}
