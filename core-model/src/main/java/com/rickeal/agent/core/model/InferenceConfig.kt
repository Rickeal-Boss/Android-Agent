package com.rickeal.agent.core.model

import kotlinx.serialization.Serializable

/**
 * 一次推理的完整配置。这是「参数调节页」的唯一数据出口。
 */
@Serializable
data class InferenceConfig(
    val sampling: SamplingParams = SamplingParams(),
    val maxTokens: Int = 1024,
    val contextLength: Int = 4096,
    val backend: InferenceBackend = InferenceBackend.CPU,
    val visionBackend: InferenceBackend? = null,
    val audioBackend: InferenceBackend? = null,
    val thinking: ThinkingMode = ThinkingMode.AUTO,
    /**
     * thinking 通道的**独立 token 预算**（上游 litertlm 0.17.1 `ThinkingConfig.thinkingTokenBudget`）。
     *
     * `0` = 自动（= [maxTokens] 的一半，为正文保留等量余量）；`> 0` = 显式上限。
     * ⚠️ 预算**计入** [maxTokens]（上游口径：thinking + 正文共享 `maxOutputToken`），故必须
     * `< maxTokens`，否则正文仍可能被挤空 —— [coerce] 据此归一。此前本仓完全没接线：
     * thinking 与可见答案共享同一个 [maxTokens]，thinking 烧光即无可见输出（真机 222s 空转 ⇒ 熔断）。
     *
     * 带默认值，旧 JSON 前后兼容（`ignoreUnknownKeys` + `explicitNulls=false`）。
     */
    val thinkingTokenBudget: Int = 0,
    val systemInstruction: String = "",
    val maxAgentRounds: Int = 8,
    val enableTools: Boolean = true,
    /**
     * 是否启用**模型原生工具通道**（工具经引擎的原生 tool 通道注册与回传，工具清单不再
     * 写进系统提示词）。与 [enableTools] 是两件事：关掉工具时它无意义。
     *
     * **默认 false 是刻意的 fail-safe**：工具 schema 的形状（OpenAI 平铺形能否被某个
     * 转换件的 chat template 正确解析）**无法离线验证** —— 只能靠引擎侧探针在真机上试。
     * 误开比不开更糟：schema 解析失败发生在 `createConversation` 内，会把整个会话创建
     * 打掉（连文本协议一起没了）；保持 false 只是继续走已验证的文本协议，最坏情形是
     * 「没拿到新收益」。
     *
     * 真正生效需要三条件同时成立（引擎侧判据，缺一即退回文本协议）：本开关打开 ∧
     * 引擎探针通过（模型/转换件接受原生工具注册）∧ 模型能力位 toolCalling 为真。
     *
     * 加字段带默认值，旧 JSON 前后兼容（ignoreUnknownKeys + explicitNulls=false）。
     */
    val nativeToolChannel: Boolean = false,
    val stream: Boolean = true,
) {
    /** 归一化：把所有字段压回合法区间。 */
    fun coerce(): InferenceConfig {
        // maxTokens 先归一，thinkingTokenBudget 的「< maxTokens」上界必须取**归一后**的值
        // （否则 maxTokens 越界时上界算错）。下界 0 保留「自动」语义。
        val normalizedMaxTokens = maxTokens.coerceIn(64, 32768)
        return copy(
            sampling = sampling.coerce(),
            maxTokens = normalizedMaxTokens,
            contextLength = contextLength.coerceIn(512, 131072),
            maxAgentRounds = maxAgentRounds.coerceIn(1, 32),
            thinkingTokenBudget = thinkingTokenBudget.coerceIn(0, (normalizedMaxTokens - 1).coerceAtLeast(0)),
        )
    }
}
