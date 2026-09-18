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
    fun coerce(): InferenceConfig = copy(
        sampling = sampling.coerce(),
        maxTokens = maxTokens.coerceIn(64, 32768),
        contextLength = contextLength.coerceIn(512, 131072),
        maxAgentRounds = maxAgentRounds.coerceIn(1, 32),
    )
}
