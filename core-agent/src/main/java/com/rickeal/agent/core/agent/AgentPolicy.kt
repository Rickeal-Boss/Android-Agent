package com.rickeal.agent.core.agent

import kotlinx.serialization.Serializable

@Serializable
data class AgentPolicy(
    /** 最大轮次（一轮 = 一次模型生成 + 一批工具执行） */
    val maxRounds: Int = 8,
    /** 单个工具超时 */
    val toolTimeoutMillis: Long = 15_000L,
    /** 工具输出截断长度，防止把上下文撑爆 */
    val maxToolOutputChars: Int = 4000,
    /** 是否自动执行 dangerous 工具 */
    val autoApproveDangerous: Boolean = false,
    /** 是否启用文本协议兜底解析（```json / <tool_call>） */
    val enableTextProtocol: Boolean = true,
    /** 是否在每轮前做上下文压缩 */
    val compressContext: Boolean = true,
    /** 上下文占用比例阈值，超过则压缩 */
    val compressThreshold: Float = 0.75f,
)
