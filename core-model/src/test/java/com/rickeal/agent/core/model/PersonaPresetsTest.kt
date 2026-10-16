package com.rickeal.agent.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [PersonaPreset] 的契约测试（W70 B4；W69-07 人格档位产品件）。
 *
 * 人格 = 写入 `InferenceConfig.systemInstruction` 的 system prompt 文本，
 * **不新增存储字段、不建第二状态源** ⇒ 选中态唯一事实源 = 该字段的逐字内容。
 * 本测试钉三个「错了不报错、只静默改变行为」的面：
 *   ① 每档 prompt 非空且 id 唯一（空文本 = 点选等于没写；id 重复 = 归因串台）；
 *   ② [PersonaPreset.matches] 逐字相等（手改一个句号、多一个空格都不得误判
 *      选中 —— 宽松匹配会把「自定义」显示成预设高亮，chip 与文本不符）；
 *   ③ 预设文本无占位/空转标记（TODO / 待填 / `{{ }}` 等入仓 = 把半成品提示词
 *      直接喂给用户）。
 */
class PersonaPresetsTest {

    @Test
    fun everyPresetHasNonEmptyTextAndUniqueId() {
        val ids = mutableSetOf<String>()
        for (persona in PersonaPreset.entries) {
            assertTrue(persona.label.isNotBlank(), "人格档位的展示名为空：${persona.id}")
            assertTrue(persona.prompt.isNotBlank(), "人格「${persona.label}」的 prompt 为空")
            // 长度下限：挡「一句话空转文案」（占位/凑数文本）。
            assertTrue(
                persona.prompt.length >= 16,
                "人格「${persona.label}」的 prompt 过短（疑似空转文案）：${persona.prompt}",
            )
            assertTrue(ids.add(persona.id), "人格 id 重复：${persona.id}")
        }
        assertTrue(PersonaPreset.entries.size >= 2, "人格档位少于 2 个（选择器失去意义）")
    }

    @Test
    fun matchesSelectsOnlyExactPrompt() {
        for (persona in PersonaPreset.entries) {
            // 逐字相等才选中。
            assertEquals(persona, PersonaPreset.matches(persona.prompt))
            // 手改（尾部多一个句号）不得误判选中。
            assertNull(PersonaPreset.matches(persona.prompt + "。"))
            // 空白差异（首尾各多一个空格）不得误判选中。
            assertNull(PersonaPreset.matches(" ${persona.prompt}"))
            assertNull(PersonaPreset.matches("${persona.prompt} "))
            // 换行差异（尾部多一个换行）同样不得误判。
            assertNull(PersonaPreset.matches(persona.prompt + "\n"))
            // 内嵌空白差异（句号后多一个空格）同样不得误判。
            assertNull(PersonaPreset.matches(persona.prompt.replace("。", "。 ")))
        }
        // 默认态 / 用户清空 / 纯空白：不得命中任何档位（UI 显示「自定义」）。
        assertNull(PersonaPreset.matches(""))
        assertNull(PersonaPreset.matches("   "))
    }

    @Test
    fun presetTextHasNoPlaceholderCopy() {
        val markers = listOf("TODO", "FIXME", "占位", "待填", "{{", "}}", "xxx")
        for (persona in PersonaPreset.entries) {
            for (marker in markers) {
                assertTrue(
                    marker !in persona.prompt,
                    "人格「${persona.label}」的 prompt 含占位/空转标记「$marker」",
                )
            }
        }
    }
}
