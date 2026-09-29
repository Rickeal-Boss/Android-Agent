package com.rickeal.agent.core.agent.tools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [FileReadPager]（file_read 的读取核心，Wave 31）单元测试。
 *
 * 覆盖点对应简报「改动 B」的要求：offset=0（等价旧行为）/ 中间段 / 尾部 / offset 越界 /
 * 负数 / limit 超上限。全程纯 JVM（只碰 `java.io.File`），不构造 [ToolContext]
 * —— 因此读取逻辑被刻意下沉到纯对象 [FileReadPager]。
 */
class FileReadPagerTest {

    private fun tempFile(content: String): File =
        File.createTempFile("cam-p-file-read", ".txt").apply { writeText(content) }

    // ── 默认路径（零回归）────────────────────────────────────────────────────

    @Test
    fun `默认路径短文件返回完整内容且无标记`() {
        val file = tempFile("hello 世界")
        try {
            assertEquals("hello 世界", FileReadPager.readHead(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `默认路径空文件返回空串`() {
        val file = tempFile("")
        try {
            assertEquals("", FileReadPager.readHead(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `限量截断不切出半个代理对 —— 尾部不留孤立代理（Wave 35 D1）`() {
        // 原实现注释自认「keep 落点可能切开 UTF-16 代理对，纯修饰性，不处理」——
        // 这个判断是错的：本输出会原样上屏并在工具卡里展示，尾部一个 U+FFFD 就是
        // 用户可见的乱码。emoji 全是代理对，落点奇偶两种情形都会被这份语料覆盖。
        val limit = FileReadPager.READ_LIMIT_CHARS
        val file = tempFile("\uD83D\uDE00".repeat(limit) + "tail")
        try {
            val out = FileReadPager.readHead(file)
            val content = out.substringBeforeLast('\n')
            assertTrue(!out.contains('\uFFFD'), "不得产出替换字符：$out")
            if (content.isNotEmpty()) {
                val tail = content.last()
                val lone = tail.isHighSurrogate() ||
                    (tail.isLowSurrogate() && (content.length < 2 || !content[content.length - 2].isHighSurrogate()))
                assertTrue(!lone, "内容尾部留下孤立代理：$content")
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `默认路径超限时保留旧截断标记文案且不超限量`() {
        val limit = FileReadPager.READ_LIMIT_CHARS
        val file = tempFile("a".repeat(limit + 10))
        try {
            val out = FileReadPager.readHead(file)
            // 逐字节回归钉：旧文案原样保留。
            assertTrue(out.contains("仅载入前 $limit 字符，其余未读"))
            assertTrue(out.length <= limit)
        } finally {
            file.delete()
        }
    }

    // ── 分页路径 ─────────────────────────────────────────────────────────────

    @Test
    fun `分页读取中间段并给出下一段 offset`() {
        val file = tempFile("0123456789")
        try {
            val out = FileReadPager.readRange(file, offset = 2, limit = 3)
            assertTrue(out.startsWith("234"), "应读到 [2,5) 段：$out")
            assertTrue(out.contains("offset=5"), "应给出续读 offset=5：$out")
        } finally {
            file.delete()
        }
    }

    @Test
    fun `分页读取尾部报告已到末尾`() {
        val file = tempFile("0123456789")
        try {
            val out = FileReadPager.readRange(file, offset = 7, limit = 100)
            assertTrue(out.startsWith("789"))
            assertTrue(out.contains("已读到末尾"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `offset 越界给出明确提示`() {
        val file = tempFile("0123456789")
        try {
            val out = FileReadPager.readRange(file, offset = 100, limit = 10)
            assertTrue(out.contains("已超出文件长度"), "越界应给出可行动提示：$out")
        } finally {
            file.delete()
        }
    }

    @Test
    fun `短文件时分页与默认路径输出一致`() {
        // offset=0 且 limit=文件长度：两者都应返回完整原文（无标记）。
        val file = tempFile("0123456789")
        try {
            assertEquals(FileReadPager.readHead(file), FileReadPager.readRange(file, offset = 0, limit = 10))
        } finally {
            file.delete()
        }
    }

    // ── 参数钳制 ─────────────────────────────────────────────────────────────

    @Test
    fun `负数 offset 归一为 0`() {
        assertEquals(0, FileReadPager.normalizeOffset(-5))
        assertEquals(0, FileReadPager.normalizeOffset(0))
        assertEquals(7, FileReadPager.normalizeOffset(7))
    }

    @Test
    fun `limit 归一化：非正取默认、超上限取上限`() {
        val limit = FileReadPager.READ_LIMIT_CHARS
        assertEquals(limit, FileReadPager.normalizeLimit(0))
        assertEquals(limit, FileReadPager.normalizeLimit(-3))
        assertEquals(limit, FileReadPager.normalizeLimit(999_999))
        assertEquals(100, FileReadPager.normalizeLimit(100))
    }

    // ── 长度不变量边界（P2-C3）────────────────────────────────────────────────

    @Test
    fun `limit 为 1 时内容不超 limit 且完整输出不超 limit 加指引预算`() {
        // keep = limit − MARKER_BUDGET_CHARS − 1 ≤ 0 ⇒ 不预留指引空间：完整输出可略超 limit，
        // 但恒 ≤ limit + 1（换行）+ 指引长度（≤ MARKER_BUDGET_CHARS）。见 readRange 的 KDoc。
        val file = tempFile("0123456789")
        val limit = 1
        try {
            val out = FileReadPager.readRange(file, offset = 0, limit = limit)
            // 内容部分（首个换行前）恒 ≤ limit。
            assertTrue(out.substringBefore('\n').length <= limit, "内容部分应 ≤ limit：$out")
            // 完整输出上界 = limit + 换行 + 指引预算。
            assertTrue(
                out.length <= limit + 1 + FileReadPager.MARKER_BUDGET_CHARS,
                "完整输出应 ≤ limit+1+指引预算：len=${out.length}",
            )
            assertTrue(out.contains("offset=1"), "应给出续读指引：$out")
        } finally {
            file.delete()
        }
    }

    @Test
    fun `limit 为 0 钳为默认后完整输出不超 READ_LIMIT_CHARS`() {
        val limit = FileReadPager.normalizeLimit(0)
        assertEquals(FileReadPager.READ_LIMIT_CHARS, limit)
        val file = tempFile("a".repeat(limit + 10))
        try {
            val out = FileReadPager.readRange(file, offset = 0, limit = limit)
            // keep = limit − MARKER_BUDGET_CHARS − 1 > 0 ⇒ 预留指引空间 ⇒ 完整输出 ≤ limit。
            assertTrue(
                out.length <= FileReadPager.READ_LIMIT_CHARS,
                "默认 limit 下完整输出应 ≤ READ_LIMIT_CHARS：len=${out.length}",
            )
            assertTrue(out.substringBefore('\n').length <= limit)
        } finally {
            file.delete()
        }
    }
}
