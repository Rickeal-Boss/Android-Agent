package com.rickeal.agent.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ProcessEngineResilienceStore] 的语义单测（W56，治 W55 审查 P2#1）。
 *
 * 为什么值得写：store 是「自愈置位跨引擎实例存活」的唯一载体 —— 它若读回默认值 /
 * 串键 / 覆盖丢字段，W55 P2#1（证伪随 evict 清零，每次 rebuild 重走必炸路径）就会
 * 静默复发。三个用例分别钉住：默认读、快照整体覆盖、cid 隔离。纯 JVM、零 native。
 */
class EngineResilienceStoreTest {

    private val store = ProcessEngineResilienceStore()

    @Test
    fun `未写入的 cid 读回默认快照`() {
        val state = store.read("cid-a")
        assertFalse(state.nativeToolsRejected)
        assertEquals(0, state.templateRebuildCount)
    }

    @Test
    fun `写入后读回同一快照且覆盖写入取最新`() {
        store.write(
            "cid-a",
            EngineResilienceState(nativeToolsRejected = true, templateRebuildCount = 2),
        )
        val first = store.read("cid-a")
        assertTrue(first.nativeToolsRejected)
        assertEquals(2, first.templateRebuildCount)

        // 覆盖写入 = 快照整体替换（引擎侧每轮双写用的就是同一语义）。
        store.write(
            "cid-a",
            EngineResilienceState(nativeToolsRejected = false, templateRebuildCount = 3),
        )
        val second = store.read("cid-a")
        assertFalse(second.nativeToolsRejected)
        assertEquals(3, second.templateRebuildCount)
    }

    @Test
    fun `不同 cid 互不串扰`() {
        store.write(
            "cid-a",
            EngineResilienceState(nativeToolsRejected = true, templateRebuildCount = 1),
        )
        // 换会话即新键（W56 方案 §2.2：cid 天然隔离）—— 旧会话的证伪/计数不得漏进新会话。
        val other = store.read("cid-b")
        assertFalse(other.nativeToolsRejected)
        assertEquals(0, other.templateRebuildCount)
    }

    @Test
    fun `clear 单键后读回默认快照`() {
        store.write(
            "cid-a",
            EngineResilienceState(nativeToolsRejected = true, templateRebuildCount = 5),
        )
        store.clear("cid-a")
        // 清键（W58 修补 B）= 删除该 cid 快照：读回回到默认值（复位发生在 merge 之前，
        // 由用户显式动作触发，单调不变式不破）。
        val state = store.read("cid-a")
        assertFalse(state.nativeToolsRejected)
        assertEquals(0, state.templateRebuildCount)
    }

    @Test
    fun `clearAll 清空全部键后读回默认快照`() {
        store.write(
            "cid-a",
            EngineResilienceState(nativeToolsRejected = true, templateRebuildCount = 5),
        )
        store.write(
            "cid-b",
            EngineResilienceState(nativeToolsRejected = false, templateRebuildCount = 7),
        )
        store.clearAll()
        // 引擎「开关 OFF→ON」复位块（W58 修补 B）用的就是全清语义。
        for (cid in listOf("cid-a", "cid-b")) {
            val state = store.read(cid)
            assertFalse(state.nativeToolsRejected)
            assertEquals(0, state.templateRebuildCount)
        }
    }
}
