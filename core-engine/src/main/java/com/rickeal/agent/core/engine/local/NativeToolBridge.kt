package com.rickeal.agent.core.engine.local

import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import com.rickeal.agent.core.model.AgentJson
import com.rickeal.agent.core.model.ToolParameter
import com.rickeal.agent.core.model.ToolParamType
import com.rickeal.agent.core.model.ToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/*
 * 原生工具通道的桥接层（Wave 34 题 A，引擎侧）。
 *
 * 存在理由：工具此前走**文本协议** —— 系统提示词里塞一整段工具清单 + JSON 调用格式，
 * 小模型续写时先复述它，即用户反复反馈的「只输出提示词及工具调用语言然后胡言乱语」。
 * 改走 litertlm 原生工具通道后该段可以从提示词删除（由 Agent 层负责），回显面消失。
 *
 * 本文件只做三件纯翻译工作，全部是**纯函数**（无 IO、无 native 调用），因此可被 JVM
 * 单测直接覆盖（见 NativeToolSchemaTest）：
 *  1. ToolSpec → litertlm 工具描述 JSON（toOpenApiSchemaJson）；
 *  2. ToolSpec 列表 → ToolProvider 列表（toToolProviders）；
 *  3. native 回传的 tool_call 参数 ↔ JSON 串（argsMapToJson / jsonToArgsMap）。
 *
 * ## 红线：工具**只声明、不执行**
 *
 * litertlm 的 `ConversationConfig.automaticToolCalling` **默认为 true** —— 一旦为 true，
 * native 会自己调用 `OpenApiTool.execute()` 把工具跑掉，完全绕过 AgentRunner 的审批 /
 * 沙箱 / 熔断管线。因此：
 *  - 引擎侧的 ConversationConfig 构造点必须**显式**写 `automaticToolCalling = false`
 *    （漏写即静默绕过审批，是本项目最严重的一类事故）；
 *  - 本文件的 DeclaredOnlyTool.execute() **抛异常**而不是返回错误串 —— 这是物理保险：
 *    万一上面那道闸门被误设 true，异常会经 native 报错可见；返回错误串则会让模型以为
 *    工具真的执行了，从而**静默**绕过审批（不可观测，比报错糟得多）。
 */

/**
 * 引擎侧能力探测用的**哑工具**（Wave 34 题 A）。
 *
 * 只在 `LiteRtLmEngine.load()` 的探针里注册一次，用途是验证「当前模型 / 转换件能否接受
 * 原生工具注册」（schema 解析发生在 `createConversation` 内部，解析不过就整段抛错）。
 * 名字刻意带 `cam_p_` 前缀避免与真实工具重名；它永远不会被调用（通道恒定
 * `automaticToolCalling = false`，且 [DeclaredOnlyTool.execute] 抛异常）。
 */
private val NATIVE_TOOL_PROBE_SPEC = ToolSpec(
    name = "cam_p_native_tool_probe",
    description = "Capability probe only. Never invoked.",
    parameters = listOf(
        ToolParameter(
            name = "probe",
            type = ToolParamType.STRING,
            description = "unused",
            required = false,
        ),
    ),
)

/**
 * 把 [ToolSpec] 渲染成 **OpenAI 平铺形**工具描述 JSON。
 *
 * 形状（与 litertlm 0.17.1 `ToolManager` 的解析口径一致）：
 * ```
 * {"name":…,"description":…,"parameters":{"type":"object","properties":{…},"required":[…]}}
 * ```
 *
 * 两处刻意的保守选择：
 *  - **无参工具也必须给空 `parameters` 对象**（而不是省略）—— 某些转换件在缺
 *    `parameters` 时会把工具描述判为非法，而多给一个空对象是 JSON Schema 的合法形态；
 *  - **`required` 恒存在**（无必填参数时为空数组）—— 同样是「多给比少给安全」，
 *    且形状稳定便于真机日志比对。
 *
 * `enumValues` 非空时才写 `enum` 键（空数组会被部分模板当成「该参数无可选值」）。
 * 描述里的引号 / 换行由 kotlinx.serialization 负责转义，产出**恒为合法 JSON**。
 */
internal fun ToolSpec.toOpenApiSchemaJson(): String = JsonObject(
    buildMap<String, JsonElement> {
        put("name", JsonPrimitive(name))
        put("description", JsonPrimitive(description))
        put(
            "parameters",
            JsonObject(
                buildMap<String, JsonElement> {
                    put("type", JsonPrimitive("object"))
                    put("properties", propertiesOf(parameters))
                    put(
                        "required",
                        JsonArray(parameters.filter { it.required }.map { JsonPrimitive(it.name) }),
                    )
                },
            ),
        )
    },
).toString()

/** `parameters.properties` 片段：参数名 → 该参数的 JSON Schema 片段。 */
private fun propertiesOf(parameters: List<ToolParameter>): JsonObject = JsonObject(
    parameters.associate { parameter ->
        parameter.name to JsonObject(
            buildMap<String, JsonElement> {
                put("type", JsonPrimitive(parameter.type.toJsonType()))
                put("description", JsonPrimitive(parameter.description))
                if (parameter.enumValues.isNotEmpty()) {
                    put("enum", JsonArray(parameter.enumValues.map { JsonPrimitive(it) }))
                }
            },
        )
    },
)

/** [ToolParamType] → JSON Schema 的类型串。`when` 穷举：新增类型会编译不过。 */
private fun ToolParamType.toJsonType(): String = when (this) {
    ToolParamType.STRING -> "string"
    ToolParamType.NUMBER -> "number"
    ToolParamType.INTEGER -> "integer"
    ToolParamType.BOOLEAN -> "boolean"
    ToolParamType.ARRAY -> "array"
    ToolParamType.OBJECT -> "object"
}

/**
 * 「只声明、不执行」的 litertlm 工具实现。
 *
 * `execute()` 抛异常是**红线物理保险**，见本文件顶部说明：宁可让误开的自动调用炸出可见
 * 错误，也不能返回一个「看起来执行成功」的错误串让模型静默绕过审批管线。
 */
private class DeclaredOnlyTool(private val spec: ToolSpec) : OpenApiTool {
    override fun getToolDescriptionJsonString(): String = spec.toOpenApiSchemaJson()

    override fun execute(args: String): String = throw IllegalStateException(
        "原生自动执行已禁用：${spec.name} 必须经 AgentRunner 审批/沙箱管线（收到参数：$args）"
    )
}

/**
 * 工具清单 → litertlm 的 `ToolProvider` 列表。
 *
 * 只能走 `tool(OpenApiTool)` 工厂：`ToolProvider` 是抽象类，其抽象方法名带模块混淆后缀
 * （litertlm 0.17.1 实测），在应用侧**无法覆写**。
 */
internal fun List<ToolSpec>.toToolProviders(): List<ToolProvider> = map { tool(DeclaredOnlyTool(it)) }

/**
 * 探针用的一套 `ToolProvider`（哑工具 `NATIVE_TOOL_PROBE_SPEC`）。
 *
 * 做成函数而不是顶层 val：注册动作只在引擎探针里发生一次，避免类加载阶段就产生与
 * native 生命周期无关的常驻对象。
 */
internal fun nativeToolProbeProviders(): List<ToolProvider> =
    listOf(tool(DeclaredOnlyTool(NATIVE_TOOL_PROBE_SPEC)))

/**
 * native 回传的 tool_call 参数（`com.google.ai.edge.litertlm.ToolCall.arguments`）→ JSON 串。
 *
 * 为什么不能直接 `toString()`：`arguments` 的值来自 gson `JsonObject.toMap()`，形态是
 * Kotlin/Java 的**普通容器**（`Map` / `List` / `String` / `Boolean` / `Number` / `null`），
 * 也可能直接是 gson 的 `JsonElement`。`Map.toString()` 是 Kotlin 的调试格式（`{a=1}`，
 * 不是 JSON），喂给上层解析必然失败。这里递归转成 kotlinx `JsonElement` 再 `toString()`，
 * 产出**恒为合法 JSON**。
 *
 * 兜底：无法识别的类型退化为 `toString()` 的字符串（宁可形状次优，也不丢字段 / 不抛异常）。
 */
internal fun argsMapToJson(arguments: Map<String, Any?>): String =
    JsonObject(arguments.mapValues { (_, value) -> toJsonElement(value) }).toString()

private fun toJsonElement(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(
        value.entries.associate { entry -> entry.key.toString() to toJsonElement(entry.value) }
    )
    is Iterable<*> -> JsonArray(value.map { toJsonElement(it) })
    is Array<*> -> JsonArray(value.map { toJsonElement(it) })
    // gson JsonElement 等兜底：保住「不丢字段、不抛异常」，形状退化为字符串。
    else -> JsonPrimitive(value.toString())
}

/**
 * [argsMapToJson] 的逆：应用侧落盘的 `ToolCall.argumentsJson` → native 需要的
 * `Map<String, Any?>`。
 *
 * 用在**历史重放**（会话重建时把 MODEL 轮的 tool_calls 播种回 native）。非法 / 非对象的
 * JSON 返回空 Map —— 历史里的坏参数不该让整次会话重建失败（模型输出的参数串本就可能
 * 不是严格 JSON）。
 */
internal fun jsonToArgsMap(json: String): Map<String, Any?> {
    val element = runCatching { AgentJson.Default.parseToJsonElement(json) }.getOrNull()
        ?: return emptyMap()
    if (element !is JsonObject) return emptyMap()
    return element.mapValues { (_, value) -> toPlainValue(value) }
}

private fun toPlainValue(element: JsonElement): Any? = when (element) {
    is JsonNull -> null
    is JsonObject -> element.mapValues { (_, value) -> toPlainValue(value) }
    is JsonArray -> element.map { toPlainValue(it) }
    is JsonPrimitive -> when {
        element.isString -> element.content
        element.booleanOrNull != null -> element.booleanOrNull
        element.longOrNull != null -> element.longOrNull
        element.doubleOrNull != null -> element.doubleOrNull
        else -> element.contentOrNull
    }
}
