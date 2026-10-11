# W64 波次收官交接（Wave 64 Closeout）

> 基线：W63 收官 `ac3d517`｜本波 tip = 见文末 CI 表
> 前置文档：`docs/handoff-20261011-wave63-closeout.md`（§五 = W64 挂账来源）
> 外部审查：**第九审**（`LiquidAgent-第九审-W63治理波与W64路线-20261011.md`：C1 R1-Llama 缺口 / C2 W63 diff 补审 / C3 K-1 系数矛盾 / C4 用户行动卡 / C5 selftest 提速）｜**深度审查 W63**（L-1 临界值 / L-2 挂账丢失 / L-3 新文件裸奔 / L-4 复验留痕 / L-5 selftest / L-6 文案）——两份审查的 W64 建议本波全部处置（见 §四）。

---

## 一、一句话 + 关键现实

**W64 = 治本 + 补审 + 提速波**：① **K-1 治本**（思考字符守卫由全局常量改为**按本轮 maxTokens 派生的注入参数**，消除 `minMaxTokens=2048` 预设下「守卫早于 native 抢跑约 52%」的结构性根因）；② **C1 R1-Distill-Llama 通道缺口**（去 family 门控）；③ L-1 临界值 KDoc 订正；④ L-6 截断提示文案；⑤ L-4 复验留痕；⑥ **守卫 #35**（`AgentRunnerToolSetup.kt ≤ 400`）；⑦ **selftest `--only/--affected` 提速**（C5 军令状件）；⑧ C2 补审 W63 diff（PASS 零阻断）；⑨ C4 用户行动卡 + 上游文案入仓。

关键现实（动手前必读）：
1. 🔴 **K-1 靶心不是 KDoc 文字矛盾，而是 `minMaxTokens`**：`ModelSamplingProfiles.appliedTo` 对 R1 / MiniCPM5 用 `maxOf(config.maxTokens, minMaxTokens=2048)` ⇒ 用户**不动滑条**这两个预设 maxTokens 也 =2048 ⇒ native 思考预算 = `resolveThinkingBudget(0,2048)` = **1024 token**；旧**固定 4096 字符**守卫在其**尚余约 52%** 时就抢跑（W62「合法长推理被误判循环」的结构性根因）。W63 的 KDoc 把残留写成「maxTokens ≥4096（用户拉滑条才遇到）」——**说窄了**（深度审查 L-1）。
2. 🔴 **系数 C 取 16，且**「守卫晚于 native」**的运行时判据本波 ⛔ vacuous（未行使）**：C = 16 来自实测上界 8.4 chars/token（W62 真机 2048 字符≈244 token）×≈1.9 余量；**故障不对称**（C 取小 ⇒ 守卫抢跑 = W62 病灶；取大 ⇒ 退化为纯兜底）⇒ 向上取。**冻结 C 前须真机 ≥2 样本（英文+中文）复测**；本波真机两 run `thinklen`（gemma 2025 / R1 1598 字符）均**远低于**阈值（8192 / 16384）⇒ **>阈值区间从未被行使** ⇒ 只能宣称「结构面已核验 + 不变式单测绿 + 真机回归无碍」，**不得宣称治本已真机生效**。
3. 🔴 **单源靠测试保证**：core-model **不可见** core-engine（零引擎依赖）⇒ `StreamRepetitionDetector.THINKING_CHAR_BUDGET` 只能写字面量 **8192**（= 派生式在 1024 档取值）；与 `thinkingCharBudgetFor(1024,0)` 的相等由 core-engine 的 `ThinkingBudgetInvariantTest` 钉住（**唯一**同时可见派生函数与检测器常量的模块）。勿让两处各自演化。
4. 🔴 **selftest 提速已落地**：`--only <n>[,<n>…]`（按守卫编号跑子集，本机 ≈2–5 min）+ `--affected`（git 改动 → 守卫映射；未映射 / 无 upstream **保守回退全量**）。**CI 调用零改动、默认全量路径逐字节不变**；新映射表在 `scripts/arch-guard-selftest.sh` 头部（新增守卫 case 须同步 `KNOWN_GUARDS`）。
5. 🔴 **真机一律有线 adb**（OPPO A13 `13309cc8`）；**本波实测坐标陷阱**：app 输入框中心 = `(540,1969)`、发送按钮在**键盘弹起后**移至 `(942,1180)`（未弹起时为 `(942,1968)`）——**必须动态 dump `content-desc="发送"` 的 bounds 后再点**（W61 脚本写死的 `(942,1356)` 是「键盘已弹起」态，本波未弹起时点空 ⇒ `NO_NEW_SESSION`）。

## 二、改动清单（4 commit）

| commit | 性质 | 内容 |
|---|---|---|
| `W64-1` | fix(model,engine,agent) | **K-1 治本 + C1 + L-1 + L-6**：core-engine 新增 `THINKING_CHARS_PER_TOKEN_CEIL=16` + `public thinkingCharBudgetFor(maxTokens, configured)`；`StreamRepetitionDetector` 构造参数 `thinkingCharBudget`（默认 8192）+ 判定点改用实例字段 + KDoc 全量订正（L-1）；`AgentRunner` 注入派生值 + L-6 落点 `loopTruncationNoticeFor`；`ModelHeuristics.inferChannelSyntax` 去 family 门控（C1）+ 单测；`build.yml` 基线 669→678（同 commit 双向） |
| `W64-2` | fix(models) | **L-4**：`ModelCard.kt`「仍要尝试」出口落 warn（`fileName` + `knownBrokenOn` 原文，排在 `onLoad()` 前） |
| `W64-3` | ci | **守卫 #35**（`AgentRunnerToolSetup.kt ≤ 400`，fail-closed）+ selftest `--only/--affected` + case51 组 + case49b 内容断言 + #34 扫描器缺失前置断言 |
| `W64-4` | docs | 收官交接（本文档）+ README 波次段 + `docs/14-user-actions.md` + 上游文案入仓 `docs/upstream/` |

| workflow | run id | 结论 | 关键 job |
|---|---|---|---|
| **Build** | _见 §七_ | _待回填_ | — |
| **Release** | _见 §七_ | _待回填_ | — |

> 分工说明（如实入账）：C1/L-1/L-6 由开发席（柯码成）完成并自验；K-1 治本（含 core-engine 派生函数、检测器参数化、AgentRunner 注入、不变式单测）由开发席第二实例完成；L-4 由开发席实例完成；守卫 #35 + selftest 提速由 CI 席（崔续程）完成；K-1 方案裁决由方案席（沈思远）完成；C2 补审 + W64 全 diff 审查由审查席（严质衡）完成；docs/14 + 上游文案入仓 + README + 本交接由主理人完成。

## 三、真机复验（OPPO A13 `13309cc8`，有线 adb，debug 包）

| 判据 | 内容 | 态 |
|---|---|---|
| **E-1（回归）** | gemma-4-E2B-it（CPU）+ **AUTO**：`thinking` **2025** 字符非空、正文 932 字符、`finishReason=STOP`、零 `<|channel>`/`<think>` 泄漏、通道声明「正文剥离 `<|channel>thought…<channel|>`」在位、tmpl_fail=0、rebuild=0 | ✅ |
| **D-1（回归）** | R1-Distill-Qwen-1.5B + **AUTO**：`thinking` **1598** 字符非空、正文 187、`finishReason=STOP`、零 `<think>` 泄漏、通道声明「正文剥离 `<think>…</think>`」在位 | ✅ |
| 共性判据 | 两 run 均 `settled=ModelStopped`、`轮内重复检测触发`=0、`thinking_char_budget` marker=0、`思考预算` 提醒=0、app FATAL=0（日志中 2 条 FATAL 经核为 `com.android.shell WRITE_SETTINGS` 陈旧条目，PID 非 app、时间戳早于本轮） | ✅ |
| **K-1 绑定判据** | ⛔ **vacuous（未行使）**：`thinklen`（2025 / 1598）**远低于**新阈值（8192 / 16384）⇒ 「守卫晚于 native」未被真机行使；治本正确性由 `ThinkingBudgetInvariantTest`（滑条全域 64..8192）**结构面**证明 | ⛔ |
| L-4 复验留痕 | ⚠️ **未行使**：需人眼点「仍要尝试」（uiautomator 对玻璃卡片下半区零捕获，W63 §六.4）——留用户手验（`docs/14-user-actions.md` §A1） | ⚠️ |
| C1 真机 | ⛔ **未行使**：`DeepSeek-R1-Distill-Llama-8B` 容器**未取得**（设备在机 13 模型无此件）⇒ C1 对 LLAMA 家族为**同管线外推**，KDoc 已如实标注 | ⛔ |

journal 证据：`_w64_dev/results/E2B_AUTO2_run.jsonl`（gemma E2B AUTO）、`_w64_dev/results/R1_AUTO_run.jsonl`（R1 AUTO）；日志 `*_log.txt`；驱动脚本 `_ci-tools/_w64_run2.sh`（**动态定位发送按钮**，勿复用写死坐标的 W61 脚本）。

## 四、审查对账

- **C2 补审 W63 diff（审查席，PASS 零阻断）**：四个回看面逐条核实 —— ① 刀D/E 合并提交（判据单一落点 / GEMMA_4 未误伤 / null 语义守住 / E4B 外推标注充分）② 刀A 五落点（**全仓 grep**：无任何消费方按旧串匹配；`AgentRunner.kt:2222` 属跨轮签名路径、无 marker 参与、保留正确）③ 刀F 等价性（**57 行归一 diff 为空**；求值顺序/字段/解构三点对齐；无 emit/状态/IO）④ #34 fail-closed（逐项核 `check()` 契约：缺文件 / 删声明 / 扫描器异常 / 无构造块 / 无 python 全判红）。**独立确认 C1 缺口成立**（因果链 5 步）。
- **W64 全 diff 审查（审查席第二实例，PASS 零阻断）**：无 P0/P1；1×P2（`AgentRunner.kt` 行数余量 34 偏紧，须在下一波外提前先看）——已并入挂账；6×P3（注释/措辞级）**本波处置 2 项**（`isReasonerMarker` KDoc「两处严格等价」过强 → 订正为精确边界；`ThinkingBudgetInvariantTest` KDoc 过度声称 core-engine 覆盖 → 订正 + 补真判据），余项入挂账。
- **W64 自查闸门**：`arch-guard` **35 项全 OK**（0 error）；`fulltest` 9 模块 **tests=678 failures=1**（唯一失败 = core-data `SandboxFileScannerTest` Windows 符号链接，**既有且仅 Windows**）；`selftest` 见 §六.1；`@Test` 严格口径 **678** = `build.yml` baseline。

## 五、挂账（W65 起；权威版见本节 + README）

1. 🔴 **K-1 真机绑定判据未行使**：需构造 `thinklen > 阈值`（gemma >8192 / R1 >16384 字符）的长推理样本，验证「native 先优雅收尾、守卫不触发」；**并据此复测系数 C**（≥2 样本：长英文 + 长中文）——C 现值 16 系单样本外推。
2. 🔴 **C1 的 R1-Distill-Llama 容器取证**：设备在机 13 模型无此件 ⇒ C1 对 LLAMA 家族属同管线外推；取得容器后按 `_w63_r1_scan.py` 两步法复验（`channel` 字节全 0 ∧ `<think>` 在特殊 token 表）。
3. 🟡 **C-1/C-2 手验回收**：knownBroken 对话框真机点验（用户 10 秒）——见 `docs/14-user-actions.md` §A1。
4. 🟡 **K 论证件（BuildConfig 化 + 灌测）——第 3 次顺延**（现值 `Int.MAX_VALUE` 零风险；K-1 治本后灌测必要性需重估）。
5. 🟡 **knownBroken `evidenceLevel` 全 schema**（templateSafety/e2eImage/functionalTest + 守卫）。
6. 🟡 **`AgentRunner.kt` 行数余量 34 偏紧**（W64 审查 P2）：下一波在 AgentRunner 落新代码前先看余量（余 <20 启动 docs/13 接缝②）；`executeBodyUnchecked` 498/550（余 52）尚有空间。
7. 🟡 **selftest 进一步提速**：`--only/--affected` 已落地；进一步可评估分片 / 并行（全量本机仍 ~52 min）。
8. 🟡 **审查 P3-d**：core-model → core-engine 经**测试源集**传递的依赖方向评估（可接受的耦合选择，但应成文）。
9. 沿袭：法务 `termsVersion`（先于首个 tag）｜0 tags｜main 快照｜fulltest 接 CI｜F4 文字 token 统一｜会话删除 UI｜`TokenUsage.estimated`｜bump 双轴季扫（docs/12 现 13 锚 + #3576 钩子）｜上游出货（用户手动，`docs/14` §B）。

## 六、下次接手须知

1. **静态闸门基线（本波后）**：`arch-guard.sh` **35 项**（+#35 `AgentRunnerToolSetup.kt ≤ 400`）；`arch-guard-selftest.sh` **PASS=102**（97 + case49b/case51 组 5 —— 见 §六.2 实测）；`fulltest.sh` = 9 模块 **tests=678 failures=1**（唯一失败 = core-data `SandboxFileScannerTest` Windows 符号链接，既有且仅 Windows）；`@Test` 严格口径 **678** = baseline；引擎 **2009/2400**；`LiteRtLmEngineLoader.kt` 704；**`AgentRunner.kt` 2866/2900（余 34）**；方法级 `executeBodyUnchecked` **498/550（余 52）**；`AgentRunnerToolSetup.kt` **108/400**。
2. 🔴 **selftest 用法**：`bash scripts/arch-guard-selftest.sh`（全量，CI 用）｜`--only 34,35`（子集）｜`--affected`（git 映射，未映射回退全量）｜`--help`。**新增守卫 case 必须同步 `KNOWN_GUARDS`**（否则 `--only <新号>` 报错而非静默零跑）。
3. 🔴 **K-1 三处单源**：core-engine `THINKING_CHARS_PER_TOKEN_CEIL` / `thinkingCharBudgetFor` ↔ core-model `StreamRepetitionDetector.THINKING_CHAR_BUDGET`（8192）↔ core-agent `AgentRunner` 注入点。**改任一处必须同步另两处并跑 `ThinkingBudgetInvariantTest`**。
4. 🔴 **真机驱动坐标**：输入框 `(540,1969)`；发送按钮**必须动态 dump `content-desc="发送"` 的 bounds**（键盘弹起与否位置不同）。复用 `_ci-tools/_w64_run2.sh`，勿用写死 `(942,1356)` 的 W61 脚本。
5. 🔴 **uiautomator 对模型卡下半区盲**（W63 §六.4 沿用）：knownBroken 红字 / 加载按钮不可捕获 ⇒ 点按判据只能靠 journal/logcat 或人眼。
6. gradle 本机环境同 W62（`_ci-tools/localbuild.sh` 在工作区根；`scripts/fulltest.sh` 需注入 `JAVA_HOME`/`ANDROID_HOME`/`GRADLE_USER_HOME`，**background 任务里必须显式 export**）；OPPO A13 必须 `adb push` + `pm install -r`（直装报 -99）。

## 七、CI run id

| workflow | run id | 结论 | 关键 job |
|---|---|---|---|
| **Build** | _待回填_ | _待回填_ | — |
| **Release** | _待回填_ | _待回填_ | — |

**上游提交申报**：fine-grained PAT 对 `google-ai-edge` 组织**无 issue 写权限** ⇒ #3576 补充评论与 gemma-4 GPU NOT_FOUND 新 issue 仍**待用户手动提交**，文案已入仓 `docs/upstream/`（见 `docs/14-user-actions.md` §B）。
