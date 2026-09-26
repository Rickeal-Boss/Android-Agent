package com.rickeal.agent.core.model

import java.util.Locale

/**
 * 轮内流式重复检测器（Wave 19 反循环 P0，防线第 4 层）。
 *
 * ## 解决什么问题
 *
 * 既有防线（AgentRunner 的轮级 maxRounds、跨轮重复签名检测、NO_TOOL_STREAK_LIMIT、
 * MAX_EMPTY_ANSWER_ROUNDS、DENIAL_CIRCUIT_LIMIT）全是**轮粒度**的：一轮生成内部
 * 「循环思考 → 同一句子无限复读」时，整轮照常跑满 maxTokens 才结束，用户盯着
 * 一段不断自我重复的流式文本几十秒。本检测器把防线下沉到 **chunk 粒度**：
 * 流式过程中发现循环立即终止本次生成，已产出的文本保留（不丢弃）。
 *
 * ## 来源证据（调研 9 个开源 harness 后定案）
 *
 *  - 「取消保留已生成文本」语义：deepseek-harness
 *    `packages/core/agent-loop/src/agent.ts:428-460`（interrupted blocks —— 中断的
 *    生成块不回滚、原样入上下文）；本仓 `LiteRtLmEngine.kt:512-521` 的 finally
 *    `cancelProcess` 收尾路径与之配套（collector 抛异常 ⇒ finally 触发 ⇒
 *    conversationDirty ⇒ 下一轮自动重建会话，上下文一致性无损）。
 *  - 「不看轮数看增量」思想：claude-code（free-code）`src/query/tokenBudget.ts:59-62`
 *    —— 预算按增量消耗计，不按回合计。本类同样只消费增量 delta，不做全文比对。
 *
 * ## 复杂度纪律（硬约束）
 *
 * 每 chunk 只处理**增量 delta**：逐字符拼句 + 句界冲刷（O(delta)），窗口检查
 * O([WINDOW])（≤16 个签名的去重）。**禁止**每 chunk 对累积全文做扫描 —— 端侧
 * 0.5B 的推理吞吐本来就低，检测开销必须可忽略。
 *
 * ## 判定规则（text / thinking 两条流各自独立一套状态）
 *
 *  1. 同句连击：与上一句签名相同 → streak++，`streak >= LOOP_STREAK` → 循环。
 *  2. 窗口塌缩：窗口内句子数 ≥ [WINDOW_MIN_SENTENCES] 且唯一签名 ≤
 *     [WINDOW_UNIQUE_LIMIT]（两句 A/B 交替也是循环）。
 *  3. 思考预算（仅 thinking 流）：累计思考字符超 [THINKING_CHAR_BUDGET] → 循环
 *     （循环思考的常见形态是不出句界地无限铺陈，句界判定抓不住，只能按总量截）。
 */
class StreamRepetitionDetector {

    sealed interface Verdict {
        data object Ok : Verdict

        /** [inThinking] = 循环发生在思考流；[repeatedSignature] = 命中的句子签名（预算截断时为固定标记）。 */
        data class LoopDetected(val inThinking: Boolean, val repeatedSignature: String) : Verdict
    }

    /** 单条流（text 或 thinking）的独立状态。 */
    private class StreamState {
        /** 跨 chunk 拼句缓冲：句子终止符到达时整句冲刷为签名。 */
        val pendingSentence = StringBuilder()

        /** 最近句子的滚动签名窗口（FIFO，容量 [Companion.WINDOW]）。 */
        val recentSignatures = ArrayDeque<String>()

        /** 上一句的签名（同句连击判定的基准）。 */
        var lastSignature: String? = null

        /** 与上一句签名相同的连续句数。 */
        var streak = 0

        /** 仅 thinking 流使用：累计思考字符数（预算截断判据）。 */
        var totalChars = 0

        fun reset() {
            pendingSentence.setLength(0)
            recentSignatures.clear()
            lastSignature = null
            streak = 0
            totalChars = 0
        }
    }

    private val textState = StreamState()
    private val thinkingState = StreamState()

    fun observeText(delta: String): Verdict = observe(textState, delta, inThinking = false)

    fun observeThinking(delta: String): Verdict = observe(thinkingState, delta, inThinking = true)

    /** 新一轮生成前调用（含同轮失败重试）：两条流的状态全部清零。 */
    fun reset() {
        textState.reset()
        thinkingState.reset()
    }

    private fun observe(state: StreamState, delta: String, inThinking: Boolean): Verdict {
        if (delta.isEmpty()) return Verdict.Ok
        // 思考预算：循环思考常常不出句界（没有终止符的长铺陈），句界判定抓不住，
        // 只能按累计字符量截。放在逐字符处理之前，够便宜且先到先判。
        if (inThinking) {
            state.totalChars += delta.length
            if (state.totalChars > THINKING_CHAR_BUDGET) {
                return Verdict.LoopDetected(inThinking = true, repeatedSignature = THINKING_BUDGET_MARKER)
            }
        }
        for (ch in delta) {
            state.pendingSentence.append(ch)
            if (SENTENCE_TERMINATORS.indexOf(ch) >= 0) {
                val verdict = flushSentence(state, inThinking)
                if (verdict is Verdict.LoopDetected) return verdict
            }
        }
        return Verdict.Ok
    }

    /** 把 pendingSentence 冲刷成签名并跑两条判定（O(1) + O(WINDOW)）。 */
    private fun flushSentence(state: StreamState, inThinking: Boolean): Verdict {
        val sentence = state.pendingSentence.toString()
        state.pendingSentence.setLength(0)
        val signature = normalizedSignature(sentence) ?: return Verdict.Ok
        // 判定①：同句连击 —— 与上一句签名相同则连击累加，否则重置为新句。
        if (signature == state.lastSignature) {
            state.streak++
        } else {
            state.lastSignature = signature
            state.streak = 1
        }
        // 判定②：窗口塌缩 —— 入窗后窗口足够满、唯一签名又极少，即 A/B 交替循环。
        state.recentSignatures.addLast(signature)
        if (state.recentSignatures.size > WINDOW) state.recentSignatures.removeFirst()
        if (state.streak >= LOOP_STREAK) {
            return Verdict.LoopDetected(inThinking, signature)
        }
        if (state.recentSignatures.size >= WINDOW_MIN_SENTENCES &&
            state.recentSignatures.distinct().size <= WINDOW_UNIQUE_LIMIT
        ) {
            return Verdict.LoopDetected(inThinking, signature)
        }
        return Verdict.Ok
    }

    companion object {
        /**
         * 归一化签名的最小长度：过短的口头语（「好的」「完成」）不算循环证据。
         * 与 AgentRunner 跨轮重复检测共用同一口径（单份实现，杜绝口径分叉 ——
         * 该处历史坑：两份归一化各自演化后「重复检测」静默失效）。
         */
        const val MIN_SIGNATURE_CHARS = 8

        /** 滚动签名窗口容量。窗口检查 O(WINDOW)，上限 16 保证检测开销可忽略。 */
        const val WINDOW = 16

        /** 同句连击阈值：连续 4 句签名相同即判循环。 */
        const val LOOP_STREAK = 4

        /** 窗口塌缩判定的最少句子数。 */
        const val WINDOW_MIN_SENTENCES = 6

        /** 窗口塌缩判定的最大唯一签名数。 */
        const val WINDOW_UNIQUE_LIMIT = 2

        /**
         * thinking 流的字符预算。**可调，真机反馈校准**：2048 字符 ≈ maxTokens 1024
         * 的中文口径（中文字符 ≈ 1 token ≈ 2 字符取整上限）；英文推理模型若被过早
         * 截断可上调。
         */
        const val THINKING_CHAR_BUDGET = 2048

        /** 预算截断时 LoopDetected.repeatedSignature 的固定标记（日志可辨识）。 */
        const val THINKING_BUDGET_MARKER = "thinking_char_budget"

        /** 句子终止符集合：中英句读 + 换行（换行是流式输出最常见的句界）。 */
        private const val SENTENCE_TERMINATORS = "。！？!?.\n"

        /**
         * 「同一段话」的归一化签名：去空白、去标点、小写。
         * 直接用归一化后的字符串做键 —— 等价于哈希，但不会因为哈希碰撞把不同文本
         * 误判成重复。过短的口头语（「好的」「完成」）不构成循环证据，返回 null
         * 由调用方跳过。
         *
         * AgentRunner 的跨轮重复检测（progressSignature）与本检测器共用本函数，
         * **不许**各自再抄一份（口径分叉历史坑）。
         */
        fun normalizedSignature(text: String): String? {
            // 必须指定 Locale：默认 Locale 在土耳其语区会把 "I" 折成无点的 "ı"，
            // 于是同一段英文/中文回答前后归一化出不同签名，「重复检测」静默失效。
            val normalized = text.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
            return normalized.takeIf { it.length >= MIN_SIGNATURE_CHARS }
        }
    }
}
