package com.rickeal.agent.core.engine.local

/**
 * 从配置派生 thinking 通道的**生效**预算（上游 litertlm 0.17.1
 * `ThinkingConfig.thinkingTokenBudget`，native 硬约束）。
 *
 * 提取成 `internal` 纯函数（Wave 47 项1）的理由：本仓无本地 JDK 预演 native 路径，而这条
 * 派生公式是「thinking 独立预算」唯一可离线验证的一环 —— 生产路径
 * [LiteRtLmEngine.generateStream] 真实调用本函数，故测试覆盖的是**真实逻辑而非镜像**
 * （与本仓既有范式 `applyTerminalEvent` / `processTokenBudget` 一致）。
 *
 * 语义（与 `InferenceConfig.thinkingTokenBudget` 的 KDoc 逐字同源）：
 * - `configured > 0` ⇒ 采信显式值；
 * - `configured == 0` ⇒ 自动 = [maxTokens] 的一半（为正文保留等量余量），至少 1；
 * - 结果恒钳在 `1..(maxTokens - 1)`：预算**计入** `maxOutputToken`，必须给正文留出余量。
 *
 * 退化边界（`maxTokens <= 1`）：配置已被 `InferenceConfig.coerce()` 挡在 `≥64`，此处仅为
 * 穷尽防御 —— 上界 `coerceAtLeast(1)` 保证返回值恒 `≥1`，不产生非法（0 / 负）预算。
 *
 * @param configured `InferenceConfig.thinkingTokenBudget`（0 = 自动）。
 * @param maxTokens 逐消息输出上限（`maxOutputToken`，thinking + 正文共享）。
 */
internal fun resolveThinkingBudget(configured: Int, maxTokens: Int): Int {
    val budget = if (configured > 0) configured else (maxTokens / 2).coerceAtLeast(1)
    return budget.coerceIn(1, (maxTokens - 1).coerceAtLeast(1))
}
