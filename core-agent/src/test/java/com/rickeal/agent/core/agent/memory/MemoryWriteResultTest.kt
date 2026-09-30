package com.rickeal.agent.core.agent.memory

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * [AgentMemory.upsert] / [AgentMemory.remove] 的**结果类型**（Wave 37）行为锁定。
 *
 * 背景（本波修的缺陷）：`writeSync` 曾把落盘异常吞进日志后让 `upsert` 无条件回 `true`
 * —— 磁盘满 / 权限 / IO 失败时用户与模型都被告知「已记住」，实际一个字节都没落盘。
 * 本测试第一次让「落盘失败」这条路径可被**纯 JVM** 钉住（用例 5、6c）。
 *
 * 覆盖：
 *  ① 正常写入 → [MemoryWriteResult.Ok]；
 *  ② 正文恰等于 [AgentMemory.MAX_CONTENT_CHARS] → Ok（off-by-one 锚）；
 *  ③ 正文 = 上限 + 1 → [MemoryWriteResult.TooLong]，且 length 精确等于上限 + 1；
 *  ④ 非法 JSON → [MemoryWriteResult.Corrupted]，且**原文件一字不动**；
 *  ⑤ **落盘失败** → [MemoryWriteResult.WriteFailed]（父路径是普通文件：`mkdirs()` 失败 +
 *     tmp 写入抛异常）；
 *  ⑥ `remove` 三态：文件 / 条目不存在 → [MemoryRemoveResult.NotFound]；损坏 → Corrupted；
 *     写失败 → WriteFailed。
 *
 * 全程纯 JVM：只碰 `java.io.File` + `core-model` 的 JSON（无 Android 类）。
 */
class MemoryWriteResultTest {

    private fun <T> withTempDir(name: String, body: (File) -> T): T {
        val dir = Files.createTempDirectory(name).toFile()
        return try {
            body(dir)
        } finally {
            dir.setWritable(true, false)
            dir.deleteRecursively()
        }
    }

    @Test
    fun `正常写入 —— 回 Ok`() = withTempDir("cam-p-mwr-ok") { dir ->
        val memory = AgentMemory(File(dir, "memory.json"))
        assertEquals(MemoryWriteResult.Ok, runBlocking { memory.upsert("标题", "正文") })
        assertEquals(listOf("标题"), runBlocking { memory.sections() }.map { it.title })
    }

    @Test
    fun `正文恰等于上限 —— Ok（off-by-one 锚）`() = withTempDir("cam-p-mwr-boundary") { dir ->
        val memory = AgentMemory(File(dir, "memory.json"))
        val exact = "y".repeat(AgentMemory.MAX_CONTENT_CHARS)
        assertEquals(MemoryWriteResult.Ok, runBlocking { memory.upsert("边界", exact) })
    }

    @Test
    fun `正文超上限一个字符 —— TooLong 且 length 精确`() = withTempDir("cam-p-mwr-toolong") { dir ->
        val memory = AgentMemory(File(dir, "memory.json"))
        val tooLong = "x".repeat(AgentMemory.MAX_CONTENT_CHARS + 1)
        val result = runBlocking { memory.upsert("新条目", tooLong) }
        assertTrue(result is MemoryWriteResult.TooLong, "超限必须回 TooLong：$result")
        assertEquals(AgentMemory.MAX_CONTENT_CHARS + 1, (result as MemoryWriteResult.TooLong).length)
        assertNotNull(result.userMessage, "TooLong 必须带面向人的原因")
    }

    @Test
    fun `非法 JSON —— Corrupted 且原文件一字不动`() = withTempDir("cam-p-mwr-corrupted") { dir ->
        val file = File(dir, "memory.json")
        file.writeText("{ 这不是合法 JSON")
        val memory = AgentMemory(file)
        assertEquals(MemoryWriteResult.Corrupted, runBlocking { memory.upsert("任意", "x") })
        assertEquals("{ 这不是合法 JSON", file.readText(), "Corrupted 分支不得改写原文件")
    }

    @Test
    fun `落盘失败 —— WriteFailed（父路径是普通文件）`() = withTempDir("cam-p-mwr-writefail") { dir ->
        // 父路径是普通文件 ⇒ file.exists() 为 false（readState 走 Absent，不是 Corrupted），
        // 但 writeSync 里 mkdirs() 失败 + tmp 写入抛 IOException ⇒ 落盘失败。
        val blocker = File(dir, "blocker")
        blocker.writeText("x")
        val memory = AgentMemory(File(blocker, "memory.json"))
        val result = runBlocking { memory.upsert("t", "c") }
        assertTrue(result is MemoryWriteResult.WriteFailed, "父路径是文件时必须回 WriteFailed：$result")
        assertNotNull(result.userMessage, "WriteFailed 必须带面向人的原因")
    }

    @Test
    fun `remove 文件不存在 —— NotFound`() = withTempDir("cam-p-mwr-rm-absent") { dir ->
        val memory = AgentMemory(File(dir, "memory.json"))
        assertEquals(MemoryRemoveResult.NotFound, runBlocking { memory.remove("t") })
    }

    @Test
    fun `remove 条目不存在 —— NotFound`() = withTempDir("cam-p-mwr-rm-missing") { dir ->
        val file = File(dir, "memory.json")
        file.writeText("""[{"title":"既有","content":"c"}]""")
        val memory = AgentMemory(file)
        assertEquals(MemoryRemoveResult.NotFound, runBlocking { memory.remove("不存在的标题") })
        assertEquals("""[{"title":"既有","content":"c"}]""", file.readText(), "未命中不得改写原文件")
    }

    @Test
    fun `remove 损坏文件 —— Corrupted`() = withTempDir("cam-p-mwr-rm-corrupted") { dir ->
        val file = File(dir, "memory.json")
        file.writeText("{ 这不是合法 JSON")
        val memory = AgentMemory(file)
        assertEquals(MemoryRemoveResult.Corrupted, runBlocking { memory.remove("t") })
        assertEquals("{ 这不是合法 JSON", file.readText(), "Corrupted 分支不得改写原文件")
    }

    @Test
    fun `remove 落盘失败 —— WriteFailed`() = withTempDir("cam-p-mwr-rm-writefail") { dir ->
        // 构造「读得到、写不了」：文件是合法 JSON（readState 走 Ok 且能命中条目），
        // 但把父目录与文件都置为只读 —— POSIX 下去掉父目录写权限 ⇒ 建 tmp 失败；
        // Windows 下目录只读被忽略，但文件只读会让 REPLACE_EXISTING 的 move 失败。
        // 两条路径都收敛到 WriteFailed（跨平台）。
        //
        // ⚠️ 能力探测（动机）：本用例依赖 POSIX 权限位**生效**。若执行环境以 root 运行，
        // root 会绕过权限检查 ⇒ setWritable(false) 形同虚设 ⇒ 写入反而成功、remove 回 Removed、
        // 用例误红。故置只读后先做一次探测：能在只读目录里写文件即说明权限位被绕过，此时
        // **跳过断言而不是判红**（沿用本仓既有惯例「环境不支持则跳过」，与 SandboxFileScannerTest
        // 的符号链接逃逸用例同款处置）。CI（GitHub ubuntu-latest）以非 root 的 `runner` 用户运行，
        // 权限位生效 ⇒ 实际会走到下面的断言。
        val file = File(dir, "memory.json")
        file.writeText("""[{"title":"t","content":"c"}]""")
        dir.setWritable(false, false)
        file.setWritable(false, false)
        try {
            // 探测必须紧跟置只读、在构造 AgentMemory（其 remove 会写盘）之前，中间不得有任何其它写操作。
            val probe = File(dir, "probe.tmp")
            val writableDespiteMode = runCatching { probe.writeText("x"); true }.getOrDefault(false)
            if (writableDespiteMode) {
                runCatching { probe.delete() }
                return@withTempDir
            }
            val memory = AgentMemory(file)
            val result = runBlocking { memory.remove("t") }
            assertTrue(result is MemoryRemoveResult.WriteFailed, "写失败必须回 WriteFailed：$result")
        } finally {
            dir.setWritable(true, false)
            file.setWritable(true, false)
        }
    }
}
