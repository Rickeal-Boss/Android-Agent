package com.rickeal.agent.core.agent

import com.rickeal.agent.core.engine.EngineCapabilities
import com.rickeal.agent.core.model.ChatMessage
import com.rickeal.agent.core.model.DisclosureTools
import com.rickeal.agent.core.model.InferenceConfig
import com.rickeal.agent.core.model.ToolDisclosureMode
import com.rickeal.agent.core.model.ToolEffect
import com.rickeal.agent.core.model.ToolResult
import com.rickeal.agent.core.model.ToolSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [buildToolSetup] 的 JVM 纯逻辑单测（W63 刀F：docs/13 接缝① 工具装配簇外提）。
 *
 * 外提前该簇内联在 `executeBodyUnchecked`（无法单测）；外提为顶层 internal 纯函数后，
 * 这里把装配语义逐条钉住 —— 三重门（① capabilities ② enableTools ③ 实验开关）、
 * 白名单过滤、披露模式收窄与「空目录退回全量」、以及装配产物之间的一致性
 * （registeredToolNames 与 availableTools 同源、allToolNames 与 allToolSpecs 同源）。
 *
 * 纯 JVM：[ToolRegistry] / [AgentRequest] / [InferenceConfig] / [EngineCapabilities]
 * 均可直接构造，零协程 / 零 Android 依赖（[Tool] 用假实现，不触 [invoke]）。
 */
class ToolSetupTest {

    private fun fakeTool(name: String): Tool = object : Tool {
        override val spec: ToolSpec = ToolSpec(
            name = name,
            description = "test $name",
            effect = ToolEffect.READ,
        )

        override suspend fun invoke(argumentsJson: String): ToolResult = ToolResult()
    }

    private fun registryOf(vararg names: String): ToolRegistry = ToolRegistry().apply {
        names.forEach { register(fakeTool(it)) }
    }

    private fun requestOf(
        toolNames: Set<String>? = null,
        disclosureMode: ToolDisclosureMode = ToolDisclosureMode.FULL,
    ): AgentRequest = AgentRequest(
        userInput = ChatMessage(text = "hi"),
        toolNames = toolNames,
        disclosureMode = disclosureMode,
    )

    @Test
    fun `关工具时装配全空且原生门被关断`() {
        val setup = buildToolSetup(
            config = InferenceConfig(enableTools = false, nativeToolChannel = true),
            request = requestOf(),
            toolRegistry = registryOf("alpha"),
            capabilities = EngineCapabilities(nativeToolChannel = true),
        )
        assertFalse(setup.useNativeTools, "enableTools=false 必须关断原生门（三重门②）")
        assertTrue(setup.allToolSpecs.isEmpty())
        assertFalse(setup.disclosureActive, "关掉工具时不该凭空冒出两个元工具")
        assertTrue(setup.hiddenToolCatalog.isEmpty)
        assertTrue(setup.availableTools.isEmpty())
        assertTrue(setup.registeredToolNames.isEmpty())
        assertTrue(setup.allToolNames.isEmpty())
    }

    @Test
    fun `全量模式装配全量工具清单且原生门默认关`() {
        val setup = buildToolSetup(
            config = InferenceConfig(),
            request = requestOf(),
            toolRegistry = registryOf("alpha", "beta"),
            capabilities = EngineCapabilities(nativeToolChannel = true),
        )
        // capabilities 说有原生通道，但实验开关（三重门③）默认 false ⇒ 仍走文本协议。
        assertFalse(setup.useNativeTools, "nativeToolChannel 开关默认关 ⇒ 不走原生通道")
        assertEquals(setOf("alpha", "beta"), setup.allToolSpecs.map { it.name }.toSet())
        assertFalse(setup.disclosureActive)
        assertTrue(setup.hiddenToolCatalog.isEmpty)
        assertEquals(setup.allToolSpecs, setup.availableTools)
        assertEquals(setOf("alpha", "beta"), setup.registeredToolNames)
        assertEquals(setup.registeredToolNames, setup.allToolNames, "FULL 模式下两口径同集合")
    }

    @Test
    fun `原生通道三重门全开才走 native`() {
        val setup = buildToolSetup(
            config = InferenceConfig(nativeToolChannel = true),
            request = requestOf(),
            toolRegistry = registryOf("alpha"),
            capabilities = EngineCapabilities(nativeToolChannel = true),
        )
        assertTrue(setup.useNativeTools)
    }

    @Test
    fun `capabilities 为空时原生门关断`() {
        // executeBodyUnchecked 里 engine.capabilities() 抛异常被吞成 null ⇒ 必须兜底文本协议。
        val setup = buildToolSetup(
            config = InferenceConfig(nativeToolChannel = true),
            request = requestOf(),
            toolRegistry = registryOf("alpha"),
            capabilities = null,
        )
        assertFalse(setup.useNativeTools, "capabilities=null（探测失败）必须兜底关断原生门")
        assertEquals(setOf("alpha"), setup.registeredToolNames)
    }

    @Test
    fun `按需披露收窄为元工具且真实工具进隐藏目录`() {
        val setup = buildToolSetup(
            config = InferenceConfig(),
            request = requestOf(disclosureMode = ToolDisclosureMode.ON_DEMAND),
            toolRegistry = registryOf("calculator"),
            capabilities = null,
        )
        assertTrue(setup.disclosureActive)
        assertEquals(
            DisclosureTools.publicSpecs().map { it.name }.toSet(),
            setup.availableTools.map { it.name }.toSet(),
            "ON_DEMAND 下面向模型的清单只有两个元工具",
        )
        assertEquals(setOf(DisclosureTools.SEARCH_TOOL_NAME, DisclosureTools.CALL_TOOL_NAME), setup.registeredToolNames)
        assertEquals(setOf("calculator"), setup.allToolNames, "allToolNames 是披露之前的用户启用口径")
        assertEquals(1, setup.hiddenToolCatalog.size)
    }

    @Test
    fun `白名单过滤生效且过滤为空时披露退回全量`() {
        // 白名单命中：allToolSpecs 只剩 alpha。
        val filtered = buildToolSetup(
            config = InferenceConfig(),
            request = requestOf(toolNames = setOf("alpha")),
            toolRegistry = registryOf("alpha", "beta"),
            capabilities = null,
        )
        assertEquals(setOf("alpha"), filtered.allToolNames)

        // 白名单过滤为空：隐藏目录为空 ⇒ 披露退回全量（availableTools 不再是元工具）。
        val emptyCatalog = buildToolSetup(
            config = InferenceConfig(),
            request = requestOf(toolNames = setOf("missing"), disclosureMode = ToolDisclosureMode.ON_DEMAND),
            toolRegistry = registryOf("alpha"),
            capabilities = null,
        )
        assertFalse(emptyCatalog.disclosureActive, "目录为空时披露只会教模型检索空集 ⇒ 退回全量")
        assertTrue(emptyCatalog.availableTools.isEmpty())
        assertTrue(emptyCatalog.registeredToolNames.isEmpty())
        assertTrue(emptyCatalog.allToolNames.isEmpty())
    }
}
