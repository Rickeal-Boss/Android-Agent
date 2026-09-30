# Wave 40 交接 —— 对话页右侧「工作区」覆盖层 + 循环思考收口 + 会话上下文丢失修复 + v10 报告收编

> **基线 `352ab16`（Wave 39 收尾）→ tip `e9d2213`**
> **提交链**：`8f5fa1c`（G3 docs+守卫）→ `b0190a3`（G2 引擎双修）→ `f65c790`（G1 右侧覆盖层）→ `71b389a`（审查收口）→ `92563a8`（编译修复）→ `e9d2213`（lint 收口）
> **CI**：Build 36744397218 + Release 36744396854 **双绿**；lint artifact 实证真实新问题 = 0
> **团队**：沈思远（方案裁决）→ 柯码成 ×3 并行（G1 UI / G2 引擎 / G3 docs+守卫）→ 严质衡（全量审查）→ 主理人收口集成
> **需求来源**：用户三条（右侧覆盖层 / 循环重复思考 / 会话重开上下文丢失）+ 外部复评报告 v10 四项收编

---

## 一、交付

### A. 对话页右侧「工作区」覆盖层（G1，`f65c790`）

COMPACT 下从右缘拖出沙箱工作区文件面板，宽 `min(360dp, 82%)`；宽屏（`useTwoPane`）不做右滑、顶栏入口也不渲染（与左抽屉同判据）。

| 要点 | 实现 |
|---|---|
| 手势 | `AnchoredDraggableState` 双锚点（Closed = +面板宽屏外右 / Open = 0）+ 右缘 24dp 触发区；BOM 2026.02.00（foundation 1.10.3）**已解包 aar 按字节码核验签名**（`rememberSwipeableState` 已删除，禁用） |
| 数据面 | 直接实例化既有 `SandboxFilesViewModel(container)` —— 零新依赖、零数据复制；VM **懒创建**（首次打开才建，启动零扫盘） |
| 渲染层 | 覆盖层是 body `Row` 的**兄弟节点**（外层包 Box）—— blur 挂在 Row 上，若作子节点会把自己糊掉（参数面板同款问题） |
| 模糊 | 三路：锚点位移逐帧驱动进 body max（NaN 兜 0）/ 开启时向 `LocalOverlayBlurState` 登记 SHELL（对话框同通道）/ scrim `drawBehind` |
| 返回键 | `WorkspaceOverlayBackHandler` 注册在 `DrawerBackHandler` **之后**（LIFO 先派发）；回调内读 VM 真源：子目录下则 `navigateUp()`，否则关面板 |
| 可发现性 | ChatScreen 顶栏 COMPACT 加「工作区」入口（`onOpenWorkspace: (() -> Unit)?`，宽屏传 null 即隐藏）—— 手势不可发现，入口必须有 |
| 组件复用 | `SandboxFilesScreen` 的列表块与预览弹层抽为 **public** 组件（跨模块 `internal` 不可见，见 §3.4），原页行为零变化 |

### B. 循环重复思考（G2 B1 + 根因裁决）

**根因裁决**：三假设中「工具循环上限过宽」**证伪**（防线密度全仓最高：`maxRounds=8` / 同参硬护栏 3 / 零工具连击 3 / 空输出 3 / 轮内循环 2 / 振荡窗口 6，全部 trip → HARD 熔断）。

- **B1（本波修）**：跨轮 `seenSignatures` 每个签名只提醒一次 ⇒ 第三次出现同签名时无提醒可注入，被**直接当最终答案交付**（用户看到「又重复一遍」）。外提纯函数 `repeatSignatureVerdict(seen, reminded, signature)` 三态（FirstSight / Remind / RemindedRepeat），`RemindedRepeat` **且零工具调用**时按失败收尾（`AgentLogStore.error` + `breaker.trip(StreamLoop)` 幂等 + journal settled "Failed" + `emit Failed` + `return Terminal`），逐段对齐 `MAX_INTRA_STREAM_LOOP_ROUNDS` 超限路径。⚠️ **只在 `calls.isEmpty()` 时收口**——复读但同时还在发工具调用 = 工具侧在推进（归既有守卫管），在此熔断会误杀。
- **B2（策略内残余，不动）**：改写型思考循环（每轮文本都不同、不出句界、低于预算）→ 所有检测器全数放过，只靠 `maxRounds=8` 兜底。收窄需真机数据。
- **B3（最可疑输入侧根因，需真机取证，本波不动）**：legacy 回退路径（`roleChannelActive=false`）下 MODEL 轮经 `buildContents` 文本压平成 `Message.user` 发给引擎 ⇒ **模型把自己的回复当用户输入读 = 自我强化复读**（与 MEMORY 第一原则同族）。取证判据：复现时抓诊断卡 `roleChannelActive` 或日志「角色通道播种失败」。确认后再做（候选：legacy 分支给 MODEL 文本加显式前缀，一处字符串）。

### C. 会话重开上下文丢失（G2 C3，`b0190a3`）

**两个流行假设均证伪**：历史**有**回灌（`ChatViewModel.init` → `ConversationRepository.load` → `AgentRequest.history` → 引擎 `initialMessages` 按 role 播种）、角色**没**丢。

**真根因**：会话文件只存 USER + 最终 MODEL 答案（`commitAssistant`），**TOOL 与中间 toolCall 消息只进 journal**。重开/进程重启 → 引擎重建 → 播种的「历史」= 纯问答对 ⇒ **模型丢失全部工具执行上下文**（写过哪些文件、跑过哪些命令、计划推进到哪）。双态判别特征：**当场续聊不丢**（不重建、KV 保活）、**重启或切会话后丢**（重建、播种缩水）。

**修复 C3**：发送时从 journal 重放过程消息进引擎 history。

- 新函数 `mergeProcessIntoVisible(visible, process)` + `historyWithProcess(visible, journalRoot, cid)`（顶层 `internal`，可 JVM 直测）。
- 去重口径**逐行照抄** `onRecover`（`role.name+"|"+text`，只剔 MODEL 命中项；TOOL/USER 永不在会话文件里故不误剔）。
- 归档形态实证：`.dismissed.jsonl` **读**（`open(id.removeSuffix(".jsonl"))` 一字不差拼回）；`.jsonl.archived` 与 Wave2 旧名 `.jsonl.dismissed` 无法经 `open()` 还原 ⇒ 跳过（KDoc 如实记录）。
- 接线 `onSend` / `onSendFrom`（`AgentRequest(` 全仓生产调用点 4 处，`AskSubagentTool` 子 run 不接 —— 设计使然）；**只进 `AgentRequest.history`，不回写 `uiState.messages`**（UI 不重复渲染过程气泡）。

### D. 外部复评 v10 四项收编（G3，`8f5fa1c`）

| 项 | 内容 |
|---|---|
| I1 | `handoff-wave39` 铁律② 残留错误（「Release 没有 paths-ignore ⇒ docs 提交 Release 照跑」）改正为 paths 正向白名单表述 + 补「`paths` 对 tag 推送不生效」半个坑 |
| I3 | 守卫管道 rc 洗白矩阵搬进 `arch-guard.sh` 头注释（贴守卫头顶才防得住下一个写守卫的人） |
| I5 | `docs/10-device-acceptance.md` §11.0 补 debug/release 分叉：debug 不复现而 release 复现 ⇒ R8 相关新问题，需专门取证设计 |
| I4 | **arch-guard 第 16 项**：`AgentLogStore.setSink(` 全仓只允许 1 处调用点（单槽覆盖式，第二个 sink 会静默顶掉 ERROR 落盘）+ selftest `case14`/`case14b`（PASS 23 → **25**） |

---

## 二、审查与收口

严质衡全量审查：**0 P0 / 1 P1 / 4 P2**。审查逐条核实了 B1 三态等价性与收尾路径、C3 接线覆盖度与并发安全（TOCTOU 有 `readLines` 守卫 + `runCatching` 兜底，最坏丢一批消息不崩溃）、G1 的 AnchoredDraggable 字节码签名与宽屏/模糊/返回键链路、G3 守卫判据实测。

收口提交 `71b389a`：

- **P1-1（修）**：覆盖层 VM 在 `remember{}` 里直接 new、不经 ViewModelStore ⇒ `viewModelScope` 永不 cancel（全仓唯一绕过 store 的 VM 实例化点）。加 `DisposableEffect(vm 实例)` 补偿清理（`onDispose` 时 cancel scope），KDoc 写明两条后续约束（改正规 `viewModel()` 路径时本块必须删除；若加 `onCleared` 则仅 cancel 不够）。**不改旋转语义**。
- **P2-3（记已知事项，不修）**：右缘触发区与系统返回手势同带，本仓未设 gesture exclusion rect；左抽屉左缘是同款暴露面、非本波回归 ⇒ 真机须「三键 / 手势导航各验一次」，顶栏入口作兜底。
- **P2-4（修）**：`HistoryWithProcessTest` 的 `createTempDirectory` 未清理 ⇒ 改 `tempRoot()` 登记 + `@After tearDown()` 递归删除。

---

## 三、本波证伪清单（5 条，全部由实测/源码坐实）

1. **「工具循环上限过宽」证伪** —— 见 §B。
2. **「历史不回灌 / 角色丢失」证伪** —— 见 §C。
3. **裁决稿 I4 守卫判据被实测证伪**：`\.setSink(` 在仓库根目录**零命中**——唯一合法调用点是 Kotlin **尾随 lambda** 形态 `setSink { ... }`（无括号）；照稿落地会是**僵尸守卫**（连第二个 sink 都拦不住）。改为 `\.setSink[[:space:]]*[( {]` 双形态，case14 fixture 刻意用尾随 lambda 形态钉住。
4. **裁决稿「抽取 internal 组件跨模块复用」证伪**：Kotlin `internal` 是**按模块**隔离（不是按包/按文件）——app 虽依赖 feature-settings 也**看不到**其 internal 符号。故两个组件定为 public（本仓未开 apiLint/explicitApi，public 面可控）。更收敛的替代：feature-settings 暴露一个 public 组装入口、内部保持 internal —— 下波美化。
5. **`SegmentedHistoryStore` 是死代码**（类 KDoc 自述「未接线」），但**恢复权威本来就不在它** ⇒ 不是上下文丢失的原因。

---

## 四、本波新判据（可复用）

- **🔴 跨模块复用先确认 `internal` 是模块级可见性**：「A 模块依赖 B 模块」≠「A 能用 B 的 internal 符号」。抽组件做跨模块复用时，先想清楚可见性边界，别等编译报错才发现。
- **🔴 绕过 ViewModelStore 的 `new` VM 必须自己兜 scope 生命周期**：`ViewModel.clear()` 由 store 触发，绕过 store 即无人 cancel；补 `DisposableEffect(实例) { onDispose { vm.viewModelScope.cancel() } }`，并在注释里写死「改正规路径时必须一并删除」。
- **🔴 守卫判据必须覆盖「调用形态」，不只是「名字」**：同一个 Kotlin API 有 `f(` 与 `f {` 两种调用写法，按 `f(` 写判据会整条守卫失效 ⇒ **写守卫前先 grep 实测全仓真实形态**，并把易漏形态做进 selftest fixture（本波 case14 即如此）。
- **覆盖层与 blur 的层级关系是硬约束**：body 级 blur 挂 Row ⇒ 覆盖层必须是 Row **兄弟**节点；返回键靠 **LIFO** 排序（后注册先派发）而非 enabled 互斥。
- **「去重口径」要抄既有实现而不是重写**：C3 直接逐行照抄 `onRecover` 的 `role+"|"+text`，两处口径天然一致；各写一遍迟早漂移（同 A7 数字口径的旧教训）。
- **测试卫生**：`createTempDirectory` 必须配 `@After` 清理，否则每次跑测试留垃圾目录。
- **旧归档命名的可读性要实测再决定跳过**：`.dismissed.jsonl` 能经 `open()` 还原、`.jsonl.dismissed` 不能——差别只在后缀顺序，靠读代码推断会错，靠实测才准。

---

## 五、挂账（下波候选，按优先级）

1. **真机验收（当前最大缺口）**：① 右侧覆盖层右缘拖出（三键 + 手势导航各一次）+ 左抽屉手势回归；② B 根因取证 —— 复现「循环重复思考」时抓诊断卡 `roleChannelActive`（false ⇒ 走 legacy MODEL 前缀方案）；③ C 双态判别 —— 当场续聊不丢 vs 重启/切会话后丢。
2. **P2-1**：`historyWithProcess` 无上限回灌，长会话 IO/token 成本随 run 数线性增长 ⇒ 按 token 预算或近 N 轮开窗。
3. **P2-2**：`onRecover` 只回灌 unsettled run 的过程消息，与 C3「新发消息拿全量」口径不一致 ⇒ 下波统一走 `historyWithProcess`。
4. 历史挂账仍在：法务 `TODO(legal)` × 4（上商店硬阻塞）、`termsVersion`（必须先于文本替换落地）、G4 `ACTION_VIEW` 出应用三选一、**0 tags / 0 releases**、工具 schema 真机实证（R1，决定原生通道成败）、本地 gradle 基建波、记忆向量检索、THIN 0.21 已销账。

---

## 六、验证

- 本地三闸门：`bash scripts/arch-guard.sh`（**16 项 exit 0**，含新增第 16 项 setSink）／ `python scripts/check-test-void.py`（exit 0）／ `python ../balance_check.py`（G1 五文件全 OK）。
- `bash scripts/arch-guard-selftest.sh`：**PASS=25 FAIL=0**（G3 实测，本机 6m22s，本次未复现 EXIT trap 卡死）。

## 七、CI（三轮往返）

| 轮 | Build | Release | 结果 |
|---|---|---|---|
| 1 | [36741680697](https://github.com/Rickeal-Boss/Android-Agent/actions/runs/36741680697) | [36741680245](https://github.com/Rickeal-Boss/Android-Agent/actions/runs/36741680245) | ❌ 2m30s 快速失败：**8 条编译错误**（见下方教训） |
| 2 | [36743114113](https://github.com/Rickeal-Boss/Android-Agent/actions/runs/36743114113) | [36743114322](https://github.com/Rickeal-Boss/Android-Agent/actions/runs/36743114322) | ⚠️ Unit tests ✅ / Assemble Debug ✅ / Release ✅，**lint 硬门禁红**（1 条真实新问题） |
| 3 | [36744397218](https://github.com/Rickeal-Boss/Android-Agent/actions/runs/36744397218) | [36744396854](https://github.com/Rickeal-Boss/Android-Agent/actions/runs/36744396854) | ✅ **双绿**（Build 三 job 含 lint 全 success） |

**最终产物**：release APK **70.21 MB** ＋ debug APK **39.35 MB** ＋ mapping **4.34 MB**（Release workflow 另出一份 debug 39.35 MB）。
**lint artifact 实证**（`verify_lint.py 36744397218`）：报告里只有 `LintBaseline` 一个 Hint ⇒ **真实新问题 = 0**；基线吸收 3 errors + 1 hint = 4（与冻结值一致）；无 `LintBaselineFixed`。

### 三轮往返买到的四条教训（全部是「本机无 JDK 查不出」）

1. **🔴 `kotlin.math.min/max` 只接数值，不接受 `Dp`** —— `min(360.dp, …)` 报「None of the following candidates is applicable」，错误还会**级联**到下一行 `with(LocalDensity) { …toPx() }`（类型参数 R 推导不出来）⇒ 看错误信息容易误判成 `with` 的问题。**Dp 的取小/取大走 Comparable 版 `coerceAtMost` / `coerceAtLeast`。**
2. **🔴 扩展成员必须显式 import** —— `AnchoredDraggableState.animateTo`（5 处）与 `ViewModel.viewModelScope` 都是**顶层扩展**（不是成员），写对了用法但漏 import 照样 Unresolved reference；本地无 JDK 时「看起来像成员方法」最容易漏。
3. **🔴 `kotlin.test` 没有裸 `After` / `Before`** —— 只有 `BeforeTest` / `AfterTest` / `BeforeClass` / `AfterClass`（`AfterTest` = `org.junit.After` 的 typealias）。写 `@After` 直接 Unresolved reference。
4. **🔴 lint 的「修复建议」不等于正确解** —— `ConfigurationScreenWidthHeight` 建议改用 `LocalWindowInfo.containerSize`，但那是 **px 且语义为 ComposeView 容器尺寸**，切过去会改变行为（折叠屏/分屏边界翻档）。本仓既有约定：`screenWidthDp` 是正确口径 ⇒ **就地抑制 + KDoc 写明「为什么建议不适用」**（先例 `WindowSizeClass.kt:50`、`ConsentScreens.kt:134`）。**新增抑制优先用局部 `@Suppress`（压到该行），函数级会把将来同函数内的真问题一起吞掉。**
