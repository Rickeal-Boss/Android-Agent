# W58 波次收官交接（Wave 58 Closeout）

> 基线：W57 收官 `c6b1bdd`（W57 共 4 commit）｜本波 tip = **推送后回填**
> 前置文档：`docs/handoff-20261009-wave57-closeout.md`（W57 逐项记录，§六 = 本波挂账来源）
> 方案：`_plans/w58-phase1-strategy.md`（仓外，方案席沈思远）｜审查：`_plans/w58-review.md`（仓外，审查席严质衡）｜真机取证：`_litert_forensics/_w58_smoke/`（仓外）

---

## 一、一句话 + 关键现实

**W58 = 三线审查融合修补波：守卫 #29（litertlm 版本钉死，治深度审查 F-1 P0）+ 韧性 store 语义补全（修补 A 第二写点 / 修补 B 用户复位路径 / 修补 C bump 前置⑤ / 修补 D 收尾四件套单源化）+ Stage-2 拆分预案落档 + 一行件全捎 + W58 构建真机冒烟（含连续两轮文本）。**

关键现实（动手前必读）：
1. **本波全部改动离线可完成**，无新容器、无新引擎行为面扩张——是「机制先行、语义后补全」的补全波。引擎行数 **2345/2400（余 55）**，比方案预估（≈2317）多 17 行（注释/KDoc 按本仓密度写实），**Stage-2 触发线更近**（见 §四）。
2. **守卫 #29 是 F-1（P0）的最后一块缺口**：此前 litertlm 版本变更在 28 项守卫 + lint + 全量单测下**全绿**，而 v0.18.0 已删收敛点 ⇒ fold 静默失效 ⇒ Qwen2.5 系**每轮必炸**。#29 把 bump 从「改一行 toml 静默发生」变成「必须同时改守卫并复核四前置+前置⑤」。
3. **修补 A/B 的口径**：A = persist 第二写点（守卫 #28 判据② `==1 → ==2`）；B = 开关 OFF→ON 跳变复位（同实例、`lastSeen=null` 不复位保 evict 防护）。二者共同把 store 从「模板失败专用」补全为其目的陈述所声称的完整语义。**均非用户可见故障修复**（潜伏级敞口）。
4. 🔴 **本波新踩的取证坑（跨波复用）**：OPPO A13 充电态会频繁休眠 + UI tap 不稳定（Compose bounds dump 坐标与实际点击面漂移）⇒ **多步 UI 自动化剧本必须每步落盘判据后立刻行使下一步**，跨多命令的序列极易被休眠打断；`uiautomator` 语义树里 Compose 节点 `clickable=false` 且祖先 bounds 可能陈旧，**点按判据以 journal/日志落盘为准，不以 dump 坐标为准**。

---

## 二、改动清单（5 commit；docs 回填另计）

| commit | 性质 | 内容 |
|---|---|---|
| `bf0193e` | fix(engine) | **修补 A**：`ensureConversation` 成功路径（cid 赋值后，:1205）补 `persistResilienceToStore()` 第二写点；KDoc 如实化两写点纪律、订正「快照能带走探针/legacy 证伪」过度声称（残留边界如实申报：建会话最终抛错则不带 store，与改前一致）；守卫 #28 判据② persist `==1→==2`（守卫名同步）；selftest fixture 补写点① + 新 case40/40b（只删建会话侧 persist 判红，断言「调用数(1) != 2」） |
| `fbb278b` | feat(engine) | **修补 B**：`EngineResilienceStore` 增 `clear/clearAll` 接口；纯函数 `shouldResetResilienceOnSwitchFlip(lastSeen, current)` 外提（null 不判翻转，保 evict 防护）；引擎 `lastSeenSwitchOn` 字段（`releaseInternal` 复位 null）+ `generateStream` **入口复位块**（:1399-1408，熔断闸门**之前**、同步/异步共同必经点：flip ⇒ `nativeToolsRejected=false` + `templateRebuildCount=0` + `clearAll()` + warn）；单测 **+6**（SwitchResetTest 4 例真调纯函数 + StoreTest clear/clearAll 2 例）；`build.yml` baseline **635→641**（#26 双向同步）；`ConversationRepository.delete` 清键钩子（**KDoc 申报「零生产调用点，预埋卫生位」**——亲核属实）+ AppContainer store 上移至 conversationRepository 之前注入 |
| `76b49c0` | refactor(engine) | **修补 D**：`generationCleanup()` 局部函数单源化（catch/finally 各一行调用；互斥穷尽论证写入声明处注释：同步抛出 ⇒ collect/finally 不可达、正常路径 catch 不可达、onError 是另一入口 ⇒ 行为逐字节等价）；**修补 C**：fold 出口 bump 重启清单追加**前置⑤**「bump 合入前设临时 K=3（W56 Arm B 实测 1 次收敛 ×3 余量）」——**不拍值生效**，只钉拍值时机，消除「K 等 bump 数据 / bump 等 K」循环依赖 |
| `2ed92ef` | test(guard) | **守卫 #29**「litertlm 版本必须为 0.17.1」：`^litertlm = ` 行首锚取值钉死；文件缺失 / 提不出版本行均 exit 1 判红（fail-closed，沿 #26 范式）；红输出含 F-1 后果摘要（v0.18.0 收敛点已删 ⇒ fold 失效 ⇒ Qwen2.5 系每轮必炸）+ 四前置 + 前置⑤提示。selftest **+5**（case39/39b/39c/39d/39e：版本改红 / 原样绿 / 文件缺失红 / 缺失真命中 / `[libraries]` 相似行 + 注释提及不误伤） |
| `2ef57c5` | docs | **一行件**：`ModelPresets` SmolVLM2-500M(:310) / LFM2.5-VL-1.6B fixB(:338) 回写「✅ 端到端图片输入已真机验证（W57 L3）」口径（:323/:352 两未验容器**未动**）；README:342 补「（W55 时点口径，现行见台账聚合句）」；README:361 W55 段 H-A 行尾补双源复核句 |

⇒ **@Test 实数 = 641**（W58 +6 例），`build.yml` baseline 同步 635 → 641。**守卫 28 → 29 项，selftest PASS 70 → 77**（case39×5 + case40×2 = +7？——**正账：selftest 用例数 +7，PASS=77**）。

⚠️ 行数预算核算：引擎 2291 → **2345**（+54；方案预估 +26，A/B/D 注释与 KDoc 权重比估算重），余量 **55**。`ChatRunCoordinator` 1278 未动。

---

## 三、真机验证（本波判据）

### 3.1 ✅ 台账 #43：W58 构建真机冒烟（含连续两轮文本）

- APK = W58 工作树构建；**dex 实测含 4 个新符号**（`shouldResetResilienceOnSwitchFlip` / `mergeResilienceState` / `generationCleanup` / `clearAll`）——沿用 W57 符号完整性抽查法。
- `adb push` → `pm install -r` Success（OPPO A13 剧本；`MSYS_NO_PATHCONV=1` + 本地路径写 `D:/...`）。
- 冷启动 `FATAL EXCEPTION` = **0**（全量档 `w58_smoke_full.log` 已落档 `_litert_forensics/_w58_smoke/`）。
- **文本 run 第一轮**：`Reply one word: OK` → 回复 **`OK`**（in 587 / out 1 · 首字 8214ms）→ `settled=ModelStopped`；journal 新目录（68 → 69）。
- **文本 run 第二轮（D 项回归探针）**：`Reply one word: Hi` → 回复 **`Hi`**（in 594 / out 1 · 首字 1195ms，热引擎）→ `settled=ModelStopped`。同会话第二份 `run_*.jsonl`（journal 目录按会话键控，**新目录判据只适用于首轮**）⇒ **连续两轮行使同一实例的 `generationCleanup()` 收尾路径**，`activeGenerations` 漏减回归无复发（第二轮未被「上一次生成仍在继续」拦截）。
- `Failed to apply template` = **0**（native/引擎相关行 **82** 条先验在档 ⇒ 「0 命中」有意义）。

### 3.2 ⚠️ 如实申报：修补 B 复位路径真机行使未完成

- 已完成面：开关 UI 定位与切换**可靠**（`原生工具通道` 开关 `checked` 态经 dump 两次确认 OFF→ON 成功）；复位块入口代码每轮 run 均被行使（读 `lastSeenSwitchOn` + 写回，未触发 flip 分支）。
- 未完成面：**OFF→ON 翻转 + 随后 run 触发复位 warn** 的端到端链未跑通——第三轮发送 tap 多点位（(942,1356)/(942,1310)/(942,1968)）均未产生新 `run_*.jsonl`，疑因设备充电态休眠干扰 + Compose 语义树 bounds 陈旧（见 §一.4）。
- **补偿覆盖**：`SwitchResetTest` 4 例 JVM 真调纯函数（四象限全钉，非 vacuous）；**设备开关已复原 ON**（用户设置无损）。
- **挂 W59**：翻转复位真机行使（建议先把「发送 tap → journal 落盘」写成单命令原子步骤 + `svc` 保屏替代方案）。

### 3.3 未覆盖（如实申报）

- 剩 `Qwen2-VL-2B` / `LFM2.5-VL-3B fixB` 两容器端到端**未验**（挂 W59，剧本复用 `_w57_l3`）。
- bump 上游 tag 季扫（本波复查）：最新仍 **v0.18.0**（`b2f686e2e`），无新 tag ⇒ 前置④仍红，bump 挂起维持。

---

## 四、Stage-2 拆分预案（已落档，触顶即照单执行）

- **首选 = 候选 2「加载/后端尝试集群」**（`load`/`loadLocked`/GPU 两段尝试/EngineAttempt，~250 行）——与守卫 #24/#28 扫描面**零交集**，最安全。
- 候选 1「播种/水印集群」（~200 行）：**必须同 commit 改 #24 扫描文件清单**（`Message.user` 下发点实测 **4 处**：:1631/:1952/:2007/:2031-2032——第五审报告写 3 处系行号过期）并补 selftest case。
- 候选 3 韧性集群（~90 行）：被 #28 按文件名钉死，收益最低，**不建议**。
- 触发口径：守卫 #25 语义 = 触顶**启动拆分评审、非改阈值**；本波后余量 55 < 波均增量（~120）⇒ **W59 任何引擎行为面新增前必须先执行候选 2**。
- 全文见 `_plans/w58-phase1-strategy.md` §四（仓外；设计件不入仓沿 wave44-57 先例，行号锚入仓即 stale）。

---

## 五、外部审查对账

- 审查席报告（`code-quality-reviewer`）：**结论 = ✅ 通过（0 P1 / 0 P2 / 7 P3）**，独立复跑亲核（arch-guard 29 OK rc=0 / @Test 口径 641 == baseline / 引擎 2345 ≤ 2400 / Coordinator 未动 / 工作树干净）。
- 关键核验（回源码）：修补 A 写点时序安全（adopt 首行在前、两路证伪置位均在 persist 之前、单调合并保证回写不拉退）；修补 D 互斥穷尽成立；修补 B 复位块在 flow 首块、先于熔断闸门与 ensureConversation、lastSeen 检查/赋值间无挂起点；6 例新单测全真调非 vacuous；守卫 #29 两处 fail-closed。
- **P3×7（不阻塞，处置挂账）**：
  1. `clearAll` 会连带清同进程**其他 cid** 的证伪/计数，store KDoc 只申报了跨实例/重启场景 ⇒ **W59 补 KDoc 连带面一句**（本波以交接申报代偿）；
  2. OFF→ON 之间若无 run（连续翻转）则不复位——设计内边界，挂账即可；
  3. 引擎净增 +54 超方案预估，余量 55（本档 §二 已写实测数，Stage-2 触发线更近）；
  4. `ModelPresets` 引「台账 #41」但同族臂实为 #42，引用略欠精确（下次 docs 触碰时改）；
  5. README:342 补注与方案字串微差、语义等价（留痕）；
  6. 复位块 vs 迟到 onError 的理论并发窗口——保守方向（恢复防护原状），现架构 isBusy 串行下面极小，观察；
  7. `clearAll` vs 在飞写点② 的理论并发窗口——同上，观察。
- 方案席两处亲核纠偏（纪律正面）：`Message.user` 下发点实测 4 处（报告写 3 处）；`ModelPresets` 标注实测 :310（报告写 :311）。另：方案文档自带的 README:342 措辞会被守卫 #27 误选首行判红（方案自相矛盾处），开发席改用主理人任务书口径后 #27 复绿——**守卫红面反推方案措辞**是一次有效的机械闸门行使。

---

## 六、挂账（W59 起；权威版见本节 + README）

1. **多模态剩 2 容器端到端**：`Qwen2-VL-2B`（异族）/ `LFM2.5-VL-3B fixB`（同族最贵）；剧本 = `_w57_l3` 前置链 + 0–10 判据。
2. **修补 B 翻转复位真机行使**（本波 §3.2 未完成面；先修「发送 tap」剧本稳定性）。
3. **bump 重启监视**：上游 tag 季扫（本波复查仍 v0.18.0）＋ litert-community 重转换件双轴（第五审修补 F）。
4. **K 回填**：前置⑤已落码（bump 合入前设临时 K=3）；分布数据仍待 bump 场景或现有剧本灌 N 次（复审 17 依赖链解绑口径）。
5. **P3 清单**：store KDoc 补 `clearAll` 跨 cid 连带面（P3#1）｜`ModelPresets` #41→#42 引用精确化（P3#4）｜复位/清并发窗口观察（P3#6/#7）｜P3#2 边界挂账。
6. 其余沿 W57 §六.5：N-W3 `evidenceLevel` 数据化 + 图片入口硬闸 ｜ `DeepSeek-R1` 误判（HF 直链通道已通，可解除阻塞）｜ `TokenUsage.estimated` 治根 ｜ 通知文案「渠道」｜ 法务 `termsVersion`（先于法务文本）｜ **0 tags** ｜ main 快照过时 ｜ `ChatRunCoordinator` 余量 22（有功能落它才拆）｜ F4 文字 token 统一 ｜ `fulltest.sh` 接进 CI 运行步。

---

## 七、下次接手须知

1. **静态闸门基线（本波后）**：`arch-guard.sh` **29 项**（新增 #29）；`arch-guard-selftest.sh` **PASS=77 FAIL=0**（+7：case39/39b/39c/39d/39e + case40/40b；本机实测 ~39 分钟，**比 W57 的 22-24 分钟更长，给 ≥600s 或 run_in_background**）；`fulltest.sh` = **tests=641 failures=1**（唯一失败 = core-data `SandboxFileScannerTest` Windows 符号链接，**既有基线**；判据 = 无新增失败 + 模块数 9 + XML mtime 覆盖本轮改动模块）；`LiteRtLmEngine.kt` = **2345 行**（阈值 2400，**余量 55——W59 引擎行为面新增前先执行 Stage-2 候选 2**）。
2. 🔴 全量单测必须注入 `JAVA_HOME`/`ANDROID_HOME`/`GRADLE_USER_HOME` 并核对模块数 = 9；gradle 的 `N tests completed` 残缺口径不可信。
3. 🔴 守卫必须在**仓库根目录**跑：从工作区根跑会扫到 `.recon-ext`/`_ci-tools` 等仓外目录全红（本波实证）；输出为空 + SIGTERM 是沙箱执行姿势问题，**重定向文件再读**即正常。
4. 🔴 adb：`MSYS_NO_PATHCONV=1` + push 本地路径写 `D:/...`；读二进制 `exec-out`；设备侧 `nohup logcat -f` 落盘；**串行执行**；充电态设备易休眠，多步 UI 剧本每步落判据（§一.4）。
5. 🔴 journal 目录按**会话**键控：「新目录判据」只适用首轮；同会话后续轮看**新 `run_*.jsonl`**。

---

## 八、CI run id

> **推送后回填占位**（W52 教训：run-id 回填须在推送后立即回填）。docs-only 提交预期 0 run。

| workflow | run id | 结论 | 关键 job |
|---|---|---|---|
| **Build** | _待回填_ | — | — |
| **Release** | _待回填_ | — | — |

---

## 九、对账 checklist（按复审 17 终稿建议逐条；协作纪律 ⑩ 首次行使）

| # | 复审 17 建议 | W58 处置 | 态 |
|---|---|---|---|
| 1 | god-file 拆分评审提前启动 | Stage-2 预案落档（候选 2 首选 + 候选 1 守卫联动成本写明）+ 本档 §四 触发口径 | ✅ |
| 2 | 熔断 K 论证件（先机制后数值）+ onError 幂等防御同波 | 前置⑤落码（K=3 拍值时机钉死）；**onError 幂等防御未做**——复审 17 建议它为 K 生效前置，但 K 本波仍不生效（Int.MAX_VALUE），防御可与 K 回填同窗口落 ⇒ 挂 W59，**不得**在 K 生效前遗漏 | ⚠️ 部分 |
| 3 | bump 判据重审清单前置挂账（第五前置：v0.17.1 锚定面重锚） | 前置⑤已落码 fold 出口注释区 + 守卫 #29 红输出提示；**第五条（v0.17.1 行为锚定面清单）在 #29 红输出中以「四前置+前置⑤」概括提及，逐项清单仍未成文** ⇒ 并入 bump 重启清单 W59 成文 | ⚠️ 部分 |
| 4 | preset 标注即时回写 | `:310/:338` 已回写（:323/:352 未验不动） | ✅ |
| 5 | README:342 时间限定词 | 已补（措辞经守卫 #27 红面校准） | ✅ |
| 6 | onError 幂等防御 | 见 #2，挂 W59 | ⚠️ 部分 |
| 7 | 双源复核入档 | README:361 H-A 行尾已补 | ✅ |
| 8 | store 复合键迁移评估 | 路线图仍无「会话内换模型」UI 计划 ⇒ 按建议走「申报升级守卫」分支——**未落守卫**（改动面让位 #29），挂 W59 评估 | ⚠️ 部分 |
| 9 | 收尾四件套单一化 | `generationCleanup()` 单源化 + 真机两轮回归 | ✅ |
| 10 | 三线出件对账 checklist | 本节即首次行使 | ✅ |
