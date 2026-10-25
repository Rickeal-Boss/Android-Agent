# LiquidAgent 全局全量代码审查报告（2026-10-26）

> 分支 `core-fix`，tip `b794327`（W83 波收尾 + 审查修订后）。
> 审查方式：4 路并行只读审查代理（core-agent / core-data+app / core-engine+core-model / UI 三 feature 模块）
> + 主理人跨切面自审（gradle / CI / EOL / 许可证 / Manifest / 机械化反模式 / 覆盖图 / core-design），
> **全部关键发现经主理人逐条回源核验**（四态：✅证实 / ⚠️存疑 / ⛔证伪 / ⛔vacuous）。
> 纪律依据：「测试全绿 ≠ 口径被验证」「外部报告/注释/描述的因果都可能不成立 ⇒ 动手前回源码核验并标出处」。

---

## 一、总评

- **P0：0 处。** 无数据丢失 / 崩溃 / 安全逃逸 / 死循环级缺陷。
- **P1：4 处**（core-agent ×1、core-engine ×1、feature-chat ×1、feature-settings/tools ×1）——均需排期修复，其中 2 处有明确用户可见后果。
- **P2：13 处**（core-data ×5、core-agent ×2、core-engine ×1、UI ×5）——并发原子性窄窗口为主，修法均为一行到数行。
- **P3：约 30 处**——KDoc 失实 ×6、小优化 ×15、latent 声明缺口 ×2、开发机 only ×1、设计取舍知悉 ×1 等。
- **工程质量总判**：防御纵深（fail-closed 纪律、留痕纪律、终态四态、纯函数抽取可测性）与测试锚密度显著高于常规端侧项目；
  arch-guard 56 项 + 全量 selftest + 830 单测构成有效闸门。问题集中在**并发原子性窄窗口**与**注释-代码漂移**两类，无系统性架构缺陷。

### 模块健康度速览

| 模块 | 结论 | 关键发现 |
|---|---|---|
| core-agent（40 文件 / 8648 行） | 良（1 P1） | 审批先于校验（P1）；file_list 默认路径承诺未实现（P2）；ON_DEMAND 记忆指令缺失（P2） |
| core-data + app（40 文件 / 9227 行） | 良（无 P1） | 5 处并发原子性 P2；DataStore/下载/FGS/崩溃 handler 高危面全部核验成立 |
| core-engine + core-model（47 文件 / 8754 行） | 良（1 P1） | generateStream 无在途互斥（P1）；韧性计数竞态（P2）；1 项代理发现被证伪 |
| feature-chat / models / settings（39 文件 / 13724 行） | 中（2 P1） | forNewConversation 丢权限字段（P1）；工具页主线程 IO（P1）；6 处 P2 |
| core-design（64 文件 / 11366 行） | 优 | 模块纯净（零 core-* 依赖）、API 门控诚实、两处 `!!` 契约安全 |
| 跨切面（gradle/CI/EOL/许可/Manifest） | 优 | 零违规；文档冲突 1 处需勘误（见 §六） |

---

## 二、P1 详单（4 处，全部 ✅ 已回源证实）

### P1-1 审批闸门先于参数校验 —— 拒斥熔断被「从未执行的调用」消耗（core-agent）

- **位置**：`AgentRunner.kt:1819`（审批闸门）→ `:1894-1895`（拒绝对账 `toolDenialCounts.merge`）→ `:1919`（`ToolArgsValidator.validate` 在后）。
- **证据链**：`needsApproval` 为真的工具（file_write / clipboard set / memory_write / memory_delete / ask_actor / READ_ONLY 档位下的 WRITE 工具）**先弹授权卡**；模型参数给错（4B 高频）时用户看到畸形调用并合理拒绝 ⇒ `toolDenialCounts[name]++`；`DENIAL_CIRCUIT_LIMIT = 2`（:81）⇒ 第 2 次拒绝后熔断分支（:1836-1837）对该工具**本 run 剩余轮次全部跳过审批直接拒**，并回给模型「该工具已被多次拒绝，本任务内不再询问」+ pendingReminder「这是终态。换路径或直接收尾。」
- **影响**：一次「缺 content / 类型错」的调用被用户合理拒绝两次，即把整个任务的写文件路径焊死——即使模型后续修好参数也永被拒，任务被误导收尾。语义漂移：「用户拒绝了畸形调用」被误记成「用户拒绝了该工具」。
- **修法**：把 `ToolArgsValidator.validate` + `emitToolFailure` 块整体搬到审批闸门**之前**（校验失败不弹卡、不进拒绝对账）。同参签名计数（:1684）与振荡历史（:1697）对畸形调用的计数是无害的启发式防线，不动。
- **四态**：✅（主理人已核验执行序与熔断分支行为）

### P1-2 `generateStream` 无在途生成互斥 —— 健康探针与聊天可双流并发共享 native Conversation（core-engine）

- **位置**：`LiteRtLmEngine.kt:996-1060`（flow 体无 busy 闸门：token 自增 → 开关复位 → 熔断 → ensureConversation → `activeGenerations.incrementAndGet()`@:1059）。
- **证据链（四项闭环）**：① `DefaultEngineFactory.create` 用 `engines.getOrPut(kind)` 缓存（:28）⇒ 探针与聊天**共用同一 LOCAL 实例**；② 聊天侧 `AgentRunner:1461` 直 collect generateStream，`ChatRunCoordinator` 只闸自己的 `state.isGenerating`——**全链不查 `engine.isBusy`**；③ `ModelHealthProbe.run:127-129` 的 isBusy 是建议性布尔（其注释自述「真闸门在引擎侧」，而引擎侧并无闸门）；④ 探针卡**非 debug-only**（仅帧统计卡 debug 门控）⇒ release 包可达。
- **可达路径**：用户先点「运行自检」（检查时 isBusy=false）→ 探针生成中切回对话发消息 → 双流并发 `sendMessageAsync` 打同一 native Conversation ⇒ native 报错或冻输出；且 `generationCleanup`（:1324-1331）的 `cancelProcess()` 会杀掉对方的流（用户可见：在途回答被静默掐断）。
- **修法**（二选一）：① 引擎入口 fail-fast——flow 体首行对 `activeGenerations.get() > 0` 抛 `EngineException`（与 load/unload 闸门同构，最小改动）；② 上层 per-engine 生成 Mutex 收敛探针与 AgentRunner。
- **四态**：✅（主理人已核验四项证据链与可达路径）

### P1-3 `ChatUiState.forNewConversation` 丢 capabilityMode / disclosureMode（feature-chat）

- **位置**：`ChatViewModel.kt:759-767`（`forNewConversation()` 重建态只带 7 字段）vs 收集器 `:288-293`（capabilityMode）/ `:295-300`（disclosureMode）。
- **证据链**：两个收集器是**独立冷流**（`combine(settings.activeModelId, models)` 同族——「新对话」时上游不变 ⇒ 不重发，collect 协程只订阅一次）；字段重建后回落默认值 `AiCapabilityMode.WORKSPACE_WRITE`（:116）/ `ToolDisclosureMode.FULL`（:122）⇒ 无人恢复。**同一函数 KDoc（:745-757）记载的 activeModelKnownBroken 就是此教训（W68 B2-R1）的修复**——两个新字段没照做。
- **影响**：用户在设置页收窄能力档位（如 READ_ONLY）或切 ON_DEMAND 后点「新对话」，下一次 run 的 `request.capabilityMode` 静默回默认（权限变宽：READ_ONLY → WORKSPACE_WRITE）/ 披露模式回 FULL。安全向漂移（静默变宽），用户不可见。
- **修法**：`forNewConversation()` 构造补 `capabilityMode = capabilityMode, disclosureMode = disclosureMode` 两行 + KDoc 补记（与 activeModelKnownBroken 同段）。
- **四态**：✅（主理人已核验重建态字段清单、收集器冷流形态与默认值）

### P1-4 工具测试页主线程跑工具 IO（feature-settings/tools）

- **位置**：`ToolsViewModel.kt:156-166`（`onRunTest`：`viewModelScope.launch { … tool.invoke(args) }`，无 `withContext(Dispatchers.IO)`）。
- **证据链**：`viewModelScope` 默认 `Dispatchers.Main.immediate`；`FileReadTool.invoke`（FileTools.kt:223 起）内部**不切调度器**（直落 `resolveSafe`/`file.readText`）。生产路径 `ToolExecutionGuard.kt:60-62` 显式 `withContext(Dispatchers.IO) { tool.invoke(...) }` 且注释写明理由「工具会做文件读写…必须离开调用方线程」——**工具测试页是全仓唯一漏 IO 切换的 invoke 调用点**。
- **影响**：读大文件 / file_list 万级目录时主线程阻塞 ⇒ 工具测试页 ANR / 掉帧；该页定位就是「造执行失败态验证展示」，恰恰最可能对大文件/坏路径操作。
- **修法**：`viewModelScope.launch { withContext(Dispatchers.IO) { … invoke … } }` 包住 invoke（或给工具页专用 scope）。
- **四态**：✅（主理人已核验调用点、调度器默认值与生产路径的对照）

---

## 三、P2 详单（13 处，全部 ✅ 已回源证实）

### core-data（5 处，并发原子性窄窗口）

**P2-1 `ConversationRepository.create()` 不经 writeMutex —— 并发创建丢 meta**（:176-180）
`create` 直调 `save`；`save`（:105-109）的 `_metas.value.filter{}+toMeta` 是非原子 RMW。类 KDoc（:68-75）自述锁只加在 appendMessage/rename/autoTitle/delete 四入口——create 是漏网的第五个 RMW。两 create 交错 ⇒ index.json 与 _metas 只保一个 ⇒ 另一会话文件成**无索引孤儿**（列表不可见、磁盘残留）。可达面：`ChatRunCoordinator.onSend` 的 isGenerating 闸门与置位间的双击窗口。**修法**：`create` 内 `writeMutex.withLock { save(...) }`（save 自身不加锁，无自锁风险；Mutex 不可重入的既有纪律不变）。

**P2-2 `AppContainer` 两个池的 `getOrPut` 非原子 —— 实例分裂**（:159-162 / :176-179）
Kotlin `ConcurrentHashMap.getOrPut` 扩展是先 get 后 put（非原子）。`tokenLedgers` 是活面：`ChatRunCoordinator:233/504/686/829` 多调用点中两协程首次同时调 ⇒ 各建一个 `InMemoryRunTokenLedger`，AgentRunner 回写实例 A、UI 观察实例 B ⇒ **token 计数 UI 侧永不动**（观测面静默失效、零报错）。KDoc 自称「池化后 commitMutex 真正生效」的前提被自身实现破坏。historyStores 同型但当前死代码（W83-11）零影响。**修法**：两处改 `computeIfAbsent`（ConcurrentHashMap 原生原子）。

**P2-3 `ModelRepository.importFromPath` 幂等声明与实现有窄窗口**（:376-386）
`existing` 在锁外预读 `_models.value`，id 是 UUID。下载完成登记与手动 refresh 并发同路径 ⇒ 双双读到 existing=null ⇒ 两个 UUID 条目同路径，下次 refresh 才 distinctBy 收敛。**修法**：existing 查询挪进 upsert 锁内，或 id 改由 path 派生稳定值。

**P2-4 `AgentForegroundService.refreshOnging` 的合并非原子**（:158-175）
`refreshScheduled` 的 check-then-set 无同步；publish 可在任意线程。两线程同时 publish ⇒ 主线程队列短暂 2 个 Runnable（KDoc 承诺「队列长度恒 ≤1」不严格成立）⇒ 多刷一帧常驻通知。无害但边界失真。**修法**：`AtomicBoolean.compareAndSet`。

**P2-5 `AndroidGenerationNotifier.lastEmitAt` 缺 @Volatile**（GenerationNotifier.kt:80）
对比 :84-85 `enabled` 明确 @Volatile 且注释称防跨线程可见性——同文件同类字段处理不对称。onTick 若跨线程，节流窗口可见性错位 ⇒ 通知多发/少发一帧。**修法**：补 @Volatile（一行）。

### core-agent（2 处）

**P2-6 file_list 的「默认根目录」承诺未实现**（FileTools.kt:537 vs :544-546 + :46-48）
spec 声明 `param("path", …, required = false)` 明示默认根目录；invoke 里 `stringArg` 缺键返回 `""` ⇒ `resolveSafe("")` 抛「路径为空」。省略 path **100% 失败**，模型必须自己领悟传 `"."`，且报错文案不给下一步提示。tools/ 下无 FileListTool  invoke 层用例（该背离无测试锁定）。**修法**：`val path = stringArg(argumentsJson, "path").ifBlank { "." }`（一行）+ 补 invoke 层用例。

**P2-7 ON_DEMAND 披露模式下 MEMORY_MAINTENANCE 指令静默消失**（AgentPromptComposer.kt:187）
门控 `tools.any { it.name == "memory_write" } }` 判的是**可见清单**；ON_DEMAND 下 availableTools 只剩 search_tools/call_tool 两元工具（AgentRunnerToolSetup.kt:69）⇒「收尾用 memory_write 沉淀」指令不注入。ON_DEMAND 在设置页可达（SettingsScreen.kt:868-882）⇒ 记忆沉淀功能该模式下静默失效、无日志。**修法**：门控改判「可见或隐藏目录有 memory_write」（需把全量工具名传进 buildSystemSections）——**动提示词面，golden hash 须同步更新并申报**。

### core-engine（1 处；另 1 处代理发现被证伪，见 §五）

**P2-8 `handleTemplateRenderFailure` 的 `templateRebuildCount++` 非原子**（实位 LiteRtLmEngine.kt:1430；代理原报 :1152 行号有误）
`@Volatile var` 的 `++` 是读-改-写非原子；两条入口（:1205 异步 onError native 回调线程 / :1362 同步 catch engineDispatcher）可并发。当前 `TEMPLATE_REBUILD_FUSE_THRESHOLD = Int.MAX_VALUE`（不熔断）⇒ 计数仅观测、危害被掩盖；一旦按 W58 前置⑤回填 K=3，计数失真直接削弱熔断（少计 ⇒ 该断不断）。**修法**：字段换 `AtomicInteger`（或处置整体挪 engineDispatcher 串行化）——**必须与熔断阈值回填同批**。

### UI 三模块（5 处）

**P2-9 TTFT 披露文案的作用面宽于其依据**（ChatMessageList.kt:242）
条件 `streamAnswer.isEmpty() && streamThink.isNullOrBlank()` 覆盖**一切首字未到窗口**——含工具执行阶段与后续轮次；而「约 20 秒」的依据只是冷启 round0（W82 取证）。用户热问第三句看着「正在生成回复（首次需加载模型，约 20 秒）…」会误以为又冷启了。**修法**：窗口条件加 round 首轮限定（如 `streaming.roundIndex == 0`，以实际字段为准）+ 文案拆两句。

**P2-10 诊断页日志渲染非懒 + 无条数上限**（DiagnosticsScreen.kt:1075-1079）
`:1075/:1079` 对 `MAX_SHOWN=200` 条 `persisted + visible` 逐条 `appendLine` 拼整段字符串再 `Text(text)` 一次性布局；无 maxLines/垂直滚动。200 条 × 100+ 字符 ≈ 20KB+ 单行文本 ⇒ 长日志时该卡重组即掉帧，且**诊断日志常含数万字符单行**（本仓已知坑）。**修法**：LazyColumn + 每行一个 item + maxLines 截断。

**P2-11 message 提示无自动消退**（ModelsViewModel :264/:288/:296/:302/:311/:325/:332/:380/:405/:436/:459/:480/:481/:510/:519/:532/:566/:579/:661/:690/:698/:746/:775/:810/:827/:844/:877/:899/:908/:925/:940/:949/:970/:987/:998/:1009/:1019/:1032/:1044/:1055/:1067/:1076/:1108/:1122/:1132/:1141/:1150/:1160/:1169/:1178/:1199）
错误类 message（删除失败 / 激活失败等）**粘屏到下一次写点**，用户改个设置就静默消失、无「知道了」关闭手段；且与 GlassSnackbar 平行两套机制。**修法**：错误类 message 过 N 秒自动清 或 提供关闭钮；顺带收敛双机制。

**P2-12 onDelete 无忙时闸门**（ModelsViewModel.kt:1087-1101）
删除在载模型 / 生成中可执行：加载中删 ⇒ load 后半段找不到文件（失败态正确但报错误面）；生成中删在用模型 ⇒ 引擎异常（可恢复但可见）。**修法**：onDelete 首行加 isBusy/isGenerating 闸门 + 禁用态。

**P2-13 GPU 白名单回落不落盘**（ModelsViewModel.kt:984-999）
未列白名单模型的 config.backend=GPU 时，加载前回落 CPU 只改 `_uiState` + 局部量（:809 的持久化在 onBackendChange，不覆盖本路径）⇒ 每次激活都要重跑回落+重打 warn，冷启后 UI 短暂说谎。行为不错（重活每次重干）。**修法**：回落后补一次 `settingsRepository.updateInferenceConfig`。

---

## 四、P3 汇总（约 30 处，抽验 10 处 ✅ 属实）

### KDoc / 注释失实（6 处，按「单一落点」纪律应批量订正）
| # | 位置 | 失实内容 |
|---|---|---|
| 1 | `BreakerLedger.kt:3-6/61-65` vs `AgentRunner.kt:1791` | 称「未注册不算 attempt、recordAttempt 仅 executeWithGuard 一处调用」，实际未注册分支也记账（有意偏离：为 BottleneckReport 供归因线索）——诊断卡「尝试过的工具」含从未执行的调用 |
| 2 | `AgentRunner.kt:1693-1696` | 称同参死锁「被硬护栏挡在第 3 次起忽略不执行、不再产生新历史项」，实际 callSignatureHistory.addLast 在硬护栏**之前**（测试 ToolOscillationDetectorTest:41-45 写的是反的且正确） |
| 3 | `AgentPlanStore.kt:89-97` | 称 stepsFor「只读不 touch 访问序」，实际 `plans[key]` 在 accessOrder=true 上会刷新访问位 |
| 4 | `TextToolProtocol.kt:133-138` | 称「普通 JSON 代码块原样保留」，实际带 `name`/`tool`/`function` 字符串键的 JSON 围栏会被当协议块剥掉（判据 toolNameOf 的已知宽度，建议写明） |
| 5 | `SamplingParams.kt:13-15` | 温度注释称「LiteRT-LM SamplerConfig 无 repetition penalty」，与 0.17.1 起 RepetitionPenaltyConfig 真实生效矛盾（同文件 :5-9 另说）——降档理由已过期 |
| 6 | `SettingsViewModel.kt:689` | 称「此前无统一恢复面」，W81-06 的 `restoreUiStateFromConfig()`（:162）即恢复面 |

### 代码质量 / 小优化（15 处，摘重）
- `AgentRunner.kt:2179-2184` Remind 分支缺单槽检查（理论不可达——工具循环当轮末清零；补 `if (state.pendingReminder == null)` 与文档对齐）
- `ToolExecutionGuard.kt:61` 工具超时非冻结感知（裸 withTimeout；灭屏冻结 15s+ 解冻瞬间误杀正常工具调用——端侧灭屏即冻结场景可达；P3-6）
- `ToolApprovalCache.kt:113-114` revokeAll 前缀误删（cid "c1" 连带清 "c1x\|…"；安全方向但越界）
- `ToolArgsValidator.kt:137-138` 拒数字字符串 `"5"` vs FileTools/MemoryTools 明示宽容——层间口径不一致，校验层拦在宽容层前面
- `DateTimeTool.kt:34/39` 时钟双采样（formatted 与 epochMillis 可差 1ms）
- `ChatViewModel.kt:436-439` `onParamChange` 零生产调用点（W82-13 时代的活代码，现死代码）
- `ChatViewModel.kt:565` onCleared 无条件全量写（建议 `if (current != next)` 短路，省每次退对话页一次 DataStore 写）
- `ChatParamsSheet.kt:89` BackHandler 只在 visible 期间拦截——退场动画 ~300ms 窗口 BACK 穿透（数据已提交，影响止于多退一级页面；我 W83-07 实现的残余面）
- `ChatRunCoordinator.kt:1136-1148` KDoc 陈旧（run.cancel() 已改 run.onStop() 两年，新贡献者读注释会得出反向结论）
- `ModelsViewModel.kt:162-168` `_uiState.update` 读的是 load() 入口旧快照（点第二张卡加载再取消，先启动的加载收尾时把 config/backend 写回旧值）
- `ModelsScreen.kt:216-223` 等 GPU 禁用模型卡点 GPU 是静默 no-op——红字常显但 GPU 选中态（全局 config.backend）可并存，用户仍困惑
- `DiagnosticsScreen.kt:160-161` 日志/错误用 `mutableStateOf(快照)` + `LaunchedEffect(Unit)` 只取一次——非可观察容器，探针结果等变化要手动重取（代理原报 mutableStateMapOf 定位有误，实质为该快照模式）
- `DiagnosticsScreen.kt:205-215` 通知开关 / `:237-250` 通知使用权不做实时门控——开启即用旧值渲染（次要：Listener 回调会纠正）
- `ModelDownloader.kt:99` enqueue 的 deletePartFiles 会删并发同名下载的 `name-1.ext.part`
- `FileTools.kt:516-519` renameTo 覆盖在 Windows 必败（开发机 only；生产 Android 无此问题）

### latent 声明缺口（2 处，安全向，须写进注释防未来踩中）
- `AskSubagentTool.kt:192-195`：子 run 未声明 allowedTools 时取**全量注册表**且无审批通道（approvalHandler=null）——当前被 capabilityMode 档位 + dangerous 工具 fail-closed 兜住；若将来引入「WRITE 效果却不声明审批」的工具，子 run 成绕过父白名单+审批的路径
- `Conversation.toMeta():37-38` 预览退化：末条 MODEL 消息 text 为空白（纯思考/工具轮）时 `?.text` 得 `""` 而非 null，不回退更晚的 USER 文本

---

## 五、证伪与定位修正（诚实登记——代理发现的四态）

| 代理原报 | 核验结论 | 说明 |
|---|---|---|
| engine：`AgentRunner.kt:133` KDoc 声称 sandboxDir 白名单「只允许 {filesDir, cacheDir, externalFilesDir}」、:1236-1250 消费无校验 | **⛔ 证伪** | 全仓无此声明、无此白名单、无此消费点（AgentRunner 零 filesDir/sandboxDir 引用）。真实实现 = AppContainer 单一 `filesDir/agent_sandbox`（:127）+ FileTools 三层 resolveWithinSandbox（core-data 代理独立核验通过） |
| engine：templateRebuildCount++ 在 :1152 | **⚠️ 定位修正** | 实位 :1430（handleTemplateRenderFailure）；实质（非原子 ++）成立 |
| features：ModelsScreen:50 / SettingsScreen:47 `mutableStateMapOf` | **⚠️ 定位修正** | 两处该行号为 import；实质（UI 侧非可观察快照）在 DiagnosticsScreen:160-161 成立 |
| features：ToolsViewModel 在 feature-models 模块 | **⚠️ 定位修正** | 实在 feature-settings/tools/；实质（主线程 invoke）成立 |

> 方法论价值：本次 4 路代理共报 ~50 项，主理人回源核验发现 **1 项完全证伪 + 3 项定位错误**——再次实证「外部报告/代理结论的因果都可能不成立，逐条回源是必要成本」。

---

## 六、跨切面结论（主理人自审，全过）

1. **gradle / 依赖**：version catalog 锁死（AGP 9.3.2 / Kotlin 2.3.0 / litertlm 0.17.1）；禁项（KSP/Room/Hilt/Dagger/Koin/Navigation3/Retrofit/Coil/JitPack）零命中；模块依赖方向全部正确（core-model 零项目依赖；core-engine→model；core-agent→model+engine 不碰 litertlm；core-design 零 core-* 导入）。
2. **CI**：build.yml push 分支 7 个枚举齐全 + PR 门禁 ['**']（W83-06）；b794327 run 40210850003 三 job 全 success；release.yml 双轨按设计（core-fix 不进白名单，按需手动 dispatch）。
3. **EOL / 许可证**：`.gitattributes`（*.kt/*.sh eol=lf）实测 0 违规；根 LICENSE Apache-2.0 + NOTICE（AndroidLiquidGlass 衍生署名）+ README 声明齐全；34 个衍生文件头署名正确。
4. **Manifest / 安全机械化**：权限最小集（INTERNET/NETWORK_STATE/POST_NOTIFICATIONS/FGS/SPECIAL_USE/WAKE_LOCK）；FileProvider exported=false；硬编码秘密/IP 扫描零命中（仅 sanitize KDoc 的模式描述）；机械化反模式面干净（TODO 5 处全为 legal 占位、GlobalScope 0、runBlocking 0、!! 3 处均核验安全、printStackTrace 仅 CrashSceneLogger 有意为之、Thread.sleep 仅 perf 采样 daemon 线程）。
5. **测试覆盖图**：319 个 main 顶层声明 ↔ 90 个测试类，同名对位 39；大 VM（ChatViewModel/ChatRunCoordinator/ModelsViewModel/AgentRunner/LiteRtLmEngine）无对位行为测试——与 W83-14 挂账一致（无 kotlinx-coroutines-test）；纯函数抽取纪律执行好（missingTaskProgress/crashSceneLines/withinWindow/probePresentation 等均可 JVM 钉）。
6. **core-design（64 文件 / 11366 行）自审**：模块纯净、API 门控诚实（LiquidGlassCapabilities 的死代码有诚实 KDoc）、RuntimeShaderCache 随 modifier 节点生命周期合理、两处 `!!`（Outline.kt:17 / ShapeProvider.kt:46）契约成立（Rounded 分支必有 path；_outline 赋值后非空）——移植代码的既有形态，P3 级。
7. **文档-代码冲突（需勘误）**：`docs/04-code-review.md:747` 与 `docs/handoff-20261025-wave82-quality-perf.md:87` 均记 W82-13 修法「改走 onParamChange」，与已实施的三边界 flush + BackHandler 不符——建议在本报告挂账 W84 文档勘误项（历史交接文档可加勘误指针，勿改史）。

---

## 七、修复优先级建议（下一波 W84 候选排序）

1. **P1 四条**（P1-1 审批前移 / P1-2 引擎在途互斥 / P1-3 forNewConversation 补字段 / P1-4 工具页切 IO）——前两条有用户可见错误路径，建议同波落地；每条 1-20 行改动 + 对位测试。
2. **P2 十三条**：一行档五条（create 锁 / computeIfAbsent ×2 / @Volatile / file_list ifBlank）可一批快修；P2-7（ON_DEMAND 记忆指令）与 P2-10（日志懒加载）需单独小心（前者动 golden hash、后者动 DiagnosticsScreen 1088/1150 行数闸门）。
3. **P3 批量**：KDoc 失实 6 处一批订正；死代码 2 处（onParamChange / AttachmentStore.copyToInternal）删除或委派；latent 声明 2 处补注释。
4. **文档勘误** 1 处（§六.7）。

## 八、本报告的口径说明

- 所有 P1/P2 均经主理人逐条回源核验（证据链见各项）；P3 为代理报告 + 抽验，未逐条全核。
- 四态标签：✅ = 已证实；⚠️ = 定位修正后实质成立；⛔ = 证伪；⛔vacuous = 判据不成立（本波无）。
- 本报告为只读审查产出，**未修改任何源码**；修复排期待用户裁决后按 W84 波走（含闸门 + CI）。
