package com.rickeal.agent.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 文本协议三态解析（[TextToolProtocol]）真值表。
 *
 * 锁住的核心契约是「宁可少执行一次工具，也不能进死循环」：
 * - 三态判定：可执行 [ProtocolResult.Calls] / 形状对但不可执行 → [ProtocolResult.FinalAnswer]（永不重试）
 *   / 无协议形状 → [ProtocolResult.NoProtocol]；
 * - 名字不在白名单 = rejected = FinalAnswer，且携带**原文**（用户可能就是要这段 JSON）；
 * - rejected 优先于 accepted：混合块整段按最终答案处理，绝不部分执行。
 */
class TextToolProtocolTest {

    private val registered = setOf("calc", "time")

    // ── NoProtocol：没有协议形状 ─────────────────────────────────────────────

    @Test
    fun `空白文本返回 NoProtocol`() {
        assertEquals(ProtocolResult.NoProtocol, TextToolProtocol.parse("", registered))
        assertEquals(ProtocolResult.NoProtocol, TextToolProtocol.parse("   \n\t ", registered))
    }

    @Test
    fun `普通文本没有 JSON 返回 NoProtocol`() {
        val result = TextToolProtocol.parse("今天天气不错，我们出去走走吧。", registered)
        assertEquals(ProtocolResult.NoProtocol, result)
    }

    @Test
    fun `普通 JSON 不带工具名键时按普通回复处理`() {
        // 模型把用户要求的普通 JSON 直接吐出来，但没有 tool/name/function 任一键
        // → 不是协议形状 → NoProtocol，上层按普通回复展示。
        val text = "查询结果如下：{\"result\": 42, \"note\": \"hello\"}"
        assertEquals(ProtocolResult.NoProtocol, TextToolProtocol.parse(text, registered))
    }

    @Test
    fun `name 字段不是字符串时不算协议形状`() {
        // "name": 123 —— 工具名必须是字符串，数字键不构成调用形状。
        assertEquals(
            ProtocolResult.NoProtocol,
            TextToolProtocol.parse("{\"name\": 123}", registered),
        )
    }

    @Test
    fun `工具名为空白字符串时不算协议形状`() {
        assertEquals(
            ProtocolResult.NoProtocol,
            TextToolProtocol.parse("{\"name\": \"\"}", registered),
        )
    }

    // ── Calls：可执行的工具调用 ──────────────────────────────────────────────

    @Test
    fun `json 围栏内标准调用被解析为 Calls`() {
        val text = "```json\n{\"tool\": \"calc\", \"arguments\": {\"x\": 1}}```"
        val result = TextToolProtocol.parse(text, registered)
        val calls = (result as ProtocolResult.Calls).calls
        assertEquals(1, calls.size)
        assertEquals("calc", calls[0].name)
        assertEquals("{\"x\":1}", calls[0].argumentsJson)
    }

    @Test
    fun `tool_call 标签包裹的调用被解析为 Calls`() {
        val text = "<tool_call>{\"tool\": \"time\", \"arguments\": {\"tz\": \"Asia/Shanghai\"}}</tool_call>"
        val result = TextToolProtocol.parse(text, registered)
        val calls = (result as ProtocolResult.Calls).calls
        assertEquals(1, calls.size)
        assertEquals("time", calls[0].name)
        assertEquals("{\"tz\":\"Asia/Shanghai\"}", calls[0].argumentsJson)
    }

    @Test
    fun `裸 JSON 调用被解析为 Calls`() {
        val text = "好的，我来计算 {\"tool\": \"calc\"} 请稍等"
        val result = TextToolProtocol.parse(text, registered)
        val calls = (result as ProtocolResult.Calls).calls
        assertEquals(1, calls.size)
        assertEquals("calc", calls[0].name)
        // arguments 缺省归一化为 "{}"
        assertEquals("{}", calls[0].argumentsJson)
    }

    @Test
    fun `数组载荷解析出多个调用且 id 互不相同`() {
        val text = "```json\n[{\"tool\": \"calc\"}, {\"tool\": \"time\"}]```"
        val result = TextToolProtocol.parse(text, registered)
        val calls = (result as ProtocolResult.Calls).calls
        assertEquals(2, calls.size)
        assertEquals(listOf("calc", "time"), calls.map { it.name })
        assertEquals(2, calls.map { it.id }.toSet().size)
    }

    @Test
    fun `数组中非对象元素被跳过`() {
        val text = "```json\n[1, {\"tool\": \"calc\"}]```"
        val result = TextToolProtocol.parse(text, registered)
        val calls = (result as ProtocolResult.Calls).calls
        assertEquals(1, calls.size)
        assertEquals("calc", calls[0].name)
    }

    @Test
    fun `parameters 是 arguments 的合法别名`() {
        val text = "```json\n{\"tool\": \"calc\", \"parameters\": {\"y\": 2}}```"
        val result = TextToolProtocol.parse(text, registered)
        val calls = (result as ProtocolResult.Calls).calls
        assertEquals("{\"y\":2}", calls[0].argumentsJson)
    }

    @Test
    fun `双重编码的字符串参数被解包`() {
        // 4B 模型常见：arguments 给的是一段 JSON 字符串而不是对象。
        val text = "```json\n{\"tool\": \"calc\", \"arguments\": \"{\\\"x\\\":1}\"}```"
        val result = TextToolProtocol.parse(text, registered)
        val calls = (result as ProtocolResult.Calls).calls
        assertEquals("{\"x\":1}", calls[0].argumentsJson)
    }

    @Test
    fun `args 与 input 同为 arguments 的合法别名`() {
        // 参数键的叫法在不同模型间五花八门（arguments / parameters / args / input），
        // 别名是「宁可放过」侧的兼容面 —— 少一个别名就是一次无谓的 Schema 违规。
        val byArgs = (TextToolProtocol.parse(
            "```json\n{\"tool\": \"calc\", \"args\": {\"x\": 1}}```", registered,
        ) as ProtocolResult.Calls).calls
        assertEquals("{\"x\":1}", byArgs[0].argumentsJson)
        val byInput = (TextToolProtocol.parse(
            "```json\n{\"tool\": \"calc\", \"input\": {\"y\": 2}}```", registered,
        ) as ProtocolResult.Calls).calls
        assertEquals("{\"y\":2}", byInput[0].argumentsJson)
    }

    @Test
    fun `function 是 tool 名的合法别名`() {
        // OpenAI 系形态用 "function" 当工具名键；不认就会把一次正常调用降级成 FinalAnswer。
        val calls = (TextToolProtocol.parse(
            "```json\n{\"function\": \"time\"}```", registered,
        ) as ProtocolResult.Calls).calls
        assertEquals("time", calls[0].name)
        assertEquals("{}", calls[0].argumentsJson)
    }

    @Test
    fun `arguments 为数组时合法通过`() {
        val calls = (TextToolProtocol.parse(
            "```json\n{\"tool\": \"calc\", \"arguments\": [1, 2]}```", registered,
        ) as ProtocolResult.Calls).calls
        assertEquals("[1,2]", calls[0].argumentsJson)
    }

    @Test
    fun `arguments 为空串时归一化为空对象`() {
        val calls = (TextToolProtocol.parse(
            "```json\n{\"tool\": \"calc\", \"arguments\": \"\"}```", registered,
        ) as ProtocolResult.Calls).calls
        assertEquals("{}", calls[0].argumentsJson)
    }

    @Test
    fun `数组内含未知工具时整块不执行 —— 数组内同样不部分执行`() {
        // 与「多块混合」同一纪律：数组里有一个未知工具 → 整段按最终答案，
        // 绝不执行数组里另一个已知工具（否则下一轮模型原样重复输出，仍然绕圈）。
        val text = "```json\n[{\"tool\": \"calc\"}, {\"tool\": \"ghost\"}]```"
        assertEquals(ProtocolResult.FinalAnswer(text), TextToolProtocol.parse(text, registered))
    }

    // ── FinalAnswer：形状对但不可执行（永不重试） ────────────────────────────

    @Test
    fun `工具名不在白名单时降级为 FinalAnswer 且携带原文`() {
        // {"name": "张三"} 是文档里的经典误伤案例：天然带 name 字段，形状像调用
        // 但「张三」不是注册工具 → 必须按最终答案收尾，绝不能重试解析。
        val text = "{\"name\": \"张三\"}"
        val result = TextToolProtocol.parse(text, registered)
        assertEquals(ProtocolResult.FinalAnswer(text), result)
    }

    @Test
    fun `围栏内未知工具名降级为 FinalAnswer`() {
        val text = "```json\n{\"tool\": \"ghost\", \"arguments\": {\"a\": 1}}```"
        val result = TextToolProtocol.parse(text, registered)
        // 携带的是原文而不是剥离后的文本
        assertEquals(ProtocolResult.FinalAnswer(text), result)
    }

    @Test
    fun `参数字符串不是合法 JSON 时降级为 FinalAnswer`() {
        val text = "```json\n{\"tool\": \"calc\", \"arguments\": \"不是JSON\"}```"
        assertEquals(ProtocolResult.FinalAnswer(text), TextToolProtocol.parse(text, registered))
    }

    @Test
    fun `rejected 优先于 accepted 整段按最终答案处理`() {
        // 一个合法块 + 一个未知工具块：绝不部分执行，否则模型下一轮重复输出同样的块。
        val text = "```json\n{\"tool\": \"calc\"}```\n中间文本\n```json\n{\"tool\": \"ghost\"}```"
        assertEquals(ProtocolResult.FinalAnswer(text), TextToolProtocol.parse(text, registered))
    }

    // ── 畸形输入 ─────────────────────────────────────────────────────────────

    @Test
    fun `围栏内畸形 JSON 按普通回复处理`() {
        // 非 lenient 解析：无引号键解析失败 → 块被跳过；裸 JSON 提取同样解析失败
        // → NoProtocol，上层按普通回复展示原文。
        val text = "```json\n{tool: calc}```"
        assertEquals(ProtocolResult.NoProtocol, TextToolProtocol.parse(text, registered))
    }

    @Test
    fun `裸 JSON 畸形时按普通回复处理`() {
        val text = "看看这个 {不完整的json"
        assertEquals(ProtocolResult.NoProtocol, TextToolProtocol.parse(text, registered))
    }

    // ── strip：展示文本剥离 ──────────────────────────────────────────────────

    @Test
    fun `整段都是协议块时剥离为空串`() {
        // 旧实现无条件删所有围栏 → 剥完提交空气泡；新实现只删协议块，
        // 且「整段就是协议」剥完是空串，由上层兜底。
        val text = "```json\n{\"tool\": \"calc\", \"arguments\": {\"x\": 1}}```"
        assertEquals("", TextToolProtocol.strip(text))
    }

    @Test
    fun `普通代码块原样保留`() {
        val text = "说明如下\n```python\nprint(1)\n```\n完"
        assertEquals(text, TextToolProtocol.strip(text))
    }

    @Test
    fun `不带工具名键的 json 围栏原样保留`() {
        // 用户真正想要的 JSON 片段（没有 tool/name/function 字符串键）不是协议块。
        val text = "```json\n{\"result\": 42}\n```"
        assertEquals(text, TextToolProtocol.strip(text))
    }

    @Test
    fun `tool_call 标签从展示文本剥离`() {
        val text = "好的，马上执行<tool_call>{\"tool\": \"calc\"}</tool_call>"
        assertEquals("好的，马上执行", TextToolProtocol.strip(text))
    }

    @Test
    fun `空串 strip 原样返回`() {
        assertEquals("", TextToolProtocol.strip(""))
    }

    // ── 不变量 ───────────────────────────────────────────────────────────────

    @Test
    fun `同一文本对 registered 为空集时永不产生 Calls`() {
        // 白名单为空 = 没有任何工具可执行，任何形状都只能降级为最终答案。
        val text = "```json\n{\"tool\": \"calc\"}```"
        assertEquals(ProtocolResult.FinalAnswer(text), TextToolProtocol.parse(text, emptySet()))
    }
}
