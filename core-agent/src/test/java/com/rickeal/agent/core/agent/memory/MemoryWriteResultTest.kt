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
 *  ⑥ `remove` 五个变体：文件 / 条目不存在 → [MemoryRemoveResult.NotFound]；损坏 → Corrupted；
 *     读不了 → Unreadable；写失败 → WriteFailed；
 *  ⑦ **读不了**（权限 / IO）→ [MemoryWriteResult.Unreadable] / [MemoryRemoveResult.Unreadable]
 *     （Wave 38 新增：与「能读但解析失败」的 Corrupted 是两条独立结局，处置建议相反）。
 *
 * 全程纯 JVM：只碰 `java.io.File` + `core-model` 的 JSON（无 Android 类）。
 *
 * ⚠️ 高复用价值的坑（JUnit 4）：测试方法必须返回 **void**。Kotlin 表达式体 `fun x() = ...` 的返回
 * 类型由**末表达式**决定，而 `assertNotNull` / `assertIs` / `assertFailsWith` / `assertFails` 会
 * **返回一个值**（非 Unit）—— 若以它们收尾，方法被推断成返回非 Unit ⇒ JUnit 校验失败 ⇒ 整个测试类
 * `initializationError`，该类**所有**用例一个都不跑（日志只报「182 tests completed, 1 failed」，
 * 极具误导性）。故每个用例的末语句必须是返回 Unit 的断言（`assertEquals` / `assertTrue` /
 * `assertNull` / `assertSame`）；需要 `assertNotNull` 的值语义时先绑局部 `val`，再用 Unit 型断言收尾。
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
        // ⚠️ 末语句必须返回 Unit：assertNotNull 会返回其 actual（非 Unit），单独收尾会让本方法
        // 推断成返回 String ⇒ JUnit 4 校验失败 ⇒ 整类 initializationError、本文件 10 个用例全不跑。
        val message = assertNotNull(result.userMessage, "TooLong 必须带面向人的原因")
        assertTrue(message.contains(AgentMemory.MAX_CONTENT_CHARS.toString()), "原因文案应含上限数值（防常量漂移）：$message")
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
        // 末语句返回 Unit（同上）：assertNotNull 的返回值先绑到局部，再用 assertTrue 收尾。
        val message = assertNotNull(result.userMessage, "WriteFailed 必须带面向人的原因")
        assertTrue(message.contains("磁盘"), "原因文案应说明是落盘失败：$message")
    }

    @Test
    fun `文件存在但读不了 —— upsert / remove 均回 Unreadable 而非 Corrupted`() =
        withTempDir("cam-p-mwr-unreadable") { dir ->
            // 构造「读不了」：文件存在且是合法 JSON，但去掉读权限 ⇒ `readText` 抛 ⇒ 必须回
            // Unreadable。Wave 38 之前这会与「解析失败」同归 Corrupted，把权限问题报成
            // 「文件损坏，请修复或删除」—— 而那个文件其实内容完好（读不了 ≠ 坏了）。
            val file = File(dir, "memory.json")
            file.writeText("""[{"title":"t","content":"c"}]""")
            file.setReadable(false, false)
            try {
                // 能力探测（同 WriteFailed 用例惯例）：若以 root 运行 / 文件系统忽略权限位，
                // 置不可读后仍读得到 ⇒ 前提不成立，**跳过断言而非误红**。
                val stillReadable = runCatching { file.readText(); true }.getOrDefault(false)
                if (stillReadable) return@withTempDir
                val memory = AgentMemory(file)
                val write = runBlocking { memory.upsert("新", "x") }
                assertTrue(write is MemoryWriteResult.Unreadable, "读不了必须回 Unreadable：$write")
                val remove = runBlocking { memory.remove("t") }
                assertTrue(remove is MemoryRemoveResult.Unreadable, "读不了必须回 Unreadable：$remove")
            } finally {
                file.setReadable(true, false)
            }
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
