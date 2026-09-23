package com.rickeal.agent.core.agent.memory

import com.rickeal.agent.core.agent.Tool
import com.rickeal.agent.core.agent.tools.stringArg
import com.rickeal.agent.core.model.ToolParamType
import com.rickeal.agent.core.model.ToolParameter
import com.rickeal.agent.core.model.ToolResult
import com.rickeal.agent.core.model.ToolSpec

/** 写/更新一条长期记忆（按标题 upsert）。 */
class MemoryWriteTool(private val memory: AgentMemory) : Tool {

    companion object {
        /** 单条大小上限：记忆会注入每次会话的 system prompt（渲染截断 1200 字符）， */
        /** 存储面无界写入既浪费磁盘也放大注入面（r6 审查 P2-10）。 */
        private const val MAX_TITLE_CHARS = 80
        private const val MAX_CONTENT_CHARS = 2000
    }

    override val spec: ToolSpec = ToolSpec(
        name = "memory_write",
        description = "把一条应当长期记住的信息（用户偏好、项目事实、长期约定）写入持久记忆，" +
            "相同标题会覆盖旧值。只写「跨会话仍然有效」的结论，不要记闲聊或临时状态。",
        parameters = listOf(
            ToolParameter("title", ToolParamType.STRING, "记忆标题（唯一键，如「用户偏好」「项目约定」）"),
            ToolParameter("content", ToolParamType.STRING, "记忆内容，一到三句话"),
        ),
        category = "memory",
        // 记忆是跨会话持久的副作用（写错一条会污染后续每一次会话的系统提示词），
        // 与 file_write 同级 —— 执行前过审批闸门；子 run 无审批通道时按 fail-closed 拒绝
        // （子代理本就不该有沉淀长期记忆的权限，这正是想要的边界）。
        requiresConfirmation = true,
    )

    override suspend fun invoke(argumentsJson: String): ToolResult {
        val title = stringArg(argumentsJson, "title").trim()
        val content = stringArg(argumentsJson, "content").trim()
        if (title.isEmpty()) return ToolResult(name = spec.name, ok = false, errorMessage = "缺少 title 参数")
        if (content.isEmpty()) return ToolResult(name = spec.name, ok = false, errorMessage = "缺少 content 参数")
        if (title.length > MAX_TITLE_CHARS) {
            return ToolResult(
                name = spec.name,
                ok = false,
                errorMessage = "标题过长（${title.length} 字符，上限 $MAX_TITLE_CHARS）：请缩短标题",
            )
        }
        if (content.length > MAX_CONTENT_CHARS) {
            return ToolResult(
                name = spec.name,
                ok = false,
                errorMessage = "内容过长（${content.length} 字符，上限 $MAX_CONTENT_CHARS）：只记结论本身，细节放沙箱文件",
            )
        }
        // Wave4 审查（E-P0-2）：记忆文件损坏时 upsert 会拒写 —— 必须把失败透传给模型，
        // 否则模型以为已记住，实际什么都没发生（静默失败比写入失败更糟）。
        val written = memory.upsert(title, content)
        if (!written) {
            return ToolResult(
                name = spec.name,
                ok = false,
                errorMessage = "记忆文件已损坏，本次写入被拒绝以保护原文件。请告知用户人工修复 agent_memory/memory.json",
            )
        }
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
        // 与 memory_write 同理：删除是不可逆的持久副作用，过审批闸门。
        requiresConfirmation = true,
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
