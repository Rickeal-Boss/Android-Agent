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
    /** 思考通道字面量语法（Wave 49 R-A）；`null` = 信任容器元数据。见 [ChannelSyntax]。 */
    val thoughtChannelSyntax: ChannelSyntax? = null,
)

object ModelHeuristics {

    fun infer(fileName: String, sizeBytes: Long = 0L): HeuristicResult {
        val lower = fileName.lowercase()
        val name = fileName.substringBeforeLast('.').ifBlank { fileName }

        val family = inferFamily(lower)
        val quantization = inferQuantization(lower)
        val capabilities = inferCapabilities(family, lower)
        val contextLength = inferContextLength(family, lower, sizeBytes)
        val thoughtChannelSyntax = inferChannelSyntax(family, lower)

        return HeuristicResult(
            displayName = name,
            family = family,
            quantization = quantization,
            capabilities = capabilities,
            contextLength = contextLength,
            thoughtChannelSyntax = thoughtChannelSyntax,
        )
    }

    /** 推断出的能力合并进已有 descriptor（用户显式设置优先，否则按启发式重算）。 */
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
            // Wave 48：来源决定采信谁 —— 用户改过（USER）则以持久化值为准，否则用启发式**替换**
            // （不再并集，避免「曾被启发式写 true」的位永久为 true）。
            capabilities = resolveCapabilities(
                descriptor.capabilities,
                descriptor.capabilitiesSource,
                result.capabilities,
            ),
            // 旧 null（Wave 48 之前的存量数据）首次重算即迁移为 HEURISTIC。
            capabilitiesSource = descriptor.capabilitiesSource ?: CapabilitySource.HEURISTIC,
            // Wave 49 R-A：思考通道语法是**模型事实**（描述容器真实的通道语法），不可用户编辑，
            // 故每次 refresh 无条件重算 —— 不采信持久化值（否则一次误写会永久覆盖正确元数据）。
            thoughtChannelSyntax = result.thoughtChannelSyntax,
        )
    }

    private fun inferFamily(lower: String): ModelFamily = when {
        // 注意顺序：gemma-3n 同时含 "3n" 与 "gemma-3"，必须先判 3n
        lower.contains("3n") -> ModelFamily.GEMMA_3N
        lower.contains("gemma-3") || lower.contains("gemma3") -> ModelFamily.GEMMA_3
        // gemma-4 不含 "3n"、也不含 "gemma-3"，与上面两条不冲突；紧邻 Gemma 分支保持可读
        lower.contains("gemma-4") || lower.contains("gemma4") -> ModelFamily.GEMMA_4
        lower.contains("qwen3") || lower.contains("qwen-3") -> ModelFamily.QWEN_3
        // MiniCPM 系先于 LLAMA 判断（litert-community 转换件架构是 LlamaForCausalLM，
        // 但家族口径按品牌）：minicpm5 文本系 / minicpm-v 视觉系共用 MINICPM 族。
        lower.contains("minicpm") -> ModelFamily.MINICPM
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
        // - ⚠️ Wave 53 容器取证（L1/L2）：image=true 已对 **gemma-4-E2B-it** 容器逐容器证实
        //   （确带 VISION_ENCODER 子图，非仅凭模型卡）；**E4B 容器未取得** ⇒ 该位对 E4B 属
        //   **家族外推**（模型卡依据 + 同族 E2B 实证），非逐容器实测 —— 勿据本行断言 E4B 已验。
        // - toolCalling：Google Gemma 4 模型卡「Function Calling – Native support for structured
        //   tool use, enabling agentic workflows」。
        // - thinking（W63 刀E 订正：false → true）：W62 真机实证 gemma-4 有 `<|channel>thought`
        //   思考通道且正常工作（V4 判据：`thinklen=2061` 即切分后的思考流、`finish=STOP`、
        //   正文 1005 字符完整作答 —— 通道切分由 thoughtChannelSyntax=GEMMA 显式下发保证）。
        //   原「未在 LiteRT-LM 侧查到可靠依据，按保守关闭」的依据已被一手证据推翻。
        //   ⚠️ E2B 为真机实证；E4B 变体未单独真机取证（GPU 变体引擎初始化即失败，见预设
        //   knownBrokenOn），按家族事实外推并如实标注 —— 沿用 image=true 对 E4B 的外推范式。
        // - speculativeDecoding：litert-community 两张模型卡均有独立章节写明
        //   「Speculative decoding is available on CPU and GPU on Mobile and Desktop」。
        // - 后端：模型卡 Android 基准只列 CPU / GPU（NPU 仅出现在 IoT 的独立 NPU 模型上），
        //   本工程预设也只提供 CPU / GPU 两个文件，故取 GPU + CPU。
        ModelFamily.GEMMA_4 -> ModelCapabilities(
            text = true,
            image = true,
            audio = true,
            toolCalling = true,
            thinking = true,
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

        // MiniCPM 系（Wave 20，逐项依据 = litert-community 转换仓 README + openbmb
        // 官方 tags）：
        // - minicpm5（文本）：官方 tags 有 tool-calling；两个转换件都声明 thought
        //   channel（<think>/</think>）→ thinking = true。int4 件模板默认思考、int8
        //   件默认直答，但能力位只描述「模型支持」，开关交给 ThinkingMode。
        // - minicpm-v（视觉）：image = true；toolCalling/thinking 未在转换仓查到依据，
        //   按「查不到就不设」保守关闭。
        // - 后端：MiniCPM5 README 有 Galaxy S26 GPU 全委托实测（已入 ModelGpuSupport
        //   白名单）；V 系未验证 → CPU 起步，GPU 由白名单拦。
        ModelFamily.MINICPM -> ModelCapabilities(
            text = true,
            image = lower.contains("minicpm-v"),
            audio = false,
            toolCalling = lower.contains("minicpm5"),
            thinking = lower.contains("minicpm5"),
            preferredBackends = setOf(InferenceBackend.CPU, InferenceBackend.GPU),
        )

        // OTHER 分支：无专属家族分支的模型（含 DeepSeek-R1 蒸馏系，见 inferFamily KDoc）。
        // - thinking（W61 修复 / W63 刀D 判据外提）：DeepSeek-R1 系推理模型文件名含 `qwen`
        //   但不含 `qwen3`/`qwen-3` ⇒ [inferFamily] 落 OTHER ⇒ 按**推理标识**识别 thinking，
        //   判据单一落点 = [isReasonerMarker]（与 inferChannelSyntax 的 R1 → THINK 分支共用）。
        //   ⚠️ 标记纪律与边界说明见该函数 KDoc（裸 `think` 禁用；`qwen2.5` 不得命中——已单测钉住）。
        //   ⚠️ 边界：本位的运行时效果**仅经 AUTO 档**（OFF/ON 由用户显式选择，见 LiteRtLmEngine
        //   的 thinkingOn 解析）；另 UI 模型卡「思考」chip 亦读本位。
        else -> ModelCapabilities(
            text = true,
            // "vl"：Qwen2-VL / LFM2.5-VL / SmolVLM；"minicpm-v"：MiniCPM-V 系列文件名是
            // "-V-"（无 "vl" 子串），但该系列全部是视觉模型（文本系 MiniCPM5/4 不含此段）。
            image = lower.contains("vl") || lower.contains("vision") || lower.contains("minicpm-v"),
            audio = lower.contains("audio") || lower.contains("omni"),
            toolCalling = lower.contains("it") || lower.contains("instruct"),
            thinking = isReasonerMarker(lower),
            preferredBackends = setOf(InferenceBackend.CPU),
        )
    }

    /**
     * R1 系推理标识（W61 引入 / W63 刀D 外提为共享纯函数）：`r1` ∨ `reasoner` ∨
     * （`deepseek` ∧ `distill`）。
     *
     * ⚠️ 标记纪律（W61 成文）：**不得**用裸 `think`（易误伤普通词）；`distill` 单独用
     * 也过宽，须与 `deepseek` 合取；Qwen2.5-1.5B 亦落 OTHER ⇒ **不得**命中 `qwen2.5`
     * （已单测钉住）。**单一落点**：[inferCapabilities]（OTHER 分支 thinking 位）与
     * [inferChannelSyntax]（R1 → THINK 显式下发）两处共用，防口径分叉。
     */
    private fun isReasonerMarker(lower: String): Boolean =
        lower.contains("r1") || lower.contains("reasoner") ||
            (lower.contains("deepseek") && lower.contains("distill"))

    /**
     * 思考通道语法归类（Wave 49 R-A）。**默认 `null`（信任容器元数据）** —— 只有一手证据证明
     * 「容器元数据**不含**该通道」的家族才给显式字面量。
     *
     * - **Gemma-4 → [ChannelSyntax.GEMMA]**：W43 `3e08b8f` 真机实锤 —— 不下发时
     *   `<|channel>thought…<channel|>` 原样混进正文 ⇒ 元数据不含该通道 ⇒ 必须显式下发。
     * - **R1 系（[isReasonerMarker] 命中）→ [ChannelSyntax.THINK]**（W63 刀D）：一手证据 =
     *   R1 容器字节扫描（`DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm`，
     *   1.83GB，2026-10-11）：`<|channel` / `channel` / `chat_template` / `bos_token` 全 0 命中；
     *   `<think>` 恰 1 处 @ offset `0x4067` —— 位于 protobuf 元数据的特殊 token 列表内，形态为
     *   `<｜Assistant｜><think>`（单个 25 字节字符串，与 `<｜User｜>`、`<｜begin▁of▁sentence｜>`
     *   等并列）⇒ 容器**无原生通道声明**、思考以 `<think>`/`</think>` 文本定界（助手轮以
     *   `<｜Assistant｜><think>` 开局）⇒ 符合「元数据不含该通道才显式下发」前置。
     *   ⚠️ 只对 [isReasonerMarker] 命中者生效 —— **勿扩大面**：MiniCPM5 类容器元数据**自带**
     *   thought 通道，显式下发会覆盖正确元数据（N1 教训）。
     * - **其余全部 `null`**：含 MiniCPM5（`RM_MiniCPM5-2B.md:106/163`：容器元数据**自带**
     *   `thought` 通道 `<think>`/`</think>`）、未知模型、非思考模型。
     *   ⚠️ **不得为「看起来完整」给 Gemma 字面量** —— 那正是 N1 本体（错误覆盖正确元数据）。
     *
     * ⚠️ 已知误分类（顺带登记，本波不改）：`DeepSeek-R1-Distill-Qwen-1.5B` 文件名含 `qwen` 但不含
     * `qwen3` ⇒ [inferFamily] 落 `OTHER`（其 KDoc 已登记）。
     *
     * ⚠️ `lower` 参与 R1 判定（[isReasonerMarker]）；R-A 回退档一行落点
     * （`family == MINICPM && lower.contains("minicpm5") -> ChannelSyntax.THINK`，见设计 §3.2）
     * 仍保留在 `when` 可达位置。
     */
    private fun inferChannelSyntax(family: ModelFamily, lower: String): ChannelSyntax? = when {
        family == ModelFamily.GEMMA_4 -> ChannelSyntax.GEMMA
        family == ModelFamily.OTHER && isReasonerMarker(lower) -> ChannelSyntax.THINK
        else -> null
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
            // MiniCPM5 原生 131k，但 litert-community 转换件把 KV 预算定死在
            // max_num_tokens=4096（转换仓 README 明示）—— 配置给再大也是白给，
            // 只会让压缩器塞进超预算的历史。按「宁可给小」取 4096。
            ModelFamily.MINICPM -> 4096
            ModelFamily.LLAMA -> 8192
            ModelFamily.PHI -> 4096
            ModelFamily.OTHER -> if (sizeBytes >= 4L * 1024 * 1024 * 1024) 8192 else 4096
        }
    }

    /**
     * 能力位来源解析（Wave 48，纯函数，可 JVM 单测）。
     *
     * - `source == USER` ⇒ 采信持久化值（用户显式编辑优先，启发式**不再改写**）；
     * - 其它（`HEURISTIC` / **旧数据 `null`**）⇒ 用启发式**替换**（旧 null 视作 HEURISTIC
     *   ⇒ 重算，修好存量虚高；见 `wave48-design.md` §3.3 迁移策略 (i)）。
     *
     * 刻意不做并集（旧 `mergeHeuristic` 语义）：并集只增不减，会让「曾被启发式写 true」的位
     * 永久为 true（真机 `_w45_models.json` 5 模型全虚高），用户手动关掉的位被反复抬回。
     */
    internal fun resolveCapabilities(
        persisted: ModelCapabilities,
        source: CapabilitySource?,
        heuristic: ModelCapabilities,
    ): ModelCapabilities = if (source == CapabilitySource.USER) persisted else heuristic
}
