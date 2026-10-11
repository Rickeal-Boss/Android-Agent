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
        // R-A 本体：只有「一手证据证明元数据不含该通道」的模型才显式下发；其余一律 null
        //（信任容器元数据）。见 ChannelSyntax / inferChannelSyntax KDoc。
        assertEquals(
            ChannelSyntax.GEMMA,
            ModelHeuristics.infer("gemma-4-E2B-it.litertlm").thoughtChannelSyntax,
        )
        assertEquals(
            ChannelSyntax.GEMMA,
            ModelHeuristics.infer("gemma-4-E4B-it-gpu.litertlm").thoughtChannelSyntax,
        )
        // W63 刀D：R1 系（推理标识命中）→ THINK —— 一手证据 = 容器字节扫描（<|channel /
        // channel / chat_template / bos_token 全 0；<think> 恰 1 处 @ 元数据特殊 token 列表，
        // 形态 <｜Assistant｜><think>）⇒ 元数据不含原生通道声明、思考以文本定界 ⇒ 显式下发。
        assertEquals(
            ChannelSyntax.THINK,
            ModelHeuristics.infer("DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm")
                .thoughtChannelSyntax,
        )
        assertEquals(ChannelSyntax.THINK, ModelHeuristics.infer("some-reasoner-7b.litertlm").thoughtChannelSyntax)
        // W64：通道判定与家族解耦，llama 家族样例同样命中 THINK（见下用例）。
        assertEquals(ChannelSyntax.THINK, ModelHeuristics.infer("deepseek-distill-qwen-7b.litertlm").thoughtChannelSyntax)
        // MiniCPM5：元数据自带 <think>/</think> ⇒ null（不下发，避免覆盖正确元数据 = N1 本体）。
        assertNull(ModelHeuristics.infer("MiniCPM5-2B_int4.litertlm").thoughtChannelSyntax)
        // MiniCPM-V 视觉系、未知模型、老 Gemma 3n/3、Qwen、Phi：全部 null。
        assertNull(ModelHeuristics.infer("MiniCPM-V-4-int8.litertlm").thoughtChannelSyntax)
        assertNull(ModelHeuristics.infer("some-unknown-model.litertlm").thoughtChannelSyntax)
        assertNull(ModelHeuristics.infer("gemma-3n-E2B-it.litertlm").thoughtChannelSyntax)
        assertNull(ModelHeuristics.infer("gemma-3-4b-it.litertlm").thoughtChannelSyntax)
        // 标记纪律：Qwen2.5（落 OTHER 但非推理标识）不得命中 THINK（W61 纪律，刀D 沿用）。
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

    // --- W63 刀E / W64 P3：gemma-4 能力位 thinking=true（E2B 真机实证，改值后补钉） ---

    @Test
    fun `gemma4 能力位 thinking 为 true 且家族为 GEMMA_4`() {
        // W63 刀E 把 gemma-4 的 thinking 由 false 改为 true（ModelHeuristics.kt:141），
        // 此前无 @Test 钉住该值（审查席 C2 判为 vacuous）—— 本用例钉死。
        // 依据：E2B 真机实证（W62 V4：thinklen=2061，思考流切分正常）；E4B 未单独真机取证，
        // 按家族事实外推（见 ModelHeuristics.kt:126-131 KDoc）。通道侧为 GEMMA（**非** THINK），
        // 已由 `思考通道语法归类` 用例覆盖，此处不重复。
        val e2b = ModelHeuristics.infer("gemma-4-E2B-it.litertlm")
        assertEquals(ModelFamily.GEMMA_4, e2b.family)
        assertEquals(true, e2b.capabilities.thinking)

        // E4B（GPU 变体文件名仍含 gemma-4）走同一 GEMMA_4 分支 ⇒ thinking 亦 true。
        val e4b = ModelHeuristics.infer("gemma-4-E4B-it-gpu.litertlm")
        assertEquals(ModelFamily.GEMMA_4, e4b.family)
        assertEquals(true, e4b.capabilities.thinking)
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
    fun `R1 蒸馏 Llama 家族亦命中 THINK 且 thinking 为 true`() {
        // W64（C1）组合例：R1-Distill-Llama-8B 文件名含 `llama` ⇒ inferFamily 落 LLAMA 家族。
        // 修复前 inferChannelSyntax 的 R1 分支带 `family == OTHER` 门控 ⇒ LLAMA 被挡掉 ⇒
        // 通道返回 null、思考不切分、推理腔泄漏正文（W61 J1 原症状对该容器原样存在）。
        // 修复后判据唯一 = isReasonerMarker，家族与通道判定解耦：LLAMA 家族同样拿到 THINK。
        // 同时钉住能力位侧无碍（LLAMA 无专属分支 ⇒ 落 else ⇒ thinking = isReasonerMarker）。
        val r = ModelHeuristics.infer("deepseek-r1-distill-llama-8b.litertlm")
        assertEquals(ModelFamily.LLAMA, r.family)
        assertEquals(true, r.capabilities.thinking)
        assertEquals(ChannelSyntax.THINK, r.thoughtChannelSyntax)
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
