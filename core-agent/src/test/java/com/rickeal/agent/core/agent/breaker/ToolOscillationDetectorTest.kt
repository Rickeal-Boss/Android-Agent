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
