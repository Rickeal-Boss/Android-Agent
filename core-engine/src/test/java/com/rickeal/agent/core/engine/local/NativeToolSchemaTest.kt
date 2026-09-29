package com.rickeal.agent.core.engine.local

import com.rickeal.agent.core.model.ToolParameter
import com.rickeal.agent.core.model.ToolParamType
import com.rickeal.agent.core.model.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 原生工具通道的纯函数单测（Wave 34 题 A）：工具 schema 渲染与 tool_call 参数的双向翻译。
 *
 * 为什么值得写：这几段是**无法离线验证形状**的那部分 —— litertlm 的 `ToolManager` 在
 * `createConversation` 内部解析 `getToolDescriptionJsonString()` 的返回值，形状不被接受时
 * 整段抛错（会话创建失败，连文本协议一起没了）。R1 只能靠真机日志定位，所以这里必须把
 * 「产出恒为合法 JSON、类型串正确、无参工具也有空 parameters」这类**可在 JVM 上验证**的
 * 部分钉死，把真机的未知面收窄到「这个形状这个模型认不认」。
 *
 * 纯函数、零 native：不构造引擎、不加载 litertlm（[toOpenApiSchemaJson] / [argsMapToJson] /
 * [jsonToArgsMap] 都不 Touch native 或 Android API）。
 */
class NativeToolSchemaTest {

    private fun specOf(parameters: List<ToolParameter>): ToolSpec = ToolSpec(
        name = "read_file",
        description = "读取文件内容",
        parameters = parameters,
    )

    private fun parseSchema(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    // ── toOpenApiSchemaJson：形状 ────────────────────────────────────────

    @Test
    fun `schema 顶层是 OpenAI 平铺形`() {
        val schema = parseSchema(
            specOf(
                listOf(
                    ToolParameter(name = "path", type = ToolParamType.STRING),
                )
            ).toOpenApiSchemaJson()
        )
        assertEquals("read_file", schema["name"]?.jsonPrimitive?.content)
        assertEquals("读取文件内容", schema["description"]?.jsonPrimitive?.content)
        assertEquals("object", schema["parameters"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun `类型映射_六种全覆盖`() {
        val cases = mapOf(
            ToolParamType.STRING to "string",
            ToolParamType.NUMBER to "number",
            ToolParamType.INTEGER to "integer",
            ToolParamType.BOOLEAN to "boolean",
            ToolParamType.ARRAY to "array",
            ToolParamType.OBJECT to "object",
        )
        for ((type, expected) in cases) {
            val schema = parseSchema(
                specOf(listOf(ToolParameter(name = "p", type = type))).toOpenApiSchemaJson()
            )
            val property = schema["parameters"]?.jsonObject
                ?.get("properties")?.jsonObject
                ?.get("p")?.jsonObject
            assertEquals(
                expected,
                property?.get("type")?.jsonPrimitive?.content,
                "ToolParamType.$type 应映射为 JSON Schema 的 \"$expected\"",
            )
        }
    }

    @Test
    fun `enum 非空时才写 enum 键`() {
        val withEnum = parseSchema(
            specOf(
                listOf(
                    ToolParameter(
                        name = "mode",
                        type = ToolParamType.STRING,
                        enumValues = listOf("head", "tail"),
                    ),
                )
            ).toOpenApiSchemaJson()
        )
        val property = withEnum["parameters"]?.jsonObject?.get("properties")?.jsonObject
            ?.get("mode")?.jsonObject
        val enumValues = property?.get("enum")?.jsonArray?.map { it.jsonPrimitive.content }
        assertEquals(listOf("head", "tail"), enumValues)

        // 负例：没有可选值时**不写** enum（空数组会被部分模板当成「无可选值」）。
        val withoutEnum = parseSchema(
            specOf(listOf(ToolParameter(name = "mode", type = ToolParamType.STRING)))
                .toOpenApiSchemaJson()
        )
        val plain = withoutEnum["parameters"]?.jsonObject?.get("properties")?.jsonObject
            ?.get("mode")?.jsonObject
        assertFalse(plain?.containsKey("enum") ?: false, "无 enumValues 时不应出现 enum 键")
    }

    @Test
    fun `无参工具也有空 parameters 对象`() {
        val schema = parseSchema(specOf(emptyList()).toOpenApiSchemaJson())
        val parameters = schema["parameters"]?.jsonObject
        assertTrue(parameters != null, "无参工具也必须给出 parameters（缺了可能被判非法 schema）")
        assertEquals("object", parameters?.get("type")?.jsonPrimitive?.content)
        assertTrue(
            parameters?.get("properties")?.jsonObject?.isEmpty() ?: false,
            "无参工具的 properties 应为空对象",
        )
        assertTrue(
            parameters?.get("required")?.jsonArray?.isEmpty() ?: false,
            "无参工具的 required 应为空数组",
        )
    }

    @Test
    fun `required 恒存在且只含必填参数`() {
        val schema = parseSchema(
            specOf(
                listOf(
                    ToolParameter(name = "path", type = ToolParamType.STRING, required = true),
                    ToolParameter(name = "limit", type = ToolParamType.INTEGER, required = false),
                )
            ).toOpenApiSchemaJson()
        )
        val required = schema["parameters"]?.jsonObject?.get("required")?.jsonArray
            ?.map { it.jsonPrimitive.content }
        assertEquals(listOf("path"), required, "required 只取 ToolParameter.required 为真的参数")
    }

    @Test
    fun `描述含引号换行时仍产出合法 JSON`() {
        val description = "他说：“别再复述提示词”\n第二行\t带制表符 \\ 与反斜杠"
        val spec = ToolSpec(name = "echo", description = description)
        val schema = parseSchema(spec.toOpenApiSchemaJson())
        assertEquals(description, schema["description"]?.jsonPrimitive?.content)
    }

    @Test
    fun `参数描述为空时也写入空串`() {
        // 「多给比少给安全」：部分模板/解析器要求每个属性都带 description。
        val schema = parseSchema(
            specOf(listOf(ToolParameter(name = "p", type = ToolParamType.STRING, description = "")))
                .toOpenApiSchemaJson()
        )
        val property = schema["parameters"]?.jsonObject?.get("properties")?.jsonObject
            ?.get("p")?.jsonObject
        assertEquals("", property?.get("description")?.jsonPrimitive?.content)
    }

    // ── argsMapToJson：native 参数 → JSON 串 ─────────────────────────────

    @Test
    fun `argsMapToJson_标量与空 Map`() {
        assertEquals("{}", argsMapToJson(emptyMap()))
        val json = Json.parseToJsonElement(
            argsMapToJson(
                mapOf(
                    "s" to "文本",
                    "b" to true,
                    "i" to 42,
                    "d" to 1.5,
                )
            )
        ).jsonObject
        assertEquals("文本", json["s"]?.jsonPrimitive?.content)
        assertEquals("true", json["b"]?.jsonPrimitive?.content)
        assertEquals("42", json["i"]?.jsonPrimitive?.content)
        assertEquals("1.5", json["d"]?.jsonPrimitive?.content)
    }

    @Test
    fun `argsMapToJson_嵌套 Map 与 List`() {
        val raw = mapOf<String, Any?>(
            "outer" to mapOf("inner" to listOf(1, "x", false)),
            "list" to listOf(mapOf("k" to "v")),
        )
        val json = Json.parseToJsonElement(argsMapToJson(raw)).jsonObject
        val outer = json["outer"]?.jsonObject
        assertEquals("1", outer?.get("inner")?.jsonArray?.get(0)?.jsonPrimitive?.content)
        assertEquals("x", outer?.get("inner")?.jsonArray?.get(1)?.jsonPrimitive?.content)
        assertEquals("false", outer?.get("inner")?.jsonArray?.get(2)?.jsonPrimitive?.content)
        assertEquals(
            "v",
            json["list"]?.jsonArray?.get(0)?.jsonObject?.get("k")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `argsMapToJson_null 保留为 JSON null 且字段不丢`() {
        val json = Json.parseToJsonElement(
            argsMapToJson(mapOf("a" to null, "b" to "x"))
        ).jsonObject
        assertTrue(json.containsKey("a"), "null 值字段必须保留（丢弃会让模型以为参数没传）")
        assertTrue(json["a"] is JsonNull)
        assertEquals("x", json["b"]?.jsonPrimitive?.content)
    }

    @Test
    fun `argsMapToJson_已是 JsonElement 的值原样透传`() {
        // gson JsonObject.toMap() 的产物里可能出现 JsonElement：不能退化成 toString()。
        val json = Json.parseToJsonElement(
            argsMapToJson(mapOf("nested" to JsonPrimitive("v")))
        ).jsonObject
        assertEquals("v", json["nested"]?.jsonPrimitive?.content)
    }

    // ── jsonToArgsMap：JSON 串 → native 参数（历史重放路径）──────────────

    @Test
    fun `jsonToArgsMap_还原嵌套结构`() {
        val map = jsonToArgsMap("""{"s":"文本","i":42,"b":true,"n":null,"o":{"k":1},"a":[1,"x"]}""")
        assertEquals("文本", map["s"])
        assertEquals(42L, map["i"])
        assertEquals(true, map["b"])
        assertTrue(map.containsKey("n") && map["n"] == null)
        assertEquals(mapOf("k" to 1L), map["o"])
        assertEquals(listOf(1L, "x"), map["a"])
    }

    @Test
    fun `jsonToArgsMap_空串与非法 JSON 返回空 Map`() {
        // 历史里的坏参数不该让整次会话重建失败（模型输出的参数串本就可能不是严格 JSON）。
        assertTrue(jsonToArgsMap("").isEmpty())
        assertTrue(jsonToArgsMap("不是 JSON").isEmpty())
        assertTrue(jsonToArgsMap("[1,2]").isEmpty(), "非对象 JSON 一律视为无参数")
    }

    @Test
    fun `jsonToArgsMap_与 argsMapToJson 可往返`() {
        val original = mapOf<String, Any?>(
            "path" to "/sdcard/a.txt",
            "limit" to 10,
            "recursive" to false,
            "extra" to mapOf("k" to listOf(1, 2)),
        )
        val restored = jsonToArgsMap(argsMapToJson(original))
        assertEquals(original["path"], restored["path"])
        assertEquals(10L, restored["limit"], "整数在往返后归一为 Long（native 侧只认 Map<String, Any?>）")
        assertEquals(false, restored["recursive"])
        assertEquals(mapOf("k" to listOf(1L, 2L)), restored["extra"])
    }
}
