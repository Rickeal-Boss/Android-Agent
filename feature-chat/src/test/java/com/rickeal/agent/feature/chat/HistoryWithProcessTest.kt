package com.rickeal.agent.feature.chat

import com.rickeal.agent.core.agent.journal.AgentRunJournal
import com.rickeal.agent.core.model.ChatMessage
import com.rickeal.agent.core.model.Role
import com.rickeal.agent.core.model.TokenEstimator
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Wave 40 C3 + Wave 41 P2-1：`processTokenBudget`（预算公式）、`mergeProcessIntoVisible`
 * （纯核心）与 `historyWithProcess`（目录层 + token 开窗）的 JVM 单测。
 *
 * 钉住的语义：
 *  1. 预算公式与 AgentRunner 压缩预算同源（base = (contextLength − maxTokens)
 *     .coerceAtLeast(512) × threshold；再减 visible 与本次输入占用，钳 0）；
 *  2. 去重口径与 onRecover 逐行一致 —— 只剔「MODEL 且 role+text 已在可见历史」；
 *  3. TOOL / 中间 toolCall 消息永远保留（它们只存在于 journal）；
 *  4. 可见历史在前、过程消息按 run 时间序排在之后（第一版拼接语义）；
 *  5. 目录层：跨 run 按文件名时间戳排序合并、用户丢弃（.dismissed.jsonl）的
 *     run 也读、user_input 行不混入过程消息、损坏行按 decode 既有纪律跳过、
 *     目录不存在按「无过程消息」处理；
 *  6. token 开窗（Wave 41）：预算内全保留（回归锚）、截断只发生在 run 边界
 *     （丢最旧 run、保序、无半 run）、最新 run 无条件保底（预算 0 也保底）、
 *     kept run 的最终 MODEL 命中 visible 去重口径被剔。
 *
 * 目录层测试用真实 [AgentRunJournal] 落 fixture（suspend append 经 runBlocking
 * 驱动，JVM 纯文件 IO），覆盖的是生产读路径本身而非镜像。截断阈值不硬编码
 * token 数，统一用 [TokenEstimator] 对 fixture 实算 —— 与生产同一估算口径。
 */
class HistoryWithProcessTest {

    /**
     * 本轮跑测试建过的临时根目录（[tempRoot] 建一个登记一个）。
     *
     * Wave 40 审查 P2-4：原先 `Files.createTempDirectory` 建完不清理，每次跑测试在
     * %TEMP% 里留空壳目录（CI 上是 runner 的临时区，本机就是越攒越多）。统一在
     * [tearDown] 里递归删除 —— 让用例只留下「跑过」这件事，不留垃圾。
     */
    private val tempRoots = mutableListOf<File>()

    /** 建临时根目录并登记（交给 [tearDown] 清理）。 */
    private fun tempRoot(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { tempRoots.add(it) }

    // ⚠️ 注解名是 kotlin.test.**AfterTest**（typealias 到 org.junit.After），
    //    不是 kotlin.test.After —— 后者不存在（kotlin-test 只有 BeforeTest/AfterTest/
    //    BeforeClass/AfterClass，没有裸 Before/After）。JUnit 4 要求该方法 public 且返回 void。
    @AfterTest
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

    /** 带 tag 的过程消息 fixture（开窗测试用：每个 run 的文案互不混淆）。 */
    private fun runFixture(tag: String, answer: String): List<ChatMessage> = listOf(
        ChatMessage(role = Role.MODEL, text = "调用$tag", toolCalls = emptyList()),
        ChatMessage(role = Role.TOOL, text = "结果$tag"),
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

    /** 若干个 run 的 token 实算合计（与生产同一 [TokenEstimator] 口径）。 */
    private fun costOf(vararg runs: List<ChatMessage>): Int {
        var total = 0
        for (run in runs) for (message in run) total += TokenEstimator.estimate(message)
        return total
    }

    /** 断言一个 run 的过程消息**完整**在 texts 里（M(toolCalls) 与 TOOL 成对存活）。 */
    private fun assertRunFullyKept(texts: List<String>, tag: String, answer: String) {
        assertTrue("调用$tag" in texts, "run $tag 的中间 toolCall 消息应保留")
        assertTrue("结果$tag" in texts, "run $tag 的 TOOL 结果应与 toolCall 成对保留")
        assertTrue(answer in texts, "run $tag 的最终 MODEL 答案应保留")
    }

    /** 断言一个 run 的过程消息**整体**不在 texts 里（run 原子：绝不出现半 run）。 */
    private fun assertRunAbsent(texts: List<String>, tag: String, answer: String) {
        assertFalse("调用$tag" in texts, "run $tag 被裁剪则中间 toolCall 不得残留")
        assertFalse("结果$tag" in texts, "run $tag 被裁剪则 TOOL 结果不得残留")
        assertFalse(answer in texts, "run $tag 被裁剪则最终 MODEL 答案不得残留")
    }

    // ------------------------------------------------------ processTokenBudget

    @Test fun `预算公式与 AgentRunner 压缩预算同源并减去可见历史与本次输入占用`() {
        // 4096 − 1024 = 3072，×0.75f = 2304（与 AgentRunner 轮头 budget 同算式）。
        // visible 十个 CJK 字 = 10 tok（TokenEstimator 的 CJK 口径）；本次输入 U 与
        // 生产调用点同构（TokenEstimator.estimate(userMessage) 实算）—— 复审 P1-1：
        // 不预减本次输入则预算吃满时 engineHistory 总量超 base，AgentRunner 轮头
        // 每轮必触发压缩（全量 re-prefill），开窗收益被完全吐回。
        val visible = listOf(ChatMessage(role = Role.USER, text = "一二三四五六七八九十"))
        val input = ChatMessage(role = Role.USER, text = "帮我查一下天气")
        assertEquals(
            2304 - 10 - TokenEstimator.estimate(input),
            processTokenBudget(4096, 1024, visible, TokenEstimator.estimate(input), 0.75f),
        )
    }

    @Test fun `预算公式 coerceAtLeast512 与钳 0 两道保底`() {
        // maxTokens ≥ contextLength → (0).coerceAtLeast(512) = 512，×0.75f = 384；
        // visible 500 tok + 本次输入占用已超 384 → 余量为负，钳 0。
        val visible = List(50) { ChatMessage(role = Role.USER, text = "一二三四五六七八九十") }
        val input = ChatMessage(role = Role.USER, text = "帮我查一下天气")
        assertEquals(
            0,
            processTokenBudget(512, 32768, visible, TokenEstimator.estimate(input), 0.75f),
        )
    }

    // ------------------------------------------------ mergeProcessIntoVisible

    @Test fun `纯拼接 —— 可见在前过程在后且 TOOL 与中间消息全保留`() {
        // ⚠️ flaky 根因与修法（**勿回改**）：`ChatMessage.createdAtMillis` 的默认值是
        //    **构造时**的 `System.currentTimeMillis()`（ChatMessage.kt:17）。本用例若用
        //    全字段 `assertEquals` 比对**分别构造**的两个列表，只要两次构造跨了毫秒边界，
        //    时间戳就不同 ⇒ 随机红（Wave 41 起潜伏；因 fail-fast 在 core-data 就中止、
        //    feature-chat 用例从未跑到，直到 W48 用 `--continue` 才现形）。
        //    生产语义：`mergeProcessIntoVisible` 返回的是**入参同一批实例**（`visible + proc`，
        //    不重建消息）⇒ 时间戳被原样保留、本可比。故修法 = 给入参与 expected **显式钉同一
        //    固定时间戳**：断言仍覆盖 `createdAtMillis`（不是把它排除），且结果确定。
        val ts = 1_700_000_000_000L
        val visible = listOf(
            ChatMessage(id = "u1", role = Role.USER, text = "帮我查一下", createdAtMillis = ts),
            ChatMessage(id = "m1", role = Role.MODEL, text = "最终答案甲", createdAtMillis = ts),
        )
        val process = listOf(
            ChatMessage(id = "p1", role = Role.MODEL, text = "中间调用 current_time", createdAtMillis = ts),
            ChatMessage(id = "p2", role = Role.TOOL, text = "工具执行结果甲", createdAtMillis = ts),
            ChatMessage(id = "p3", role = Role.MODEL, text = "最终答案甲", createdAtMillis = ts),
        )
        val merged = mergeProcessIntoVisible(visible, process)
        // 最终答案甲与可见历史重复 → 剔；TOOL 与中间消息 → 全保留；顺序：可见在前。
        assertEquals(
            listOf(
                ChatMessage(id = "u1", role = Role.USER, text = "帮我查一下", createdAtMillis = ts),
                ChatMessage(id = "m1", role = Role.MODEL, text = "最终答案甲", createdAtMillis = ts),
                ChatMessage(id = "p1", role = Role.MODEL, text = "中间调用 current_time", createdAtMillis = ts),
                ChatMessage(id = "p2", role = Role.TOOL, text = "工具执行结果甲", createdAtMillis = ts),
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
        assertEquals(visible, historyWithProcess(visible, root, "不存在的会话", Int.MAX_VALUE))
        // 目录存在但没有任何 journal 文件。
        assertTrue(File(root, "some-cid").mkdirs())
        assertEquals(visible, historyWithProcess(visible, root, "some-cid", Int.MAX_VALUE))
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
        val merged = historyWithProcess(visible, root, cid, Int.MAX_VALUE)
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

    @Test fun `预算内全保留 —— 与开窗前行为一致（回归锚）`() {
        val root = tempRoot("c3-window-all")
        val cid = "cid-w1"
        assertTrue(File(root, cid).mkdirs())
        val runDir = File(root, cid)
        val early = runFixture("甲", "答案甲")
        val late = runFixture("乙", "答案乙")
        writeRun(runDir, "run_1700000000000", early)
        writeRun(runDir, "run_1700000001000", late)

        val visible = listOf(ChatMessage(id = "u1", role = Role.USER, text = "问题"))
        // 预算 = 两个 run 的实算合计 → 全保留，且与「无上限回灌」的旧行为逐条一致。
        val merged = historyWithProcess(visible, root, cid, costOf(early, late))
        assertEquals(
            listOf("问题", "调用甲", "结果甲", "答案甲", "调用乙", "结果乙", "答案乙"),
            merged.map { it.text },
        )
    }

    @Test fun `预算截断 —— 丢最旧 run 保序且 run 原子`() {
        val root = tempRoot("c3-window-trim")
        val cid = "cid-w2"
        assertTrue(File(root, cid).mkdirs())
        val runDir = File(root, cid)
        val oldest = runFixture("甲", "答案甲")
        val middle = runFixture("乙", "答案乙")
        val newest = runFixture("丙", "答案丙")
        writeRun(runDir, "run_1700000000000", oldest)
        writeRun(runDir, "run_1700000001000", middle)
        writeRun(runDir, "run_1700000002000", newest)

        val visible = listOf(ChatMessage(id = "u1", role = Role.USER, text = "问题"))
        // 预算只装得下最新的两个 run（新者优先）→ 最旧 run 被裁、其余完整保留。
        val merged = historyWithProcess(visible, root, cid, costOf(middle, newest))
        val texts = merged.map { it.text }
        // 保序：可见历史在前，kept run 按时间序（乙 → 丙）排在后面。
        assertEquals(
            listOf("问题", "调用乙", "结果乙", "答案乙", "调用丙", "结果丙", "答案丙"),
            texts,
        )
        // run 原子：被保留 run 的 M(toolCalls) 与 TOOL 成对存活（无半 run）。
        assertRunFullyKept(texts, "乙", "答案乙")
        assertRunFullyKept(texts, "丙", "答案丙")
        assertRunAbsent(texts, "甲", "答案甲")
    }

    @Test fun `最新 run 单独超预算 —— 保底完整保留`() {
        val root = tempRoot("c3-window-newest")
        val cid = "cid-w3"
        assertTrue(File(root, cid).mkdirs())
        val runDir = File(root, cid)
        val oldest = runFixture("甲", "答案甲")
        val newest = runFixture("乙", "答案乙")
        writeRun(runDir, "run_1700000000000", oldest)
        writeRun(runDir, "run_1700000001000", newest)

        val visible = listOf(ChatMessage(id = "u1", role = Role.USER, text = "问题"))
        // 预算比最新 run 自己还小 → 最新 run 无条件保底（不参与预算判定），
        // 更旧 run 一个都不读。
        val merged = historyWithProcess(visible, root, cid, costOf(newest) - 1)
        val texts = merged.map { it.text }
        assertRunFullyKept(texts, "乙", "答案乙")
        assertRunAbsent(texts, "甲", "答案甲")
    }

    @Test fun `预算为 0 —— 仍保底最新 run（语义文档化：0 只是不读更旧 run）`() {
        val root = tempRoot("c3-window-zero")
        val cid = "cid-w4"
        assertTrue(File(root, cid).mkdirs())
        val runDir = File(root, cid)
        val oldest = runFixture("甲", "答案甲")
        val newest = runFixture("乙", "答案乙")
        writeRun(runDir, "run_1700000000000", oldest)
        writeRun(runDir, "run_1700000001000", newest)

        val visible = listOf(ChatMessage(id = "u1", role = Role.USER, text = "问题"))
        // 语义钉死：预算 = 0（visible 已吃满压缩预算）≠「一条过程消息都不给」。
        // 最新 run 的工具残骸是当前任务最相关的上下文，无条件保留；预算 0 只意味着
        // 不再读任何更旧 run。这是「最新 run 保底」语义在极端输入下的边界行为。
        val merged = historyWithProcess(visible, root, cid, 0)
        val texts = merged.map { it.text }
        assertRunFullyKept(texts, "乙", "答案乙")
        assertRunAbsent(texts, "甲", "答案甲")
    }

    @Test fun `开窗后 kept run 的最终 MODEL 命中可见历史去重口径被剔`() {
        val root = tempRoot("c3-window-dedup")
        val cid = "cid-w5"
        assertTrue(File(root, cid).mkdirs())
        val runDir = File(root, cid)
        val oldest = runFixture("甲", "答案甲")
        val newest = runFixture("乙", "答案乙")
        writeRun(runDir, "run_1700000000000", oldest)
        writeRun(runDir, "run_1700000001000", newest)

        // 可见历史里已有与最新 run 最终答案同 role+text 的 MODEL 消息
        // （commitAssistant 落库形态）→ merge 层按 onRecover 口径去剔。
        val visible = listOf(
            ChatMessage(id = "u1", role = Role.USER, text = "问题"),
            ChatMessage(id = "m1", role = Role.MODEL, text = "答案乙"),
        )
        // 预算只装最新 run：开窗 + 去重同时生效。
        val merged = historyWithProcess(visible, root, cid, costOf(newest))
        assertEquals(
            listOf("问题", "答案乙", "调用乙", "结果乙"),
            merged.map { it.text },
        )
        assertRunAbsent(merged.map { it.text }, "甲", "答案甲")
        // TOOL 与中间 toolCall 不在可见历史 → 永不被剔（去重只剔 MODEL）。
        assertRunFullyKept(merged.map { it.text }, "乙", "答案乙")
    }

    @Test fun `用户丢弃的 run 也读 —— dismissed 归档同样回灌，旧命名归档跳过`() {
        val root = tempRoot("c3-dismissed")
        val cid = "cid-2"
        assertTrue(File(root, cid).mkdirs())
        val runDir = File(root, cid)
        writeRun(runDir, "run_1700000000000", processFixture("被打断的答案"))
        // markDismissed 同款改名：runId.dismissed.jsonl。
        val raw = File(runDir, "run_1700000000000.jsonl")
        assertTrue(raw.renameTo(File(runDir, "run_1700000000000.dismissed.jsonl")))
        // 旧命名归档（.jsonl.archived / .jsonl.dismissed）：无法经 open() 还原
        // 文件名，必须整体跳过 —— 塞进去内容，验证它不会混进结果。
        val archivedJunk = listOf(
            "{\"seq\":1,\"atMillis\":1,\"kind\":\"message\"," +
                "\"payload\":{\"role\":\"TOOL\",\"text\":\"归档垃圾甲\"}}\n",
        )
        File(runDir, "run_1700000002000.jsonl.archived").writeText(archivedJunk.joinToString(""))
        File(runDir, "run_1700000003000.jsonl.dismissed").writeText(archivedJunk.joinToString(""))

        val visible = listOf(ChatMessage(id = "u1", role = Role.USER, text = "第一个问题"))
        val merged = historyWithProcess(visible, root, cid, Int.MAX_VALUE)
        // 最终 MODEL 答案「被打断的答案」不在可见历史 → 保留；TOOL / 中间消息同样在。
        val texts = merged.map { it.text }
        assertTrue("被打断的答案" in texts, "dismissed run 的过程消息必须回灌")
        assertTrue("工具执行结果甲" in texts)
        // 旧命名归档整体跳过（文件名去 .jsonl 后缀后 open 拼不回去，读不到）。
        assertFalse("归档垃圾甲" in texts, ".jsonl.archived / .jsonl.dismissed 必须跳过")
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
        val merged = historyWithProcess(visible, root, cid, Int.MAX_VALUE)
        assertTrue("正常答案" in merged.map { it.text }, "坏行只跳过自身，好行必须完整保留")
    }
}
