# W61 波次收官交接（Wave 61 Closeout）

> 基线：W60 收官 `06505b7`｜本波 tip = `（收官刀后回填）`（7 刀代码/文档 + 1 刀收官交接）
> 前置文档：`docs/handoff-20261010-wave60-closeout.md`（§五 = W61 挂账来源）
> 方案：`_plans/w61-strategy.md`（仓外，方案席沈思远）｜主理人取证：`_plans/w61-lead-recon.md`｜真机计划+结果：`_plans/w61-device-plan.md`｜审查：审查席严质衡报告
> 真机取证：`_w61_dev/`（`repro_minicpm5.log` + `results/*_run.jsonl` + `*_log.txt`）

---

## 一、一句话 + 关键现实

**W61 = 定案波**：把两份外部审查的**核心因果全部推翻**并用五条独立证据链定案 MiniCPM5 首发模板失败的真实机制（**原生工具通道 tools 段**，非 fold）；据此处置 docs/12 锚 #13 + 引擎 KDoc 限定 + 预设回写 ×7 + DeepSeek-R1 thinking 判据修复；并完成真机批（J1/J2/J3）与全量静态闸门核验。

关键现实（动手前必读）：
1. 🔴 **W60 MiniCPM5「首发模板失败」= 原生工具通道（native tool channel）路径下渲染模板 `tools` 段失败**，根因 = 容器 `chat_template:6` 的 `{{- tool | tojson(ensure_ascii=False) }}` 与 litertlm 0.17.1 内嵌 **Rust** minijinja 的单参 `tojson` filter 不兼容。**与 fold / `requires_typed_content` 完全无关**（离线实测 7/7 容器 rtc=false；全档无「多元素 content 下发」行）。第七审 §3.1 三假说、W60 深审 J-1 因果**均不成立**。
2. 🔴 **W60 交接 §3.2 表格张冠李戴**（本波已订正）：`Unsupported model type` 全档只属 MiniCPM-V-4；gemma GPU 两变体实为**引擎初始化** `NOT_FOUND`（`TF_LITE_PREFILL_DECODE not found` / 缺 VISION·AUDIO encoder section）。
3. 🔴 **K 值维持 `Int.MAX_VALUE`**（**未**按两份审查建议回填 K=3）：W60 样本非 fold 路径 + 7/7 rtc=false ⇒ 「非自愈」路径不可达 ⇒ 拍 K=3 属无据早优化。**可判定触发判据**见 §五。
4. 🔴 **DeepSeek-R1 修复已落地并生效，但用户可见症状未解**（本波 J1 负面结果）：`capabilities.thinking` 由 false→true、AUTO 档 `thinkingOn` 由 false→true，**症状一字未变**（2×`LENGTH` + 2 次「轮内重复检测触发（thinking=false）」）⇒ 真实成因在**思考通道未切分**，不在能力位（`inThinking` 由增量走哪条流决定，**不读** `capabilities.thinking`，见 `ThoughtChannels.kt:42`）。
5. 🔴 **证据链措辞纪律**（审查 P2-1）：离线复现（PyPI Python minijinja）报 `unexpected keyword argument 'ensure_ascii'`，**≠** 真机 Rust 版 `too many arguments (in template:6)`。**同因不同串**，不得写「逐字同型」。

---

## 二、改动清单（7 刀）

| commit | 性质 | 内容 |
|---|---|---|
| `03828b1` | test(guard) | 守卫 #33 判据① 加注释位排除（与 #24/#28/#30 同款）+ selftest case47（绿面） |
| `dd5a1c2` | docs(engine) | MiniCPM5 定案注记：docs/12 **锚 #13**（12→13 项）+ `foldAdjacentText` KDoc「不覆盖范围」限定句 + `probeNativeTools` KDoc「探针保真度缺口」节 + `handleTemplateRenderFailure` 补段（**全为注释，零可执行语句改动**） |
| `1758fac` | docs(models) | 预设回写 ×7（Qwen2-VL 2B / LFM2.5-VL 3B ✅；gemma E2B·GPU / E4B·GPU / MiniCPM-V-4 ❌ 按**真实**签名；gemma E2B CPU；MiniCPM5 补原生通道不可用说明） |
| `38fd739` | fix(model) | DeepSeek-R1 thinking 判据（OTHER 分支 `r1`/`reasoner`/`deepseek`+`distill`）+ 3 单测 + **baseline 649→652（同 commit 双向）** |
| `272aa08` | docs | `docs/13-agentrunner-split-plan.md` 首刀预案成文（**零改动落 AgentRunner**） |
| `1902e71` | docs | W60 交接 §3.2 表格订正（gemma 两条失败层级）+ 订正小注 |
| `30875bb` | docs | 审查修正刀：P2-1 措辞 + P3-1/P3-2/P3-3 |

⇒ 引擎 **1990 → 2008**（#25 上限 2400，余 392）；`LiteRtLmEngineLoader.kt` 704（#32 ≤900）；`AgentRunner.kt` 2802（#31 ≤2900，**方法级 #12 `executeBodyUnchecked` 540/550 余 10**）；守卫 **33 项**（不变）；`@Test` **652** = baseline。

---

## 三、真机验证（本波判据）

设备 OPPO A13 `13309cc8`（有线 adb）；APK = W61 工作树构建；`adb push` + `pm install -r` Success。

| 项 | 判据 | 态 |
|---|---|---|
| **J1 DeepSeek-R1 + AUTO** | `models.json` 刷新后 R1 `capabilities.thinking = True`（HEURISTIC）✅ 修复生效；但 `settled=ModelStopped` 下 `finishReason` 仍 2×`LENGTH`+1×`STOP`、2 次 `轮内重复检测触发（thinking=false）`、输出仍推理腔 ⇒ **症状未解**（负面结果） | ⚠️ 部分 |
| **J2 MiniCPM5 回归** | `tmpl_fail=2 / rebuild=1 / fatal=0`；回复 `这是最终答案。`（STOP）⇒ 文本协议路径不受影响、自愈链按设计工作 | ✅ |
| **J3 Qwen2.5-1.5B** | 原生 `TOOL_CALLS` → round1 `当前可用的子代理有：planner, critic, summarizer。`（STOP）；`tmpl_fail=0` | ✅ |
| **J3 LFM2.5-VL-1.6B** | `OK`（STOP）；`tmpl_fail=0` | ✅ |
| **J3 gemma-4-E2B-it（CPU）** | `OK`（STOP）；`tmpl_fail=0` | ✅ |
| **冒烟（第 7 刀后）** | `LFM2.5-VL-1.6B` → `OK`（STOP）/ `settled=ModelStopped` / `tmpl_fail=0` / `fatal=0`（设备 lastUpdateTime `2026-10-11 00:42:01`） | ✅ |

⇒ **5+1 次真机运行全部 `settled=ModelStopped`、零 FATAL**（5 项判据 + 1 次收官冒烟）；唯一模板失败 = MiniCPM5 的**已定案**不兼容（自愈有效）。

---

## 四、审查对账

- 审查席（code-quality-reviewer）：**⚠️ 需修改**（0 P0 / 0 P1 / 1 P2 / 3 P3）。
- 关键独立验证：守卫 #33 **反向验证**（删真调用点 ⇒ 判红）、case47 **非 vacuous**（还原守卫 ⇒ 该 case FAIL）、DeepSeek 判据**变异测试**（还原为 false ⇒ 单测红）、13 预设名回归扫描（仅 R1 翻 true）、数字全部复算吻合。
- P2-1（docs/12 措辞）+ P3-1/P3-2/P3-3 已在本波第 7 刀全部修正。

---

## 五、挂账（W62 起；权威版见本节 + README）

1. 🔴 **DeepSeek-R1「推理腔/重复循环」根因重定位（新，本波 J1 产出）**：根因在**思考通道未切分**，非 thinking 能力位。W62 需按本仓纪律（「只有一手证据证明容器元数据**不含**该通道的家族才给显式字面量」）取证 R1 容器是否声明 `<think>` 通道，再决定是否给 `ChannelSyntax.THINK`。⚠️ R1 容器**无明文 jinja 模板**（`bos_token`/`tojson(`/`message.role` 全档 0 命中），取证需另找路径。
2. 🔴 **K 值回填触发判据（改写为可判定形式）**：任一容器实测 `requires_typed_content=true` **或**出现「证伪后仍每 run 必炸」的失败面 ⇒ **立即回填 K=3**（依据 = 该容器每轮必炸 + W56 Arm B 的 1 次收敛样本，×3 余量）。当前保持 `Int.MAX_VALUE`。
3. 🟡 **AUTO 档 thinking 预算偏小（新）**：`thinkingOn=true` ⇒ `ThinkingConfig` 预算 = `resolveThinkingBudget(0, 2048)` = **1024**，而 R1 采样档案自述「思维链需 ≥2048」⇒ 潜在截断风险（本波未见可测退化）。
4. 🟡 **AgentRunner 首刀**：`executeBodyUnchecked` **540/550（#12 余 10）**；真接缝在方法内（工具装配簇 `:637-693` 等，见 `docs/13`）；触发条件：≥545｜文件 ≥2880｜任一挂账落 AgentRunner｜真机批通过。**禁改 #12/#31 阈值**。
5. 🟡 **N-W3 `evidenceLevel` 数据化**（第七审 §3.4 schema 已备，本波推迟）：`ModelPreset` 加字段 + UI 硬提示 + 3 守卫。
6. 🟡 **上游 issue**（素材本波空前充分）：头号 = MiniCPM5 容器模板 `tojson(ensure_ascii=False)` 与 minijinja 2.14.0 不兼容；次号 = gemma-4 GPU 变体引擎初始化 NOT_FOUND。
7. 沿袭：多模态 GPU 组合待验｜bump 双轴季扫（`docs/12` 现 **13 项**）｜会话删除 UI 立项｜`TokenUsage.estimated`｜法务 `termsVersion`｜0 tags｜main 快照｜fulltest 接 CI｜selftest 提速（本机 ~52min）。

---

## 六、下次接手须知

1. **静态闸门基线（本波后）**：`arch-guard.sh` **33 项**；`arch-guard-selftest.sh` **PASS=93**（+case47，本机 ~52min）；`fulltest.sh` = tests=**652** failures=1（唯一失败 = core-data `SandboxFileScannerTest` Windows 符号链接，**既有**）；引擎 **2008/2400（余 392）**；`LiteRtLmEngineLoader.kt` 704（#32 ≤900）；`AgentRunner` 2802（#31 ≤2900，**方法级 #12 余 10 才是真压力**）。
2. 🔴 **`@Test` 计数必须用严格口径**：`grep -rnE "^\s*@Test\b" --include=*.kt . | wc -l`（= 652）；宽松 `grep "@Test"` 会多算 **7 处 KDoc 引用**（= 659）。
3. 🔴 gradle 本机环境：`_ci-tools/localbuild.sh`（**工作区根**）；JDK/SDK/gradle 缓存全在 `_j2env/`。
4. 🔴 **真机（有线 adb）**：`MSYS_NO_PATHCONV=1`；OPPO A13 走 `push` + `pm install -r`；脚本 `_ci-tools/_w61_test_model.sh`（换模型+发 prompt+收 journal）、`_w61_set_model_auto.sh`（active_model_id + ThinkingMode=AUTO 一次写入）、`_w61_repro_minicpm5.sh`（MiniCPM5 复现）、`_w61_patch_pb2.py`（**改 datastore JSON 长度时的 protobuf 长度前缀修正**）。
5. 🔴 **thinking 相关验证必须设 AUTO**：`capabilities.thinking` 只经 `LiteRtLmEngine.kt:1057-1061` 的 AUTO 档生效；OFF 档恒下发 `enable_thinking=false`，不构成修复判据。

---

## 七、CI run id

> **推送后回填**。

| workflow | run id | 结论 | 关键 job |
|---|---|---|---|
| **Build** | 待回填 | | |
| **Release** | 待回填 | | |
