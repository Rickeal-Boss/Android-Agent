package com.rickeal.agent.feature.settings.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 沙箱文件子页纯函数的 JVM 单测（Wave 33）。
 *
 * 只覆盖不触碰 Android / Compose / ViewModel 运行时的纯函数：大小格式化、
 * 时间格式化、MIME 决策、文本预览白名单。MimeTypeMap 这类平台查询不在 JVM
 * 测试面（由 [resolveMimeType] 的 lookup 注入解耦）。
 */
class SandboxFilesPureLogicTest {

    // ── formatSandboxBytes ──────────────────────────────────────────────────

    @Test
    fun `字节数各档格式化正确`() {
        assertEquals("0 B", formatSandboxBytes(0L))
        assertEquals("1023 B", formatSandboxBytes(1023L))
        assertEquals("1.0 KB", formatSandboxBytes(1024L))
        assertEquals("1.5 KB", formatSandboxBytes(1536L))
        assertEquals("1.0 MB", formatSandboxBytes(1_048_576L))
        assertEquals("2.4 MB", formatSandboxBytes(2_516_582L))
        assertEquals("1.0 GB", formatSandboxBytes(1_073_741_824L))
        // 小数点必须是点号（Locale.US 显式给定，防欧洲语区逗号）。
        assertTrue(formatSandboxBytes(1536L).contains("1.5"))
    }

    // ── formatSandboxTime ───────────────────────────────────────────────────

    @Test
    fun `相对时间分档正确`() {
        val now = 1_700_000_000_000L
        assertEquals("刚刚", formatSandboxTime(now - 30_000L, now))
        assertEquals("刚刚", formatSandboxTime(now + 5_000L, now)) // 未来时间按「刚刚」兜
        assertEquals("5 分钟前", formatSandboxTime(now - 5 * 60_000L, now))
        assertEquals("3 小时前", formatSandboxTime(now - 3 * 3_600_000L, now))
    }

    @Test
    fun `超过一天回退绝对时间格式`() {
        val now = 1_700_000_000_000L
        val text = formatSandboxTime(now - 26 * 3_600_000L, now)
        // yyyy-MM-dd HH:mm：形状断言而不是写死时区下的具体值。
        assertTrue(Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$""").matches(text))
    }

    // ── resolveMimeType ─────────────────────────────────────────────────────

    @Test
    fun `MIME 命中查询时用查询结果`() {
        assertEquals("text/plain", resolveMimeType("txt") { "text/plain" })
        // 扩展名先归一小写再查：report.MD 与 report.md 必须同判。
        assertEquals("text/markdown", resolveMimeType("MD") { "text/markdown" })
    }

    @Test
    fun `MIME 查不到或无扩展名时兜底 octet-stream`() {
        assertEquals("application/octet-stream", resolveMimeType("xyz") { null })
        assertEquals("application/octet-stream", resolveMimeType("") { "text/plain" })
    }

    // ── sandboxTextPreviewEligible ──────────────────────────────────────────

    @Test
    fun `文本白名单判定`() {
        assertTrue(sandboxTextPreviewEligible("md"))
        assertTrue(sandboxTextPreviewEligible("MD")) // 大小写不敏感
        // 空扩展名（README / LICENSE / Makefile 类）按文本尝试。
        assertTrue(sandboxTextPreviewEligible(""))
        assertFalse(sandboxTextPreviewEligible("png"))
        assertFalse(sandboxTextPreviewEligible("bin"))
    }
}
