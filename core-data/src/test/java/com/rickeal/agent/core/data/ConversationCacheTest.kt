package com.rickeal.agent.core.data

import com.rickeal.agent.core.model.Conversation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ConversationCache] 的 JVM 单测（W80-03 Phase A，审查 P1/P2 修法后口径）。
 *
 * 缓存是 appendMessage 热路径上「每条消息 2 次全量读 → 稳态 0 次」优化的载体，
 * 判据一旦写错就是双倍事故：要么缓存了不该缓存的（已删会话复活 / 陈旧快照盖新消息），
 * 要么 LRU 淘汰错了（把活跃会话挤出去）。所以在这里把命中 / 淘汰 / 失效钉死。
 * parseFailed 二态已随审查修法删除（瞬时读失败不熔断，见 [ConversationCache] KDoc）。
 * 纯内存数据结构，不触 Android、不做 IO。
 */
class ConversationCacheTest {

    private fun conv(id: String) = Conversation(id = id)

    // ── 命中语义：put → get 原样返回；未缓存返回 null ─────────────────────

    @Test
    fun getReturnsWhatWasPut() {
        val cache = ConversationCache()
        val c = conv("a")
        cache.put("a", c)
        assertEquals(c, cache.get("a"), "put 后 get 应命中同一会话")
    }

    @Test
    fun getMissReturnsNull() {
        val cache = ConversationCache()
        assertNull(cache.get("never-put"), "未缓存的 id 应返回 null")
    }

    // ── LRU：get 刷新访问序，put 超限淘汰最久未访问者 ──────────────────────

    @Test
    fun evictsLeastRecentlyPutBeyondLimit() {
        val cache = ConversationCache(maxEntries = 2)
        cache.put("a", conv("a"))
        cache.put("b", conv("b"))
        cache.put("c", conv("c"))  // a 是最久未访问的，应被淘汰
        assertNull(cache.get("a"), "超限后最久未访问的 a 应被淘汰")
        assertEquals("b", cache.get("b")?.id)
        assertEquals("c", cache.get("c")?.id)
    }

    @Test
    fun getRefreshesAccessOrderSoTouchedEntrySurvives() {
        val cache = ConversationCache(maxEntries = 2)
        cache.put("a", conv("a"))
        cache.put("b", conv("b"))
        assertEquals("a", cache.get("a")?.id)  // 访问 a → b 变成最久未访问
        cache.put("c", conv("c"))              // b 应被淘汰，a 因刚被访问存活
        assertEquals("a", cache.get("a")?.id, "刚被 get 过的 a 不得被淘汰")
        assertNull(cache.get("b"), "未再访问的 b 应被淘汰")
        assertEquals("c", cache.get("c")?.id)
    }

    @Test
    fun putSameIdRefreshesAndOverwritesWithoutEvictingOthers() {
        val cache = ConversationCache(maxEntries = 2)
        cache.put("a", conv("a"))
        cache.put("b", conv("b"))
        cache.put("a", conv("a2"))  // 覆盖 + 刷新访问序
        assertEquals("a2", cache.get("a")?.id, "同 id 重复 put 应覆盖为新值（save 写穿回填新全文）")
        assertEquals("b", cache.get("b")?.id, "覆盖 put 不得把 b 挤出（上限 2 只装 2 条）")
    }

    // ── 失效与清空 ────────────────────────────────────────────────────────

    @Test
    fun invalidateRemovesConversationEntry() {
        val cache = ConversationCache()
        cache.put("a", conv("a"))
        assertEquals("a", cache.get("a")?.id)
        cache.invalidate("a")
        assertNull(cache.get("a"), "invalidate 后不得再命中（delete 语义：已删会话不得复活）")
    }

    @Test
    fun invalidateUnknownIdIsNoOp() {
        val cache = ConversationCache()
        cache.put("a", conv("a"))
        cache.invalidate("never-put")  // 不得抛异常、不得影响其他条目
        assertEquals("a", cache.get("a")?.id)
    }

    @Test
    fun clearRemovesAllEntries() {
        val cache = ConversationCache()
        cache.put("a", conv("a"))
        cache.put("b", conv("b"))
        cache.clear()
        assertNull(cache.get("a"), "clear 后所有会话不得再命中")
        assertNull(cache.get("b"))
    }

    // ── 构造参数防御 ──────────────────────────────────────────────────────

    @Test
    fun rejectsNonPositiveLimits() {
        // 断言绑 val 收尾为 Unit 型断言（arch-guard：@Test 方法必须返回 void，
        // 裸 assertFailsWith 收尾会让整个测试类 initializationError）。
        val zero = assertFailsWith<IllegalArgumentException> { ConversationCache(maxEntries = 0) }
        val negative = assertFailsWith<IllegalArgumentException> { ConversationCache(maxEntries = -1) }
        val capacity = assertFailsWith<IllegalArgumentException> { ConversationCache(initialCapacity = 0) }
        assertTrue(zero.message!!.contains("maxEntries"), "zero limit 应点名 maxEntries")
        assertTrue(negative.message!!.contains("maxEntries"), "negative limit 应点名 maxEntries")
        assertTrue(capacity.message!!.contains("initialCapacity"), "capacity 应点名 initialCapacity")
    }

    @Test
    fun tinyCapacityWorksWithLimitOne() {
        val cache = ConversationCache(maxEntries = 1, initialCapacity = 1)
        cache.put("a", conv("a"))
        cache.put("b", conv("b"))
        assertNull(cache.get("a"))
        assertEquals("b", cache.get("b")?.id)
    }
}
