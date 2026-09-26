package com.rickeal.agent.feature.models

import com.rickeal.agent.core.model.ModelGpuSupport

/**
 * 内置模型预设：**只收录 Google 官方 `litert-community` 组织下真实存在的文件**，
 * 链接与体积均按 Hugging Face API 核对过（2026-09 收录；2026-09-24 全量复核体积并增补
 * 5 条视觉多模态预设与国内镜像源），不使用社区随机仓库，避免下到损坏/来路不明的权重。
 *
 * ## 下载镜像（2026-09-24 增补，服务国内用户）
 *
 * 每条预设 = 主源 HuggingFace + 两路国内镜像（UI 可一键换源，直链均为 https）：
 *  - `hf-mirror.com`：HF 全站 1:1 代理，路径规则与主源完全一致（仅换域名），
 *    resolve 直链实测 302 → CDN 直通，无需逐仓维护。
 *  - `www.modelscope.cn`：**逐仓核实过文件真实存在**（2026-09-24，13/13 仓全过，
 *    含 gemma-4 全系），LFS 直链 `resolve/master/<file>` 实测 200。魔搭没有全站
 *    代理语义，仓库缺失就是死链 —— 新增镜像前必须先打 repo/files API 核实。
 *
 * ## 视觉多模态预设的取舍（2026-09-24 帕累托前沿调研结论）
 *
 *  - **只收 `.litertlm` 官方转换件**：引擎只吃这个格式，原始权重再强也进不了推理链路。
 *  - LFM2.5-VL 系列收 **`_fixB` 修复版**：stock int4 的 vision graph 有「模型只能看到
 *    图片顶部 1/4」的已知 bug（上游 LiteRT-LM#3246），fixB 把 2×2 pixel-unshuffle
 *    重导出进编码器后整图可见，体积几乎不变。
 *  - **刻意排除**（都是想清楚才不收，别顺手加回来）：
 *    - `google/gemma-3n-E2B/E4B-it-litert-lm`：HF `gated: manual`，DownloadManager
 *      不带访问令牌，收了就是 401 死链（本应用不做 HF 授权流程）。
 *    - `InternVL3_5-1B/2B/4B`：转换件文件名固定 `model.litertlm`，多条预设重名会触发
 *      DownloadManager 的 `model-1.litertlm` 改名 → [findByFileName] 失配，内存闸门
 *      退化为无预设估算；且同档位体积/能力被 LFM2.5-VL 压制。
 *    - `FastVLM-0.5B` / `LLaVA-OneVision-0.5B`：与 SmolVLM2-500M、LFM2.5-VL 同档，
 *      帕累托被压制（体积更大或能力更弱），收录只会稀释选择。
 *  - **引擎版本提醒**：VL bundle 的官方验证运行时是 litert-lm 0.16.x；本仓引擎
 *    litertlm-android 0.11.0 能否加载这些视觉图**未真机验证**（既有 LFM2.5-VL-450M
 *    预设同此状态）。真机加载失败的第一排查方向：评估升级 litertlm-android（独立波次）。
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
 *     偏乐观，由调用方把尾部余量从 `1.25` 换成 `1.41`（常量
 *     `ModelsViewModel.NO_PRESET_KV_COMPENSATION`）。它不是随手定的：令
 *     `(W·f+O)·c = (W·f+KV+O)·1.25` 得 `c = 1.25 × (1 + KV/(W·f+O))`，按下面 13 条反算右边，
 *     最大缺口仍出现在 E2B·GPU（KV 0.318 / 底 2.562）≈ **1.4052**（2026-09-24 增补的
 *     5 条视觉预设最高只到 1.4033·MiniCPM-V-4，未越界），向上取整即 1.41。
 *   - **这个余量只作用于无预设那一支**：有预设时预设才是权威值（`maxOf(preset, 估算)`，
 *     估算保持 `×1.25`）。给有预设的模型也用 1.41，会把闸门抬到预设之上 —— 例如
 *     Phi-4-mini 会从 5.6 被抬到 6.0 GiB，把刚消掉的「大模型误拦」加回来，而且卡片上
 *     「≥ 5.6 GB」的文案会和实际闸门对不上。**改这里的系数要同步改那边，反之亦然。**
 *   - 尾部余量作用在 2GB 下限**之前**：下限代表不随权重缩放的固定开销，余量补的是 KV(n)，
 *     两者不该相乘（否则无预设的小模型下限会变成 2.82 GiB 而不是 2.00）。
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
/**
 * 预设的备用下载源（国内镜像直链）。主源永远是 [ModelPreset.url]（HuggingFace）；
 * 镜像与主源**逐字节同源**（hf-mirror 是代理、ModelScope 是镜像仓），所以文件名、
 * 体积、内存闸门口径完全一致 —— 换源不影响 [ModelPresets.findByFileName]。
 */
data class ModelPresetMirror(
    /** UI 换源行上显示的短标签。 */
    val label: String,
    val url: String,
)

data class ModelPreset(
    val label: String,
    val url: String,
    val sizeText: String,
    /** 面向用户的「建议可用内存」下界文本。 */
    val ramText: String,
    /** 加载前内存闸门用的阈值（字节）。 */
    val requiredRamBytes: Long,
    /** 下载体积（字节），用于下载前的存储空间检查。主源与镜像同文件，体积一致。 */
    val sizeBytes: Long,
    /** 国内镜像直链（hf-mirror / ModelScope），2026-09-24 起全预设配齐；主源不在列。 */
    val mirrors: List<ModelPresetMirror> = emptyList(),
    /** 是否推荐给新手（排序时置顶，UI 打「新手推荐」标识）。 */
    val recommended: Boolean = false,
    /** 数字是怎么来的，写进数据里，后人才能校准。 */
    val memBasis: String,
    val note: String,
    /** GPU 放行/禁用的依据与警示（模型卡展示）。空串 = 无特别说明。 */
    val backendBasis: String = "",
) {
    /**
     * 是否放行 GPU 后端（2026-09-26）。**判定源下沉到 [ModelGpuSupport]**（core-model）：
     * GPU 路径不支持时是 native 崩溃（用户只看到闪退），除了本表的 UI 选择拦截，
     * 对话链路（AgentRunner → EngineEnvironment.loadConfig）也要做加载前兜底 ——
     * 判定必须是同一数据源，否则 UI 与引擎各判各的必然漂移。
     */
    val gpuSupported: Boolean
        get() = ModelGpuSupport.gpuVerifiedFileNames.contains(url.substringAfterLast('/'))
    /** 全部可用直链：主源在前，镜像在后。 */
    val allUrls: List<String>
        get() = listOf(url) + mirrors.map { it.url }

    /** 该直链是否属于本预设（主源或任一镜像）—— 换到镜像源仍是同一个模型。 */
    fun ownsUrl(url: String): Boolean = allUrls.contains(url)
}

object ModelPresets {

    private const val BASE = "https://huggingface.co/litert-community"

    // 国内镜像根。hf-mirror 是 HF 全站 1:1 代理（换域名即得）；ModelScope 镜像仓
    // 逐仓核实过（2026-09-24），且魔搭默认分支是 master 而非 main。
    private const val HF_MIRROR_BASE = "https://hf-mirror.com/litert-community"
    private const val MODELSCOPE_BASE = "https://www.modelscope.cn/litert-community"

    /**
     * 生成两路国内镜像直链。**调用前必须先用魔搭 repo/files API 核实该仓库与文件
     * 真实存在**（本文件全部预设已于 2026-09-24 逐仓核验），禁止凭感觉补链接。
     */
    private fun domesticMirrors(repo: String, file: String): List<ModelPresetMirror> = listOf(
        ModelPresetMirror("hf-mirror · 国内加速", "$HF_MIRROR_BASE/$repo/resolve/main/$file"),
        ModelPresetMirror("ModelScope · 国内直连", "$MODELSCOPE_BASE/$repo/resolve/master/$file"),
    )

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
            sizeBytes = 2008432640,
            recommended = true,
            backendBasis = "官方 GPU 特化变体（LiteRT 的 ML Drift GPU，README 含 Galaxy S26 GPU 基准）",
            mirrors = domesticMirrors("gemma-4-E2B-it-litert-lm", "gemma-4-E2B-it-gpu.litertlm"),
        ),
        ModelPreset(
            label = "Gemma 4 E2B · CPU",
            url = "$BASE/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            sizeText = "2.41 GB",
            ramText = "≥ 3.9 GB",
            requiredRamBytes = (3.9 * GB).toLong(),
            memBasis = BASIS_CPU,
            note = "同上的 CPU 版：兼容性最好，慢一些但不容易出错",
            sizeBytes = 2588147712,
            recommended = false,
            backendBasis = "CPU 特化变体；GPU 路径请选 GPU 变体（本变体未单独验证 GPU 图）",
            mirrors = domesticMirrors("gemma-4-E2B-it-litert-lm", "gemma-4-E2B-it.litertlm"),
        ),
        ModelPreset(
            label = "Gemma 4 E4B · GPU",
            url = "$BASE/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it-gpu.litertlm",
            sizeText = "2.77 GB",
            ramText = "≥ 5.2 GB",
            requiredRamBytes = (5.2 * GB).toLong(),
            memBasis = BASIS_GPU,
            note = "更大的模型：回答更好，需要 8GB 以上内存的手机",
            sizeBytes = 2969059328,
            recommended = false,
            backendBasis = "官方 GPU 特化变体（README 含 Galaxy S26 Ultra GPU 基准）",
            mirrors = domesticMirrors("gemma-4-E4B-it-litert-lm", "gemma-4-E4B-it-gpu.litertlm"),
        ),
        ModelPreset(
            label = "Qwen2.5 1.5B · q8",
            url = "$BASE/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            sizeText = "1.49 GB",
            ramText = "≥ 2.4 GB",
            requiredRamBytes = (2.4 * GB).toLong(),
            memBasis = BASIS_CPU,
            note = "中文好、体积小，6GB 内存的手机也能流畅跑",
            sizeBytes = 1597931520,
            // 注意：recommended 只允许两项（MiniCPM5 主推 + Gemma 4 E2B·GPU 备选），
            // 标多了就失去「替用户做决定」的意义。
            recommended = false,
            backendBasis = "官方 README 仅有桌面 GPU 基准（macOS Metal），无 Android GPU 验证 → 仅 CPU",
            mirrors = domesticMirrors(
                "Qwen2.5-1.5B-Instruct",
                "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            ),
        ),
        ModelPreset(
            label = "DeepSeek-R1 蒸馏 Qwen 1.5B",
            url = "$BASE/DeepSeek-R1-Distill-Qwen-1.5B/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
            sizeText = "1.71 GB",
            ramText = "≥ 2.7 GB",
            requiredRamBytes = (2.7 * GB).toLong(),
            memBasis = BASIS_CPU,
            note = "会先「想一想」再回答，适合体验思考过程",
            sizeBytes = 1833451520,
            recommended = false,
            backendBasis = "官方 README 无 Android GPU 验证 → 仅 CPU",
            mirrors = domesticMirrors(
                "DeepSeek-R1-Distill-Qwen-1.5B",
                "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
            ),
        ),
        ModelPreset(
            label = "MiniCPM5 2B · int4",
            url = "$BASE/MiniCPM5-2B/resolve/main/MiniCPM5-2B_int4.litertlm",
            sizeText = "1.45 GB",
            ramText = "≥ 2.4 GB",
            requiredRamBytes = (2.4 * GB).toLong(),
            memBasis = BASIS_CPU,
            note = "体积小、中文强，大多数手机都能跑",
            sizeBytes = 1553670064,
            recommended = true,
            backendBasis = "官方 README 明示 CPU+GPU 双兼容，但要求 litert-lm ≥ 0.16（本仓 0.11.0 未验证）→ 防闪退先禁 GPU；⚠️ 本模型同样存在运行时版本风险，加载失败请反馈",
            mirrors = domesticMirrors("MiniCPM5-2B", "MiniCPM5-2B_int4.litertlm"),
        ),
        ModelPreset(
            label = "Phi-4-mini · q8",
            url = "$BASE/Phi-4-mini-instruct/resolve/main/Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            sizeText = "3.64 GB",
            ramText = "≥ 5.6 GB",
            requiredRamBytes = (5.6 * GB).toLong(),
            memBasis = BASIS_CPU,
            note = "推理与代码能力强，但体积大，仅建议 12GB 内存机型",
            sizeBytes = 3910090752,
            recommended = false,
            backendBasis = "官方 README 的 GPU 行无显存数据（N/A），无 Android GPU 验证 → 仅 CPU",
            mirrors = domesticMirrors(
                "Phi-4-mini-instruct",
                "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            ),
        ),
        ModelPreset(
            label = "LFM2.5-VL 450M · 视觉",
            url = "$BASE/LFM2.5-VL-450M/resolve/main/LFM2.5-VL-450M_int8.litertlm",
            sizeText = "0.52 GB",
            ramText = "≥ 2.0 GB",
            requiredRamBytes = (2.0 * GB).toLong(),
            memBasis = BASIS_CPU,
            note = "能看懂图片：用来体验拍照问答，体积最小",
            sizeBytes = 563549568,
            recommended = false,
            backendBasis = "官方在 Pixel 8a（litert-lm 0.16.1）验证过 GPU profile；本仓 0.11.0 未验证 → 防闪退先禁 GPU",
            mirrors = domesticMirrors("LFM2.5-VL-450M", "LFM2.5-VL-450M_int8.litertlm"),
        ),
        // ── 视觉多模态批（2026-09-24 增补）：端侧 VL 帕累托前沿，500M/2B/1.6B/3B/8B 五档。
        // 收录判据与排除名单见文件头「视觉多模态预设的取舍」；引擎版本提醒也在那里。
        ModelPreset(
            label = "SmolVLM2 500M · 视觉",
            url = "$BASE/SmolVLM2-500M/resolve/main/SmolVLM2-500M.litertlm",
            sizeText = "0.34 GB",
            ramText = "≥ 2.0 GB",
            requiredRamBytes = (2.0 * GB).toLong(),
            memBasis = "公式 < 1G；按小模型固定基线 2.0G 取下限（同 450M-VL 锚点），图像 token 预算已含",
            note = "能看图的最小模型：老手机也能体验拍照问答",
            sizeBytes = 360822960,
            recommended = false,
            backendBasis = "官方在 Galaxy S26（litert-lm 0.15）验证 Android GPU 可生成；本仓 0.11.0 未验证 → 防闪退先禁 GPU。⚠️ 文本对话能力弱（360M 解码器，为图像输入设计）：纯文本对话建议 Qwen2.5-1.5B 或 DeepSeek-R1",
            mirrors = domesticMirrors("SmolVLM2-500M", "SmolVLM2-500M.litertlm"),
        ),
        ModelPreset(
            label = "Qwen2-VL 2B · 视觉",
            url = "$BASE/Qwen2-VL-2B/resolve/main/Qwen2-VL-2B.litertlm",
            sizeText = "1.66 GB",
            ramText = "≥ 2.7 GB",
            requiredRamBytes = (2.7 * GB).toLong(),
            memBasis = "W×1.05(CPU)+KV@4096(Qwen2 骨干 ≈0.11G)+O ×1.25 ≈2.6G，加视觉编码器激活取 2.7G",
            note = "阿里通义视觉模型：中文看图、截图问答与 OCR 强",
            sizeBytes = 1783424544,
            recommended = false,
            backendBasis = "官方在 Pixel 8a（视觉 GPU + 解码 CPU）与 Galaxy S26（0.15）验证；本仓 0.11.0 未验证 → 防闪退先禁 GPU",
            mirrors = domesticMirrors("Qwen2-VL-2B", "Qwen2-VL-2B.litertlm"),
        ),
        ModelPreset(
            label = "LFM2.5-VL 1.6B · 视觉",
            // 收 fixB 修复版：stock int4 的 vision graph 有「只见图片顶部 1/4」的
            // 上游 bug（LiteRT-LM#3246），fixB 重导出后整图可见，体积几乎不变。
            url = "$BASE/LFM2.5-VL-1.6B/resolve/main/LFM2.5-VL-1.6B_int4_fixB.litertlm",
            sizeText = "1.21 GB",
            ramText = "≥ 2.4 GB",
            requiredRamBytes = (2.4 * GB).toLong(),
            memBasis = "W×1.05+KV@4096(LFM2.5 混合架构 ≈0.04G)+O ×1.25 ≈1.9G；加视觉激活与图像 token 余量取 2.4G",
            note = "同体积看图能力最强之一：多语言视觉与 OCR 均衡（含视觉修复）",
            sizeBytes = 1298139472,
            recommended = false,
            backendBasis = "官方在 Pixel 8a（litert-lm 0.16.1）验证过 GPU profile；本仓 0.11.0 未验证 → 防闪退先禁 GPU",
            mirrors = domesticMirrors("LFM2.5-VL-1.6B", "LFM2.5-VL-1.6B_int4_fixB.litertlm"),
        ),
        ModelPreset(
            label = "LFM2.5-VL 3B · 视觉",
            // 同上：收 fixB 修复版而非 stock int4。
            url = "$BASE/LFM2.5-VL-3B/resolve/main/LFM2.5-VL-3B_int4_fixB.litertlm",
            sizeText = "2.19 GB",
            ramText = "≥ 3.5 GB",
            requiredRamBytes = (3.5 * GB).toLong(),
            memBasis = "W×1.05(CPU)+KV@4096(混合架构 ≈0.08G)+O ×1.25 ≈3.3G，加视觉激活取 3.5G",
            note = "小体积视觉旗舰：精细图像理解与文档 OCR，8GB 内存机型舒适运行（含视觉修复）",
            sizeBytes = 2352023888,
            recommended = false,
            backendBasis = "官方在 Pixel 8a（litert-lm 0.16.1）验证过 GPU profile；本仓 0.11.0 未验证 → 防闪退先禁 GPU",
            mirrors = domesticMirrors("LFM2.5-VL-3B", "LFM2.5-VL-3B_int4_fixB.litertlm"),
        ),
        ModelPreset(
            label = "MiniCPM-V 4 · 8B 视觉",
            url = "$BASE/MiniCPM-V-4/resolve/main/MiniCPM-V-4-int8.litertlm",
            sizeText = "3.93 GB",
            ramText = "≥ 6.5 GB",
            requiredRamBytes = (6.5 * GB).toLong(),
            memBasis = "W×1.05(CPU)+KV@4096(Qwen3-8B 骨干 ≈0.56G)+O ×1.25 ≈6.4G，含高分辨率图像 token 余量",
            note = "视觉能力天花板：8B 级多模态，仅建议 8GB 以上内存机型",
            sizeBytes = 4214021104,
            recommended = false,
            backendBasis = "官方 README 无 Android GPU 验证章节 → 仅 CPU",
            mirrors = domesticMirrors("MiniCPM-V-4", "MiniCPM-V-4-int8.litertlm"),
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

    /**
     * 按直链反查预设（下载卡片显示口径、内存/流量闸门、Gemma 授权标签共用）。
     *
     * 2026-09-24 起一条预设有多路镜像直链，主源与镜像**必须**都能反查到同一个预设：
     * 否则从镜像下载会被当成「自定义链接」，流量/内存闸门口径退化为无预设估算。
     * 用 [ModelPreset.ownsUrl] 而不是等值比较，就是这个原因。
     */
    fun findByUrl(url: String): ModelPreset? = all.firstOrNull { it.ownsUrl(url) }

    /**
     * 按**文件名**反查预设。
     *
     * 为什么需要它：加载前的内存闸门（`ModelsViewModel.onLoad`）手上只有一个
     * [com.rickeal.agent.core.model.ModelDescriptor]，**没有 URL**，拿不到 [ModelPreset.requiredRamBytes]。
     * 下载链路里文件名就是 URL 的末段（`onDownloadFromUrl` 的 `fileName` 派生），而 13 个预设的
     * 文件名只含 `[A-Za-z0-9._-]`，不会被 `ModelDownloader.sanitizeFileName` 改写，所以能稳定匹配。
     *
     * **查不到是正常情况，不是错误**：SAF 导入的用户自定义文件、以及因重名被 DownloadManager
     * 落成 `name-1.ext` 的下载，都匹配不上 —— 调用方必须自带兜底估算，不能把 null 当异常。
     */
    fun findByFileName(name: String): ModelPreset? =
        all.firstOrNull { it.url.substringAfterLast('/') == name }
}
