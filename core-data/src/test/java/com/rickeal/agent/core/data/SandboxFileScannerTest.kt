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
        val created = runCatching {
            Files.createSymbolicLink(escape.toPath(), outside.toPath())
            Files.createSymbolicLink(insideLink.toPath(), insideTarget.toPath())
        }.isSuccess
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
