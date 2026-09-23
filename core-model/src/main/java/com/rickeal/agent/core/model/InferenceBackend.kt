package com.rickeal.agent.core.model

import kotlinx.serialization.Serializable

/** LiteRT-LM 与 OpenAI 的 backend 统一枚举。 */
@Serializable
enum class InferenceBackend {
    CPU,
    GPU,
    NPU,
}

/**
 * 引擎大类。本应用已收敛为**纯端侧运行**：远程 OpenAI 兼容通道已整体移除
 * （远程调用的网络安全面、凭据管理成本与端侧定位不符，权衡后砍掉）。
 * 枚举保留单值是为 EngineFactory API 形态与 journal 里的历史字符串兼容。
 */
@Serializable
enum class EngineKind {
    LOCAL,
}
