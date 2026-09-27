package com.rickeal.agent.core.agent.schema

import com.rickeal.agent.core.model.ToolParamType
import com.rickeal.agent.core.model.ToolParameter
import com.rickeal.agent.core.model.ToolSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 工具参数 Schema 校验器（[ToolArgsValidator]）真值表。
 *
 * 锁住的设计取舍（对齐 TextToolProtocol 的三态哲学：宁可放过，不可死锁）：
 * - 未声明的多余键一律放行；显式 null 视同缺失；
 * - 只在「必然执行失败」时拦：类型不匹配 / 必填缺失 / 枚举越界；
 * - 违规信息必须结构化到「哪一项、期望什么、实际是什么」，供模型下一轮定向修复。
 */
class ToolArgsValidatorTest {

    private fun spec(vararg params: ToolParameter) = ToolSpec(
        name = "t",
        description = "test",
        parameters = params.toList(),
    )

    private fun violation(params: List<ArgsViolation>, name: String): ArgsViolation =
        params.first { it.param == name }

    // ── 放行方向 ─────────────────────────────────────────────────────────────

    @Test
    fun `无参数声明时任何输入都放行`() {
        assertEquals(emptyList(), ToolArgsValidator.validate(spec(), "{\"x\":1}"))
        assertEquals(emptyList(), ToolArgsValidator.validate(spec(), "完全不是 JSON"))
    }

    @Test
    fun `未声明的多余键一律放行`() {
        val s = spec(ToolParameter("path"))
        assertEquals(
            emptyList(),
            ToolArgsValidator.validate(s, "{\"path\":\"/a\",\"note\":\"说明\",\"extra\":123}"),
        )
    }

    @Test
    fun `可选参数缺失时静默放行`() {
        val s = spec(
            ToolParameter("required_arg"),
            ToolParameter("opt", required = false),
        )
        assertEquals(emptyList(), ToolArgsValidator.validate(s, "{\"required_arg\":\"v\"}"))
    }

    @Test
    fun `空串参数视同空对象`() {
        val s = spec(ToolParameter("path"))
        val violations = ToolArgsValidator.validate(s, "  ")
        assertEquals(1, violations.size)
        assertEquals("缺失", violations[0].got)
    }

    // ── 必填与 null ──────────────────────────────────────────────────────────

    @Test
    fun `必填缺失判违规`() {
        val s = spec(ToolParameter("path"))
        val violations = ToolArgsValidator.validate(s, "{}")
        assertEquals(1, violations.size)
        val v = violations[0]
        assertEquals("path", v.param)
        assertEquals("必填", v.expected)
        assertEquals("缺失", v.got)
    }

    @Test
    fun `显式 null 视同缺失`() {
        // 模型爱写 "path": null；required 判违规，语义与键不存在一致。
        val s = spec(ToolParameter("path"))
        val violations = ToolArgsValidator.validate(s, "{\"path\":null}")
        assertEquals(1, violations.size)
        assertEquals("缺失", violations[0].got)
    }

    // ── 类型校验 ─────────────────────────────────────────────────────────────

    @Test
    fun `STRING 拒绝数字`() {
        val s = spec(ToolParameter("name", ToolParamType.STRING))
        val v = violation(ToolArgsValidator.validate(s, "{\"name\":123}"), "name")
        assertEquals("string", v.expected)
        assertEquals("number", v.got)
    }

    @Test
    fun `STRING 接受字符串`() {
        val s = spec(ToolParameter("name", ToolParamType.STRING))
        assertEquals(emptyList(), ToolArgsValidator.validate(s, "{\"name\":\"hi\"}"))
    }

    @Test
    fun `NUMBER 接受整数与浮点`() {
        val s = spec(ToolParameter("n", ToolParamType.NUMBER))
        assertEquals(emptyList(), ToolArgsValidator.validate(s, "{\"n\":5}"))
        assertEquals(emptyList(), ToolArgsValidator.validate(s, "{\"n\":1.5}"))
    }

    @Test
    fun `NUMBER 拒绝字符串形式的数字`() {
        val s = spec(ToolParameter("n", ToolParamType.NUMBER))
        val v = violation(ToolArgsValidator.validate(s, "{\"n\":\"5\"}"), "n")
        assertEquals("string", v.got)
    }

    @Test
    fun `INTEGER 拒绝浮点`() {
        val s = spec(ToolParameter("n", ToolParamType.INTEGER))
        val v = violation(ToolArgsValidator.validate(s, "{\"n\":5.5}"), "n")
        assertEquals("integer", v.expected)
        assertEquals("number", v.got)
    }

    @Test
    fun `INTEGER 接受整数`() {
        val s = spec(ToolParameter("n", ToolParamType.INTEGER))
        assertEquals(emptyList(), ToolArgsValidator.validate(s, "{\"n\":42}"))
    }

    @Test
    fun `BOOLEAN 只接受 true 与 false`() {
        val s = spec(ToolParameter("flag", ToolParamType.BOOLEAN))
        assertEquals(emptyList(), ToolArgsValidator.validate(s, "{\"flag\":true}"))
        assertEquals(emptyList(), ToolArgsValidator.validate(s, "{\"flag\":false}"))
        val v = violation(ToolArgsValidator.validate(s, "{\"flag\":\"true\"}"), "flag")
        assertEquals("boolean", v.expected)
        // 字符串原始值的 describe 是 "string"（类型描述），不是原文
        assertEquals("string", v.got)
    }

    @Test
    fun `ARRAY 与 OBJECT 互斥`() {
        val arrSpec = spec(ToolParameter("items", ToolParamType.ARRAY))
        assertEquals(emptyList(), ToolArgsValidator.validate(arrSpec, "{\"items\":[1,2]}"))
        assertEquals("object", violation(ToolArgsValidator.validate(arrSpec, "{\"items\":{}}"), "items").got)

        val objSpec = spec(ToolParameter("cfg", ToolParamType.OBJECT))
        assertEquals(emptyList(), ToolArgsValidator.validate(objSpec, "{\"cfg\":{\"k\":1}}"))
        assertEquals("array", violation(ToolArgsValidator.validate(objSpec, "{\"cfg\":[]}"), "cfg").got)
    }

    // ── 枚举 ─────────────────────────────────────────────────────────────────

    @Test
    fun `枚举值越界判违规`() {
        val s = spec(ToolParameter("mode", ToolParamType.STRING, enumValues = listOf("a", "b")))
        val v = violation(ToolArgsValidator.validate(s, "{\"mode\":\"c\"}"), "mode")
        assertEquals("one of [a, b]", v.expected)
        assertEquals("\"c\"", v.got)
    }

    @Test
    fun `枚举值在集合内放行`() {
        val s = spec(ToolParameter("mode", ToolParamType.STRING, enumValues = listOf("a", "b")))
        assertEquals(emptyList(), ToolArgsValidator.validate(s, "{\"mode\":\"a\"}"))
    }

    // ── 顶层结构问题 ─────────────────────────────────────────────────────────

    @Test
    fun `畸形 JSON 返回单条顶层违规`() {
        val s = spec(ToolParameter("path"))
        val violations = ToolArgsValidator.validate(s, "{不完整的json")
        assertEquals(1, violations.size)
        assertEquals("", violations[0].param)
        assertEquals("一个 JSON 对象", violations[0].expected)
        assertTrue(violations[0].got.startsWith("无法解析的 JSON"))
    }

    @Test
    fun `根不是 JSON 对象时返回顶层违规`() {
        val s = spec(ToolParameter("path"))
        val violations = ToolArgsValidator.validate(s, "[1,2,3]")
        assertEquals(1, violations.size)
        assertEquals("", violations[0].param)
        assertEquals("array", violations[0].got)
    }

    // ── 面向模型的渲染 ───────────────────────────────────────────────────────

    @Test
    fun `describe 带参数名时逐条列差异`() {
        val v = ArgsViolation("path", "string", "number")
        assertEquals("参数「path」期望 string，实际 number", v.describe())
    }

    @Test
    fun `describe 顶层问题时省略参数名`() {
        val v = ArgsViolation("", "一个 JSON 对象", "array")
        assertEquals("期望 一个 JSON 对象，实际 array", v.describe())
    }

    @Test
    fun `renderForModel 包含工具名 项数与修复指引`() {
        val rendered = ToolArgsValidator.renderForModel(
            "file_write",
            listOf(ArgsViolation("path", "string", "number")),
        )
        assertTrue(rendered.contains("工具 file_write 参数校验失败（1 项）"))
        assertTrue(rendered.contains("1. 参数「path」期望 string，实际 number"))
        assertTrue(rendered.contains("重新调用同一工具"))
    }
}
