# W62 波次收官交接（Wave 62 Closeout）

> 基线：W61 收官 `de3144e`｜本波 tip = `（收官刀后回填）`（3 刀代码/测试 + 1 刀收官交接）
> 前置文档：`docs/handoff-20261011-wave61-closeout.md`（§五 = W62 挂账来源）
> 方案：`_plans/w62-strategy.md`（仓外，方案席沈思远）｜主理人取证：`_plans/w62-lead-recon.md`（含决定性 A/B）
> 真机取证：`_w62_dev/`（用户会话 journal ×6 + 会话原文含 thinking 全文）+ `_w61_dev/results/W62Before_run.jsonl`（改前对照）

---

## 一、一句话 + 关键现实

**W62 = 误判修复波**：把「gemma 图片问答答非所问的道歉」定案为**检测器把合法长推理误判为循环**（`THINKING_CHAR_BUDGET = 2048` 字符口径、阈值是刀锋），并以 **A 语义区分（思考预算 ≠ 重复循环）+ B 阈值抬高（2048 → 4096）** 修复；让 native「预算耗尽 → 强制吐思考结束符 → 转回正文生成」的**优雅路径**重新生效。

关键现实（动手前必读）：
1. 🔴 **触发判据唯一**：`StreamRepetitionDetector.kt` 的 `THINKING_CHAR_BUDGET`（**字符**口径，非 token）。逐条排除法证明其余 8 条判据命中时 `thinklen` 都会 <2048，实测 2051/2052 ⇒ 只可能是它。
2. 🔴 **决定性 A/B**（同设备/同模型/同构建/同 `ThinkingMode=ON`）：`thinklen=2010`（<2048）**正常作答**；`thinklen=2051/2052`（>2048）**误判循环、用户看不到答案**。⇒ **阈值是刀锋**，合法推理长度横跨它。
3. 🔴 **`finishReason=LENGTH` 100% 来自检测器人工标记**（全仓唯一赋值 `AgentRunner.kt` 的 handlePostStreamSignals 内；引擎侧只发 STOP）——**不得**据此反推 native 行为。
4. 🔴 **native 路径本来是优雅的**：`thinking_budget_constraint.h:31-35`（0.17.1 源码）——思考 token 预算耗尽时**强制吐思考结束符并转回正文生成**。原检测器以「硬截 + 误述循环」抢先约 2%，把这条路径抢掉了。
5. 🔴 **提醒文案曾单一口径**：`INTRA_LOOP_REMINDER`（「你刚才的输出陷入重复循环…」）对**所有** verdict 共用——判据侧有 marker 区分，**文案侧没有** ⇒ 模型据此道歉。
6. ⚠️ **`ThinkingMode.ON` 只是暴露条件，非缺陷本体**：gemma-4 能力位 `thinking=false`（保守误设——真机实证它确有 `<|channel>thought` 通道）+ 用户设 ON ⇒ 思考被强制打开。**改能力位/限制 ON 都不修症状**（思考仍跑、仍撞 2048），本波不动；能力位订正**另立项**（需 E2B/E4B 双变体取证）。

---

## 二、改动清单（4 刀）

| commit | 性质 | 内容 |
|---|---|---|
| `56fddc4` | `fix(model)` | **`THINKING_CHAR_BUDGET` 2048 → 4096**。取值依据（禁无据拍值）：native 思考预算 **token** 计 = `resolveThinkingBudget(0, 1024) = 512`；英语 ≈4 chars/token ⇒ 512 token ≈ 2048 字符；取 **2× 余量 = 4096**（≈1024 token 英语 / ≈2048 token 中文）＝**全量 maxTokens 的英语字符当量**——超它才算真 runaway（此时 native 必已失效）。**回填判据**：真机若仍见「正文为空 + 循环提醒」⇒ 按实测 chars/token 上调。KDoc 同步改写（原 KDoc 自陈「英文推理模型若被过早截断可上调」——作者已预见本缺陷但从未回填）。判定③单测同步改大（1100×2=2200 → 2050×2=4100）。 |
| `a9be643` | `fix(agent)` | **语义区分**：`GenerationOutcome.intraStreamLoop: Boolean` → `loopMarker: String?`（null=未截断，core-agent 内部数据类不跨模块）；catch 处携带 `loop.signature`；注入点按 marker 分流：`THINKING_BUDGET_MARKER` ⇒ 新增 `THINKING_BUDGET_REMINDER`（「你的思考过程过长，已到上限被截断。请直接给出最终答案，不要再展开推理。」），否则 ⇒ `INTRA_LOOP_REMINDER`。日志补 marker。**不动** `MAX_INTRA_STREAM_LOOP_ROUNDS(=2)` 收尾语义、**不搬** `EMPTY_ANSWER_NUDGE`（语义重叠）、**不动**引擎/能力位。 |
| `81e059b` | `test(agent)` | 分流逻辑外提为文件级 `internal fun intraLoopReminderFor(marker: String?)`（单一落点，避免「口径分叉」历史坑）+ `IntraLoopReminderTest` **3 例**（思考预算 marker 命中专用文案且**不含**「重复循环」字样；真重复 marker 走通用文案；null 兜底）。**`build.yml` baseline 652 → 655**（同 commit 双向，守卫 #26）。 |
| （本刀） | `docs` | 收官交接 + README 波次段。 |

⇒ `@Test` 实数 = **655** = baseline；守卫 **33 项 / 0 FAIL**；`AgentRunner.kt` 2802 → **2843**（#31 ≤2900，余 57）；`executeBodyUnchecked` **未触碰**（#12 OK）。

---

## 三、真机复验（本波判据）

设备 OPPO A13 `13309cc8`（有线 adb）；**`ThinkingMode=ON` 必须保持**（AUTO 对 gemma 因能力位 false 会关思考、不复现）。

| 判据 | 内容 | 态 |
|---|---|---|
| **改前基线（已在手）** | `_w61_dev/results/W62Before_run.jsonl`：`thinklen=2010`（<2048）⇒ `finish=STOP`、`textlen=2404` 正常作答、无提醒 | ✅ |
| **改前病灶（已在手）** | 用户 run ×2：`thinklen=2051/2052` ⇒ round0 `textlen=0` + 循环提醒 → round1 道歉 | ✅（病灶复现） |
| V1 | 同类题面（长推理）修复后：**不再出现**「重复循环」提醒；若触发思考预算则文案为「思考过长…请直接给出最终答案」 | 待回填 |
| V2 | round0 `textlen>0`（用户能看到答案）或 round1 产出实际答案 | 待回填 |
| V3 | `settled ModelStopped`、无 `Failed`、无 `BreakerKind.StreamLoop` | 待回填 |
| V4 | 思考 2048–4096 字符区间（约 500–1000 token）修复后**不触发** | 待回填 |
| V5 | 短问题（1+1）不触发 | 待回填 |

---

## 四、审查对账

⚠️ **如实申报**：本波审查席（code-quality-reviewer）因主理人会话 **429 配额耗尽**（重置于 2026-10-11 20:05 UTC+8）**未能独立出报告**；开发席同样在刀3 中途因 429 失败，**主理人接手完成刀3 提交与刀4 文档**。

主理人自审 + 独立核验清单（替代审查席的最低保障）：
- ✅ 刀1 阈值取值**有依据**（native 512 token 的字符当量 ×2）+ 回填判据成文（非无据拍值）。
- ✅ 刀1 判定③单测**已同步**（2200→4100，否则该例在新阈值下 vacuous）。
- ✅ 刀2 marker 传播路径逐点核验（`GenerationOutcome` / `catch` / 注入点）；`intraStreamLoop: Boolean` 全仓**无残留**。
- ✅ 刀2 未触碰 `MAX_INTRA_STREAM_LOOP_ROUNDS`、`EMPTY_ANSWER_NUDGE`、引擎、能力位。
- ✅ 刀3 纯函数单一落点 + 3 例断言钉「判别特征词」非整句措辞（文案可微调、语义不回归）。
- ✅ baseline 655 = `@Test` 严格口径实测（宽松 grep 会多算 KDoc 引用）。
- ✅ `arch-guard` 33/0；`core-model`+`core-agent` 强制重跑 **358 tests / 0F / 0E**；编译 BUILD SUCCESSFUL。
- ⚠️ **缺口**：无独立第二双眼；W63 起建议由审查席补一轮对本波 diff 的回看。

---

## 五、挂账（W63 起；权威版见本节 + README）

1. 🔴 **gemma-4 能力位订正（另立项）**：`GEMMA_4 -> thinking=false` 是保守误设（真机实证有 `<|channel>thought` 通道）；订正需 E2B/E4B 双变体真机取证（thinking 通道行为 + AUTO 档效果）。
2. 🟡 **R1 思考通道切分取证（W61 挂账沿袭）**：R1 推理腔泄漏根因在通道未切分（`inThinking` 由增量走哪条流决定，不读能力位）。
3. 🟡 **AUTO 档 thinking 预算偏小（W61 挂账沿袭）**：`resolveThinkingBudget(0, 2048) = 1024` vs R1 自述 ≥2048。
4. 🟡 **knownBroken 容器 UI 硬提示（第七审 §3.4 沿袭，本波再获实证）**：用户在真机对 `gemma-4-E4B-it-gpu`（引擎初始化 NOT_FOUND，预设已标 ❌）**连续点了 4 次都 Failed** ⇒ 标注只在 `backendBasis` 长文案里，可见性不足。schema（`knownBrokenOn` 字段）+ UI 硬提示应尽快落地。
5. 🟡 **`THINKING_CHAR_BUDGET` 回填**：若真机仍见「正文为空 + 循环提醒」，按实测 chars/token 上调（记于 KDoc）。
6. 沿袭：上游 issue（MiniCPM5 模板↔minijinja + gemma GPU 引擎初始化）｜evidenceLevel 数据化｜AgentRunner 首刀（**方法级 `executeBodyUnchecked` 540/550 余 10，本波未触**）｜法务 termsVersion｜0 tags｜main 快照｜fulltest 接 CI｜selftest 提速。

---

## 六、下次接手须知

1. **静态闸门基线（本波后）**：`arch-guard.sh` **33 项**；`arch-guard-selftest.sh` PASS=**93**；`fulltest.sh` = 9 模块 tests=**655** failures=1（唯一失败 = core-data `SandboxFileScannerTest` Windows 符号链接，**既有且仅 Windows**，Linux CI 通过）；`@Test` 严格口径 **655** = baseline；引擎 **2008/2400**；`AgentRunner.kt` **2843/2900**（#31，余 57）；**方法级 `executeBodyUnchecked` 未触碰**（#12 余 10 仍是最紧引信）。
2. 🔴 **`@Test` 计数用严格口径** `grep -rnE "^\s*@Test\b" --include=*.kt . | grep -v /build/ | wc -l`；宽松 `grep "@Test"` 多算 KDoc 引用（W61 实测差 7）。
3. 🔴 **`finishReason=LENGTH` 是检测器人工标记**（非 native）——离线归因**不得**据此反推引擎行为；看 `textlen` 与 reminder 行的有无。
4. 🔴 **思考预算相关真机验证必须 `ThinkingMode=ON`**（AUTO 对 gemma 因能力位 false 会关思考、不复现）。
5. 🔴 gradle 本机环境：`_ci-tools/localbuild.sh`（**工作区根**）；真机脚本 `_ci-tools/_w61_test_model.sh` / `_w61_set_model_auto.sh`。
6. 🔴 **datastore JSON 长度改动须修 protobuf 长度前缀**（`_ci-tools/_w61_patch_pb2.py`）。

---

## 七、CI run id

> **推送后回填**。

| workflow | run id | 结论 | 关键 job |
|---|---|---|---|
| **Build** | 待回填 | | |
| **Release** | 待回填 | | |
