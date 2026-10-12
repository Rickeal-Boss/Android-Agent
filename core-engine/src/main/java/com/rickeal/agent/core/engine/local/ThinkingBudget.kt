package com.rickeal.agent.core.engine.local

/**
 * ⚠️ **改 `THINKING_CHARS_PER_TOKEN_CEIL`（C）三处同步单**（W65 D1 前半落档，禁单点演化）：
 * 改 C 的取值须同步以下两处，并跑 `ThinkingBudgetInvariantTest`（跨模块单源靠它钉住）：
 *  1. core-model `StreamRepetitionDetector.THINKING_CHAR_BUDGET`：**字面量 8192** =
 *     本模块 `thinkingCharBudgetFor(maxTokens = 1024, configured = 0)` 的取值
 *     （core-model 不可见 core-engine，只能写字面量，相等由不变式测试钉住）；
 *  2. core-engine `ThinkingBudgetInvariantTest`：「检测器默认值等于派生式在1024档的取值」
 *     与「字符守卫严格晚于 native 预算的实测字符当量」两条断言（后者内含实测上界 8.4）。
 * 改任一处必须同步另两处 —— 同族历史坑：两处口径各自演化 ⇒ 检测静默失效。
 */

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
 *   取大 ⇒ 退化为纯兜底（native 先优雅收尾）⇒ 必须**向上**取。
 *
 * **安全窗口**（W65 D1 前半）：`C ∈ (r_max, 2×r_max) = (8.4, 16.8)`，其中 r_max =
 * 真实文本 chars/token 实测上界 8.4：
 *  - **下界**（C > 8.4）：健康 native 下阈值 = budget×C > budget×r_max ≥ thinklen
 *    ⇒ 守卫数学不可达，不误触发（W62 病灶防线）；
 *  - **上界**（C < 16.8）：native 失效（bump 删符号 / .so 缺编）时思考可吃满 maxTokens，
 *    超阈需 r > C/2 = 8 —— 字符密集 profile（r≈8）恰好仍可触发 ⇒ 兜底能力不失；
 *    ⚠️ **轴档案条件化**（W65 M-1）：`configured > 0`（R1/MiniCPM5 = 1536，maxTokens=2048）时
 *    失效兜底阈 = C×budget/maxTokens ⇒ r > 16×1536/2048 = **12** —— 高于 W62 实测 r_max 8.4
 *    ⇒「判定③ = native 失效兜底」对三个轴档案**条件性收窄**（W62 同型样本在新预算档不再触发
 *    思考判据，由锚 #5 墙钟兜底承担）。半预算档（configured=0）不受影响，仍为 r > 8；
 *  - **C=16 贴上界**：故障不对称下的刻意选择 —— 取小 = 抢跑误伤，取大 = 纯兜底（可接受）。
 *
 * 复测目标（ narrowed，W65）：「确认 r_max 仍 ≤8.4」即可 —— 若是，则窗口 (8.4, 16.8) 内
 * 任意取值结构安全，无需开放式重新标定；只有当 r_max 被真机样本突破时才需重取 C
 * （同步单见本文件顶部注释块）。
 *
 * 🔴 **r 的测量方法（W66 E5 落档，先修尺子再采样）** —— 现有两个 r 样本（W62 的 8.4 /
 * W65 补丁臂的 2.0）**分母都是软的**（W62 的 token 数是推算、W65 的分母用的是预算而非
 * 实际 thinking token），故第三个样本前必须钉死方法。按可得性排序：
 *  1. **native usage 分通道：⛔ 不可用** —— 引擎 `completionTokens` 实为内容 **chunk 数**
 *     （见 `LiteRtLmEngine` 的 usage 计算），**无 thinking 通道 token 数**；
 *  2. **离线 tokenizer 计数（推荐，本仓采用）**：真机 journal 的 `message` 行经
 *     `ChatMessage.serializer()` 落盘、**含 `thinking` 字段** ⇒ 从 journal 抽出 thinking
 *     全文，用模型官方 tokenizer（`transformers` / `sentencepiece`）**离线精确计数**，
 *     得 `r = thinking 字符数 / thinking token 数`；
 *  3. **`TokenEstimator.estimate` 估算：仅旁证** —— 字符启发式（英文 3.2 字/token、
 *     CJK 1 字/token），**不得当硬证据**。
 * 判定口径：测得 `r_max ≤ 8.4` ⇒ C=16 冻结；`r_max > 8.4`（如中文 markup 密集推理）⇒
 * 窗口下界上移、C 需重估（同步单见本文件顶部注释块）。
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
