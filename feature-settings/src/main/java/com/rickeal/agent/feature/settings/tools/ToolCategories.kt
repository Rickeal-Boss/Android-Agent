package com.rickeal.agent.feature.settings.tools

/**
 * 工具分类的**规范展示顺序**。
 *
 * 只列出「当前实际存在」的分类，按此顺序排列（见 [ToolsViewModel] 的 `refiltered()`）。
 * 用固定顺序而不是「按注册顺序去重」，是为了让 chip 行在工具增删后仍稳定 ——
 * 否则每加一个工具，chip 的顺序都可能变，用户刚记住的位置就没了。
 */
internal val TOOL_CATEGORY_ORDER = listOf("agent", "file", "memory", "plan", "system", "utility")

/**
 * 工具分类的中文展示名。
 *
 * 分类原始值是 `ToolSpec.category`（`:core-agent` 注册工具时写死的英文串），
 * 取值域共 6 类：[TOOL_CATEGORY_ORDER]。
 *
 * ## 为什么放这里而不是 `core-design`
 *
 * 这是一张**展示层**映射表：`core-design` 必须零业务依赖（arch-guard 第 2 条扫整个
 * `core-design/`，连注释都不放过），而「分类取值域」属于业务语义。放在消费它的
 * feature 模块里，改动面最小、归属最清楚。
 *
 * 未知分类**原样回显**（走 `else` 分支）：将来新增分类时，chip 会显示英文原值而不是
 * 凭空消失，便于一眼看出「这里有张表没跟上」，而不是静默漏掉一个分类。
 */
internal fun toolCategoryLabel(category: String): String = when (category) {
    "agent" -> "Agent"
    "file" -> "文件"
    "memory" -> "记忆"
    "plan" -> "计划"
    "system" -> "系统"
    "utility" -> "实用"
    else -> category
}
