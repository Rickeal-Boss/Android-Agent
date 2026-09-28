package com.rickeal.agent.feature.settings.tools

/**
 * 「沙箱工作区」文件子页路由（Wave 33）。
 *
 * route 走 `tools/` 前缀：会被 `MainShell` 的 `routeTop()` **最长前缀匹配**自动归到
 * TOOLS 页签（选中态与滑动方向零额外改动）—— 与 `settings/storage` 归到 SETTINGS
 * 是同一处置。前缀纪律与 ToolsRoute 相同：顶层字符串，不得带 `settings/` 前缀。
 */
object SandboxFilesRoute {
    const val ROUTE = "tools/sandbox"

    fun build(): String = ROUTE
}
