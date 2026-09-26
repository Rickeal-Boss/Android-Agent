package com.rickeal.agent.core.model

/**
 * 按模型的采样参数档案（Wave 20，2026-09-26）。
 *
 * ## 为什么存在
 *
 * 全局默认（temperature 0.7 / topK 40 / topP 0.95）对每个模型都「差不多对」，但 Wave 18/19
 * 的真机反馈证明「差不多」在端侧小模型上就是会出事：Qwen2.5 官方要 repetition_penalty 1.1
 * 才不循环；DeepSeek-R1 蒸馏模型温度低于 0.5 会把思维链冻住；MiniCPM5 官方建议 1.0 而我们
 * 的 agent 覆写是 0.4；SmolVLM2 的 README 明说贪心解码「repetitive/verbose」。每个模型的
 * 安全区间与推荐值都来自**官方仓库的一手资料**（generation_config.json / 模型卡 / 转换仓
 * README），证据逐条写在 [evidence] 里，改数字前先读。
 *
 * ## 生效位置（唯一）
 *
 * [AgentRunner][com.rickeal.agent.core.agent.AgentRunner] 组装 InferenceConfig 之后、
 * 交给引擎加载之前（`ModelSamplingProfiles.appliedTo(request.model?.fileName, config)`）。
 * 这是所有对话/agent/子 run 的唯一汇聚点，因此：
 *  - UI 参数面板保存的用户值**不会被改写**，只在真正推理那一刻被钳制（面板显示与生效值
 *    可能不同，这是有意的：档案是「运行时安全围栏」，不是「用户设置改写器」）；
 *  - 按文件名精确匹配（与 [ModelGpuSupport]、`ModelPresets.findByFileName` 同一约定），
 *    SAF 导入 / DownloadManager 重名落盘（name-1.ext）匹配不上 → 返回原配置，零行为变化。
 *
 * ## 钳制语义（幂等、单调，方便反复套用）
 *
 *  - temperature → clamp 进 [temperatureRange]；
 *  - topK → clamp 进 1..[maxTopK]；
 *  - repetitionPenalty → max(用户值, [recommendedRepetitionPenalty])：档案值是**下限**
 *    而非改写（Qwen2.5 官方 1.1，用户想更狠可以拉到 1.3，但默认 1.0 会被抬到 1.1）；
 *  - maxTokens → max(用户值, [minMaxTokens])：思考模型的思维链被截断就交不出答案
 *    （MiniCPM5 README 原话：truncated mid-thought it produces no final answer at all）；
 *  - contextLength → min(用户值, [maxContextLength])：`.litertlm` 的 KV 预算是转换时
 *    定死的（ekv4096 文件 / metadata max_num_tokens=4096），配置调得再大也是白给，
 *    只会让上层压缩器塞进超出预算的历史。
 *
 * 不做 @Serializable：档案是随版本分发的静态注册表（不落库不迁移），而
 * ClosedFloatingPointRange 本就没有 kotlinx 序列化器 —— 保留纯 data class。
 */
data class ModelSamplingProfile(
    /** 推荐温度（参数面板「按模型重置」类语义的依据，不直接改写用户值）。 */
    val recommendedTemperature: Float,
    /** 温度硬区间。下限防贪心循环，上限防高温胡言。 */
    val temperatureRange: ClosedFloatingPointRange<Float>,
    val recommendedTopK: Int,
    val maxTopK: Int,
    val recommendedTopP: Float = 0.95f,
    /** 重复惩罚下限。1.0 = 该模型不需要（保持关闭）。 */
    val recommendedRepetitionPenalty: Float = 1.0f,
    /** 最大输出 token 下限（思考模型 >0）。0 = 不干预。 */
    val minMaxTokens: Int = 0,
    /** 运行时 KV 预算上限（上下文裁剪预算不得超过）。 */
    val maxContextLength: Int,
    /** 数值出处。改任何数字都必须同步更新这条证据。 */
    val evidence: String,
)

object ModelSamplingProfiles {

    /**
     * 按 `.litertlm` 文件名精确匹配（去掉 DownloadManager 重名后缀的情况匹配不上，
     * 与 ModelGpuSupport 同一口径：从严）。
     */
    fun forFileName(fileName: String?): ModelSamplingProfile? = when (fileName) {
        // Gemma 4（E2B/E4B）：litert-community README 未给采样建议；沿用 Gemma 系模型卡
        // 一贯口径（temp 1.0 / topP 0.95 / topK 64）。上下文封顶 8192：README 标 32k 上限，
        // 但预设内存全部按 KV@4096 预算（ModelPresets 头注），放宽到 32k 会让内存闸门
        // 系统性低估 —— 8192 是「预算内、又不至于把 4k 长对话压得太狠」的取值。
        "gemma-4-E2B-it-gpu.litertlm",
        "gemma-4-E2B-it.litertlm",
        "gemma-4-E4B-it-gpu.litertlm",
        -> ModelSamplingProfile(
            recommendedTemperature = 1.0f,
            temperatureRange = 0.3f..1.5f,
            recommendedTopK = 64,
            maxTopK = 128,
            recommendedTopP = 0.95f,
            maxContextLength = 8192,
            evidence = "litert README 无采样建议 → Gemma 系口径 temp1.0/topP0.95/topK64；" +
                "README 标 32k 上限但内存按 KV@4096 预算 → 上下文封顶 8192",
        )

        // Qwen2.5-1.5B-Instruct：官方 generation_config.json 逐字段照抄
        //（temperature 0.7 / top_p 0.8 / top_k 20 / repetition_penalty 1.1）。
        // 文件名 ekv4096 = KV 预算 4096，上下文封顶同值。
        "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm" -> ModelSamplingProfile(
            recommendedTemperature = 0.7f,
            temperatureRange = 0.3f..1.2f,
            recommendedTopK = 20,
            maxTopK = 100,
            recommendedTopP = 0.8f,
            recommendedRepetitionPenalty = 1.1f,
            maxContextLength = 4096,
            evidence = "官方 generation_config.json：temp0.7/topP0.8/topK20/repPen1.1；" +
                "文件名 ekv4096 = KV 预算 4096",
        )

        // DeepSeek-R1-Distill-Qwen-1.5B：官方模型卡明示「0.5–0.7（推荐 0.6），贪心会
        // 重复循环、应避免」。上下文 ekv4096；思维链必须完整 → maxTokens 下限 2048
        //（1.5B 蒸馏版思维链通常 1~3k token，1024 默认值会被拦腰截断）。
        "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm" -> ModelSamplingProfile(
            recommendedTemperature = 0.6f,
            temperatureRange = 0.5f..0.7f,
            recommendedTopK = 40,
            maxTopK = 100,
            recommendedTopP = 0.95f,
            minMaxTokens = 2048,
            maxContextLength = 4096,
            evidence = "官方模型卡：temp 0.5–0.7（rec 0.6）/ topP 0.95，明确警告贪心循环；" +
                "ekv4096 KV 预算；思维链需 ≥2048 token 预算",
        )

        // MiniCPM5-2B：转换仓 README 明示「OpenBMB recommends temperature 1.0, top_p 0.95」；
        // 原生上下文 131k 但 litertlm 元数据 max_num_tokens=4096（KV 预算）→ 封顶 4096；
        // 思考模式「truncated mid-thought it produces no final answer at all」→ ≥2048。
        "MiniCPM5-2B_int4.litertlm",
        "MiniCPM5-2B_int8.litertlm",
        -> ModelSamplingProfile(
            recommendedTemperature = 1.0f,
            temperatureRange = 0.5f..1.3f,
            recommendedTopK = 40,
            maxTopK = 128,
            recommendedTopP = 0.95f,
            minMaxTokens = 2048,
            maxContextLength = 4096,
            evidence = "litert-community README：OpenBMB 建议 temp1.0/topP0.95；" +
                "原生 131k 但 litertlm 元数据 max_num_tokens=4096；思考 run 需 ≥2048 token",
        )

        // Phi-4-mini：官方 generation_config 为空、模型卡示例用贪心（基准口径）。
        // 3.8B 体量不至于一降温就瘫，但端侧无 repeat penalty 时代贪心已多次实锤循环
        //（Wave 18/19），下限 0.2 保留近贪心空间、推荐值用全局默认。
        "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm" -> ModelSamplingProfile(
            recommendedTemperature = 0.7f,
            temperatureRange = 0.2f..1.2f,
            recommendedTopK = 40,
            maxTopK = 128,
            recommendedTopP = 0.95f,
            maxContextLength = 4096,
            evidence = "官方 generation_config 为空（示例贪心，基准口径）；ekv4096 KV 预算；" +
                "端侧防循环经验设下限 0.2，推荐值沿用全局默认",
        )

        // SmolVLM2-500M：转换仓 README 原话「use sensible max_tokens and use sampling
        // (e.g. top-p); at pure greedy it can be repetitive/verbose」→ 温度下限 0.3。
        // repPen 下限 1.05：Wave 21 真机该模型单字符退化实锤（「、」单字符行无限刷屏），
        // README 警告贪心 repetitive → 取保守下限；小模型 repPen 副作用（抑制高频但
        // 合法的词）更明显（Welleck 2020 unlikelihood 训练结论的推理期类比），故不取 1.1。
        "SmolVLM2-500M.litertlm" -> ModelSamplingProfile(
            recommendedTemperature = 0.7f,
            temperatureRange = 0.3f..1.2f,
            recommendedTopK = 40,
            maxTopK = 128,
            recommendedTopP = 0.9f,
            recommendedRepetitionPenalty = 1.05f,
            maxContextLength = 4096,
            evidence = "转换仓 README：贪心会 repetitive/verbose，要求 top-p 采样 → 下限 0.3；" +
                "Wave21 真机单字符退化实证（「、」刷屏）；README 警告贪心 repetitive → " +
                "保守 1.05 下限（小模型 repPen 副作用更明显，Welleck 2020 unlikelihood 结论，不取 1.1）；" +
                "基准口径 max-num-tokens 4096",
        )

        // Qwen2-VL-2B：官方 generation_config 近贪心（temp 0.01 / top_p 0.001 / topK 1，
        // VQA/OCR 基准口径）。端侧聊天按贪心跑会在长描述上重复（SmolVLM2 README 同族警告），
        // 取温和折衷并放宽下限到 0（OCR 类任务用户可以自己调回近贪心）。
        "Qwen2-VL-2B.litertlm" -> ModelSamplingProfile(
            recommendedTemperature = 0.3f,
            temperatureRange = 0.0f..1.2f,
            recommendedTopK = 20,
            maxTopK = 128,
            recommendedTopP = 0.8f,
            maxContextLength = 4096,
            evidence = "官方 generation_config 近贪心（0.01/0.001/topK1，基准口径）；" +
                "端侧聊天取折衷 0.3/20/0.8 防重复，下限 0 保留 OCR 近贪心空间",
        )

        // LFM2.5-VL / MiniCPM-V-4：官方 generation_config 均为空（未披露采样建议），
        // 沿用全局默认值；上下文按 litertlm KV 预算 4096 封顶。
        "LFM2.5-VL-450M_int8.litertlm",
        "LFM2.5-VL-1.6B_int4_fixB.litertlm",
        "LFM2.5-VL-3B_int4_fixB.litertlm",
        "MiniCPM-V-4-int8.litertlm",
        -> ModelSamplingProfile(
            recommendedTemperature = 0.7f,
            temperatureRange = 0.3f..1.2f,
            recommendedTopK = 40,
            maxTopK = 128,
            recommendedTopP = 0.95f,
            maxContextLength = 4096,
            evidence = "官方 generation_config 为空（未披露采样建议）→ 全局默认；" +
                "litertlm KV 预算 4096 封顶",
        )

        else -> null
    }

    /**
     * 把 [config] 按 [fileName] 命中的档案钳制一遍；未命中返回原值（无行为变化）。
     * 任何字段被改动都记一条 info（AgentLogStore），真机排查「为什么我的温度没生效」时
     * 这是唯一线索。
     */
    fun appliedTo(fileName: String?, config: InferenceConfig): InferenceConfig {
        val profile = forFileName(fileName) ?: return config
        val s = config.sampling
        val clampedSampling = s.copy(
            temperature = s.temperature.coerceIn(profile.temperatureRange.start, profile.temperatureRange.endInclusive),
            topK = s.topK.coerceIn(1, profile.maxTopK),
            repetitionPenalty = maxOf(s.repetitionPenalty, profile.recommendedRepetitionPenalty),
        )
        val clamped = config.copy(
            sampling = clampedSampling,
            maxTokens = maxOf(config.maxTokens, profile.minMaxTokens),
            contextLength = minOf(config.contextLength, profile.maxContextLength),
        ).coerce()
        if (clamped != config) {
            AgentLogStore.info(
                "模型采样档案生效：${fileName ?: "?"} → temp ${clamped.sampling.temperature}" +
                    "/topK ${clamped.sampling.topK}/topP ${clamped.sampling.topP}" +
                    "/repPen ${clamped.sampling.repetitionPenalty}" +
                    "/maxTok ${clamped.maxTokens}/ctx ${clamped.contextLength}（${profile.evidence}）"
            )
        }
        return clamped
    }
}
