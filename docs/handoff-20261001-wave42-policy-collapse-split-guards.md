# Wave 42 交接：AgentPolicy 构造收敛 + ChatViewModel 拆分 + 守卫网 17/18 项

> 写于 2026-10-01。基线 `e6c8a87`（Wave 41）→ 代码 tip `bb149f5`，本件为随行 docs 提交（docs-only 不触发 CI，属正常）。
> 触发：Wave 41 复审 P2 备忘（**P2-4 AgentPolicy 三处构造双写**）+ 主理人 Wave 42 任务书（J3 拆分 + 守卫网扩容 17/18 项）。
> 团队 SOP 全程：沈思远裁决 → 柯码成 3 commit（8f112ac / a414e58 / bb149f5）→ 严质衡全量审查通过（0 P0/P1 + 3 P2）→ 崔续程推送盯守 **CI 一次过零返工**。

---

## 一、CI 终态（SHA `bb149f5`）

| Run | Workflow | 结论 | 耗时 |
|---|---|---|---|
| 36848060374 | build.yml | ✅ success | 3m41s |
| 36848060401 | release.yml | ✅ success | 7m16s |

- **产物**：release APK **70.22MB**（仓库密钥签名，apksigner v3 验签断言过）/ debug APK **39.35MB** / mapping **4.34MB** —— 三项与 Wave 41 持平（零依赖、零新代码路径的机械波，体积即回归锚）；**16KB ELF 对齐断言**随 release job 通过。
- **lint artifact**：真实新问题 = **0**（baseline 冻结 4 条不涨，无 LintBaselineFixed）。
- **arch-guard**：**18 项全 OK**（本波新增第 17 / 18 项，见交付明细③）；**selftest PASS=30 FAIL=0**（原 25 + 新 5）。

## 二、交付明细（3 commit，全部机械 / 低风险 / 零行为变更 / 零新依赖 / 零新测试用例）

### 1. P2-4（Wave 41 复审备忘，销账）三处 AgentPolicy 构造收敛为 `chatAgentPolicy` 单点（`8f112ac`）

- onRecover / onSend / onSendFrom 三处**逐行相同**的 AgentPolicy 构造块（`maxRounds.coerceAtLeast(1)` + `enableTools` 判 `agentSamplingOverride`）提取为顶层 private 函数 `chatAgentPolicy(config: InferenceConfig)`；Wave 19 P1-1 采样折衷注释收敛为一份权威 KDoc 挂在函数上，不再三处复制。`SamplingParams(temperature = 0.4f, topK = 20)` 字面量全文件恰 1 处，改采样口径不再需要三处同步。
- 提取前先逐行比对确认相同（仅缩进与 onRecover 在 AgentRequest 具名参数内联两处非语义差异）；onSend / onSendFrom 调用点「构造上提原因（compressThreshold 先于 engineHistory）」的**时序注释原样保留**（它说明的是调用点时序，不属于构造体）；onRecover 的 history 组装口径不动。
- **申报偏差**：任务书自查判据 `grep -c "AgentPolicy("` 应为 1 **不可字面达成**——三个调用点 `chatAgentPolicy(` 含子串 "AgentPolicy(" 会计为 4。改用词边界判据 `grep -nE "(^|[^a-zA-Z])AgentPolicy\("` = 1（真实构造点恰 1 处，即 helper 函数体），已如实写入 commit message。

### 2. J3 拆分：顶层纯函数组迁出 ChatViewModel（`a414e58`）

- `mergeProcessIntoVisible` / `processTokenBudget` / `historyWithProcess` 三函数（含全部 KDoc，原 :1524-1694 约 171 行）**逐字搬移**至同包新文件 `ProcessHistory.kt`（180 行）——KDoc 里 `[ChatViewModel.xxx]` 跨符号链接未改写（同包可见）、`processTokenBudget` 公式未动、「过程消息开窗：保留 N/M 个 run（预算 X tok）」INFO **真机验收关键字措辞未动**；搬移段经 diff 确认逐字一致。
- `applyTerminalEvent` **不搬**（ChatUiState 扩展映射，与 ChatUiStateTest 同域，留原文件）；ChatViewModel.kt 现有 import 逐一验证仍被类体使用，零删除；两个测试文件（HistoryWithProcessTest / ChatUiStateTest）同包零改动。
- 全仓 grep `historyWithProcess|mergeProcessIntoVisible|processTokenBudget` 引用面复核：仅剩 ProcessHistory.kt 定义、ChatViewModel 调用点（onSend :966/:970、onSendFrom :1107/:1111）与 HistoryWithProcessTest，**无第三处、无旧名残留**（旧名残留是 Wave 41 实锤的编译事故源，此处显式冻结引用面）。
- 行数：ChatViewModel.kt **1682 → 1510 行**，为第 18 项守卫阈值（≤1600）腾出余量。

### 3. 守卫网扩容：第 17 / 18 项 + selftest 5 case（`bb149f5`）

- **第 17 项：ViewModel 直构造点全仓 ≤ 1**。正规路径必须走 viewModelFactory（ViewModelStore 接管 clear/cancel 生命周期）；唯一白名单 = `LiquidAgentApp.kt:378` 的 `SandboxFilesViewModel(container)`（工作区覆盖层「首次打开才创建 + 旋转即关」刻意取舍）。判据三层过滤（class 声明行 / 单行 viewModelFactory / EXCLUDE_COMMENT），按真实树实测调通：恰命中白名单 1 处，8 处 factory 构造全部单行不误红。**已知局限（已入守卫注释）**：过滤是行级的——将来格式化把 viewModelFactory 块拆成多行会缺关键字误红，届时改回单行或升级跨行判据。LiquidAgentApp.kt 同 commit 补一行注释（不改执行逻辑）：该直构造点唯一性由第 17 项冻结，迁移正规路径 = 行为变更（重开覆盖层重扫语义 /「旋转即关」取舍），触发条件见挂账台账 J4。
- **第 18 项：ChatViewModel.kt 总行数 ≤ 1600**（wc -l 单文件断言；1510 ≤ 1550 故冻结值取 1600，未触发「向上取整」分支）。守卫注释写明：**触顶不是改数字，是启动「onSend / onSendFrom 合并」候选评审**——两函数的 journal 回灌 + engineHistory 组装已高度趋同，评审通过后合并/拆分再按实际行数下调本值。
- **selftest 新增 5 case**（25 → 30）：case15 红面（注入 2 处**真代码行**直构造，非注释——selftest 铁律）/ case15b 真命中断言 / case15c 绿面（单行 factory 形态 + 0 直构造不误红）/ case16 红面（注入 1705 行超阈文件）/ case16b 真命中断言。scaffold 补最小 ChatViewModel.kt 骨架（第 18 项文件存在性依赖，其类声明行被第 17 条 class 过滤器排除，干净树对 17/18 双绿）。
- **红面三段式反假绿范式**（本波 5 个新 case 统一采用，与既有 case12b / case13b / case14b 同范式）：先排除「守卫命令自身执行失败」（该行也含守卫名，会让 `assert_red` 假绿——case12 首版踩过）→ 再要求输出含**真命中计数行**（如「处 ViewModel 直构造点」「超 1600 行上限」，证明 fixture 确被计到）→ else 兜底 FAIL。**建议沉淀为 selftest 编写规范**：凡计数型守卫的 selftest 红面，三段式缺一不可。

## 三、审查记录（严质衡）

- 全量审查**通过**：**0 P0 / 0 P1 + 3 P2**（对比 Wave 41 的 1 P0 + 1 P1 + 4 P2）。
- **P2-1（J4 台账悬空引用）**：由本 commit（docs-only）收口——挂账台账 J4 条目随本件落 README，LiquidAgentApp.kt 注释里的「见挂账台账」不再悬空。
- **P2-2（备忘留档）**：第 17 项判据对 `src/test` 源集与 `hiltViewModel(...)` 形态敏感——当前全仓无此两种形态（无测试内直构造、零 Hilt），判据安全；**将来引入 Compose-Hilt 时必须复评**（`hiltViewModel()` 内部的工厂形态是否被误判为直构造，需实测）。
- **P2-3（编译验证）**：本地无 JDK 无法编译验证，由 **CI 双绿实证收口**（Build 36848060374 含 unit tests + lint + assembleDebug 全过）。

## 四、挂账（下波）

| 项 | 定级 | 说明 |
|---|---|---|
| **J4 SandboxFilesViewModel 直构造白名单** | 行为变更项，README 台账新增 | 唯一性已由第 17 项冻结；迁移正规 viewModel 路径的触发条件与实施约束见 README 挂账台账（重扫语义必须处理 + 真机验证） |
| **P2-2 第 17 项判据复评** | 条件备忘 | 引入 Compose-Hilt 时复评（hiltViewModel / src/test 形态），见上文审查记录 |
| N1 legacy 自我强化复读 | P1 待真机取证 | Wave 41 裁决不变 |
| W40-2 接缝排序策略 | 条件立项 | 验收项已入 §11.9，真机复现才提前 |
| P2-2 onRecover 与 C3 口径统一 | 挂账 | Wave 41 裁决不变：先真机对比观测再统一 |
| N4 termsVersion | 维持挂账 | 需产品/法务决策，时序硬约束已固化 |
| 历史挂账 | 不变 | 法务 TODO×4、G4 ACTION_VIEW 三选一、0 tags/releases、本地 gradle 基建 |

## 五、真机验收入口

`docs/10-device-acceptance.md` §11.0 → §11.9 → §11.10（R1 三层判据 + Plan B + 三通道）。本波**零行为变更**（构造收敛 / 搬移 / 守卫三件套都不触执行路径），**不新增验收项**；Wave 41 开窗回归（§11.9 末行）判据不变。v6 复审判断维持：分支处于「万事俱备，只欠真机」状态。

## 六、团队与流程

沈思远 Wave 40 裁决 → Wave 41 交接定本波任务书（主理人批准）→ 柯码成 3 commit（8f112ac 收敛 / a414e58 拆分 / bb149f5 守卫，每 commit 独立可 CI 绿、身份 Rickeal-Boss、只 add 具体文件）→ 严质衡全量审查通过（0P0/0P1+3P2）→ 崔续程推送 + 盯守。**CI 一次过零返工**（连续第二波：Wave 41、42）。任务书与源码冲突三处均为判据表述修正（词边界 grep / KDoc 字面量措辞 / selftest case 数 5），无扩大改动面。
