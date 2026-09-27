# Wave 25 交接文档（复审3 逐条核验 + 最小修复批）

> 写于 2026-09-27 16:00 | 基线 `84fdee2`（= `70195d8` + Wave 24 交接文档）
> 上游：`LiquidAgent-复审3-三线合审-终版.md`（整合人：莫斯，2026-09-27 15:35，基线 70195d8）
> 一句话：复审3 的 5 条待派单缺陷**在当前代码里全部复现属实**，本波清 4 条（#1/#2/#3/#4），#5 明确 defer 并留技术论证。

---

## 1. 复审3 逐条核验结论（全部逐行读源码，不采信任何报告自述）

| # | 报告判定 | 我方核验 | 证据（文件:行，基线 84fdee2） |
|---|---|---|---|
| 1 | **P1** 循环乱文 UI 残留叠加 | ✅ **属实** | `AgentRunner.kt` 三条处置路径（轮内循环 :761-803 / 重复回答 :842-872 / 空输出 :877-906）只 `working.add` + `journal.append*` + `round++/continue`，**零 UI 事件**；`ChatViewModel.kt` 的 `resetStreamingText()` 仅在 `Retrying`(:930) 与 `Failed`(:1061) 调用；`RoundStarted`(:920) 只写 `agentRound/agentMaxRounds`；`MessageCommitted`(:1017) 只 append 消息、不动缓冲。⇒ 下一轮 `TextDelta` 叠在被丢弃的乱文之后，直到终态才消失 |
| 2 | **P2** 尾部 MODEL 静默丢失 | ✅ **属实（当前不可触发）** | `LiteRtLmEngine.kt:472` `seed = nonSystem.dropLast(1)` 把尾条排除在播种外；`buildContents():646` 在 `roleChannelActive` 时跳过 MODEL ⇒ 尾条若为 MODEL 则**既不播种也不发送**。当前入口尾条恒 TOOL（AgentRunner 工具轮）/USER（UI 层），故不可触发 |
| 3 | **P3** `AgentRunner.kt:808` 死条件 | ✅ **属实** | `intraStreamLoop` 声明在 `while(round < maxRounds)` **循环体内**（:524，每轮重建）；其分支（:761-807）所有路径都是 `return` 或 `continue` ⇒ 执行到 :852（原 :808）与 :915（原 :871）时恒为 false。**报告只点了 :808，:871 同一处死条件，一并删** |
| 4 | **P3** 提醒文案无「[系统提醒]」壳 | ✅ **属实** | `REPEAT_REMINDER`(:107) / `NO_TOOL_REMINDER`(:111) / `INTRA_LOOP_REMINDER`(:155) 三条 `role = Role.USER` 且裸文本；**另有第四处**：空输出 nudge 文案内联在调用点（原 :900），同类同构，报告漏列 |
| 5 | **P3** 流式跟随手势竞争 | ✅ **属实，但本波 defer** | `ChatMessageList.kt:113-119` `atBottom` = `lastVisible.index >= totalItemsCount - 2`（2 条松弛）；:138-149 流式跟随每个文本 tick 无条件 `animateScrollToItem`，无手势让位判据。**defer 理由见 §3** |

### 议题② 复核（滑动卡顿）

- **路径 A（覆盖层模糊）已结构性解决** ✅：`core-design/.../OverlayBackdropBlur.kt` 在库，缩略图管线在位。
- **路径 B（长会话回看）成本结构未变** ✅ 与报告一致：`DrawBackdropModifier.kt:284` `recordLayer` 每帧全量重录、:366-372 `updateEffects()` 重建 RenderEffect 链；全仓**无** `isScrollInProgress` 降级（grep 零命中）。报告的三条优化方向（滑动降级 / 源指纹跳录 / 参数不变跳重建）**均未实现**。

---

## 2. 本波修复（4 项，全部落在既有纪律内）

### 2.1 `StreamReset` 事件 —— 清掉被丢弃的乱文（#1，P1）

- `AgentEvents.kt`：新增 `data class StreamReset(val reason: String)`。
  **为什么不挂 `RoundStarted`**（报告也这么建议）：`RoundStarted` 每轮都发（含正常轮），挂上去会误伤「正常轮之间的过渡话术」—— 上一轮合法输出在被 `MessageCommitted` 之前不该被抹。本事件只在**真的丢弃了文本**时发，语义精确。
- `AgentRunner.kt`：三处处置点各 emit 一次（:804 / :873 / :908），均紧跟在 `journal?.appendReminder(...)` 之后、`round++` 之前。
- `ChatViewModel.kt`：`is AgentEvent.StreamReset -> resetStreamingText()`。
  与 `Retrying` 同理 —— **清缓冲不落库**（被丢弃的文本本就不该交付）。
- 兼容性：`AskSubagentTool.kt:198` 的 `when` 已有 `else -> Unit` 兜底，新事件不破坏子代理路径；`ChatViewModel` 的 `when` 无 `else`（sealed 穷尽），已补分支。

### 2.2 尾部 MODEL 兜底（#2，P2）

`LiteRtLmEngine.kt`：`val tailIsModel = nonSystem.lastOrNull()?.role == Role.MODEL`，
`seed = if (tailIsModel) nonSystem else nonSystem.dropLast(1)`。
尾部是 MODEL 时**全量播种**（含该条），本次载荷退化为空文本 —— native 侧已持有完整历史，
空载荷语义与既有 `fresh.isEmpty() → Content.Text("")` 兜底一致。
当前路径 `tailIsModel` 恒 false ⇒ **行为逐字节不变**，纯防御。

### 2.3 删死条件（#3，P3）

`:852` / `:915` 的 `if (intraStreamLoop) LENGTH else (accumulator.finishReason ?: STOP)`
→ `accumulator.finishReason ?: STOP`。
**同时在上方轮内循环分支加了不变量注释**：本分支所有路径恒 `continue/return` ⇒ 下方
`intraStreamLoop` 恒 false；若日后有人去掉那个 `continue`，必须把三元加回去（否则
「截断轮次误标 STOP」会静默回归）。

### 2.4 合成提醒统一加「[系统提醒]」壳（#4，P3）

- 新增 `SYSTEM_REMINDER_PREFIX = "[系统提醒] "`，四条提醒统一带壳：
  `REPEAT_REMINDER` / `NO_TOOL_REMINDER` / `INTRA_LOOP_REMINDER` / **新增常量 `EMPTY_ANSWER_NUDGE`**（第四处，报告漏列；旧实现文案内联在调用点，抽成常量是为了「所有合成提醒都带壳」只有一个落点）。
- **为什么加文本壳而不是换 role**（报告建议，我方独立复核认同）：换 `Role.SYSTEM` 会被引擎的
  `roleChannelActive` 门控**跳过、根本不发送**；换 `Role.TOOL` 在文本协议下没有配对的
  `tool_call`，多数 chat template 判非法。文本前缀是唯一**不动 native 播种口径**的做法。
- 为什么这在本波更紧迫：bbfa3c9 角色通道修复后，这些提醒不再被压进同一条 user 纯文本，而是
  **各自独立的 user turn** ⇒ 语义权重比旧压平路径**更高**，小模型更容易当真实用户回应。

---

## 3. 明确 defer：#5 流式跟随手势竞争（附论证）

**不做的理由**：报告给的方向是「手势拖动中暂停程序滚动」，唯一可用的公开判据是
`LazyListState.isScrollInProgress`。但该标志**对程序自身动画同样为 true**，而本仓的流式跟随
正是用 `animateScrollToItem` 实现的 —— 门控写成 `!isScrollInProgress` 有「跟随永久停摆」的
风险（每条 tick 都撞上上一帧动画的 in-progress），而**本仓无本地 JDK、无真机**，这个回归
CI 查不出来、只能靠用户肉眼发现。

同时排除了「收紧 `atBottom` 阈值」这条看似更安全的路：把判据改成「末条完整可见」
（`last.offset + last.size <= viewportEnd`）会在**长气泡超出视口**时恒为 false（流式回答
超过一屏是常态）⇒ 跟随中途直接断掉。现有 `- 2` 的松弛正是为这条路径留的。

**结论**：需要真机 + 逐帧观感才能定方案（候选：① 记录「本次滚动是否由我方发起」的标志位，
仅对手势拖动让位；② 手势拖动中改用 `scrollToItem` 瞬时对齐而非动画）。已列入真机清单。

---

## 4. 真机验收清单（CI 查不出，必须人工）

1. **P0 遗留（Wave 24）**：日志出现 `角色通道播种失败…已回退 legacy` ⇒ 该模型的 chat template 不吃 role 通道，bbfa3c9 对它失效。
2. **本波 #1 验收**：故意触发一次轮内重复（长会话 + 小模型），确认气泡**不再**出现「乱码 + 新回答」叠加，而是清空后重新流式。
3. **本波 #4 验收**：日志/journal 里提醒文本应带 `[系统提醒] ` 前缀；观察模型是否不再把提醒当用户寒暄回应。
4. **Wave 24 遗留两条 P1**：记忆段变化导致的会话重建频率；MODEL 正文为空被过滤时可能出现的相邻两条 user。
5. **#5 手势竞争**：真机复现「轻微上滑仍被拽回」的抖动，确认是否值得单独派单。

---

## 5. CI 验收（`4b910e4`，双绿一次通过）

| Run | 结论 | 时长 | 产物 |
|---|---|---|---|
| Build `36304241154` | **success** | — | `liquidagent-debug-4b910e4…` 39.20 MB（30 天） |
| Release `36304241269` | **success** | — | `liquidagent-release-apk-harness-improve` **70.0 MB** / `-mapping-` 4.22 MB / `-debug-` 39.20 MB |

- `unit-tests` job 随 Build 一起绿（`:core-model:testDebugUnitTest` 24 用例继续实跑）。
- Release 侧 16KB ELF 对齐断言 / APK 验签 / AAB 验签 / arch-guard 全部 success。
- 本地静态闸门先行：`scripts/arch-guard.sh` 10/10、`balance_check.py` 四个改动文件全配平。
- 编译面风险点已验证：`AgentEvent` 是 sealed ⇒ 新增 `StreamReset` 必须同步所有 `when`
  （`ChatViewModel` 无 `else`，已补分支；`AskSubagentTool:198` 有 `else`，不受影响）；
  `const val A = "x"; const val B = A + "y"` 的常量拼接合法。
- ⚠️ 本节（CI 验收结果）随 `3977336` **留在本地**，按惯例随下波代码一起推 —— `build.yml` 无
  paths 过滤，单独推 docs 会白跑一轮全量 CI。代码与 handoff 正文已在 `4b910e4` 上远端。

## 6. 挂账（未动，非本波范围）
- **THIN 0.21 底色裁决**（Wave 21 明确留给用户，唯一半条挂账）。
- **判定实验重设计**：输入侧基线已被 bbfa3c9 改变，原实验前提失效，需在 70195d8+ 上重设计（强度档 1.0 锁定 + 角色通道生效确认双前提）。
- **路径 B 列表优化**（滑动降级 / 源指纹跳录 / 参数不变跳重建）：待真机帧时间实测结果决定是否派单。
- **A1 材质亮度自适应 / U3·A5 无障碍联动**：Wave 24 已给出不可行性论证（AGSL 无法回读标量；Android 无 Reduce Transparency 对应系统设置），**勿重复调研**。
- **原生工具通道**（`Capabilities.supportsFunctionCalling()` + `ConversationConfig.tools`）：回显问题的终解，独立波次。
