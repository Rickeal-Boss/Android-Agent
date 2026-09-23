package com.rickeal.agent.core.agent.memory

import com.rickeal.agent.core.agent.Tool
import com.rickeal.agent.core.agent.tools.stringArg
import com.rickeal.agent.core.model.ToolParamType
import com.rickeal.agent.core.model.ToolParameter
import com.rickeal.agent.core.model.ToolResult
import com.rickeal.agent.core.model.ToolSpec

/** 写/更新一条长期记忆（按标题 upsert）。 */
class MemoryWriteTool(private val memory: AgentMemory) : Tool {
    override val spec: ToolSpec = ToolSpec(
        name = "memory_write",
        description = "把一条应当长期记住的信息（用户偏好、项目事实、长期约定）写入持久记忆，" +
            "相同标题会覆盖旧值。只写「跨会话仍然有效」的结论，不要记闲聊或临时状态。",
        parameters = listOf(
            ToolParameter("title", ToolParamType.STRING, "记忆标题（唯一键，如「用户偏好」「项目约定」）"),
            ToolParameter("content", ToolParamType.STRING, "记忆内容，一到三句话"),
        ),
        category = "memory",
    )

    override suspend fun invoke(argumentsJson: String): ToolResult {
        val title = stringArg(argumentsJson, "title").trim()
        val content = stringArg(argumentsJson, "content").trim()
        if (title.isEmpty()) return ToolResult(name = spec.name, ok = false, errorMessage = "缺少 title 参数")
        if (content.isEmpty()) return ToolResult(name = spec.name, ok = false, errorMessage = "缺少 content 参数")
        memory.upsert(title, content)
        return ToolResult(name = spec.name, ok = true, output = "已记住：[$title] $content")
    }
}

/** 读取全部长期记忆。 */
class MemoryReadTool(private val memory: AgentMemory) : Tool {
    override val spec: ToolSpec = ToolSpec(
        name = "memory_read",
        description = "读取全部长期记忆（用户偏好、项目事实等此前沉淀的信息）",
        category = "memory",
    )

    override suspend fun invoke(argumentsJson: String): ToolResult {
        val list = memory.sections()
        if (list.isEmpty()) {
            return ToolResult(name = spec.name, ok = true, output = "（记忆为空）")
        }
        return ToolResult(
            name = spec.name,
            ok = true,
            output = list.joinToString("\n") { "[${it.title}] ${it.content}" },
        )
    }
}

/** 删除一条长期记忆（模型纠错路径：写错了要能删）。 */
class MemoryDeleteTool(private val memory: AgentMemory) : Tool {
    override val spec: ToolSpec = ToolSpec(
        name = "memory_delete",
        description = "删除一条长期记忆（按标题）",
        parameters = listOf(
            ToolParameter("title", ToolParamType.STRING, "要删除的记忆标题"),
        ),
        category = "memory",
    )

    override suspend fun invoke(argumentsJson: String): ToolResult {
        val title = stringArg(argumentsJson, "title").trim()
        if (title.isEmpty()) return ToolResult(name = spec.name, ok = false, errorMessage = "缺少 title 参数")
        val removed = memory.remove(title)
        return ToolResult(
            name = spec.name,
            ok = true,
            output = if (removed) "已删除：[$title]" else "不存在标题为「$title」的记忆",
        )
    }
}

/**
 * 记忆工具装配。挂在 [ToolContext] 上随内置工具一起注册。
 */
fun installMemoryTools(registry: com.rickeal.agent.core.agent.ToolRegistry, memory: AgentMemory) {
    registry.register(MemoryWriteTool(memory))
    registry.register(MemoryReadTool(memory))
    registry.register(MemoryDeleteTool(memory))
}
