package com.rickeal.agent.core.agent

import com.rickeal.agent.core.model.ToolSpec
import java.util.concurrent.ConcurrentHashMap

/**
 * 工具注册中心。用 ConcurrentHashMap，允许在生成过程中动态开关。
 */
class ToolRegistry {
    private val tools = ConcurrentHashMap<String, Tool>()
    private val disabled = ConcurrentHashMap.newKeySet<String>()

    fun register(tool: Tool) {
        tools[tool.spec.name] = tool
    }

    fun registerAll(list: List<Tool>) {
        for (tool in list) register(tool)
    }

    fun unregister(name: String) {
        tools.remove(name)
        disabled.remove(name)
    }

    fun setEnabled(name: String, enabled: Boolean) {
        if (enabled) disabled.remove(name) else disabled.add(name)
    }

    fun isEnabled(name: String): Boolean = !disabled.contains(name)

    fun get(name: String): Tool? = if (isEnabled(name)) tools[name] else null

    fun all(): List<Tool> = tools.values.sortedBy { it.spec.name }

    fun enabledTools(): List<Tool> = all().filter { isEnabled(it.spec.name) }

    fun specs(): List<ToolSpec> = enabledTools().map { it.spec }
}
