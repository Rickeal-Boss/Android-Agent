package com.rickeal.agent.core.data.notify

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [OngoingNotificationSlot] 的 JVM 单测（W78 D2 / 硬点 2）。
 *
 * 为什么值得单测：它是**进程级 object**，错了的形态不是崩溃而是「速度文案投进已销毁的
 * 服务」或「notifier 不再自己发通知」—— 都表现为「用户什么也看不到」，没有异常、没有日志。
 *
 * ## 用例隔离（硬点 2，写死）
 *
 * 该 object 的状态在**同一 JVM 内跨用例存活**：某用例 `attach` 后断言失败退出，残留的 sink
 * 会让后续用例读到别人的状态而**假绿**。所以三条纪律：
 * ① 每个用例一个**新的** owner 实例（identity 语义靠它）；
 * ② `@Before` 先自证干净 —— 上一个用例漏了清场就立刻红（fail-closed，而不是被静默 reset：
 *    「清空」会掩盖泄漏，「断言」会暴露泄漏）；
 * ③ `@After` 按 owner 身份 detach，并再断言已清空。
 *
 * 刻意不提供 `resetForTest()`：生产代码不装测试专用 API。
 */
class OngoingNotificationSlotTest {

    /** 每个用例一个新的 owner 实例（JUnit4 每个 @Test 都新建测试类实例）。 */
    private val owner = Any()

    @Before
    fun assertCleanState() {
        assertFalse("上一个用例泄漏了 sink（@After 没清场）", OngoingNotificationSlot.isActive())
    }

    @After
    fun cleanUp() {
        OngoingNotificationSlot.detach(owner)
        assertFalse("清场失败：sink 仍在", OngoingNotificationSlot.isActive())
    }

    @Test
    fun attachDeliversTextAndReportsAccepted() {
        val delivered = mutableListOf<String>()
        OngoingNotificationSlot.attach(owner) { text ->
            delivered += text
            true
        }

        assertTrue("attach 之后槽必须是活跃态", OngoingNotificationSlot.isActive())
        assertTrue("活跃态下 publish 必须返回 true", OngoingNotificationSlot.publish("x"))
        assertTrue("sink 必须收到文案", delivered == listOf("x"))
    }

    @Test
    fun publishReturnsFalseAfterDetach() {
        OngoingNotificationSlot.attach(owner) { true }

        OngoingNotificationSlot.detach(owner)

        // 返回 false 是 notifier 的兜底信号：它要回落自己发 1001，否则速度通知整轮消失。
        assertFalse("detach 之后槽必须不再活跃", OngoingNotificationSlot.isActive())
        assertFalse("无活跃 FGS 时 publish 必须返回 false", OngoingNotificationSlot.publish("x"))
    }

    @Test
    fun detachWithAnotherOwnerKeepsSink() {
        // 新旧服务交错的回归面：旧服务 onDestroy 晚于新服务 onStartCommand 时，旧服务不得
        // 清掉新服务的 sink。owner 必须是**两个 Any() 实例** —— `Unit` / 字符串常量在 Kotlin
        // 里可能被常量池合并 ⇒ identity 断言会假过。
        val anotherOwner = Any()
        val delivered = mutableListOf<String>()
        OngoingNotificationSlot.attach(owner) { text ->
            delivered += text
            true
        }

        OngoingNotificationSlot.detach(anotherOwner)

        assertTrue("非 owner 的 detach 不得清空槽", OngoingNotificationSlot.isActive())
        assertTrue(OngoingNotificationSlot.publish("y"))
        assertTrue(delivered == listOf("y"))

        OngoingNotificationSlot.detach(owner)
        assertFalse("owner 自己 detach 后必须清空", OngoingNotificationSlot.isActive())
    }
}
