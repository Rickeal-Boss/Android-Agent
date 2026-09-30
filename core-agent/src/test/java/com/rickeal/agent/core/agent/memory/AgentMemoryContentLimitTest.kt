package com.rickeal.agent.core.agent.memory

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * [AgentMemory.upsert] 的**单条正文上限**（Wave 35 D6）行为锁定。
 *
 * 注入面早已有界（`MAX_SECTIONS` 条 × `PROMPT_MAX_CHARS` 截断），但存储面此前无界：
 * 写一条 100KB 正文会让 `memory.json` 无限膨胀，而每次会话真正用上的只有前 1200 字符
 * —— 存了 100KB、用了 1200，剩下的用户在设置页也看不到。所以 storage 层必须自持上界。
 *
 * 本测试钉住三件事：
 *  ① 超限**拒绝**（Wave 37 起返回 [MemoryWriteResult.TooLong]）而不是静默截断 ——
 *     静默截断会让模型以为记住了全文，而错误不可观测，比「写失败」更糟；
 *  ② 边界（恰好等于上限）必须**通过**（[MemoryWriteResult.Ok]），off-by-one 会变成
 *     「模型永远写不进正常长度」；
 *  ③ 超限拒写**不得动原文件**（既有记忆必须原样存活）—— 这是把「新的拒绝态」与
 *     [MemoryWriteResult.Corrupted] 的「保护性拒写」区分开的锚：两者都不入新条目，
 *     但前者只是拒这一条，后者是整份文件不可写。
 *
 * 全程纯 JVM：只碰 `java.io.File` + `core-model` 的 JSON（无 Android 类）。
 */
class AgentMemoryContentLimitTest {

    private fun <T> withMemory(body: suspend (AgentMemory, File) -> T): T {
        val dir = Files.createTempDirectory("cam-p-memory-limit").toFile()
        return try {
            val file = File(dir, "memory.json")
            runBlocking { body(AgentMemory(file), file) }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `正文恰好等于上限 —— 通过（off-by-one 回归锚）`() = withMemory { memory, _ ->
        val exact = "y".repeat(AgentMemory.MAX_CONTENT_CHARS)
        assertEquals(MemoryWriteResult.Ok, memory.upsert("边界", exact), "恰好等于上限必须写入成功")
        val sections = memory.sections()
        assertEquals(1, sections.size)
        assertEquals(AgentMemory.MAX_CONTENT_CHARS, sections.single().content.length)
    }

    @Test
    fun `正文超过上限一个字符 —— 拒绝写入且原文件一字不动`() = withMemory { memory, file ->
        assertEquals(MemoryWriteResult.Ok, memory.upsert("既有", "保持"))
        val before = file.readText()
        val tooLong = "x".repeat(AgentMemory.MAX_CONTENT_CHARS + 1)
        val result = memory.upsert("新条目", tooLong)
        assertTrue(result is MemoryWriteResult.TooLong, "超限必须拒绝：$result")
        assertEquals(AgentMemory.MAX_CONTENT_CHARS + 1, (result as MemoryWriteResult.TooLong).length)
        assertEquals(before, file.readText(), "超限拒写不得改写原文件（含不得落半截文件）")
        assertEquals(listOf("既有"), memory.sections().map { it.title }, "既有记忆必须原样存活")
    }

    @Test
    fun `上限按 trim 后的正文计 —— 前后空白不计入`() = withMemory { memory, _ ->
        val padded = "  \n" + "z".repeat(AgentMemory.MAX_CONTENT_CHARS) + "\n\n"
        assertEquals(MemoryWriteResult.Ok, memory.upsert("去空白", padded), "前后空白不计入上限")
        assertEquals(AgentMemory.MAX_CONTENT_CHARS, memory.sections().single().content.length)
    }

    @Test
    fun `超限拒写与 Corrupted 拒写是两条独立分支 —— 损坏文件仍然拒写`() = withMemory { memory, file ->
        file.writeText("{ 这不是合法 JSON")
        assertEquals(MemoryWriteResult.Corrupted, memory.upsert("任意", "x"), "损坏文件一律拒写（保护原文件）")
        assertEquals("{ 这不是合法 JSON", file.readText(), "Corrupted 分支同样不得改写原文件")
    }
}
