package com.rickeal.agent.core.model

/**
 * 模型能力启发式（架构文档 §5.3）。
 *
 * 位置说明：刻意放在 `:core-model` —— 纯字符串判断，零依赖、可单测，
 * 不需要 Context 也不需要真的打开模型文件（打开 .litertlm 探测代价太高且易失败）。
 *
 * 这里的结果只是「初值」：用户始终可以在模型详情页手动改。
 */
data class HeuristicResult(
    val displayName: String = "",
    val family: ModelFamily = ModelFamily.OTHER,
    val quantization: Quantization = Quantization.UNKNOWN,
    val capabilities: ModelCapabilities = ModelCapabilities(),
    val contextLength: Int = 4096,
)

object ModelHeuristics {

    fun infer(fileName: String, sizeBytes: Long = 0L): HeuristicResult {
        val lower = fileName.lowercase()
        val name = fileName.substringBeforeLast('.').ifBlank { fileName }

        val family = inferFamily(lower)
        val quantization = inferQuantization(lower)
        val capabilities = inferCapabilities(family, lower)
        val contextLength = inferContextLength(family, lower, sizeBytes)

        return HeuristicResult(
            displayName = name,
            family = family,
            quantization = quantization,
            capabilities = capabilities,
            contextLength = contextLength,
        )
    }

    /** 推断出的能力合并进已有 descriptor（只覆盖「看起来更可信」的启发式结果）。 */
    fun applyTo(descriptor: ModelDescriptor): ModelDescriptor {
        val raw = descriptor.fileName.ifBlank {
            descriptor.path.substringAfterLast('/', descriptor.path)
        }
        if (raw.isBlank()) return descriptor
        val result = infer(raw, descriptor.sizeBytes)
        return descriptor.copy(
            displayName = descriptor.displayName.ifBlank { result.displayName },
            family = if (descriptor.family == ModelFamily.OTHER) result.family else descriptor.family,
            quantization = if (descriptor.quantization == Quantization.UNKNOWN) {
                result.quantization
            } else {
                descriptor.quantization
            },
            capabilities = descriptor.capabilities.mergeHeuristic(result.capabilities),
        )
    }

    private fun inferFamily(lower: String): ModelFamily = when {
        // 注意顺序：gemma-3n 同时含 "3n" 与 "gemma-3"，必须先判 3n
        lower.contains("3n") -> ModelFamily.GEMMA_3N
        lower.contains("gemma-3") || lower.contains("gemma3") -> ModelFamily.GEMMA_3
        // gemma-4 不含 "3n"、也不含 "gemma-3"，与上面两条不冲突；紧邻 Gemma 分支保持可读
        lower.contains("gemma-4") || lower.contains("gemma4") -> ModelFamily.GEMMA_4
        lower.contains("qwen3") || lower.contains("qwen-3") -> ModelFamily.QWEN_3
        lower.contains("llama") -> ModelFamily.LLAMA
        lower.contains("phi") -> ModelFamily.PHI
        else -> ModelFamily.OTHER
    }

    private fun inferQuantization(lower: String): Quantization = when {
        lower.contains("q4_k_m") || lower.contains("q4-k-m") -> Quantization.Q4_K_M
        lower.contains("int4") || lower.contains("q4") -> Quantization.INT4
        lower.contains("int8") || lower.contains("q8") -> Quantization.INT8
        lower.contains("bf16") -> Quantization.BF16
        lower.contains("fp16") || lower.contains("f16") -> Quantization.FP16
        else -> Quantization.UNKNOWN
    }

    private fun inferCapabilities(family: ModelFamily, lower: String): ModelCapabilities = when (family) {
        ModelFamily.GEMMA_3N -> ModelCapabilities(
            text = true,
            image = true,
            audio = true,
            toolCalling = true,
            thinking = false,
            speculativeDecoding = lower.contains("e2b") || lower.contains("e4b"),
            preferredBackends = setOf(InferenceBackend.GPU, InferenceBackend.CPU),
        )

        ModelFamily.GEMMA_3 -> ModelCapabilities(
            text = true,
            image = true,
            audio = false,
            toolCalling = lower.contains("it"),
            thinking = false,
            preferredBackends = setOf(InferenceBackend.GPU, InferenceBackend.CPU),
        )

        // Gemma 4（E2B / E4B，litert-community 的 .litertlm 版本）—— 能力位逐项依据：
        // - image / audio：litert-community 模型卡写明「the Vision and Audio models are loaded
        //   on demand / as needed」；Google Gemma 4 模型卡写明「handling text and image input
        //   (with audio supported on E2B, E4B, and 12B models)」。
        // - toolCalling：Google Gemma 4 模型卡「Function Calling – Native support for structured
        //   tool use, enabling agentic workflows」。
        // - thinking：未在 LiteRT-LM 侧查到「思考通道」的可靠依据，按「查不到就不设」保守关闭。
        // - speculativeDecoding：litert-community 两张模型卡均有独立章节写明
        //   「Speculative decoding is available on CPU and GPU on Mobile and Desktop」。
        // - 后端：模型卡 Android 基准只列 CPU / GPU（NPU 仅出现在 IoT 的独立 NPU 模型上），
        //   本工程预设也只提供 CPU / GPU 两个文件，故取 GPU + CPU。
        ModelFamily.GEMMA_4 -> ModelCapabilities(
            text = true,
            image = true,
            audio = true,
            toolCalling = true,
            thinking = false,
            speculativeDecoding = lower.contains("e2b") || lower.contains("e4b"),
            preferredBackends = setOf(InferenceBackend.GPU, InferenceBackend.CPU),
        )

        ModelFamily.QWEN_3 -> ModelCapabilities(
            text = true,
            image = false,
            audio = false,
            toolCalling = true,
            thinking = true,
            preferredBackends = setOf(InferenceBackend.CPU, InferenceBackend.GPU),
        )

        else -> ModelCapabilities(
            text = true,
            image = lower.contains("vl") || lower.contains("vision"),
            audio = lower.contains("audio") || lower.contains("omni"),
            toolCalling = lower.contains("it") || lower.contains("instruct"),
            thinking = false,
            preferredBackends = setOf(InferenceBackend.CPU),
        )
    }

    /**
     * 上下文长度：完全无法从文件名可靠推断，只能给一个「保守的家族默认值」。
     * 宁可给小不给大 —— 给大了会让上层不做压缩，直接把模型跑崩。
     *
     * **注意优先级**：下面的「显式 k 匹配」优先于家族默认值，详见函数体内注释。
     */
    private fun inferContextLength(family: ModelFamily, lower: String, sizeBytes: Long): Int {
        // 【优先级陷阱】显式 k 匹配（文件名含 "32k" 之类）**先于**家族默认值生效，且命中即 return。
        //
        // 为什么这个优先级危险：本工程预设的 memBasis 全部按 `KV@4096` 估算内存，而家族默认值
        // （尤其下面 GEMMA_4 的 8192）正是照这份预算「宁可给小」取的。一旦文件名带上 "32k"，
        // 这里会静默返回 32768，把家族默认值的保守权衡整个作废 —— 内存仍按 4096 算、上下文却
        // 按 32k 跑，恰好落进上面说的「给大了会把模型跑崩」。
        //
        // 加新预设时请先确认：文件名的显式 k 与预设 memBasis 的内存预算一致；对 Gemma 3 / 4
        // 这类已按 KV@4096 估内存的模型，不要在文件名里塞比家族默认值更大的 k。
        val explicit = Regex("(\\d+)\\s*k").find(lower)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (explicit != null && explicit in 1..1024) return explicit * 1024
        return when (family) {
            ModelFamily.GEMMA_3N -> 8192
            ModelFamily.GEMMA_3 -> 8192
            // litert-community 模型卡称「可支持到 32k」，但本工程预设的内存估算按 KV@4096 计的，
            // 按「宁可给小不给大」先与 GEMMA_3N / GEMMA_3 对齐取 8192。
            ModelFamily.GEMMA_4 -> 8192
            ModelFamily.QWEN_3 -> 32768
            ModelFamily.LLAMA -> 8192
            ModelFamily.PHI -> 4096
            ModelFamily.OTHER -> if (sizeBytes >= 4L * 1024 * 1024 * 1024) 8192 else 4096
        }
    }

    /** 启发式结果只做「并集」：任一方说支持就支持，避免把用户手动打开的能力关掉。 */
    private fun ModelCapabilities.mergeHeuristic(other: ModelCapabilities): ModelCapabilities =
        ModelCapabilities(
            text = text || other.text,
            image = image || other.image,
            audio = audio || other.audio,
            toolCalling = toolCalling || other.toolCalling,
            thinking = thinking || other.thinking,
            speculativeDecoding = speculativeDecoding || other.speculativeDecoding,
            preferredBackends = if (preferredBackends.size > other.preferredBackends.size) {
                preferredBackends
            } else {
                other.preferredBackends
            },
        )
}
