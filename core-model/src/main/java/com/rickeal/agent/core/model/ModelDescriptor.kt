package com.rickeal.agent.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ModelFamily {
    GEMMA_3N,
    GEMMA_3,
    QWEN_3,
    LLAMA,
    PHI,
    OTHER,
}

@Serializable
enum class Quantization {
    NONE,
    INT4,
    INT8,
    Q4_K_M,
    Q8,
    FP16,
    BF16,
    UNKNOWN,
}

/** 能力位：驱动 UI 的「哪些开关可用」与引擎的「要不要传 visionBackend」。 */
@Serializable
data class ModelCapabilities(
    val text: Boolean = true,
    val image: Boolean = false,
    val audio: Boolean = false,
    val toolCalling: Boolean = false,
    val thinking: Boolean = false,
    val speculativeDecoding: Boolean = false,
    val preferredBackends: Set<InferenceBackend> = setOf(InferenceBackend.CPU),
)

@Serializable
data class ModelDescriptor(
    val id: String = newId(),
    val displayName: String = "",
    val family: ModelFamily = ModelFamily.OTHER,
    /** .litertlm / .task 的绝对路径 */
    val path: String = "",
    val fileName: String = "",
    val sizeBytes: Long = 0L,
    val sha256: String? = null,
    val capabilities: ModelCapabilities = ModelCapabilities(),
    val defaultParams: SamplingParams = SamplingParams(),
    val quantization: Quantization = Quantization.UNKNOWN,
    val contextLength: Int = 4096,
    val version: String? = null,
    val sourceUrl: String? = null,
    val addedAtMillis: Long = System.currentTimeMillis(),
    val isBuiltIn: Boolean = false,
    val notes: String? = null,
) {
    fun exists(): Boolean = path.isNotBlank() && java.io.File(path).exists()

    fun sizeText(): String = when {
        sizeBytes <= 0L -> "未知"
        sizeBytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(sizeBytes / 1024.0 / 1024.0 / 1024.0)
        else -> "%.1f MB".format(sizeBytes / 1024.0 / 1024.0)
    }
}
