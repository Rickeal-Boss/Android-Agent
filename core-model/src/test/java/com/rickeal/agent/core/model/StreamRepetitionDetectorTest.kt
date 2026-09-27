package com.rickeal.agent.core.model

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [StreamRepetitionDetector] 的 JVM 纯函数单测：9 条判定的正例 / 误伤负例 / 边界。
 *
 * 全部确定性、零 Android 依赖、零 sleep、零随机 —— 只喂字符串、只断言 [Verdict]。
 * 重点覆盖 P0-A 新增的判定⑨「字符级滚动指纹」：正例（标点/换行被改写仍命中）与
 * 三类负例（复述不足 64 归一化字符 / 未传 systemPrompt / 正常文本不误伤）。
 *
 * 断言风格与 core-data 的 WallpaperSamplingTest / WallpaperFailureTest 一致
 * （kotlin.test 的 assertEquals / assertTrue / assertNull / fail）。
 */
class StreamRepetitionDetectorTest {

    // ------------------------------------------------------------------ 判定①～⑧

    /** 判定①：同一 ≥8 字长句连续 4 次 → 同句连击。 */
    @Test
    fun `判定①同句连击四次触发`() {
        val detector = StreamRepetitionDetector()
        val sentence = "这是一段足够长的重复句子。"
        var verdict: StreamRepetitionDetector.Verdict = StreamRepetitionDetector.Verdict.Ok
        repeat(4) { verdict = detector.observeText(sentence) }
        val loop = assertLoop(verdict, StreamRepetitionDetector.normalizedSignature(sentence)!!)
        assertEquals(false, loop.inThinking)
    }

    /** 判定②：两句 A/B 交替 6 轮 → 窗口塌缩（唯一签名数 ≤ 2 且窗口已满）。 */
    @Test
    fun `判定②窗口塌缩两句交替六轮触发`() {
        val detector = StreamRepetitionDetector()
        val text = "第一句内容足够长用来触发判定。第二句内容同样很长用于测试。".repeat(3)
        val loop = assertLoopAny(detector.observeText(text))
        assertEquals(false, loop.inThinking)
    }

    /** 判定③：thinking 累计字符超 2048 → 思考预算截断（inThinking = true）。 */
    @Test
    fun `判定③思考预算超限触发`() {
        val detector = StreamRepetitionDetector()
        val chunk = "abcdefghij".repeat(110) // 1100 字符，无句界
        assertOk(detector.observeThinking(chunk), "1100 字符未超预算")
        val loop = assertLoop(
            detector.observeThinking(chunk),
            StreamRepetitionDetector.THINKING_BUDGET_MARKER,
        )
        assertEquals(true, loop.inThinking)
    }

    /** 判定④：同一短签名（「、」）连续 6 句 → 短签名连击。 */
    @Test
    fun `判定④短签名连击顿号刷屏触发`() {
        val detector = StreamRepetitionDetector()
        val loop = assertLoop(
            detector.observeText("、\n".repeat(6)),
            StreamRepetitionDetector.SHORT_SIG_RUN_MARKER,
        )
        assertEquals(false, loop.inThinking)
    }

    /** 判定⑤：text 流无句界累计超 4096 字符 → 正文铺陈预算。 */
    @Test
    fun `判定⑤正文铺陈预算超限触发`() {
        val detector = StreamRepetitionDetector()
        assertOk(detector.observeText("abcdefghij".repeat(500)), "5000 字符单次喂入不立即判")
        val loop = assertLoop(
            detector.observeText("x"),
            StreamRepetitionDetector.TEXT_RUNAWAY_MARKER,
        )
        assertEquals(false, loop.inThinking)
    }

    /** 判定⑥：连续 2 句逐字复述系统提示词（标点/句界保持一致）→ 提示词回显。 */
    @Test
    fun `判定⑥提示词回显连续两句触发`() {
        val detector = StreamRepetitionDetector(systemPrompt = SYSTEM_PROMPT)
        val echo = "请仔细阅读以下指令并严格遵循所有要求。你必须始终使用中文回答用户的问题。"
        val loop = assertLoop(
            detector.observeText(echo),
            StreamRepetitionDetector.PROMPT_ECHO_MARKER,
        )
        assertEquals(false, loop.inThinking)
    }

    /** 判定⑦：三句块 A B C 以句距 3 复读（同句距连续 5 次）→ 周期块循环。 */
    @Test
    fun `判定⑦周期块循环触发`() {
        val detector = StreamRepetitionDetector()
        val text = "第一句循环内容足够长。第二句循环内容同样长。第三句循环内容也不短。".repeat(3)
        val loop = assertLoop(
            detector.observeText(text),
            StreamRepetitionDetector.BLOCK_CYCLE_MARKER,
        )
        assertEquals(false, loop.inThinking)
    }

    /** 判定⑧：同一非空白字符连续 24 个 → 单字符 run。 */
    @Test
    fun `判定⑧单字符run二十四个触发`() {
        val detector = StreamRepetitionDetector()
        assertLoop(detector.observeText("`".repeat(24)), StreamRepetitionDetector.CHAR_RUN_MARKER)
    }

    /** 判定⑧ 边界：23 个（差一个）不触发。 */
    @Test
    fun `判定⑧单字符run二十三个不触发`() {
        val detector = StreamRepetitionDetector()
        assertOk(detector.observeText("`".repeat(23)), "23 < CHAR_RUN_LOOP(24)")
    }

    // ------------------------------------------------------------------ 判定⑨（P0-A）

    /**
     * 判定⑨ 正例（与 ⑥ 的关键区别）：逐字复述系统提示词，但**故意把句号移位**
     * （前 3 句并成一句 + 换行 + 后 2 句并成一句）—— 句子边界与 ⑥ 的切分完全错位，
     * ⑥ 永不命中，⑨ 因只看连续字符流仍命中。
     */
    @Test
    fun `判定⑨标点换行改写后仍触发`() {
        val detector = StreamRepetitionDetector(systemPrompt = SYSTEM_PROMPT)
        val echo = "请仔细阅读以下指令并严格遵循所有要求你必须始终使用中文回答用户的问题" +
            "禁止输出任何与任务无关的内容\n" +
            "请始终以友好的语气与用户进行交流遇到不确定的信息应当如实告知用户。"
        val loop = assertLoop(
            detector.observeText(echo),
            StreamRepetitionDetector.PROMPT_ECHO_CHAR_MARKER,
        )
        assertEquals(false, loop.inThinking)
    }

    /** 判定⑨ 负例：只复述 32 个归一化字符（不足 3 连击）→ 不触发。 */
    @Test
    fun `判定⑨只复述三十二个归一化字符不触发`() {
        val detector = StreamRepetitionDetector(systemPrompt = SYSTEM_PROMPT)
        val prefix = SYSTEM_PROMPT.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }.take(32)
        assertOk(
            detector.observeText(prefix + "苹果香蕉橘子葡萄西瓜草莓桃子梨子柠檬"),
            "32 字符只够 1 次命中",
        )
    }

    /** 判定⑨ 负例：未传 systemPrompt（指纹集为空）→ 同一段回显文本不触发。 */
    @Test
    fun `判定⑨未传系统提示词时同文本不触发`() {
        val detector = StreamRepetitionDetector()
        val echo = "请仔细阅读以下指令并严格遵循所有要求你必须始终使用中文回答用户的问题" +
            "禁止输出任何与任务无关的内容\n" +
            "请始终以友好的语气与用户进行交流遇到不确定的信息应当如实告知用户。"
        assertOk(detector.observeText(echo), "无 systemPrompt ⇒ 判定⑨关闭")
    }

    // ------------------------------------------------------------------ 误伤负例

    /**
     * 负例：交替排比「- 优点 / - 缺点」5 组（句距 2）。
     * 标签 <8 字 ⇒ 原口径签名 null（判定②不参与），句距 2 < BLOCK_CYCLE_MIN_PERIOD(3)
     * ⇒ 判定⑦不成立。若去掉周期下限，本例会以句距 2 触发。
     */
    @Test
    fun `负例交替排比句距二不触发周期块循环`() {
        val detector = StreamRepetitionDetector()
        assertOk(detector.observeText("- 优点\n- 缺点\n".repeat(5)))
    }

    /**
     * 负例：真实 Markdown 文档含 10 条水平分隔线 `---`，但分隔线间距各不相同
     * （1..10 句），故「相同句距」不成立 ⇒ 判定⑦不触发；分隔线非连续 ⇒ 判定④不触发。
     */
    @Test
    fun `负例Markdown分隔线间距不一不触发`() {
        val detector = StreamRepetitionDetector(systemPrompt = SYSTEM_PROMPT)
        val sb = StringBuilder()
        var n = 0
        for (gap in 1..10) {
            repeat(gap) {
                n++
                sb.append("第").append(n).append("段说明文字内容各不相同。\n")
            }
            sb.append("---\n")
        }
        assertOk(detector.observeText(sb.toString()))
    }

    /** 负例：带几十个空格缩进的 60 行代码块 → 空白不计入 run，且各句签名互异。 */
    @Test
    fun `负例缩进代码块不触发`() {
        val detector = StreamRepetitionDetector(systemPrompt = SYSTEM_PROMPT)
        val indent = " ".repeat(32)
        val code = buildString {
            repeat(60) { i ->
                append(indent).append("val value").append(i).append(" = compute(").append(i).append(")\n")
            }
        }
        assertOk(detector.observeText(code))
    }

    /** 负例：12 句内容各异的中文段落 → 无连击 / 无窗口塌缩 / 无周期。 */
    @Test
    fun `负例十二句各异的中文段落不触发`() {
        val detector = StreamRepetitionDetector(systemPrompt = SYSTEM_PROMPT)
        val text = (1..12).joinToString("") { "第${it}个要点的内容彼此都不一样，需要分别说明。" }
        assertOk(detector.observeText(text))
    }

    /** 负例：正常口头语「好的。」「明白。」交替 10 次 → 短签名互异、句距 2 不计周期。 */
    @Test
    fun `负例正常口头语交替不触发`() {
        val detector = StreamRepetitionDetector(systemPrompt = SYSTEM_PROMPT)
        assertOk(detector.observeText("好的。明白。".repeat(10)))
    }

    // ------------------------------------------------------------------ 边界

    /** 边界：空 delta 直接返回 Ok（两条流）。 */
    @Test
    fun `边界空delta返回Ok`() {
        val detector = StreamRepetitionDetector(systemPrompt = SYSTEM_PROMPT)
        assertOk(detector.observeText(""))
        assertOk(detector.observeThinking(""))
    }

    /** 边界：reset() 清空先前的连击状态（同输入在 reset 后不立即触发）。 */
    @Test
    fun `边界reset清空连击状态`() {
        val detector = StreamRepetitionDetector()
        val sentence = "这是一段足够长的重复句子。"
        repeat(3) { assertOk(detector.observeText(sentence), "第 ${it + 1} 次不应触发") }
        detector.reset()
        // reset 前第 4 次会触发判定①；reset 后同一输入只算第 1 次。
        assertOk(detector.observeText(sentence))
    }

    /** 边界：原口径签名对 <8 字文本返回 null。 */
    @Test
    fun `边界归一化签名短文本返回null`() {
        assertNull(StreamRepetitionDetector.normalizedSignature("好的"))
        assertNull(StreamRepetitionDetector.normalizedSignature("完成。"))
        assertEquals(
            "这是一段足够长的句子",
            StreamRepetitionDetector.normalizedSignature("这是一段足够长的句子。"),
        )
    }

    /** 边界：第二口径短签名保留标点、零门槛；纯空白返回 null。 */
    @Test
    fun `边界短签名保留标点且无长度门槛`() {
        assertEquals("、", StreamRepetitionDetector.normalizedShortSignature("、"))
        assertEquals("当:", StreamRepetitionDetector.normalizedShortSignature("当:"))
        assertNull(StreamRepetitionDetector.normalizedShortSignature("  \n "))
    }

    /** 边界：text / thinking 两条流状态独立（只喂 thinking 不影响 text 侧计数）。 */
    @Test
    fun `边界text与thinking两条流状态独立`() {
        val detector = StreamRepetitionDetector()
        val sentence = "这是一段足够长的重复句子。"
        repeat(3) { detector.observeThinking(sentence) }
        // thinking 侧已连击 3 次；text 侧仍是全新状态，第 1 次不该触发。
        assertOk(detector.observeText(sentence))
    }

    /**
     * 边界（流式正确性关键属性）：同一段循环文本按 1 / 3 / 7 / 整段切碎喂入，
     * 判定① 的**触发字符位置**必须一致 —— 状态完全由已消费字符决定，与分块无关。
     */
    @Test
    fun `边界分块喂入与整段喂入触发点一致`() {
        val sentence = "这是一段足够长的重复句子。"
        val text = sentence.repeat(4)
        val triggerIndex = text.length - 1 // 第 4 个句号（0-based）触发判定①
        for (chunkSize in intArrayOf(1, 3, 7, text.length)) {
            assertTriggerAt(text, triggerIndex, chunkSize)
        }
    }

    /** 边界：归一化硬编码 [Locale.ROOT]，不受默认区域设置影响（土耳其语区 I 不分叉）。 */
    @Test
    fun `边界归一化使用ROOT区域设置`() {
        assertEquals("iiiiiiii", StreamRepetitionDetector.normalizedSignature("IIIIIIII"))
        assertEquals("titleistest", StreamRepetitionDetector.normalizedSignature("TITLE Is TEST"))
    }

    // ------------------------------------------------------------------ helpers

    private fun assertOk(verdict: StreamRepetitionDetector.Verdict, hint: String = "") {
        assertTrue(
            verdict is StreamRepetitionDetector.Verdict.Ok,
            "期望 Ok，实际 $verdict${if (hint.isEmpty()) "" else "（$hint）"}",
        )
    }

    private fun assertLoop(
        verdict: StreamRepetitionDetector.Verdict,
        expectedMarker: String,
    ): StreamRepetitionDetector.Verdict.LoopDetected {
        val loop = verdict as? StreamRepetitionDetector.Verdict.LoopDetected
            ?: fail("期望 LoopDetected($expectedMarker)，实际 $verdict")
        assertEquals(expectedMarker, loop.repeatedSignature)
        return loop
    }

    private fun assertLoopAny(
        verdict: StreamRepetitionDetector.Verdict,
    ): StreamRepetitionDetector.Verdict.LoopDetected =
        verdict as? StreamRepetitionDetector.Verdict.LoopDetected
            ?: fail("期望 LoopDetected，实际 $verdict")

    /**
     * 按 [chunkSize] 切碎喂入 [text]，断言首次 LoopDetected 出现在覆盖 [triggerIndex]
     * 的那个分块内 —— 即触发字符位置与分块方式无关。
     */
    private fun assertTriggerAt(text: String, triggerIndex: Int, chunkSize: Int) {
        val detector = StreamRepetitionDetector()
        var start = 0
        for (chunk in text.chunked(chunkSize)) {
            val verdict = detector.observeText(chunk)
            val end = start + chunk.length
            if (verdict is StreamRepetitionDetector.Verdict.LoopDetected) {
                assertTrue(
                    triggerIndex in start until end,
                    "chunkSize=$chunkSize 触发于 [$start, $end)，未覆盖触发字符 $triggerIndex",
                )
                return
            }
            start = end
        }
        fail("chunkSize=$chunkSize 未检测到循环")
    }

    private companion object {
        /**
         * 80 个归一化字符的系统提示词样本（5 句，每句 ≥8 字）。判定⑥ 按句建指纹、
         * 判定⑨ 按连续字符流建指纹，两者共用同一语料。
         */
        val SYSTEM_PROMPT: String =
            "请仔细阅读以下指令并严格遵循所有要求。" +
                "你必须始终使用中文回答用户的问题。" +
                "禁止输出任何与任务无关的内容。" +
                "请始终以友好的语气与用户进行交流。" +
                "遇到不确定的信息应当如实告知用户。"
    }
}
