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

    /**
     * 第三方（ToolContributor）工具注册：**强制过审批闸门**。
     *
     * 贡献者无法自证可信，闸门由宿主而非贡献者决定 —— 未声明审批属性的工具
     * 若直接 register，等于把信任边界建立在贡献者的自觉上（r6 审查 P2-8）。
     * dangerous 不代改：贡献者若声明了 dangerous，按更严的一条走（两标志都会进闸门）。
     * 同名覆盖内置工具时打 warn —— 名字抢注（注册一个 file_read 顶掉内置版）是
     * 真实攻击面，至少要留痕。
     */
    fun registerContributed(tool: Tool) {
        val existing = tools[tool.spec.name]
        if (existing != null) {
            com.rickeal.agent.core.model.AgentLogStore.warn(
                "第三方工具「${tool.spec.name}」覆盖了已注册工具（可能的名字抢注，请确认来源）"
            )
        }
        register(object : Tool by tool {
            override val spec: ToolSpec = tool.spec.copy(requiresConfirmation = true)
        })
    }

    fun registerAllContributed(list: List<Tool>) {
        for (tool in list) registerContributed(tool)
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
