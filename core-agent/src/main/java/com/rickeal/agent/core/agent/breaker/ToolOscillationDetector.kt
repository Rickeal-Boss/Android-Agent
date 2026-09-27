package com.rickeal.agent.core.agent.breaker

/**
 * 工具调用振荡检测器（Wave 30 §2.6，评审 §3.2(c) 判据收编）。
 *
 * 纯函数：输入 append 后的签名历史序列，输出 null（未检出）或 trip 证据文案；
 * AgentRunner 只调用不实现。单份实现，杜绝与 StreamRepetitionDetector 判定②⑦
 * 的口径分叉（normalizedSignature 注释的既有纪律）。
 * 已知分叉（复审记录，勿当成照搬）：判定⑦ 的等距连击是 5、判定② 的窗口容量是
 * 16，本检测器取 [CYCLE_STREAK_LIMIT]=3 / [COLLAPSE_WINDOW]=6 —— 工具域取值理由
 * 见各常量 KDoc（连击 3 由主理人裁定维持，改它需要真机数据）。
 *
 * 双判据：
 * - 判据一（周期距离）：从末尾起连续 [CYCLE_STREAK_LIMIT] 个位置，每个位置到其
 *   上一同签名位置的距离相同且 ≥ [MIN_PERIOD] → 检出（抓 A/B/C/A/B/C 这类
 *   距离判据才能抓住的展开周期形态）。
 *   长度门槛虽写 [CYCLE_STREAK_LIMIT]，实际最小可检出长度是 6：倒数第 3 个位置
 *   也要有更早的同签名位置，故长度 4/5 结构性不检出。
 * - 判据二（窗口塌缩）：取**末尾 [COLLAPSE_WINDOW] 次调用**作滑动窗口，窗口内
 *   distinct ≤ [COLLAPSE_DISTINCT] **且相邻两两不等（真交替）** → 检出（抓 A/B
 *   交替这种短周期形态 —— 判定② KDoc 的同款搬移；distance 判据因周期 2 <
 *   MIN_PERIOD 抓不住它）。两个附加条件各自有明确职责：
 *   - 滑动窗口（只看末尾 N 项，不是全量历史）：否则本 run 只要出现过 ≥3 个不同
 *     签名，窗口 distinct 就永远 > 2，A/B 死循环再也进不了判据 —— 本波的核心
 *     目标会静默失效（复审 P1-1）。
 *   - 相邻两两不等（真交替）：同参连发（distinct=1）与**换参重试簇**（A×n 之后
 *     换参 B×n，distinct=2）都是簇状而非交替，交给既有同参护栏 / 失败连击护栏
 *     处置，不被本判据抢走 trip 与归因 —— 否则诊断卡会把「连续失败」写成
 *     「在 2 个选项之间来回打转」（复审 P1-2）。
 *
 * 误杀面（评审 §3.5 已接受）：合法的严格 A/B 交替（file_read/file_write 读写循环）
 * 会被判据二判作振荡 —— 需窗口内满 6 次且严格交替，evidence 带签名历史摘要，用户
 * 可从诊断卡判断误杀。
 */
object ToolOscillationDetector {
    /** 历史容量（对齐 BLOCK_CYCLE_HISTORY 的环形纪律；AgentRunner 侧负责裁剪）。 */
    const val HISTORY_CAPACITY = 48

    /** 判据一：周期距离下限（<3 的短周期由判据二兜住）。 */
    const val MIN_PERIOD = 3

    /** 判据一：末尾连续同距的步数。 */
    const val CYCLE_STREAK_LIMIT = 3

    /**
     * 判据二：塌缩窗口大小 —— 只取末尾这 N 次调用（滑动窗口，不是全量历史）。
     *
     * 取 6 而**不是**原型判定② 的 16：工具调用粒度远粗于句子，一轮轮预算只有
     * 8 次调用，窗口 16 会吃掉半个 run、等到第 16 次才止损已失去意义；6 = 3 个
     * A/B 周期，与同参硬护栏「每个签名各放行 3 次」（REPEAT_TOOL_CALL_EXEC_LIMIT）
     * 对称 —— 交替形态下两个签名各自也正好拿到 3 次执行机会。
     */
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
            val window = history.takeLast(COLLAPSE_WINDOW)
            val distinct = window.toSet().size
            // 真交替闸门：相邻两两不等。簇状序列（同参连发 A/A/A…、换参重试簇
            // A×3 → B×3）distinct 也 ≤2，但不是「来回打转」——留给既有同参护栏
            // 与失败连击护栏，本判据不抢它的 trip 与归因。
            val alternating = (1 until window.size).all { window[it] != window[it - 1] }
            if (distinct <= COLLAPSE_DISTINCT && alternating) {
                return "最近 ${window.size} 次工具调用只在 $distinct 个选项之间来回打转" +
                    "（${window.joinToString(" → ") { shortSignature(it) }}）"
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
