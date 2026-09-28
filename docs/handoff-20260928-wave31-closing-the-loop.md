# Wave 31 交接：接线收口 + 把约束变成守卫

> 写于 2026-09-28。基线 `82aba24`（Wave 30 交接）→ 推送 `00fb46e`。
> 外部输入：四份第三方体检/复审报告（基线 `82aba24`）。
> 团队：沈思远（Wave 31 蓝图）→ 柯码成 ×3（四条并行开发流）→ 严质衡（独立质量审查）→ 主理人（裁决 / 独立复核 / 集成提交 / 推 CI）。
> 波次主题：**「最后一公里」收口**。第三轮复审的正确诊断是——本项目的缺陷不集中在「写不出」，而集中在「写好了、差最后一步没接」。

---

## 0. 本波最重要的判断：报告只作线索，一律回源码复核

四份报告我逐条回源码核验过，**结论多数属实，但有两处偏差、一处连带缺陷是报告没抓到的**：

| 报告结论 | 复核结果 |
|---|---|
| 诊断卡漏接 `AgentRunner:1561`/`:1712` | ✅ 属实 |
| `budgetTail` 用 `trips.any{}` ⇒ 轮次耗尽被误报为「token 用满」 | ✅ 属实 |
| `RunTokenLedger` 全链生产恒 null | ✅ 属实 |
| 墙钟上界被低估（生成/reload 无超时 + 子 run 独立预算） | ✅ 属实 |
| 热 CRITICAL 释放被 isBusy 跳过后无补释放 | ✅ 属实 |
| `executeBodyUnchecked` **517 行** | ❌ **实测 631 行**（`:357`→`:988`） |
| arch-guard **12 项** | ❌ 实测 **11 项**（第 12 项是本波加的） |
| `firstHard()` 可作为「终止者」口径 | ❌ **不成立**（见下 §1.2） |

---

## 1. Wave 31 本体：六项改动（流 1）

### 1.1 诊断卡补接两条最高频失败路径（P0）

`AgentRunner` 共 7 个终态出口，此前 `:1561`（StreamLoop 轮内重复循环超限）与 `:1712`（EmptyOutput 连续空输出超限）两处 `emit(Failed)` **没有传 `report=`** ⇒ 端侧 4B 最常遇到的失败恰好是唯一看不到诊断的失败，且那两处的 `state.breaker.trip(...)` 写进了随协程消亡的 `RunState` = **死写**。两处补 `report = buildBottleneckReportFor(state, journal, registeredToolNames)`，`message` 文案一字未改。

### 1.2 `budgetTail` 归因修正 + `terminator()`（P0，含报告未抓到的连带缺陷）

归因从「trips 里有没有」改为「**谁终止了 run**」。

⚠️ **复审建议的 `firstHard()` 也是错的** —— 3 分钟 SOFT 墙钟留痕用的 kind 是 `WallClockBudget`，而该 kind 的 `severity` 恒为 **HARD**（`BreakerKind` KDoc 明写「档位语义由 record 点控制」）⇒ `firstHard()` 可能返回一条**从未终止 run** 的 trip。故新增：

```kotlin
fun terminator(): Trip? = _trips.lastOrNull { it.kind.severity == BreakerKind.Severity.HARD }
```

`budgetTail` 与 `emitBreakerFailed` 的用户文案**统一走 `terminator()`**（同口径取一份事实）—— 顺带修掉「热熔断被报成『耗时超出预算』」的判据错报。删除常量 `BUDGET_TAIL_TOKEN`（TokenBudget 是 SOFT、结构上永不终止；`report` 只在 HARD 终态装配）。

### 1.3 `TerminationReason.BreakerTripped`（P1）

此前墙钟/热/振荡/同参死锁/失败连击五种「被 Harness 主动掐断」的 `terminatedBy` 全是 `null`。新增枚举值并由 `emitBreakerFailed` 无条件填写；随后严质衡审查发现它只覆盖 4 条熔断（另 3 条走各自 plain Failed），三处补填 + KDoc 改为如实描述三条出口（见 §4）。

### 1.4 单轮生成超时（P1）

`AgentPolicy.generationTimeoutMillis = 300_000L`；`withTimeout` 包住 `engine.generateStream(...).collect {}`。

⚠️ **关键实现约束（写进 commit message）**：`TimeoutCancellationException` 是 `CancellationException` 子类，必须在 `catch (t: Throwable)` **之前**显式接住，否则超时被当成静默取消上抛（既无 `Failed` 也无诊断卡）。块首 `currentCoroutineContext().ensureActive()` 保证「父协程取消」优先于「误判为超时」。

### 1.5 绝对 `deadlineNanos` + 子 run 继承（P1）

此前 `ask_actor` 子 run 走 `runUnlocked → executeBody → executeBodyUnchecked` ⇒ **新 `RunState` ⇒ 新 `startedElapsedNanos` ⇒ 独立的全新 5 分钟预算**，而父 run 的墙钟检查只在轮头 ⇒ 真实最坏上界约 **12 分钟**（不是申报的「5min + 一轮」）。
新增 `AgentRequest.deadlineNanos: Long?`（**绝对 nanoTime 时刻，不是剩余时长**），`RunState.hardDeadlineNanos`，轮头判据从「已运行多久」改成「距硬截止还剩多少」，`AskSubagentTool.ParentContext` 透传父 run 的同一堵墙。

### 1.6 外提两块（体积硬约束）

`executeBodyUnchecked` 631 行、逼近 JVM 单方法 64KB bytecode 上限（**Wave 27 曾真实炸过 CI**）。外提 `gateRoundHead`（轮头墙钟+热闸）与 `accountSendTokens`（发送侧记账+TokenBudget SOFT）⇒ **631 → 434 行**。纯机械移动，非空行多重集与原来一致。

---

## 2. 宿主面：热补释放 + 账本接线（流 2）

- **热 CRITICAL 补释放**：`ThermalGovernor` 加 `isBusy: StateFlow<Boolean>?` / `scope: CoroutineScope?`（默认 null = 零回归）+ `@Volatile releaseDeferred`，观察 `AppContainer.agentRunner.isBusy`（全应用唯一的「引擎忙」真值源）在转闲时补一次释放。
- **`RunTokenLedger` 生产接线**：`ChatViewModel` 三处 `AgentRequest` 补 `tokenLedger = container.tokenLedger(cid)`；`observeTokenLedger` 把 `snapshot.sentTokens` 镜像进 `ChatUiState.sentTokensEstimate`；`ChatContextMeter` 并列「估算≈X · 实测 Y」两口径（**不换算**）。消掉「打开历史会话时从 `messages.lastOrNull { usage.promptTokens > 0 }` 反捞上下文占用」的 workaround。
  ⚠️ 已知口径边界：账本按 cid 池化跨 run 存活，而 `sentTokens` 是 run 级覆盖写 ⇒ UI **只消费 sentTokens**、不消费 `cumulativeIn/Out`。
- **`Failed.terminatedBy` 接 UI**：抽出顶层 `internal fun ChatUiState.applyTerminalEvent`（纯映射，可 JVM 单测），`ChatScreen` 渲染一行小字（`else -> null` 不渲染）。

## 3. 守卫升级 + CI 治理 + 文档（流 3）

- arch-guard **第 12 项**：主循环方法体积（≤550 行，阈值由「实测 ~66 B/源码行 × 550 ≈ 36 KB ≈ 上限 56%」推导）。
- arch-guard **第 13 项**：`AgentRequest` 可空字段孤儿（防「代码完备但未接线」新增）。
- **`scripts/arch-guard-selftest.sh`**：守卫的自测网（1 正例 + 6 违规 case），已接入 `build.yml`。
- `build.yml` 加 `paths-ignore`；修注释漂移；`release.yml` 删重复的 `harness-improve`。
- README 三态表补**第四态「🟡 已实现未接线」**并消除三处失实（Token 账本 / 路线图 ProviderStop / CI 能力表）。
- `LegalDocuments` 由占位改为按 App 真实行为撰写的草稿（保留 `TODO(legal)` 待法务复核）。

## 4. 工具面能力（流 4）

- `core-engine` 测试源集点火（此前 NO-SOURCE）：3 类 / 18 例。
- `file_read` 分页（offset/limit）：不传参数时走 `readHead()`，输出与引入前逐字节一致。
- `ToolSpec.keywords`（默认空 = 零回归）+ 内置工具中文口语别名 + `coverage()` 归一化并暴露「相关度 X%」。
  ⚠️ **命中门仍是 `score > 0`，本波有意不改判据**：本仓语料里分类/描述单命中的 coverage 天然很低（实测 ≈0.09 / ≈0.16），任何有意义的阈值都会误删正确命中（如 query「utility」应召回 `current_time」）；「相对最高分」式阈值又对「只有一条弱命中」恒判 1.0、过滤不掉。

---

## 5. 独立质量审查（严质衡）与裁决

审查判定「需修改」，给出 1 项 P0-候选 + 4 项 P1 + 4 项 P2。裁决结果：

### 5.1 P0-候选被证伪（用源码，不用猜）

审查担心 `withTimeout { emit() }` 位于 `flow{}` 内会触发 `Flow invariant is violated`（每次 run 第一个 `TextDelta` 即崩）。

我拉取本仓实际使用的 **kotlinx-coroutines 1.9.0** 源码逐行核验，**结论是合法、不会抛**：

- `Timeout.kt:151-154`：`TimeoutCoroutine : ScopeCoroutine<T>(uCont.context, uCont)`
- `SafeCollector.common.kt:92-97`：`transitiveCoroutineParent` 遇到 `ScopeCoroutine` 会**继续沿 parent 上溯** ⇒ 发射 Job 的传递父 = collect job，`:65` 的 `emissionParentJob !== collectJob` 不成立
- `:27-29` 检查非 Job 上下文元素（含 `ContinuationInterceptor`），`withTimeout` **只加 Job、不换调度器** ⇒ 全部一致
  （这也解释了为什么 `withContext` 换调度器会违规、而 `withTimeout` 不会。）

但该结论已固化为回归测试 `FlowWithTimeoutInvariantTest`：若将来升级 coroutines 让穿透失效，测试先红，而不是等真机上「第一个 TextDelta 就崩」。

### 5.2 采纳的修复

| 项 | 内容 |
|---|---|
| P1-B1 | `BreakerTripped` 另 3 条（StreamLoop / EmptyOutput / GenerationTimeout）补 `terminatedBy`；`emitBreakerFailed` KDoc 由「统一出口」改为如实描述三条出口。**注**：审查给的「5 条走本函数」不准，实测调用点只有 4 个 —— 开发按代码核出正确归属，未照抄。 |
| P1-B2 | 热补释放清位竞态：原「先清位再释放」在调用窗口内新 run 起来时会丢掉唯一一次回调 ⇒ 改为**调用后复查 `isBusy`** 才清位。 |
| P1-B3 | `observeTokenLedger` 的「先清 null」对 StateFlow 无效（立即重放上一轮值）⇒ 改用账本自身时间戳做基线。 |
| P2-C1 | 第 13 项旧正则静默漏检泛型字段（`Map<String, Int>?`）⇒ 改 sed 只抽名字；补 selftest case5/5b。 |
| P2-C2 | 第 12 项：① 只认 `private/internal/suspend fun` ⇒ 放宽后实测 `n` 仍 434；② 方法在类尾时 `e` 为空会让 `n` 变负而**静默通过**（僵尸规则）⇒ 补 `e` 非空断言；补 selftest case6。 |
| P2-C3 | `readRange` 不变量措辞精确化 + `limit=1/0` 边界用例。 |

### 5.3 已核验通过（审查独立确认）

catch 位置与 `ensureActive()` 语义、外提等价性（无捕获遗漏）、`RunState`/`ParentContext`/两处外提方法的所有构造点与调用点、`deadlineNanos` 绝对时刻语义与新旧判据等价（误差 ≤1ms）、`terminator()` 在「3min SOFT 留痕 + 热熔断」序列下取对、`budgetTail` 的 `when` 穷尽、`BreakerKind` 新增后无其它穷尽 `when`、`TerminationReason` 唯一 `when` 带 `else`、`coverage` 数学（`maxScore` 确为上界、除零防护、权重与 KDoc 推导清单逐值一致）、5 个新测试类的依赖符号逐个匹配。

---

## 6. CI 记录

| 轮次 | 提交 | 结果 |
|---|---|---|
| 第 1 轮 | `82aba24..b08bcb1`（7 commit） | **全红，但只有一个编译错误**：`ChatContextMeter.kt:116` `Operator '==' cannot be applied to 'Long' and 'Int'`。core-agent / core-model / core-data / core-engine / core-design **五模块全部编译通过**；Lint 与 Unit tests 的红均由这一处引起。 |
| 第 2 轮 | `b08bcb1..00fb46e`（4 commit，含编译修复 + 审查收编） | 见 §6.1 |

根因：本波把 `formatTokenCount(value: Int)` 放宽为 `(value: Long)`（为接收账本的 `Long` 估算），函数体里 `fraction == 0` 随之成为 `Long == Int`。
**教训（已写进 KDoc）**：把一个参数的类型改宽时，函数体里所有与整数字面量的比较/运算都要同步加 `L` —— 本仓无本地 JDK，这类错误只能靠 CI 发现，代价是一整轮 CI 往返。

### 6.1 第二轮结果：全绿

`b08bcb1..00fb46e`（4 commit）→ **Build #261 与 Release #168 全部 success**。

| job | 结论 |
|---|---|
| Assemble Debug (JDK 21) | ✅ success |
| Unit tests | ✅ success，`BUILD SUCCESSFUL in 1m 43s` |
| Lint (warn-only) | ✅ success |
| Build & (optionally) sign release | ✅ success |

**这是 `Unit tests` 第一次真正执行到测试**（第一轮挂在编译阶段）。实测各模块：
`core-model` / `core-engine`（**本波新点火，18 例**）/ `core-agent` / `core-data` /
`feature-chat`（**本波新点火，4 例**）五个模块 `testDebugUnitTest` 全部执行通过；
`core-design` / `feature-models` / `feature-settings` / `app` 仍是 NO-SOURCE。
全仓 JVM 单测 **315 例 / 24 文件**（Wave 30 末为 265 例 / 18 文件）。

Release 双轨产出（正是用户要求的「正式包 + debug 包」）：

| artifact | 大小 |
|---|---|
| `liquidagent-release-apk-harness-improve`（仓库密钥签名正式包） | 73.5 MB |
| `liquidagent-debug-harness-improve`（debug 包） | 41.2 MB |
| `liquidagent-release-mapping-harness-improve`（R8 mapping） | 4.5 MB |

---

## 7. 本波新增的挂账（Wave 32+）

1. **`Unit tests` 在第一轮从未执行到** —— 首次真正的测试执行在第二轮。若第二轮仍有红，需按失败明细收编。
2. `ProviderStop` 仍零生产写入点（README 已标 🟡，待接 UI 端点诊断页）。
3. 检索覆盖率**阈值**校准（需真机语料）；`AskSubagentTool` 的 `keywords` 因在禁改清单未补。
4. Lint 翻阻断（需先建立 baseline，无本地预演时一次翻红全仓阻塞）。
5. 记忆 pull 化 + `memory_search`、`ConversationRepository` 增量写、`SubagentProgress` 事件、`formatVersion`。
6. 真机验收 6 项（热四档 / `adb shell ps -T` 无线程泄漏 / 诊断卡渲染 / ledger 与 usage 一致性 / A-B 第 3 周期 trip 先于 maxRounds / 3min-5min 时间线）—— **一条都没回收**。

## 8. 方法论沉淀（值得复制到后续波次）

1. **报告只作线索**：本波报告的两处行号/项数偏差与一处「建议方案本身也不成立」（`firstHard()`），都是回源码才发现的。
2. **「终止者」口径必须取单一来源**：`firstHard` 与 `terminator` 的分叉会同时污染归因与用户文案。
3. **守卫自己也要有自测网**：第 13 项的注释写着「由 selftest 背书」而 selftest 没有它的 case —— 正是它要防的形态。今后**每新增一条守卫必须同步新增 selftest case**。
4. **P0-候选不要靠猜**：拉取依赖源码（coroutines 1.9.0）定论只花了 2 次 curl，比「宁可保守地改实现」便宜得多。
5. **注释里别写会立刻陈旧的数字**：本波两次出现「刚更新的用例数立刻被本波自己推翻」。
