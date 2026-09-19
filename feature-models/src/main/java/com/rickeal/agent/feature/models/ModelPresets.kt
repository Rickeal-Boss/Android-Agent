package com.rickeal.agent.feature.models

/**
 * 内置模型预设：**只收录 Google 官方 `litert-community` 组织下真实存在的文件**，
 * 链接与体积均按 Hugging Face API 核对过（2026-09），不使用社区随机仓库，避免下到损坏/来路不明的权重。
 *
 * 格式说明（很重要）：
 *  - `.litertlm` = 新版 LiteRT-LM 运行时格式，本项目引擎直接支持。
 *  - `.task`    = 旧版 MediaPipe / LiteRT Task 格式，仅作兼容导入，推理行为可能受限。
 *
 * 体积提示：端侧 4B 级模型普遍在 1.4~3.7 GB，请在 Wi-Fi 下下载，并确保设备有足够可用内存。
 *
 * 关于 [ramText]（借鉴 Edge0 对每个模型档位量化披露峰值内存的做法）：
 * 峰值内存 ≈ 权重常驻(≈1× 体积) + KV cache 与运行时(按 4k 上下文经验取 ≈0.5× 体积)，
 * 因此按 **1.5 × 权重体积** 给出「建议可用内存」下界，属**估算值**，真机实测后应回填更准数字。
 */
data class ModelPreset(
    val label: String,
    val url: String,
    val sizeText: String,
    val ramText: String,
    val note: String,
)

object ModelPresets {

    private const val BASE = "https://huggingface.co/litert-community"

    val all: List<ModelPreset> = listOf(
        ModelPreset(
            label = "Gemma 4 E2B · GPU",
            url = "$BASE/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it-gpu.litertlm",
            sizeText = "1.87 GB",
            ramText = "≥ 2.8 GB",
            note = "端侧主力：GPU / NPU 后端首选，速度与质量平衡",
        ),
        ModelPreset(
            label = "Gemma 4 E2B · CPU",
            url = "$BASE/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            sizeText = "2.41 GB",
            ramText = "≥ 3.6 GB",
            note = "同模型的 CPU 版，兼容性最好、无 GPU 依赖",
        ),
        ModelPreset(
            label = "Gemma 4 E4B · GPU",
            url = "$BASE/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it-gpu.litertlm",
            sizeText = "2.77 GB",
            ramText = "≥ 4.2 GB",
            note = "更大有效参数量，建议 8GB+ 内存机型",
        ),
        ModelPreset(
            label = "Qwen2.5 1.5B · q8",
            url = "$BASE/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            sizeText = "1.49 GB",
            ramText = "≥ 2.3 GB",
            note = "中文表现好、体积小，低配机型友好",
        ),
        ModelPreset(
            label = "DeepSeek-R1 蒸馏 Qwen 1.5B",
            url = "$BASE/DeepSeek-R1-Distill-Qwen-1.5B/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
            sizeText = "1.71 GB",
            ramText = "≥ 2.6 GB",
            note = "蒸馏自带思维链，适合验证「思考模式」",
        ),
        ModelPreset(
            label = "MiniCPM5 2B · int4",
            url = "$BASE/MiniCPM5-2B/resolve/main/MiniCPM5-2B_int4.litertlm",
            sizeText = "1.45 GB",
            ramText = "≥ 2.2 GB",
            note = "小体积、高质量，中文任务表现佳",
        ),
        ModelPreset(
            label = "Phi-4-mini · q8",
            url = "$BASE/Phi-4-mini-instruct/resolve/main/Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            sizeText = "3.64 GB",
            ramText = "≥ 5.5 GB",
            note = "推理与代码能力强，体积偏大",
        ),
        ModelPreset(
            label = "LFM2.5-VL 450M · 视觉",
            url = "$BASE/LFM2.5-VL-450M/resolve/main/LFM2.5-VL-450M_int8.litertlm",
            sizeText = "0.52 GB",
            ramText = "≥ 1.0 GB",
            note = "视觉语言模型，用来验证图片输入（多模态）",
        ),
    )
}
