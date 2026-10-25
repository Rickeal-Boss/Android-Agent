package com.rickeal.agent.core.data

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SandboxFileScanner] 的 JVM 纯函数单测：只依赖 `java.io.File`，不触任何 Android 类。
 *
 * 临时目录用「createTempFile → 删掉 → mkdirs()」构造（junit4 无 TempDir 规则，
 * 不为此引新依赖）；测试目录各自独立命名，互不串扰。
 */
class SandboxFileScannerTest {

    /** 建一个独立的临时目录。 */
    private fun newTempDir(tag: String): File =
        File.createTempFile("sandbox_scan_$tag", null).apply {
            delete()
            mkdirs()
        }

    @Test
    fun `根目录不存在时返回空结果`() {
        val root = File(newTempDir("missing").parentFile, "sandbox_scan_missing_${System.nanoTime()}")
        val result = SandboxFileScanner.scan(root)
        assertTrue(result.entries.isEmpty())
        assertEquals(0, result.totalEntries)
        assertFalse(result.truncated)
    }

    @Test
    fun `空目录返回空结果`() {
        val result = SandboxFileScanner.scan(newTempDir("empty"))
        assertTrue(result.entries.isEmpty())
        assertEquals(0, result.totalEntries)
        assertFalse(result.truncated)
    }

    @Test
    fun `普通文件按修改时间降序排列`() {
        val root = newTempDir("order")
        val base = System.currentTimeMillis()
        File(root, "old.txt").apply { writeText("a"); setLastModified(base - 10_000) }
        File(root, "new.md").apply { writeText("b"); setLastModified(base) }
        File(root, "mid.log").apply { writeText("c"); setLastModified(base - 5_000) }

        val result = SandboxFileScanner.scan(root)
        assertEquals(listOf("new.md", "mid.log", "old.txt"), result.entries.map { it.name })
        assertEquals(3, result.totalEntries)
        assertFalse(result.truncated)
        // 文件条目：大小为字节数、扩展名小写、非目录。
        val md = result.entries.first { it.name == "new.md" }
        assertFalse(md.isDirectory)
        assertEquals(1L, md.sizeBytes)
        assertEquals("md", md.extension)
    }

    @Test
    fun `目录条目不递归且带直接子项计数`() {
        val root = newTempDir("dirs")
        val dir = File(root, "sub").apply { mkdirs() }
        File(dir, "inner.txt").writeText("inner")
        File(root, "top.txt").writeText("top")

        val result = SandboxFileScanner.scan(root)
        val entry = result.entries.first { it.name == "sub" }
        assertTrue(entry.isDirectory)
        assertEquals(0L, entry.sizeBytes)
        assertEquals(1, entry.childCount)
        assertEquals("", entry.extension)
        // 不递归：inner.txt 不出现在结果里。
        assertEquals(2, result.totalEntries)
        assertEquals(setOf("sub", "top.txt"), result.entries.map { it.name }.toSet())
    }

    @Test
    fun `隐藏文件与原子写临时文件被排除`() {
        val root = newTempDir("excluded")
        File(root, ".hidden").writeText("h")
        // FileWriteTool 的临时名形态：原名 + ".tmp_" + 纳秒。
        File(root, "report.md.tmp_123456789").writeText("tmp")
        File(root, "report.md").writeText("real")

        val result = SandboxFileScanner.scan(root)
        assertEquals(listOf("report.md"), result.entries.map { it.name })
        assertEquals(1, result.totalEntries)
    }

    @Test
    fun `名字里含 tmp_ 但不是原子写临时文件的合法名必须保留`() {
        // 判据必须是「.tmp_ + 全数字」的**后缀形态**而不是子串匹配：子串匹配会把
        // 下面这些用户 / agent 起的合法名静默藏掉（文件凭空消失且无日志，D2）。
        assertTrue(SandboxFileScanner.isAtomicWriteTemp("report.md.tmp_123456789"))
        assertTrue(SandboxFileScanner.isAtomicWriteTemp("a.tmp_0"))
        assertFalse(SandboxFileScanner.isAtomicWriteTemp("report.tmp_backup.txt"))
        assertFalse(SandboxFileScanner.isAtomicWriteTemp("tmp_notes.md"))
        assertFalse(SandboxFileScanner.isAtomicWriteTemp("notes.tmp_")) // 空时间戳不是临时名
        assertFalse(SandboxFileScanner.isAtomicWriteTemp("notes.tmp_v2"))

        val root = newTempDir("tmp_legal")
        File(root, "report.tmp_backup.txt").writeText("legal")
        File(root, "x.tmp_123456").writeText("temp")
        File(root, "keep.md").writeText("keep")

        val result = SandboxFileScanner.scan(root)
        assertEquals(
            setOf("report.tmp_backup.txt", "keep.md"),
            result.entries.map { it.name }.toSet(),
        )
        assertEquals(2, result.totalEntries)
    }

    @Test
    fun `超限时截断且 totalEntries 含全部条目`() {
        val root = newTempDir("limit")
        repeat(5) { index -> File(root, "f$index.txt").writeText("x") }

        val result = SandboxFileScanner.scan(root, limit = 3)
        assertEquals(3, result.entries.size)
        assertEquals(5, result.totalEntries)
        assertTrue(result.truncated)
    }

    @Test
    fun `装饰排序去装饰后 mtime 降序与名字 tiebreak 及 childCount 保持不变`() {
        // W80-04 装饰-排序-去装饰重构的回归锚：stat 缓存后排序语义必须与旧实现
        // （比较子内现 stat）逐条目等价 —— mtime 降序、同毫秒名字升序 tiebreak、
        // 目录 childCount 正确、隐藏文件与原子写临时文件过滤口径不变。
        val root = newTempDir("w80_decorated")
        val base = System.currentTimeMillis()
        // 乱序落盘的 mtime：落盘顺序 ≠ 期望输出顺序（旧实现的排序 selector 现读 stat，
        // 新实现读缓存值 —— 两边都必须产出同一条链）。
        File(root, "b_same.txt").apply { writeText("1"); setLastModified(base) }
        File(root, "a_same.txt").apply { writeText("2"); setLastModified(base) }
        File(root, "z_older.txt").apply { writeText("3"); setLastModified(base - 20_000) }
        File(root, "m_mid.txt").apply { writeText("4"); setLastModified(base - 10_000) }
        // 目录：childCount 由装饰阶段的 listFiles 一次性读出，去装饰后不得丢。
        // （setLastModified 必须在写完子文件之后 —— 写子文件会刷新目录 mtime。）
        val dir = File(root, "folder").apply { mkdirs() }
        File(dir, "one.txt").writeText("x")
        File(dir, "two.txt").writeText("y")
        File(dir, "three.txt").writeText("z")
        dir.setLastModified(base - 30_000)
        // 过滤口径不变的对照项：隐藏文件 + 原子写临时名。
        File(root, ".hidden").writeText("h")
        File(root, "note.md.tmp_999888").writeText("tmp")

        val result = SandboxFileScanner.scan(root)
        assertEquals(
            listOf("a_same.txt", "b_same.txt", "m_mid.txt", "z_older.txt", "folder"),
            result.entries.map { it.name },
            "mtime 降序 + 同毫秒名字升序 tiebreak 必须保持",
        )
        assertEquals(5, result.totalEntries, "过滤后的有效条目数口径不变")
        assertFalse(result.truncated)

        val folder = result.entries.first { it.name == "folder" }
        assertTrue(folder.isDirectory)
        assertEquals(3, folder.childCount, "去装饰后目录 childCount 不得丢失")
        assertEquals(0L, folder.sizeBytes)
        assertEquals("", folder.extension)

        val sameA = result.entries.first { it.name == "a_same.txt" }
        assertEquals(base, sameA.lastModifiedMillis, "展示条目的 lastModifiedMillis 必须来自装饰阶段缓存值")
    }

    // ── resolveWithinSandbox（路径安全，Wave 36）──────────────────────────────

    @Test
    fun `resolveWithinSandbox 接受合法相对路径`() {
        val root = newTempDir("resolve_ok")
        File(root, "sub").mkdirs()
        // 空串 → 沙箱根自身。
        assertEquals(root, SandboxFileScanner.resolveWithinSandbox(root, ""))
        // 单层 / 多层相对路径 → 规范化后的目标（canonical 比对）。
        assertEquals(
            File(root, "sub").canonicalFile,
            SandboxFileScanner.resolveWithinSandbox(root, "sub"),
        )
        assertEquals(
            File(root, "a/b").canonicalFile,
            SandboxFileScanner.resolveWithinSandbox(root, "a/b"),
        )
    }

    @Test
    fun `resolveWithinSandbox 拒绝逃逸与非法段`() {
        val root = newTempDir("resolve_bad")
        val illegal = listOf(
            "..",            // 直接上溯
            "a/../..",       // 多段上溯
            "sub/../../etc", // 穿越到沙箱外
            "/abs",          // 绝对路径（POSIX）
            "\\abs",         // 绝对路径（Windows 反斜杠）
            "a//b",          // 空段
            "a/./b",         // "." 段
            "a\\..\\b",      // 反斜杠分隔的 ".."（结构化段判定，非字符串子串匹配）
        )
        illegal.forEach { path ->
            assertNull(SandboxFileScanner.resolveWithinSandbox(root, path), "应拒绝：$path")
        }
    }

    @Test
    fun `符号链接指向沙箱外的目录被拒绝并剔除`() {
        val root = newTempDir("symlink_root")
        val outside = newTempDir("symlink_outside")
        File(outside, "secret.txt").writeText("secret")
        val escape = File(root, "escape")
        val insideLink = File(root, "inside")
        val insideTarget = File(root, "real").apply { mkdirs() }

        // 能力探测：环境不支持符号链接（Windows 无特权 / 文件系统不支持）时**跳过**
        // 该条断言而不是 fail（CI 是 Linux，但本机 / 某些挂载卷可能不支持）。
        // ⚠️ 只查「不抛异常」不够：某些 Windows 环境（FS 过滤驱动 / 无符号链接特权的
        // 兼容层）createSymbolicLink「成功」但落盘的并非真 reparse point
        // （isSymbolicLink == false、canonical 不解析，W80-04 自测实测），必须再验证。
        val created = runCatching {
            Files.createSymbolicLink(escape.toPath(), outside.toPath())
            Files.createSymbolicLink(insideLink.toPath(), insideTarget.toPath())
        }.isSuccess &&
            Files.isSymbolicLink(escape.toPath()) &&
            Files.isSymbolicLink(insideLink.toPath())
        if (!created) return

        // 指向沙箱外的链接：resolveWithinSandbox 必须拒绝。
        assertNull(SandboxFileScanner.resolveWithinSandbox(root, "escape"))
        // 指向沙箱内的链接：合法，放行。
        assertNotNull(SandboxFileScanner.resolveWithinSandbox(root, "inside"))

        // scan 必须把逃逸目录从 listing 剔除，同时保留合法的内部链接。
        val names = SandboxFileScanner.scan(root).entries.map { it.name }.toSet()
        assertFalse(names.contains("escape"), "逃逸符号链接目录不得出现在 listing：$names")
        assertTrue(names.contains("inside"), "指向沙箱内的符号链接目录应保留：$names")
    }

    @Test
    fun `逃逸目录在计数与限流之前剔除且不占展示名额`() {
        // W82-04 的行为锚：剔除发生在 `.take(limit)` **之前**，所以逃逸目录既不计入
        // totalEntries、也不吃掉一个展示名额。若日后有人把 filter 挪到 take 之后，
        // 「有个目录消失了、列表还少了一条」这种退化只有这条用例能钉住。
        val root = newTempDir("escape_slot")
        val outside = newTempDir("escape_slot_outside")
        File(outside, "secret.txt").writeText("secret")
        val escape = File(root, "escape")
        // 能力探测口径与上一条 symlink 用例一致：环境不支持符号链接时跳过（Windows
        // 无特权 / 某些文件系统）而不是 fail —— CI 是 Linux，本机可能不支持。
        val created = runCatching {
            Files.createSymbolicLink(escape.toPath(), outside.toPath())
        }.isSuccess && Files.isSymbolicLink(escape.toPath())
        if (!created) return

        val base = System.currentTimeMillis()
        File(root, "a.txt").apply { writeText("a"); setLastModified(base) }
        File(root, "b.txt").apply { writeText("b"); setLastModified(base - 5_000) }

        // limit=2 恰好等于「两个合法文件」的数量。
        val result = SandboxFileScanner.scan(root, limit = 2)
        assertEquals(setOf("a.txt", "b.txt"), result.entries.map { it.name }.toSet())
        assertEquals(2, result.totalEntries, "逃逸目录不得计入 totalEntries")
        assertFalse(result.truncated, "逃逸目录不得占用展示名额")
    }

    // ── 扫描日志节流窗口（W82）────────────────────────────────────────────────
    // withinWindow 只测纯函数；warn() 的分键行为（W83-04）经其时钟/输出缝验证
    // （见下一节）—— 默认值即生产路径，单测传收集器，不碰 AgentLogStore 进程级
    // 单例、不断言日志实现细节，用例间用不同消息（键）隔离，顺序不构成隐式依赖。

    @Test
    fun `扫描日志节流窗口内判定为真`() {
        // windowStart=0 表示「从未记过」；窗口起点之后 1ms / 29_999ms 都还在窗口内。
        assertTrue(SandboxFileScanner.ScanEscapeAudit.withinWindow(nowMillis = 1L, windowStartMillis = 0L))
        assertTrue(SandboxFileScanner.ScanEscapeAudit.withinWindow(nowMillis = 29_999L, windowStartMillis = 0L))
        val base = 1_700_000_000_000L
        assertTrue(
            SandboxFileScanner.ScanEscapeAudit.withinWindow(nowMillis = base + 19_999L, windowStartMillis = base),
        )
    }

    @Test
    fun `扫描日志节流窗口在恰好 30s 处结束`() {
        // 左闭右开：恰好 30_000ms 视为窗口已结束，必须重新记（否则节流永不释放）。
        assertFalse(SandboxFileScanner.ScanEscapeAudit.withinWindow(nowMillis = 30_000L, windowStartMillis = 0L))
        val base = 1_700_000_000_000L
        assertFalse(
            SandboxFileScanner.ScanEscapeAudit.withinWindow(nowMillis = base + 30_000L, windowStartMillis = base),
        )
        // 真实时钟量级下「从未记过」⇒ 差值远大于窗口 ⇒ 首次必然放行。
        assertFalse(
            SandboxFileScanner.ScanEscapeAudit.withinWindow(
                nowMillis = 1_700_000_000_000L,
                windowStartMillis = 0L,
            ),
        )
    }

    // ── 分键节流（W83-04）────────────────────────────────────────────────────
    // warn 的双缝：nowMillis 固定时钟、sink 收集器（默认值即生产路径，不碰
    // AgentLogStore）。键即消息（内含目录路径），用例间用不同消息隔离。

    @Test
    fun `不同目录路径的扫描告警互不抑制`() {
        val t0 = 1_700_000_000_000L
        val lines = mutableListOf<String>()
        val warn = { message: String ->
            SandboxFileScanner.ScanEscapeAudit.warn(message, nowMillis = t0) { lines.add(it) }
        }
        // 同一时刻两个不同路径：旧全局窗口下第二条会被吞成「已省略」。
        warn("沙箱扫描：剔除逃逸目录 escape")
        warn("沙箱扫描：剔除逃逸目录 other")
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("escape"))
        assertTrue(lines[1].contains("other"))
        assertFalse(lines[0].contains("已省略"))
        assertFalse(lines[1].contains("已省略"))
    }

    @Test
    fun `同一路径的扫描告警在 30s 窗口内仍只记一条`() {
        val t0 = 1_700_000_000_000L
        val lines = mutableListOf<String>()
        val warn = { delta: Long ->
            SandboxFileScanner.ScanEscapeAudit.warn(
                "沙箱扫描：剔除逃逸目录 escape",
                nowMillis = t0 + delta,
            ) { lines.add(it) }
        }
        // 窗口内连记 3 次（t0 / +1s / 29_999ms）：只放行第一条，其余计省略。
        warn(0L)
        warn(1_000L)
        warn(29_999L)
        assertEquals(1, lines.size)
        // 恰好 30s：窗口结束，重新记，并带上被省略的 2 次。
        warn(30_000L)
        assertEquals(2, lines.size)
        assertTrue(lines[1].contains("另有 2 次已省略"), lines[1])
    }

    // ── 逐层下钻（Wave 36）────────────────────────────────────────────────────

    @Test
    fun `下钻时条目 relativePath 带目录前缀`() {
        val root = newTempDir("drill")
        val sub = File(root, "sub").apply { mkdirs() }
        File(sub, "inner.txt").writeText("inner")
        File(sub, "deeper").apply { mkdirs() }
        File(root, "top.txt").writeText("top")

        val result = SandboxFileScanner.scan(root, dirPath = "sub")
        assertEquals(
            setOf("sub/inner.txt", "sub/deeper"),
            result.entries.map { it.relativePath }.toSet(),
        )
        // 根层仍不带前缀（回归锚：dirPath 默认空串与 Wave 33 逐字节一致）。
        val rootResult = SandboxFileScanner.scan(root)
        assertEquals(
            setOf("sub", "top.txt"),
            rootResult.entries.map { it.relativePath }.toSet(),
        )
    }

    @Test
    fun `下钻到不存在的目录或非法路径返回空结果`() {
        val root = newTempDir("drill_missing")
        File(root, "real.txt").writeText("x")
        val missing = SandboxFileScanner.scan(root, dirPath = "nope")
        assertTrue(missing.entries.isEmpty())
        assertEquals(0, missing.totalEntries)
        assertFalse(missing.truncated)
        // 非法路径（逃逸）同样 fail-closed。
        val escaped = SandboxFileScanner.scan(root, dirPath = "..")
        assertTrue(escaped.entries.isEmpty())
    }
}
