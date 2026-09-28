package com.rickeal.agent.feature.settings.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [toolCategoryLabel] / [TOOL_CATEGORY_ORDER] / [ToolsUiState.refiltered] 的 JVM 纯函数单测
 * （Wave 32 · 流 B：为 :feature-settings 点亮测试源集 —— 此前该模块 src/test 为空，NO-SOURCE）。
 *
 * 为什么这三个值得先钉住（都是展示层直接读的判据，错了用户立刻看得见）：
 *  1. [toolCategoryLabel] 的 `else` 分支**原样回显**未知分类 —— 这是「新分类上线而映射表
 *     没跟上」时的显性信号（chip 显示英文原值），写错成返回空串就变成静默消失；
 *  2. [refiltered] 的分类 chip 顺序 = [TOOL_CATEGORY_ORDER] 规范序 + 表外分类字典序追加
 *     —— 用户记住的 chip 位置不许漂移；
 *  3. `enabledOnly` 与关键词（大小写不敏感，匹配工具名 / 描述）的组合过滤。
 *
 * 刻意**不构造** [ToolsViewModel]：它依赖 AppContainer / 协程，JVM 上不可测。
 * 这里只测被它调用的文件级纯函数（`refiltered` 由 `private` 改 `internal`，函数体一字未动）。
 */
class ToolsPureLogicTest {

    private fun toolOf(
        name: String,
        category: String,
        description: String = "",
        enabled: Boolean = true,
    ) = ToolSpecUi(
        name = name,
        description = description,
        category = category,
        enabled = enabled,
        dangerous = false,
        requiresConfirmation = false,
        parameters = emptyList(),
    )

    // ── toolCategoryLabel：映射表 + 未知分类原样回显 ────────────────────────

    @Test
    fun toolCategoryLabelMapsAllSixCanonicalCategories() {
        assertEquals("Agent", toolCategoryLabel("agent"))
        assertEquals("文件", toolCategoryLabel("file"))
        assertEquals("记忆", toolCategoryLabel("memory"))
        assertEquals("计划", toolCategoryLabel("plan"))
        assertEquals("系统", toolCategoryLabel("system"))
        assertEquals("实用", toolCategoryLabel("utility"))
    }

    @Test
    fun toolCategoryLabelEchoesUnknownCategoryVerbatim() {
        // else 分支是有意的显性信号：新分类没跟上映射表时 chip 显示英文原值而不是消失
        assertEquals("browser", toolCategoryLabel("browser"))
        assertEquals("", toolCategoryLabel(""))
    }

    // ── refiltered：chip 顺序 + 三维过滤组合 ────────────────────────────────

    @Test
    fun refilteredOrdersCanonicalFirstThenOutOfTableAlphabetically() {
        // 表外分类「sync」不在 TOOL_CATEGORY_ORDER 里 ⇒ 追加在规范序之后（防漏出 chip 行）
        val state = ToolsUiState(
            tools = listOf(
                toolOf("t1", "sync"),
                toolOf("t2", "memory"),
                toolOf("t3", "agent"),
                toolOf("t4", "clipboard"),
            ),
        )
        val out = state.refiltered()
        assertEquals(listOf("agent", "memory", "clipboard", "sync"), out.categories)
        assertEquals(4, out.visibleTools.size)
    }

    @Test
    fun refilteredMatchesKeywordCaseInsensitivelyOnNameAndDescription() {
        val state = ToolsUiState(
            tools = listOf(
                toolOf("file_read", "读取沙箱目录内的文本文件"),
                toolOf("memory_write", "写入长期记忆"),
                toolOf("calculator", "四则运算"),
            ),
            query = "FILE",
        )
        // 只有 name 命中（file_read）；描述里含「文件」但 query 是英文
        assertEquals(listOf("file_read"), state.refiltered().visibleTools.map { it.name })

        val byDescription = state.copy(query = "长期记忆").refiltered()
        assertEquals(listOf("memory_write"), byDescription.visibleTools.map { it.name })
    }

    @Test
    fun refilteredCombinesCategoryEnabledAndKeywordFilters() {
        val state = ToolsUiState(
            tools = listOf(
                toolOf("file_read", "读文件", category = "file", enabled = true),
                toolOf("file_write", "写文件", category = "file", enabled = false),
                toolOf("memory_write", "写记忆", category = "memory", enabled = true),
            ),
            category = "file",
            enabledOnly = true,
        )
        // 家族=file 过滤掉 memory_write；enabledOnly 过滤掉 file_write
        val out = state.refiltered()
        assertEquals(listOf("file_read"), out.visibleTools.map { it.name })
        // categories 仍反映全集（chips 不随筛选消失）
        assertEquals(TOOL_CATEGORY_ORDER, out.categories)
    }

    @Test
    fun refilteredWithNoFiltersShowsEverythingInInputOrder() {
        val state = ToolsUiState(
            tools = listOf(toolOf("b", "file"), toolOf("a", "memory")),
        )
        val out = state.refiltered()
        assertEquals(listOf("b", "a"), out.visibleTools.map { it.name })
        assertTrue(out.categories.containsAll(listOf("file", "memory")))
    }
}
