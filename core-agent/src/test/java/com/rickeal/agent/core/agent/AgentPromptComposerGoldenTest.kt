package com.rickeal.agent.core.agent

import com.rickeal.agent.core.model.DisclosureTools
import com.rickeal.agent.core.model.InferenceConfig
import com.rickeal.agent.core.model.ToolEffect
import com.rickeal.agent.core.model.ToolParameter
import com.rickeal.agent.core.model.ToolSpec
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [AgentPromptComposer] 的 **Golden 哈希锚**（W82 拆分第一刀：docs/13 触发条件②）。
 *
 * ## 这些哈希是 W82 拆分前实测值
 *
 * `EXPECTED_SHA256` 里的每个值都是**拆分前**实现（`AgentRunner.buildSystemSections`
 * 原文，整段搬移前后逐行 diff 为 0 行差异）在 JVM 上实测出的
 * `buildSystemSections(...).joinToString("\n\n")` 的 SHA-256 十六进制。
 * **任何提示词文本 / 段顺序变更都会判红。改提示词必须同时更新哈希，并在交接里申报
 * 提示词面变化**（本仓提示词改动历来要申报：`echoCorpus` 指纹集与 `prompt_echo` 截断
 * 检测直接耦合这些文本，见 `MEMORY_MAINTENANCE` / `TOOL_GUARDRAILS_NATIVE` 的 KDoc）。
 *
 * 它钉的是**逐字节等价**，不是「大致一样」：段内多一个空格、段顺序互换、
 * `joinToString` 分隔符从 `"\n\n"` 变成 `"\n"`，都会让哈希变。
 *
 * ## 覆盖的分支矩阵（12 组，取能覆盖全部分支的最小组合集）
 *
 * | 维度 | 取值 |
 * |---|---|
 * | `systemInstruction` | 空串 / 纯空白 / 自定义中文 |
 * | `tools` | 空 / 普通工具集 / 披露元工具（[DisclosureTools.publicSpecs]）/ 含 `memory_write` |
 * | `nativeTools` | false（文本协议）/ true（原生通道） |
 * | `memoryText` | null / 纯空白 / 非空 |
 *
 * 分支落点（对应 `buildSystemSections` 内每个 `if`）：
 * `systemInstruction.isNotBlank()`、`tools.isNotEmpty()`、`nativeTools` 真假两路、
 * `tools.any { search_tools }`（`DISCLOSURE_GUIDE`）、`!memoryText.isNullOrBlank()`、
 * `tools.any { memory_write }`（`MEMORY_MAINTENANCE`）、恒追加的 `STOP_CONDITIONS`。
 *
 * 纯 JVM：只构造 [InferenceConfig] / [ToolSpec]，零 Android / 引擎 / 协程依赖。
 */
class AgentPromptComposerGoldenTest {

    /** 一个测试用例：入参四元组 + 便于报错定位的名字。 */
    private data class Case(
        val name: String,
        val config: InferenceConfig,
        val tools: List<ToolSpec>,
        val nativeTools: Boolean,
        val memoryText: String?,
    )

    private companion object {
        const val CUSTOM_INSTRUCTION = "你是端侧助手，回答要短，先给结论再给依据。"

        /** 纯空白：`isNotBlank()` 为 false，与空串走同一分支，但入参不同（防「trim 掉才算空」的实现漂移）。 */
        const val BLANK_INSTRUCTION = "  \n\t "

        const val MEMORY_TEXT = "用户偏好：回答用中文；项目事实：主色是蓝色。"
        const val BLANK_MEMORY_TEXT = "   \n "

        /** 普通工具集（无 `search_tools` / `memory_write`）⇒ 走「清单 + [TOOL_GUARDRAILS]」文本协议分支。 */
        val NORMAL_TOOLS: List<ToolSpec> = listOf(
            ToolSpec(
                name = "clock",
                description = "读取当前时间",
                parameters = emptyList(),
                effect = ToolEffect.READ,
            ),
            ToolSpec(
                name = "file_read",
                description = "读取文件内容",
                parameters = listOf(ToolParameter(name = "path", description = "文件路径")),
                effect = ToolEffect.READ,
            ),
        )

        /** 普通工具集 + `memory_write` ⇒ 额外命中 [AgentPromptComposer.MEMORY_MAINTENANCE] 门控。 */
        val MEMORY_TOOLS: List<ToolSpec> = NORMAL_TOOLS + ToolSpec(
            name = "memory_write",
            description = "沉淀长期记忆",
            effect = ToolEffect.WRITE,
        )

        /** 披露模式元工具（`search_tools` + `call_tool`）⇒ 额外命中 `DISCLOSURE_GUIDE` 分支。 */
        val DISCLOSURE_TOOLS: List<ToolSpec> = DisclosureTools.publicSpecs()

        /** 披露元工具 + `memory_write`：同时命中披露说明与记忆维护两段。 */
        val DISCLOSURE_MEMORY_TOOLS: List<ToolSpec> = DisclosureTools.publicSpecs() + ToolSpec(
            name = "memory_write",
            description = "沉淀长期记忆",
            effect = ToolEffect.WRITE,
        )

        fun cases(): List<Case> = listOf(
            Case("01_全空_只有停止条件", InferenceConfig(systemInstruction = ""), emptyList(), false, null),
            Case("02_空白指令视同空", InferenceConfig(systemInstruction = BLANK_INSTRUCTION), emptyList(), false, null),
            Case("03_自定义指令", InferenceConfig(systemInstruction = CUSTOM_INSTRUCTION), emptyList(), false, null),
            Case("04_文本协议工具清单", InferenceConfig(systemInstruction = ""), NORMAL_TOOLS, false, null),
            Case("05_原生通道精简护栏", InferenceConfig(systemInstruction = ""), NORMAL_TOOLS, true, null),
            Case("06_披露模式说明段", InferenceConfig(systemInstruction = ""), DISCLOSURE_TOOLS, false, null),
            Case("07_披露模式加原生通道", InferenceConfig(systemInstruction = ""), DISCLOSURE_TOOLS, true, null),
            Case("08_记忆内容段", InferenceConfig(systemInstruction = ""), emptyList(), false, MEMORY_TEXT),
            Case("09_空白记忆视同无", InferenceConfig(systemInstruction = ""), emptyList(), false, BLANK_MEMORY_TEXT),
            Case("10_记忆维护门控", InferenceConfig(systemInstruction = CUSTOM_INSTRUCTION), MEMORY_TOOLS, false, MEMORY_TEXT),
            Case("11_记忆维护加原生通道", InferenceConfig(systemInstruction = ""), MEMORY_TOOLS, true, null),
            Case("12_披露加记忆维护全段", InferenceConfig(systemInstruction = CUSTOM_INSTRUCTION), DISCLOSURE_MEMORY_TOOLS, false, MEMORY_TEXT),
        )

        /**
         * 拆分前实测的 SHA-256（`buildSystemSections(...).joinToString("\n\n")`，UTF-8）。
         * 见类 KDoc：改提示词必须同步更新本表并申报提示词面变化。
         */
        val EXPECTED_SHA256: Map<String, String> = mapOf(
            "01_全空_只有停止条件" to "c2299cd9051943e7d0be1d614ae9f9a4c9eb3c19575b353316907a688f3c503f",
            "02_空白指令视同空" to "c2299cd9051943e7d0be1d614ae9f9a4c9eb3c19575b353316907a688f3c503f",
            "03_自定义指令" to "941471eb8f38ca39b0697e79b885a19cc4171f430e4576722b54a5b0ec97b5ea",
            "04_文本协议工具清单" to "d3574c7f1082afb6c8b01e01e5890e0ca8e0d20c89777fe0a084c91e5e30181e",
            "05_原生通道精简护栏" to "3a75e389ac1a50fd9cd8dc40322e2f393bcdd47e262b76c299d1f447fd57a66e",
            "06_披露模式说明段" to "4930de22af2949e87aa5ae43954fe9338cd9c828a50c026ce4b15e6fcfa797bf",
            "07_披露模式加原生通道" to "3a75e389ac1a50fd9cd8dc40322e2f393bcdd47e262b76c299d1f447fd57a66e",
            "08_记忆内容段" to "8ff7b76212fb0f0a7580912536f06f2ce59f5728f228a277b8383eaeb023195d",
            "09_空白记忆视同无" to "c2299cd9051943e7d0be1d614ae9f9a4c9eb3c19575b353316907a688f3c503f",
            "10_记忆维护门控" to "d0614d0dff0a602d9822baf6499ee2cc0e2b311674c3009c6956e6d37b064b5e",
            "11_记忆维护加原生通道" to "dc5c133d29b6d146a10d12a57947932108a73af6e65b168bbd7cb97c02c6ad47",
            "12_披露加记忆维护全段" to "4f9440af3287f5cd66742590788591d996d1c25cf5e06d8e8acb862517c74410",
        )

        fun sha256(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { byte ->
                (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
            }
        }

        fun instructionOf(case: Case): String = AgentPromptComposer.buildSystemInstruction(
            case.config,
            case.tools,
            case.nativeTools,
            case.memoryText,
        )

        fun sectionsOf(case: Case): List<String> = AgentPromptComposer.buildSystemSections(
            case.config,
            case.tools,
            case.nativeTools,
            case.memoryText,
        )
    }

    @Test
    fun 提示词哈希与拆分前实测值一致() {
        assertEquals(
            EXPECTED_SHA256.keys,
            cases().map { it.name }.toSet(),
            "用例集合与 EXPECTED_SHA256 键集合不一致（新增/改名用例必须同步补哈希）",
        )
        // 先整体比一张「名字=哈希」表：一次失败就能看出**哪几组**变了（失败信息里是完整对照表），
        // 而不是只看到第一组。下面再逐组断言，给出精确到用例名的定位。
        assertEquals(
            EXPECTED_SHA256.entries.joinToString("\n") { "${it.key}=${it.value}" },
            cases().joinToString("\n") { "${it.name}=${sha256(instructionOf(it))}" },
            "系统提示词哈希表变化 ⇒ 提示词文本或段顺序被改动。" +
                "若是有意改提示词，必须同步更新 EXPECTED_SHA256 并在交接里申报提示词面变化。",
        )
        cases().forEach { case ->
            assertEquals(
                EXPECTED_SHA256.getValue(case.name),
                sha256(instructionOf(case)),
                "用例 ${case.name} 的系统提示词哈希变化 ⇒ 提示词文本或段顺序被改动。",
            )
        }
    }

    /** 发送路径与语料路径必须同源：[buildSystemInstruction] 恒等于 sections 的 `"\n\n"` 拼接。 */
    @Test
    fun 发送指令恒等于分段拼接() {
        cases().forEach { case ->
            assertEquals(
                sectionsOf(case).joinToString("\n\n"),
                instructionOf(case),
                "用例 ${case.name}：buildSystemInstruction 与 buildSystemSections 拼接不等价",
            )
        }
    }

    /**
     * `echoCorpus`（回显指纹语料）的豁免口径：记忆段必须能被 [AgentPromptComposer.MEMORY_SECTION_PREFIX]
     * 前缀识别，且**只有**记忆段会被识别 —— 否则 prompt_echo 会误截合法的记忆引用。
     */
    @Test
    fun 记忆段可被前缀识别且只有记忆段命中() {
        val case = Case(
            "记忆段前缀口径",
            InferenceConfig(systemInstruction = CUSTOM_INSTRUCTION),
            MEMORY_TOOLS,
            false,
            MEMORY_TEXT,
        )
        val sections = sectionsOf(case)
        val memorySections = sections.filter { it.startsWith(AgentPromptComposer.MEMORY_SECTION_PREFIX) }
        assertEquals(1, memorySections.size, "记忆段应恰好一段且可被 MEMORY_SECTION_PREFIX 识别")
        assertEquals(
            AgentPromptComposer.MEMORY_SECTION_PREFIX + MEMORY_TEXT,
            memorySections.single(),
            "记忆段内容 = 前缀 + 原文，逐字节不变",
        )
    }

    /** 段顺序契约：指令 → 工具段 → 记忆内容 → 记忆维护 → 停止条件（停止条件恒为末段）。 */
    @Test
    fun 段顺序契约与停止条件恒为末段() {
        val case = Case(
            "段顺序",
            InferenceConfig(systemInstruction = CUSTOM_INSTRUCTION),
            DISCLOSURE_MEMORY_TOOLS,
            false,
            MEMORY_TEXT,
        )
        val sections = sectionsOf(case)
        assertEquals(
            7,
            sections.size,
            "指令 + 披露说明 + 工具清单 + 护栏 + 记忆内容 + 记忆维护 + 停止条件 应为 7 段",
        )
        assertEquals(CUSTOM_INSTRUCTION, sections[0], "第 1 段是系统指令")
        assertEquals(DisclosureTools.DISCLOSURE_GUIDE, sections[1], "第 2 段是披露协议说明")
        assertTrue(sections[2].contains("可用工具："), "第 3 段是工具清单")
        assertEquals(AgentPromptComposer.TOOL_GUARDRAILS, sections[3], "第 4 段是文本协议护栏")
        assertEquals(AgentPromptComposer.MEMORY_SECTION_PREFIX + MEMORY_TEXT, sections[4], "第 5 段是记忆内容")
        assertEquals(AgentPromptComposer.MEMORY_MAINTENANCE, sections[5], "第 6 段是记忆维护")
        assertEquals(AgentPromptComposer.STOP_CONDITIONS, sections[6], "第 7 段是停止条件（恒为末段，权重最高）")
    }
}
