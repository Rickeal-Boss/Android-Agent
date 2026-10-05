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

/**
 * 思考通道的**字面量语法**（模型事实，非用户偏好；Wave 49 R-A）。
 *
 * `ModelDescriptor.thoughtChannelSyntax == null` ⇒ **不下发 `channels`，信任容器元数据**
 * （`ConversationConfig.channels = null`；0.17.1 KDoc 逐字：`null` = 用 `LlmMetadata` 的默认
 * 通道配置，`empty` = 禁用通道）。非 null ⇒ 下发**单元素** def **覆盖**元数据。
 *
 * ## 为何 null 优先于「猜一个字面量」
 *
 * native `channels` 是 **overwrite 语义**（litert-lm `conversation.cc:189-200`）：一旦非空即
 * **整体丢弃**容器元数据通道。若对「元数据已自声明思考通道」的模型下发**错误字面量**，会把正确
 * 通道覆盖成错的 ⇒ 模型按自己的标记输出、运行时按错误标记切分 ⇒ **思维链泄漏进正文 + 思考预算
 * 静默失效 + 零报错**（W48 N1 真机 `bbd8db82` / W43 真机实锤同款）。
 * ⇒ **能信任元数据就信任，不猜字面量。**
 */
@Serializable
enum class ChannelSyntax(val start: String, val end: String) {
    /**
     * Gemma 系：`<|channel>thought` / `<channel|>`（litert-lm `channel_util.h` 示例 +
     * W43 `3e08b8f` 真机泄漏文本逐字节一致）。
     */
    GEMMA("<|channel>thought", "<channel|>"),

    /**
     * MiniCPM5 系：`<think>` / `</think>`（`RM_MiniCPM5-2B.md:163`）。
     * **保留作显式回退档** —— 若真机证明其容器元数据通道未被 native 采纳，一行切回。
     */
    THINK("<think>", "</think>"),
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

/**
 * 能力位的**来源**（Wave 48）。
 *
 * 现有 schema 只有布尔值、无法区分「用户改的」与「启发式写的」，导致 `applyTo` 的并集语义
 * 让「曾被启发式写 true」的位永久为 true（真机 `_w45_models.json` 5 模型全虚高）。本枚举
 * 让 `applyTo` 能按来源决定「采信持久化值」还是「用启发式重算」。
 *
 * - [HEURISTIC]：启发式初值（`applyTo` 可重算覆盖）。
 * - [USER]：用户显式编辑过（`setCapabilities`），此后启发式**不再改写**能力位。
 *
 * ⚠️ `ModelDescriptor.capabilitiesSource` 为 `null` = **Wave 48 之前的旧数据**（无法区分
 * 来源）；`applyTo` 会把旧 `null` 迁移为 [HEURISTIC]（重算，见迁移策略）。
 */
@Serializable
enum class CapabilitySource { HEURISTIC, USER }

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
    /**
     * 能力位来源（Wave 48）：[CapabilitySource.USER] = 用户显式编辑过（`setCapabilities`），
     * 此后启发式**不再改写**；[CapabilitySource.HEURISTIC] = 启发式初值（`applyTo` 可重算覆盖）；
     * `null` = **Wave 48 之前的旧数据**（无法区分，`applyTo` 首次重算迁移为 HEURISTIC）。
     *
     * 新增带默认值的字段对旧 JSON **向后兼容**（缺失 → 取默认 `null`）。
     */
    val capabilitiesSource: CapabilitySource? = null,
    val defaultParams: SamplingParams = SamplingParams(),
    val quantization: Quantization = Quantization.UNKNOWN,
    val contextLength: Int = 4096,
    val version: String? = null,
    val sourceUrl: String? = null,
    val addedAtMillis: Long = System.currentTimeMillis(),
    val isBuiltIn: Boolean = false,
    /**
     * 思考通道字面量语法（Wave 49 R-A）。`null` = **信任容器元数据**（不下发 `channels`）。
     *
     * 默认 `null` —— **未登记的未知模型必须是 null**，否则等于没修（见 [ChannelSyntax] KDoc）。
     * 新增带默认值字段对旧 JSON **向后兼容**（缺失 → `null`）；`ModelHeuristics.applyTo` 每次
     * refresh 都**重算**本字段（它是模型事实、不可用户编辑，故不采信持久化值）。
     */
    val thoughtChannelSyntax: ChannelSyntax? = null,
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
