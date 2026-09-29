package com.rickeal.agent.core.agent.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [MemorySearch]（Wave 34 题 B，记忆 pull 化的检索核心）单元测试。
 *
 * 覆盖点对应简报要求：命中 / 排序 / 空 query 回退 / 超长截断，另加两条**回归护栏**：
 *  - CJK 二元组召回（「读取文件」必须命中「读取沙箱目录内的文本文件」）—— 一旦有人
 *    把 `tokenize()` 换成 `split(' ')`，这条会立刻红；
 *  - 渲染预算再小也至少渲染一条（空结果会被模型读成检索失败而重试同一查询）。
 *
 * 全程纯 JVM：只碰 [MemorySection] 数据类与本对象，不构造 [AgentMemory]（那个要 File），
 * 因此检索/渲染逻辑被刻意下沉到纯对象 [MemorySearch]。
 */
class MemorySearchTest {

    private fun section(title: String, content: String, updatedAt: Long = 0L): MemorySection =
        MemorySection(title = title, content = content, updatedAtMillis = updatedAt)

    // ── 命中 ────────────────────────────────────────────────────────────────

    @Test
    fun `标题命中优先于正文命中`() {
        val sections = listOf(
            section("用户偏好", "回答用中文"),
            section("项目约定", "分支命名走 harness-improve"),
            section("闲聊", "今天天气不错"),
        )
        val result = MemorySearch.search(sections, "偏好")
        assertEquals(listOf("用户偏好"), result.hits.map { it.title })
        assertEquals(false, result.recentFallback)
    }

    @Test
    fun `正文命中也能召回`() {
        val sections = listOf(section("用户偏好", "回答用中文，不要客套"))
        assertEquals(listOf("用户偏好"), MemorySearch.search(sections, "中文").hits.map { it.title })
    }

    @Test
    fun `全不命中返回空且不带回退标记`() {
        val sections = listOf(section("用户偏好", "回答用中文"))
        val result = MemorySearch.search(sections, "绝不存在的关键词")
        assertTrue(result.hits.isEmpty())
        assertEquals(false, result.recentFallback)
    }

    @Test
    fun `CJK 二元组召回 —— 读取文件命中读取沙箱目录内的文本文件`() {
        // 换成分词 split(' ') 的话「读取文件」是**一个** term，title/正文都不包含它
        // → 全项 0 分 → 零命中。这条用例就是那道召回悬崖的护栏。
        val sections = listOf(section("沙箱笔记", "读取沙箱目录内的文本文件"))
        assertTrue(
            MemorySearch.search(sections, "读取文件").hits.isNotEmpty(),
            "CJK 二元组必须让「读取文件」命中「读取沙箱目录内的文本文件」",
        )
    }

    // ── 排序 ────────────────────────────────────────────────────────────────

    @Test
    fun `标题完全匹配排在子串匹配之前`() {
        val sections = listOf(
            section("记忆规范", "标题唯一键"),
            section("记忆", "跨会话沉淀"),
        )
        assertEquals(listOf("记忆", "记忆规范"), MemorySearch.search(sections, "记忆").hits.map { it.title })
    }

    @Test
    fun `同分按更新时间倒序`() {
        val sections = listOf(
            section("旧偏好", "回答用中文", updatedAt = 100L),
            section("新偏好", "回答用中文", updatedAt = 900L),
        )
        assertEquals(listOf("新偏好", "旧偏好"), MemorySearch.search(sections, "中文").hits.map { it.title })
    }

    @Test
    fun `limit 生效且非法值回落默认`() {
        val sections = (1..10).map { section("偏好 $it", "回答用中文") }
        assertEquals(MemorySearch.DEFAULT_LIMIT, MemorySearch.search(sections, "偏好").hits.size)
        assertEquals(2, MemorySearch.search(sections, "偏好", limit = 2).hits.size)
        assertEquals(MemorySearch.DEFAULT_LIMIT, MemorySearch.search(sections, "偏好", limit = 0).hits.size)
    }

    // ── 空 query 回退 ───────────────────────────────────────────────────────

    @Test
    fun `空 query 返回最近更新的八条`() {
        val sections = (1..12).map { section("条目 $it", "内容 $it", updatedAt = it.toLong()) }
        val result = MemorySearch.search(sections, "")
        assertEquals(true, result.recentFallback)
        assertEquals(MemorySearch.RECENT_LIMIT, result.hits.size)
        // 最近更新 = updatedAtMillis 最大的一批，且内部按更新时间倒序。
        assertEquals((12 downTo 5).map { "条目 $it" }, result.hits.map { it.title })
    }

    @Test
    fun `空白 query 与空串同口径`() {
        val sections = listOf(section("条目 1", "内容 1", updatedAt = 1L))
        assertEquals(true, MemorySearch.search(sections, "   ").recentFallback)
    }

    @Test
    fun `空 query 渲染首行写明未给关键词`() {
        val sections = (1..3).map { section("条目 $it", "内容 $it", updatedAt = it.toLong()) }
        val rendered = MemorySearch.render(MemorySearch.search(sections, ""), "")
        assertTrue(
            rendered.startsWith("未给关键词，返回最近 3 条："),
            "空 query 结果必须自证语义，实际首行：$rendered",
        )
        // 回退路径没有评分，不能展示「相关度 0%」——那会被读成「全都不相关」。
        assertTrue(!rendered.contains("相关度"), rendered)
    }

    @Test
    fun `空记忆空 query 给出可行动提示`() {
        val rendered = MemorySearch.render(MemorySearch.search(emptyList(), ""), "")
        assertEquals("记忆为空，没有可检索的条目。", rendered)
    }

    @Test
    fun `关键词无命中渲染出换关键词提示`() {
        val rendered = MemorySearch.render(MemorySearch.search(listOf(section("a", "b")), "不存在"), "不存在")
        assertTrue(rendered.contains("没有匹配「不存在」的记忆"), rendered)
        assertTrue(rendered.contains("memory_read"), rendered)
    }

    // ── 渲染与截断 ──────────────────────────────────────────────────────────

    @Test
    fun `超长正文在结果里被裁剪`() {
        val long = "记".repeat(5000)
        val rendered = MemorySearch.render(MemorySearch.search(listOf(section("长条目", long)), "长条目"), "长条目")
        assertTrue(rendered.contains("…"), "超长正文必须带省略号")
        assertTrue(rendered.length < 400, "单条渲染必须远小于原正文长度，实际 ${rendered.length}")
    }

    @Test
    fun `整体超出渲染预算时丢弃尾部并标明条数`() {
        val sections = (1..20).map { section("条目 $it", "记".repeat(400), updatedAt = it.toLong()) }
        val result = MemorySearch.search(sections, "条目", limit = 20)
        val rendered = MemorySearch.render(result, "条目")
        assertTrue(rendered.contains("条未显示"), "超预算必须写明还有几条没显示：$rendered")
        assertTrue(
            rendered.length <= MemorySearch.MAX_RENDER_CHARS + 200,
            "渲染长度应受预算约束，实际 ${rendered.length}",
        )
    }

    @Test
    fun `渲染预算再小也至少渲染一条`() {
        // 空结果会被模型读成「检索失败」从而重试同一查询 —— 这里必须保底一条。
        val sections = (1..50).map { section("条目 $it", "记".repeat(400)) }
        val rendered = MemorySearch.render(MemorySearch.search(sections, "条目", limit = 50), "条目")
        val bodyLines = rendered.lineSequence().filter { it.startsWith("- [") }.toList()
        assertTrue(bodyLines.isNotEmpty(), "至少渲染一条命中")
        assertTrue(bodyLines.size < 50, "预算生效，未全量渲染")
    }

    @Test
    fun `渲染尾部带看全文出口`() {
        val rendered = MemorySearch.render(MemorySearch.search(listOf(section("偏好", "回答用中文")), "偏好"), "偏好")
        assertTrue(rendered.trimEnd().endsWith("用 memory_read 看全文。"), rendered)
    }

    @Test
    fun `超长正文含 emoji 时截断不切出半个代理对（Wave 35 D1）`() {
        // memory_search 的输出会经工具结果回灌模型上下文并原样上屏 —— 停在半个代理对上
        // 就是一个 U+FFFD，会被模型读成「内容损坏」。这里用 0..3 四种前缀长度把
        // 「落点在高位代理」和「落点在低位代理」两种情形都覆盖到（MAX_HIT_CHARS=160 为偶数）。
        for (prefix in 0..3) {
            val sections = listOf(section("偏好", "a".repeat(prefix) + "\uD83D\uDE00".repeat(200)))
            val rendered = MemorySearch.render(MemorySearch.search(sections, "偏好"), "偏好")
            assertTrue(!rendered.contains('\uFFFD'), "prefix=$prefix 产出替换字符：$rendered")
            val hitLine = rendered.lineSequence().first { it.startsWith("- [") }
            // 「- [标题] 正文…（相关度 N%）」→ 剥掉相关度尾巴与省略号，检查**正文**尾部。
            val clipped = hitLine.substringAfter("] ").substringBefore("（相关度").removeSuffix("…")
            assertTrue(clipped.isNotEmpty(), "prefix=$prefix 正文不应为空：$hitLine")
            val tail = clipped.last()
            val lone = tail.isHighSurrogate() ||
                (tail.isLowSurrogate() && (clipped.length < 2 || !clipped[clipped.length - 2].isHighSurrogate()))
            assertTrue(!lone, "prefix=$prefix 正文尾部留下孤立代理：$clipped")
        }
    }

    @Test
    fun `相关度展示值落在 0 到 100 之间`() {
        val hit = section("记忆", "记忆是跨会话沉淀")
        val percent = (MemorySearch.coverage(hit, "记忆") * 100).toInt()
        assertTrue(percent in 1..100, "相关度应在 (0,100]，实际 $percent")
        assertEquals(0, (MemorySearch.coverage(hit, "") * 100).toInt())
    }
}
