package com.rickeal.agent.core.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
