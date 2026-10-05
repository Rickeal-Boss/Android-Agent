package com.rickeal.agent.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ModelHealthCriteria] 的 JVM 纯函数单测（Wave 44 P0-1）。
 *
 * 覆盖：判据 A / A′ / B1 / B2 / C 的正例与误伤负例、口径一致性断言（阈值与
 * [StreamRepetitionDetector] 常量同源）、聚合档位、以及「evaluate 包裹 detector 不改变结论」的冒烟。
 *
 * ⚠️ JUnit4 纪律：所有 `@Test` 方法必须以返回 **void** 的断言收尾
 * （`assertEquals` / `assertTrue` / `assertFalse` / `assertNull`）；
 * **不得**以 `assertNotNull` / `assertIs` / `assertFailsWith` 收尾（它们有返回值 ⇒ 整类
 * `initializationError`，一个都不跑，Gradle 只报「N completed, 1 failed」假象）。
 */
class ModelHealthCriteriaTest {

    // ────────────────────────────────── 判据 A ──────────────────────────────────

    @Test
    fun `判据A保留token命中`() {
        assertEquals(listOf("<unused1556>"), ModelHealthCriteria.reservedTokenHits("前文<unused1556>后文"))
        assertEquals(listOf("<unused 4347>"), ModelHealthCriteria.reservedTokenHits("<unused 4347>"))
        assertEquals(listOf("<UNUSED12>"), ModelHealthCriteria.reservedTokenHits("<UNUSED12>"))
    }

    @Test
    fun `判据A无数字与普通标签不命中`() {
        assertEquals(emptyList(), ModelHealthCriteria.reservedTokenHits("<unused>"))
        assertEquals(emptyList(), ModelHealthCriteria.reservedTokenHits("<b>粗体</b>"))
    }

    // ────────────────────────────────── 判据 A′ ──────────────────────────────────

    @Test
    fun `判据A_prime白名单通道与无标记不命中`() {
        assertEquals(emptyList(), ModelHealthCriteria.nonWhitelistChannelHits("<|channel>thought 一些内容"))
        assertEquals(emptyList(), ModelHealthCriteria.nonWhitelistChannelHits("完全没有标记的普通文本"))
    }

    @Test
    fun `判据A_prime非白名单天城文通道命中`() {
        assertEquals(listOf("तरह"), ModelHealthCriteria.nonWhitelistChannelHits("<|channel>तरह"))
    }

    @Test
    fun `判据A_prime尾部标点不污染白名单判定`() {
        assertEquals(emptyList(), ModelHealthCriteria.nonWhitelistChannelHits("<|channel>thought。"))
    }

    // ────────────────────────────────── 判据 B1 ──────────────────────────────────

    @Test
    fun `判据B1单字符run阈值与detector常量一致`() {
        val threshold = StreamRepetitionDetector.CHAR_RUN_LOOP
        assertTrue(ModelHealthCriteria.singleCharRunHit("`".repeat(threshold)))
        assertFalse(ModelHealthCriteria.singleCharRunHit("`".repeat(threshold - 1)))
    }

    @Test
    fun `判据B1空白不计入`() {
        // 40 个空格（缩进）合法，不命中
        assertFalse(ModelHealthCriteria.singleCharRunHit(" ".repeat(40)))
        // 12 + 12 个反引号被一个空格打断，任一 run 都不足阈值
        assertFalse(ModelHealthCriteria.singleCharRunHit("`".repeat(12) + " " + "`".repeat(12)))
    }

    // ────────────────────────────────── 判据 B2 ──────────────────────────────────

    @Test
    fun `判据B2双字符周期命中周期二`() {
        assertEquals(2, ModelHealthCriteria.charPeriodicRepeat("ab".repeat(24)))
    }

    @Test
    fun `判据B2三字符周期命中周期三`() {
        assertEquals(3, ModelHealthCriteria.charPeriodicRepeat("abc".repeat(16)))
    }

    @Test
    fun `判据B2周期二十四需九十六字符`() {
        val block = "0123456789abcdefghijklmn" // 24 个互不相同的字符
        assertEquals(24, ModelHealthCriteria.charPeriodicRepeat(block.repeat(4))) // 96 字符
    }

    @Test
    fun `判据B2尾部窗口不足不命中`() {
        val block = "0123456789abcdefghijklmn"
        // p=24 需覆盖 96 字符窗口；95 字符时窗口不足 ⇒ 不命中该周期
        assertFalse(ModelHealthCriteria.isPeriodicTail(block.repeat(4).dropLast(1), 24, 96))
        assertTrue(ModelHealthCriteria.isPeriodicTail(block.repeat(4), 24, 96))
    }

    @Test
    fun `判据B2周期大于窗口显式返回假`() {
        // Wave 44 审查 P3-2 回归：p > window 时循环区间为空 ⇒ 旧实现真空为真；现须显式 false
        assertFalse(ModelHealthCriteria.isPeriodicTail("abc", 10, 3))
    }

    @Test
    fun `判据B2正常文本不命中`() {
        val normal = "这是一个完全正常的模型回答，内容自然流畅没有任何退化迹象，" +
            "句子之间没有严格的周期性重复结构，因此多字符周期判据不应命中。"
        assertNull(ModelHealthCriteria.charPeriodicRepeat(normal))
    }

    @Test
    fun `判据B2周期下界为二不返回周期一`() {
        assertEquals(2, ModelHealthCriteria.PERIOD_MIN)
        // 单字符 run 的数学周期也是 2（B2 从不返回 1）；p=1 是 B1 的辖区
        assertFalse(ModelHealthCriteria.charPeriodicRepeat("a".repeat(60)) == 1)
    }

    // ────────────────────────────────── 判据 C ──────────────────────────────────

    @Test
    fun `判据C空输出命中`() {
        assertTrue(ModelHealthCriteria.isEffectivelyEmpty(""))
        assertTrue(ModelHealthCriteria.isEffectivelyEmpty("   "))
        assertTrue(ModelHealthCriteria.isEffectivelyEmpty("。。。！？"))
    }

    @Test
    fun `判据C有字母或数字不命中`() {
        assertFalse(ModelHealthCriteria.isEffectivelyEmpty("好"))
        assertFalse(ModelHealthCriteria.isEffectivelyEmpty("a"))
        assertFalse(ModelHealthCriteria.isEffectivelyEmpty("1"))
    }

    // ─────────────────────────────── 口径一致性（关键）───────────────────────────────

    @Test
    fun `口径一致性B2阈值与detector常量同源`() {
        assertEquals(StreamRepetitionDetector.CHAR_RUN_LOOP, ModelHealthCriteria.PERIOD_MAX)
        assertEquals(StreamRepetitionDetector.LOOP_STREAK, ModelHealthCriteria.PERIOD_MIN_CYCLES)
        assertEquals(2 * StreamRepetitionDetector.CHAR_RUN_LOOP, ModelHealthCriteria.PERIOD_MIN_COVERED)
        assertEquals(ModelHealthCriteria.PERIOD_MAX, ModelHealthCriteria.PERIOD_MIN_COVERED / 2)
    }

    // ────────────────────────────────── 聚合档位 ──────────────────────────────────

    @Test
    fun `聚合硬判据命中判坏`() {
        val hits = listOf(CriterionHit("x", CriterionSeverity.HARD, ""))
        assertEquals(ModelHealthVerdict.BAD, ModelHealthCriteria.verdictOf(hits))
    }

    @Test
    fun `聚合仅软判据判降级`() {
        val hits = listOf(CriterionHit("x", CriterionSeverity.SOFT, ""))
        assertEquals(ModelHealthVerdict.DEGRADED, ModelHealthCriteria.verdictOf(hits))
    }

    @Test
    fun `聚合无命中判通过`() {
        assertEquals(ModelHealthVerdict.PASS, ModelHealthCriteria.verdictOf(emptyList()))
    }

    @Test
    fun `聚合软硬混合判坏`() {
        val hits = listOf(
            CriterionHit("a", CriterionSeverity.SOFT, ""),
            CriterionHit("b", CriterionSeverity.HARD, ""),
        )
        assertEquals(ModelHealthVerdict.BAD, ModelHealthCriteria.verdictOf(hits))
    }

    // ─────────────────────── 回归：evaluate 包裹 detector 不改变结论 ───────────────────────

    @Test
    fun `回归短签名连击经evaluate判坏`() {
        val hits = ModelHealthCriteria.evaluate("、\n".repeat(6))
        assertEquals(ModelHealthVerdict.BAD, ModelHealthCriteria.verdictOf(hits))
        assertTrue(hits.any { it.id == ModelHealthCriteria.ID_DETECTOR_LOOP })
    }

    @Test
    fun `回归保留token经evaluate判坏`() {
        val hits = ModelHealthCriteria.evaluate("模型说<unused1556>然后继续")
        assertEquals(ModelHealthVerdict.BAD, ModelHealthCriteria.verdictOf(hits))
        assertTrue(hits.any { it.id == ModelHealthCriteria.ID_RESERVED_TOKEN })
    }

    @Test
    fun `回归正常短文本经evaluate判通过`() {
        val hits = ModelHealthCriteria.evaluate("今天天气很好，我们去公园散步。")
        assertEquals(ModelHealthVerdict.PASS, ModelHealthCriteria.verdictOf(hits))
    }

    // ──────────────────── 分通道（evaluateSplit，Wave 49 E1）────────────────────

    @Test
    fun `回归evaluate等价于空思考的分通道`() {
        val text = "今天天气很好，我们去公园散步。"
        assertEquals(ModelHealthCriteria.evaluate(text), ModelHealthCriteria.evaluateSplit(text, ""))
    }

    @Test
    fun `分通道思考的周期复读不判坏`() {
        val thinking = "ab".repeat(30)
        // 分通道：思考里的周期复读不进重复类判据（只判正文）⇒ 无 HARD。
        assertFalse(
            ModelHealthCriteria.evaluateSplit("这是一段正常的回答。", thinking)
                .any { it.severity == CriterionSeverity.HARD },
        )
        // 对照（旧口径）：两通道拼起来跑 evaluate ⇒ 思考的周期复读被误判 HARD（本波修的误报）。
        assertTrue(
            ModelHealthCriteria.evaluate("这是一段正常的回答。" + thinking)
                .any { it.severity == CriterionSeverity.HARD },
        )
    }

    @Test
    fun `分通道思考非空不判空输出`() {
        // 思考烧光预算、正文为空 —— 但模型确实产出了内容 ⇒ C（两通道合并）不命中。
        assertFalse(
            ModelHealthCriteria.evaluateSplit("", "让我想想这个问题的答案")
                .any { it.id == ModelHealthCriteria.ID_EMPTY_OUTPUT },
        )
        // 对照：单通道口径下正文为空 ⇒ C 命中（旧口径对思考型模型的误判来源）。
        assertTrue(
            ModelHealthCriteria.evaluate("")
                .any { it.id == ModelHealthCriteria.ID_EMPTY_OUTPUT },
        )
    }

    @Test
    fun `分通道思考通道的保留token仍命中`() {
        // 保留 token 是 logits 塌的确定性证据，思考通道同样有效 ⇒ 不能只判正文而漏检。
        assertTrue(
            ModelHealthCriteria.evaluateSplit("正常回答", "<unused1556>")
                .any { it.id == ModelHealthCriteria.ID_RESERVED_TOKEN },
        )
    }

    @Test
    fun `分通道通道标记只判正文`() {
        // 思考通道里的通道标记文本不进 A′（marker 是正文流的通道边界现象）。
        assertFalse(
            ModelHealthCriteria.evaluateSplit("正常回答", "<|channel>garbage")
                .any { it.id == ModelHealthCriteria.ID_CHANNEL_MARKER },
        )
        // 正文里的则命中（软判据）。
        assertTrue(
            ModelHealthCriteria.evaluateSplit("<|channel>garbage", "正常思考")
                .any { it.id == ModelHealthCriteria.ID_CHANNEL_MARKER },
        )
    }
}
