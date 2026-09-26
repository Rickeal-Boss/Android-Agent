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
 *  4. 短签名连击：第二口径（去空白、保留标点、无长度门槛）下，同一签名连续
 *     [SHORT_LOOP_STREAK] 句 → 循环。Wave 21 真机事故（SmolVLM2-500M）：模型退化为
 *     「、」「当:」单字符行无限刷屏，而判定①②的 8 字门槛把短退化句全数放行
 *     （「、」归一化为空、「当:」仅 1 字），窗口收不到任何签名、检测全盲。
 *  5. text 流铺陈预算（仅 text 流）：不出句界累计超 [TEXT_RUNAWAY_CHARS] 字符 →
 *     循环。与判定③同构：正常代码块 / 列表都有 \n，4096 字符无任何句界只能是退化。
 *  6. 提示词回显（仅当构造时传入 systemPrompt）：连续 [ECHO_LOOP_STREAK] 句命中
 *     系统提示词指纹 → 循环。Wave 21 真机：500M 级模型先逐字复述工具系统提示词
 *     再退化 —— 层1 的提示词约束对它被证伪，只能在层3（输出侧）拦截。
 */
class StreamRepetitionDetector(
    /**
     * 系统提示词原文，可选。非空时在构造期一次性切段建「回显指纹集」（判定⑥）。
     * 默认 null = 回显检测完全关闭，既有调用点（含 ask_actor 子 run）零行为变化：
     * 回显指纹必须来自本 run 实际拼出的系统提示词，没有传就没有可比对基准 ——
     * 不做成「空串也建指纹集」的退化语义，那只会让检测永远静默、看似接入实则无效。
     */
    systemPrompt: String? = null,
) {

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

        /**
         * 短签名连击计数（判定④）：第二口径下与上一句短签名相同的连续句数。
         * 短退化句（「、」「当:」）在原口径里签名恒为 null、进不了判定①，
         * 只能在这里独立计数。
         */
        var shortStreak = 0

        /** 上一句的第二口径短签名（短签名连击判定的基准）。 */
        var shortLastSignature: String? = null

        /**
         * 提示词回显连击计数（判定⑥）：连续命中回显指纹集的原口径句子数。
         * 放在 [StreamState] 内（两条流各一份）而非检测器字段：回显主要发生在
         * text 流，但 thinking 流同样可能复读提示词，口径必须两条流独立。
         */
        var echoHitStreak = 0

        fun reset() {
            pendingSentence.setLength(0)
            recentSignatures.clear()
            lastSignature = null
            streak = 0
            totalChars = 0
            shortStreak = 0
            shortLastSignature = null
            echoHitStreak = 0
        }
    }

    private val textState = StreamState()
    private val thinkingState = StreamState()

    /**
     * 回显指纹集：系统提示词按句界切段、逐段走**原口径**归一化（≥8 字门槛）。
     * O(|prompt|) 且只在构造时跑一次；prompt 为空/空白 → 空集 → 判定⑥永远不触发
     * （等价于既有行为）。用原口径建指纹：回显的是完整提示词句子，天然 ≥8 字；
     * 若用无门槛的短口径，正常回答里随手一个「工具。」都会与提示词中的片段撞指纹。
     */
    private val echoSignatures: Set<String> = buildEchoSignatures(systemPrompt)

    private fun buildEchoSignatures(prompt: String?): Set<String> {
        if (prompt.isNullOrBlank()) return emptySet()
        return prompt
            .split(*SENTENCE_TERMINATORS.toCharArray())
            .mapNotNull { normalizedSignature(it) }
            .toSet()
    }

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
        // text 流铺陈预算（判定⑤，P0-2）：与 thinking 预算同构 —— 循环退化同样可能
        // 出现在 text 流且不出句界。O(1) 判定（只读拼句缓冲长度），先到先判。
        if (!inThinking && state.pendingSentence.length > TEXT_RUNAWAY_CHARS) {
            return Verdict.LoopDetected(inThinking = false, repeatedSignature = TEXT_RUNAWAY_MARKER)
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

    /**
     * 把 pendingSentence 冲刷成签名并跑四条句子级判定（O(1) + O(WINDOW)）。
     *
     * 判定顺序与短路语义：④⑥先于①②，因为①②以原口径签名为前提 ——
     * `normalizedSignature` 对短退化句返回 null 时旧实现直接 `return Ok`（短路），
     * Wave 21 真机「、」刷屏正是从这个短路里全数漏过。④⑥不依赖原口径签名，
     * 必须放在该短路之前才能接住。⑤（铺陈预算）不在句界，见 [observe]。
     */
    private fun flushSentence(state: StreamState, inThinking: Boolean): Verdict {
        val sentence = state.pendingSentence.toString()
        state.pendingSentence.setLength(0)

        // 判定④：短签名连击（P0-1）。第二口径（保留标点、无长度门槛）——「、」
        // 本身就是退化证据，标点不能像原口径那样剥掉；正常口头语（「好的。」
        // 「明白。」「继续。」）签名互不相同不会连击，同一 1-2 字符签名连出
        // [SHORT_LOOP_STREAK] 次在正常回答中不存在（Fu 2021：高频词分布的自强化
        // 是退化的实锤形态）。
        val shortSignature = normalizedShortSignature(sentence)
        if (shortSignature != null) {
            if (shortSignature == state.shortLastSignature) {
                state.shortStreak++
            } else {
                state.shortLastSignature = shortSignature
                state.shortStreak = 1
            }
            if (state.shortStreak >= SHORT_LOOP_STREAK) {
                return Verdict.LoopDetected(inThinking, SHORT_SIG_RUN_MARKER)
            }
        }

        // 判定⑥：提示词回显（P0-3）。原口径签名命中回显指纹集 → 连击累加，
        // 任何未命中（含短退化句）清零。取「连续 2 句」而非单句：防误伤
        // 「用户贴提示词片段、模型正常引用」的场景 —— 真回显是逐字复述，必然
        // 连续多句命中；偶发引用一句后接正常内容即被打断。
        val signature = normalizedSignature(sentence)
        if (signature != null && signature in echoSignatures) {
            state.echoHitStreak++
            if (state.echoHitStreak >= ECHO_LOOP_STREAK) {
                return Verdict.LoopDetected(inThinking, PROMPT_ECHO_MARKER)
            }
        } else {
            state.echoHitStreak = 0
        }

        // 原口径短句短路（既有行为，保持不变）：下面的①②只对 ≥8 字签名有意义。
        if (signature == null) return Verdict.Ok
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

        /**
         * 短签名连击阈值（判定④）。长句连击阈值是 [LOOP_STREAK]=4，这里取 6：
         * 「好的。」「明白。」「继续。」这类交替口头语签名互不相同不会连击，而
         * 同一 1-2 字符签名连出 6 次在正常回答中不存在 —— Fu 2021（高频词分布
         * 自强化）正是这种退化形态的实锤。取 6 而非更低：给正常回答里的偶发
         * 重复（如列表项前的同一个引导词）留足余量。
         */
        const val SHORT_LOOP_STREAK = 6

        /**
         * 提示词回显连击阈值（判定⑥）：连续 2 句命中指纹集即判循环。取 2 而非 1
         * 是防误伤「用户贴提示词片段、模型正常引用」的场景 —— 真回显是逐字复述
         * 整段提示词，必然连续多句命中；偶发引用一句后接正常内容即被打断。
         */
        const val ECHO_LOOP_STREAK = 2

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

        /**
         * text 流的正文铺陈预算（判定⑤）。与 [THINKING_CHAR_BUDGET] 同款口径注记：
         * 正常代码块 / 列表 / 段落都带 \n 句界，text 流 4096 字符无任何句界只能是
         * 「不出句界的无限铺陈」退化。**可调，真机反馈校准**：量级取 thinking 预算
         * 的两倍（正文比思维链更常出现长列表 / 长代码），流式长代码被误截可上调。
         */
        const val TEXT_RUNAWAY_CHARS = 4096

        /** 预算截断时 LoopDetected.repeatedSignature 的固定标记（日志可辨识）。 */
        const val THINKING_BUDGET_MARKER = "thinking_char_budget"

        /** 短签名连击（判定④）触发时的固定标记（日志可辨识，非具体签名）。 */
        const val SHORT_SIG_RUN_MARKER = "short_sig_run"

        /** text 流铺陈预算（判定⑤）触发时的固定标记（日志可辨识）。 */
        const val TEXT_RUNAWAY_MARKER = "text_runaway_sentence"

        /** 提示词回显（判定⑥）触发时的固定标记（日志可辨识）。 */
        const val PROMPT_ECHO_MARKER = "prompt_echo"

        /** 句子终止符集合：中英句读 + 换行（换行是流式输出最常见的句界）。 */
        private const val SENTENCE_TERMINATORS = "。！？!?.\n"

        /**
         * 「同一段话」的归一化签名：去空白、去标点、小写。
         * 直接用归一化后的字符串做键 —— 等价于哈希，但不会因为哈希碰撞把不同文本
         * 误判成重复。过短的口头语（「好的」「完成」）不构成循环证据，返回 null
         * 由调用方跳过。
         *
         * AgentRunner 的跨轮重复检测（AgentRunner.kt 无进展检测处的调用点）与本检测器
         * 共用本函数，**不许**各自再抄一份（口径分叉历史坑）。
         */
        fun normalizedSignature(text: String): String? {
            // 必须指定 Locale：默认 Locale 在土耳其语区会把 "I" 折成无点的 "ı"，
            // 于是同一段英文/中文回答前后归一化出不同签名，「重复检测」静默失效。
            val normalized = text.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
            return normalized.takeIf { it.length >= MIN_SIGNATURE_CHARS }
        }

        /**
         * 短句的第二口径签名（判定④专用）：去空白、小写（同 [Locale.ROOT] 口径）、
         * **保留标点**，非空即返回 —— 没有长度门槛。
         *
         * ⚠️ 与 [normalizedSignature] 是**刻意分开的两份实现**，不许互相调用、不许合并：
         * 两者的取舍正好相反 —— 原口径剥标点 + 8 字门槛，服务「长句级」重复证据；
         * 短口径保留标点 + 零门槛，服务「单字符级」退化证据。「、」「当:」这类退化
         * 句在原口径下归一化为空 / 1 字，全部被 8 字门槛放行（Wave 21 真机事故根因）；
         * 而标点本身就是这里的证据 —— 「、」刷屏里唯一稳定的就是那个顿号。合并成一
         * 份带开关参数的实现会让两个口径互相牵制（改门槛/改标点策略必然同时影响
         * AgentRunner 跨轮检测），分开演化才是安全的。
         */
        fun normalizedShortSignature(text: String): String? {
            val normalized = text.lowercase(Locale.ROOT).filter { !it.isWhitespace() }
            return normalized.takeIf { it.isNotEmpty() }
        }
    }
}
