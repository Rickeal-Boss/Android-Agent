# docs/16 — ChatRunCoordinator 接缝拆分预案（W69 成文，W71 G3 re-anchor）

> 性质：**预案成文，非执行**。本波（W71）**无结构性改动落 `ChatRunCoordinator.kt`**（见 §四）。
> 动机：深审 S-1 —— `ChatRunCoordinator.kt` 余量仅 **55 行**（1245/1300），而触顶动作当前指向**仓外文档**
> （守卫 #20 注释写的「`_plans/wave48-design.md` 第二拆分候选」在 `docs/` 下不存在）。本文件把
> **真实压力面**与**可减压力的接缝**钉进仓内，供触发时直接照做（对齐 `docs/13-agentrunner-split-plan.md` 范式）。

## 锚点口径（W71 G3 re-anchored，务必读）

**所有行区间已降级为参考值。稳定标识 = 函数名 / 字段名。** 任何触发本预案的动作前，
必须对 `feature-chat/src/main/java/com/rickeal/agent/feature/chat/ChatRunCoordinator.kt`
执行 `grep -n "<函数名>"` 实测取行号（执行纪律），不得直接引用本文档的行号。

教训（深度审 P2 / W71 实拉）：W69 成文时 21 锚中 **10 锚已漂移**（累计漂移 −90 ~ +32 行），
且漂移方向双向、文件总行数 1245 未变（增删相抵，`wc -l` 不暴露）—— 行号锚在活跃文件上
必然过期。故本版改**复合锚**：函数名（稳定）+ 触发时实测（纪律）+ 下表（参考值 + 漂移史）。

**实测漂移表**（方案席 2026-10-17 全量实拉 @ tip `d7bf462`，`git status` 干净；行号 = `grep -n` 结果）：

| # | 锚点（函数/字段名） | W69 成文行号（@172f398） | d7bf462 实测 | 漂移 |
|---|---|---|---|---|
| 1 | `handleEvent` | :899–1126 | :899–1147 | 起点 0（巧合）/ **尾 +21** |
| 2 | `onSend` | :671–700 | **:581** | **−90** |
| 3 | `onRecover` | :489–518 | **:406** | **−83** |
| 4 | `onSendFrom` | :814–843 | **:751** | **−63** |
| 5 | `commit` | :1128–1153 | **:1148** | **+20** |
| 6 | `commitAssistant` | :1155–1202 | **:1187** | **+32** |
| 7 | `thermallyCappedConfig` | :336–394 | **:355** | **+19** |
| 8 | `thermalRejection` | :327–334 | **:334** | **+7** |
| 9 | `renderMemoryIndex` | :313–325 | **:321** | **+8** |
| 10 | `maybeOfferRecovery` | :296–301 | **:300** | **+4** |
| 11 | `dismissRecovery` | :306–311 | **:310** | **+4** |
| 12 | `_streaming` / `streaming`（字段） | :83–84 | :83–84 | 0 |
| 13 | `textBuffer` / `thinkingBuffer` / `flushJob`（字段） | :87–89 | :87–89 | 0 |
| 14 | `ensureFlushLoop` | :133–146 | :133 | 0（起） |
| 15 | `flushNow` | :149–160 | :149 | 0（起） |
| 16 | `updateStreamingUsage` | :163–173 | :163 | 0（起） |
| 17 | `resetStreamingText` | :176–180 | :176 | 0（起） |
| 18 | `resetStreaming` | :183–198 | :183 | 0（起） |
| 19 | ①调用点 `thermallyCappedConfig(...)` ×3 | :488 / :633 / :784 | :488 / :633 / :784 | 0 |
| 20 | `ensureConversation` | :1204–1212 | :1204 | 0（起） |
| 21 | `firstUserText` | :1214–1215 | :1214 | 0（起） |
| 附 | C7 注释锚（§四申报引用） | :876–878 / :888–889 | :876–877 / :888 | 0 |

> 交接提醒（接缝约束）：任何动 `ChatRunCoordinator` 的 commit 须申报「docs/16 已 re-anchor / 未动」。

---

## 一、真实压力面：**文件级**

| 维度 | 载体 | 守卫 | 当前 / 阈值 | 余量 | 是否真压力 |
|---|---|---|---|---|---|
| **文件级** | `ChatRunCoordinator.kt`（`wc -l` = **1245**，触发时实测） | **#20**（≤ 1300） | **1245 / 1300** | **55** | ✅ **真压力**（余量 4.2%，是行数表 10 行中第三紧：仅宽于 `ThinkingBudget.kt` 余 22、方法级 `executeBodyUnchecked` 余 49） |
| 方法级 | `handleEvent`（d7bf462 实测 :899–1147，248 行） | 无独立守卫 | — | — | ❌ 无方法级守卫（与 AgentRunner 的 #12 不同，见 §五） |

> **一句话**：本文件的压力是**文件体积**（#20），不是方法体积。`handleEvent` 虽达 248 行，但它是一个
> sealed `when` 事件分派器（13 个 `AgentEvent` 分支），**不是**「可无脑外提的纯计算簇」；本预案治的是文件级 #20。

---

## 二、可减 #20 的接缝（按「收益 × 风险」排序）

| # | 接缝（方法簇） | 内容（方法名 + 参考行区间，d7bf462 实测；触发时 `grep -n` 复核） | 性质 | 预估减行 | 风险 |
|---|---|---|---|---|---|
| ① | **热档位簇** | `thermalRejection`（实测 :334）+ `thermallyCappedConfig`（实测 :355–394） | **近纯计算**：只读 `container.thermalGovernor` / `base: InferenceConfig` / `model: ModelDescriptor?`，无 emit、无实例状态（日志走 `AgentLogStore` 静态） | **≈ 60** | **低**（须保持：`governor.tier.value` 快照**只读一次**、`maxRoundsCap` 先于 `maxTokensCap`、`tokenFloor>0` 不压 maxTokens、三分支日志文案逐字） |
| ② | **run 请求装配簇** | `onSend`（实测 :581）/ `onSendFrom`（实测 :751）/ `onRecover`（实测 :406）三处近乎逐字重复的 `AgentRequest(...)` 构造（各 ≈30 行） | **重复结构**：三处字段集合与求值顺序一致（S-6「四条公开入口经三个调用点汇入」） | **≈ 55–70** | **中**（须逐字保持字段与求值顺序；`memoryText`/`approvalHandler`/`planStore`/`thermalGate` 引用 `container` 与 `uiState` 快照；⚠️ **必须同 commit 更新守卫 #30** —— #30 钉 `model = uiState.value.activeModel` 出现 **恰 3 次**，外提后该计数会变） |
| ③ | **落库簇** | `commit`（实测 :1148）+ `commitAssistant`（实测 :1187） | **run 级状态耦合**：依赖 `persistState`（run 起点清零不变量） | **≈ 60** | **中–高**（`persistState` 清零点须与 `collectRunWithPerfWindow` 首行 + `resetForNewConversation` 同步；`internal` 跨模块隔离，须抽 public/同包） |
| ④ | **流式缓冲簇** | 字段 `_streaming`/`streaming`（实测 :83–84）、`textBuffer`/`thinkingBuffer`/`flushJob`（实测 :87–89）、时间戳（实测 :92–93）+ `ensureFlushLoop`（实测 :133）/`flushNow`（实测 :149）/`updateStreamingUsage`（实测 :163）/`resetStreamingText`（实测 :176）/`resetStreaming`（实测 :183） | **状态所有权**：可抽 holder 类 `StreamingBuffer` 持有流 + 缓冲 + 时间戳 | **≈ 55–70** | **中–高**（`streaming` 是 VM 消费的 public StateFlow；`flushNow` 单点写 `salvageText`；所有权变更波及调用面） |
| ⑤ | **会话/记忆辅助簇** | `maybeOfferRecovery`（实测 :300）/`dismissRecovery`（实测 :310）（facade）+ `renderMemoryIndex`（实测 :321）/`ensureConversation`（实测 :1204）/`firstUserText`（实测 :1214） | **薄函数**：散落、单条收益小 | **≈ 20–30** | **低–中**（`ensureConversation` 是 `conversationId` 唯一写点，须保持） |
| ⑥ | **事件分派簇** | `handleEvent`（实测 :899–1147，248 行） | **高耦合**：一个 `when` 覆盖 13 个 `AgentEvent` 分支，多分支写 `_streaming`/`persistState`/缓冲 | **≈ 80+** | **高**（拆分会打散「三条终态相对顺序」硬不变量，见类头 KDoc；留最后） |

> **首刀建议**：先做 ①（近纯计算、零行为风险、减行最多、**不触碰任何守卫计数**），把 #20 从 1245 拉回
> **≈ 1185**（余 ≈ 115）。若仍紧，再评估 ②（须抽共享 `buildAgentRequest` **并同 commit 改 #30**）；
> ③/④ 涉及 run 级状态所有权，须配契约单测；⑥ 留最后。
> **共同纪律**：外提函数优先做成**顶层 `internal fun`**（① 直接返回 `InferenceConfig`，无需新增类型），
> 保持**求值顺序**与**逐字节行为等价**，并补 JVM 纯逻辑单测（沿 W59 加载集群拆分 / W63 接缝① 的先例）。

---

## 三、① 首刀详细（热档位簇）

- `thermalRejection()`（实测 :334）本体 = `container.thermalGovernor.heatBlockReason()` 一行 ⇒ 外提价值低，**保留原位**。
- `thermallyCappedConfig(base, model)`（实测 :355–394）是首刀主体：
  - **可外提判据**：入参只依赖 `base: InferenceConfig`、`model: ModelDescriptor?`，内部经 `container.thermalGovernor`
    取 `governor` ⇒ 改签名为 `thermallyCappedConfig(governor: ThermalGovernor, base: InferenceConfig, model: ModelDescriptor?)`，
    调用点（`grep -n "thermallyCappedConfig("` 实测 :488 / :633 / :784 三处）传 `container.thermalGovernor`。
  - **须逐字保持**：① `governor.tier.value` **只读一次**（Wave 51 P3-3：判据与日志 `.name` 共用同一快照）；
    ② `maxRoundsCap` 先于 `maxTokensCap` 求值；③ `tokenFloor > 0`（思考模型）不压 maxTokens；④ 三分支日志文案逐字；
    ⑤ 无变化静默返回 `base`（`if (!roundsChanged && !tokensChanged) return base`）。
  - 落点建议：新文件 `feature-chat/src/main/java/com/rickeal/agent/feature/chat/ChatRunThermal.kt`（同包顶层 `internal fun`）。
  - **新文件守卫纪律**：本仓「新文件裸奔」已六次复发（#32/#35/#36/#37/#38 的教训）⇒ 拆分同 commit **必须**给新文件补
    #25 家族行数守卫 + selftest case + `KNOWN_GUARDS`/映射表同步（沿 W65 先例）。

---

## 四、触发条件与阈值纪律

**触发条件（满足任一即启动本预案）**：

1. `ChatRunCoordinator.kt` **≥ 1290**（#20 余 ≤ 10，逼近 1300 硬预算；触发时 `wc -l` 实测）；**或**
2. 任一挂账**明确落 `ChatRunCoordinator`**（新功能/修复只能改此文件，无可外提空间）；**或**
3. 需要给本文件新增**非平凡逻辑**（> ~15 行）之前，先按 §二 顺序外提补足余量。

> 🔴 **禁改 #20 阈值**：`ChatRunCoordinator.kt ≤ 1300` 为「触顶 = 启动拆分评审，**不是改阈值**」语义
> （与 #12/#18/#25/#31/#32 同款「只许缩不许涨」）。触顶时的正确动作是**执行本预案的接缝外提**，而不是上调 1300。

**逐波复核记录**：

- **W69（成文波）**：**无结构性改动落 `ChatRunCoordinator`**。理由：W69 各刀落点均**不在**本文件
  （治理槽 → `scripts/*`；产品件 C3 → `ConversationDrawer.kt` / `ConversationRepository.kt` / `ChatViewModel`；
  工程件 S-1 → 本文档；S-2 → `ChatViewModel.kt`；C8 → `core-agent`）⇒ 按上表触发条件**均未满足** ⇒ 无触发，不拆。
  > **唯一触及** = 工程件 **C7（S-6 措辞）**：把实测 :876–877 / :888 注释里的「三条 run 路径」订正为
  > 「四条公开入口经三个调用点汇入」——**纯注释措辞、零行为、零行数影响**（同批由 C7 席执行，不属本预案范围）。
- **W71（G3 re-anchor 波）申报**：三问逐条复核（2026-10-17，tip `d7bf462`）——
  ① `wc -l` = **1245 < 1290**（余 55，未触顶）；② W71 全部落点无一挂账落 `ChatRunCoordinator`
  （治理批 → `scripts/*` + `docs/15`；产品件 → `feature-settings` 诊断页；工程件 → `docs/12`/README/KDoc）；
  ③ 无新增非平凡逻辑需求。⇒ **触发条件三问全否，未触发，不拆**。本波对 Coordinator 零触碰，
  接缝①（热档位簇）保持**「预案就绪、触发即做」**状态；21 锚已 re-anchor（见上表），后续任何动刀前
  仍须 `grep -n` 实测复核。

---

## 五、与既有拆分先例的关系

- `docs/13`（AgentRunner）的**真压力是方法级 #12**（单方法 64KB 上限，Wave 27 曾真实炸过 `Method too large`），
  文件级 #31 余量宽松；本文件相反 —— **真压力是文件级 #20**，`handleEvent` 虽长但**无方法级守卫**
  （`when` 分派器，外提收益/风险比差）。⇒ 两份预案**治的层级不同**，接缝清单不可互相套用。
- W65 X3「零行为风险文件迁移」先例（拆出 `ChatRunRecovery.kt`，`ChatRunCoordinator` 1278 → 1204）：journal 恢复
  三函数外提 + facade 保形，与本预案 §二①「顶层 `internal fun` + 调用点传依赖」同范式。
- 本文件替代守卫 #20 注释里指向的**仓外**「`_plans/wave48-design.md` 第二拆分候选」（该文档不在 git 仓内）
  —— 接缝预案自此入仓，触顶动作有仓内权威落点。

—— W69 成文（深审 S-1）；W71 G3 全量 re-anchor（21 锚复合化 + 漂移表 + 触发复核申报）；本波零改动落 `ChatRunCoordinator`。
