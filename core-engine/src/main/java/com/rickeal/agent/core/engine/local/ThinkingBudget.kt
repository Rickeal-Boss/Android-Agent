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
 * W67 B1 按离线重测结论重新标定 16 → 9）。
 *
 * 出处与推导（禁无据拍值）：
 * - **W62 原始上界 8.4 已被 W67 离线重测证伪（口径伪影）**：`README.md` W62 段「2048 字符
 *   ≈ 244 token」的 244 不可能是 thinking token 数 —— 引擎 `completionTokens` 实为**内容
 *   chunk 数**、且 thinking chunk 根本不计入（见 `LiteRtLmEngine` 的 usage 计算）；
 *   同样本用 gemma 官方 tokenizer 离线精确计数：
 *   2051 字符 = **469 token**（r = 4.37），2010 字符 = 495 token（r = 4.06）；换 Qwen
 *   tokenizer 复核 464 / 479 token（r ≈ 4.2–4.4）⇒ 两种 tokenizer 下 8.4 均不成立。
 * - **W67 全量实测 r_max = 5.06**（W62/W65/W66 三波 31 个 thinking 样本，禁挑样本：R1 中文
 *   思维链 1.35–2.26、gemma 英文思维链 3.0–5.06；≥500 字符主样本段 r_max = 4.53）。
 *   方法学自证：W66 E5 落档 4 值（318 / 268 / 330 / 1042）被同一脚本逐位复现 ⇒ 尺子可信；
 * - ×≈1.8 余量 ⇒ 取整 **9**；
 * - **故障不对称**：系数取小 ⇒ 字符守卫早于 native 抢跑（= W62 病灶：native 优雅路径被硬截）；
 *   取大 ⇒ 退化为纯兜底（native 先优雅收尾）⇒ 必须**向上**取。
 *
 * **安全窗口**（W65 D1 前半立、W67 B1 按实测重标定）：`C ∈ (r_max, 2×r_max) =
 * (5.06, 10.11)`，其中 r_max = W67 离线实测 chars/token 上界 5.06：
 *  - **下界**（C > 5.06）：健康 native 下阈值 = budget×C > budget×r_max ≥ thinklen
 *    ⇒ 守卫数学不可达，不误触发（W62 病灶防线）；
 *  - **上界**（C < 10.11）：native 失效（bump 删符号 / .so 缺编）时思考可吃满 maxTokens，
 *    超阈需 r > C/2 = 4.5 —— 实测英文思维链 profile（r ≈ 4.5–5.1）恰好仍可触发 ⇒ 兜底能力不失；
 *    ⚠️ **轴档案条件化**（W65 M-1）：`configured > 0`（R1/MiniCPM5 = 1536，maxTokens=2048）时
 *    失效兜底阈 = C×budget/maxTokens ⇒ r > 9×1536/2048 = **6.75** —— 高于 W67 实测 r_max 5.06
 *    ⇒「判定③ = native 失效兜底」对两个轴档案**条件性收窄**（同型样本在新预算档不触发思考
 *    判据，由锚 #5 墙钟 + N-2 warn（`thinkingOnlyEmptyDiagnostic`，全仓唯一生产落点）兜底
 *    承担）。半预算档（configured=0）不受影响，仍为 r > 4.5；
 *  - **C=9 的取法**：窗口上部带余量取值（= 1.78×r_max，与原设计 16/8.4 = 1.90× 同量级）。
 *    不取 C=10：贴上界 2×r_max = 10.11 仅 0.11 余量（取整即出界），且半预算兜底阈
 *    r > 5.0 ≈ 实测上界 ⇒ 兜底对已实测 profile 失效；不取 C≤8：对未实测文本的守卫余量
 *    低于原设计 Ratio，「向上取」纪律不允许。
 *
 * 🔴 **r 的测量方法（W66 E5 落档、W67 B1 完成首次全量重测）** —— 历史两个软样本
 * （W62 的 8.4 / W65 补丁臂的 2.0）已淘汰；现役口径：
 *  1. **native usage 分通道：⛔ 不可用** —— 引擎 `completionTokens` 实为内容 **chunk 数**
 *     （见 `LiteRtLmEngine` 的 usage 计算），**无 thinking 通道 token 数**；
 *  2. **离线 tokenizer 计数（现役硬证据）**：从 journal 抽 thinking 全文，用模型官方 tokenizer
 *     （gemma 侧 / Qwen 侧各一份，W66 E5 同款资产）**离线精确计数**，得
 *     `r = thinking 字符数 / thinking token 数`。W67 复测脚本 `_ci-tools/_w67_r_retest.py`
 *     （仓外 CI 工具，结论表见 W67 回报）：全量 31 样本，与 W66 E5 落档值逐位复现；
 *  3. **`TokenEstimator.estimate` 估算：仅旁证** —— 字符启发式（英文 3.2 字/token、
 *     CJK 1 字/token），**不得当硬证据**。
 * 判定口径（已执行）：W67 实测 r_max = 5.06 ⇒ 历史声称的 8.4 系口径伪影（证伪）⇒ 窗口
 * 下界 8.4 → 5.06、C 16 → 9（同步单见本文件顶部注释块）。
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
