package com.rickeal.agent.core.agent.memory

import com.rickeal.agent.core.model.HiddenToolCatalog
import com.rickeal.agent.core.model.truncateSafe

/**
 * 一次记忆检索的结果。
 *
 * [recentFallback] 必须**外显**而不是隐含在 hits 里：空 query 走的是「最近更新」
 * 回退路径，渲染时要在首行明说，否则模型会把「最近 8 条」误读成「这 8 条与我的
 * 问题相关」，进而跳过真正的关键词检索。
 */
data class MemorySearchResult(
    val hits: List<MemorySection>,
    /** true = 空 query 走「最近更新」回退；false = 关键词评分命中。 */
    val recentFallback: Boolean,
)

/**
 * 长期记忆的关键词检索（Wave 34 题 B：记忆从「全量注入提示词」改为「按需检索」）。
 *
 * ## 为什么需要它
 *
 * 此前 `AgentMemory.renderForPrompt()` 把**全部**条目（截断 1200 字符）塞进系统提示词：
 * ① 端侧 4B 的每一个 token 都在挤占工作记忆，而绝大多数条目与当前任务无关；
 * ② 更硬的约束是——记忆内容随 `memory_write` 变化会让 `systemText` 变化，而
 * `systemText` 是引擎的**会话重建判据**，每个 run 首次生成都要全量 re-prefill
 * （4B 秒级；Wave 24 验收点已标红「每轮一次 = 异常」，每 run 一次尚可接受）。
 * 改成 pull 之后，系统提示词里只剩一份**稳定的标题索引**，正文按需用工具取。
 *
 * ## 为什么复用 [HiddenToolCatalog] 的分词/归一化而不是自己写一套
 *
 * `normalize()`（Locale.ROOT 小写 + 折叠空白）与 `tokenize()`（CJK 二元组）是
 * 本仓**唯一**一套经过单测锁定的检索口径：中文没有空格，不按二元组切就会出现
 * 「读取文件」永远命中不了「读取沙箱目录内的文本文件」的召回悬崖。这里若另写
 * 一套，等于在同一个产品里维护两套互相不知道对方存在的检索语义。
 * 二者均 public、零依赖（`core-agent` 已 `api` 依赖 `core-model`），直接复用即可。
 *
 * ## 评分口径
 *
 * 记忆条目与工具条目同构：标题 ↔ 工具名，正文 ↔ 工具描述（记忆没有「分类」与
 * 「检索别名」两路语料，故不设这两路权重）。倍率沿用 [HiddenToolCatalog] 的
 * 相对序（精确匹配 > 名称 > 描述 > 逐词项），理由同其类 KDoc：标题是短标识、
 * 正文是中文长句、噪音天然更高。
 * ```
 * 标题完全等于 query  +200
 * 标题包含 query      +90
 * 正文包含 query      +60
 * 逐词项（长度≥2）    标题 +30 / 正文 +8
 * ```
 *
 * ⚠️ **命中门维持 `score > 0`，本轮不改阈值** —— 与 [HiddenToolCatalog] 同源的
 * 裁决（见 `ToolDisclosure.kt` 类 KDoc）：本仓语料下任何有意义的 coverage 阈值都会
 * 误删正确命中（正文单命中的相对分天然很低），调阈值必须拿真机语料说话。
 * [coverage] 因此只作为「相关度 xx%」**展示**给模型，不参与判定。
 *
 * ## 空 query 为什么与工具检索**刻意不同**
 *
 * [HiddenToolCatalog.search] 对空 query 返回空（防止模型退化成「先空搜一次」把
 * 目录全量捞出）。记忆这里反其道行之：返回**最近更新**的 [RECENT_LIMIT] 条，并在
 * 结果首行写明「未给关键词，返回最近 N 条」。理由是两者代价结构不同——工具目录
 * 全量泄漏只是浪费 token，而「模型想回忆却什么也拿不到」会让它直接退化成
 * `memory_read` 全量读（正是本波要消灭的行为），或者干脆编造记忆。给一份带明确
 * 声明的兜底列表，比给一个空结果更能把模型留在 pull 路径上。
 */
object MemorySearch {

    /** 关键词检索的默认返回条数（工具未传 limit 时）。 */
    const val DEFAULT_LIMIT = 5

    /** 空 query 回退「最近更新」的条数。 */
    const val RECENT_LIMIT = 8

    /** 整段渲染的字符预算（叠加在 `AgentPolicy.maxToolOutputChars` 之内，见 [render]）。 */
    const val MAX_RENDER_CHARS = 1200

    /** 单条正文的渲染预算：检索结果只用于「哪条相关」，看全文走 `memory_read`。 */
    private const val MAX_HIT_CHARS = 160

    private const val ELLIPSIS = "…"

    // 评分权重（相对量，非概率）。推导见类 KDoc；改动会同时影响排序与 coverage 展示值。
    private const val W_TITLE_EXACT = 200
    private const val W_TITLE_CONTAINS = 90
    private const val W_BODY_CONTAINS = 60
    private const val W_TERM_TITLE = 30
    private const val W_TERM_BODY = 8

    /**
     * 检索长期记忆。
     *
     * @param query 关键词；**空白串走「最近更新」回退**（语义见类 KDoc，不是「列出全部」）。
     * @param limit 返回条数上限（≤0 时按 [DEFAULT_LIMIT]）。
     */
    fun search(
        sections: List<MemorySection>,
        query: String,
        limit: Int = DEFAULT_LIMIT,
    ): MemorySearchResult {
        val normalized = HiddenToolCatalog.normalize(query)
        if (normalized.isEmpty()) {
            return MemorySearchResult(recent(sections, RECENT_LIMIT), recentFallback = true)
        }
        val cap = if (limit <= 0) DEFAULT_LIMIT else limit
        val terms = HiddenToolCatalog.tokenize(normalized)
        val hits = sections
            .map { it to score(it, normalized, terms) }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<MemorySection, Int>> { it.second }
                    // 同分按更新时间倒序：模型更可能想要最近沉淀的那条。
                    .thenByDescending { it.first.updatedAtMillis }
            )
            .take(cap)
            .map { it.first }
        return MemorySearchResult(hits, recentFallback = false)
    }

    /**
     * 把检索结果渲染成**文本**，走既有工具结果回灌通道回给模型。
     *
     * 渲染成文本而不是结构化对象是刻意的（与 `HiddenToolCatalog.renderHits` 同款）：
     * 它随 `ToolResult.output` 进对话历史，自动受 `AgentPolicy.maxToolOutputChars`
     * 截断，**不新增任何协议面**。这里的 [MAX_RENDER_CHARS] 是叠加在 policy 之上的
     * 更小预算——检索结果一次可能有多条，留足空间给后续轮次的其它内容。
     *
     * 截断时附「还有 N 条未显示」，且**至少渲染一条**（预算再小也不返回空串 ——
     * 空结果会让模型以为检索失败而重试同一查询）。
     */
    fun render(result: MemorySearchResult, query: String): String {
        val hits = result.hits
        if (hits.isEmpty()) {
            return if (result.recentFallback) {
                "记忆为空，没有可检索的条目。"
            } else {
                "没有匹配「$query」的记忆。换更宽泛的关键词再试，或用 memory_read 看全部记忆。"
            }
        }
        val head = if (result.recentFallback) {
            "未给关键词，返回最近 ${hits.size} 条："
        } else {
            "匹配「$query」的记忆（${hits.size} 条）："
        }
        val lines = ArrayList<String>(hits.size)
        var used = head.length
        var omitted = 0
        for (hit in hits) {
            val line = renderLine(hit, query, result.recentFallback)
            val cost = line.length + 1 // +1 = 行尾换行
            // lines 为空时无条件放行：保证至少渲染一条。
            if (lines.isNotEmpty() && used + cost > MAX_RENDER_CHARS) {
                omitted = hits.size - lines.size
                break
            }
            lines.add(line)
            used += cost
        }
        val sb = StringBuilder(head).append('\n').append(lines.joinToString("\n"))
        if (omitted > 0) {
            sb.append("\n").append(ELLIPSIS).append("（还有 ").append(omitted).append(" 条未显示，缩小关键词范围再查）")
        }
        // 逃生舱：检索结果只有摘要，看全文仍然靠 memory_read —— 这行提示是
        // 「pull 化之后模型怎么拿到正文」的唯一出口，不能省。
        sb.append("\n用 memory_read 看全文。")
        return sb.toString()
    }

    /**
     * 单条相关度 = 原始分 / 「该 query 的理论最高分」，落在 `[0,1]`。
     *
     * 只作**展示**（渲染成「相关度 xx%」供模型在多个命中间取舍），**不参与命中判定** ——
     * 命中门是 [search] 里的 `score > 0`（理由见类 KDoc）。空白 query 返回 0.0。
     */
    fun coverage(section: MemorySection, query: String): Double {
        val normalized = HiddenToolCatalog.normalize(query)
        if (normalized.isEmpty()) return 0.0
        val terms = HiddenToolCatalog.tokenize(normalized)
        val max = maxScore(terms)
        if (max <= 0) return 0.0
        return score(section, normalized, terms).toDouble() / max
    }

    /** 最近更新的 [cap] 条。同 `updatedAtMillis` 时按文件内位置靠后者优先（后写即更新）。 */
    private fun recent(sections: List<MemorySection>, cap: Int): List<MemorySection> =
        sections
            .mapIndexed { index, section -> section to index }
            .sortedWith(
                compareByDescending<Pair<MemorySection, Int>> { it.first.updatedAtMillis }
                    .thenByDescending { it.second }
            )
            .take(cap)
            .map { it.first }

    private fun renderLine(hit: MemorySection, query: String, recentFallback: Boolean): String {
        val title = hit.title.replace("\n", " ")
        val body = clip(hit.content.replace("\n", " ").trim(), MAX_HIT_CHARS)
        return if (recentFallback) {
            // 回退路径没有评分：不展示「相关度 0%」，那会被读成「全部不相关」。
            "- [$title] $body"
        } else {
            "- [$title] $body（相关度 ${(coverage(hit, query) * 100).toInt()}%）"
        }
    }

    /**
     * 代理对安全截断（Wave 35 D1，与全仓其余各处同一口径）：本输出经 `memory_search`
     * 的工具结果**回灌模型上下文并上屏**，裸 `take` 停在半个代理对上时就是一个 U+FFFD ——
     * 那会被模型当成「内容本身坏了」，比少显示一个字形糟得多。
     */
    private fun clip(text: String, maxChars: Int): String =
        if (text.length <= maxChars) text else text.truncateSafe(maxChars) + ELLIPSIS

    /** 该 query 下任何单一条目能达到的分数上界（用于 [coverage] 归一化）。 */
    private fun maxScore(terms: List<String>): Int =
        W_TITLE_EXACT + W_TITLE_CONTAINS + W_BODY_CONTAINS +
            terms.count { it.length >= 2 } * (W_TERM_TITLE + W_TERM_BODY)

    private fun score(section: MemorySection, normalizedQuery: String, terms: List<String>): Int {
        val title = HiddenToolCatalog.normalize(section.title)
        val body = HiddenToolCatalog.normalize(section.content)
        var score = 0
        if (title == normalizedQuery) score += W_TITLE_EXACT
        if (title.contains(normalizedQuery)) score += W_TITLE_CONTAINS
        if (body.contains(normalizedQuery)) score += W_BODY_CONTAINS
        for (term in terms) {
            if (term.length < 2) continue
            if (title.contains(term)) score += W_TERM_TITLE
            if (body.contains(term)) score += W_TERM_BODY
        }
        return score
    }
}
