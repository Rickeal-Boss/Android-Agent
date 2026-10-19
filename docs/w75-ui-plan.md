# W75 UI 全方位改进执行方案（落库版）

> **建立**：W75（2026-10-19），内核收尾验证通过后立。
> **解决**：W-1「UI 波执行清单完全在仓外」——同型第三次（W72 七格 harness / W73 驱动脚本 / 本次 UI 计划）。**本方案入仓为 `docs/w75-ui-plan.md`，作为 UI 波单一事实源**（不再依赖仓外 `_plans/`）。
> **冻结前提**：内核运行面已冻结（见 `docs/handoff-20261019-wave75-kernel-finalization.md`）。UI 波内核触点走白名单（定版冻结报告 §3.4 三件预授权）；白名单外内核改动一律走 P0 例外程序。
> **来源**：四份内核终审文档（定版冻结合并版 / 复审28 莫斯线 / 转UI前收尾 / FINAL-AGGREGATE）+ `docs/15-backlog.md`。

---

## 0. 输入全景（内核留给 UI 的资产）

内核交付的不是黑盒，而是稳定契约面（定版冻结报告 §5）：

| 契约面 | 载体 | UI 可直接消费的资产 |
|---|---|---|
| 预设证据数据 | `ModelPreset` | `knownBrokenOn` / `evidenceLevel`（14 MEASURED）/ `templateArraySafety`（10/2/0/2）/ `requiredRamBytes`+`memBasis` / `appliesToRuntime` —— **徽章/红字/内存提示/版本限定全在锅里，只欠渲染** |
| 事件流 | `AgentEvent` sealed 族 | 工具轨迹折叠组 / 审批卡 / 计划时间线 / 恢复卡 |
| 观测流 | `engineInitStatus` + `sessionDiagnostics` + `activeModelKnownBroken` + `probePresentation` | 引擎状态 / knownBroken 提示 / 探针卡（含免责行），全为「纯函数 + 流」形态 |
| 数据面 | `ConversationRepository` / `ModelRepository` / DataStore | 删除 UI 已上线；**U3 DataStore 失败面是 UI 波第一批** |
| 人格 | `PersonaPresets` | 3 档 + 逐字派生选中态 + 自定义逃生阀（加档 = 追加枚举项，零架构改动） |

---

## 1. 待用户裁决（U1–U5）

重建自内核终审的「等用户裁决 / 用户议程」项（原件未落盘，本方案重建并标注来源）。**UI 波在 U1–U5 未裁决前，不代裁、不据假设动工；涉及 toml/资源文案的产品决策一律等裁决**。

| ID | 裁决项 | 来源 | 影响面 |
|---|---|---|---|
| **U1** | evidenceLevel 徽章形态 + **是否参与可信度排序**（声明「不参与」先行） | W68-03 / docs/14 §D | UI-01 的呈现口径与排序语义 |
| **U2** | bump litertlm `0.18.0` 决策（收益实测 GPU init 修复 + MiniCPM-V-4 type 13 修复；代价实测 锚#1 P0 2/14 + 锚#15 `Capabilities` 删除 + E2B·GPU 输出退化未修） | W68-06 / C6 / docs/12 | 全仓依赖版本；C 不裁决前不动 `libs.versions.toml` |
| **U3** | 法务 `termsVersion` 正式文本 + 托管地址 → 首个 tag | W68-08 C1/C2 | 发版拓扑；文本未到前 FF 无收益 |
| **U4** | 人格加档（现有 3 档；是否加档、加几档） | W68-03 UI-03 / W69-07 | 枚举扩展面 |
| **U5** | U3 持久化失败面 UI 形态裁决：读路径 Corruption→静默 empty prefs（设置无声全失）/ 写路径→崩溃循环（P0 既遂前科） | 定版冻结报告 §3 条件 2 | `core-data` 属 UI 波合法改动面，不违内核冻结 |

---

## 2. UI 波 backlog（UI-01 … UI-06，源自「转UI前收尾检查 §二」）

> 此六条即 W-1 收尾检查建议抄入 `docs/15` 的清单；本方案立为权威，并同步在 `docs/15` 建「UI 波候选」段（单一事实源）。

| ID | 条目 | 约束 | 来源 |
|---|---|---|---|
| UI-01 | `evidenceLevel` 徽章（模型卡三态 + 口径 tooltip） | 受 U1 约束 | W68-03 |
| UI-02 | `templateArraySafety` 模型卡展示（10/2/0/2 四态） | — | W68-03 衍生 |
| UI-03 | 人格加档 UI（选择器扩展） | 受 U4 约束 | W68-03 UI-03 |
| UI-04 | 音频能力位下游（B3 虚高已排除，仅展示） | — | W68-03 衍生 |
| **UI-05** | **批量工具调用 0 执行的可见提示**（W73/W74 两波提了、台账零命中，#42 单向盲区又吃一条） | 纯 UI 可见性缺口 | W-5④ / 转UI前收尾 §六 |
| UI-06 | W69-12 / W69-13（工具超时孤儿写 / indeterminate 记账）可见性 | 草案在 KDoc，parked | W69-12/13 |

> **UI-05 最急**：它是纯 UI 可见性缺口、UI 波最该做的一条，却被 #42 的单向盲区（只查「交接 ID ⊆ 台账」，测不到反向）又吃掉一条。落地即补 `docs/15` 台账登记（解决反向盲区）。

---

## 3. UI 波开工前小修（各 ≤20 行，来自定版冻结报告 P1 + 转UI前收尾 W-2/W-3/W-4）

这些**就在要动的 UI 文件里**，建议 UI 波开工第一件事批量落地：

| 项 | 位置 | 修法 |
|---|---|---|
| **P1-3** 设置页逐键全量落盘 | `SettingsScreen.kt:488` `onValueChange`→`onConfigChange` | 每按键 = 一次 `coerce()` + 一次 DataStore 事务 + 一次 Flow 发射 ⇒ 改 preview/commit 配对（同仓 `ChatViewModel` 已有 `onParamPreview`/`onParamCommit` 先例）或 debounce |
| **P1-5** 下载取消轮询协程不死 | `ModelsViewModel.kt:653` `onCancelDownload` | 不取消轮询 Job（未持引用）⇒ 取消后下次 `progress()` 查无此行返回 `STATUS_SUCCESSFUL` ⇒ 弹「下载完成但登记失败」+ 同名兜底把旧文件登记为模型并 setActiveModel。修：轮询首行检查取消标志即 return，或持 Job 引用取消 |
| **UI-05** 探针 UI 无 finally 复位 | 探针 UI | 异常后 Running 恒锁 ⇒ 加 finally 复位 |
| **UI-06** Gemma 授权拒绝不清一次性确认标志 | 授权流 | 拒绝时清一次性确认标志 |
| **UI-07** 对话页系统提示词只有 preview 无 commit | 对话页 | 重启回退 ⇒ 补 commit（同 W71 人格档位范式） |
| **W-2** 改采样参数 = 会话复位，UI 零披露 | `ChatParamsPanel` | `resetSessionForResample()`：temperature/topK/topP 任一变化 = 上下文从零开始。面板加披露「改采样参数将重置当前会话上下文」（比人格档位的 re-prefill 更重） |
| **W-3** 滑条显示值 ≠ 生效值 | `ChatParamsPanel` ↔ `ModelSamplingProfiles.appliedTo()` | 上下文滑条开到 32768，但 `minOf(..., maxContextLength)` 压回 8192；`maxTokens` 低于 `minMaxTokens` 被抬高。内核已记「模型采样档案生效」日志，UI 没消费 ⇒ 钳制时显示「实际生效 XXX（受模型档案限制）」或 `valueRange` 按档案动态收紧 |
| **W-4** `GlassSwitch` 无 label（无障碍硬伤，跨 70+ 波） | `GlassSwitch.kt` | 加 `label: String?` 参数 + `semantics { contentDescription = label }`，逐个调用点（`SettingsScreen.kt:217/240/300`）补名 |

---

## 4. UI 波 P2 群（随波，行为变更需真机）

| 项 | 来源 | 说明 |
|---|---|---|
| F1 token 增量记账同 id 内容可变漏记 | 定版冻结报告 §4 | sanitize 补丁聚合面 |
| F3 pendingReminder 单槽双注入口径不对称 | §4 | 签名提醒被覆盖 ⇒ 永不提醒 ⇒ 下次复读判 Failed |
| F6 toolCallsEmitted 布尔闩锁吞 native 分帧多 tool_call 第二帧 | §4 | — |
| F5 模态降级重建失败路径 degradedModality 诊断面回退 | §4 | — |
| UI-08 状态条陈旧占用告警 | §4 | — |
| UI-09 onRetry 裁剪后二次拒绝不可回滚 | §4 | — |
| UI-10 SAF 导入乐观激活双写竞争 | §4 | — |
| UI-11 `GlassSlider` commitValue 语义扩面 | §5 | — |
| UI-12 `onParamChange` 死重载 coerce 不一致 | §5 | — |
| UI-13 `AppContainer` 主线程全量构造 | §5 | — |
| i18n 171 处硬编码（24 文件） | 转UI前收尾 §5 / FINAL-AGGREGATE | UI 波必办，远期排 |

---

## 5. 守卫约束（UI 波会撞，转段前先记）

| 守卫 | 约束 | 注意 |
|---|---|---|
| **#20** | `ChatRunCoordinator.kt ≤ 1300`（**余 55**） | UI 波主力文件，**最可能先触顶**；触顶动作已入仓 `docs/16` |
| **#35** | lint baseline ≤ 4（只许清障不许新增豁免） | ⚠️ **UI 加新组件/新 API 极易触发**（NewApi、Padding 类）；**建议 UI 第一波就跑 lint 看增量，别攒到收官** |
| #18 | `ChatViewModel.kt ≤ 1600`（余 905） | 宽松 |
| #16 | ViewModel 直构造点全仓 ≤ 1 | 新增 ViewModel 会撞 |
| #17 | `AgentLogStore.setSink(` 全仓 ≤ 1 | UI 加日志出口会撞 |
| #12 | `executeBodyUnchecked ≤ 550`（余 48） | 内核冻结后基本不动 |
| #40 | core-model 禁出现 `feature-models` 字样 | 跨模块搬代码会撞 |

---

## 6. 推荐执行序（第一批 → 主线 → 远期）

| 批次 | 内容 | 量级 |
|---|---|---|
| **UI 波开工前** | P1-3 逐键落盘 / P1-5 下载取消 / UI-05 探针 finally / UI-07 preview 补 commit / UI-06 授权标志清理 / W-4 GlassSwitch label | 各 ≤20 行 |
| **随 W74-03 工具面** | P1-2 validate 按 tag 核长（~25 行）+ F-02 死属性降级清理 + Y5-a KDoc | 半天 |
| **UI 波内（行为变更需真机）** | P2 群：F1 记账 / F3 单槽 / F6 帧去重 / F5 降级落回 / UI-08~10；+ W-2 采样复位披露 / W-3 滑条生效回显 / UI-01..06 | 随波 |
| **用户触点（U1–U5）** | C6 bump（U2）/ termsVersion→tag（U3）/ 人格加档（U4）/ evidenceLevel 排序（U1）/ U3 持久化失败面（U5） | 等裁决，20 分钟会 |
| **远期** | i18n（171 处硬编码）/ `TokenUsage.estimated` / 接缝②③ / knownBroken 三容器上游 | 排队 |

---

## 7. 冻结哨兵（UI 波期间照转，不休眠）

- 季扫三轴：bump tag / #3576 + gallery #1326 / 回归 issue 状态
- LOSSY 条款（W72-01：上游动 MiniCPM5 模板必重跑七格 harness）
- N-2 warn + docs/14 §D 自动收敛条款（N=3）
- #26/#27/#42 机械面照常（含 UI-05 落地的反向盲区补登）

---

*本方案为 UI 波单一事实源；交接只写 delta（销账/新增/顺延 + 判据），不整表重抄。U1–U5 裁决后回填 §1。*
