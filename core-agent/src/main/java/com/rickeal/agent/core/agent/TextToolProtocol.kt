package com.rickeal.agent.core.agent

import com.rickeal.agent.core.model.AgentLogStore
import com.rickeal.agent.core.model.ToolCall
import com.rickeal.agent.core.model.newId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 文本协议解析结果（三态）。
 *
 * 为什么要三态而不是 `List<ToolCall>`：4B 级模型经常把「用户要求的普通 JSON」直接吐出来，
 * 而 `{"name": "张三"}` 这种对象天然带 `name` 字段，旧实现一律当成工具调用，于是上层
 * 「解析 → 执行 → 失败 → 再问模型 → 模型再吐同样的 JSON」无限循环。
 * 把「有工具调用形状但不可执行」单独标成 [FinalAnswer]，上层就能直接收尾、永不重试。
 */
sealed interface ProtocolResult {
    /** 确认是工具调用：JSON 形状正确 + 工具名已注册 + 参数可解析，三者同时成立。 */
    data class Calls(val calls: List<ToolCall>) : ProtocolResult

    /**
     * 有工具调用的「形状」，但不可执行（工具名未注册 / 参数明显不合法）。
     * 必须按最终答案提交并结束本轮 —— 绝不能重试解析，否则就是死循环。
     * 携带的是**原文**而不是剥离后的文本：用户可能就是要这段 JSON 作为答案。
     *
     * W83-03（LQ-1）：真机 186 会话里 11 例「发一句话回一堆 JSON、无任何解释」后，
     * [TextToolProtocol.parse] 在原文之后追加一行可读说明（被拒工具名 + 不可用原因），
     * 但**仍不 strip 原文** —— 「用户可能就是要这段 JSON」的合法场景不被破坏，
     * 说明行自身也写明「以上为模型原始输出」。
     */
    data class FinalAnswer(val text: String) : ProtocolResult

    /** 文本里没有任何协议形状，按普通回复处理。 */
    object NoProtocol : ProtocolResult
}

/**
 * 文本协议解析：兼容 4B 级模型常见的三种"口头调用工具"写法。
 *  1) ```json ... ``` 围栏
 *  2) <tool_call>...</tool_call> 标签（Qwen 系）
 *  3) 裸 JSON（首个 { 到最后一个 }）
 * 支持两种载荷形状：单个对象 {"tool":..,"arguments":..} 或数组 [{...},{...}]
 */
object TextToolProtocol {

    private val json = Json { ignoreUnknownKeys = true }

    /** 一个完整的 ``` 围栏块；group 1 是块内内容（语言标记已被吃掉）。 */
    private val fenceRegex = Regex("```[A-Za-z0-9_+.-]*\\r?\\n?([\\s\\S]*?)```")

    /** Qwen 系的 <tool_call>…</tool_call> 标签。 */
    private val toolCallTagRegex = Regex("<tool_call>[\\s\\S]*?</tool_call>")

    /**
     * 围栏语言标记 `json`（**大小写不敏感**，W67 B0 修）。
     *
     * 历史病灶：`extractBlocks` 曾用 `removePrefix("json")`（大小写敏感）——部分转换件模板
     * 产大写/混合大小写标记（` ```JSON ` / ` ```Json `）时标记剥不掉，`tryParseJson` 对残留串
     * 失败 ⇒ **工具调用整体丢弃**；且 `strip()` 侧同一判据也认不出该块 ⇒ 围栏原文泄漏进
     * 用户可见正文与会话历史（协议回显小径复活）。`\b` 边界保证 `json5` 一类不被误剥。
     */
    private val fenceLangMarkerRegex = Regex("^json\\b", RegexOption.IGNORE_CASE)

    /**
     * 解析文本协议。
     *
     * @param registeredToolNames 当前**真正可执行**的工具名集合。只有名字落在集合里的调用才会
     *   被判定为 [ProtocolResult.Calls]；名字不认识的调用一律降级成 [ProtocolResult.FinalAnswer]，
     *   由上层当作最终答案收尾（宁可少执行一次工具，也不能进死循环）。名字先做 `trim()` 归一
     *   再判注册（W83-03：真机实证 `" clipboard"` 前导空格误拒本仓真实工具）。
     *   rejected 降级时 [ProtocolResult.FinalAnswer.text] = 原文 + 一行可读说明（见
     *   [rejectedFinalAnswer]），并落 `AgentLogStore.warn` 留痕。
     */
    fun parse(text: String, registeredToolNames: Set<String>): ProtocolResult {
        if (text.isBlank()) return ProtocolResult.NoProtocol

        // 1) 显式协议标记：```json 围栏 / <tool_call> 标签
        val blocks = extractBlocks(text)
        if (blocks.isNotEmpty()) {
            val calls = ArrayList<ToolCall>()
            val rejectedNames = ArrayList<String>()
            for (block in blocks) {
                val element = tryParseJson(block) ?: continue
                when (val outcome = classify(element, registeredToolNames)) {
                    is BlockOutcome.Accepted -> calls.addAll(outcome.calls)
                    is BlockOutcome.Rejected -> rejectedNames.addAll(outcome.names)
                    is BlockOutcome.NotShape -> Unit
                }
            }
            // 只要出现一个「形状对但不可执行」的块，整段就按最终答案处理：
            // 部分执行 + 部分忽略会让模型下一轮重复输出同样的块，仍然会绕圈。
            if (rejectedNames.isNotEmpty()) return rejectedFinalAnswer(text, rejectedNames)
            if (calls.isNotEmpty()) return ProtocolResult.Calls(calls)
        }

        // 2) 裸 JSON（首个 { 到最后一个 }）
        val bare = extractBareJson(text) ?: return ProtocolResult.NoProtocol
        val element = tryParseJson(bare) ?: return ProtocolResult.NoProtocol
        return when (val outcome = classify(element, registeredToolNames)) {
            is BlockOutcome.Accepted -> ProtocolResult.Calls(outcome.calls)
            is BlockOutcome.Rejected -> rejectedFinalAnswer(text, outcome.names)
            is BlockOutcome.NotShape -> ProtocolResult.NoProtocol
        }
    }

    /**
     * W83-03（LQ-1）：rejected 降级为最终答案时的**可读化收口**。
     *
     * 裁决不变：仍判 [ProtocolResult.FinalAnswer]、仍携带原文、仍永不重试解析。
     * 只在原文之后追加一行说明（被拒工具名 + 不可用原因）—— 真机实证用户看到
     * 「发一句话回一堆 JSON」时完全不知道模型是想调工具，把静默降级变成可解释的收尾。
     * **不 strip**：用户可能真的要这段 JSON，说明行自带「以上为模型原始输出」兜底。
     *
     * 多个被拒名去重后只列前 3 个（截断补「等」）：说明行不能比原文还长。
     *
     * warn 留痕与 [com.rickeal.agent.core.agent.tools.FileTools] 沙箱逃逸审计同族：
     * 降级是静默决策，不留痕事后无法归因。分级取 `warn` 而非 `error` —— 协议降级不是
     * 安全事件，`error` 才会被 AgentLogFileStore 落盘（杀进程可查），`warn` 只进 200 条
     * 内存环；一次 parse 至多一条，不存在 FileTools 那样的高频刷环风险，故不节流。
     */
    private fun rejectedFinalAnswer(text: String, rejectedNames: List<String>): ProtocolResult.FinalAnswer {
        val names = rejectedNames.distinct()
        if (names.isEmpty()) return ProtocolResult.FinalAnswer(text)
        AgentLogStore.warn("工具协议降级：工具=${names.joinToString(",")} 原因=未注册或参数非法")
        val shown = names.take(3).joinToString("、") { "`$it`" } + if (names.size > 3) "等" else ""
        val explanation = "（模型尝试调用工具 $shown，但该工具当前不可用或调用格式不正确；以上为模型原始输出。）"
        return ProtocolResult.FinalAnswer(text + "\n" + explanation)
    }

    /**
     * 把工具协议片段从展示文本里剥掉，避免用户看到一堆工具 JSON。
     *
     * 只剥离**确认是协议块**的围栏（块内 JSON 带 tool / name / function 字符串键，
     * 判据与 [toolNameOf] 一致）；普通代码块（用户真正想要的 Python / Java / JSON 片段）
     * 原样保留。
     *
     * 为什么不能无条件删：旧实现把所有 ``` 块一律删掉，模型回「说明 + 代码」时用户拿到的
     * 答案会缺一大块；若整段回答就是一个代码块，剥完直接是空串 —— 上层因此提交过空气泡，
     * 还把「只跑了 1 轮」误报成「达到轮次上限」。
     */
    fun strip(text: String): String {
        if (text.isEmpty()) return text
        val withoutFences = fenceRegex.replace(text) { match ->
            if (isProtocolBlock(match.groupValues[1])) "" else match.value
        }
        return toolCallTagRegex.replace(withoutFences, "").trim()
    }

    /** 块内 JSON 是否带「工具名」键 —— 与 [parse] 判定协议形状用的是同一个 [toolNameOf]。 */
    private fun isProtocolBlock(block: String): Boolean {
        val element = tryParseJson(block.trim()) ?: return false
        val objects = when (element) {
            is JsonArray -> element.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(element)
            else -> emptyList()
        }
        return objects.any { toolNameOf(it) != null }
    }

    private fun extractBlocks(text: String): List<String> {
        val out = ArrayList<String>()
        var cursor = 0
        while (cursor < text.length) {
            val fenceStart = text.indexOf("```", cursor)
            if (fenceStart < 0) break
            val fenceEnd = text.indexOf("```", fenceStart + 3)
            if (fenceEnd < 0) break
            val inner = text.substring(fenceStart + 3, fenceEnd)
            out.add(fenceLangMarkerRegex.replace(inner.trim(), "").trim())
            cursor = fenceEnd + 3
        }
        val tag = Regex("<tool_call>([\\s\\S]*?)</tool_call>")
        for (match in tag.findAll(text)) out.add(match.groupValues[1].trim())
        return out
    }

    /** 裸 JSON：首个 `{` 到最后一个 `}`；不构成对象时返回 null。 */
    private fun extractBareJson(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        val end = text.lastIndexOf('}')
        if (end <= start) return null
        return text.substring(start, end + 1)
    }

    private fun tryParseJson(block: String): JsonElement? = try {
        json.parseToJsonElement(block)
    } catch (t: Throwable) {
        null
    }

    /** 把一个已解析的 JSON 元素分类成「可执行的调用 / 有形状但不可执行 / 与协议无关」。 */
    private fun classify(element: JsonElement, registeredToolNames: Set<String>): BlockOutcome {
        val objects: List<JsonObject> = when (element) {
            is JsonArray -> element.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(element)
            else -> emptyList()
        }
        val calls = ArrayList<ToolCall>()
        val rejectedNames = ArrayList<String>()
        for (obj in objects) {
            val name = toolNameOf(obj) ?: continue      // 没有工具名字段 → 这个对象不是协议形状
            val argumentsJson = normalizeArguments(argumentsOf(obj))
            // 参数解析不出来 → 形状对但不能执行，标记 rejected（→ 最终答案）
            if (argumentsJson == null) {
                rejectedNames.add(name)
                continue
            }
            // 工具名未注册 → 同上，绝不当作可执行的调用
            if (name !in registeredToolNames) {
                rejectedNames.add(name)
                continue
            }
            calls.add(
                ToolCall(id = newId(), name = name, argumentsJson = argumentsJson, raw = obj.toString())
            )
        }
        return when {
            rejectedNames.isNotEmpty() -> BlockOutcome.Rejected(rejectedNames)
            calls.isNotEmpty() -> BlockOutcome.Accepted(calls)
            else -> BlockOutcome.NotShape
        }
    }

    private fun toolNameOf(obj: JsonObject): String? {
        val raw = obj["tool"] ?: obj["name"] ?: obj["function"]
        val primitive = raw as? JsonPrimitive ?: return null
        // 工具名必须是字符串：`"name": 123` 之类一律不算协议形状
        if (!primitive.isString) return null
        // W83-03（LQ-1）工具名形态归一：trim 后再判空白与注册表。真机实证
        // `" clipboard"`（前导空格）就是本仓真实工具名（SystemTools.kt:17），
        // 未 trim 时判「未注册」⇒ 整段降级 FinalAnswer ⇒ 原文 JSON 上屏。
        // 注册表工具名均为无空格标识符（calculator / current_time / clipboard /
        // file_read / file_write / memory_* / plan_* 等，已全量核对），trim 安全。
        // trim 后的值同时用于 ToolCall.name（执行侧拿到的就是干净名）。
        return primitive.content.trim().takeIf { it.isNotBlank() }
    }

    private fun argumentsOf(obj: JsonObject): JsonElement? =
        obj["arguments"] ?: obj["parameters"] ?: obj["args"] ?: obj["input"]

    /**
     * 归一化成参数字符串，无法作为参数对象时返回 null。
     *  - 缺省 → "{}"
     *  - 对象/数组 → 原样序列化
     *  - 字符串 → 内容必须本身是一段 JSON 对象/数组（模型常见的双重编码）
     */
    private fun normalizeArguments(args: JsonElement?): String? = when (args) {
        null -> "{}"
        is JsonObject, is JsonArray -> args.toString()
        is JsonPrimitive -> {
            if (!args.isString) return null
            val content = args.content.trim()
            if (content.isEmpty()) {
                "{}"
            } else {
                val parsed = tryParseJson(content)
                if (parsed is JsonObject || parsed is JsonArray) content else null
            }
        }
        else -> null
    }

    /** [classify] 的内部三态，不对本文件之外暴露。Rejected 携带被拒工具名（W83-03 可读化用）。 */
    private sealed interface BlockOutcome {
        data class Accepted(val calls: List<ToolCall>) : BlockOutcome
        data class Rejected(val names: List<String>) : BlockOutcome
        object NotShape : BlockOutcome
    }
}
