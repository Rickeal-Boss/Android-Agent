package com.rickeal.agent.core.agent.subagent

import com.rickeal.agent.core.model.AgentJson
import kotlinx.serialization.Serializable

/**
 * 子代理（Actor）定义 —— 移植自 ZCode 的具名 Actor 与 Claude Code 式 markdown 定义：
 * 「agent("名字", { system }) 创建的是一个持久化上下文；同一个 Actor 的任务串行执行，
 * 上下文在多次 ask 之间持续累积」。
 *
 * Android 端侧形态：定义是**纯数据**（可来自内置清单或用户/项目文件），执行复用同一个
 * [com.rickeal.agent.core.agent.AgentRunner]（端侧只有一个引擎实例，Actor 隔离的是
 * **上下文**与**系统提示词**，不是引擎进程 —— 与 ZCode 的子进程沙箱不同，这是端侧
 * 资源约束下的同构降级，见 AskSubagentTool 的注释）。
 */
@Serializable
data class SubagentDefinition(
    /** Actor 名字 —— 同时是 ask_actor 工具的 actor 参数值与上下文身份键（ZCode 语义）。 */
    val name: String,
    /** 一句话说明（展示给主模型与用户）。 */
    val description: String = "",
    /** 子代理的系统提示词。 */
    val systemPrompt: String = "",
    /**
     * 允许使用的工具名白名单；null = 继承全部已启用工具（ask_actor 自身永远被排除，
     * 防止 4B 模型递归自我委派）。
     */
    val allowedTools: List<String>? = null,
    /** 子 run 的最大轮次（比主 run 默认值小：辅助任务不该烧太多轮）。 */
    val maxRounds: Int = 4,
)

/** Markdown 解析结果失败的原因（注册时报错可读）。 */
class SubagentParseException(message: String) : IllegalArgumentException(message)

/**
 * Markdown 格式的 Actor 定义解析。
 *
 * 格式（frontmatter + 正文 = 系统提示词）：
 * ```markdown
 * ---
 * name: reviewer
 * description: 审查答案与计划
 * tools: file_read, file_list
 * maxRounds: 4
 * ---
 * 你是严格的审查员……
 * ```
 */
object SubagentMarkdown {

    fun parse(text: String): SubagentDefinition {
        val trimmed = text.trim()
        require(trimmed.startsWith("---")) { "缺少 frontmatter 开头 ---" }
        val end = trimmed.indexOf("\n---", 3)
        require(end > 0) { "缺少 frontmatter 结束 ---" }
        val header = trimmed.substring(3, end)
        val body = trimmed.substring(end + 4).trim()

        val fields = HashMap<String, String>()
        for (rawLine in header.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val idx = line.indexOf(':')
            require(idx > 0) { "frontmatter 行格式错误：$line" }
            fields[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }

        val name = fields["name"]?.takeIf { it.isNotBlank() }
            ?: throw SubagentParseException("frontmatter 缺少 name")
        require(Regex("^[A-Za-z0-9_-]{1,32}$").matches(name)) {
            "name 只允许字母数字下划线连字符（1~32 位）：$name"
        }
        val maxRounds = fields["maxRounds"]?.toIntOrNull()?.coerceIn(1, 16) ?: 4
        val tools = fields["tools"]
            ?.split(',', ';', '，', '；')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }

        return SubagentDefinition(
            name = name,
            description = fields["description"].orEmpty(),
            systemPrompt = body,
            allowedTools = tools,
            maxRounds = maxRounds,
        )
    }
}

/** 子代理注册中心。 */
class SubagentRegistry {

    private val definitions = LinkedHashMap<String, SubagentDefinition>()

    @Synchronized
    fun register(definition: SubagentDefinition) {
        definitions[definition.name] = definition
    }

    @Synchronized
    fun registerMarkdown(text: String) {
        register(SubagentMarkdown.parse(text))
    }

    @Synchronized
    fun get(name: String): SubagentDefinition? = definitions[name]

    @Synchronized
    fun all(): List<SubagentDefinition> = definitions.values.toList()

    @Synchronized
    fun names(): List<String> = definitions.keys.toList()

    /** 序列化整表（供后续做导入导出 / 持久化，Wave 2）。 */
    @Synchronized
    fun toJson(): String = AgentJson.Default.encodeToString(
        ListSerializer,
        definitions.values.toList(),
    )

    private companion object {
        val ListSerializer = kotlinx.serialization.builtins.ListSerializer(SubagentDefinition.serializer())
    }
}
