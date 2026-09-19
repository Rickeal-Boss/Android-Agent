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
    val systemInstruction: String = "",
    val engineKind: EngineKind = EngineKind.LOCAL,
    val remoteEndpointId: String? = null,
    val maxAgentRounds: Int = 8,
    val enableTools: Boolean = true,
    val stream: Boolean = true,
) {
    /**
     * 归一化：把所有字段压回合法区间，**并把「空串端点 id」收敛成 null**。
     *
     * 为什么要归一 `remoteEndpointId`：它是「有没有选远程端点」的唯一判据，
     * 空串与 null 在下游是同一个结果（`EndpointRepository.find` 都返回 null），
     * 但**在配置本身上表示的意义相反** —— 「用户选了一个端点（只是 id 恰好是空的）」vs
     * 「用户没选端点」。于是配置会自相矛盾：`engineKind == REMOTE` 且 `remoteEndpointId != null`
     * 却解析不出端点，出错文案只能说"端点不存在"，而真相是"从没选过"。
     * 归一后「未选择」只有 null 一种表示，判断分支也就只有一条。
     */
    fun coerce(): InferenceConfig = copy(
        sampling = sampling.coerce(),
        maxTokens = maxTokens.coerceIn(64, 32768),
        contextLength = contextLength.coerceIn(512, 131072),
        maxAgentRounds = maxAgentRounds.coerceIn(1, 32),
        remoteEndpointId = remoteEndpointId?.takeIf { it.isNotBlank() },
    )
}
