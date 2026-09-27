package com.rickeal.agent.core.agent.breaker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 工具调用振荡检测器（[ToolOscillationDetector]）行为锁定（Wave 30 §2.6）。
 *
 * 用例对齐方案 §2.6 的 6 例清单；「不检出」侧重点钉住**合法重试窗口**：
 * 失败换参重试（distance=1 / 短序列）绝不能误杀，否则真机上的正常恢复路径被熔断。
 */
class ToolOscillationDetectorTest {

    private fun evaluate(vararg sigs: String): String? = ToolOscillationDetector.evaluate(sigs.toList())

    // ── 判据二：窗口塌缩 ─────────────────────────────────────────────────────

    @Test
    fun `A_B 交替第 3 个周期检出 —— 窗口 6 且 distinct 2`() {
        val evidence = evaluate("A", "B", "A", "B", "A", "B")
        assertTrue(evidence != null, "A/B 第 3 周期（6 次）应检出")
        assertTrue(evidence!!.contains("2 个选项"), evidence)
    }

    @Test
    fun `A_B 交替第 2 个周期不检出 —— 窗口未满 6`() {
        // 4 次 A/B：塌缩窗口不够，distance 判据因周期 2 < MIN_PERIOD 抓不住
        // —— 这是判据二存在的理由（短周期形态）。
        assertNull(evaluate("A", "B", "A", "B"))
    }

    @Test
    fun `A_B 交替第 5 次仍不检出 —— 塌缩门槛严格落在第 6 次`() {
        // 第 5 次：窗口 5 < 6，distance 判据仍因周期 2 抓不住。
        assertNull(evaluate("A", "B", "A", "B", "A"))
    }

    @Test
    fun `同参连发 6 次不检出 —— 归旧同参硬护栏，不抢归因`() {
        // 旧护栏在第 3 次起「忽略不执行」，而忽略的调用同样进历史（追加在判定之前），
        // 所以历史里确实有 6 条同签名；但簇状序列不是「来回打转」，判据二的真交替
        // 闸门把它挡在门外 —— 归因留在同参重复，不写成振荡。
        assertNull(evaluate("A", "A", "A", "A", "A", "A"))
    }

    @Test
    fun `容量 48 满载不使判据失效 —— 全历史 distinct 2 仍检出`() {
        val seq = MutableList(ToolOscillationDetector.HISTORY_CAPACITY) { i ->
            if (i % 2 == 0) "A" else "B"
        }
        assertTrue(evaluate(*seq.toTypedArray()) != null, "容量满载后 A/B 仍应检出")
    }

    @Test
    fun `混合历史后进入 A_B 死循环仍检出 —— 判据二是滑动窗口，只看末尾 6 项`() {
        // 全量历史 distinct=5（C/D/E/A/B），但滑动窗口只看末尾 6 项 A/B 交替 ——
        // 这是本判据存在的理由：真机上本 run 早跑过别的工具是常态，取全量 distinct
        // 会让 A/B 死循环永远抓不到（复审 P1-1 的修后锁定）。
        val evidence = evaluate("C", "D", "E", "A", "B", "A", "B", "A", "B", "A", "B")
        assertTrue(evidence != null, "混合历史后的 A/B 死循环必须检出")
        assertTrue(evidence!!.contains("2 个选项"), evidence)
    }

    // ── 判据一：周期距离 ─────────────────────────────────────────────────────

    @Test
    fun `A_B_C 循环检出 —— 连续 3 步等距回环`() {
        val evidence = evaluate("A", "B", "C", "A", "B", "C")
        assertTrue(evidence != null, "A/B/C/A/B/C 应被距离判据检出")
        assertTrue(evidence!!.contains("周期 3"), evidence)
    }

    @Test
    fun `A_B_C 循环第二轮仍然检出（7 个元素）`() {
        assertTrue(evaluate("A", "B", "C", "A", "B", "C", "A") != null)
    }

    @Test
    fun `等距但距离不等的历史不检出`() {
        // A/B/A/C/A/B/A/C 混合形态：末尾 3 步到各自上一同签名的距离不一致或过短。
        assertNull(evaluate("A", "B", "A", "C", "A", "B", "A", "C"))
    }

    @Test
    fun `周期距离小于 MIN_PERIOD 的短周期不检出`() {
        // A/A/A（distance=1）：同参死锁由既有硬护栏处置，不进距离判据。
        assertNull(evaluate("A", "A", "A"))
    }

    // ── 合法重试窗口（不检出侧）────────────────────────────────────────────

    @Test
    fun `file_read 失败换参重试 2 次不检出`() {
        // 换参重试 = 每次签名都不同：末尾位置找不到更早同签名 → 未检出。
        assertNull(evaluate("file_read:{a:1}", "file_read:{a:2}", "file_read:{a:3}"))
    }

    @Test
    fun `退回旧参数重试一次不检出（距离 2 小于 MIN_PERIOD）`() {
        assertNull(evaluate("file_read:{a:1}", "file_read:{a:2}", "file_read:{a:1}"))
    }

    @Test
    fun `5 工具正常序列不检出`() {
        assertNull(
            evaluate(
                "search_tools", "file_read:{p}", "file_write:{p}", "current_time", "memory_write",
            ),
        )
    }

    @Test
    fun `短于判据所需长度的历史一律不检出`() {
        assertNull(evaluate("A"))
        assertNull(evaluate("A", "B"))
        assertNull(evaluate("A", "B", "C"))
        // 判据一实际最小长度是 6（倒数第 3 个位置也要有更早同签名）：4/5 结构性不检出。
        assertNull(evaluate("A", "B", "C", "A"))
        assertNull(evaluate("A", "B", "C", "A", "B"))
    }

    @Test
    fun `长序列中单次回调旧工具不检出 —— 尾部未构成周期`() {
        assertNull(evaluate("A", "B", "C", "D", "E", "F", "A"))
    }

    @Test
    fun `参数各异的推进序列不检出 —— 周期判据比的是签名不是工具名`() {
        // 同名工具换参数 ⇒ 签名不同 ⇒ 末尾位置找不到更早同签名。
        assertNull(
            evaluate(
                "read:{\"p\":\"a\"}", "write:{\"p\":\"a\"}", "run",
                "read:{\"p\":\"b\"}", "write:{\"p\":\"b\"}", "run",
            ),
        )
    }

    @Test
    fun `换参重试簇不检出 —— 真交替闸门放行换参重来`() {
        // read{a} 连发后换参 read{b} 再连发：旧同参护栏在换参时把 streak 重置
        // （换参重来是既有设计放行的恢复路径，连续失败由 TOOL_FAILURE_STREAK_LIMIT
        // 维度处置）。窗口 distinct=2 但簇状非交替 ⇒ 判据二放行，不把「连续失败」
        // 归因成「在 2 个选项之间打转」（复审 P1-2 的修后锁定）。
        assertNull(
            evaluate(
                "read:{a}", "read:{a}", "read:{a}", "read:{b}", "read:{b}", "read:{b}",
            ),
        )
    }

    // ── evidence 可读性 ──────────────────────────────────────────────────────

    @Test
    fun `evidence 摘要裁剪参数串并保留工具名`() {
        val longArgs = "read:{\"p\":\"" + "长".repeat(60) + "\"}"
        // R/X/Y/R/X/Y：末尾 3 步等距 3，判据一检出。
        val evidence = evaluate(longArgs, "x", "y", longArgs, "x", "y")
        assertTrue(evidence!!.contains("read("), evidence)
        assertTrue(evidence.length < 400, "evidence 不应被长参数撑爆：${evidence.length}")
    }
}
