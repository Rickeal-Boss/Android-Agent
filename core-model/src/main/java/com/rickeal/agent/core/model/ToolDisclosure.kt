package com.rickeal.agent.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.Locale

/**
 * 隐藏工具目录的一条记录（Wave 27 / Operit「CLI 工具模式」裁剪移植）。
 *
 * 与 [ToolSpec] 的区别：目录条目是**给模型检索用的摘要**，不含执行语义，
 * 也不进提示词 —— 只有被检索命中后才会以文本形式回灌给模型。
 */
data class HiddenToolEntry(
    val name: String,
    val description: String,
    val category: String,
    /** 参数名 + 类型 + 是否必填的紧凑提示，供模型拼出正确参数。 */
    val parameterHints: List<String>,
)

/**
 * 隐藏工具目录：持有全部「真实工具」的摘要，提供**关键词评分检索**。
 *
 * 放在 `core-model` 而不是 `core-agent` 的理由与 [StreamRepetitionDetector] 相同：
 * 它是**纯逻辑**（无 IO、无状态、零 Android 依赖），因此能在 JVM 上直接单测 ——
 * 而 `core-agent` 目前没有测试源集，把它留在那边等于让检索评分失去安全网。
 *
 * 评分口径（数值是**相对权重**，不是概率；命中即排序，全部 0 分视为未命中）：
 * ```
 * 工具名完全等于 query        +200
 * 工具名包含 query            +90
 * 描述包含 query              +60
 * 分类包含 query              +25
 * 逐词项（query 按空白切分）：名 +30 / 分类 +12 / 描述 +8
 * ```
 * 排序**思路**（精确匹配优先于子串匹配、名称优先于描述）属通用检索常识；**具体倍率
 * 按本方语料独立推导**，不取自任何外部实现：① 我方工具名是短英文标识
 * （file_read / memory_write），模型说对名字即最强信号，故完全匹配 200、名称包含 90；
 * ② 描述是中文长句，含任意查询词的概率天然很高（噪音大），故只给 60；③ 分类只有
 * 个位数取值，命中即命中一大片，故只给 25。逐词项按同一信息量顺序给权重。
 */
class HiddenToolCatalog private constructor(
    private val entries: List<HiddenToolEntry>,
) {

    val size: Int get() = entries.size

    /** 目录是否为空（空目录下 [search] 恒返回空，调用方据此给出可行动提示）。 */
    val isEmpty: Boolean get() = entries.isEmpty()

    /**
     * 按能力关键词检索。
     *
     * @param query 能力关键词或工具名；空白串直接返回空结果（不做「列出全部」——
     *   否则模型会退化成「先空搜一次」的无意义调用）。
     * @param limit 返回条数上限（≤0 时按 [DEFAULT_LIMIT]）。
     */
    fun search(query: String, limit: Int = DEFAULT_LIMIT): List<HiddenToolEntry> {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isEmpty()) return emptyList()
        val terms = tokenize(normalizedQuery)
        val cap = if (limit <= 0) DEFAULT_LIMIT else limit
        return entries
            .map { it to score(it, normalizedQuery, terms) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<HiddenToolEntry, Int>> { it.second }.thenBy { it.first.name })
            .take(cap)
            .map { it.first }
    }

    /**
     * 把检索结果渲染成回灌给模型的一段文本。
     *
     * 渲染成**文本**而不是结构化对象是刻意的：它随工具结果一起进对话历史，走的是
     * 既有的结果回灌通道（含截断与长度预算），不需要新增任何协议面。
     */
    fun renderHits(hits: List<HiddenToolEntry>, query: String): String {
        if (hits.isEmpty()) {
            val hint = if (isEmpty) {
                "当前没有可按需检索的工具。"
            } else {
                "换一个更宽泛的能力关键词再试（例如「文件」「时间」「记忆」「计算」），" +
                    "或用工具的真实英文名检索。"
            }
            return "没有匹配「$query」的工具。$hint"
        }
        val sb = StringBuilder()
        sb.append("匹配「").append(query).append("」的工具（").append(hits.size).append(" 条）：\n")
        for (entry in hits) {
            sb.append("- ").append(entry.name).append("：").append(entry.description)
            if (entry.parameterHints.isNotEmpty()) {
                sb.append("\n  参数：").append(entry.parameterHints.joinToString("，"))
            }
            sb.append('\n')
        }
        sb.append(
            "确认目标后，用 " + DisclosureTools.CALL_TOOL_NAME +
                " 转发执行：{\"tool_name\": \"<工具名>\", \"params\": {<参数>}}。"
        )
        return sb.toString()
    }

    private fun score(entry: HiddenToolEntry, normalizedQuery: String, terms: List<String>): Int {
        val name = normalize(entry.name)
        val description = normalize(entry.description)
        val category = normalize(entry.category)
        var score = 0
        if (name == normalizedQuery) score += 200
        if (name.contains(normalizedQuery)) score += 90
        if (description.contains(normalizedQuery)) score += 60
        if (category.contains(normalizedQuery)) score += 25
        for (term in terms) {
            if (term.length < 2) continue
            if (name.contains(term)) score += 30
            if (category.contains(term)) score += 12
            if (description.contains(term)) score += 8
        }
        return score
    }

    companion object {
        /**
         * 单次检索返回条数上限。按「一次回灌不超过 ~600 字符」反推：每条含描述与参数
         * 提示约 100 字符，故取 6 —— 检索结果走的是既有工具结果回灌通道（受 AgentPolicy
         * 的输出长度预算约束），上限留足空间给后续轮次的其它内容。
         */
        const val DEFAULT_LIMIT = 6

        val EMPTY: HiddenToolCatalog = HiddenToolCatalog(emptyList())

        /**
         * 从工具声明构建目录。
         *
         * **保留名一律剔除**：两个元工具自己不能出现在目录里，否则模型可以
         * `call_tool(search_tools)` 递归转发（浪费轮次，且让「先检索再转发」的
         * 协议约束形同虚设）。这与 Operit 的「保留代理目标」处理同构。
         */
        fun from(specs: List<ToolSpec>): HiddenToolCatalog {
            val entries = specs
                .asSequence()
                .filter { !DisclosureTools.isReservedName(it.name) }
                .map { spec ->
                    HiddenToolEntry(
                        name = spec.name,
                        description = spec.description,
                        category = spec.category,
                        parameterHints = spec.parameters.map { renderParameterHint(it) },
                    )
                }
                .toList()
            return HiddenToolCatalog(entries)
        }

        private fun renderParameterHint(parameter: ToolParameter): String {
            val required = if (parameter.required) "必填" else "可选"
            val type = when (parameter.type) {
                ToolParamType.STRING -> "字符串"
                ToolParamType.NUMBER -> "数字"
                ToolParamType.INTEGER -> "整数"
                ToolParamType.BOOLEAN -> "布尔"
                ToolParamType.ARRAY -> "数组"
                ToolParamType.OBJECT -> "对象"
            }
            val enumSuffix =
                if (parameter.enumValues.isEmpty()) "" else "，取值：" + parameter.enumValues.joinToString("|")
            val desc = if (parameter.description.isBlank()) "" else "（${parameter.description}）"
            return "${parameter.name}:$type,$required$enumSuffix$desc"
        }

        /**
         * 归一化：`Locale.ROOT` 小写 + 折叠空白。
         *
         * `Locale.ROOT` 是硬要求 —— 默认区域设置下土耳其语会把 `I` 小写成 `ı`，
         * 让同一个查询在不同设备上检索出不同结果（本项目在 [StreamRepetitionDetector]
         * 已踩过同一坑，口径保持一致）。
         */
        fun normalize(text: String): String =
            text.lowercase(Locale.ROOT).trim().replace(WHITESPACE, " ")

        /**
         * 检索分词：CJK 连续段按二元组（bigram）切，非 CJK 连续段整词保留。
         *
         * 为什么不能用 `split(' ')`：中文没有空格，「读取文件」会成为**一个** term，
         * `description.contains("读取文件")` 对「读取沙箱目录内的文本文件」= false
         * → 全项 0 分 → 零命中（深度评审离线复现证实 5 组自然语言 query 全部零命中，
         * 而 ON_DEMAND 已 shipped —— 这是已发布的召回率悬崖，不是理论风险）。
         * 二元组把「读取文件」拆成 {读取, 取文, 文件}，与描述的公共子串必然相交。
         *
         * 单字 CJK 段保留原字（单字 query 仍有意义；[score] 侧 `term.length < 2`
         * 的跳过逻辑不变 —— 单字噪音大，与既有口径一致）。
         * 非 CJK 段（英文工具名/标识符）整词保留不拆字符 —— 拆成单字符会产生
         * 大量误命中。标点/空白视为词界。
         *
         * **已申报的覆盖边界（取舍，非疏忽）**：CJK 判定只取**统一表意文字基本区**
         * `U+4E00..U+9FFF` 这一个区间。落在该区间外、但 `Char.isLetterOrDigit()` 仍为
         * true 的字符，会走下面的 `wordRun` 分支被当作**一个整词**—— 也就是中文之外的
         * CJK 语料**仍存在同款召回悬崖**：
         * - 日文平假名/片假名 `U+3040..U+30FF`：「読み込む」= 1 个 term，
         *   不会切成 {読み,み込,込む}；
         * - 韩文谚文音节 `U+AC00..U+D7AF`：「파일읽기」= 1 个 term；
         * - CJK 扩展 A `U+3400..U+4DBF`、兼容表意文字 `U+F900..U+FAFF`：同理整词；
         * - 数字与拉丁字母同属 `isLetterOrDigit`，`file2` 是一个整词（这是想要的）。
         *
         * 不扩区间的理由：本仓工具名/描述/分类是**中英双语**，日韩语料当前为零，
         * 扩区间只会扩大误命中面而换不到召回。真要扩，改动面是下面 `when` 里
         * 那一行区间判断，**并且必须同步补 [tokenize] 用例** —— 这是纯函数，
         * 行为变了测试不会自己发现。
         */
        fun tokenize(text: String): List<String> {
            val out = ArrayList<String>()
            val cjkRun = StringBuilder()
            val wordRun = StringBuilder()
            fun flush() {
                if (cjkRun.isNotEmpty()) {
                    val s = cjkRun.toString()
                    if (s.length == 1) {
                        out.add(s)
                    } else {
                        for (i in 0 until s.length - 1) out.add(s.substring(i, i + 2))
                    }
                    cjkRun.setLength(0)
                }
                if (wordRun.isNotEmpty()) {
                    out.add(wordRun.toString())
                    wordRun.setLength(0)
                }
            }
            for (ch in text) {
                when {
                    ch.code in 0x4E00..0x9FFF -> {
                        if (wordRun.isNotEmpty()) flush()
                        cjkRun.append(ch)
                    }
                    ch.isLetterOrDigit() -> {
                        if (cjkRun.isNotEmpty()) flush()
                        wordRun.append(ch)
                    }
                    else -> flush()
                }
            }
            flush()
            return out
        }

        private val WHITESPACE = Regex("\\s+")
    }
}

/** 一次被解包出来的真实调用（`call_tool` 的载荷 → 目标工具名 + 参数 JSON）。 */
data class UnpackedToolCall(
    val targetName: String,
    val argumentsJson: String,
)

/**
 * 按需披露模式的两个元工具契约（Wave 27）。
 *
 * 设计取舍：这两个工具**不做成 `core-agent` 的 Tool 实例**，而是由 `AgentRunner`
 * 在执行路径上特判 —— 因为 `call_tool` 的语义是「转发到另一个工具」，而审批、同参
 * 守卫、超时、并发闸门全部挂在执行路径上；做成普通 Tool 就要么绕开这些闸门（安全
 * 缺口），要么把执行路径整个复制一份（两处漂移源）。特判是唯一能让转发**逐字节复用**
 * 原路径的形态。
 */
object DisclosureTools {

    const val SEARCH_TOOL_NAME = "search_tools"
    const val CALL_TOOL_NAME = "call_tool"

    /** 参数名常量：解包与提示词渲染共用一份，杜绝字面量漂移。 */
    const val ARG_QUERY = "query"
    const val ARG_LIMIT = "limit"
    const val ARG_TOOL_NAME = "tool_name"
    const val ARG_PARAMS = "params"

    private val RESERVED = setOf(SEARCH_TOOL_NAME, CALL_TOOL_NAME)

    private val json = Json { ignoreUnknownKeys = true }

    /** 保留名判定：元工具自己不得成为转发目标，也不得进入隐藏目录。 */
    fun isReservedName(name: String): Boolean = name.trim() in RESERVED

    /**
     * 按需披露模式下**面向模型**的工具清单（只有两个元工具）。
     *
     * 这份清单会同时用于：① 系统提示词的工具段；② 原生 tool 通道的 tools 参数；
     * ③ `TextToolProtocol.parse` 的「可执行工具名」白名单 —— 三者共用一份，
     * 因此模型**在协议层就无法直接调用隐藏工具**（未注册名会被降级为最终答案，
     * 而不是执行）。这是本模式的可见性边界所在。
     */
    fun publicSpecs(): List<ToolSpec> = listOf(
        ToolSpec(
            name = SEARCH_TOOL_NAME,
            description = "检索当前可用的真实工具（按能力关键词）。只检索工具清单，不读取文件、不访问网络。" +
                "拿到工具名与参数形状后再用 $CALL_TOOL_NAME 转发执行。",
            parameters = listOf(
                ToolParameter(
                    name = ARG_QUERY,
                    type = ToolParamType.STRING,
                    description = "能力关键词或工具名，例如「文件」「时间」「记忆」",
                    required = true,
                ),
                ToolParameter(
                    name = ARG_LIMIT,
                    type = ToolParamType.INTEGER,
                    description = "可选，返回条数上限，默认 ${HiddenToolCatalog.DEFAULT_LIMIT}",
                    required = false,
                ),
            ),
            category = "disclosure",
            // 元工具自身无副作用：检索是纯内存计算，转发由目标工具的效果决定。
            effect = ToolEffect.READ,
        ),
        ToolSpec(
            name = CALL_TOOL_NAME,
            description = "转发执行一个真实工具。必须先用 $SEARCH_TOOL_NAME 拿到目标工具名与参数形状。" +
                "目标工具的危险判定与授权要求照常适用。",
            parameters = listOf(
                ToolParameter(
                    name = ARG_TOOL_NAME,
                    type = ToolParamType.STRING,
                    description = "目标工具名（由 $SEARCH_TOOL_NAME 返回）",
                    required = true,
                ),
                ToolParameter(
                    name = ARG_PARAMS,
                    type = ToolParamType.OBJECT,
                    description = "转发给目标工具的参数对象",
                    required = true,
                ),
            ),
            category = "disclosure",
            // 元工具自身声明 READ（保守面留给目标工具的动态声明）：
            // 若这里声明 WRITE，能力档位会对每一次转发都要求授权 —— 包括只读目标，
            // 那会让「只读档」变成噪音源，反而训练用户无脑点同意。真正的效果判定
            // 发生在解包之后、对**目标工具**重新求值（见 AgentRunner 转发分支）。
            //
            // ⚠️ 这是一条**跨模块契约**，不是本地实现细节：AgentRunner 的转发分支必须
            // 在解包后重新计算目标工具的 effect（含 EffectAwareTool 动态声明），否则
            // 这里的 READ 就会变成「转发通道对能力档位永久隐身」——只读档下任何写操作
            // 都能靠 call_tool 绕过。改任一侧都要同步核对另一侧。
            effect = ToolEffect.READ,
        ),
    )

    /**
     * 把 `call_tool` 的参数解包成一次真实调用。**任何不确定一律返回 null**
     * （fail-closed：宁可让模型收到一条可行动的报错，也不猜目标）。
     *
     * 兼容写法：目标名接受 `tool_name` / `name` / `tool`；参数接受 `params` /
     * `arguments` / `args` —— 与 `TextToolProtocol` 对工具名/参数的宽容口径对齐
     * （小模型经常换 key）。
     */
    fun unpackCall(argumentsJson: String): UnpackedToolCall? {
        val root = runCatching { json.parseToJsonElement(argumentsJson).jsonObject }.getOrNull() ?: return null

        val targetRaw = root[ARG_TOOL_NAME] ?: root["name"] ?: root["tool"]
        val targetPrimitive = targetRaw as? JsonPrimitive ?: return null
        if (!targetPrimitive.isString) return null
        val target = targetPrimitive.content.trim()
        if (target.isEmpty()) return null
        // 递归防护：不得转发到元工具自己。
        if (isReservedName(target)) return null

        // 别名集必须与 TextToolProtocol.argumentsOf 保持**同口径**（arguments / parameters /
        // args / input）：协议层认得的载荷形状，解包层也必须认得，否则会出现「协议层判为
        // 可执行、解包层却解不出来」的分叉 —— 模型写 `parameters` 就会白白收到一条报错。
        val paramsRaw = root[ARG_PARAMS]
            ?: root["arguments"]
            ?: root["parameters"]
            ?: root["args"]
            ?: root["input"]
        val paramsJson = when (paramsRaw) {
            null -> "{}"
            is JsonObject -> paramsRaw.toString()
            is JsonPrimitive -> {
                // 双重编码（模型把参数对象序列化成了字符串）—— 与文本协议同口径。
                if (!paramsRaw.isString) return null
                val content = paramsRaw.content.trim()
                if (content.isEmpty()) {
                    "{}"
                } else {
                    val parsed = runCatching { json.parseToJsonElement(content) }.getOrNull()
                    if (parsed is JsonObject) content else return null
                }
            }
            else -> return null
        }
        return UnpackedToolCall(targetName = target, argumentsJson = paramsJson)
    }

    /** 解包失败时回灌给模型的可行动提示（单一落点，便于与单测对齐）。 */
    const val UNPACK_ERROR_HINT: String =
        "转发参数不合法。请先用 $SEARCH_TOOL_NAME 检索目标工具，然后按 " +
            "{\"tool_name\": \"<工具名>\", \"params\": {<参数对象>}} 的形状调用 $CALL_TOOL_NAME。"

    /** 按需披露模式下追加进系统提示词的协议说明段。 */
    const val DISCLOSURE_GUIDE: String =
        "工具按需检索模式：\n" +
            "- 现在只直接提供两个元工具：$SEARCH_TOOL_NAME（检索真实工具）与 $CALL_TOOL_NAME（转发执行）。\n" +
            "- 真实工具的清单与参数形状不在提示词里，需要先用 $SEARCH_TOOL_NAME 以能力关键词检索。\n" +
            "- 检索命中后，用 $CALL_TOOL_NAME 转发执行；不要凭空猜测工具名，也不要直接写出未检索到的工具名。"
}
