package com.rickeal.agent.core.agent

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
     * 解析文本协议。
     *
     * @param registeredToolNames 当前**真正可执行**的工具名集合。只有名字落在集合里的调用才会
     *   被判定为 [ProtocolResult.Calls]；名字不认识的调用一律降级成 [ProtocolResult.FinalAnswer]，
     *   由上层当作最终答案收尾（宁可少执行一次工具，也不能进死循环）。
     */
    fun parse(text: String, registeredToolNames: Set<String>): ProtocolResult {
        if (text.isBlank()) return ProtocolResult.NoProtocol

        // 1) 显式协议标记：```json 围栏 / <tool_call> 标签
        val blocks = extractBlocks(text)
        if (blocks.isNotEmpty()) {
            val calls = ArrayList<ToolCall>()
            var rejected = false
            for (block in blocks) {
                val element = tryParseJson(block) ?: continue
                when (val outcome = classify(element, registeredToolNames)) {
                    is BlockOutcome.Accepted -> calls.addAll(outcome.calls)
                    is BlockOutcome.Rejected -> rejected = true
                    is BlockOutcome.NotShape -> Unit
                }
            }
            // 只要出现一个「形状对但不可执行」的块，整段就按最终答案处理：
            // 部分执行 + 部分忽略会让模型下一轮重复输出同样的块，仍然会绕圈。
            if (rejected) return ProtocolResult.FinalAnswer(text)
            if (calls.isNotEmpty()) return ProtocolResult.Calls(calls)
        }

        // 2) 裸 JSON（首个 { 到最后一个 }）
        val bare = extractBareJson(text) ?: return ProtocolResult.NoProtocol
        val element = tryParseJson(bare) ?: return ProtocolResult.NoProtocol
        return when (val outcome = classify(element, registeredToolNames)) {
            is BlockOutcome.Accepted -> ProtocolResult.Calls(outcome.calls)
            is BlockOutcome.Rejected -> ProtocolResult.FinalAnswer(text)
            is BlockOutcome.NotShape -> ProtocolResult.NoProtocol
        }
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
            out.add(inner.trim().removePrefix("json").trim())
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
        var rejected = false
        for (obj in objects) {
            val name = toolNameOf(obj) ?: continue      // 没有工具名字段 → 这个对象不是协议形状
            val argumentsJson = normalizeArguments(argumentsOf(obj))
            // 参数解析不出来 → 形状对但不能执行，标记 rejected（→ 最终答案）
            if (argumentsJson == null) {
                rejected = true
                continue
            }
            // 工具名未注册 → 同上，绝不当作可执行的调用
            if (name !in registeredToolNames) {
                rejected = true
                continue
            }
            calls.add(
                ToolCall(id = newId(), name = name, argumentsJson = argumentsJson, raw = obj.toString())
            )
        }
        return when {
            rejected -> BlockOutcome.Rejected
            calls.isNotEmpty() -> BlockOutcome.Accepted(calls)
            else -> BlockOutcome.NotShape
        }
    }

    private fun toolNameOf(obj: JsonObject): String? {
        val raw = obj["tool"] ?: obj["name"] ?: obj["function"]
        val primitive = raw as? JsonPrimitive ?: return null
        // 工具名必须是字符串：`"name": 123` 之类一律不算协议形状
        if (!primitive.isString) return null
        return primitive.content.takeIf { it.isNotBlank() }
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

    /** [classify] 的内部三态，不对本文件之外暴露。 */
    private sealed interface BlockOutcome {
        data class Accepted(val calls: List<ToolCall>) : BlockOutcome
        object Rejected : BlockOutcome
        object NotShape : BlockOutcome
    }
}
