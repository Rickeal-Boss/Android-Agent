package com.rickeal.agent.core.engine.local

/**
 * ⚠️ **改 `THINKING_CHARS_PER_TOKEN_CEIL`（C）三处同步单**（W65 D1 前半落档，禁单点演化）：
 * 改 C 的取值须同步以下两处，并跑 `ThinkingBudgetInvariantTest`（跨模块单源靠它钉住）：
 *  1. core-model `StreamRepetitionDetector.THINKING_CHAR_BUDGET`：**字面量 4608** =
 *     本模块 `thinkingCharBudgetFor(maxTokens = 1024, configured = 0)` 的取值
 *     （core-model 不可见 core-engine，只能写字面量，相等由不变式测试钉住）；
 *  2. core-engine `ThinkingBudgetInvariantTest`：「检测器默认值等于派生式在1024档的取值」
 *     与「字符守卫严格晚于 native 预算的实测字符当量」两条断言（后者内含实测上界 5.06）。
 * 改任一处必须同步另两处 —— 同族历史坑：两处口径各自演化 ⇒ 检测静默失效。
 *
 * **W67 B1 重标定记录**（C 16 → 9）：W62 的 8.4 被离线重测试证伪（口径伪影，见
 * [THINKING_CHARS_PER_TOKEN_CEIL] KDoc），W67 全量实测 r_max = 5.06 ⇒ 安全窗口
 * (8.4, 16.8) → (5.06, 10.11)、C 16 → 9、THINKING_CHAR_BUDGET 8192 → 4608，
 * 四处（本常量 / core-model 字面量 / 双不变式测试 / 轴档案推导段 KDoc）同批同步。
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
 * thinking 字符预算相对 native token 预算的**字符当量上界系数**（chars/token，W64 K-1 治本；
 * W67 B1 按离线重测结论重新标定 16 → 9）。**结论：C = 9**（安全窗口 `(5.06, 10.11)`，
 * r_max = 5.06 = W67 离线实测 chars/token 上界）。**文字版复现规程**（不依赖仓外
 * `_w67_r_retest.py` 存活）：journal `message` 行抽 thinking 全文 → 官方 tokenizer（gemma /
 * Qwen 各一份）离线计数 → `r = 字符/token`，全量取上界；完整推导（8.4 证伪 / 窗口论证 /
 * 取整纪律）= `docs/12-litertlm-0171-anchors.md` K-1「W73 K4 移入」小节（原文照搬）。
 * ⚠️ 改 C 的三处同步单见本文件顶部纪律块（禁搬）。
 */
internal const val THINKING_CHARS_PER_TOKEN_CEIL = 9

/**
 * native 思考 token 预算的**字符当量上界**（守卫用，W64 K-1 治本）。
 *
 * 语义：把 [resolveThinkingBudget] 派生的 native 思考 **token** 预算，按字符当量上界系数
 * [THINKING_CHARS_PER_TOKEN_CEIL] 折算成**字符**预算，供 `StreamRepetitionDetector` 的
 * 判定③（thinking 流铺陈预算）使用 —— 使字符守卫随 maxTokens 滑条 / 预设同步伸缩，不再是
 * 脱离 native 预算的固定常量（W62 病灶的结构性根因，详见检测器 `THINKING_CHAR_BUDGET` KDoc）。
 *
 * **不变式**：本值恒 > native 预算 token 数 × 实测上界(5.06) —— 即守卫必须**晚于** native
 * 优雅路径（预算耗尽 ⇒ 强制吐思考结束符 ⇒ 转回正文生成）。选小会重现 W62 病灶（守卫早于
 * native 抢跑），选大则退化为纯兜底 ⇒ 系数必须向上取。该不变式由 core-engine 的
 * `ThinkingBudgetInvariantTest` 参数化钉住（core-engine 是唯一同时可见本函数与 core-model
 * 检测器常量的模块）。
 *
 * ⚠️ **不变式的适用前提 = 预算已下发**（W67 B1 F1 裁决回填）：上方「晚于 native」论证以
 * `thinkingOn = true`（ThinkingConfig 已随会话下发）为前提。`ThinkingMode.OFF` 档下引擎
 * **恒不下发** ThinkingConfig（`LiteRtLmEngine` generateStream：`thinkingOn == false` ⇒
 * 构造 null）⇒ 无 native 预算约束；对**服从** `enable_thinking=false` 的模型（显式通道
 * 关闭）thinking 根本不产生，两条路径都不受影响；但对**不服从** `enable_thinking=false`
 * 的显式通道模型（DeepSeek-R1 类，W66 §3.2 探针实证：OFF 档仍输出思考），本派生预算与
 * 字符守卫双双缺席 ⇒ 该第三态为 **best-effort 申报**（W67 F1 选项 B，零行为改动）：无
 * native 预算约束、字符守卫休眠、轴档案 `minThinkingTokenBudget` 虽仍会把 InferenceConfig
 * 的 configured 抬到 1536（`ModelSamplingProfiles.appliedTo` 不分辨思考档），但引擎 OFF
 * 档恒不下发 ThinkingConfig ⇒ 到不了模型 ⇒ **唯一探测面 = N-2 warn**
 * （`thinkingOnlyEmptyDiagnostic`，`AgentRunner` 空答分支唯一落点）+ 锚 #5 墙钟。改为强
 * 抑制（OFF 档补发 budget=1）须先过真机双臂判据，未过之前不得宣称已覆盖该态。
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
