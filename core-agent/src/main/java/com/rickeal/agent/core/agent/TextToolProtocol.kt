package com.rickeal.agent.core.agent

import com.rickeal.agent.core.model.ToolCall
import com.rickeal.agent.core.model.newId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 文本协议解析：兼容 4B 级模型常见的三种"口头调用工具"写法。
 *  1) ```json ... ``` 围栏
 *  2) <tool_call>...</tool_call> 标签（Qwen 系）
 *  3) 裸 JSON（首个 { 到最后一个 }）
 * 支持两种载荷形状：单个对象 {"tool":..,"arguments":..} 或数组 [{...},{...}]
 */
object TextToolProtocol {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): List<ToolCall> {
        if (text.isBlank()) return emptyList()
        val blocks = extractBlocks(text)
        val calls = ArrayList<ToolCall>()
        for (block in blocks) {
            calls.addAll(parseBlock(block))
        }
        if (calls.isEmpty()) {
            val bare = text.substringAfter('{', "").let { if (it.isEmpty()) "" else "{" + it }
            val trimmed = bare.substringBeforeLast('}', "").let { if (it.isEmpty()) "" else it + "}" }
            if (trimmed.isNotBlank()) calls.addAll(parseBlock(trimmed))
        }
        return calls
    }

    /** 把工具 JSON 从展示文本里剥掉，避免用户看到一堆代码。 */
    fun strip(text: String): String {
        var result = text
        for (block in extractBlocks(text)) {
            result = result.replace("```json", "", ignoreCase = true)
            result = result.replace(block, "")
        }
        result = result.replace("```", "")
        result = Regex("<tool_call>[\\s\\S]*?</tool_call>").replace(result, "")
        return result.trim()
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

    private fun parseBlock(block: String): List<ToolCall> {
        val element = try {
            json.parseToJsonElement(block)
        } catch (t: Throwable) {
            return emptyList()
        }
        return when (element) {
            is JsonArray -> element.mapNotNull { toCall(it) }
            is JsonObject -> listOfNotNull(toCall(element))
            else -> emptyList()
        }
    }

    private fun toCall(element: JsonElement): ToolCall? {
        val obj = element as? JsonObject ?: return null
        val name = (obj["tool"] ?: obj["name"] ?: obj["function"])?.let { it as? JsonPrimitive }?.content
            ?: return null
        val args = (obj["arguments"] ?: obj["parameters"] ?: obj["args"] ?: obj["input"])
        val argsJson = when (args) {
            null -> "{}"
            is JsonPrimitive -> args.content
            else -> args.toString()
        }
        return ToolCall(id = newId(), name = name, argumentsJson = argsJson, raw = obj.toString())
    }
}
