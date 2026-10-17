package com.rickeal.agent.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ModelPreset.templateArraySafety] 的数据面单测（W72 D 数据件）。
 *
 * 为什么要有它：本字段是 U-1（「标记体系只记安全、不记不安全」）的结构性补丁 ——
 * bump 决策要回答「0.18.0 会炸哪些容器」，答案全在这 14 个取值里。数据一旦被静默改掉
 * （顺手把 UNVERIFIED 填成 SAFE「看起来完整」、或把 LOSSY 删掉），静态守卫 #39 只能
 * 发现「缺赋值」，发现不了「分布变了」与「UNVERIFIED 裸留」。本测试补这两个面。
 *
 * 钉住的判据：
 *  1. 分布冻结 = **10 SAFE / 2 UNSAFE / 0 LOSSY / 2 UNVERIFIED**（W72 方案席六格取证定稿）；
 *  2. 每个 `UNVERIFIED` 条目的 `backendBasis` 或 `note` **文本内**必须写明理由类别
 *     （`NO_TEMPLATE_IN_CONTAINER` / `NO_CONTAINER`）—— 禁裸留（裸留会被后人读成「漏做」）。
 *
 * ⚠️ 本测试**不**校验「显式赋值」（运行时不可区分默认值与显式写同值）—— 那一面归
 * 静态守卫 #39（CI 席扩展），两者互补。
 */
class ModelPresetTemplateSafetyTest {

    private val presets = ModelPresets.all

    @Test
    fun `templateArraySafety 分布为 10 SAFE 与 2 UNSAFE 与 0 LOSSY 与 2 UNVERIFIED`() {
        assertEquals(14, presets.size, "预设条数变了 —— 本断言与分布断言需同步复核")
        val counts = presets.groupingBy { it.templateArraySafety }.eachCount()
        assertEquals(10, counts[TemplateArraySafety.SAFE] ?: 0, "SAFE 条数漂移")
        assertEquals(2, counts[TemplateArraySafety.UNSAFE] ?: 0, "UNSAFE 条数漂移")
        // LOSSY 现役 0 命中：保留该态是为了捕获「只修 system 分支」滑向不可见失败
        assertEquals(0, counts[TemplateArraySafety.LOSSY] ?: 0, "LOSSY 现役应为 0 —— 新增即须同步本文与 KDoc")
        assertEquals(2, counts[TemplateArraySafety.UNVERIFIED] ?: 0, "UNVERIFIED 条数漂移")
    }

    @Test
    fun `UNVERIFIED 条目必须在文本内写明理由类别`() {
        val categories = listOf("NO_TEMPLATE_IN_CONTAINER", "NO_CONTAINER")
        val unverified = presets.filter {
            it.templateArraySafety == TemplateArraySafety.UNVERIFIED
        }
        assertTrue(unverified.isNotEmpty(), "现役应有 2 条 UNVERIFIED，本断言 vacuous 须复查")
        for (preset in unverified) {
            val text = preset.backendBasis + "\n" + preset.note
            assertTrue(
                categories.any { text.contains(it) },
                "预设「${preset.label}」标 UNVERIFIED 但 backendBasis/note 内无理由类别" +
                    "（${categories.joinToString(" / ")}）—— 禁裸留",
            )
        }
    }
}
