package com.rickeal.agent.core.data.perf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 采样线程生命周期的**确定性**不变量（Wave 30 §2.2，复审 7 号补）。
 *
 * 只断言「句柄」与「引用计数」，不碰时间：
 * - 不断言线程已退出 —— reaper 在后台 join，等线程死必然偶发失败；
 *   空闲零线程由真机 `adb shell ps -T <pid>` 验收。
 * - 不用 `Thread.activeCount` —— 那是 JVM 全局计数，Gradle 并行跑其他模块的用例时会飘。
 *
 * 构造传 `null` Context（测试缝，见类 KDoc）：本模块 JVM 单测无 Robolectric，
 * 构造不出 Context；可用内存恒 0 不影响本组断言（生命周期不读 Context）。
 */
class PerfLifecycleTest {

    @Test
    fun `acquire 启动 daemon 命名线程`() {
        val manager = PerformanceMonitorManager(null)
        manager.acquire("test")
        val thread = manager.workerHandleForTest()
        assertNotNull(thread, "acquire 后必须持有句柄")
        assertEquals("perf-sampler", thread.name)
        assertTrue(thread.isDaemon, "非 daemon 会拖住进程退出")
        manager.release("test")
    }

    @Test
    fun `release 归零后句柄同步置 null`() {
        val manager = PerformanceMonitorManager(null)
        manager.acquire("test")
        assertNotNull(manager.workerHandleForTest())
        manager.release("test")
        // 句柄在 stopWorker 里就地摘除（join 走后台），所以这条断言零时序依赖。
        assertNull(manager.workerHandleForTest(), "归零即摘句柄，不等待 reaper")
    }

    @Test
    fun `acquire 幂等 —— 二次 acquire 不新建线程，两次 release 才归零`() {
        val manager = PerformanceMonitorManager(null)
        manager.acquire("observer-a")
        val first = manager.workerHandleForTest()
        manager.acquire("observer-b")
        assertSame(first, manager.workerHandleForTest(), "第二个观测者不得新建线程")

        manager.release("observer-a")
        assertSame(first, manager.workerHandleForTest(), "还剩一个观测者，不该停")

        manager.release("observer-b")
        assertNull(manager.workerHandleForTest(), "最后一个观测者退出才停")
    }

    @Test
    fun `release 过计数 —— 钳回 0 且句柄不复活`() {
        val manager = PerformanceMonitorManager(null)
        manager.acquire("test")
        manager.release("test")
        assertNull(manager.workerHandleForTest())
        // 多 release：计数归负按 0 钳制并记 warn，不抛、不复活线程。
        manager.release("test")
        assertNull(manager.workerHandleForTest())
    }

    @Test
    fun `新窗口重建线程 —— 归零后再 acquire 拿到新句柄`() {
        val manager = PerformanceMonitorManager(null)
        manager.acquire("window-1")
        val first = manager.workerHandleForTest()
        manager.release("window-1")
        manager.acquire("window-2")
        val second = manager.workerHandleForTest()
        assertNotNull(second)
        assertTrue(first !== second, "新窗口必须是新线程（旧线程已 interrupt 不可复用）")
        manager.release("window-2")
    }
}
