package com.rickeal.agent.feature.chat

import com.rickeal.agent.core.agent.journal.AgentRunJournal
import com.rickeal.agent.core.model.ChatMessage
import com.rickeal.agent.core.model.Role
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wave 40 C3：`mergeProcessIntoVisible`（纯核心）与 `historyWithProcess`
 * （目录层）的 JVM 单测。
 *
 * 钉住的语义：
 *  1. 去重口径与 onRecover 逐行一致 —— 只剔「MODEL 且 role+text 已在可见历史」；
 *  2. TOOL / 中间 toolCall 消息永远保留（它们只存在于 journal）；
 *  3. 可见历史在前、过程消息按 run 时间序排在之后（第一版拼接语义）；
 *  4. 目录层：跨 run 按文件名时间戳排序合并、用户丢弃（.dismissed.jsonl）的
 *     run 也读、user_input 行不混入过程消息、损坏行按 decode 既有纪律跳过、
 *     目录不存在按「无过程消息」处理。
 *
 * 目录层测试用真实 [AgentRunJournal] 落 fixture（suspend append 经 runBlocking
 * 驱动，JVM 纯文件 IO），覆盖的是生产读路径本身而非镜像。
 */
class HistoryWithProcessTest {

    /**
     * 本轮跑测试建过的临时根目录（[tempRoot] 建一个登记一个）。
     *
     * Wave 40 审查 P2-4：原先 `Files.createTempDirectory` 建完不清理，每次跑测试在
     * %TEMP% 里留 4 个空壳目录（CI 上是 runner 的临时区，本机就是越攒越多）。统一在
     * [tearDown] 里递归删除 —— 让用例只留下「跑过」这件事，不留垃圾。
     */
    private val tempRoots = mutableListOf<File>()

    /** 建临时根目录并登记（交给 [tearDown] 清理）。 */
    private fun tempRoot(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { tempRoots.add(it) }

    @After
    fun tearDown() {
        tempRoots.forEach { it.deleteRecursively() }
        tempRoots.clear()
    }

    /** 过程消息 fixture：一条中间 toolCall、一条工具结果、一条最终 MODEL 答案。 */
    private fun processFixture(answer: String): List<ChatMessage> = listOf(
        ChatMessage(role = Role.MODEL, text = "中间思考与调用", toolCalls = emptyList()),
        ChatMessage(role = Role.TOOL, text = "工具执行结果甲"),
        ChatMessage(role = Role.MODEL, text = answer),
    )

    /** 在临时目录里落一个 run 的 journal（真实写路径）。 */
    private fun writeRun(dir: File, runId: String, messages: List<ChatMessage>, userInput: ChatMessage? = null) {
        runBlocking {
            val journal = AgentRunJournal.open(dir, runId)
            userInput?.let { journal.appendUserInput(it) }
            messages.forEach { journal.appendMessage(it) }
        }
    }

    // ------------------------------------------------ mergeProcessIntoVisible

    @Test fun `纯拼接 —— 可见在前过程在后且 TOOL 与中间消息全保留`() {
        val visible = listOf(
            ChatMessage(id = "u1", role = Role.USER, text = "帮我查一下"),
            ChatMessage(id = "m1", role = Role.MODEL, text = "最终答案甲"),
        )
        val process = listOf(
            ChatMessage(id = "p1", role = Role.MODEL, text = "中间调用 current_time"),
            ChatMessage(id = "p2", role = Role.TOOL, text = "工具执行结果甲"),
            ChatMessage(id = "p3", role = Role.MODEL, text = "最终答案甲"),
        )
        val merged = mergeProcessIntoVisible(visible, process)
        // 最终答案甲与可见历史重复 → 剔；TOOL 与中间消息 → 全保留；顺序：可见在前。
        assertEquals(
            listOf(
                ChatMessage(id = "u1", role = Role.USER, text = "帮我查一下"),
                ChatMessage(id = "m1", role = Role.MODEL, text = "最终答案甲"),
                ChatMessage(id = "p1", role = Role.MODEL, text = "中间调用 current_time"),
                ChatMessage(id = "p2", role = Role.TOOL, text = "工具执行结果甲"),
            ),
            merged,
        )
    }

    @Test fun `只剔 MODEL —— 可见历史不吞过程侧的 USER 与 TOOL 消息`() {
        val visible = listOf(
            ChatMessage(id = "u1", role = Role.USER, text = "同样的话"),
            ChatMessage(id = "m1", role = Role.MODEL, text = "答案"),
        )
        val process = listOf(
            ChatMessage(id = "p1", role = Role.USER, text = "同样的话"),
            ChatMessage(id = "p2", role = Role.TOOL, text = "答案"),
            ChatMessage(id = "p3", role = Role.MODEL, text = "答案"),
        )
        val merged = mergeProcessIntoVisible(visible, process)
        // USER「同样的话」与 TOOL「答案」role 不符 → 不剔；只剔 MODEL「答案」。
        assertEquals(listOf("p1", "p2"), merged.drop(2).map { it.id })
    }

    @Test fun `同名文本不同角色不互剔`() {
        val visible = listOf(
            ChatMessage(id = "u1", role = Role.USER, text = "重复的文本"),
        )
        val process = listOf(
            ChatMessage(id = "p1", role = Role.MODEL, text = "重复的文本"),
        )
        // visibleKeys 里是 "USER|重复的文本"，MODEL 键不同 → 保留。
        assertEquals(2, mergeProcessIntoVisible(visible, process).size)
    }

    // ---------------------------------------------------- historyWithProcess

    @Test fun `目录不存在或无 run 文件 —— 返回可见历史原样`() {
        val root = tempRoot("c3-empty")
        val visible = listOf(ChatMessage(id = "u1", role = Role.USER, text = "问题"))
        assertEquals(visible, historyWithProcess(visible, root, "不存在的会话"))
        // 目录存在但没有任何 journal 文件。
        assertTrue(File(root, "some-cid").mkdirs())
        assertEquals(visible, historyWithProcess(visible, root, "some-cid"))
    }

    @Test fun `跨 run 按文件名时间序合并且 user_input 行不混入`() {
        val root = tempRoot("c3-runs")
        val cid = "cid-1"
        assertTrue(File(root, cid).mkdirs())
        val runDir = File(root, cid)
        // 先写时间上更晚的 run（验证排序依据是文件名时间戳而非写入顺序）。
        val lateRun = processFixture("答案乙")
        writeRun(runDir, "run_1700000001000", lateRun, userInput = ChatMessage(role = Role.USER, text = "第二个问题"))
        val earlyRun = processFixture("答案甲")
        writeRun(runDir, "run_1700000000000", earlyRun, userInput = ChatMessage(role = Role.USER, text = "第一个问题"))

        val visible = listOf(
            ChatMessage(id = "u1", role = Role.USER, text = "第一个问题"),
            ChatMessage(id = "m1", role = Role.MODEL, text = "答案甲"),
            ChatMessage(id = "u2", role = Role.USER, text = "第二个问题"),
            ChatMessage(id = "m2", role = Role.MODEL, text = "答案乙"),
        )
        val merged = historyWithProcess(visible, root, cid)
        val tail = merged.drop(visible.size)
        // run_1700000000000 的 TOOL 与中间消息在前，run_1700000001000 的在后；
        // 两个 run 的最终 MODEL 答案与 user_input 行都被剔除。
        assertEquals(
            listOf(
                "中间思考与调用", "工具执行结果甲",   // run_1700000000000（fixture 的工具结果文案固定）
                "中间思考与调用", "工具执行结果甲",   // run_1700000001000
            ),
            tail.map { it.text },
        )
        assertTrue(tail.none { it.role == Role.USER }, "user_input 行不得混进过程消息")
    }

    @Test fun `用户丢弃的 run 也读 —— dismissed 归档同样回灌过程消息`() {
        val root = tempRoot("c3-dismissed")
        val cid = "cid-2"
        assertTrue(File(root, cid).mkdirs())
        val runDir = File(root, cid)
        writeRun(runDir, "run_1700000000000", processFixture("被打断的答案"))
        // markDismissed 同款改名：runId.dismissed.jsonl。
        val raw = File(runDir, "run_1700000000000.jsonl")
        assertTrue(raw.renameTo(File(runDir, "run_1700000000000.dismissed.jsonl")))

        val visible = listOf(ChatMessage(id = "u1", role = Role.USER, text = "第一个问题"))
        val merged = historyWithProcess(visible, root, cid)
        // 最终 MODEL 答案「被打断的答案」不在可见历史 → 保留；TOOL / 中间消息同样在。
        val texts = merged.map { it.text }
        assertTrue("被打断的答案" in texts, "dismissed run 的过程消息必须回灌")
        assertTrue("工具执行结果甲" in texts)
    }

    @Test fun `损坏行按既有 decode 纪律跳过 —— 绝不抛异常挡发送`() {
        val root = tempRoot("c3-corrupt")
        val cid = "cid-3"
        assertTrue(File(root, cid).mkdirs())
        val runDir = File(root, cid)
        writeRun(runDir, "run_1700000000000", processFixture("正常答案"))
        // 追加坏行：非法 JSON 与合法 JSONL 壳但坏 payload 各一行。
        val file = File(runDir, "run_1700000000000.jsonl")
        file.appendText("这不是一行 JSON\n")
        file.appendText("{\"seq\":99,\"atMillis\":1,\"kind\":\"message\",\"payload\":{\"broken\":true\n")

        val visible = listOf(ChatMessage(id = "u1", role = Role.USER, text = "问题"))
        val merged = historyWithProcess(visible, root, cid)
        assertTrue("正常答案" in merged.map { it.text }, "坏行只跳过自身，好行必须完整保留")
    }
}
