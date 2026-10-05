package com.rickeal.agent.core.model

/**
 * 模型自检的健康度结论（Wave 44 P0-1）。
 *
 * 聚合口径（充分必要，见 [ModelHealthCriteria.verdictOf]）：
 *  - [BAD]：≥1 **HARD** 命中。硬判据全部是「logits 分布塌 / 容器↔运行时错配」的确定性证据
 *    （Wave 43 真机：`<unused1556>` 保留 token），出现即换温度/topK 也救不回。
 *  - [DEGRADED]：0 HARD ∧ ≥1 **SOFT** 命中。软判据「可疑但非决定性」（上游可能新增合法通道），
 *    只降级提示、不判死 —— 误报成本高（任务书明确）。
 *  - [PASS]：0 HARD ∧ 0 SOFT。
 */
enum class ModelHealthVerdict { PASS, DEGRADED, BAD }

/** 判据强度（决定聚合档位）。 */
enum class CriterionSeverity { HARD, SOFT }

/**
 * 一条判据命中。
 *
 * @param id 常量标记（[ModelHealthCriteria] 的 `ID_*`），日志/UI 可辨识
 * @param severity HARD / SOFT
 * @param evidence 命中片段（截断 ≤ [ModelHealthCriteria.EVIDENCE_MAX_CHARS]，脱敏）
 */
data class CriterionHit(
    val id: String,
    val severity: CriterionSeverity,
    val evidence: String,
)

/**
 * 模型加载后小样本自检的**判据纯函数集**（Wave 44 P0-1）。
 *
 * ## 总原则：复用优先，零口径分叉
 *
 * 自检对输出跑的**重复类**判据，一律通过新建一个
 * [StreamRepetitionDetector]`(systemPrompt = null)` 实例 + 喂 `observeText` 实现
 * —— **零复制常量、零复制归一化**（历史坑：两份归一化各自演化 ⇒ 检测静默失效，
 * 见 `StreamRepetitionDetector.kt` 的注释）。detector 判定⑥⑨依赖 systemPrompt，
 * 传 null 即自动关闭（自检不需要回显检测，无系统提示词语料）。
 *
 * 本 object 只**新增** detector 不覆盖的判据（保留 token / 通道标记 / 多字符周期 /
 * 空输出），外加 B1（单字符 run）作为独立可测的纯函数 —— 其口径与 detector 判定⑧
 * **逐字一致**（空白不计），阈值**引用** `StreamRepetitionDetector.CHAR_RUN_LOOP` 而非复制 24。
 *
 * ## 全部阈值集中在本 object 顶部常量区（可校准、禁止散落）
 *
 * 真机实测后回填：改一处即可（B2 的 [PERIOD_MAX] / [PERIOD_MIN_CYCLES] 直接引用 detector
 * 常量，改 detector 阈值会**自动同步**）。
 *
 * ## 候选判据（**未实现**，仅备忘）
 *
 *  - **低字符多样性**（`uniqueChars / total < 0.15`）：短输出天然多样性低，误报面大，
 *    待真机校准后若证明有效再落地。**本波不实现、不写占位代码**（避免死代码）。
 *    软判据集当前仅 [ID_CHANNEL_MARKER]。
 */
object ModelHealthCriteria {

    // ─────────────────────────────── 常量区（可校准）───────────────────────────────

    /**
     * 判据 A：保留未训练 token（硬）。
     *
     * 形态 `<unusedNNNN>`（大小写不敏感、允许空格）。依据：Wave 43 真机
     * `gemma-4-E2B-it-gpu.litertlm` 采样出 `<unused1556>` / `<unused4347>`
     * （logits 分布退化的确定性证据）。
     */
    val RESERVED_TOKEN_REGEX: Regex = Regex("""<unused\s*\d+>""", RegexOption.IGNORE_CASE)

    /**
     * 判据 A′：非白名单通道标记（**软**）。
     *
     * 形态 `<|channel>NAME`（`<|channel|>` 变体亦匹配）。白名单 [CHANNEL_WHITELIST]
     * （= 引擎唯一声明的通道）。定为软判据：上游可能新增合法通道，硬判会误报
     * （Wave 43 真机见 `<|channel>तरह` 泄漏）。
     *
     * ⚠️ **通道名必须 Unicode 感知**：`[\p{L}\p{N}_]`（任意文字系统的字母/数字/下划线），
     * **不是** `[A-Za-z_]` —— Wave 43 真机泄漏的通道名是**天城文** `तरह`，ASCII-only 正则
     * 捕获不到它（整条不匹配），会把真实泄漏漏检。用 `\p{L}` 同时保证
     * `<|channel>thought。` 只捕获 `thought`（句号是标点，不入组）⇒ 白名单判定不受尾部标点干扰。
     */
    val CHANNEL_MARKER_REGEX: Regex = Regex("""<\|channel\|?>\s*([\p{L}\p{N}_]+)""")

    /** 判据 A′ 白名单通道（= `LiteRtLmEngine.THOUGHT_CHANNEL`，引擎只声明这一个）。 */
    val CHANNEL_WHITELIST: Set<String> = setOf("thought")

    /**
     * 判据 B2 最小周期。p=1 是 B1（单字符 run）的辖区，划走避免同一现象两处判定、
     * 阈值互不一致。
     */
    const val PERIOD_MIN = 2

    /**
     * 判据 B2 最大周期 = [StreamRepetitionDetector.CHAR_RUN_LOOP]（= 24）。
     *
     * 推导：detector 已把 24 定为「退化短 run」的边界（合法同字符 run 最长 ≈6 的 Markdown
     * 围栏 ×4 余量）。周期块长于 24 字符不再是「短 token 退化」，属**句子级**判定⑦辖区
     * （`BLOCK_CYCLE_HISTORY=48`）。在 24 截断既避免辖区重叠，又把扫描上限钉死为
     * O(24×96)≈2300 次字符比较（可忽略）。
     */
    const val PERIOD_MAX = StreamRepetitionDetector.CHAR_RUN_LOOP

    /**
     * 判据 B2 最小整周期数 = [StreamRepetitionDetector.LOOP_STREAK]（= 4）。
     * detector 判定①「同一签名连续 4 次 = 循环」是最基础的置信口径，与之同源。
     */
    const val PERIOD_MIN_CYCLES = StreamRepetitionDetector.LOOP_STREAK

    /**
     * 判据 B2 最小覆盖字符数 = 2 × [StreamRepetitionDetector.CHAR_RUN_LOOP]（= 48）。
     *
     * 绝对下限：短周期（p=2）若只要求 4 周期 = 8 字符，证据太弱；48 字符的严格周期性
     * （p=2 时 = 24 个 `ab`）在正常文本中不存在。取 2×CHAR_RUN_LOOP 与单字符阈值同量级。
     */
    const val PERIOD_MIN_COVERED = 2 * StreamRepetitionDetector.CHAR_RUN_LOOP

    /** evidence 截断上限（字符）。 */
    const val EVIDENCE_MAX_CHARS = 80

    /** 判据 A id。 */
    const val ID_RESERVED_TOKEN = "reserved_token"

    /** 判据 A′ id。 */
    const val ID_CHANNEL_MARKER = "channel_marker"

    /** 判据 B1 id（= detector 判定⑧ 的标记，同一现象同 id）。 */
    const val ID_CHAR_RUN = StreamRepetitionDetector.CHAR_RUN_MARKER

    /** 判据 B2 id。 */
    const val ID_CHAR_PERIOD = "char_period"

    /** 判据 C id。 */
    const val ID_EMPTY_OUTPUT = "empty_output"

    /** detector 循环命中（判定①②④⑤⑦⑧任一）id。 */
    const val ID_DETECTOR_LOOP = "detector_loop"

    // ─────────────────────────────── 判据实现 ───────────────────────────────

    /** 判据 A：`<unusedNNNN>` 命中片段（可多个）。 */
    fun reservedTokenHits(text: String): List<String> =
        RESERVED_TOKEN_REGEX.findAll(text).map { it.value }.toList()

    /** 判据 A′：非白名单通道名命中片段（可多个）。 */
    fun nonWhitelistChannelHits(text: String): List<String> =
        CHANNEL_MARKER_REGEX.findAll(text)
            .mapNotNull { match ->
                match.groupValues.getOrNull(1)?.takeIf { it !in CHANNEL_WHITELIST }
            }
            .toList()

    /**
     * 判据 B1：非空白字符连续 run ≥ [StreamRepetitionDetector.CHAR_RUN_LOOP]。
     *
     * 口径与 detector 判定⑧**逐字一致**：空白（含缩进空格 / 连续空行）**不计入**
     * —— 代码块缩进可以合法地有几十个空格。做**原始字符比较、不归一化**。
     */
    fun singleCharRunHit(text: String): Boolean {
        var runChar = '\u0000'
        var runLength = 0
        for (ch in text) {
            if (ch.isWhitespace()) {
                runChar = '\u0000'
                runLength = 0
            } else if (ch == runChar) {
                runLength++
                if (runLength >= StreamRepetitionDetector.CHAR_RUN_LOOP) return true
            } else {
                runChar = ch
                runLength = 1
            }
        }
        return false
    }

    /**
     * 判据 B2：尾部窗口是否为周期 p 的整周期复读。做**原始字符比较、不归一化**。
     *
     * @param window 需要覆盖的尾部窗口长度（由 [charPeriodicRepeat] 计算）。
     *   前置条件 `window ≥ p`（生产恒成立：`charPeriodicRepeat` 传 `window = max(48, 4p) > p`）；
     *   违反时**显式返回 false** —— 否则 `start + p until len` 为空区间、函数会**真空为真**
     *   （Wave 44 审查 P3-2：`isPeriodicTail("abc", 10, 3)` 曾误返 true）。
     */
    fun isPeriodicTail(text: String, p: Int, window: Int): Boolean {
        if (p <= 0 || window <= 0 || text.length < window) return false
        if (p > window) return false
        val start = text.length - window
        for (i in start + p until text.length) {
            if (text[i] != text[i - p]) return false
        }
        return true
    }

    /**
     * 判据 B2：返回命中的**最小**周期 p（∈ [[PERIOD_MIN], [PERIOD_MAX]]），null = 未命中。
     *
     * detector 现有 9 判据**不覆盖**多字符周期（判定⑧只抓单字符、判定⑤要等 4096 字符、
     * 判定④⑦是句子级）—— 这是自检唯一新增的重复逻辑。做原始字符比较，无归一化分叉面。
     */
    fun charPeriodicRepeat(text: String): Int? {
        for (p in PERIOD_MIN..PERIOD_MAX) {
            val window = maxOf(PERIOD_MIN_COVERED, p * PERIOD_MIN_CYCLES)
            if (isPeriodicTail(text, p, window)) return p
        }
        return null
    }

    /**
     * 判据 C：空输出（硬）。空白/标点/符号皆不算内容；无任何 letter/digit 即视为空
     * （含纯标点）。口径复用 `isLetterOrDigit()`（与 detector 的归一化同一字符分类口径），
     * **不引入第二套字符分类**。
     */
    fun isEffectivelyEmpty(text: String): Boolean = text.none { it.isLetterOrDigit() }

    /**
     * 对一段输出跑**全部**判据，返回命中列表（**单通道入口**）。
     *
     * 等价于 [evaluateSplit]`(text, "")` —— 保留既有语义、零行为变化。
     *
     * 重复类判据走新建的 [StreamRepetitionDetector]`(systemPrompt = null)` 实例
     * （判定①②④⑤⑦⑧），其余走本 object 的纯函数。
     *
     * ⚠️ **B1 与 detector 判定⑧ 同口径**：一段 24 个连续 `` ` `` 会同时命中
     * [ID_CHAR_RUN]（B1）与 [ID_DETECTOR_LOOP]（detector 标记 = `char_run`）——这是刻意的：
     * B1 提供稳定自述 id、detector 提供标记，便于归因；不影响档位（都是 HARD）。
     */
    fun evaluate(text: String): List<CriterionHit> = evaluateSplit(text, thinking = "")

    /**
     * 对**双通道**输出（正文 + 思考）跑判据，返回命中列表（Wave 49 E1，探针专用）。
     *
     * ## 为什么不能把两通道拼成一段文本再跑
     *
     * 判据的**通道适用性并不一致**（逐条回源码确认）：
     *  - **保留 token（A）与空输出（C）判「两通道合并」**：A 是 logits 塌的确定性证据，
     *    思考通道同样会吐 `<unusedNNNN>` ⇒ 只判正文会**漏检**；C 的原意是「模型完全没交出
     *    任何字母/数字」，若只判正文，**思考型模型在短预算下**（思维链烧光 token、正文尚未
     *    开始）会被 C **误判 BAD** —— 这正是本波要修的误判。
     *  - **通道标记（A′）与重复类（B1 / B2 / detector）只判正文**：marker 是正文流的通道边界
     *    现象；思考里的结构化重复（列点 / 编号 / 复述）是**正常思维形态**，喂进重复判据会
     *    误报 BAD（Wave 49 外部审查 P2 的误判方向）。
     *
     * ⚠️ A 对两通道**分别匹配再合并**（不是先拼接再匹配）—— 避免「正文尾 + 思考头」拼接出
     * 跨通道的伪 `<unusedNNNN>`。
     *
     * @param text 正文（用户可见输出）。
     * @param thinking 思考通道输出；无思考通道的模型传 `""`（此时本函数与 [evaluate] 等价）。
     */
    fun evaluateSplit(text: String, thinking: String): List<CriterionHit> {
        val hits = mutableListOf<CriterionHit>()

        // 判据 A：保留 token（硬，逐命中一条；**两通道分别匹配后合并**，见上方 ⚠️）。
        reservedTokenHits(text).forEach { token ->
            hits += CriterionHit(ID_RESERVED_TOKEN, CriterionSeverity.HARD, evidenceOf(token))
        }
        reservedTokenHits(thinking).forEach { token ->
            hits += CriterionHit(ID_RESERVED_TOKEN, CriterionSeverity.HARD, evidenceOf(token))
        }
        // 判据 A′：非白名单通道标记（软，逐命中一条；**只判正文**）。
        nonWhitelistChannelHits(text).forEach { channel ->
            hits += CriterionHit(ID_CHANNEL_MARKER, CriterionSeverity.SOFT, evidenceOf(channel))
        }
        // 判据 B1：单字符 run（硬；**只判正文**）。
        if (singleCharRunHit(text)) {
            hits += CriterionHit(
                ID_CHAR_RUN,
                CriterionSeverity.HARD,
                "非空白字符连续 run ≥ ${StreamRepetitionDetector.CHAR_RUN_LOOP}",
            )
        }
        // 判据 B2：多字符周期（硬；**只判正文**）。
        charPeriodicRepeat(text)?.let { period ->
            hits += CriterionHit(ID_CHAR_PERIOD, CriterionSeverity.HARD, "尾部周期复读 p=$period")
        }
        // 判据 C：空输出（硬；**两通道合并** —— 两通道都没有字母/数字才算「完全没输出」）。
        if (isEffectivelyEmpty(text) && isEffectivelyEmpty(thinking)) {
            hits += CriterionHit(ID_EMPTY_OUTPUT, CriterionSeverity.HARD, "输出无任何字母/数字字符")
        }
        // detector：句级/字符级循环（判定①②④⑤⑦⑧；**只判正文**）—— 复用实例，零复制阈值。
        val verdict = StreamRepetitionDetector(systemPrompt = null).observeText(text)
        if (verdict is StreamRepetitionDetector.Verdict.LoopDetected) {
            hits += CriterionHit(
                ID_DETECTOR_LOOP,
                CriterionSeverity.HARD,
                evidenceOf(verdict.repeatedSignature),
            )
        }
        return hits
    }

    /** 聚合档位：≥1 HARD ⇒ [ModelHealthVerdict.BAD]；0 HARD ∧ ≥1 SOFT ⇒ DEGRADED；否则 PASS。 */
    fun verdictOf(hits: List<CriterionHit>): ModelHealthVerdict = when {
        hits.any { it.severity == CriterionSeverity.HARD } -> ModelHealthVerdict.BAD
        hits.any { it.severity == CriterionSeverity.SOFT } -> ModelHealthVerdict.DEGRADED
        else -> ModelHealthVerdict.PASS
    }

    /** evidence 规范化：换行压成空格 + 脱敏（复用日志出口的脱敏规则）+ 截断。 */
    private fun evidenceOf(raw: String): String =
        AgentLogStore.sanitizeUserFacing(raw.replace('\n', ' ')).take(EVIDENCE_MAX_CHARS)
}
