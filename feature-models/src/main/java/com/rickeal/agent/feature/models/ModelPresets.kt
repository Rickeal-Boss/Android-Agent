package com.rickeal.agent.feature.models

/**
 * 内置模型预设：**只收录 Google 官方 `litert-community` 组织下真实存在的文件**，
 * 链接与体积均按 Hugging Face API 核对过（2026-09），不使用社区随机仓库，避免下到损坏/来路不明的权重。
 *
 * 格式说明（很重要）：
 *  - `.litertlm` = 新版 LiteRT-LM 运行时格式，本项目引擎直接支持。
 *  - `.task`    = 旧版 MediaPipe / LiteRT Task 格式，仅作兼容导入，推理行为可能受限。
 *
 * ## 关于「建议可用内存」的口径（务必读完再改数字）
 *
 * 估算公式：**(W × f_backend + KV(n) + O) × 1.25**
 *   - `W` = `.litertlm` 文件体积（权重）
 *   - `f_backend` = CPU 1.05 / **GPU 1.25** / NPU 1.15
 *     —— 系数必须**区分后端**：GPU 后端除常驻权重外还要额外承担 OpenCL buffer 与可能的 fp16 权重副本。
 *     注意 GPU 变体的**文件更小**（E2B-GPU 1.87GB < CPU 版 2.41GB），那是重新打包省的磁盘体积，
 *     **不是运行内存**。早期版本用统一的 1.5× 估算，导致 GPU 变体低估约 20~27%。
 *   - `KV(n)` = 2 × L(层) × H_kv(kv 头数) × D(head_dim) × n(上下文 token) × 2 字节
 *   - `O` = max(200MB, 0.12 × W)（运行时 + prefill 激活）
 *   - `×1.25` = Android 安全余量（无 swap + LMK + App 自身 150~300MB）
 *
 * 两点必须避开：
 *  1. **不要拿 Edge0 披露的 2.9GB / 1.0GB 反推系数** —— 那是 MoE + SSD 流式加载下的
 *     "Measured peak **activation** memory"，**不含权重驻留**，与我们的 dense 全量常驻口径完全不同。
 *  2. 本口径仅适用于 **4k 上下文**；上下文调大需按上面 KV(n) 线性上调。
 *
 * 这些是**估算值**，真机实测后应回填 `ModelDescriptor` 的实测字段。
 */
data class ModelPreset(
    val label: String,
    val url: String,
    val sizeText: String,
    /** 面向用户的「建议可用内存」下界文本。 */
    val ramText: String,
    /** 加载前内存闸门用的阈值（字节）。 */
    val requiredRamBytes: Long,
    /** 下载体积（字节），用于下载前的存储空间检查。 */
    val sizeBytes: Long,
    /** 是否推荐给新手（排序时置顶，UI 打「新手推荐」标识）。 */
    val recommended: Boolean = false,
    /** 数字是怎么来的，写进数据里，后人才能校准。 */
    val memBasis: String,
    val note: String,
)

object ModelPresets {

    private const val BASE = "https://huggingface.co/litert-community"
    private const val GB = 1_073_741_824L

    private const val BASIS_GPU =
        "W×1.25(GPU)+KV@4096+O，×1.25 安全余量"
    private const val BASIS_CPU =
        "W×1.05(CPU)+KV@4096+O，×1.25 安全余量"

    val all: List<ModelPreset> = listOf(
        ModelPreset(
            label = "Gemma 4 E2B · GPU",
            url = "$BASE/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it-gpu.litertlm",
            sizeText = "1.87 GB",
            ramText = "≥ 3.6 GB",
            requiredRamBytes = (3.6 * GB).toLong(),
            memBasis = BASIS_GPU,
            note = "端侧主力：画质与速度平衡，有 GPU/NPU 的手机首选",
            sizeBytes = 2007897210,
            recommended = true,
        ),
        ModelPreset(
            label = "Gemma 4 E2B · CPU",
            url = "$BASE/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            sizeText = "2.41 GB",
            ramText = "≥ 3.9 GB",
            requiredRamBytes = (3.9 * GB).toLong(),
            memBasis = BASIS_CPU,
            note = "同上的 CPU 版：兼容性最好，慢一些但不容易出错",
            sizeBytes = 2587717795,
            recommended = false,
        ),
        ModelPreset(
            label = "Gemma 4 E4B · GPU",
            url = "$BASE/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it-gpu.litertlm",
            sizeText = "2.77 GB",
            ramText = "≥ 5.2 GB",
            requiredRamBytes = (5.2 * GB).toLong(),
            memBasis = BASIS_GPU,
            note = "更大的模型：回答更好，需要 8GB 以上内存的手机",
            sizeBytes = 2974264852,
            recommended = false,
        ),
        ModelPreset(
            label = "Qwen2.5 1.5B · q8",
            url = "$BASE/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            sizeText = "1.49 GB",
            ramText = "≥ 2.4 GB",
            requiredRamBytes = (2.4 * GB).toLong(),
            memBasis = BASIS_CPU,
            note = "中文好、体积小，6GB 内存的手机也能流畅跑",
            sizeBytes = 1599875317,
            // 注意：recommended 只允许两项（MiniCPM5 主推 + Gemma 4 E2B·GPU 备选），
            // 标多了就失去「替用户做决定」的意义。
            recommended = false,
        ),
        ModelPreset(
            label = "DeepSeek-R1 蒸馏 Qwen 1.5B",
            url = "$BASE/DeepSeek-R1-Distill-Qwen-1.5B/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
            sizeText = "1.71 GB",
            ramText = "≥ 2.7 GB",
            requiredRamBytes = (2.7 * GB).toLong(),
            memBasis = BASIS_CPU,
            note = "会先「想一想」再回答，适合体验思考过程",
            sizeBytes = 1836098519,
            recommended = false,
        ),
        ModelPreset(
            label = "MiniCPM5 2B · int4",
            url = "$BASE/MiniCPM5-2B/resolve/main/MiniCPM5-2B_int4.litertlm",
            sizeText = "1.45 GB",
            ramText = "≥ 2.4 GB",
            requiredRamBytes = (2.4 * GB).toLong(),
            memBasis = BASIS_CPU,
            note = "新手推荐：体积小、中文强，大多数手机都能跑",
            sizeBytes = 1556925644,
            recommended = true,
        ),
        ModelPreset(
            label = "Phi-4-mini · q8",
            url = "$BASE/Phi-4-mini-instruct/resolve/main/Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            sizeText = "3.64 GB",
            ramText = "≥ 5.6 GB",
            requiredRamBytes = (5.6 * GB).toLong(),
            memBasis = BASIS_CPU,
            note = "推理与代码能力强，但体积大，仅建议 12GB 内存机型",
            sizeBytes = 3908420239,
            recommended = false,
        ),
        ModelPreset(
            label = "LFM2.5-VL 450M · 视觉",
            url = "$BASE/LFM2.5-VL-450M/resolve/main/LFM2.5-VL-450M_int8.litertlm",
            sizeText = "0.52 GB",
            ramText = "≥ 1.0 GB",
            requiredRamBytes = (1.0 * GB).toLong(),
            memBasis = BASIS_CPU,
            note = "能看懂图片：用来体验拍照问答，体积最小",
            sizeBytes = 558345748,
            recommended = false,
        ),
    )

    /** 推荐项置顶，供「一键获取模型」对话框使用。 */
    val recommendedFirst: List<ModelPreset> = all.sortedByDescending { it.recommended }

    /** 按 URL 反查预设（用于下载卡片显示口径与内存阈值）。 */
    fun findByUrl(url: String): ModelPreset? = all.firstOrNull { it.url == url }
}
