package com.rickeal.agent.core.model

/**
 * GPU 后端白名单（2026-09-26）。
 *
 * 为什么放在 core-model 而不是 feature-models 的预设表：GPU 路径不支持时是
 * **native 崩溃**（用户只看到闪退），Kotlin 层 catch 不住 —— 除了模型库 UI 的
 * 选择拦截，**对话链路**（AgentRunner → EngineEnvironment.loadConfig → engine.load）
 * 也必须能拿到同一份判定做加载前兜底；core-model 是所有层可见的最底层，
 * 放这里才能让两条路径共享同一数据源，避免 UI 与引擎各写一份而漂移。
 *
 * 判据（从严）：官方 README 有**明确 Android GPU 证据**（GPU 特化变体 /
 * Galaxy S26·Pixel 8a 实测章节）才收录；桌面 GPU 基准（macOS Metal）不算。
 * 证据链与用户可见文案在 feature-models 的 `ModelPreset.backendBasis`（逐仓
 * 核实于 2026-09-26，README 快照存档于工作区 _readme_*.md）。
 *
 * **匹配口径 = 文件名精确匹配**（与 ModelPresets.findByFileName 同一约定）。
 * SAF 导入/重名落盘（name-1.ext）的文件不在名单 → 一律 CPU（从严防闪退）。
 */
object ModelGpuSupport {

    /** 已验证支持 GPU 后端的模型文件名（litert-community 官方转换件）。 */
    val gpuVerifiedFileNames: Set<String> = setOf(
        // 官方 GPU 特化变体（README 含 Galaxy S26 / S26 Ultra GPU 基准章节）
        "gemma-4-E2B-it-gpu.litertlm",
        "gemma-4-E4B-it-gpu.litertlm",
    )

    /** 该文件名是否放行 GPU 后端。 */
    fun isGpuVerified(fileName: String?): Boolean =
        fileName != null && fileName in gpuVerifiedFileNames
}
