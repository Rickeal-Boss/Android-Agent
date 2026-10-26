package com.rickeal.agent.core.agent.journal

import com.rickeal.agent.core.model.AgentLogStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `findUnsettled` 尾读优化（W84-17）回归测试：覆盖「尾读命中 / 超窗口回退 / 截断半行」三面。
 *
 * 不依赖 Android，纯 JVM：journal 文件直接落临时目录，断言 `findUnsettled` 的对外契约
 * （返回最近未 settled 且含 message 的 run；过滤 `.jsonl` 后缀与 `.dismissed` 归档）。
 * 尾读路径的判据语义须与全文读完全一致——本测试即钉住这一点。
 */
class AgentRunJournalTailReadTest {

    private fun writeJournal(dir: File, runId: String, rawLines: List<String>) {
        dir.mkdirs()
        File(dir, "$runId.jsonl").writeText(rawLines.joinToString("\n") { it } + "\n")
    }

    /** 与 [AgentRunJournal.JournalLine] 序列化形态逐字一致（字段名 + 空 payload）。 */
    private fun line(seq: Long, atMillis: Long, kind: String): String =
        """{"seq":$seq,"atMillis":$atMillis,"kind":"$kind","payload":{}}"""

    // ---- 尾读命中：窗口内（小 journal）未 settled ⇒ 判定为可恢复 run，且不回退全文读 ----
    @Test
    fun `tailReadDetectsUnsettledWithinWindow`() {
        val dir = Files.createTempDirectory("jr-tail").toFile()
        try {
            writeJournal(dir, "r1", listOf(
                line(1, 100, AgentRunJournal.KIND_RUN_STARTED),
                line(2, 200, AgentRunJournal.KIND_MESSAGE),
            ))
            val result = AgentRunJournal.findUnsettled(dir)
            assertNotNull(result, "窗口内未 settled 的 run 应被判定为可恢复")
            assertEquals("r1", result!!.runId)
            assertEquals(1, result.messageCount)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- 尾读命中：窗口内末行 settled ⇒ 跳过（非可恢复 run） ----
    @Test
    fun `tailReadSkipsSettledWithinWindow`() {
        val dir = Files.createTempDirectory("jr-tail").toFile()
        try {
            writeJournal(dir, "r2", listOf(
                line(1, 100, AgentRunJournal.KIND_RUN_STARTED),
                line(2, 200, AgentRunJournal.KIND_MESSAGE),
                line(3, 300, AgentRunJournal.KIND_SETTLED),
            ))
            val result = AgentRunJournal.findUnsettled(dir)
            assertNull(result, "末行 settled 的 run 不应被判定为可恢复")
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- 超窗口回退：journal 超过尾读窗口（>200 行）⇒ 回退全文读，仍正确判定 + 打 WARN ----
    @Test
    fun `exceedsWindowFallsBackToFullRead`() {
        AgentLogStore.clear()
        val dir = Files.createTempDirectory("jr-fallback").toFile()
        try {
            val many = (1..250).map { line(it.toLong(), it * 100L, AgentRunJournal.KIND_MESSAGE) }
            writeJournal(dir, "big", many)
            val result = AgentRunJournal.findUnsettled(dir)
            assertNotNull(result, "超窗口 journal 经回退全文读仍应被正确判定为可恢复")
            assertEquals(250, result!!.messageCount)
            val warned = AgentLogStore.recent().any { it.message.contains("尾读超窗口") }
            assertTrue(warned, "超窗口应触发回退 WARN（便于监控回退频率）")
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- 截断半行：窗口边界切在一条 JSON 中间 ⇒ 不得抛异常，仍能经回退正确判定 ----
    @Test
    fun `truncatedHalfLineDoesNotThrow`() {
        val dir = Files.createTempDirectory("jr-trunc").toFile()
        try {
            // 构造 >64KB 的 journal（每条 message 行带较长 payload 文本），令尾读窗口起点落在
            // 某条 JSON 中间 ⇒ 首行半截被丢弃，不应抛异常；文件 > 窗口 ⇒ 回退全文读。
            val sb = StringBuilder()
            repeat(2000) { i ->
                sb.append(line((i + 1).toLong(), (i + 1) * 100L, AgentRunJournal.KIND_MESSAGE)).append("\n")
            }
            dir.mkdirs()
            File(dir, "huge.jsonl").writeText(sb.toString())
            val result = AgentRunJournal.findUnsettled(dir)
            assertNotNull(result, "截断半行场景不应抛异常，且应正确判定为可恢复")
        } finally {
            dir.deleteRecursively()
        }
    }
}
