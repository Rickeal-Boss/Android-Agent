package com.rickeal.agent.core.engine.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * preface 第三态闸门的纯函数单测（Wave 33）。
 *
 * 为什么值得写：`prefaceContainsSystem` 是「角色通道第三态」（createConversation 成功
 * 但 chat template 渲染丢失 systemInstruction，Gemma 系模板原生无 system role）的唯一
 * 代码侧判定点 —— 判真了走中档回退（关会话重建 + 系统提示词并入首条 USER），判错了
 * 要么白丢一次会话重建（误报），要么系统提示词根本没进上下文（漏报，等于复活 P0）。
 * 纯函数、零 native：不构造引擎，只验证归一化与窗口子串语义本身。
 */
class PrefaceGateTest {

    // ── normalizeForPrefaceCheck ─────────────────────────────────────────

    @Test
    fun `归一化保留字母数字并转小写`() {
        assertEquals("helloworld123", normalizeForPrefaceCheck("Hello, World! 123"))
    }

    @Test
    fun `归一化吃掉换行与空白噪声`() {
        assertEquals("abc", normalizeForPrefaceCheck("a\nb\r\n\tc  "))
    }

    @Test
    fun `归一化保留中文等 Unicode 字母`() {
        // isLetterOrDigit 对 CJK 为 true：中文提示词的归一化串 = 原文去标点。
        assertEquals("你是助理", normalizeForPrefaceCheck("你是，助理。"))
    }

    @Test
    fun `归一化空串与纯符号串得空串`() {
        assertEquals("", normalizeForPrefaceCheck(""))
        assertEquals("", normalizeForPrefaceCheck("!?.,\n"))
    }

    // ── prefaceContainsSystem 正例 ───────────────────────────────────────

    @Test
    fun `正例_preface 原样包含系统提示词`() {
        val system = "You are a helpful assistant that never repeats prompts."
        assertTrue(prefaceContainsSystem(preface = "start$system\nend", systemText = system))
    }

    @Test
    fun `正例_模板插入了标点换行噪声仍命中`() {
        // native chat template 可能在提示词前后/中间插入自己的标记：归一化必须吃掉
        // 这些格式噪声，只比对字母数字流。
        val system = "You are a helpful assistant."
        val preface = "### Instruction:\nYou  are -- a helpful,\nassistant!\n\nUser: hi"
        assertTrue(prefaceContainsSystem(preface, system))
    }

    @Test
    fun `正例_大小写差异不误报`() {
        val system = "You Are A HELPFUL Assistant."
        assertTrue(prefaceContainsSystem(preface = "you are a helpful assistant.", systemText = system))
    }

    @Test
    fun `正例_systemText 归一化后不足窗口长度时全串匹配`() {
        // 窗口语义：systemText 归一化后 < 64 字符 → 窗口退化为全串，必须整段出现
        // 在 preface 里（这段 system 只有 ~30 个归一化字符）。
        val system = "You are helpful."
        assertTrue(prefaceContainsSystem(preface = "prefix $system suffix", systemText = system))
        // 全串差一个字符都不行（截断版 ≠ 窗口串）。
        assertFalse(
            prefaceContainsSystem(preface = "prefix you are helpfu suffix", systemText = system),
            "systemText 短于窗口时按全串匹配：缺尾字符即判 false",
        )
    }

    @Test
    fun `正例_无系统提示词时恒真`() {
        // systemText 归一化为空 = 没有可校验的东西，闸门不该触发回退。
        assertTrue(prefaceContainsSystem(preface = "", systemText = ""))
        assertTrue(prefaceContainsSystem(preface = "anything", systemText = "！？。"))
    }

    // ── prefaceContainsSystem 负例 ───────────────────────────────────────

    @Test
    fun `负例_preface 完全不含系统提示词`() {
        val system = "You are a helpful assistant that never repeats prompts and always answers directly."
        assertFalse(prefaceContainsSystem(preface = "<start><bos>User: hi", systemText = system))
    }

    @Test
    fun `负例_系统提示词被截断到窗口内则不命中`() {
        // 窗口语义（与生产判据严格同读）：systemText 归一化后取**前 64 字符**做窗口；
        // 模板只把前 40 个归一化字符渲染进了 preface（典型的渲染截断/错位），此时
        // 窗口（64 字符）不可能是 preface 的子串 → 必须**判 false**（命中第三态，
        // 触发中档回退），而不是因为「有一部分像」就放过。
        val system =
            "You are a helpful assistant that never repeats prompts and always answers directly."
        val normalized = normalizeForPrefaceCheck(system)
        assertTrue(normalized.length > 64, "前置：fixture 的归一化长度必须超过窗口 64")
        // preface 只含有归一化前 40 个字符的渲染结果。
        val truncatedPrefix40 = normalized.take(40)
        assertFalse(
            prefaceContainsSystem(preface = truncatedPrefix40, systemText = system),
            "preface 仅含前 40 个归一化字符（< 窗口 64）→ 不足以确认 system 完整渲染，判 false",
        )
        // 对照组：同样的 preface 补足到窗口长度（前 64 个归一化字符完整出现）→ 真。
        val window = normalized.take(64)
        assertTrue(
            prefaceContainsSystem(preface = window, systemText = system),
            "前 64 个归一化字符完整出现（== 窗口串）→ 判 true",
        )
    }

    @Test
    fun `负例_只有零散片段拼不出连续窗口`() {
        // 模型模板把提示词拆散重排（只保留零散短片段）：逐字连续匹配必须失败。
        val system =
            "You are a helpful assistant that never repeats prompts and always answers directly."
        assertFalse(
            prefaceContainsSystem(
                preface = "You are direct. helpful assistant always answers.",
                systemText = system,
            ),
        )
    }
}
