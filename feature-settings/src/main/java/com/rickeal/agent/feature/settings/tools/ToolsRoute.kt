package com.rickeal.agent.feature.settings.tools

/**
 * 工具页路由（Wave4 UI 五页签改造：由设置子页 `settings/tools` 提升为**顶层页签**）。
 *
 * ⚠️ route 必须是顶层字符串、不得带 `settings/` 前缀（六路审查 B-P0-2）：
 * `MainShell` 的选中态按 route 前缀匹配到页签，前缀会被 `settings` 分支吃掉，
 * 选中胶囊永远指不过来 —— 不报错但行为错的那类缺陷。
 */
object ToolsRoute {
    const val ROUTE = "tools"

    fun build(): String = ROUTE
}
