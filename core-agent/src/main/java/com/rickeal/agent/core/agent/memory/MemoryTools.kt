package com.rickeal.agent.core.agent.memory

import com.rickeal.agent.core.agent.Tool
import com.rickeal.agent.core.agent.tools.json
import com.rickeal.agent.core.agent.tools.stringArg
import com.rickeal.agent.core.model.ToolEffect
import com.rickeal.agent.core.model.ToolParamType
import com.rickeal.agent.core.model.ToolParameter
import com.rickeal.agent.core.model.ToolResult
import com.rickeal.agent.core.model.ToolSpec
import kotlinx.serialization.json.JsonPrimitive

/** 写/更新一条长期记忆（按标题 upsert）。 */
class MemoryWriteTool(private val memory: AgentMemory) : Tool {

    companion object {
        /** 单条大小上限：记忆会注入每次会话的 system prompt（渲染截断 1200 字符），
         *  存储面无界写入既浪费磁盘也放大注入面（r6 审查 P2-10）。 */
        private const val MAX_TITLE_CHARS = 80
        // 正文上限**不在此处另写一份**：引用 AgentMemory.MAX_CONTENT_CHARS（存储层同一
        // 判据的唯一定义处，Wave 35 D6）—— 两处各写一个 2000 是必然漂移的双份常量。
        // 这里的前置校验先于 I/O 命中，故模型侧拿到的永远是下面这条精确文案；
        // AgentMemory.upsert 里的同判据拒写是存储层兜底（防换条路写进来），不是重复。
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
        effect = ToolEffect.WRITE,
        // 记忆是跨会话持久的副作用（写错一条会污染后续每一次会话的系统提示词），
        // 与 file_write 同级 —— 执行前过审批闸门；子 run 无审批通道时按 fail-closed 拒绝
        // （子代理本就不该有沉淀长期记忆的权限，这正是想要的边界）。
        requiresConfirmation = true,
        // 检索别名（Wave 31）：用户口语「记住/备忘」。
        keywords = listOf("记住", "记一下", "备忘", "长期记忆"),
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
        if (content.length > AgentMemory.MAX_CONTENT_CHARS) {
            return ToolResult(
                name = spec.name,
                ok = false,
                errorMessage = "内容过长（${content.length} 字符，上限 ${AgentMemory.MAX_CONTENT_CHARS}）：" +
                    "只记结论本身，细节放沙箱文件；确实要长期保留就拆成多条记忆",
            )
        }
        // Wave4 审查（E-P0-2）：记忆文件损坏时 upsert 会拒写 —— 必须把失败透传给模型，
        // 否则模型以为已记住，实际什么都没发生（静默失败比写入失败更糟）。
        // Wave 37：upsert 返回结果类型，非 Ok 的任一结局（超限 / 损坏 / 读不了 / **落盘失败**）都带
        // 面向人的原因（`userMessage`）—— 直接透传，不再一律谎报「文件已损坏」。
        val result = memory.upsert(title, content)
        if (result !is MemoryWriteResult.Ok) {
            return ToolResult(
                name = spec.name,
                ok = false,
                errorMessage = result.userMessage ?: "记忆写入失败",
            )
        }
        return ToolResult(name = spec.name, ok = true, output = "已记住：[$title] $content")
    }
}

/**
 * 读取全部长期记忆。
 *
 * ⚠️ **不退役**（Wave 34 题 B 的显式裁决）：pull 化之后它是「看全文」的唯一逃生舱 ——
 * [MemorySearchTool] 只回摘要（单条正文有渲染预算），没有它模型就再也拿不到正文。
 * 退役还有一条更要命的理由：历史 journal / 崩溃恢复路径里已经写死了 `memory_read`
 * 这个名字，一旦从 registry 撤下，它就变成**未注册名**，会被 `TextToolProtocol.parse`
 * 降级为「最终答案」（而不是执行）—— 那是静默的行为变化，不是功能收敛。
 *
 * 描述里补「输出较长，优先用 memory_search」是**引导**而非禁用：让模型在 FULL 披露
 * 模式的工具清单里自己看到更省的那条路。
 */
class MemoryReadTool(private val memory: AgentMemory) : Tool {
    override val spec: ToolSpec = ToolSpec(
        name = "memory_read",
        description = "读取全部长期记忆（用户偏好、项目事实等此前沉淀的信息）。" +
            "输出较长，优先用 memory_search 按关键词检索",
        category = "memory",
        effect = ToolEffect.READ,
        // 检索别名（Wave 31）。
        keywords = listOf("回忆", "之前记的", "读取记忆"),
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

/**
 * 按关键词检索长期记忆（Wave 34 题 B：记忆从「全量注入」改为「按需检索」的 pull 入口）。
 *
 * 为什么是**工具**而不是「往提示词里塞检索结果」：检索结果随轮次变化，塞进
 * `systemText` 会让系统提示词每轮都变 —— 而 `systemText` 是引擎的**会话重建判据**，
 * 每轮命中即每轮一次全量 re-prefill（4B 秒级；Wave 24 验收点已把「每轮一次」标为异常）。
 * 走工具则结果进的是**对话历史**（工具结果回灌通道），不动系统提示词一个字。
 *
 * 只读、无副作用，故 `requiresConfirmation = false`（与 [MemoryReadTool] 同档）：
 * 给它套审批闸门只会凭空增加弹卡噪音。
 *
 * ⚠️ **提示词面变化申报**（有意改动，非疏漏）：新增工具会让 FULL 披露模式的系统提示词
 * 工具段**多一行** `memory_search`，故该段与 `AgentRunner` 的 `echoCorpus`（回显指纹
 * 语料）逐字节变化。方向是**纯增量**：指纹集按 `\n` 切句建句子级指纹（并对归一化字符流
 * 建窗口哈希集），新增一行只**增加**指纹，既有各句指纹一字不动、仍在集合内 —— 只增强
 * 不削弱。代价与 `FileReadTool` 申报的分页描述改动同款：工具清单变长了一点，
 * 换来的是记忆正文可以从提示词里整体退出（本波的净收益方向）。
 */
class MemorySearchTool(private val memory: AgentMemory) : Tool {
    override val spec: ToolSpec = ToolSpec(
        name = "memory_search",
        description = "按关键词检索长期记忆（用户偏好、项目事实、此前沉淀的结论），返回命中的标题与摘要",
        parameters = listOf(
            ToolParameter("query", ToolParamType.STRING, "检索关键词，例如「偏好」「项目约定」「上次决定」"),
            ToolParameter(
                name = "limit",
                type = ToolParamType.INTEGER,
                description = "可选，返回条数上限，默认 ${MemorySearch.DEFAULT_LIMIT}",
                required = false,
            ),
        ),
        category = "memory",
        effect = ToolEffect.READ,
        requiresConfirmation = false,
        // 检索别名（Wave 31 [ToolSpec.keywords] 口径）：用户口语里的「回忆」类表达。
        // 「有没有记过」是刻意收的一条——它正是「先查再决定写不写」的入口语。
        keywords = listOf("回忆", "之前记的", "偏好", "有没有记过"),
    )

    override suspend fun invoke(argumentsJson: String): ToolResult {
        val query = stringArg(argumentsJson, "query").trim()
        val limit = intArg(argumentsJson, "limit", MemorySearch.DEFAULT_LIMIT)
        // 刻意**不** runCatching：readSync() 内部已吞解析异常（损坏 → 空列表），而
        // runCatching 会把 CancellationException 也吞掉 —— 那会让「停止按钮」在检索阶段
        // 失效（run 继续跑完整轮）。真正的文件异常由 AgentRunner 的工具执行 catch 处理
        // （它会原样上抛取消、只把普通异常转成失败结果），这里不重复兜一层。
        val result = MemorySearch.search(memory.sections(), query, limit)
        // 无命中同样返回 ok = true（与 search_tools 同款动机）：那不是工具失败，而是
        // 「换个关键词」的可行动信息；按失败返回会诱导模型重试同一查询，给循环留入口。
        return ToolResult(
            name = spec.name,
            ok = true,
            output = MemorySearch.render(result, query),
        )
    }
}

/** 安全取整型参数：缺键 / 类型不符 / 非数字一律回退 [default]（数字字符串也接受）。 */
private fun intArg(argumentsJson: String, key: String, default: Int): Int {
    val element = json(argumentsJson)[key] ?: return default
    return (element as? JsonPrimitive)?.content?.toIntOrNull() ?: default
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
        effect = ToolEffect.WRITE,
        // 与 memory_write 同理：删除是不可逆的持久副作用，过审批闸门。
        requiresConfirmation = true,
        // 检索别名（Wave 31）。
        keywords = listOf("忘掉", "删除记忆", "不要再记"),
    )

    override suspend fun invoke(argumentsJson: String): ToolResult {
        val title = stringArg(argumentsJson, "title").trim()
        if (title.isEmpty()) return ToolResult(name = spec.name, ok = false, errorMessage = "缺少 title 参数")
        // Wave 37 行为修复：此前 `remove` 回 false 时工具一律回「不存在标题为…」——
        // 但 false 里还含「文件损坏」这条互不隶属的结局，等于把「损坏」谎报成「不存在」。
        // 现按 [MemoryRemoveResult] 五分支全列：损坏 / **读不了**（Wave 38）/ 落盘失败如实回 ok = false + 原因。
        return when (val result = memory.remove(title)) {
            MemoryRemoveResult.Removed ->
                ToolResult(name = spec.name, ok = true, output = "已删除：[$title]")
            MemoryRemoveResult.NotFound ->
                ToolResult(name = spec.name, ok = true, output = "不存在标题为「$title」的记忆")
            MemoryRemoveResult.Corrupted, is MemoryRemoveResult.WriteFailed,
            is MemoryRemoveResult.Unreadable ->
                ToolResult(name = spec.name, ok = false, errorMessage = result.userMessage ?: "记忆删除失败")
        }
    }
}

/**
 * 记忆工具装配。挂在 [ToolContext] 上随内置工具一起注册。
 */
fun installMemoryTools(registry: com.rickeal.agent.core.agent.ToolRegistry, memory: AgentMemory) {
    registry.register(MemoryWriteTool(memory))
    registry.register(MemoryReadTool(memory))
    // Wave 34 题 B：pull 入口。放在 memory_read **之后**注册 —— registry 的 specs()
    // 按工具名排序输出，注册顺序不影响提示词，但保持「读 → 检索 → 删」的阅读顺序
    // 与写入顺序一致，便于人工核对。memory_read 不退役（理由见其 KDoc）。
    registry.register(MemorySearchTool(memory))
    registry.register(MemoryDeleteTool(memory))
}
