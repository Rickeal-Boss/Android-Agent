package com.rickeal.agent.core.agent.approval

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * 工具审批缓存（[InMemoryToolApprovalCache]）行为锁定。
 *
 * 重点钉住 Wave 28 修复的**档位维度**：授权是「用户在某个档位下的放行」，不是跨档位
 * 通行证 —— 档位不同（尤其是降档）必须 miss、重新弹卡，否则 READ_ONLY 档位会被
 * WORKSPACE_WRITE 档的 30min 存量授权静默绕过。
 *
 * 并发语义由 ConcurrentHashMap（JDK）保证，不在本测试范围。
 */
class ToolApprovalCacheTest {

    private val cache = InMemoryToolApprovalCache()

    private fun grant(
        toolName: String = "clipboard_set",
        digest: String = "digest-a",
        conversationId: String? = "c1",
        capabilityMode: String? = "WORKSPACE_WRITE",
        ttlMillis: Long = ToolApprovalCache.DEFAULT_TTL_MILLIS,
        cache: ToolApprovalCache = this.cache,
    ) = cache.grant(toolName, digest, conversationId, capabilityMode, ttlMillis)

    private fun peek(
        toolName: String = "clipboard_set",
        digest: String = "digest-a",
        conversationId: String? = "c1",
        capabilityMode: String? = "WORKSPACE_WRITE",
        cache: ToolApprovalCache = this.cache,
    ) = cache.peek(toolName, digest, conversationId, capabilityMode)

    // ── 基础命中语义 ─────────────────────────────────────────────────────────

    @Test
    fun `未授权的调用 peek 返回 null`() {
        assertNull(peek())
    }

    @Test
    fun `授权后同 key peek 命中 APPROVED`() {
        grant()
        assertEquals(ToolApprovalDecision.APPROVED, peek())
        // 可重复查询，语义稳定
        assertEquals(ToolApprovalDecision.APPROVED, peek())
    }

    @Test
    fun `换参数摘要必 miss`() {
        // 对齐 ZCode allowAlways:false：输入每次不同的工具不能记住决策
        // （典型：clipboard set 换了文本就换摘要，照样弹卡）。
        grant(digest = "digest-a")
        assertNull(peek(digest = "digest-b"))
    }

    @Test
    fun `换工具名必 miss`() {
        grant(toolName = "clipboard_set")
        assertNull(peek(toolName = "file_write"))
    }

    @Test
    fun `换会话必 miss`() {
        grant(conversationId = "c1")
        assertNull(peek(conversationId = "c2"))
    }

    // ── 档位维度（Wave 28 修复，P1-1） ──────────────────────────────────────

    @Test
    fun `降档必 miss —— 不得用高档位授权放行低档位调用`() {
        grant(capabilityMode = "WORKSPACE_WRITE")
        assertNull(peek(capabilityMode = "READ_ONLY"))
    }

    @Test
    fun `升档同样 miss`() {
        grant(capabilityMode = "READ_ONLY")
        assertNull(peek(capabilityMode = "WORKSPACE_WRITE"))
    }

    @Test
    fun `升回原档位时 TTL 内旧授权仍命中`() {
        grant(capabilityMode = "WORKSPACE_WRITE")
        assertEquals(ToolApprovalDecision.APPROVED, peek(capabilityMode = "WORKSPACE_WRITE"))
    }

    @Test
    fun `有档位授权与无档位查询互不通用`() {
        // 注意自污染：同一 (会话,档位,工具,摘要) key 在本用例内先 grant 后 peek 会命中，
        // 断言「隔离」必须用不同的 key 维度，否则 null 命中的是前面自己授的权。
        grant(capabilityMode = null)
        // null 档位是「旧调用方兼容」通道，与带档位的 key 互相隔离：
        // null 授权后，带档位查询必须 miss。
        assertNull(peek(toolName = "file_read", digest = "digest-f", capabilityMode = "READ_ONLY"))
        assertEquals(ToolApprovalDecision.APPROVED, peek(capabilityMode = null))
        // 反向：带档位授权后，null 兼容通道查询同样 miss。
        grant(toolName = "file_read", digest = "digest-f", capabilityMode = "READ_ONLY")
        assertNull(peek(toolName = "file_read", digest = "digest-f", capabilityMode = null))
        assertEquals(ToolApprovalDecision.APPROVED, peek(toolName = "file_read", digest = "digest-f", capabilityMode = "READ_ONLY"))
    }

    // ── TTL ──────────────────────────────────────────────────────────────────

    @Test
    fun `TTL 未过期命中`() {
        grant(ttlMillis = 60_000)
        assertEquals(ToolApprovalDecision.APPROVED, peek())
    }

    @Test
    fun `TTL 过期后 peek 返回 null 且惰性淘汰`() {
        grant(ttlMillis = 1)
        Thread.sleep(50)
        assertNull(peek())
        // 惰性淘汰后再查仍然 miss，不抛异常
        assertNull(peek())
    }

    @Test
    fun `ttl 非正数时回退到构造默认 TTL`() {
        val shortCache = InMemoryToolApprovalCache(defaultTtlMillis = 40)
        shortCache.grant("t", "d", "c", "m", ttlMillis = 0)
        assertEquals(ToolApprovalDecision.APPROVED, shortCache.peek("t", "d", "c", "m"))
        Thread.sleep(80)
        assertNull(shortCache.peek("t", "d", "c", "m"))
    }

    @Test
    fun `显式 ttl 优先于构造默认 TTL`() {
        val shortCache = InMemoryToolApprovalCache(defaultTtlMillis = 1)
        shortCache.grant("t", "d", "c", "m", ttlMillis = 60_000)
        Thread.sleep(20)
        // 若走了 1ms 默认 TTL 这里早已过期；显式 ttl 必须生效
        assertEquals(ToolApprovalDecision.APPROVED, shortCache.peek("t", "d", "c", "m"))
    }

    // ── revokeAll ────────────────────────────────────────────────────────────

    @Test
    fun `revokeAll 指定会话只清该会话的全部档位`() {
        grant(toolName = "t1", conversationId = "c1", capabilityMode = "WORKSPACE_WRITE")
        grant(toolName = "t2", conversationId = "c1", capabilityMode = "READ_ONLY")
        grant(conversationId = "c2")

        cache.revokeAll("c1")

        assertNull(peek(toolName = "t1", conversationId = "c1", capabilityMode = "WORKSPACE_WRITE"))
        assertNull(peek(toolName = "t2", conversationId = "c1", capabilityMode = "READ_ONLY"))
        assertEquals(ToolApprovalDecision.APPROVED, peek(conversationId = "c2"))
    }

    @Test
    fun `revokeAll 不指定会话时清空全部`() {
        grant(conversationId = "c1")
        grant(conversationId = "c2")

        cache.revokeAll(null)

        assertNull(peek(conversationId = "c1"))
        assertNull(peek(conversationId = "c2"))
    }

    @Test
    fun `revoke 之后再授权重新生效`() {
        grant()
        cache.revokeAll("c1")
        assertNull(peek())
        grant()
        assertEquals(ToolApprovalDecision.APPROVED, peek())
    }

    // ── 摘要与哈希（companion 纯函数） ───────────────────────────────────────

    @Test
    fun `argsDigest 消除空白差异`() {
        val d1 = ToolApprovalCache.argsDigest("{\"text\":\"hi\"}")
        val d2 = ToolApprovalCache.argsDigest("{ \"text\" : \"hi\" }")
        assertEquals(d1, d2)
    }

    @Test
    fun `argsDigest 对不同参数产生不同摘要`() {
        assertNotEquals(
            ToolApprovalCache.argsDigest("{\"text\":\"hi\"}"),
            ToolApprovalCache.argsDigest("{\"text\":\"ho\"}"),
        )
    }

    @Test
    fun `argsDigest 解析失败时用原文哈希兜底且不抛异常`() {
        val malformed = "这不是 JSON{"
        assertEquals(ToolApprovalCache.sha256Hex(malformed), ToolApprovalCache.argsDigest(malformed))
    }

    @Test
    fun `sha256Hex 输出确定性 64 位十六进制`() {
        // SHA-256("") 的公认常量，锁定编码与格式化行为
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ToolApprovalCache.sha256Hex(""),
        )
        val digest = ToolApprovalCache.sha256Hex("abc")
        assertEquals(64, digest.length)
        assertEquals(digest, ToolApprovalCache.sha256Hex("abc"))
    }

    @Test
    fun `DEFAULT_TTL_MILLIS 对齐 Octop 默认 30 分钟`() {
        assertEquals(30 * 60 * 1000L, ToolApprovalCache.DEFAULT_TTL_MILLIS)
    }
}
