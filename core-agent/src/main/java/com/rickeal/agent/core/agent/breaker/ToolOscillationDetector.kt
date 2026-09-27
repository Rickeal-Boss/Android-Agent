package com.rickeal.agent.core.agent.breaker

/**
 * 工具调用振荡检测器（Wave 30 §2.6，评审 §3.2(c) 判据收编）。
 *
 * 纯函数：输入 append 后的签名历史序列，输出 null（未检出）或 trip 证据文案；
 * AgentRunner 只调用不实现。单份实现，杜绝与 StreamRepetitionDetector 判定②⑦
 * 的口径分叉（normalizedSignature 注释的既有纪律）。
 *
 * 双判据：
 * - 判据一（周期距离）：从末尾起连续 [CYCLE_STREAK_LIMIT] 个位置，每个位置到其
 *   上一同签名位置的距离相同且 ≥ [MIN_PERIOD] → 检出（抓 A/B/C/A/B/C 这类
 *   距离判据才能抓住的展开周期形态）；
 * - 判据二（窗口塌缩）：窗口 ≥ [COLLAPSE_WINDOW] 且 distinct ≤ [COLLAPSE_DISTINCT]
 *   → 检出（抓 A/B 交替这种短周期形态 —— 判定② KDoc 的同款搬移；distance 判据
 *   因周期 2 < MIN_PERIOD 抓不住它）。
 *
 * 误杀面（评审 §3.5 已接受）：合法的 A/B 交替（file_read/file_write 读写循环）
 * 会被窗口塌缩判作振荡 —— 塌缩需窗口 ≥6，evidence 带完整签名历史摘要，用户可从
 * 诊断卡判断误杀。
 */
object ToolOscillationDetector {
    /** 历史容量（对齐 BLOCK_CYCLE_HISTORY 的环形纪律；AgentRunner 侧负责裁剪）。 */
    const val HISTORY_CAPACITY = 48

    /** 判据一：周期距离下限（<3 的短周期由判据二兜住）。 */
    const val MIN_PERIOD = 3

    /** 判据一：末尾连续同距的步数。 */
    const val CYCLE_STREAK_LIMIT = 3

    /** 判据二：塌缩窗口下限。 */
    const val COLLAPSE_WINDOW = 6

    /** 判据二：窗口内 distinct 签名上限。 */
    const val COLLAPSE_DISTINCT = 2

    /**
     * 评估签名历史。返回 trip 证据文案（含历史摘要）或 null（未检出）。
     *
     * @param history 调用签名序列（AgentRunner 的 callSignatureHistory，已按
     *   [HISTORY_CAPACITY] 环形裁剪；本函数只读不写）
     */
    fun evaluate(history: List<String>): String? {
        // 判据二：窗口塌缩（先判 —— O(n)，且短周期形态 distance 判据结构性抓不住）。
        if (history.size >= COLLAPSE_WINDOW) {
            val distinct = history.toSet().size
            if (distinct <= COLLAPSE_DISTINCT) {
                return "最近 ${history.size} 次工具调用只在 $distinct 个选项之间来回打转" +
                    "（${history.takeLast(COLLAPSE_WINDOW).joinToString(" → ") { shortSignature(it) }}）"
            }
        }
        // 判据一：周期距离。检查末尾 CYCLE_STREAK_LIMIT 个位置（倒数第 1..3 个），
        // 每个位置到其「上一同签名位置」的距离全部相同且 ≥ MIN_PERIOD。
        if (history.size > CYCLE_STREAK_LIMIT) {
            var period = 0
            for (k in 1..CYCLE_STREAK_LIMIT) {
                val idx = history.size - k
                val sig = history[idx]
                // 找不到更早的同签名：序列尾部刚展开，不构成周期 → 未检出。
                val prev = (idx - 1 downTo 0).firstOrNull { history[it] == sig } ?: return null
                val distance = idx - prev
                if (distance < MIN_PERIOD) return null
                if (period == 0) period = distance else if (period != distance) return null
            }
            return "工具调用以周期 $period 反复循环（连续 $CYCLE_STREAK_LIMIT 步等距回到同一签名）：" +
                history.takeLast(CYCLE_STREAK_LIMIT * MIN_PERIOD).joinToString(" → ") { shortSignature(it) }
        }
        return null
    }

    /**
     * 签名摘要（进 evidence 给用户看）：工具名 + 参数 canonical 串前 24 字符。
     * 签名本体（name + ":" + canonicalJson）可能很长，完整塞进 evidence 会淹没诊断卡。
     */
    private fun shortSignature(sig: String): String {
        val name = sig.substringBefore(':')
        val args = sig.substringAfter(':', missingDelimiterValue = "")
        return if (args.isBlank()) name else "$name(${args.take(24)})"
    }
}
