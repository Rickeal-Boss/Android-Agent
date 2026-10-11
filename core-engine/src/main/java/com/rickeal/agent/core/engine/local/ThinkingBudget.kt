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

/**
 * thinking 字符预算相对 native token 预算的**字符当量上界系数**（chars/token，W64 K-1 治本）。
 *
 * 出处与推导（禁无据拍值）：
 * - **实测上界 8.4 chars/token**（W62 真机事故：2048 字符 ≈ 244 token，见 `README.md` W62 段）；
 * - ×≈1.9 余量 ⇒ 取整 **16**；
 * - **故障不对称**：系数取小 ⇒ 字符守卫早于 native 抢跑（= W62 病灶：native 优雅路径被硬截）；
 *   取大 ⇒ 退化为纯兜底（native 先优雅收尾）⇒ 必须**向上**取；
 * - ⚠️ 冻结前须真机 ≥2 样本复测（英文 + 中文各一），把实测值记于此。
 */
internal const val THINKING_CHARS_PER_TOKEN_CEIL = 16

/**
 * native 思考 token 预算的**字符当量上界**（守卫用，W64 K-1 治本）。
 *
 * 语义：把 [resolveThinkingBudget] 派生的 native 思考 **token** 预算，按字符当量上界系数
 * [THINKING_CHARS_PER_TOKEN_CEIL] 折算成**字符**预算，供 `StreamRepetitionDetector` 的
 * 判定③（thinking 流铺陈预算）使用 —— 使字符守卫随 maxTokens 滑条 / 预设同步伸缩，不再是
 * 脱离 native 预算的固定常量（W62 病灶的结构性根因，详见检测器 `THINKING_CHAR_BUDGET` KDoc）。
 *
 * **不变式**：本值恒 > native 预算 token 数 × 实测上界(8.4) —— 即守卫必须**晚于** native
 * 优雅路径（预算耗尽 ⇒ 强制吐思考结束符 ⇒ 转回正文生成）。选小会重现 W62 病灶（守卫早于
 * native 抢跑），选大则退化为纯兜底 ⇒ 系数必须向上取。该不变式由 core-engine 的
 * `ThinkingBudgetInvariantTest` 参数化钉住（core-engine 是唯一同时可见本函数与 core-model
 * 检测器常量的模块）。
 *
 * ⚠️ core-model **不可见**本模块（零引擎依赖）⇒ `StreamRepetitionDetector.THINKING_CHAR_BUDGET`
 * 的默认值只能取本式在 maxTokens=1024 档的**字面值**，二者相等由上述不变式单测钉住
 * （单源靠测试保证，勿让两处各自演化）。
 *
 * @param maxTokens 逐消息输出上限（`InferenceConfig.maxTokens`，thinking + 正文共享）。
 * @param configuredThinkingBudget `InferenceConfig.thinkingTokenBudget`（0 = 自动半预算）。
 */
fun thinkingCharBudgetFor(maxTokens: Int, configuredThinkingBudget: Int = 0): Int =
    resolveThinkingBudget(configuredThinkingBudget, maxTokens) * THINKING_CHARS_PER_TOKEN_CEIL
