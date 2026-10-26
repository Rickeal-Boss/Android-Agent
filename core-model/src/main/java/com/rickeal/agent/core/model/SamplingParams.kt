package com.rickeal.agent.core.model

import kotlinx.serialization.Serializable

/**
 * 纯采样参数。注意：LiteRT-LM 的 SamplerConfig 只认 topK / topP / temperature；
 * repetitionPenalty 自 litertlm 0.17.1（Wave 20）起经 RepetitionPenaltyConfig 逐消息
 * 传入真实生效（LiteRtLmEngine.generateStream，唯一生效点），1.0 = 不惩罚；seed 仍是
 * 上层忽略的死字段（架构文档 §5.1）。
 */
@Serializable
data class SamplingParams(
    // 0.8 → 0.7（2026-09-26）：端侧小模型（0.4~2B）在 0.8 下重复循环/胡言乱语真机实锤；
    // 降 0.1 换明显更稳的输出，创造性损失在端侧助手场景可接受。用户可在聊天参数面板调回。
    // ⚠️ 降档理由已过期（W84-A6 订正）：本条原写「LiteRT-LM SamplerConfig 无 repetition
    // penalty（架构 §5.1）」，但自 litertlm 0.17.1（Wave 20）起 repetitionPenalty 经
    // RepetitionPenaltyConfig 逐消息真实生效（见本文件类头与 LiteRtLmEngine.generateStream）。
    // 即：**「只能靠降温压重复」这个前提不再成立**。0.7 这个值**保留不动**（它是真机
    // 实测出来的可用点，与理由是否过期无关；改值属调参，不在注释订正范围内）。
    // 将来若要重新调温，应按「降温 + repetitionPenalty 双轴」重新做真机 A/B，而不是沿用
    // 本条的单轴推理。
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val repetitionPenalty: Float = 1.0f,
    val seed: Int? = null,
) {
    init {
        require(temperature >= 0f) { "temperature must be >= 0" }
        require(topP in 0f..1f) { "topP must be in [0,1]" }
        require(topK >= 1) { "topK must be >= 1" }
    }

    fun coerce(): SamplingParams = copy(
        temperature = temperature.coerceIn(0f, 2f),
        topP = topP.coerceIn(0f, 1f),
        topK = topK.coerceIn(1, 200),
        repetitionPenalty = repetitionPenalty.coerceIn(1f, 2f),
    )
}
