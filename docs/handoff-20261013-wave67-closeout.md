# W67 波次收官交接（Wave 67 Closeout）

> 基线：W66 收官 `50def5f`｜本波 5 个功能/文档 commit：`5c7c1a9` → `bfcba77`（见 §二）
> 单一执行依据：`_plans/w67-plan.md`（方案席产出，v2 返工版；审查报告 `_plans/w67-review.md`）
> 前置：W66 交接 §五（挂账权威版）+ 六份外部审查（复审21 / 深审 W66 / 第十二审 / 新版现状 / 旧版深审 / W66 交接）

---

## 一、一句话 + 关键现实

**W67 = 守卫网收口 + 思考预算重标定 + 预设证据回填 + 注册表迁移 + E4B 预设化波**：
① **B0 开波小刀**（守卫 #39 尾注释/锚点字面量段再收紧 + case61c、line-table 方法级条目、协议围栏大小写、D10 vacuous 改钉、文档勘误组）；
② **B1 思考字符预算重标定**（r_max 离线重测推翻 W62 的 8.4 口径伪影 ⇒ C 16→9、窗口 (5.06,10.11)、`THINKING_CHAR_BUDGET` 8192→4608；F1 第三态裁决 = best-effort 申报）；
③ **B3 evidenceLevel 回填**（4 条 UPSTREAM → MEASURED，全部有本仓 W60 台账 #45 等锚点）；
④ **B2 迁移刀**（ModelPresets 迁 core-model，与 ModelGpuSupport 同层；功能刀留 W68）；
⑤ **B4 E4B 单文件预设化**（meminfo 双点实测入册 + GPU 白名单排除 + 真机复验）。

关键现实（动手前必读）：
1. 🔴 **W62 的「实测上界 8.4 chars/token」是口径伪影（W67 证伪）**：「2048 字符 ≈ 244 token」的 244 = 引擎 `completionTokens`（内容 **chunk 数**，且 thinking chunk 不计入），非 thinking token 数。同样本用 gemma 官方 tokenizer 计 2051 字符 = **469 token**（r = 4.37）。**W66 E5 建立了「离线 tokenizer 精确计数」方法学却未回头复核 8.4 本身**，本波补做。
2. 🔴 **W67 全量实测 r_max = 5.06**（W62/W65/W66 三波 31 样本，禁挑样本；W66 E5 落档 4 值被同一脚本逐位复现 ⇒ 尺子可信）⇒ C 16→**9**、窗口 → **(5.06, 10.11)**、`THINKING_CHAR_BUDGET` → **4608**（四处单源链同批同步）。半预算兜底阈 r > 4.5 恢复可达（实测英文思维链 r≈4.5–5.1）。
3. 🟡 **F1「OFF 档无预算」第三态裁决 = 选项 B（best-effort 申报，零行为改动）**：对不服从 `enable_thinking=false` 的显式通道模型（DeepSeek-R1 类），OFF 档无 native 预算约束、字符守卫休眠、轴档案不下发 ⇒ 唯一探测面 = N-2 warn + 锚 #5 墙钟；改强抑制须先过真机双臂。
4. 🟡 **B2 只落迁移刀**：ModelPresets 已迁 core-model（登记自此对 core-data/feature-chat 可见），**功能刀（refresh 合并点 + 对话页内联提示）留 W68**（先拆后加纪律）。
5. 🟡 **E4B 单文件真机实测**：峰值 PSS ≈ **5.66 GiB**（RSS 5.05 GiB，swap ≈1.0 GiB）⇒ `requiredRamBytes` = 峰值 ×1.25 ≈ **7.1 GiB**；CPU 首字约 29s。
6. 🟡 **D7（R1 探针 verdict）与 2047 补丁臂本波未行使**（真机窗口用于 E4B + E2B 回归）⇒ 留 W68 如实挂账，不静默。

---

## 二、改动清单（5 commit）

| commit | 性质 | 内容 |
|---|---|---|
| `5c7c1a9` | fix(ci) | **B0**：守卫 #39 两处收紧（`strip_comment` 剥行内注释 + `field_lit/has_anchor` 锚点只认字面量段）+ case61c 一夹具双杀两形态；`line-table.sh` 增**首个方法级条目** #12（501/550 余 49，单源派生）；`TextToolProtocol` 围栏标记剥离 ignoreCase + 2 测；`ModelHealthProbeConfigTest` D10 vacuous 断言改钉；README/交接文档勘误组（#12 501 / W62 2008 / N-1 1.33×）；`build.yml` baseline 688→690 |
| `0141f11` | fix(engine,model) | **B1**：C 16→9、窗口 (5.06,10.11)、`THINKING_CHAR_BUDGET` 8192→4608、双不变式测试断言 8.4→5.06、KDoc 推导段全文重算；F1 第三态 best-effort 申报 + 适用前提补注；docs/12 增「定案一」；W66 交接 §五 W67 勘误注记②③（勘误归属订正（复审22 P4①）：注记① #12「498→501/550 余 49」实为 **B0** 文档勘误组件，数字源出 B0 的 line-table 方法级条目；②③ 为 B1 件） |
| `d5b304d` | docs(models) | **B3**：4 条 UPSTREAM → MEASURED（Qwen2.5 / R1 / MiniCPM5 / Phi-4-mini，锚点全部取自 W60 台账 #45 等本仓记录） |
| `6825ab6` | refactor(model) | **B2 迁移刀**：ModelPresets 迁 core-model（package + 5 消费方 import + 守卫 #34/#39 路径 + 12 处 selftest 夹具路径 + scaffold 铺目录） |
| `bfcba77` | feat(models) | **B4**：E4B 单文件预设入册（meminfo 双点实测 7.1 GiB + GPU 白名单排除 + 与两个 -gpu 变体三处可分辨） |

---

## 三、真机复验（OPPO A13 `13309cc8`，有线 adb，W67 包 12:39 安装）

### 3.1 E4B 单文件 CPU 臂 + 内存双点采样（B4）

| 项 | 实测 |
|---|---|
| 运行 | `in 1365 / out 1`，正文 `OK`，零 FATAL |
| 加载后静置 | PSS **5.66 GiB**（RSS 5.05 GiB，swap ≈1.0 GiB） |
| 生成峰值 | PSS **5.66 GiB**（两点几乎相等 = 3.41 GiB 权重主导） |
| CPU 首字 | **28972 ms** |
| `requiredRamBytes` | 峰值 ×1.25 ≈ **7.1 GiB**（`memBasis` = `BASIS_CPU_E4B_MEASURED`，记两点+设备） |

### 3.2 W67 回归（B1 的 C=9 不得破坏正常 run）

`gemma-4-E2B-it`（CPU）题面 `OK`：`in 1365 / out 8`、正文非空（「请告诉我您希望我为您做什么。」）、
`思考过程` 非空、**`FATAL`=0、`Failed to apply template`=0** ⇒ 无回归。

### 3.3 未行使项（如实挂账）

- **D7（R1 探针 verdict 读取）**：未跑（真机窗口用于 E4B + E2B）⇒ 留 W68。
- **B1 补丁臂 2047**（minThinkingTokenBudget 1536→2047 迫使 N-2 warn 命中）：未行使 ⇒ 留 W68。
- 题面英文（`adb input text` 非 ASCII NPE，W66 已申报）。

---

## 四、审查对账

- **计划稿对抗式深审**（`_plans/w67-review.md`，严质衡）：v1 计划 P0×6/P1×4/P2×3/P3×2（六报告判读表虚构、R1-R4 张冠李戴、B2/B4 错位、真机判据抽象化、设备矩阵虚构、用户议程虚构）⇒ 方案席返工 v2 后可用。
- **主理人独立复现关键证据**：① r 重测脚本实跑复现 r_max=5.06 + W62 事故样本 469 token（r=4.37）+ W66 E5 四值逐位一致；② arch-guard 39 OK / selftest `--only 39` PASS=15 / `--only 34,39` PASS=20 / line-table `--check` RC=0 均本机实测。
- **迁移一致性**：core-model/feature-models 编译通过、arch-guard 39 OK、selftest 34/39 全绿。

---

## 五、挂账（W68 起；权威版见本节 + README）

1. 🟡 **B2 功能刀 = knownBroken 激活态重校验**（迁移刀已落）：`refresh` 合并点对 active 模型查 `knownBrokenOn` → 对话页内联提示；**tag 前硬前置**。
2. 🟡 **D7（R1 探针 verdict）+ B1 补丁臂 2047**（真机窗口顺延）。
3. 🟡 **F1 选项 A 真机臂**（若议强抑制：R1 OFF+budget=1 双臂 + gemma/MiniCPM5 回归）。
4. 🔴 **bump 首跑判据**（沿袭，未验证前不 bump）。
5. 🟡 **evidenceLevel 接线（ModelCard 展示）**：需产品裁定（D1 重启前提）——回填已完成，接线可随时做。
6. 🟡 **gemma-4 GPU 用户故事**（换高显存设备复测；§B2 issue 仍应提交）。
7. 🟡 沿袭：法务 `termsVersion`（先于 tag）｜0 tags｜main 快照过时｜fulltest 接 CI｜F4 文字 token｜会话删除 UI｜`TokenUsage.estimated`｜bump 双轴季扫｜上游出货 B1/B2（用户手动）｜审查 P3-d｜旧报告 🟡-1 工具超时竞态（附 `ToolExecutionGuard.kt` 新拆建议）。
8. 🟡 **docs/14 六项用户侧**（A1 手验 / B1 #3576 评论 / B2 gemma GPU issue / C1 termsVersion / C2 tag 拓扑 / C3 删除 UI）——F4 会话模式建议（15-20 分钟议程）。

---

## 六、下次接手须知

1. **静态闸门基线（本波后）**：`arch-guard.sh` **39 项**；`arch-guard-selftest.sh` **PASS=134**（W66 的 133 + case61c；本地 `--only 39`=15、`--only 34,39`=20 实测）；`@Test` 严格口径 **690** = `build.yml baseline`；`fulltest.sh`（仓内 `scripts/fulltest.sh`）= 9 模块。
2. **行数表十行**（九文件级 + 一方法级；现行值以 `bash scripts/line-table.sh` 为准）：ChatViewModel 550/1600｜ChatRunCoordinator 1204/1300｜LiteRtLmEngine 2009/2400｜AgentRunner **2773**/2900（余 127）｜Loader 704/900｜ToolSetup 108/400｜ThinkingBudget **128**/150（余 22，B1 KDoc +26）｜LoopHelpers 209/400｜ChatRunRecovery 129/400｜**方法级 `executeBodyUnchecked` 501/550（余 49）**。
3. 🔴 **K-1 单源链（四处，现行值）**：core-engine `THINKING_CHARS_PER_TOKEN_CEIL`=**9** ↔ core-model `THINKING_CHAR_BUDGET`=**4608** ↔ 双不变式测试（`measuredCharsPerTokenCeil`=**5.06**）↔ 轴档案 `minThinkingTokenBudget`=1536。改任一处同步其余。
4. 🔴 **r 测量方法学 + 重测结论**：`_ci-tools/_w67_r_retest.py`（仓外）；结论表 = r_max 5.06 / W62 事故样本 469 token。**重跑命令**：`_w66_dev/venv/Scripts/python.exe _ci-tools/_w67_r_retest.py`。
5. 🔴 **ModelPresets 已在 core-model**（`core-model/.../core/model/ModelPresets.kt`）；守卫 #34/#39 与 selftest 夹具路径已同步，**改预设路径前先 grep 两脚本**。
6. `.gitattributes` 已钉 `*.kt text eol=lf`；`.md` 仍 CRLF，编辑勿 `sed -i`。
7. adb 双坑 + 键盘弹起发送键坐标漂移（W67 实摔：静态 (942,1968) 在键盘弹起时失效，须动态 dump `content-desc="发送"`）；锁屏上滑会误触 OPPO 紧急拨号（用 `keyevent 224` 唤醒 + monkey 启动，勿在锁屏滑动）。
8. `docs/14` = 外部依赖一等进度指标，本波仍 **0/6**。

## 七、CI run id

| workflow | run id | 结论 | 关键 job |
|---|---|---|---|
| **Build** | `38348809838` | ✅ success（head `c60abf3`） | 三 job 全 success：Lint (baseline gate) / Assemble Debug (JDK 21) / Unit tests；Assemble 内「架构守卫全部通过」（39 项）+「自测结果：**PASS=134 FAIL=0**」（= W66 的 133 + case61c）；Unit tests baseline=**690** 无低基线告警 |
| **Release** | `38348809828` | ✅ success（head `c60abf3`） | — |

> 计数口径：`arch-guard` 39 项（本地三方复跑 + CI「架构守卫全部通过」）；`selftest` **PASS=134**（CI 日志实测，本地 `--only 39`=15 / `--only 34,39`=20）；`@Test` **690** == `build.yml baseline`（#26 双向，CI baseline=690 无告警）。
