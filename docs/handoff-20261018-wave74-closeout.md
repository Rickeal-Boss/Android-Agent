# W74 波次收官交接（Wave 74 Closeout · 内核定版波）

> 基线：W73 收官 `bb399b7`｜本波 = **验证型 + 审查型**波（零 `.kt` / 零 `.py` 源码改动，只动 docs）
> 单一执行依据：`_plans/w74-plan.md`（v2，方案席 + 主理人裁 U3/U6/U7）
> 支撑报告：`_plans/w74-gates-report.md`（CI 席）｜`_plans/w74-review-report.md`（审查席）｜`_plans/w74-device-report.md`（真机批，主理人接手）
> 前置：W73 交接 §六 九条接手须知 + `docs/10` §11.11

---

## 一、一句话 + 关键现实

**W74 = 内核定版波**：对 W73 的 12 个 commit 做深度审查（14 条候选问题逐条证伪/证成 + 6 条独立发现）、跑全闸门、跑真机回归批（K1–K8），结论 = **内核运行面可以定版**；工具面 5 条 P1 全部转挂账。**本波零源码改动**，只产出审查/验证证据与定版声明。

关键现实（动手前必读）：

1. 🔴 **内核定版声明已落 `docs/10` §11.12**：K1–K4、K6–K8 全绿；K5 部分行使（模型面）；K9–K11 挂 ⚠️（W74-01）；V1–V3 ⛔ vacuous。**内核面自本波起冻结，除 P0 外不再改动**。
2. 🔴 **W73 真机批收尾把设备留在 knownBroken 模型上，且未申报**：开工时 active = `984937bb` = `gemma-4-E4B-it-gpu`（引擎初始化 NOT_FOUND + A13 OOM）。W73 交接称「终态与 927B 快照 0 差异」属实，但**那份快照本身拍于切换之后** ⇒ 「还原成功」不等于「状态正确」。**教训：还原前要问「这份快照是什么状态」，不是只问「是否逐字节一致」**。
3. 🔴 **新实证：工具 ✅ ≠ 设置生效**（W74-02，本波最高价值发现）：`set-string-key dark_mode DARK!!`（6B 等长）被工具接受、真写入设备、exit 0，而 App `runCatching { DarkMode.valueOf(...) }` **静默回退 SYSTEM**。工具只校验「字节等长」，不校验「语义合法」⇒ 任何以工具写入非枚举值的后续实验都是假绿。
4. 🟠 **K5 的失败形态被纠正**：不是「工具不执行」，而是**模型不收敛**——8 轮 `file_list` 每轮 `ok=true`、output 非空、1ms，`MaxRounds` 安全网收束。与 W73「批量调用静默 0 执行」是**两个不同缺陷**，禁合并。
5. 🟠 **K6 多轮续航缺口闭合**：同会话两轮（`Remember 47` → 追问）答出 `47`。W73 交接 §六.5 的「多轮是下一步诱导关键缺口」已解。
6. 🟡 **R1 作为默认模型不可用**：R1 冒烟回显题面 + 50956ms/3 tok ≈ 17 s/token；Qwen 同题 `OK` + 1ms。终态定 Qwen（**有意的可用性裁决**，不是还原失败）。
7. ⚠️ **过程**：方案席 429（已产出计划）、CI 席 429（主理人接手跑闸门）、开发席 429（主理人接手真机批）、审查席 429（主理人接手复核）。四席全部撞配额 ⇒ 本波实际是**主理人单人完成 + 各席产出部分采信**。

---

## 二、改动清单（docs only）

| 文件 | 内容 |
|---|---|
| `docs/10-device-acceptance.md` | 新增 §11.12「W74 内核定版批」：K1–K8 判据表 + 定版声明 + 两条新实证 + 设备状态订正 |
| `docs/15-backlog.md` | 新增 **W74-01..05**（K9–K11 未行使 / set-string-key 值域 / 工具 P1 四件 / 仓外脚本卫生 / fulltest 假绿） |
| `README.md` | W74 波次段 |
| `docs/handoff-20261018-wave74-closeout.md` | 本文件 |
| `_plans/w75-ui-plan.md` | UI 波执行方案（供用户确认，未实施） |

**零源码改动**：`git diff --stat` 应只有 `docs/**` + `README.md`。

---

## 三、闸门与真机实证

### 3.1 全闸门（主理人实跑，命令即证据）

| 闸门 | 结果 | 期望 | 判定 |
|---|---|---|---|
| `bash scripts/arch-guard.sh` | **42 OK / exit 0** | 42 | ✅ |
| `@Test` 严格计数 | **740** | = `build.yml:502 baseline` 740 | ✅ |
| `python scripts/tests/test_datastore_tool.py` | **22/22 OK** | 22/22 | ✅ |
| `bash scripts/fulltest.sh`（注入 JAVA_HOME/ANDROID_HOME/GRADLE_USER_HOME） | **9 模块 / tests=740 / failures=1** | 9 / 740 / 1（Windows symlink） | ✅（唯一失败 = `SandboxFileScannerTest > 符号链接指向沙箱外的目录被拒绝并剔除`，本机假红，CI 绿） |
| `bash scripts/line-table.sh` | ThinkingBudget **90/150 余 60**；AgentRunner 2743/2900 余 157；方法级 502/550 余 48；ChatRunCoordinator 1245/1300 余 55 | 与 W73 一致 | ✅ |

### 3.2 真机批（OPPO A13 `13309cc8`，有线 adb）

见 `docs/10` §11.12 与 `_plans/w74-device-report.md`。摘要：**K1 ✅ / K2 ✅ / K3 ✅ / K4 ✅ / K5 ⚠️ / K6 ✅ / K7 ✅ / K8 ✅**。

**设备终态**：927B；active = `dfc68e9e`（Qwen2.5-1.5B）；`dark_mode=SYSTEM`；与开工基线 `ds_w74_base.pb` 仅 **30 字节差且全在 `[126,162)` UUID 区**；crash 缓冲 0；pid 存活。

---

## 四、审查对账（14 条候选 + 6 条独立发现）

| 来源 | 结论 | 处置 |
|---|---|---|
| P0 | **0 条**（无自动可达的 P0） | — |
| P1 | **6 条**：F1 零差异 exit 0 假绿 / F2 写前不 force-stop / F3 回滚提示推未校验文件 / F4 空快照静默清空 / F5 文档因果链表述错 / **F18 force-stop 后工具缓存的旧快照须重读** | 全部转 **W74-03**（工具面），**不影响内核运行面** |
| R2（corruptionHandler） | 因果链被审查席**订正**：`CorruptionException` 继承 `IOException`，读路径被 catch 回 empty prefs，写路径未捕获才崩 | 材料转 UI 波前决策（U3），**本波不实施** |
| R3-a | 真机实证「工具 ✅ ≠ App 生效」 | **W74-02**（active） |
| R15（fulltest 假绿） | 机制缺陷成立，本波实测未被污染 | **W74-05**（active） |
| R4/R5/R6/R7/R8/R10/R12 | 成立或部分成立，均为工具面健壮性 | 并入 **W74-03** |
| R13/R14 | ✅ 自洽（ThinkingBudget 三处同步单、G3 查重机械位） | — |
| R1（设备模型） | 成立且为 W73 遗留 | 本波已订正 |

**审查席定版判词**：「P0=0；P1=6（F1–F5 + F18）均为工具面/文档面，不影响内核运行面；内核可定版（运行面），工具面转 W74-02/03。」

**K1 证据口径说明**：`_w74/k1_after_final.log` 是**累计全缓冲**导出（含其它 buffer 的 UTC 时间戳，≠ app 进程 8 小时窗口），只用于「当前无 FATAL/Corruption/knownBroken」的存在性判断；**隔离窗口权威证据 = `_w74/k1_isolated.log`**（`logcat -c` + 清 crash 缓冲 + force-stop + 启动 + 12s，仅 `main,system,crash` 三缓冲）：3685 行、`FATAL EXCEPTION=0`、`CorruptionException=0`、`已知不可用=0`、pid 4342 存活。

---

## 五、挂账（W74 delta；权威版 = `docs/15-backlog.md`）

**新增**：W74-01（K9–K11 未行使，parked）｜W74-02（set-string-key 值域零校验，active）｜W74-03（工具 P1 四件，parked）｜W74-04（仓外驱动脚本卫生，parked）｜W74-05（fulltest 假绿，active）。
**沿袭**：W70-01 / W70-02 / W70-03 / W71-01 / W71-02 / W72-01 / W72-02 / W72-03 / W73-01（closed-dead）/ W73-02（parked）。
**用户触点五件套仍挂**：gallery #1326 回复 + 0.18.0 回归 issue 提交 + A1 手验 + W68-01 数据回填 + **C6 bump 裁决**（材料已四补）。

---

## 六、下次接手须知（新增 5 条）

1. 🔴 **「还原成功」≠「状态正确」**：W73 用「与快照逐字节 0 差异」证明还原成功，但快照本身拍于模型被切换之后 ⇒ 设备被留在 knownBroken 模型上且无人发现。**还原前必须校验快照的语义状态（active_model_id 属于 knownBroken？dark_mode 是合法枚举？）**，并把「快照拍摄时点 + 当时 active」写进交接。
2. 🔴 **报结论时以快照文件的实际 active 为准，不以文件名/时间窗推断**（审查席 F19）：本波 `snap_final.pb` = R1（中间态）、`snap_final2.pb` = Qwen（终态），命名不表达语义。
3. 🔴 **工具 exit 0 ≠ 目标状态达成**（W74-02）：`set-string-key` 只保证字节等长，不保证语义合法；App 侧 `runCatching` 会静默回退。**任何以工具写入枚举键的实验，必须回读 App 侧实际生效值**。
4. 🟠 **`set-active-model` 前必须 force-stop 目标应用**（审查席 F2）：否则内存态 DataStore 可能覆盖写入。本波全部写操作均遵守。
5. 🟠 **多轮真机驱动用 `_ci-tools/_w74_run_multi.sh`**（本波新建，仓外）：它解决「force-stop 开新会话」的老问题；但其 `wait_settled` 按 journal **目录数**判 settled ⇒ 同会话第二轮不新增目录会假超时（W74-04）。判 settled 请改按「同目录新增 `run_*.jsonl` 且末行 `kind=settled`」。

---

## 七、CI run id

| workflow | run id | 结论 | 备注 |
|---|---|---|---|
| Build | 待回填 | — | 本波纯 docs（`paths-ignore` 命中）⇒ 自动 0 run 属设计预期 |
| Release | 待回填 | — | 同上 |

> 纯文档提交按 W73 先例走 `workflow_dispatch` 在同 SHA 补验，双绿后回填本节。
