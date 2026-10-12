# W66 波次收官交接（Wave 66 Closeout）

> 基线：W65 收官 `3688702`｜本波功能 tip = `a033bdc`（+ 收官文档 commit，见文末 CI 表）
> 前置文档：`docs/handoff-20261012-wave65-closeout.md`（§五 = W66 挂账来源；顶部已有 W66 勘误注记）
> 单一执行依据：`_plans/w66-plan.md`（方案席产出，214 行；含 Q1–Q8 逐问裁定与四态）
> 外部审查（三份，2026-10-12）：第十一审（E1–E7 + §4 W66 路线）｜深度审查 W65（N-1–N-5 + §8）｜issues v19（N1–N3 + §3 路线修补）

---

## 一、一句话 + 关键现实

**W66 = 离线小刀收口波 + 预设证据数据化 + E4B 单文件真机判据波**：① **E2 CRLF 根除**（3 个 `.kt` 归一 LF + `.gitattributes` 钉 `*.kt text eol=lf` —— 陷阱类在 **git 属性层**终结，不再靠操作纪律）；② **E3 探针确定性**（`probeSamplingConfig` 恒钉 `ThinkingMode.OFF` + 预算归零，轴档案正文预览从恒空恢复）；③ **B1 N-2 观测式兜底**（`thinking 非空 ∧ 正文空` = **与 r 无关**的 native 未兑现判据，warn-only 落 `AgentRunner` 空答分支）；④ **B3 evidenceLevel 全 schema + 守卫 #39**（13 预设逐条标 9 MEASURED / 4 UPSTREAM，机械强制「MEASURED ⇒ 块内 `W##` 锚点」）；⑤ **E1a 行数表勘误 + E1b 工具化**（`scripts/line-table.sh`，清单单源派生自 arch-guard）；⑥ **E5 r 测量方法学落档**；⑦ **E4 由「零成本查证」升级为「真机双臂」**（单文件 E4B 存在且 section 齐全，CPU 臂 ✅ / GPU 臂 ⛔）；⑧ 审查席 PASS 准予推送，1×P2+3×P3 全销账。

关键现实（动手前必读）：
1. 🔴 **第十一审 E2 前提失实（源码为准）**：报告称 `ChatRunCoordinator` 是「唯一 i/crlf 孤岛」——**实测 5 个 i/crlf**（3 个 `.kt`：`ChatRunCoordinator` / `ChatViewModel` / `LiquidGlassModifier` + `README.md` + W65 交接）。`core.autocrlf=false`、全仓 279 个 `.kt` 仅此 3 个 CRLF ⇒ 修法范围重定为「3 个 `.kt` + `*.kt` 属性」，**不动 `*.md`**（加了会把大文档整篇重写）。
2. 🔴 **W65 交接另有 2 处错（本波顺手订正）**：①「行数表**八员**」**漏 #18 `ChatViewModel.kt`（550/1600）** ⇒ 实为 **9 员**；②把 `ChatRunCoordinator` 的守卫号写成 **#18**，**实为 #20**（#18 = ChatViewModel）。根因 = 聚合数字手写无机械核对 ⇒ E1b 工具化。
3. 🔴 **E4 结论分两半，勿混为一谈**：单文件 `gemma-4-E4B-it.litertlm`（3,659,530,240 B）**CPU 臂真机 ✅ 可用**；**GPU 臂 ⛔ 但原因是设备显存物理不足**（A13 入门 Adreno：`IOCTL_KGSL_GPUOBJ_ALLOC errno 12 OOM` → `Resource deadlock` → `SIGABRT` → **整机重启**），**不是**容器 section 缺失。两个 `-gpu` 特化变体的 knownBroken 根因（`NOT_FOUND` / 缺 section）是**另一条**失败面，**本波未改变其状态**。
4. 🟡 **C2 r 复测：r_max ≈ 2.26 ≤ 8.4 ⇒ C=16 冻结**（窗口 (8.4,16.8) 不动）。⚠️ 偏差须申报：题面为**英文**（`adb input text` 对非 ASCII 直接 NPE ⇒ **中文题面无法注入**），但模型思维链仍为中文（App 中文系统提示词驱动）⇒ 「中文 thinking 样本」成立、「中文题面」降级。
5. 🟡 **挂账 #5（knownBroken 激活态重校验）本波未做，且报告落点被证不可行**：`core-data` 依赖链不可见 `feature-models` 的 `ModelPresets`（`knownBrokenOn` 定义处），`feature-chat` 亦不可见 ⇒ 报告建议的 `ModelRepository.refresh()` 落点**物理不可达**；可行落点须先把登记迁到 core-model（跨 4 模块），绑 tag 前置波。
6. 🟡 **docs/14 勾选仍 0/6**（六项全部用户侧；本波新增 A1 的「E4B 单文件预设化」候选销账路径见 §五）。

---

## 二、改动清单（4 功能 commit + 1 收官 docs commit）

| commit | 性质 | 内容 |
|---|---|---|
| `2cdfdde` | style(chat,design) | **A3 E2 CRLF 根除**：`.gitattributes` 追加 `*.kt text eol=lf`；`git add --renormalize` 点名 3 路径（`ChatRunCoordinator.kt` / `ChatViewModel.kt` / `LiquidGlassModifier.kt`），2066/2066 行纯 EOL 重写；`i/crlf` **5 → 2**（剩 README + W65 交接，故意保留）。**未用 `sed -i`**（本仓 W65 实摔点） |
| `97da923` | fix(engine) | **A4 E3 探针确定性**：`probeSamplingConfig` 由 `appliedTo(...).copy(maxTokens=96)` 改为 `.copy(maxTokens=96, thinking=ThinkingMode.OFF, thinkingTokenBudget=0)`；KDoc 重写（原「轴被钳到 95」取舍已消除 + **申报 OFF 是模型依赖、待真机抽验**）；`ModelHealthProbeConfigTest` 原「轴档案钳到上限减一」**反向改钉**为「探针恒钉 OFF 且预算归零」 |
| `0d335e1` | feat(agent) | **B1 N-2 观测式兜底**：纯函数 `thinkingOnlyEmptyDiagnostic(thinking, answer, hasLoopMarker)`（判据 = thinking 非空 ∧ 正文空 ∧ 无 loopMarker）+ **单一落点**调用（空答分支 `emptyAnswerStreak++` 之前，`?.let { AgentLogStore.warn(it) }`，**只观测不改行为**）+ 4 例单测；`build.yml` baseline **684 → 688**（同 commit 双向） |
| `a033bdc` | feat(models) | **B3 evidenceLevel 全 schema + 守卫 #39**：`enum EvidenceLevel { MEASURED, UPSTREAM, UNVERIFIED }` + `ModelPreset.evidenceLevel`（13 条逐条显式赋值 = **9 MEASURED + 4 UPSTREAM**，证据全部取自各条既有 `backendBasis`/`note` 文本，**禁推测预填**）；守卫 #39（内联 awk，沿 #23 范式）①缺赋值 ②未知等级 ③MEASURED 无 `W##` 锚点 ⇒ 判红，fail-closed；selftest 新增 **case56/57/58/59/60 五面**（含 56b/57b/59b fail-open 前置断言）+ `KNOWN_GUARDS`(+39) + 映射表 `ModelPresets.kt → "34 39"` |
| `<docs>` | docs | **A1 勘误**（`README.md:459` + W65 交接 §一④/§二/§五.8/§六.2 四处 2709→2712、191→188 + 顶部勘误注记 + #18→#20 守卫号订正 + §六.2 补「W65 冻结帧 / 现行值以 `line-table.sh` 为准」）+ **A2 `scripts/line-table.sh`**（清单**单源派生**自 `arch-guard.sh` 的「总行数 ≤」守卫，9 员齐全含 ChatViewModel，`--check` 自证）+ **A5 E5 r 测量方法学**（`ThinkingBudget.kt` KDoc 88→102 行 + `docs/12` 复核流程）+ **A6 E4 落档**（README 挂账台账行）+ **审查 P2/P3 销账**（`ModelHealthProbeConfigTest:35` 过时注释改述、`arch-guard.sh #39` KDoc 补缩进纪律、§六.2 冻结帧注记）|

| workflow | run id | 结论 | 关键 job |
|---|---|---|---|
| **Build** | 见 §七 | — | Assemble 内 arch-guard 39 项 + selftest |
| **Release** | 见 §七 | — | — |

> 分工说明（如实入账）：A3/A4/B1/B3 由开发席（柯码成）完成并自验（编译 + 定向单测 + arch-guard 39 OK）；A2 + workflow 触发核对 + baseline 双向机制核验由 CI 席（崔续程）完成（**并纠正了主理人派单中 `_ci-tools/fulltest.sh` 不存在的错误 —— 正确入口是仓内 `scripts/fulltest.sh`**）；A1/A5/A6 与全部收官文档由主理人完成；E4 索引实拉与 section 取证由主理人完成（`_ci-tools/_w66_section_scan.py`）；真机批由开发席完成；全量 diff 审查由审查席（严质衡）完成。

---

## 三、真机复验（OPPO A13 `13309cc8`，有线 adb，debug 包，MemTotal 11.8 GB）

### 3.1 C3 E4B 单文件双臂

| 臂 | 判据 | 实测 | 态 |
|---|---|---|---|
| **CPU** | `settled` / thinking / 正文 / tmpl_fail / FATAL / NOT_FOUND | `ModelStopped`(rounds=0) / **177 字符** / 正文 `OK`(len 2) / 0 / 0 / **0** | ✅ |
| CPU 对照 | `gemma-4-E2B-it.litertlm`（CPU）同题面 | `ModelStopped` / 1173 字符 / 正文 `OK` / 0 / 0 / 0 | ✅ |
| **GPU** | 同上 | **无 `settled`（进程被驱动/内核强杀）**；`NOT_FOUND`=**0**；`SIGABRT` → `am_crash: Native crash` → **整机重启** | ⛔ |

GPU 崩溃链（设备侧持久日志）：`sampler_factory.cc:408 OpenCL sampler not available … Could not load libLiteRtTopKOpenClSampler.so` → `Adreno-GSL: IOCTL_KGSL_GPUOBJ_ALLOC failed: errno 12 Out of memory`（数百条）→ `IOCTL_KGSL_DEVICE_WAITTIMESTAMP_CTXTID errno 35 Resource deadlock` → `F Adreno-GSL: Exiting the process … cl_a6x_cmdbuf_mgr_submit_ibs:1068` → `F libc: Fatal signal 6 (SIGABRT)`。
⇒ **A13 入门 Adreno 对 3.66 GB 单文件的 GPU 路径物理不可用**；`ModelGpuSupport` 白名单「未列入 = 防 native 闪退」的设计被本实证**反向确认**。**临时白名单补丁已还原**（`git diff ModelGpuSupport.kt` 为空）。
证据：`_w66_dev/results/e4b_cpu_run.jsonl`（+ `e4b_cpu_log.txt`）、`_w66_dev/gpu_device_log.txt`；A/B `results/e2b_cpu_run.jsonl`。

### 3.2 C1 E3 探针 OFF 抽验（判据 = 探针正文预览非空）

| 模型（CPU） | 正文预览 | 态 |
|---|---|---|
| `MiniCPM5-2B_int4` | **非空**（「作为AI助手，我能为你提供信息…」） | ✅ |
| `gemma-4-E2B-it` | **非空**（「我是一个大型语言模型…」） | ✅ |
| `DeepSeek-R1-Distill-Qwen-1.5B` | **（空）** | ⛔ |

⇒ **A4 机制被正向验证**（MiniCPM5 从 W65 的「正文恒空」恢复为真实正文）；**R1 ⛔ = `enable_thinking=false` 对 R1 容器不生效（模型依赖）**，与 A4 KDoc 的预判一致。
⚠️ **判据落点订正（源码为准）**：探针正文 `sample` **只走 UI**（DiagnosticsScreen 渲染），**不进 journal/logcat** ⇒ 计划 §3 C1 写「journal/logcat」与源码不符，实际用 uiautomator dump 读探针详情行。

### 3.3 回归 + B1 假阳性检查

`gemma-4-E2B-it.litertlm`（CPU）+ AUTO 正常题面：`settled=ModelStopped`、thinking 非空、正文非空、`Failed to apply template`=0、`FATAL`=0，**B1 新增 warn 零误报** ✅。

### 3.4 C2 E5 r 复测（离线 tokenizer 精确计数，未用 `TokenEstimator` 冒充）

| 样本 | 字符 | token | r |
|---|---|---|---|
| R1 推理题 4 块合计 | 1926 | 1042 | 1.848 |
| R1 推理题最大单块 | 720 | 318 | **2.264 ← r_max** |
| R1「Say OK」 | 520 | 268 | 1.940 |
| R1 关工具单轮 | 505 | 330 | 1.530 |

⇒ **r_max ≈ 2.26 ≤ 8.4 ⇒ C=16 冻结**（窗口 (8.4,16.8) 不动）。计划担心的「中文 markup 密集 ⇒ r>8.4」**未出现**（中文 ≈1.08 CJK 字/token）。
⚠️ 偏差申报：题面为英文（**中文无法注入**：`adb input text` 非 ASCII 直接 NPE、`cmd clipboard` 无实现、无 share/PROCESS_TEXT 入口），思维链仍中文 ⇒ 「中文题面」降级。旁证：`TokenEstimator.estimate`=1379 token（真值 1042）**高估 token ⇒ r 低估**，印证「软样本不得当硬证据」。

---

## 四、审查对账

- **审查席（严质衡）独立深审 PASS，准予推送，无 P0/P1**：A3 用 **CR-strip 后 hash-object 相等**证明 3 个 `.kt` **零内容变化**（比肉眼更硬）；B1 **单一落点**（全仓 grep 仅 1 处生产调用）+ `hasLoopMarker` 恒 false 的论证经 `handlePostStreamSignals` 全路径核对成立；B3 **自建 harness 独立复现守卫 #39 的「四红一绿」**（MEASURED 去锚点 / 未知等级 / 缺赋值 / 块界破坏全判红；删声明 → 真跑 arch-guard 报 `::error::`）⇒ fail-closed 成立；架构铁律（`AppContainer` 零改动、core-agent 无 litertlm import、零 Operit 引用、`check-test-void.py` exit 0）全绿；`arch-guard` **39 项全 OK**、`@Test` **688 == baseline 688**。
- **B3 一处判断题经审查席独立裁定**：MiniCPM5 标 **`UPSTREAM`** 正确（其 GPU 放行依据在 `ModelGpuSupport` 注释里明写为上游 Galaxy S26 基准，本条文本内无本仓 `W##` 锚点；硬标 MEASURED 会被 #39 判红）。附注：字段语义是「**本条文本内**的锚点」而非「本仓是否测过该模型」—— 如需反映 W44/48/49 真机史，须先回填本条目文本再升级（**本波不改**）。
- **1×P2 销账**：文档行数值漂移（§六.2 是 W65 冻结帧 2712/88，tip 实测 2773/102）⇒ §六.2 补「冻结帧 + 现行值以 `scripts/line-table.sh` 生成物为准」。
- **3×P3 全部处置**：`ModelHealthProbeConfigTest:35` 过时注释改述；`arch-guard.sh #39` KDoc 补「新增预设须沿用 8 空格缩进」；3 个归一 `.kt` 的工作树副本仍 w/crlf（**无害**：index/HEAD 已 LF、`eol=lf` 属性保证下次 add 仍存 LF）。
- **主理人独立复跑**：`arch-guard` **39 OK / 0 FAIL / exit 0**；`line-table.sh` 9 行；`@Test` 688 == baseline 688（三方一致）。

---

## 五、挂账（W67 起；权威版见本节 + README）

1. 🔴 **bump 首跑判据**（沿袭）：docs/12 collapse 锚 blocker + 锚 #5 缺失即阻断 P0；**未升级验证前不要 bump**。
2. 🟡 **E4B 单文件预设化（本波新增，CPU 臂已 ✅）**：`gemma-4-E4B-it.litertlm` 真机 CPU 可用（`ModelStopped` / thinking 177 / 正文非空 / 零 NOT_FOUND），但**尚未入 `ModelPresets`**（用户只能手动 push + 扫描登记）。**入预设的前置 = 实测峰值内存**（`dumpsys meminfo` 于 run 中采样）才能填 `requiredRamBytes` / `memBasis` —— **禁推测预填**（与 B3 同一条纪律）。⚠️ 注意 `-gpu` 特化变体仍 knownBroken，新条目须与它们**区分**（本条目是 CPU 单文件，GPU 路径在 A13 上物理不可用）。
3. 🟡 **gemma-4 GPU 用户故事仍未解（E4 GPU 臂 ⛔ 但原因=设备显存）**：单文件 GPU 在 A13 上因 `errno 12 OOM` 崩溃 ⇒ **换更高显存设备可复测**；上游 `-gpu` 特化变体的 `NOT_FOUND` 失败面**本波未触及** ⇒ docs/14 §B2 的 issue 仍应提交（证据链可加「单文件 GPU 在入门 Adreno 上 OOM」这一条对照）。
4. 🟡 **C 复测收尾**：r_max ≈ 2.26 ≤ 8.4 ⇒ C=16 冻结；「中文题面」降级为英文题面（中文注入不可行，见 §3.4）。
5. 🟡 **挂账 #5 knownBroken 激活态重校验（落点订正）**：报告建议的 `ModelRepository.refresh()` **物理不可达**（模块依赖）⇒ 须先把登记迁到 core-model（与 `ModelGpuSupport` 同层同范式），跨 core-model/feature-models/core-design/feature-chat 四模块；**绑 tag 前置波**。
6. 🟡 **接缝②仍不启动**：AgentRunner **2773**/2900（余 127）、Coordinator 1204/1300（余 96）、`executeBodyUnchecked` 498/550（余 52）。
7. 🟡 沿袭：C-1/C-2 对话框手验（docs/14 §A1，用户 10 秒）｜法务 `termsVersion`（先于 tag）｜0 tags｜main 快照过时｜fulltest 接 CI｜F4 文字 token 统一｜会话删除 UI｜`TokenUsage.estimated`｜bump 双轴季扫｜上游出货 B1/B2（用户手动）｜审查 P3-d。

---

## 六、下次接手须知

1. **静态闸门基线（本波后）**：`arch-guard.sh` **39 项**（+#39 evidenceLevel 全 schema，#25 家族 9 员不变）；`arch-guard-selftest.sh` PASS=**119 + case56–60 五面**（**新增守卫 case 必同步 `KNOWN_GUARDS`/映射表/`META_CASES`**）；`fulltest.sh`（**仓内 `scripts/fulltest.sh`**，非 `_ci-tools/`）= 9 模块；`@Test` 严格口径 **688** = `build.yml:497 baseline`。
2. **行数表九员**（**现行值一律以 `bash scripts/line-table.sh` 生成物为准**，勿手写）：ChatViewModel 550/1600｜ChatRunCoordinator 1204/1300｜LiteRtLmEngine 2009/2400｜AgentRunner **2773**/2900（余 127）｜LiteRtLmEngineLoader 704/900｜AgentRunnerToolSetup 108/400｜ThinkingBudget **102**/150（余 48）｜AgentRunnerLoopHelpers 209/400｜ChatRunRecovery 129/400。
3. 🔴 **K-1 单源链（四处）**：core-engine `THINKING_CHARS_PER_TOKEN_CEIL`/`thinkingCharBudgetFor` ↔ core-model `THINKING_CHAR_BUDGET`(8192) ↔ `ThinkingBudgetInvariantTest` ↔ 轴档案 `minThinkingTokenBudget`。**r 测量方法学**（W66 A5）已落 `ThinkingBudget.kt` KDoc + `docs/12`。
4. 🔴 **`.gitattributes` 已钉 `*.kt text eol=lf`** ⇒ CRLF 陷阱类在 git 层根除；`README.md` / `docs/*.md` 仍是 CRLF（**故意**），编辑它们**不要用 `sed -i`**（会翻转 EOL，Edit 工具安全）。
5. 🔴 **adb 双坑**：`export MSYS_NO_PATHCONV=1` **且源路径写 Windows 形式**（`D:/WBfil/...`；写 `/d/WBfil/...` 会 `cannot stat` —— 本波实摔）；驱动坐标输入框 `(540,1969)`、发送按钮动态 dump `content-desc="发送"`；**Bash 沙箱对多行 `.sh` 内的 `adb exec-out > file` 会偶发空捕获 ⇒ 用单行命令链**。
6. 🔴 **中文题面无法注入**（`adb input text` 非 ASCII NPE）⇒ 真机批题面一律英文，中文能力靠「模型思维链仍中文」间接覆盖。
7. **E4 取证工具**：`_ci-tools/_w66_section_scan.py <file> [head_bytes]`（扫 `.litertlm` 头部 section 名）；**HF 直连与 hf-mirror 在本沙箱均不通，走 ModelScope API**（`curl --ssl-no-revoke`）。
8. **docs/14 = 外部依赖一等进度指标**：本波仍 **0/6**（全部用户侧）。

## 七、CI run id

| workflow | run id | 结论 | 关键 job |
|---|---|---|---|
| **Build** | 待回填 | — | Lint (baseline gate) / Unit tests / Assemble Debug (JDK 21)；Assemble 内「Architecture guard」（39 项）+「Architecture guard self-test」 |
| **Release** | 待回填 | — | — |
