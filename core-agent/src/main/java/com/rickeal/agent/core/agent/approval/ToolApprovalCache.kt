package com.rickeal.agent.core.agent.approval

import com.rickeal.agent.core.model.AgentJson
import kotlinx.serialization.json.JsonElement
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 工具审批缓存 —— 「计划级授权」的端侧降级（Octop 批量审批 + TTL 的轻量同构）。
 *
 * 用户在授权卡上**显式**点「相同调用不再询问」后才写入；**拒绝永不缓存**（fail-closed）。
 * key = 会话 × 工具名 × 参数摘要：参数摘要（parse → 规范化重编码 → SHA-256）让
 * 「同参重试」免弹卡、「换参调用」仍弹卡 —— 对齐 ZCode `allowAlways: false` 的裁决
 * （输入每次不同的工具不能记住决策），也天然化解了 clipboard set 被「一次授权、
 * 永久免问」的担忧：换了文本就换摘要，照样问。
 *
 * 与 autoApproveDangerous 正交且优先级靠后：策略豁免（人在回路外）优先于用户缓存。
 * TTL 30min（对齐 Octop _DEFAULT_TTL_SECONDS），peek 时惰性淘汰，无需后台清理线程。
 */
interface ToolApprovalCache {

    /**
     * 查询缓存。命中且未过期返回 APPROVED；未命中/过期（顺手淘汰）/任意异常返回 null，
     * 调用方继续走人在回路。
     */
    fun peek(toolName: String, argsDigest: String, conversationId: String?): ToolApprovalDecision?

    /** 显式授权（仅由宿主在用户点击「相同调用不再询问」时调用）。 */
    fun grant(toolName: String, argsDigest: String, conversationId: String?, ttlMillis: Long)

    /** 清空指定会话（或全部）的授权。会话销毁/用户撤销时调用。 */
    fun revokeAll(conversationId: String? = null)
}

class InMemoryToolApprovalCache(
    private val defaultTtlMillis: Long = DEFAULT_TTL_MILLIS,
) : ToolApprovalCache {

    private data class Entry(val expiresAt: Long)

    /** key = "$conversationId|$toolName|$argsDigest"。 */
    private val grants = ConcurrentHashMap<String, Entry>()

    override fun peek(toolName: String, argsDigest: String, conversationId: String?): ToolApprovalDecision? {
        val key = keyOf(toolName, argsDigest, conversationId)
        val entry = grants[key] ?: return null
        if (System.currentTimeMillis() >= entry.expiresAt) {
            grants.remove(key) // 惰性淘汰
            return null
        }
        return ToolApprovalDecision.APPROVED
    }

    override fun grant(toolName: String, argsDigest: String, conversationId: String?, ttlMillis: Long) {
        val ttl = if (ttlMillis > 0) ttlMillis else defaultTtlMillis
        grants[keyOf(toolName, argsDigest, conversationId)] = Entry(System.currentTimeMillis() + ttl)
    }

    override fun revokeAll(conversationId: String?) {
        if (conversationId == null) {
            grants.clear()
        } else {
            grants.keys.removeAll { it.startsWith("$conversationId|") }
        }
    }

    private fun keyOf(toolName: String, argsDigest: String, conversationId: String?): String =
        "${conversationId.orEmpty()}|$toolName|$argsDigest"

    companion object {
        const val DEFAULT_TTL_MILLIS: Long = 30 * 60 * 1000L

        /**
         * 参数摘要：parse → 规范化重编码（消除空白/键序差异）→ SHA-256。
         * 解析失败用原文哈希兜底（绝不抛异常——摘要失败不能变成审批失败面）。
         */
        fun argsDigest(argumentsJson: String): String = try {
            val normalized: JsonElement = AgentJson.Default.parseToJsonElement(argumentsJson)
            sha256Hex(normalized.toString())
        } catch (t: Throwable) {
            sha256Hex(argumentsJson)
        }

        fun sha256Hex(text: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
