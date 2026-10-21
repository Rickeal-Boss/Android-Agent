package com.rickeal.agent.core.data.notify

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OngoingNotificationSlot] 的 JVM 单测（W78 D2）。
 *
 * 为什么这个对象值得单测：它是**进程级单例状态**，错了的形态不是崩溃而是
 * 「速度文案投进已销毁的服务」或「notifier 不再自己发通知」—— 两者都表现为
 * 「用户什么也看不到」，没有异常、没有日志，只有真机盯通知栏才能发现。
 *
 * ⚠️ 纪律：每个用例结束都必须 [OngoingNotificationSlot.detach] 掉自己注册的 owner。
 * 该对象的状态跨用例存活（同一 JVM），不清理就会让后面的用例在「上一个用例的 sink」
 * 上通过 —— 典型的假绿。
 */
class OngoingNotificationSlotTest {

    /** 本用例注册过的 owner，[tearDown] 里逐个注销（只 detach 自己，绝不污染他人）。 */
    private val registeredOwners = mutableListOf<Any>()

    @After
    fun tearDown() {
        registeredOwners.forEach { OngoingNotificationSlot.detach(it) }
        registeredOwners.clear()
    }

    private fun newOwner(): Any = Any().also { registeredOwners += it }

    @Test
    fun attachRegistersSinkAndPublishDeliversText() {
        val owner = newOwner()
        val delivered = mutableListOf<String>()
        OngoingNotificationSlot.attach(owner) { text ->
            delivered += text
            true
        }

        assertTrue("attach 之后槽必须是活跃态", OngoingNotificationSlot.isActive())
        assertTrue("活跃态下 publish 必须返回 true", OngoingNotificationSlot.publish("1.0 token/s"))
        assertEquals(listOf("1.0 token/s"), delivered)
    }

    @Test
    fun detachClearsSinkSoPublishFallsBack() {
        val owner = newOwner()
        OngoingNotificationSlot.attach(owner) { true }

        OngoingNotificationSlot.detach(owner)

        // 返回 false 是 notifier 的兜底信号：它要回落自己发 1001，否则速度通知整轮消失。
        assertFalse("detach 之后槽必须不再活跃", OngoingNotificationSlot.isActive())
        assertFalse("无活跃 FGS 时 publish 必须返回 false", OngoingNotificationSlot.publish("1.0 token/s"))
    }

    @Test
    fun detachByNonOwnerKeepsSinkIntact() {
        // 新旧服务交错的回归面：旧服务的 onDestroy 晚于新服务的 onStartCommand 时，
        // 旧服务不得把新服务登记的 sink 清掉。用**两个不同对象**当 owner。
        val newService = newOwner()
        val oldService = newOwner()
        val delivered = mutableListOf<String>()
        OngoingNotificationSlot.attach(newService) { text ->
            delivered += text
            true
        }

        OngoingNotificationSlot.detach(oldService)

        assertTrue("非 owner 的 detach 不得清空槽", OngoingNotificationSlot.isActive())
        assertTrue(OngoingNotificationSlot.publish("2.0 token/s"))
        assertEquals(listOf("2.0 token/s"), delivered)
    }
}
