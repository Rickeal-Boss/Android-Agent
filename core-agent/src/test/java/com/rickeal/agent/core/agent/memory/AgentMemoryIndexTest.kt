package com.rickeal.agent.core.agent.memory

import com.rickeal.agent.core.model.AgentJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [AgentMemory.renderIndex]（Wave 34 题 B：记忆 pull 化的**提示词面**）行为锁定。
 *
 * 为什么值得单独钉住：这条渲染结果直接进 `systemText`，而 `systemText` 是引擎的
 * **会话重建判据** —— 一旦有人把它改回「连正文一起注入」，每个写了记忆的 run 都会
 * 多一次全量 re-prefill（4B 秒级），而且**不报错、只是变慢**，属于典型静默退化。
 * 这里的「索引不含正文」断言就是那条退化的回归锚。
 *
 * 全程纯 JVM：只碰 `java.io.File` 与 `core-model` 的 JSON（无 Android 类），
 * 记忆文件直接按 `memory.json` 的真实格式落一份临时副本 —— 不走 `upsert`，
 * 避免 64 次原子写在单测里放大成秒级耗时。
 */
class AgentMemoryIndexTest {

    private fun <T> withSections(sections: List<MemorySection>, body: suspend (AgentMemory) -> T): T {
        val dir = Files.createTempDirectory("cam-p-memory-index").toFile()
        return try {
            val file = File(dir, "memory.json")
            file.writeText(
                AgentJson.Default.encodeToString(ListSerializer(MemorySection.serializer()), sections)
            )
            runBlocking { body(AgentMemory(file)) }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `空记忆返回 null —— 不注入省 token`() =
        withSections(emptyList()) { assertNull(it.renderIndex()) }

    @Test
    fun `索引只含标题不含正文`() =
        withSections(listOf(MemorySection("用户偏好", "回答用中文，不要客套", 1L))) { memory ->
            val text = memory.renderIndex()
            assertEquals("- [用户偏好]", text)
            assertTrue(text?.contains("不要客套") != true, "正文绝不能进提示词：$text")
        }

    @Test
    fun `超预算截断并附检索入口提示`() =
        withSections((1..64).map { MemorySection("记忆条目 " + it.toString().padStart(3, '0'), "正文".repeat(50), it.toLong()) }) { memory ->
            val text = memory.renderIndex()!!
            assertTrue(text.contains("还有"), "截断必须写明还有几条：$text")
            assertTrue(text.contains("memory_search"), "截断必须给出 pull 入口：$text")
            assertTrue(!text.contains("正文"), "正文绝不能进提示词：$text")
        }

    @Test
    fun `预算极小也至少渲染一条`() =
        withSections(listOf(MemorySection("用户偏好", "x"), MemorySection("项目约定", "y"))) { memory ->
            assertTrue(memory.renderIndex(maxChars = 1)!!.startsWith("- ["))
        }

    @Test
    fun `标题内换行被单行化 —— 不伪造系统提示词结构`() =
        withSections(listOf(MemorySection("用户\n偏好", "x"))) { memory ->
            assertEquals("- [用户 偏好]", memory.renderIndex())
        }
}
