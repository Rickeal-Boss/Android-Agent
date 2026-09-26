package com.rickeal.agent.core.agent

import com.rickeal.agent.core.model.SamplingParams
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
    /**
     * agent 会话采样折衷（Wave 19 P1-1）。null = 不覆写（默认，零行为变化）。
     *
     * 来源：官方 gallery `AgentChatSamplingParamsManager.kt:108-137` 对 agent 任务
     * 用 TopK=1（贪心解码）—— 官方对 agent 场景「确定性优先」的表态。但
     * LiteRT-LM 0.11.0 与 0.17.x 的 Kotlin SamplerConfig 均无 repeat penalty
     * （调研 9 个开源 harness 的定案：升级零收益），纯贪心在长文本上会 token 级
     * 循环，所以取折衷：低温 + 收窄 topK，并**必须**与 AgentRunner 的轮内重复
     * 检测器配套（贪心无 penalty 的循环靠检测器兜底截断）。
     * 阈值可调，真机输出质量反馈后校准。采样变更触发 Conversation 重建已由
     * LiteRtLmEngine.kt:208-218 处理，无需新代码。
     */
    val agentSamplingOverride: SamplingParams? = null,
)
