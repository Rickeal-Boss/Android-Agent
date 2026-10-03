package com.rickeal.agent.feature.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [AssistantPersistenceState] 的纯逻辑测试（Wave 46「模型回复不落盘」根因修复的回归锚）。
 *
 * 为什么能跑在 JVM 上：被测的是纯 Kotlin `internal` 顶层函数 + data class，**无任何
 * Android / Compose / ViewModel 依赖**。生产路径 [ChatRunCoordinator] 真实调用这些函数
 * （`MessageCommitted` / `commitAssistant` / run 起点），故测试覆盖的是**真实逻辑而非镜像**
 * —— 与本仓既有范式一致（`applyTerminalEvent` / `mergeProcessIntoVisible`）。
 *
 * 覆盖 §4.3 的 6 组语义：
 *  1. 正常终态落盘 1 次（富消息已由 MessageCommitted 落，Finished 决策 false）；
 *  2. MessageCommitted + Finished 不双落；
 *  3. 取消路径仍落盘（onStop 与 Cancelled 竞态，run 级文本去重挡第二次）；
 *  4. 连续两轮相同回答都落（每 run 起点清零 ⇒ 不跨 run 误吞）；
 *  5. MaxRounds（本 run 无 MessageCommitted）落 1 次；
 *  6. 生命周期清零（beginRun 后不跨 run 误吞）。
 *
 * 断言收尾一律用返回 `Unit` 的函数（`assertEquals` / `assertTrue` / `assertFalse` /
 * `assertNull`）—— `assertNotNull` / `assertIs` 等会返回值，以它们收尾会让 JUnit 4
 * 误判方法签名非 `void`，整类 `initializationError`（一个都不跑）。
 */
class AssistantPersistenceTest {

    private companion object {
        /** 助手回答文本（用常量便于表达「两轮回答相同」）。 */
        const val ANSWER = "同一个回答"
        /** 本次 run 的 USER 消息 id（MaxRounds / 取消路径下 messages.lastOrNull() 的值）。 */
        const val USER_ID = "user-1"
    }

    /**
     * 用例 1：正常终态落盘恰好 1 次。
     *
     * 时序：run 起点清零 → `MessageCommitted` 落富消息（记 id）→ `Finished` 用 id 判据
     * 跳过 ⇒ 决策 false。即「富消息是唯一落库点」，落盘次数 = 1。
     */
    @Test
    fun 正常终态落盘恰好一次() {
        var state = AssistantPersistenceState().beginRun()
        state = state.onMessageCommitted("x1")
        assertEquals("x1", state.committedMessageId, "富消息 id 应被记住")

        assertFalse(
            state.shouldCommitAssistant(lastMessageId = "x1", text = ANSWER),
            "MessageCommitted 已落富消息，Finished 不应再落",
        )
    }

    /**
     * 用例 2：`MessageCommitted` 与 `Finished` 不双落。
     *
     * 与用例 1 同源，但显式钉住「两个终态事件只产生 1 条落库」——回归 bug 正是这里被跳过。
     */
    @Test
    fun MessageCommitted与Finished不双落() {
        var state = AssistantPersistenceState().beginRun()
        state = state.onMessageCommitted("x1")

        // MessageCommitted 落库（富消息，计 1 次）；Finished 到达时的决策必须是 false。
        val finishedWouldCommit = state.shouldCommitAssistant(lastMessageId = "x1", text = ANSWER)
        assertFalse(finishedWouldCommit, "Finished 不应重复落库（否则 UI 双气泡 + 文件两份）")
    }

    /**
     * 用例 3：取消路径仍落盘（竞态去重）。
     *
     * 本 run 无 `MessageCommitted`（取消路径不发）⇒ 首到者（onStop 或 Cancelled）决策 true
     * 并落库记文本；后到者文本相同 ⇒ 决策 false。恰好 1 次。
     */
    @Test
    fun 取消路径仍落盘且竞态只落一次() {
        var state = AssistantPersistenceState().beginRun()

        // 先到者（例如 onStop）：messages 尾是 USER ⇒ 决策 true。
        val firstArrival = state.shouldCommitAssistant(lastMessageId = USER_ID, text = ANSWER)
        assertTrue(firstArrival, "取消路径首到者应落库")
        state = state.onCommitted(ANSWER)

        // 后到者（例如 AgentEvent.Cancelled）：文本相同 ⇒ 决策 false。
        val secondArrival = state.shouldCommitAssistant(lastMessageId = "model-x", text = ANSWER)
        assertFalse(secondArrival, "竞态后到者不应重复落库")
    }

    /**
     * 用例 4：连续两轮相同回答都落。
     *
     * run1 与 run2 文本相同、富消息 id 不同。每 run 起点 `beginRun()` 清零 ⇒ 文本判据不
     * 跨 run 生效 ⇒ 两轮各由 `MessageCommitted` 落库，共 2 条（不被吞）。
     */
    @Test
    fun 连续两轮相同回答都落() {
        // run1
        var state = AssistantPersistenceState().beginRun()
        state = state.onMessageCommitted("x1")
        assertFalse(state.shouldCommitAssistant(lastMessageId = "x1", text = ANSWER))

        // run2 起点清零
        state = state.beginRun()
        assertNull(state.committedMessageId, "run 起点必须清零 id 判据")
        assertNull(state.lastCommittedText, "run 起点必须清零文本判据")

        // run2：文本与 run1 相同，但仍应落库（id 不同）
        state = state.onMessageCommitted("x2")
        assertFalse(
            state.shouldCommitAssistant(lastMessageId = "x2", text = ANSWER),
            "第二轮富消息已落，Finished 决策 false",
        )
        assertEquals("x2", state.committedMessageId)
    }

    /**
     * 用例 5：MaxRounds（本 run 无 `MessageCommitted`）落 1 次。
     *
     * 轮次耗尽路径不装配富消息、不发 `MessageCommitted` ⇒ `messages.lastOrNull()` 是本 run
     * 的 USER ⇒ 文本判据不命中 ⇒ `Finished` 落 text 版（决策 true）。
     */
    @Test
    fun 轮次耗尽无富消息时落一次() {
        val state = AssistantPersistenceState().beginRun()
        assertTrue(
            state.shouldCommitAssistant(lastMessageId = USER_ID, text = ANSWER),
            "MaxRounds 无 MessageCommitted，Finished 应落 text 版",
        )
    }

    /**
     * 用例 6：生命周期清零（不跨 run 误吞）。
     *
     * run1 落了文本 A（`onCommitted(A)`）；`beginRun()` 后若不清零，run2 拿到同样文本 A 会
     * 被文本判据误吞（静默丢回答）。清零后决策 true。
     */
    @Test
    fun 生命周期清零后不跨run误吞() {
        var state = AssistantPersistenceState().beginRun()
        state = state.onCommitted(ANSWER)
        assertEquals(ANSWER, state.lastCommittedText)

        // 新 run 起点清零
        state = state.beginRun()
        assertTrue(
            state.shouldCommitAssistant(lastMessageId = null, text = ANSWER),
            "清零后同样的文本在新 run 应重新落库",
        )
    }
}
