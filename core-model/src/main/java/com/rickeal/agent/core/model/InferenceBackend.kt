package com.rickeal.agent.core.model

import kotlinx.serialization.Serializable

/** LiteRT-LM 与 OpenAI 的 backend 统一枚举。 */
@Serializable
enum class InferenceBackend {
    CPU,
    GPU,
    NPU,
}

/** 引擎大类：本地 LiteRT-LM / 远程 OpenAI 兼容。 */
@Serializable
enum class EngineKind {
    LOCAL,
    REMOTE,
}
