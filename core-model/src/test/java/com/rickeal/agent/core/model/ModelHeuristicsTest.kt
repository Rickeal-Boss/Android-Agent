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
        val once = ModelHeuristics.applyTo(descriptor("Qwen2.5-1.5B-Instruct_q8_ekv4096.litertlm"))
        val twice = ModelHeuristics.applyTo(once)
        assertEquals(once, twice)
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
}
