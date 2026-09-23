package com.rickeal.agent.core.agent.schema

import com.rickeal.agent.core.model.ToolParamType
import com.rickeal.agent.core.model.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 工具参数校验违规（对齐 ZCode WorkflowError 的 Violation 设计：JSON 路径 + 期望 + 实得）。
 *
 * 为什么要结构化而不只是一句 errorMessage：端侧 4B 模型的工具调用错误绝大多数是
 * 「参数类型给错 / 漏了必填项」。把「哪一项、期望什么、实际给了什么」逐条写进
 * tool_result，模型就能在下一轮**定向修复**，而不是笼统收到一句"参数错误"后再盲猜
 * ——那是「反复调错 → 反复失败」死循环的主要来源。
 */
data class ArgsViolation(
    /** 出错参数名；顶层结构问题（如整段不是 JSON 对象）时为空串。 */
    val param: String,
    /** 人类与模型都可读的期望描述，如 "string"、"必填"、"one of [a, b]"。 */
    val expected: String,
    /** 实际观察到的描述，如 "number"、"缺失"。 */
    val got: String,
) {
    fun describe(): String = if (param.isBlank()) {
        "期望 $expected，实际 $got"
    } else {
        "参数「$param」期望 $expected，实际 $got"
    }
}

/**
 * 工具参数 Schema 校验器（移植自 ZCode 的 typed ask：`ask<T>()` 把类型变成运行时
 * JSON Schema 约束子代理输出 —— 这里把同一语义移植到工具调用参数上）。
 *
 * 校验依据是 [ToolSpec.parameters] 里已有的声明（STRING/NUMBER/INTEGER/BOOLEAN/
 * ARRAY/OBJECT + required + enumValues），**零新依赖、零新注解处理器**。
 *
 * 设计取舍（对齐 TextToolProtocol 的三态哲学：宁可放过，不可死锁）：
 *  - **未声明的多余键一律放行**（ignoreUnknownKeys 语义）：4B 模型常带说明性字段，
 *    因多余字段拒调用会让工具永远调不成；
 *  - **显式 null 视同缺失**：模型爱写 `"path": null`；required 判违规、可选项静默跳过；
 *  - 只在「必然执行失败」时拦：类型不匹配 / 必填缺失 / 枚举越界。工具内部语义校验
 *    （路径合法性、数值范围）仍归工具自己 —— 校验器不做那层判断。
 */
object ToolArgsValidator {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 校验一段工具调用参数。返回违规列表；**空列表 = 通过**。
     * argumentsJson 解析失败 / 不是对象时返回单条顶层违规。
     */
    fun validate(spec: ToolSpec, argumentsJson: String): List<ArgsViolation> {
        if (spec.parameters.isEmpty()) return emptyList()

        val trimmed = argumentsJson.trim()
        val root: JsonElement = if (trimmed.isEmpty()) {
            JsonObject(emptyMap())
        } else {
            try {
                json.parseToJsonElement(trimmed)
            } catch (t: Throwable) {
                return listOf(
                    ArgsViolation(
                        param = "",
                        expected = "一个 JSON 对象",
                        got = "无法解析的 JSON（${t.message?.take(120) ?: t.javaClass.simpleName}）",
                    )
                )
            }
        }
        if (root !is JsonObject) {
            return listOf(ArgsViolation("", "一个 JSON 对象", describe(root)))
        }

        val violations = ArrayList<ArgsViolation>()
        for (parameter in spec.parameters) {
            // 缺失与显式 null 同语义（模型爱写 "path": null）：required 判违规，可选项静默跳过。
            // 条件为假处 rawOrNull 智能转换为非空 JsonElement（数据流分析处理 OR 条件）。
            val rawOrNull = root[parameter.name]
            if (rawOrNull == null || rawOrNull is JsonNull) {
                if (parameter.required) {
                    violations.add(ArgsViolation(parameter.name, "必填", "缺失"))
                }
                continue
            }

            val typeError = typeMismatch(parameter.type, rawOrNull)
            if (typeError != null) {
                violations.add(ArgsViolation(parameter.name, parameter.type.name.lowercase(), typeError))
                continue
            }

            if (parameter.type == ToolParamType.STRING &&
                parameter.enumValues.isNotEmpty() &&
                rawOrNull is JsonPrimitive
            ) {
                if (rawOrNull.content !in parameter.enumValues) {
                    violations.add(
                        ArgsViolation(
                            parameter.name,
                            "one of [${parameter.enumValues.joinToString(", ")}]",
                            "\"${rawOrNull.content}\"",
                        )
                    )
                }
            }
        }
        return violations
    }

    /**
     * 把违规列表渲染成**给模型看**的修复指引（tool_result 的 errorMessage）。
     * 措辞刻意可执行：逐条列差异 + 明确下一步动作。
     */
    fun renderForModel(toolName: String, violations: List<ArgsViolation>): String {
        val head = "工具 $toolName 参数校验失败（${violations.size} 项）："
        val body = violations.mapIndexed { index, v -> "${index + 1}. ${v.describe()}" }
        val tail = "请严格按工具定义修正参数后重新调用同一工具；若认为该工具不适用，请改用其它工具或直接给出结论。"
        return (listOf(head) + body + listOf(tail)).joinToString("\n")
    }

    /** 类型不匹配时返回实际类型描述；匹配返回 null。 */
    private fun typeMismatch(expected: ToolParamType, element: JsonElement): String? {
        val ok = when (expected) {
            ToolParamType.STRING -> element is JsonPrimitive && element.isString
            ToolParamType.NUMBER -> element is JsonPrimitive && !element.isString && isNumeric(element.content)
            ToolParamType.INTEGER -> element is JsonPrimitive && !element.isString &&
                element.content.toLongOrNull() != null
            ToolParamType.BOOLEAN -> element is JsonPrimitive && !element.isString &&
                (element.content == "true" || element.content == "false")
            ToolParamType.ARRAY -> element is JsonArray
            ToolParamType.OBJECT -> element is JsonObject
        }
        return if (ok) null else describe(element)
    }

    private fun isNumeric(content: String): Boolean =
        content.toLongOrNull() != null || content.toDoubleOrNull() != null

    private fun describe(element: JsonElement): String = when (element) {
        is JsonNull -> "null"
        is JsonArray -> "array"
        is JsonObject -> "object"
        is JsonPrimitive ->
            if (element.isString) "string" else if (isNumeric(element.content)) "number" else element.content
    }
}
