# W63 波次收官交接（Wave 63 Closeout）

> 基线：W62 收官 `3ea8ad0`｜本波 tip = 见文末 CI 表
> 前置文档：`docs/handoff-20261011-wave62-closeout.md`（§五 = W63 挂账来源）
> 外部审查：第八审（B1/B2/B5 + W63 路线）｜深度审查（K-1/K-2/K-3/K-5）｜复审19 三线融合终稿（#12 首序 + 一行件包）——三份审查的 W63 建议本波全部处置（见 §四）。

---

## 一、一句话 + 关键现实

**W63 = 治理 + 引信拆除 + 可见性波**：① 分支拓扑裁决落档（`1011` = 快照锚点，B1）；② **AgentRunner 方法级首刀**（docs/13 接缝①工具装配簇外提，**#12 540→492**——复审19 终裁的「先拆后加」首序硬约束兑现）；③ K-2 终态/evidence 的 marker 分流补全（`loopFailurePhraseFor` 三落点 + StreamReset/轮内日志同源）；④ K-1 治标（4096 适用区间 KDoc + 不变式单测 + 两流 4096 交叉说明）；⑤ B5 knownBroken 最小面（`knownBrokenOn` 字段 + 模型卡红字 + 加载前确认对话框 + **守卫 #34**）；⑥ B3 R1 通道取证 + 显式 `ChannelSyntax.THINK`（容器字节扫描一手证据）；⑦ B4 gemma-4 能力位 `thinking=true` 订正；⑧ 上游出货（#3576 补充评论 + gemma GPU NOT_FOUND 新 issue）；⑨ 一行件包（P3-α/P3-β/P4-γ）。

关键现实（动手前必读）：
1. 🔴 **#12 已降压**：`executeBodyUnchecked` = **492/550（余 58）**（刀F 前 540/550 余 10）；AgentRunner.kt = **2838/2900**（余 62）。工具装配簇落新文件 `AgentRunnerToolSetup.kt`（108 行，顶层 `internal fun buildToolSetup` + `data class ToolSetup`）——同文件落点会撞 #31（当时余 14），按预案备选路线走新文件。
2. 🔴 **R1 容器通道证据（本波一手取证，复扫方法见 §六.3）**：`DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm`（1.83GB）字节扫描——`<|channel`/`channel`/`chat_template`/`bos_token` 全 0；`<think>` 恰 1 处 @ offset `0x4067`，位于 protobuf 元数据特殊 token 列表，形态 `<｜Assistant｜><think>`（25 字节单串，与 `<｜User｜>` 等并列）⇒ 容器**无原生通道声明**、思考以 `<think>`/`</think>` 文本定界 ⇒ 符合 W48「显式字面量」前置（`inferChannelSyntax` R1 分支 → `ChannelSyntax.THINK`，判据单一落点 `isReasonerMarker`）。
3. 🔴 **上游 #3576 已有**（john-rocky 2026-09-13，官方已复现、`status:awaiting LiteRT-LM developer`）：MiniCPM5 tojson **不新开 issue**，本波以评论补充 Android AAR 路径实证 + 转换期 lint 诉求。
4. 🔴 **W62 代码 diff 审查基线 = `1da011d`**（勿用 `06505b7`，B2 补审 P3-1 勘误，已落 README）。
5. 🔴 **gemma-4 能力位订正后档位坍缩为二元**（README 档位决策表）：AUTO = 能力位生效入口（R1/gemma/MiniCPM5 全适用）；复现 W62 历史病灶才需要 ON（当时能力位还是 false）。

## 二、改动清单（8 commit）

| commit | 性质 | 内容 |
|---|---|---|
| `6fb0437` | fix(agent) | **刀A（K-2）**：文件级纯函数 `loopFailurePhraseFor(marker)`（思考预算 marker ⇒「思考预算耗尽」，其余/null ⇒「轮内重复循环」）+ 同族 `loopFailureUserMessageFor`（Failed 句式分设、判别同一 marker）。分流覆盖**五落点**：终止日志 / `BreakerKind.StreamLoop` evidence / `AgentEvent.Failed` 消息 / StreamReset 事件 / 轮内日志（后两处为同源顺带）。#12 方法内净增 0（顶层新增）。 |
| `f9b08e1` | fix(model) | **刀B（K-1 治标 + K-5）**：`THINKING_CHAR_BUDGET=4096` KDoc 补适用区间（标定于 maxTokens=1024：512 token ×≈4 chars ×2 余量；滑条 64..8192；maxTokens ≥4096 时守卫先于 native 触发 = 已知残留）+ 不变式单测（钉 4096 与 `resolveThinkingBudget(0,1024)` 的当量关系）+ `TEXT_RUNAWAY_CHARS` 交叉说明（两流同值不同语义：thinking 有 native 优雅路径兜底、text 撞 maxOutputToken 硬截——**两值不得联动调整**）。 |
| `6181c8a` | refactor(agent) | **刀F（复审19 首序）**：AgentRunner 方法级首刀 = docs/13 接缝①工具装配簇外提（`useNativeTools`/`allToolSpecs`/`disclosureActive`/`hiddenToolCatalog`/`availableTools`/`registeredToolNames`/`allToolNames` → `AgentRunnerToolSetup.buildToolSetup` + `ToolSetup`），求值顺序保持、逐字节等价、无 emit；`ToolSetupTest` 153 行；**#12 540→492**、#31 2886→2838；`build.yml` baseline 655→**660**（同 commit 双向）。selftest fixture 零影响（#12 自测 case 覆盖确认）。 |
| `a4225eb` | feat(model) | **刀C（B5）**：`ModelPreset.knownBrokenOn: String?`（KDoc 钉「取值必须来自真实失败证据」+「非空 ⇒ recommended=false」不变量）；三容器回填（E2B-gpu/E4B-gpu = 引擎初始化 NOT_FOUND；MiniCPM-V-4 = 会话创建 Unsupported model type——均按 W60 实测签名）；模型卡常驻红字 + 加载前确认对话框（「仍要尝试」出口保留复验入口，失败语义不变，`rememberSaveable` 纪律同 confirmDelete）；**守卫 #34**（arch-guard + `check-known-broken.py` fail-closed 扫描器）+ selftest case48 红 + case48b + case49 + case50 绿面；`ModelPresetsKnownBrokenTest`；baseline 660→**669**（同 commit 双向）。 |
| `43dc437` | fix(model) | **刀D+刀E（合并提交，W61 审查修正刀先例）**：刀D = `inferChannelSyntax` 加 R1 分支 → `ChannelSyntax.THINK`（一手证据见 §一.2），推理标识判据外提 `isReasonerMarker` 单一落点；刀E = `GEMMA_4 thinking=false→true`（W62 真机实证推翻保守依据；E4B 按家族外推并如实标注，沿用 image=true 范式）。单测：R1 文件名 → THINK、`qwen2.5`/MiniCPM5/MiniCPM-V/老 gemma → null 不变；⚠️ 测试名纪律：样例名不得含家族关键词（`deepseek-distill-llama` 落 LLAMA 家族 = W61 `inferFamily` 既有边界，测试注释登记）。@Test 669 不变。 |
| `d37256b` | docs | **一行件包（复审19 建议 7）**：README 分支口径段（B1：`1011`=快照锚点、有意不进触发列表、充当回滚锚点了结 W51 挂账）+ 审查基线勘误（P3-1）+ **档位决策表**（P3-β：能力位订正后全线坍缩二元）+ W60 交接 §五.1 首刀勘误注记（P3-α：以 docs/13 为准）+ `LiteRtLmEngine` K=3 注记「当下 Int.MAX_VALUE」限定语两处（P4-γ；:1277 原文自带限定未动）。 |
| | workflow | run id | 结论 | 关键 job |
|---|---|---|---|
| **Build** | `38127758901` | ✅ success（head `8800b8b`） | Lint (baseline gate) / Assemble Debug (JDK 21) / Unit tests 全 success；Assemble 内实测「架构守卫全部通过」（34 项）+「自测结果：PASS=97 FAIL=0」 |
| **Release** | `38127758844` | ✅ success（head `8800b8b`） | — |

**上游提交申报**：fine-grained PAT 对 `google-ai-edge` 组织无 issue 写权限（`Resource not accessible by personal access token`）——#3576 补充评论与 gemma-4 GPU NOT_FOUND 新 issue **转用户手动提交**，文案存档 `_w63_upstream_3576_comment.md` / `_w63_upstream_gemma_gpu_issue.md`（仓外）。| | docs | 收官交接（本文档）+ README 波次段。 |

> 分工说明（如实入账）：刀A/B/F 由开发席（柯码成）完成并自验；刀C 文件产出由开发席在 429 截断前完成、提交与 baseline 补齐由主理人代完成（曾出现一次重复提交 `adf4f3d`，已 squash 归一为 `a4225eb`）；刀D/刀E、容器取证、一行件包、真机验证由主理人完成。开发席曾在刀C 阶段回报过不存在 commit hash（`6e19c62`/`b81c761`）——**再次验证「派单后必须 git status 核实实际产出」纪律**。

## 三、真机复验（OPPO A13 `13309cc8`，有线 adb，debug 包）

| 判据 | 内容 | 态 |
|---|---|---|
| **E-1（刀E 决定性）** | E2B CPU + **AUTO** 档：`thinklen=1414` **非空**（W62 时代 AUTO 因能力位 false 恒空 ⇒ 订正后 AUTO 思考入口真实打开）；`textlen=1326`、`finish=STOP`、settled `ModelStopped`、tmpl_fail=0、rebuild=0 | ✅ |
| **D-1（刀D 决定性）** | R1 + AUTO 档：`thinklen=875` 非空、正文**无** `<think>` 泄漏（W61 J1 的「推理腔泄漏」症状消失——通道切分生效）、`finish=STOP`、settled `ModelStopped` | ✅ |
| D-2（不误伤） | R1 显式 THINK 下发后，gemma E2B CPU 前后两轮回归正常（`thoughtChannelDefsFor` 按模型选择的家族分支互不干扰） | ✅ |
| E-2 / F-1 | 拆分（刀F）后真机文本 run 回归：E2B CPU 长推理题（step-by-step）round0 正文完整结构化作答、无提醒、无 Failed（docs/13 触发条件④「拆分后必须有真机证据兜底」兑现）；4096 阈值在 AUTO 常态流量下安全（thinklen=1414 < 4096 未触边界） | ✅ |
| C-1/C-2（刀C 对话框） | **⚠️ 未行使（工具盲区）**：uiautomator 对玻璃卡片下半区（backendBasis/knownBroken 红字/加载按钮）零捕获（Compose 自绘节点 + 网格单元拉伸互覆盖），本会话无多模态读图能力 ⇒ 点按路径无法自动化取证。对话框逻辑有单测与 #34 守卫兜底；**留用户 10 秒手验**：模型 tab → gemma-4-E4B-it-gpu → 加载 ⇒ 应弹「该容器在当前运行时已知不可用…仍要尝试？」 | ⚠️ |

journal 证据：`_w61_dev/results/w63_e2b_auto_run.jsonl`（E2B AUTO）、`_w63_r1_think_run.jsonl`（R1 AUTO）。

## 四、审查对账

- **B2 补审**（审查席严质衡，本波恢复独立报告）：W62 三刀 **PASS 零阻断**；3×P3 全处置（基线勘误落 README；刀A 三落点全覆盖在刀A 兑现并扩至五落点；提醒文案边界观察项挂 §五.4）。
- **复审19 终稿 8 条建议处置**：①刀F 落地（#12 540→492）✅；②刀E ✅（E-1 真机判据）；③刀D ✅（D-1 真机判据）；④刀C ✅（C-1/C-2 ⚠️ 见上）；⑤K 论证件**第 2 次顺延**（本波 #12 首序 + 七刀挤占产能，见 §五.3）；⑥K-1 治标 ✅（派生化挂 §五.1）；⑦一行件包 ✅（`d37256b`）；⑧selftest 提速见 §五.7、上游出货 ✅（§四末）、口径见 §六.1、429 补位流程 = 本波已行使（主理人代提交 + squash 归一 + 审查席 B2 恢复）。
- **上游出货**：MiniCPM5 tojson = 对 **google-ai-edge/LiteRT-LM#3576** 补充评论（Android 0.17.1 AAR 路径实证 + `minijinja_template.rs` 单参 filter 根因钉点 + 转换期模板 lint 诉求；该 issue 官方已复现在案，不重复开帖）；gemma-4 GPU 双变体 NOT_FOUND = **新 issue**（上游无同型报告；#2461/#3879 均不同签名）。文案存档：`_w63_upstream_3576_comment.md` / `_w63_upstream_gemma_gpu_issue.md`。
- **W63 本波 diff 独立审查**：本波八 commit 中六 commit 落地时经开发席定向测试 + 主理人在盘核验（#12/#31/#34 实测、@Test 严格口径、三落点 grep）；收官全文闸门（arch-guard 34 项 + fulltest 669）双绿。⚠️ 无对 W63 全 diff 的独立审查席回看（配额权衡，审查席 B2 报告已恢复产能；W64 建议回看刀D/E 合并提交与 #34 扫描器）。

## 五、挂账（W64 起；权威版见本节 + README）

1. 🔴 **K-1 治本**：`THINKING_CHAR_BUDGET` 按 maxTokens 派生化（刀B 只落 KDoc+不变式单测；maxTokens ≥4096 残留在案，滑条上限 8192；深度审查 K-1 推荐方案 = 派生值 + 「守卫必须晚于 native」不变式断言）。
2. 🟡 **C-1/C-2 真机手验回收**：knownBroken 对话框真机点验（用户 10 秒）——弹窗文案、确认后仍走原失败路径、红字常驻展示；验证后销账。
3. 🟡 **K 论证件（BuildConfig 化 + 灌测）——第 2 次顺延**（复审19 建议 5 计数要求；触发判据已闭环、现值 `Int.MAX_VALUE` 零风险）。
4. 🟡 **THINKING_BUDGET_REMINDER 文案 E2B/E4B 双变体复验**（B2 P3-3：V4 样本 1 例；E-2 已在 AUTO 态复验 E2B 路径安全，E4B 变体在机即补）。
5. 🟡 **R1 文本侧误判预防性注记**（B6 沿袭）：通道切分后长推理若被 native 预算截断转正文，正文残段仍可能触发文本侧检测器——观察面，不动检测器。
6. 🟡 **knownBroken evidenceLevel 全 schema**（templateSafety/e2eImage/functionalTest + 守卫，N-W3 上层）。
7. 🟡 **selftest 提速出结论**（复审19 建议 8①：本机 ~52min（+58%）四波沿袭——本波因 #12 首序挤占产能未评估，W64 必须给「拆分 --only 子集 / CI 全量不变」的定谳，不再顺延）。
8. 沿袭：法务 `termsVersion`（先于首个 tag）｜0 tags｜main 快照｜fulltest 接 CI｜F4 文字 token 统一｜会话删除 UI｜`TokenUsage.estimated`｜bump 双轴季扫（docs/12 现 13 锚）。

## 六、下次接手须知

1. **静态闸门基线（本波后）**：`arch-guard.sh` **34 项**（+#34 knownBrokenOn 互斥）；`arch-guard-selftest.sh` **PASS=97**（+4：case48 红 / case48b / case49 / case50 绿；93→97；**CI Build job 实测 `PASS=97 FAIL=0`**，本地长跑被沙箱中断未走完）；`fulltest.sh` = 9 模块 tests=**669** failures=1（唯一失败 = core-data `SandboxFileScannerTest` Windows 符号链接，既有且仅 Windows，Linux CI 通过）；`@Test` 严格口径 **669** = baseline；引擎 **2010/2400**；`LiteRtLmEngineLoader.kt` 704；**AgentRunner.kt 2838/2900（余 62）**；**方法级 `executeBodyUnchecked` 492/550（余 58，刀F 拆解）**。
2. 🔴 **「先拆后加」已兑现一次**：AgentRunner 再落新挂账前先看 #12 余量（现 58），余 <20 时启动 docs/13 接缝②（引擎加载重试簇，sealed 改写，中风险）。
3. 🔴 **R1 容器通道取证方法**（复用）：`_w63_r1_scan.py`（全文件字节扫 + protobuf 偏移上下文 dump）；对其它容器做同类取证时先扫 `channel` 再扫 `<think>`；偏移与上下文形态写入 KDoc 引用。
4. 🔴 **uiautomator 对模型卡下半区盲**（本波实证）：玻璃卡片（GlassCard）内 backendBasis/knownBroken/加载按钮节点在 dump 中不出现（Compose 自绘 + 网格单元拉伸互覆盖），且卡片高度超过视口时节点整体缺失——**模型卡点按判据只能靠 journal/logcat 或人眼**；截图判读需多模态会话。
5. 🔴 **重复提交防范**：子代理恢复会话后可能对已在盘的产出再次提交（本波 `adf4f3d` 实例）——主理人代提交后应立即向该子代理发状态同步，squash 前先 `git reflog` 核对。
6. gradle 本机环境同 W62（`_ci-tools/localbuild.sh` 在工作区根；`scripts/fulltest.sh` 需注入 JAVA_HOME/ANDROID_HOME/GRADLE_USER_HOME——**background 任务里跑必须显式 export，否则 gradle 起不来**（本波实摔））；沙箱对 `input keyevent` 部分键值拦截 ⇒ 唤屏用 `svc power stayon true` + `wm dismiss-keyguard`。

## 七、CI run id

| workflow | run id | 结论 | 关键 job |
|---|---|---|---|
| **Build** | `38127758901` | ✅ success（head `8800b8b`） | Lint (baseline gate) / Assemble Debug (JDK 21) / Unit tests 全 success；Assemble 内实测「架构守卫全部通过」（34 项）+「自测结果：PASS=97 FAIL=0」 |
| **Release** | `38127758844` | ✅ success（head `8800b8b`） | — |

**上游提交申报**：fine-grained PAT 对 `google-ai-edge` 组织无 issue 写权限（`Resource not accessible by personal access token`）——#3576 补充评论与 gemma-4 GPU NOT_FOUND 新 issue **转用户手动提交**，文案存档 `_w63_upstream_3576_comment.md` / `_w63_upstream_gemma_gpu_issue.md`（仓外）。|
