package com.rickeal.agent.core.data.perf

import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 采样窗口的**写入侧**不变量（W82 P1：`resetWindow` 跨线程竞态）。
 *
 * ## 背景
 * `acquire(reason, clearSamples = true)` 在计数 > 1 时由**调用方线程**直接走
 * `resetWindow()`（`ChatRunCoordinator` 的 run 起点 = 主线程），而 `perf-sampler` 线程
 * 同时在往同一个窗口写样本。旧实现写入侧是「读 value → toMutableList → add → 赋值」的
 * **非原子 RMW**：与清空交错时会把清空前读到的旧快照整体写回 —— 已清空的窗口被旧样本
 * 「复活」。现改为 `MutableStateFlow.update`（CAS 重试循环）。
 *
 * ## 为什么用 [PerformanceMonitorManager.appendSampleForTest] 而不是等采样线程
 * 生产写入点是 `takeSample()`，它读 `/proc/self/stat`；JVM 单测跑在没有 /proc 的机器上
 * （Windows 开发机必空）会直接早退，一个样本都写不进来 ⇒ 并发用例退化成 vacuous。
 * 测试缝与生产**共用同一行** `appendSample()`，所以竞态覆盖不打折。
 *
 * ## 断言口径（刻意不含时序断言）
 * - 只断言「不抛异常」与「窗口不超过环形容量」这类**确定性**不变量；
 * - **不**断言「重置后窗口里绝无旧样本」—— 那是时序竞争态，必然偶发失败（类 KDoc 已申报
 *   「上限一个样本」的残留偏差是可接受的）。
 */
class PerfConcurrencyTest {

    @Test
    fun `并发 hammer —— acquire(clearSamples) 与样本写入交错不抛异常且窗口不超容量`() {
        val manager = PerformanceMonitorManager(null)
        // 先占住一个「页面窗口」（n = 1）并常驻：重置线程的 acquire(clearSamples = true)
        // 因此落在 n > 1 分支 ⇒ 直接走 resetWindow()（正是生产调用点的形态），
        // 也不会每轮都重建一条采样线程（那会让本用例变成线程创建压测）。
        manager.acquire("holder", clearSamples = false)
        val failure = AtomicReference<Throwable?>(null)
        val appender = Thread({
            try {
                repeat(APPEND_ROUNDS) { index -> manager.appendSampleForTest(sampleOf(index)) }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }, "perf-hammer-append").apply { isDaemon = true }
        val resetter = Thread({
            try {
                repeat(RESET_ROUNDS) {
                    manager.acquire("hammer", clearSamples = true)
                    manager.release("hammer")
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }, "perf-hammer-reset").apply { isDaemon = true }
        try {
            appender.start()
            resetter.start()
            appender.join(HAMMER_TIMEOUT_MILLIS)
            resetter.join(HAMMER_TIMEOUT_MILLIS)
        } finally {
            // 兜底释放：留下一条常驻 perf-sampler 线程会污染同 JVM 里的其它用例。
            manager.release("holder")
        }

        assertNull(failure.get(), "并发交错里不允许抛异常（CAS 重试不该抛出 ConcurrentModificationException）")
        val size = manager.samples.value.size
        assertTrue(
            size <= PerformanceMonitorManager.HISTORY_CAPACITY,
            "窗口样本数 $size 超过环形容量 ${PerformanceMonitorManager.HISTORY_CAPACITY}：裁剪失效",
        )
    }

    @Test
    fun `环形裁剪 —— 顺序写入超容量后只保留最新 N 个`() {
        val manager = PerformanceMonitorManager(null)
        val total = PerformanceMonitorManager.HISTORY_CAPACITY * 3
        repeat(total) { index -> manager.appendSampleForTest(sampleOf(index)) }

        val samples = manager.samples.value
        assertEquals(PerformanceMonitorManager.HISTORY_CAPACITY, samples.size, "容量必须被硬裁到 HISTORY_CAPACITY")
        // 「保留最新」而不是「保留最旧」：最后一个必须是最后写入的那条（takeLast 语义）。
        assertEquals((total - 1).toLong(), samples.last().atElapsedMillis)
        assertEquals(
            (total - PerformanceMonitorManager.HISTORY_CAPACITY).toLong(),
            samples.first().atElapsedMillis,
        )
    }

    /** 只关心 `atElapsedMillis` 与样本个数，其余字段给稳定值。 */
    private fun sampleOf(index: Int) = PerfSample(
        cpuSeconds = index.toDouble(),
        cpuPercent = null,
        pssKb = 0L,
        availMemBytes = 0L,
        atElapsedMillis = index.toLong(),
    )

    private companion object {
        /** 写入轮数：远大于容量，保证裁剪路径被反复走到。 */
        const val APPEND_ROUNDS = 3_000

        /** 重置轮数：每轮一次 resetWindow（不建线程，只做 CAS 交错）。 */
        const val RESET_ROUNDS = 200

        /** join 上限：本用例的活都是纯内存操作，10s 足够；超时也不会误报（无时序断言）。 */
        const val HAMMER_TIMEOUT_MILLIS = 10_000L
    }
}
