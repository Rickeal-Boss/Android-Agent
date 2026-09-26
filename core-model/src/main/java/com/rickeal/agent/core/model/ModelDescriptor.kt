package com.rickeal.agent.core.model

import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
enum class ModelFamily {
    GEMMA_3N,
    GEMMA_3,
    GEMMA_4,
    QWEN_3,
    LLAMA,
    PHI,

    /** OpenBMB MiniCPM 系（MiniCPM5 文本 / MiniCPM-V 视觉）。Wave 20 新增。 */
    MINICPM,
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

    /**
     * 体积的人类可读文本。
     *
     * **必须显式给 Locale.US**：`String.format` 默认取系统 Locale，德语区会输出
     * `2,40 GB`（逗号作小数点）、法语区可能出 `2 40 GB`（不换行空格作千分位）——
     * 而这个字符串会被拼进提示文案、也可能被用户复制去搜索，量纲符号必须与数字口径一致。
     * 用 `java.lang.String.format(Locale, ...)` 的显式三参重载，不依赖 Kotlin 的扩展函数。
     */
    fun sizeText(): String = when {
        sizeBytes <= 0L -> "未知"
        sizeBytes >= 1024L * 1024 * 1024 -> java.lang.String.format(
            Locale.US,
            "%.2f GB",
            sizeBytes / 1024.0 / 1024.0 / 1024.0,
        )
        else -> java.lang.String.format(Locale.US, "%.1f MB", sizeBytes / 1024.0 / 1024.0)
    }
}
