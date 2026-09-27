package com.rickeal.agent.core.agent.breaker

/**
 * 工具调用振荡检测器（Wave 30 §2.6，评审 §3.2(c) 判据收编）。
 *
 * 纯函数：输入 append 后的签名历史序列，输出 null（未检出）或 trip 证据文案；
 * AgentRunner 只调用不实现。单份实现，杜绝与 StreamRepetitionDetector 判定②⑦
 * 的口径分叉（normalizedSignature 注释的既有纪律）。
 * 已知分叉（复审记录，待裁定，勿当成照搬）：判定⑦ 的等距连击是 5、判定② 的
 * 窗口容量是 16，本检测器取 [CYCLE_STREAK_LIMIT]=3 / [HISTORY_CAPACITY]=48。
 *
 * 双判据：
 * - 判据一（周期距离）：从末尾起连续 [CYCLE_STREAK_LIMIT] 个位置，每个位置到其
 *   上一同签名位置的距离相同且 ≥ [MIN_PERIOD] → 检出（抓 A/B/C/A/B/C 这类
 *   距离判据才能抓住的展开周期形态）。
 *   长度门槛虽写 [CYCLE_STREAK_LIMIT]，实际最小可检出长度是 6：倒数第 3 个位置
 *   也要有更早的同签名位置，故长度 4/5 结构性不检出。
 * - 判据二（窗口塌缩）：历史长度 ≥ [COLLAPSE_WINDOW] 且**整个历史**（不是末尾
 *   [COLLAPSE_WINDOW] 项）的 distinct ≤ [COLLAPSE_DISTINCT] → 检出（抓 A/B 交替
 *   这种短周期形态 —— 判定② KDoc 的同款搬移；distance 判据因周期 2 < MIN_PERIOD
 *   抓不住它）。
 *   口径务必看清：distinct 取自**全量历史**（容量 [HISTORY_CAPACITY]=48），不是
 *   滑动窗口 —— 只要本 run 出现过 ≥3 个不同签名，A/B 交替就再也进不了本判据
 *   （evidence 里的箭头摘要仍只取末尾 [COLLAPSE_WINDOW] 项）。
 *
 * 误杀面（评审 §3.5 已接受）：合法的 A/B 交替（file_read/file_write 读写循环）
 * 会被窗口塌缩判作振荡 —— 塌缩需窗口 ≥6，evidence 带完整签名历史摘要，用户可从
 * 诊断卡判断误杀。
 *
 * 误杀面（复审补充，待裁定）：**换参重试簇** —— 同一工具 A 参数连发若干次后换参
 * B 再连发（旧同参护栏在换参时把 streak 重置，属既有设计放行的恢复路径），全历史
 * distinct=2 会在累计第 6 次被本判据判死，且归因写成「振荡」而非「连续失败」。
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
