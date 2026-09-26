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
 *  7. 周期块循环：同一句子**以相同句距**反复出现、相同句距连续
 *     [BLOCK_CYCLE_STREAK] 次 → 循环（「A B C D / A B C D …」型整块周期复读）。
 *     Wave 22 真机（小模型把同一段工具调用 JSON 连发 6 次）：块内句子互不相同
 *     ⇒ 判定①每句重置、判定②窗口唯一数 ≈5 > 2 永不塌缩 → 整块循环全数漏过。
 *  8. 单字符 run：同一非空白字符连续 [CHAR_RUN_LOOP] 个 → 循环（空白不计，
 *     代码缩进可合法地有几十个空格）。Wave 22 真机：模型退化为数百个「`」且
 *     **不带换行** ⇒ 判定⑦刷不出句、判定⑤要等 4096 字符才截 —— 这是唯一
 *     能在第 24 个字符就介入的判据。
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

        /**
         * 周期块循环状态（判定⑦）：最近句子的**有序**历史（FIFO，容量
         * [Companion.BLOCK_CYCLE_HISTORY]）。判定①「同上一句连击」只抓得住
         * 「A A A」，抓不住「A B C D / A B C D」这种**轮换块**的周期复读 ——
         * 后者正是真机「同一工具 JSON 连发 N 次」的形态：块内句子签名互不相同，
         * 判定①②（连击 / 窗口塌缩，窗口唯一数 ≈5 > 2）全数漏过。
         */
        val sentenceHistory = ArrayDeque<String>()

        /** 上一次「某签名再次出现」的句距（0 = 尚无）。 */
        var lastCycleDistance = 0

        /** 相同句距连续出现的次数 —— 周期成立的最强证据。 */
        var cycleDistanceStreak = 0

        /** 判定⑧ 单字符 run：当前连续重复的非空白字符（'\u0000' = 无）。 */
        var runChar: Char = '\u0000'

        /** 判定⑧ 单字符 run：当前已连续重复几个（含首个）。 */
        var runLength = 0

        fun reset() {
            pendingSentence.setLength(0)
            recentSignatures.clear()
            lastSignature = null
            streak = 0
            totalChars = 0
            shortStreak = 0
            shortLastSignature = null
            echoHitStreak = 0
            sentenceHistory.clear()
            lastCycleDistance = 0
            cycleDistanceStreak = 0
            runChar = '\u0000'
            runLength = 0
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
            // 判定⑧：单字符 run（Wave 22 P0）。
            // 真机形态：模型在工具 JSON 连发之后退化成一长串「`」——**没有换行**，
            // 于是⑦刷不出句（无句界）、⑤要等 4096 字符才截，用户盯着几百个反引号
            // 刷屏才发现不对。逐字符 run 是最早能介入的判据（Fu 2021：高频符号的
            // 自强化闭环最早就表现为单字符 run）。
            // 空白（含缩进空格 / 连续空行）刻意不计入：代码块缩进可以合法地有
            // 几十个空格，把空白算进去会误伤正常代码输出。
            if (ch.isWhitespace()) {
                state.runChar = '\u0000'
                state.runLength = 0
            } else if (ch == state.runChar) {
                state.runLength++
                if (state.runLength >= CHAR_RUN_LOOP) {
                    return Verdict.LoopDetected(inThinking, CHAR_RUN_MARKER)
                }
            } else {
                state.runChar = ch
                state.runLength = 1
            }
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

        // 判定⑦：周期块循环（Wave 22 P0）。
        // 形态：模型把一整块内容（真机 = 同一段工具调用 JSON）按固定句数周期复读
        // 「A B C D E / A B C D E / …」。块内句子互不相同 ⇒ 判定①（同上一句连击）
        // 每句都被重置、判定②（窗口唯一数 ≈5 > [WINDOW_UNIQUE_LIMIT]）永不塌缩 ——
        // 「current_time 同参连发 6 次」正是从这个缝里漏过的。
        // 判据：同一句子**以相同句距**反复出现，相同句距连续出现
        // [BLOCK_CYCLE_STREAK] 次即判周期成立 —— 比"某句重复过"强得多：
        // 正常文本里一句话复现一次是引用/排比，等距反复复现才是复读。
        // 键用第二口径（保标点、无长度门槛）：块里的短句（"{" "}"）同样参与周期。
        if (shortSignature != null) {
            state.sentenceHistory.addLast(shortSignature)
            if (state.sentenceHistory.size > BLOCK_CYCLE_HISTORY) {
                state.sentenceHistory.removeFirst()
            }
            // addLast/removeFirst 之后才扫下标，避免容量裁剪导致下标位移算错句距。
            var lastIdx = -1
            for (i in 0 until (state.sentenceHistory.size - 1)) {
                if (state.sentenceHistory[i] == shortSignature) lastIdx = i
            }
            if (lastIdx >= 0) {
                val distance = (state.sentenceHistory.size - 1) - lastIdx
                // 周期下限（[BLOCK_CYCLE_MIN_PERIOD]）：句距 2 的「A B / A B」是
                // 合法写作结构（对比 / 对仗 / 优缺点列表），不是退化 —— 实测
                // 「- 优点 / - 缺点」连写 3 组就会以句距 2 触发，必须挡掉。
                // 真机周期复读（工具 JSON 块）句距 7-9，稳稳落在门内。
                if (distance >= BLOCK_CYCLE_MIN_PERIOD) {
                    if (distance == state.lastCycleDistance) {
                        state.cycleDistanceStreak++
                    } else {
                        state.lastCycleDistance = distance
                        state.cycleDistanceStreak = 1
                    }
                    if (state.cycleDistanceStreak >= BLOCK_CYCLE_STREAK) {
                        return Verdict.LoopDetected(inThinking, BLOCK_CYCLE_MARKER)
                    }
                } else {
                    // 句距低于周期下限：不计数，但也要把「上一次句距」冲掉 ——
                    // 否则下一句算出的句距若恰好等于这个被忽略的旧值，会被误判成
                    // 连击延续（跨过一次非周期句后仍沿用旧基准）。
                    state.lastCycleDistance = 0
                    state.cycleDistanceStreak = 0
                }
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

        /** 周期块循环（判定⑦）触发时的固定标记（日志可辨识）。 */
        const val BLOCK_CYCLE_MARKER = "block_cycle"

        /** 单字符 run（判定⑧）触发时的固定标记（日志可辨识）。 */
        const val CHAR_RUN_MARKER = "char_run"

        /**
         * 周期块循环的句子历史容量（判定⑦）。容量即「能识别的最大周期」：
         * 48 句足够覆盖真机形态（工具 JSON 块约 7-9 句）。扫描 O(容量)，只在
         * 句界冲刷时跑一次（远低于字符频次），开销可忽略。
         */
        const val BLOCK_CYCLE_HISTORY = 48

        /**
         * 周期块循环的句距连击阈值（判定⑦，配合 [BLOCK_CYCLE_MIN_PERIOD]）：
         * 相同句距连续出现 5 次判周期成立。
         *
         * 阈值是**实测**定的（脚本复刻本检测器跑真机样本与误伤样本）：
         *  - 连击 4 + 无周期门：「- 优点 / - 缺点」交替 3 组（句距 2）误触发 ✗；
         *  - 连击 6 + 无周期门：交替 5 组仍误触发 ✗；
         *  - **连击 5 + 周期 ≥3**：真机「工具 JSON 连发」在第 14 句（第二次复读
         *    内）截住 ✓，交替排比 / 正常列表（内容各异）/ 代码缩进 /
         *    Markdown 分隔线全部零误伤 ✓。
         * 残留误伤面（可接受）：三句一组**逐字**相同的块重复 3 次（9 句）会触发
         * —— 那本身就是复读，不是写作结构。
         */
        const val BLOCK_CYCLE_STREAK = 5

        /**
         * 周期块循环的最小句距（判定⑦）：句距 < 3 的等距复现不判周期。
         * 句距 2 = 「A B / A B」交替，是合法写作结构（对比 / 对仗 / 优缺点列表），
         * 不是退化；真机周期复读（工具 JSON 块）句距 7-9，远在门内。
         */
        const val BLOCK_CYCLE_MIN_PERIOD = 3

        /**
         * 单字符 run 阈值（判定⑧）：同一非空白字符连续出现 24 个即判退化。
         * 取 24 的依据：合法文本里最长的同字符 run 是 Markdown 围栏/分隔线
         * （``` --- === ≈3）、强调线（—— ≈4-6）、省略号（… ≈6）—— 24 有 4 倍余量；
         * 真机「`」run 是数百级的，第 24 个字符就能截住，不用等 ⑤ 的 4096 字符。
         * **空白不计入**（代码缩进可以合法地有几十个空格），见 [observe]。
         */
        const val CHAR_RUN_LOOP = 24

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
