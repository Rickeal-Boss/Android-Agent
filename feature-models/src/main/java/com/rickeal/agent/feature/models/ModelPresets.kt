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
 *   - `f_backend` = CPU 1.05 / **GPU 1.25** / NPU **未实测**
 *     —— 系数必须**区分后端**：GPU 后端除常驻权重外还要额外承担 OpenCL buffer 与可能的 fp16 权重副本。
 *     注意 GPU 变体的**文件更小**（E2B-GPU 1.87GB < CPU 版 2.41GB），那是重新打包省的磁盘体积，
 *     **不是运行内存**。早期版本用统一的 1.5× 估算，导致 GPU 变体低估约 20~27%。
 *   - **NPU 刻意不给具体数字**：NPU 峰值约等于「权重 + N × HTP scratch」，而 scratch 是 GB 量级的，
 *     实际需求几乎必然**高于** GPU。曾写过的 NPU 1.15（比 GPU 还低）是危险方向 —— 低估会让内存
 *     闸门放行、加载时 native 崩溃，而不是把用户拦下。所以需要时按「**不低于 GPU**」处理，等真机
 *     测出 scratch 开销再校准。**别为了"看起来完整"补一个未经验证的倍率。**
 *   - `KV(n)` = 2 × L(层) × H_kv(kv 头数) × D(head_dim) × n(上下文 token) × 2 字节
 *   - `O` = max(200MB, 0.12 × W)（运行时 + prefill 激活）
 *   - `×1.25` = Android 安全余量（无 swap + LMK + App 自身 150~300MB）
 *   - **没有预设可对时**（SAF 导入的自定义模型、或因重名落成 `name-1.ext` 的下载），
 *     `ModelsViewModel.estimateRequiredRamBytes` 用同一条式子但**没有 KV 项**，于是系统性
 *     偏乐观，由调用点乘补偿系数 `×1.13` 补上（常量
 *     `ModelsViewModel.NO_PRESET_KV_COMPENSATION`）。它不是随手定的：按下面 8 条反算
 *     「估算 / 预设」的比值，最大缺口出现在 E2B·GPU = **1.1242**（其余 1.000~1.112），
 *     向上取整即 1.13。
 *   - **这个补偿只作用于无预设那一支**：有预设时预设才是权威值（`maxOf(preset, 估算)`，
 *     估算保持 `×1.25`）。给有预设的模型也乘一次，会把闸门抬到预设之上 —— 例如
 *     Phi-4-mini 会从 5.6 被抬到 6.0 GiB，把刚消掉的「大模型误拦」加回来，而且卡片上
 *     「≥ 5.6 GB」的文案会和实际闸门对不上。**改这里的系数要同步改那边，反之亦然。**
 *
 * 两点必须避开：
 *  1. **不要拿 Edge0 披露的 2.9GB / 1.0GB 反推系数** —— 那是 MoE + SSD 流式加载下的
 *     "Measured peak **activation** memory"，**不含权重驻留**，与我们的 dense 全量常驻口径完全不同。
 *  2. 本口径仅适用于 **4k 上下文**；上下文调大需按上面 KV(n) 线性上调。
 *
 * 这些是**估算值**，真机实测后应回填 `ModelDescriptor` 的实测字段。
 *
 * 校准锚点（Google 官方 `google-ai-edge/gallery` 的 `model_allowlist.json`，含
 * `estimatedPeakMemoryInBytes` 字段）：
 *   - Gemma 3n E2B int4：2.92GB 权重 → 峰值 5.5GB（≈1.9×）
 *   - Gemma 3n E4B int4：4.10GB 权重 → 峰值 6.5GB（≈1.6×）
 *   - Gemma3-1B q4   ：0.52GB 权重 → 峰值 2.0GB（≈3.9×，小模型由固定基线主导）
 * 结论：中大型模型用 1.6~1.9× 是准的，但**小模型不能按体积线性缩放**，
 * 必须设下限（约 2GB），否则会低估到以为「512MB 模型谁都能跑」。
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

    /**
     * 1 GiB（2^30）。**刻意沿用「GB」这个命名、以及界面上「GB」的写法，别改。**
     *
     * 数值口径是二进制的（`sizeBytes` / `requiredRamBytes` / `formatBytes` 全部按 2^30 算），
     * 但面向用户的文案写「GiB」只是给非技术用户加噪音，而 2^30 与 10^9 之间那 7% 的差异
     * 落在一个本身还是估算值的阈值上，不构成任何决策变化。所以：**内部按 GiB 算，对外说 GB**。
     */
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
            note = "体积小、中文强，大多数手机都能跑",
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
            ramText = "≥ 2.0 GB",
            requiredRamBytes = (2.0 * GB).toLong(),
            memBasis = BASIS_CPU,
            note = "能看懂图片：用来体验拍照问答，体积最小",
            sizeBytes = 558345748,
            recommended = false,
        ),
    )

    /** 推荐项的展示顺序（数字小的在前）。别依赖 sortedByDescending 的稳定性。 */
    private val recommendedOrder = listOf(
        "MiniCPM5 2B · int4",
        "Gemma 4 E2B · GPU",
    )

    /** 推荐项置顶且按 recommendedOrder 排序，供「一键获取模型」对话框使用。 */
    val recommendedFirst: List<ModelPreset> =
        all.filter { it.recommended }.sortedBy { preset ->
            val index = recommendedOrder.indexOf(preset.label)
            if (index < 0) Int.MAX_VALUE else index
        } + all.filter { !it.recommended }

    /** 按 URL 反查预设（用于下载卡片显示口径与内存阈值）。 */
    fun findByUrl(url: String): ModelPreset? = all.firstOrNull { it.url == url }

    /**
     * 按**文件名**反查预设。
     *
     * 为什么需要它：加载前的内存闸门（`ModelsViewModel.onLoad`）手上只有一个
     * [com.rickeal.agent.core.model.ModelDescriptor]，**没有 URL**，拿不到 [ModelPreset.requiredRamBytes]。
     * 下载链路里文件名就是 URL 的末段（`onDownloadFromUrl` 的 `fileName` 派生），而 8 个预设的
     * 文件名只含 `[A-Za-z0-9._-]`，不会被 `ModelDownloader.sanitizeFileName` 改写，所以能稳定匹配。
     *
     * **查不到是正常情况，不是错误**：SAF 导入的用户自定义文件、以及因重名被 DownloadManager
     * 落成 `name-1.ext` 的下载，都匹配不上 —— 调用方必须自带兜底估算，不能把 null 当异常。
     */
    fun findByFileName(name: String): ModelPreset? =
        all.firstOrNull { it.url.substringAfterLast('/') == name }
}
