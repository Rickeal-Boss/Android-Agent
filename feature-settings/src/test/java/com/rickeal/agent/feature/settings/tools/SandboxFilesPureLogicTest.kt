package com.rickeal.agent.feature.settings.tools

import com.rickeal.agent.core.data.SandboxFileInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 沙箱文件子页纯函数的 JVM 单测（Wave 33）。
 *
 * 只覆盖不触碰 Android / Compose / ViewModel 运行时的纯函数：大小格式化、
 * 条目数文案、时间格式化、MIME 决策、文本预览白名单，以及预览结果的**对账归约**
 * （[SandboxFilesUiState.commitPreview] —— VM 的协程时序无法在 JVM 上驱动，
 * 本源集无 coroutines-test，所以把判定逻辑抽成纯函数来钉住语义）。
 * MimeTypeMap 这类平台查询不在 JVM 测试面（由 [resolveMimeType] 的 lookup 注入解耦）。
 */
class SandboxFilesPureLogicTest {

    /** 造一个只带名字的条目（其余字段与本测试无关）。 */
    private fun fileInfo(name: String): SandboxFileInfo = SandboxFileInfo(
        name = name,
        relativePath = name,
        sizeBytes = 1L,
        lastModifiedMillis = 0L,
        isDirectory = false,
        extension = name.substringAfterLast('.', ""),
    )

    private fun preview(info: SandboxFileInfo, text: String?, loading: Boolean = false) =
        SandboxFilePreview(info = info, text = text, truncated = false, loading = loading)

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

    // ── sandboxEntryCountText ───────────────────────────────────────────────

    @Test
    fun `条目数文案在截断时带加号`() {
        // 入口卡与子页共用这一个实现：超限时两边必须都说「N+」，不能一个 200+
        // 一个 200（A7 计数口径打架的根源就是各写一遍）。
        assertEquals("200+ 项", sandboxEntryCountText(200, truncated = true))
        assertEquals("12 项", sandboxEntryCountText(12, truncated = false))
        assertEquals("0 项", sandboxEntryCountText(0, truncated = false))
    }

    // ── SandboxFilesUiState.commitPreview（预览竞态对账）─────────────────────

    @Test
    fun `迟到的旧预览结果不得覆盖当前预览条目`() {
        val a = fileInfo("a.txt")
        val b = fileInfo("b.txt")
        // 用户先点 A、再点 B：画面上已经是 B 的 loading 态。
        val showing = SandboxFilesUiState(selectedPreview = preview(b, null, loading = true))

        // A 更慢完成 ⇒ 必须丢弃，否则弹层变成「标题 B、内容 A」。
        assertSame(showing, showing.commitPreview(preview(a, "A 的内容")))
        // B 自己的结果照常落地。
        assertEquals("B 的内容", showing.commitPreview(preview(b, "B 的内容")).selectedPreview?.text)
    }

    @Test
    fun `读取期间关闭弹层后迟到的结果不得把它重新弹出`() {
        val a = fileInfo("a.txt")
        val dismissed = SandboxFilesUiState(selectedPreview = null)
        assertNull(dismissed.commitPreview(preview(a, "A 的内容")).selectedPreview)
    }

    // ── joinRelativePath / parentRelativePath（逐层下钻路径拼接，Wave 36）──────

    @Test
    fun `joinRelativePath 空父路径直接返回名字`() {
        assertEquals("a.txt", joinRelativePath("", "a.txt"))
        assertEquals("sub", joinRelativePath("", "sub"))
        assertEquals("sub/a.txt", joinRelativePath("sub", "a.txt"))
        assertEquals("a/b/c", joinRelativePath("a/b", "c"))
    }

    @Test
    fun `parentRelativePath 逐层上溯到根`() {
        assertEquals("", parentRelativePath(""))
        assertEquals("", parentRelativePath("top"))
        assertEquals("a", parentRelativePath("a/b"))
        assertEquals("a/b", parentRelativePath("a/b/c"))
        // 与 joinRelativePath 互为逆运算。
        assertEquals("a/b", parentRelativePath(joinRelativePath("a/b", "c")))
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
