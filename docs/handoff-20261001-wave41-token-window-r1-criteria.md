# Wave 41 交接：过程消息回灌 token 开窗 + R1 三层判据落档 + 验收面收编

> 写于 2026-10-01。基线 `ff963be`（Wave 40）→ 代码 tip `2c9febb`，本件为随行 docs 提交（docs-only 不触发 CI，属正常）。
> 触发：外部审查 **v6**（W40-1 无上限回灌 / W40-2 接缝播种）+ **复审8 莫斯线**（N0 R1 判据落档 / N1 legacy 复读 / N4 termsVersion / N7 门面失真）双报告收口。
> 团队 SOP 全程：沈思远裁决 → 柯码成（代码线）+ 文档线并行实施 → 严质衡全量审查（1 P0 + 1 P1 + 4 P2）→ 柯码成收口 → 严质衡增量复审通过 → 崔续程推送盯守 **CI 一次过零返工**。

---

## 一、CI 终态（SHA `2c9febb`）

| Run | Workflow | 结论 | Job |
|---|---|---|---|
| 36828943073 | build.yml | ✅ success | Unit tests / Lint (baseline gate) / Assemble Debug (JDK 21) |
| 36828943297 | release.yml | ✅ success | 签名 release + debug 双产物（apksigner 断言 + 16KB ELF 对齐断言随 job 通过） |

- **unit-tests 实证**：`:feature-chat:compileDebugUnitTestKotlin` → `:feature-chat:testDebugUnitTest` 真跑（`HistoryWithProcessTest` 14 例），非 NO-SOURCE。
- **lint artifact**：真实新问题 = **0**（baseline 吸收 4 条不涨，无 LintBaselineFixed）。
- **产物**：release APK **70.22MB**（仓库密钥签名）/ debug APK **39.35MB** / mapping **4.34MB**。

## 二、交付明细

### 1. W40-1（挂账 P2-1）historyWithProcess token 预算开窗（`36c6e3f` + `2c9febb` 收口）

- **问题（v6 原判，沈思远修正机理后更紧迫）**：C3 修复把全部 run 的 journal 过程消息重放进 history——无 token/条数上限，且每次发送全量重读所有 `.jsonl`。修正点：`AgentPolicy.compressContext` 默认 true，压缩会先于 KV 85% 警戒线动作；真正风险 = ①压缩器在 M(toolCalls)↔TOOL 配对约束下找不到安全切点时「原样发送」；②压缩真裁掉时**把 C3 刚回灌的工具上下文吐回去**。结论不变且更紧迫：不能靠压缩兜底，必须发送侧开窗。
- **方案（裁决：token 预算开窗，否决「近 N 轮」与组合方案）**：
  - 新增顶层纯函数 `processTokenBudget(contextLength, maxTokens, visible, inputTokens, compressThreshold)`（ChatViewModel.kt）：`base` **逐字镜像** AgentRunner 压缩预算算式（`((contextLength − maxTokens).coerceAtLeast(512) × compressThreshold).toInt()`），减 `estimate(visible)` 与 `estimate(userMessage)`（`inputTokens` 单独预减，审查 P1-1 收口项），钳 0；`compressThreshold` 取自真实下发进 AgentRequest 的 AgentPolicy 实例（两调用点构造上提），**零硬编码阈值**。
  - `historyWithProcess` 第 4 参 `budget`：runId 字典序**降序**（= 时间降序）逐 run 读取、**Long 累计**（estimate 返回 Int，Wave 5 教训）、累计超预算即 break（**旧 run 文件根本不读 = IO 治理本身**）、**只按 run 边界裁剪**（M(toolCalls) 与 TOOL 成对存活）、**最新 run 无条件保底**（预算 0 也保底）、`keptRuns.asReversed().flatten()` 恢复时间序；`mergeProcessIntoVisible` 去重口径**零改动**（开窗只发生在读取层）。
  - 裁剪 INFO：`过程消息开窗：保留 N/M 个 run（预算 X tok）`——**真机验收关键字，不得改措辞**；M = 本次已读到的非空 run 数（含触发 break 的那个），不是会话全部 run 数。
  - **硬不变量（P1-1 闭合后真实成立）**：visible + 回灌过程 + 本次输入 ≤ AgentRunner 压缩预算 ⇒ 引擎侧压缩在本轮开头不触发、C3 回灌的上下文不会被压缩器吐回；SYSTEM 段（buildSystemInstruction）不计入本预算，由引擎压缩器兜底（KDoc 如实标注）。
  - 测试 7→14 例：预算内回归锚（逐字节 `2304−10−U` 等值锚「等号不裁」）、截断丢最旧+保序+run 原子、最新 run 单独超预算保底、预算 0 保底、0 run/目录不存在、开窗+去重同生效、`.dismissed.jsonl` 读 + `.jsonl.archived`/`.jsonl.dismissed` 跳过、损坏行静默。阈值全部 TokenEstimator 实算非硬编码。
- **时序硬约束（已满足）**：v6 建议「P2-1 先于原生工具通道默认值翻转落地」——本波即落地；R1 判据通过前默认值不得翻转（见 Plan B）。

### 2. N0（P0 唯一离线遗留）R1 三层判据 + Plan B 落档（`f547222` + `2c9febb` 纠偏）

- 落点：`docs/10-device-acceptance.md` **§11.10**（真机窗口前置硬条件），§11.8 R1 移出挂账改指针，§11 标题改「（Wave 33 起）」。三波挂账正式销账。
- **层 1 schema 层**：关键字 `原生工具通道：已注册`（INFO，含首个 schema JSON）；反面 `原生工具通道：`（WARN 证伪）、`角色通道播种失败`（WARN）。
- **层 2 模型能力层**：关键字 `原生工具通道：模型下发 N 个 tool_call`（INFO，LiteRtLmEngine:1059-1062）＋ 审批卡弹出；UI 成功信号 = 小字区**无**「原生工具通道未生效，已退回文本协议」提示（该提示只在失败时出现，ChatScreen:697-699）。失败形态 = 模型输出「我应该调用工具」类**文本**而非调用 ⇒ 层 2 不通过。
  - ⚠️ 审查 P0-1 纠偏记录：初稿写的「诊断卡 `nativeToolChannel=true`」**在仓内任何成功路径都不存在**（BottleneckReport 无该字段；`nativeToolChannel=` 字面只在 AgentRunner 判据不一致 WARN 里）——真机按它取证必然扑空 → 误判层 2 失败 → 误触 Plan B 弃用原生通道。**验收关键字必须逐字 grep 源码，这条纪律再次实证。**
- **层 3 探针假阳性层**：关键字 `原生工具通道判据不一致`（WARN，AgentRunner:809-817）——哑工具探针（空 initialMessages）通过但真实会话失败即假阳性。
- **A1 logcat 三通道取证并入**：① adb logcat（debug 包主通道，`adb logcat -s LiquidAgentDiag:V`）② 诊断页「复制全部」③ `last_errors.log`（仅 ERROR、仅崩溃幸存场景）——三通道同一窗口一次验清。
- **Plan B**：触发 = 层 1/层 3 失败（转换件不支持）或层 2 失败（模型能力不足）；降级 = 原生通道默认值**不翻转**，文本协议继续作为交付路径（W40-1 已保证文本协议下长会话 token 可控）；登记「该转换件/模型组合不支持原生通道」；层 2 失败另评估提示词协议强化是否立项。

### 3. 验收面收编（§11.9 五行，四元组格式）

覆盖层右缘拖出（三键+手势各一次）/ 复现循环思考抓 `roleChannelActive` / C3 双态判别（三项为 Wave 40 handoff §五.1 首次入表）＋ **W40-2 接缝取证**（原生通道开 → 重开 ≥2 历史工具 run 长会话 → 发消息；若现证伪 WARN = 接缝连续 MODEL 被模板拒绝实锤，「过程消息按轮次插回原位」排序策略提前立项）＋ **W41 开窗回归**（多 run 长会话连发多次，开窗 INFO 出现且 estPrompt 不无界增长）。

### 4. N7 修正版（`bc4e7d1`）

- 复审8 断言「main README 冻结快照声明指向 harness」**经源码核验不成立**（main README 无该声明，全文件仅 :40 提及外部项目名 deepseek-harness）。
- 真实失真点 = harness-improve 自己的 README：M9 条目补「`harness` 分支自 2026-09-26 起冻结，活跃开发在 `harness-improve`」；功能 tip 行更新为 `2c9febb`（本件）。

## 三、审查记录（严质衡）

- 全量审查结论「需修改」：**P0-1**（§11.10 层 2 关键字失真，见上）＋ **P1-1**（processTokenBudget 只减 visible 未减本次输入——宣称的硬不变量与代码实建不符，实际 engineHistory 总量可达 base + U；预算吃满时 AgentRunner 轮头每轮必触发压缩全量 re-prefill，开窗收益被吐回）＋ P2×4。
- 收口 `2c9febb`：P1-1 补 `inputTokens` 预减（KDoc/注释/测试锚三处同步）；P0-1 层 2 关键字纠偏；P2-1 形参 `processTokenBudget`→`budget` 消同名顶层函数遮蔽；P2-2 KDoc 补日志分母 M 语义。P2-3（预算 0 + 第二 run 0 tok 边界会被保留，无实害）/ P2-4（AgentPolicy 12 行构造块 onSend/onSendFrom 双写，下轮可提取私有函数）备忘。
- 增量复审通过，全部销账。实现者自查另抓到一处改名漏网（`:1680 > processTokenBudget` 会解析成顶层函数引用 = 编译错）——本地无 JDK 时「形参改名后全仓 grep 旧名」必须执行。
- **复盘两条（可复用方法论）**：①「硬不变量」必须独立数学复算——机制面（filterNot 口径/拼接顺序/计算时机）全对 ≠ 不等式成立；②验收关键字必须逐字 grep 源码——`nativeToolChannel` 是真实存在的字段名，正因为它真实才更容易被写成看似可信的假关键字。

## 四、挂账（下波）

| 项 | 定级 | 说明 |
|---|---|---|
| **N1 legacy 自我强化复读** | P1 待真机取证 | 条件触发挂账：真机取证确认 `roleChannelActive=false` 且复读复现后实施。改动点 = LiteRtLmEngine.kt buildContents MODEL 分支（:1314-1317）一处；前缀候选 `[你此前的回复] `；取证判据（诊断卡/日志）不受未来修复影响。本轮不实施理由：取证先行纪律 + 前缀提示词污染面与文案校准成本被「一处字符串」低估 |
| **W40-2 接缝排序策略** | 条件立项 | 验收项已入 §11.9；真机复现「原生工具通道：证伪」于重开长会话才提前 |
| **P2-2 onRecover 与 C3 口径统一** | 挂账 | 恢复路径是高危面，先真机对比观测再统一；统一时直接走带预算签名，预算口径自动继承 |
| **N4 termsVersion** | 维持挂账 | Wave 39 裁决不变：需产品/法务决策（老用户 true 算 v1 还是未同意）；时序硬约束（先版本化后换法务文本）已固化 SettingsRepository 注释 |
| P2-4 AgentPolicy 构造提取 | 备忘 | onSend/onSendFrom 双写下轮收敛 |
| 历史挂账 | 不变 | 法务 TODO×4、G4 ACTION_VIEW 三选一、0 tags/releases、本地 gradle 基建（Wave 30~41 的 CI 往返全部属本地一次 compileDebugKotlin 可拦截类别，本波零红不改判断） |

## 五、真机验收入口

`docs/10-device-acceptance.md` §11.0（取证前提）→ §11.9（Wave 40/41 五行）→ §11.10（R1 三层判据 + Plan B + 三通道）。v6 复审判断维持：**分支处于「万事俱备，只欠真机」状态，任何新离线代码工作低于上真机**。

## 六、团队与流程

沈思远任务书（逐条源码核验：W40-1 采纳+机理修正 / W40-2 采纳 / N0 采纳 / N1 条件挂账 / N4 维持 / N7 定位修正）→ 柯码成（代码线：ChatViewModel + 测试 14 例）与文档线并行（文件面互斥）→ 严质衡全量（1P0+1P1+4P2）→ 柯码成收口 → 严质衡增量复审通过 → 崔续程推送 + 盯守。**CI 一次过零返工**（对比 Wave 30~40 累计 10+ 轮红）。
