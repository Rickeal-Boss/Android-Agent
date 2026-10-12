# W65 波次收官交接（Wave 65 Closeout）

> 基线：W64 收官 `4260cad`（docs tip）/ `acfe25e`（功能 tip）｜本波 tip = 见文末 CI 表
> 前置文档：`docs/handoff-20261011-wave64-closeout.md`（§五 = W65 挂账来源，顶部有 W65 裁定语勘误）
> 外部审查（五份，2026-10-12）：W64 深度审查（M-1 半A / M-2 锚#5升blocker / M-3 毒化AB / M-4 映射断言 / M-5 外提 / M-6 销账路径）｜复审20 三线融合（P1-①②③ / P2-①-④ / P4-①②）｜第十审（D1 不可达证明 / D2 纯函数簇迁文件 / D3 ThinkingBudget 无守卫 / D4 head50MB 取证 / D5 K论证件并入bump前置⑤ / D6 提速降级 / D7 docs/14进度指标）｜第十轮 W54-64（新-1 knownBroken激活态 / 新-2 collapse锚）｜横切 v18（X1 自检口径 / X3 Coordinator 余22）

---

## 一、一句话 + 关键现实

**W65 = 治本收口 + 守卫一致性命门 + 零风险拆弹 + 真机补丁臂波**：① **M-1 半A治本**（档案新增 `minThinkingTokenBudget` 轴，R1/MiniCPM5 填 1536，消除「native 预算 1024 vs 档案自述 ≥2048 差 2×」）；② **D1 前半**（K-1 不可达性证明入 KDoc + C 安全窗口 (8.4,16.8) 落档 + 三处同步单）与**后半**（临时 C=2 补丁臂**史上首次行使思考预算真阳性链**）；③ **守卫 #36/#37/#38**（ThinkingBudget / AgentRunnerLoopHelpers / ChatRunRecovery，同波关闭「新文件裸奔」第六次复发）+ **M-4 case53 四方一致性断言**；④ **D2+X3 零行为风险文件迁移**（AgentRunner 2866→2709、Coordinator 1278→1204）；⑤ **D4 取证闭环**（R1-Distill-Llama 容器不存在 ⇒ 外推维持）；⑥ **D5**（K 论证件并入 bump 前置⑤，销独立项）；⑦ docs/12 双锚升级（锚#5 缺失即阻断 P0 + collapse 锚 blocker+bump 首跑判据）+ docs/14 六项销账路径 + X1/M-2/M-6/P4 顺手批；⑧ **审查席 PASS 准予推送，3×P2+4×P3 全部销账**。

关键现实（动手前必读）：
1. 🔴 **K-1 挂账 #1 原形态被证明为不可能任务**：阈值 = native token 预算 × C(=16) 字符；健康 native（ThinkingBudgetConstraint 已编入 .so）下思考在 budget token 处被强制结束符截断 ⇒ thinklen ≤ budget×r，超阈需 **r>16** chars/token——实测上界仅 8.4（W62 真机 2048 字符≈244 token）、CJK 1–3、英文 3.5–5 ⇒ **真实文本数学不可达**。挂账重分类为「⛔ 结构不可达（0.17.1 健康 native），bump 场景保活」；C=16 的定位由此精确化 = **native 失效兜底**（失效时超阈需 r>8，8.4 恰好可触发）。
2. 🔴 **真阳性链由临时 C=2 补丁臂首次行使（4 腿中 3 腿 ✅，1 腿 ⛔）**：marker（`thinking_char_budget`, thinking=true）命中 → 截断（「已提前终止本次生成并保留已产出文本」）→ **专用提醒**「[系统提醒] 你的思考过程过长，已到上限被截断。请直接给出最终答案，不要再展开推理。」（**不含**「重复循环」字样）→ 终态 evidence「连续 3 轮触发思考预算耗尽（已注入 2 次提醒仍复发），终止 run」。**模型直答腿 ⛔**：gemma-4-E2B-it（2B）两题面 ×2 轮共 6 次触发，均以 >1024 字符再思考复发（天然 thinklen≈1025-1030 恰贴人造阈值 1024，每轮必复发）⇒ 端侧 2B 对「直接作答」指令不合规，非守卫链缺陷；回退机制（3 击终止）正确行使。
3. 🟡 **M-1 轴的两个已知副作用（审判 P2-1/P2-2，已申报+钉测）**：① 探针路径：`probeSamplingConfig` 钉 maxTokens=96 后轴值 1536 被钳到 **95** ⇒ R1/MiniCPM5 探针正文样本采集失效（预览恒空），但判据 C 两通道合并保证不误判 BAD（机器安全，测试钉住）；② 兜底条件化：`configured=1536/maxTokens=2048` 档失效兜底阈 = 16×1536/2048 ⇒ **r>12** > 实测 8.4 ⇒「判定③ = native 失效兜底」对三个轴档案**收窄**（W62 同型样本在旧 1024 档会触发、新 1536 档不触发），余量由锚 #5 墙钟兜底承担。
4. 🔴 **docs/14 勾选 0/6**（D7 一等指标）：六项全部用户侧（A1 手验 / B1-B2 上游 / C1-C3 裁决）；本波成果 = 每项销账路径落档（「完成后销账于：<文件:行>」），进度叙事转为可跟踪的勾选框。

---

## 二、改动清单（4 commit）

| commit | 性质 | 内容 |
|---|---|---|
| `W65-1` | fix(model,engine,agent) | **M-1 半A治本 + D1 前半 + 审查销账（代码侧）**：`ModelSamplingProfile.minThinkingTokenBudget` 轴（R1/MiniCPM5 = 1536）+ 构造期 init 不变式 + `appliedTo` 接线（maxOf 下限口径）+ 日志 /think 字段 + evidence 串订正；core-engine `ThinkingBudget.kt` 顶部三处同步单 + `THINKING_CHARS_PER_TOKEN_CEIL` 安全窗口 KDoc；`StreamRepetitionDetector` 结构不可达证明 KDoc；`ModelHealthCriteria` X1 申报；`ModelHeuristics` C1 取证回填；审查销账 P2-1（`ModelHealthProbe` KDoc 申报 + 探针轴断言 1 例）、P2-2（两处 KDoc 条件化）、P3-3（测试 KDoc 措辞）；`build.yml` baseline **678→684**（+6 同 commit 双向） |
| `W65-2` | refactor(agent,chat) | **D2+X3 零行为风险文件迁移**：`AgentRunner.kt` 顶层纯函数簇 6 个 + 提醒常量族 7 个 → 新文件 `AgentRunnerLoopHelpers.kt`（209 行，private→internal 6 项、SYSTEM_REMINDER_PREFIX 保持 private），2866→**2709**（#31 余 191）；`ChatRunCoordinator.kt` journal 恢复三函数 → 新文件 `ChatRunRecovery.kt`（129 行，facade 委托保形：`maybeOfferRecovery`/`dismissRecovery` 签名与调用点零变更），1278→**1204**（#18 余 96）；顺带订正 `AgentRunner:507`「唯一生效点」陈旧注释（探针是 appliedTo 第三调用点，W49 起失实——随本 commit 的自然落点）；CRLF 纪律保住（i/crlf w/crlf 复验） |
| `W65-3` | ci | **守卫 #36/#37/#38**（ThinkingBudget ≤150 / AgentRunnerLoopHelpers ≤400 / ChatRunRecovery ≤400，#25 家族 fail-closed）+ selftest case52 组（5 面）+ case54 组（5 面）+ case55 组（5 面）+ **case53 四方一致性断言**（映射表↔KNOWN_GUARDS↔arch-guard↔case，三提取面 fail-open 防护）+ KNOWN_GUARDS 28→30 守卫号（+36/37/38）与 --affected 映射同步 |
| `W65-4` | docs | README（M-1 勘误定性订正 + W65 波次段 + 挂账口径新订正）+ `docs/12`（锚 #5 升 P0 缺失即阻断 + collapse 锚 blocker+bump 首跑判据+预案二选一 + 「锚失效的可观测签名」第 7 列）+ `docs/14`（六项销账路径）+ W64 交接 §五裁定语勘误注记 + W63 交接 CI 表头/orphan 行修复 + **本文档** |

| workflow | run id | 结论 | 关键 job |
|---|---|---|---|
| **Build** | `38244498789` | ✅ success（head `5fb97de`） | 三 job 全 success（Assemble 内 arch-guard 38 项 + 全量 selftest） |
| **Release** | `38244498971` | ✅ success（head `5fb97de`） | — |

> 分工说明（如实入账）：W65-1、D1 KDoc、X1、审查销账由开发席第一实例（柯码成）完成并自验（编译+定向单测+core-model 全量 160/0）；W65-2 迁移由开发席第二实例完成并自验（编译+core-agent/feature-chat 253 测试 0 失败），文件名更名（AgentRunnerEvidence→LoopHelpers）与 X3 归属订正由主理人执行；W65-3 由 CI 席（崔续程）完成，主理人独立复跑 38/38 与 11/0 核实；R1-Llama 容器取证（D4）由取证代理完成（只读取证，结论经主理人回填 KDoc）；真机批、README、docs/12/14、两处交接勘误、build.yml、W64/W63 勘误、本文档由主理人完成；全量 diff 审查由审查席（严质衡）完成。

---

## 三、真机复验（OPPO A13 `13309cc8`，有线 adb，debug 包）

### 3.1 补丁臂（临时 C=2，Arm B 纪律：全局改 → 行使 → 还原 → 重建重装 → git status 干净）

| 判据 | 期望 | 实测（2 run × 3 击） | 态 |
|---|---|---|---|
| marker 命中 | `thinking_char_budget`（thinking=true） | 「轮内重复检测触发（thinking=true, marker=thinking_char_budget），已提前终止本次生成并保留已产出文本」×3/run | ✅ |
| 截断 | 提前终止+保文本 | 同上（每 run 2 轮 reminder + 第 3 击终止） | ✅ |
| 专用提醒文案 | 「思考过程过长」且**不含**「重复循环」 | journal reminder 逐字：「[系统提醒] 你的思考过程过长，已到上限被截断。请直接给出最终答案，不要再展开推理。」（2 run × 2 轮）；含「重复循环」字样 = 0 | ✅ |
| 终态 evidence | 「思考预算耗尽」语义链 | ERROR「连续 3 轮触发思考预算耗尽（已注入 2 次提醒仍复发），终止 run」 | ✅ |
| **模型直答腿** | 提醒后模型直答 | gemma-2B **6 次触发 0 合规**：两题面（jug 问题 / 17×23）每轮 thinklen≈1025-1030 恰贴人造阈值 1024 ⇒ 每轮必复发 | ⛔ |
| 附带数据点 | r = chars/token | gemma 英文样本 thinklen≈1030 字符 / 512 token 预算 ⇒ **r≈2.0**（远低于 8.4 上界，C=16 安全窗口内） | 📊 |

### 3.2 还原后回归（C=16，重建重装）

| 判据 | E2B_NORMAL（jug 题面） | R1_M1（M-1 轴回归） |
|---|---|---|
| settled | `ModelStopped` | `ModelStopped` |
| thinking | 1560 字符非空 | 618 字符（1536 档内优雅收尾） |
| 正文 | 1331 字符非空、零 `<|channel>` 泄漏（通道定界符计数 0） | 非空（tool-call 收尾） |
| marker / 提醒 / 循环 | 全 0 | 全 0 |
| tmpl_fail / rebuild / appFatal | 0 / 0 / 0 | 0 / 0 / 0 |

journal 证据：`_w65_dev/results/{E2B_PATCHARM,E2B_PATCHARM2,E2B_NORMAL,R1_M1}_run.jsonl` + `*_log.txt`；驱动脚本 `_ci-tools/_w65_run.sh`（动态定位发送按钮 + 补丁臂判据行）。**E4B 思考双变体**：设备唯一 E4B 容器 `gemma-4-E4B-it-gpu` 为 knownBroken（引擎初始化 NOT_FOUND）⇒ ⛔ 容器不可得，维持「E2B 实证 + E4B 家族外推」（W63 刀E 同口径）入挂账。

---

## 四、审查对账

- **审查席（严质衡）独立深审 PASS，准予推送，无 P0/P1**：独立复跑 arch-guard（当轮 36/36 → 追加 #37/#38 后 38/38）、selftest `--only 34,35,36,53` = 16/0 与 `--only 37,38,53` = 11/0、编译 BUILD SUCCESSFUL、@Test 严格口径 = baseline、EOL 完整（ChatRunCoordinator i/crlf w/crlf）、迁移零行为风险逐行实证（AgentRunner 全部 `+` 行 = 指针注释；Coordinator 仅 facade）、新测试真钉不变式（非镜像）、docs/12/14 事实核对逐项命中、改动集合 = 申报项无集合外文件、check-test-void 全仓零发现。
- **3×P2 全部销账**：P2-1（探针轴影响）⇒ `ModelHealthProbe` KDoc 申报 + `ModelHealthProbeConfigTest` 新断言「轴档案探针思考预算被短预算钳到上限减一」；P2-2（轴档案兜底条件化失假）⇒ 两处 KDoc 条件化措辞 + §一.3 挂账；P2-3（台账口径分叉）⇒ README 挂账行新口径改写 + W64 交接 §五勘误注记（原文保留存档）。
- **4×P3 全部处置**：P3-1（README 前向引用）⇒ 本波 W65 波次段落袋自解；P3-2（#36 注释行数陈旧 62→88）⇒ 订正；P3-3（测试 KDoc assertFailsWith 措辞）⇒ 订正；P3-4（W63 CI 表头 5 单元格失配 + orphan 行）⇒ 表头删前导空细胞 + orphan 行以正确 hash（`8800b8b`）归位 commit 表。
- **主理人收尾自查补刀**：#37/#38 新文件守卫（审查席未覆盖的面，「新文件裸奔」纪律当波关闭）；`AgentRunner:507`「唯一生效点」陈旧注释订正（探针是 appliedTo 第三调用点，W49 起失实）。

---

## 五、挂账（W66 起；权威版见本节 + README）

1. 🔴 **bump 首跑判据**（docs/12 collapse 锚 blocker + 锚 #5 缺失即阻断）：native 上新版 litertlm 时，第一条真机消息必须用「一次发 3 个工具结果回灌」W51 原题面当场行使 collapse 是否还在；解包核对 `ThinkingBudgetConstraint` 符号。**锚 #5 未升级验证前不要 bump**。
2. 🟡 **C 复测确认 r_max ≤8.4**（K-1 唯一剩项）：补丁臂已附英文样本 r≈2.0；待中文长推理样本 ⇒ 若是则窗口 (8.4,16.8) 内 C 任意取值结构安全。
3. 🟡 **轴档案兜底收窄**（§一.3）：M-1 后三档案失效兜底阈 r>12 > 实测 8.4 ⇒ 空转防线 = 锚 #5 native + 墙钟；bump 时按锚 #5 复核覆盖。
4. 🟡 **模型直答腿**：gemma-2B 6 触发 0 合规；未来若需考察「提醒后直答」合规性，换更强模型或接受 ⛔ 记录（端侧 2B 现实）。
5. 🟡 **knownBroken 激活态重校验**（第十轮 新-1，P2，10 分钟级）：`refresh()` 合并点或 run 触发对 active 模型查 `knownBrokenOn` → 对话页内联提示条（复用模型卡红字），**排在首个 tag 前**（每次预设修订都可能制造「老激活态踩新标记」用户，首发日放大）；也是挂账 #6 的第一消费面。
6. 🟡 **evidenceLevel 全 schema**（templateSafety/e2eImage/functionalTest + 守卫）；宜紧随 #5。
7. 🟡 **E4B 容器**：唯一在机变体 knownBroken；上游发布可用 E4B 后补思考双变体复验（§三）。
8. 🟡 **接缝②仍不启动**：AgentRunner 2709/2900（余 191）、Coordinator 1204/1300（余 96）、`executeBodyUnchecked` 498/550（余 52）——三引信同时松弛；下波落码前复核余量。
9. 🟡 沿袭：C-1/C-2 对话框手验（docs/14 §A1，用户 10 秒）｜法务 `termsVersion`（先于 tag）｜0 tags｜main 快照过时｜fulltest 接 CI｜F4 文字 token 统一｜会话删除 UI｜`TokenUsage.estimated`｜bump 双轴季扫（docs/12 13 锚 + #3576 钩子）｜上游出货 B1/B2（用户手动）｜审查 P3-d（测试源集依赖方向成文）。

---

## 六、下次接手须知

1. **静态闸门基线（本波后）**：`arch-guard.sh` **38 项**（+#36 ThinkingBudget ≤150 / +#37 AgentRunnerLoopHelpers ≤400 / +#38 ChatRunRecovery ≤400，#25 家族 fail-closed）；`arch-guard-selftest.sh` PASS=**103+16=119**（+case52/54/55 三组 15 面 + case53 四方断言；本地全量 ~52min 留 CI，日常 `--only`；**新增守卫 case 必同步 `KNOWN_GUARDS`**）；`fulltest.sh` = 9 模块 **tests=684 failures=1**（唯一失败 = core-data `SandboxFileScannerTest` Windows 符号链接，既有且仅 Windows）；`@Test` 严格口径 **684** = `build.yml` baseline。
2. **行数表八员**：`AgentRunner.kt` **2709**/2900（#31，余 191）｜`AgentRunnerLoopHelpers.kt` **209**/400（#37）｜`ChatRunCoordinator.kt` **1204**/1300（#18，余 96）｜`ChatRunRecovery.kt` **129**/400（#38）｜`AgentRunnerToolSetup.kt` 108/400（#35）｜`ThinkingBudget.kt` **88**/150（#36）｜`LiteRtLmEngine.kt` 2009/2400（#25）｜`LiteRtLmEngineLoader.kt` 704/900（#32）。
3. 🔴 **K-1 单源链（四处）**：core-engine `THINKING_CHARS_PER_TOKEN_CEIL`/`thinkingCharBudgetFor` ↔ core-model `THINKING_CHAR_BUDGET`(8192 字面量) ↔ `ThinkingBudgetInvariantTest` ↔（W65 新增）轴档案 `minThinkingTokenBudget`。改任一处同步其余 + 跑不变式测试；`ThinkingBudget.kt` 顶部「三处同步单」注释为唯一定期复核点。
4. 🔴 **补丁臂方法论**（W55 Arm B 纪律）：临时全局改常量 → 真机行使 → 还原 → 重建重装 → 正常态回归 → git status 干净；判据以 journal/logcat 为准（uiautomator 对 Compose bounds 不可靠）；驱动脚本 `_ci-tools/_w65_run.sh`。
5. 🔴 **CRLF 敏感文件**：`ChatRunCoordinator.kt`（i/crlf w/crlf）禁 `sed -i`（本波实摔翻转，已修复复验）⇒ Edit 工具或 python `newline=''` 字节级替换。
6. 🔴 **adb 双坑**：push/install 一律 `export MSYS_NO_PATHCONV=1`（Git Bash 路径转换，本波实摔）；真机驱动坐标：输入框 `(540,1969)`、发送按钮动态 dump `content-desc="发送"` bounds（本波实见 942/1254、942/1328、942/1356 三种位置）。
7. **docs/14 = 外部依赖一等进度指标**（D7）：本波 **0/6**（全部用户侧）；每项销账路径已落，用户动手后按行销账。

## 七、CI run id

| workflow | run id | 结论 | 关键 job |
|---|---|---|---|
| **Build** | `38244498789` | ✅ success（head `5fb97de`） | Lint (baseline gate) / Unit tests / Assemble Debug (JDK 21) 全 success；Assemble 内「Architecture guard」（38 项）+「Architecture guard self-test」两步绿 |
| **Release** | `38244498971` | ✅ success（head `5fb97de`） | — |

**上游提交申报（沿袭）**：fine-grained PAT 对 `google-ai-edge` 组织无 issue 写权限 ⇒ #3576 补充评论与 gemma-4 GPU NOT_FOUND 新 issue 仍待用户手动提交，文案入仓 `docs/upstream/`（见 `docs/14-user-actions.md` §B）。
