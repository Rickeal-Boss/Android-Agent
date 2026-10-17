# W72 波次收官交接（Wave 72 Closeout · 数据波）

> 基线：W71 收官 `fbd40c3`｜本波 commit 见 §二
> 单一执行依据：`_plans/w72-plan.md`（v3，方案席实拉基线 + 主理人三条实测订正后定稿）
> 前置：W71 交接 §五/§六 + 四份外部审查报告（2026-10-17 批次：深度审查 / 复审26-莫斯线 / 第十六审 / v24）

---

## 一、一句话 + 关键现实

**W72 = 数据波**：主刀「**0.18.0 条件真机臂**」（临时分支，五问全部出数，**决策树 (b) 命中**）+ 数据件「**`templateArraySafety` 四态字段 + 全 schema 守卫**」（14 条显式赋值，分布 10/2/0/2）+ 治理槽「**`datastore_tool.py` 唯一受许工具**」（三犯的结构性了断）+ 产品件「**诊断页 `NotRun(ENGINE_NOT_LOADED)` 补 knownBroken 登记行**」（真机 T-3 ✅）+ 小件批（#42 双去上限 / `gpuSupported` 删除 / docs/10 聚合补登 / docs/14 C6 / docs/05 真机铁律常驻节）。

关键现实（动手前必读）：

1. 🔴 **静态闸门**：arch-guard **42 项不变**（#39 扩展 `templateArraySafety` 四态、#42 glob+ID 正则双去上限，均不加编号）；selftest **+case71h/71i/71j（#42 家族）+ case76/76b/76c（#39 W72 D 三红面）**；`@Test` **735→740** == `build.yml` baseline（+3 产品件 / +2 数据件）；fulltest 9 模块 **tests=740 failures=1**（唯一失败 = 既有 core-data `SandboxFileScannerTest` Windows 符号链接，仅 Windows；CI 绿）。
2. 🔴 **0.18.0 条件真机臂 = 本波最高价值产出**（`_research/w72-0180-device-arm.md`，原始件 `_w72_dev/`）：
   - **收益实测**：gemma-4-E2B-it-gpu **引擎初始化修复**（`tf_lite_prefill_decode not found` 由致命降为 W 级 warning；`backend: GPU_ARTISAN`；38 token @ 18.34 tok/s vs CPU 0.47）+ **MiniCPM-V-4 type 13 修复**（会话建成，首 token 81.8s）；
   - **代价实测**：**锚 #1 P0 真机复现**（Qwen2.5-1.5B + **MiniCPM5-2B** 首轮 `Failed to apply template: + operator on unsupported types string and sequence`，爆面 **2/14**）+ **锚 #15 新增**（`Capabilities` 类删除 ⇒ 2 文件 API 迁移）+ E2B·GPU **输出退化未修**（knownBroken 只能订正、不能清空）；
   - **决策后果**：数据收集 ≠ 决策 ⇒ **bump 本体不做**，转 `docs/14` §C6 一次专门用户裁决。
3. 🔴 **两个方法学教训（W72 新增，见 §六）**：① **日志关键词判据必须排除自家文案**（首版旗标被本仓 knownBroken 警告文案命中 ⇒ 一度误读为 (a)）；② **模板静态阅读 ≠ 运行期行为**（MiniCPM5「静默丢字」离线推演被真机打脸——真实失败在模板求值期）。另：**取证 harness 必须覆盖 `systemInstruction` 维度**（W53/W55 只喂 user ⇒ 曾系统性高估安全面）。
4. 🟡 **数据件**：`templateArraySafety` 四态（SAFE/UNSAFE/LOSSY/UNVERIFIED）14 条显式赋值，**分布 10/2/0/2**；取证事实源 = `_ci-tools/_w72_tpl_safety.py` 七格矩阵（亲跑 `DIST 10/2/0/2`）；**模板指纹 12 条预设 → 10 个不同模板**（LFM 三兄弟共用 882B 模板 ⇒ 「10 SAFE」的样本独立性 = 9）。LOSSY **现役 0 命中但已实测可复现**（MiniCPM5 user 分支 `U_arr1/U_arrN = LOSS`，被 system 分支抛错遮蔽）⇒ 保留该态（W72-01 挂账）。
5. 🟡 **治理槽**：`scripts/datastore_tool.py`（snapshot / set-active-model / restore / verify；无快照拒绝 + 编译态定位 + 自动逐字节 diff 越界回滚 + UUID 对账设备索引）单测 **13/0 + 变异测试**（把定位改回切片法 ⇒ 3 条红，与 W70/W71 事故形态逐字一致）；**T-3 真机端到端行使**（写/还原/verify 全经工具，restore 后逐字节一致）。
6. 🟡 **产品件 T-3 真机 ✅**（逐字判据）：激活 gemma-4-E2B-it-gpu → **不发题面** → 诊断页运行自检 ⇒ verdict「模型自检未运行」+「请先在模型页加载模型，再运行自检」+「该模型已登记已知不可用：0.17.1 引擎初始化 NOT_FOUND（…）」在屏、免责行 **0 命中**、FATAL=0。
7. 🟡 **W71 数据订正（承 W71 订正 W70 的先例）**：W71 §3.2 内存表的「合计」列把**三个不同采样点**的数拼成加法（Phi-4「5.20（+swap 0.18 = 5.28）」实际 5.20+0.18=5.38≠5.28 —— 峰值 PSS 与峰值 swap 来自不同样本）。W72 `memBasis` 回流按**同样本口径**重写（Phi-4：峰值 PSS 5.20、该样本 swap 0.06 ⇒ 合计 5.26；本批最大同样本合计 5.28），**阈值不动**（tail 1.25 维持）。另 E4B·GPU 的实测峰值属**加载阶段**（0.17.1 下 knownBroken，样本 PSS 5.00→3.00 回落 ⇒ 生成期峰值未取证），已写进 `BASIS_GPU_E4B_MEASURED` 证据边界。
8. ⚠️ **过程**：CI 席（崔续程）在 E 单 + #39 扩展 + docs 批接近完成时 429 中断 ⇒ 按磁盘采信（diff 逐项复核）+ 主理人接手补完（selftest 新增 #39 三红面 case76/76b/76c + 六格→七格表述统一 + closeout/commit/push）。方案席与开发席正常交付。

---

## 二、改动清单（5 commit）

| commit | 性质 | 内容 |
|---|---|---|
| `feat(models)` | feat | **数据件 + 模型面产品件**：`TemplateArraySafety` 四态枚举 + `ModelPreset.templateArraySafety` 字段 + 14 条显式赋值（10/2/0/2，每条带证据锚）+ R1/Phi-4 `UNVERIFIED(NO_TEMPLATE_IN_CONTAINER)` 理由落 `backendBasis` + `memBasis` 实测回流 3 条（`BASIS_CPU_PHI4_MEASURED` / `BASIS_GPU_E4B_MEASURED` / MiniCPM-V-4 不可测标注）+ **删除僵尸字段 `gpuSupported`**（W69 C5 后零消费方）+ M16 `ModelCard.kt` 语义分工 KDoc + `ModelPresetTemplateSafetyTest` 2 例（分布冻结 + UNVERIFIED 禁裸留） |
| `feat(settings)` | feat | **产品件**：`probePresentation` 唯一扩面 —— `NotRun(ENGINE_NOT_LOADED)` ∧ knownBroken ⇒ 追加登记行（WARNING，与 PASS 并列同文案源 `knownBrokenLine()`）；headline/detail 逐字维持 Wave 44 口径、免责行不渲染；KDoc 重写（不变量 + 实测双证立论 + 证据边界）；`DiagnosticsProbePresentationTest` 5→8 例 |
| `feat(guard)` | feat | **治理批**：`scripts/datastore_tool.py` + `scripts/tests/test_datastore_tool.py`（13 例 + 变异实测）+ #39 扩展（`check-evidence-level.py` 同遍兼查四态：缺赋值 / 非法值 / SAFE 无锚 / UNVERIFIED 裸留，复用 preset_blocks 双重锚定）+ #42 glob 尾段 `*` + ID 正则 `[1-9][0-9]{2,}` 双去上限 + selftest +case71h/71i/71j + case76/76b/76c + 既有 #39 家族夹具补 `templateArraySafety` + `build.yml` baseline 735→740（沿革续写）+ docs/05 新增「真机实验铁律」常驻节 |
| `fix(guard)` | fix | selftest #39 家族断言对齐 W72 D 扩展后的守卫名（9 处旧标题串补「W72 D 兼查 templateArraySafety 四态」尾缀；否则 case56/57/58/59/61/61d 的 assert_red 因标题漂移误判失败 —— 终验时主理人查获并修） |
| `docs` | docs | **工程件 + 收官**：docs/12（锚 #15 新增 + 锚 #14 部分覆盖 + 0.18.0 条件臂专节 + 决策树 (b) 命中改写）+ docs/10 §11.0.1 聚合补登 46→**55**（47✅/6⚠️/2⛔，P-2 记 ⚠️）+ docs/14 增 **C6**（bump 分叉裁决，收益/代价/三选项）+ §D 悬置自动收敛条款（N=3，W72 起算）+ docs/15（W72-01/02/03 + 续更四条）+ README（W71/W72 波次段 + 聚合句 55 对齐）+ `docs/upstream/gallery-1326-reply-comment.md` **check② 补全**（0.18.0 实测：init 修复 + GPU 路径 + 退化仍在 + 模板炸数据点）+ 本交接 |

---

## 三、真机复验（OPPO A13 `13309cc8`，有线 adb）

### 3.1 0.18.0 条件真机臂（五问 + 追加一问）✅（全部出数，臂分支已弃）

| 问 | 容器 | 0.17.1 基线 | 0.18.0 实测 | 判定 |
|---|---|---|---|---|
| 编译 | — | 可编译 | `Capabilities` 删除 ⇒ 2 处 `Unresolved reference`；迁移 `ModelInfo.from` 后 BUILD SUCCESSFUL | ⛔ 锚 #15 |
| Q1 | gemma-4-E2B-it-gpu | 初始化 NOT_FOUND | init 修复（GPU_ARTISAN，18.34 tok/s） | ✅ |
| Q1′ | 同上输出质量 | 退化（W43） | 跨脚本混杂仍在 | ⚠️ |
| Q2 | MiniCPM-V-4-int8 | `Unsupported model type` | 会话建成（首 token 81.8s） | ✅ |
| Q3 | gemma-4-E2B-it CPU | 正常 | 正常（0.47 tok/s） | ✅ 无回归 |
| Q4 | Qwen2.5-1.5B | 可用（fold ON） | 首轮 `Failed to apply template` | ⛔ 锚 #1 |
| Q5 | MiniCPM5-2B | 可用（fold ON） | 同签名首轮炸 | ⛔ 爆面 2/14 |

纪律：datastore 全程快照 + 编译态补丁 + 逐字节还原校验（4 轮全 ✅）；臂分支 `w72-arm-0180` 物理隔离（git worktree），测后弃。

### 3.2 T-3 产品件冒烟 ✅

激活 gemma-4-E2B-it-gpu → **不发题面** → 设置→诊断信息→运行自检：verdict =「模型自检未运行」；UI dump 逐字三判据：headline 在位 /「该模型已登记已知不可用：0.17.1 引擎初始化 NOT_FOUND（容器缺 TF_LITE_PREFILL_DECODE section；W43 另实测输出退化）」在位 / 免责行 **0 命中**；FATAL=0。

### 3.3 datastore_tool 真机行使 ✅

T-3 全程写/还原/verify **只经工具**：`set-active-model` diff 29 字节全部落在 UUID 跨度 `[126,162)` 内（⚠️ 判据是「位置在跨度内」而非「==28 字节」，docs/05 已按此订正）；`restore` 后逐字节一致；`verify` 0 差异。**恶意路径（无快照 / 非法 UUID）离线单测 + 变异覆盖，真机路径 ⛔ vacuous 已挂 W72-03**。

---

## 四、审查对账（四份外部报告逐条处置）

| 来源 | 条目 | 处置 |
|---|---|---|
| 深度审查 U-1 | L1 全量取证 + 三态字段 | ✅ 落地且**超基线**：harness 七格（补 systemInstruction 维度）+ **四态**（三态会把 MiniCPM5 的 LOSSY 性质抹掉）+ 分布 10/2/0/2；**两点订正**：「8 条无标记」实为 4 条缺口（E2B×2/MiniCPM-V-4 早在 W53 L2 判过安全）；R1/Phi-4 容器**无明文模板** ⇒ 容器侧取证方法不适用（非漏做，挂 W72-02） |
| 深度审查 U-2 | NotRun 补 knownBroken | ✅ 落地（只扩 ENGINE_NOT_LOADED 一态）；**成本订正**：非「3 行+1 例」—— 既有断言 `assertNull(notLoaded.knownBroken)` 改写 + 不变量 KDoc 改口径 + 3 例新测试 |
| 深度审查 U-3 | docs/14 增 C6 | ✅ 落地（编号与 W69 工程件 C6 同名不同物已加提示）；内容按 W72 臂实测写（(b) 命中，收益/代价/三选项） |
| 深度审查 U-4 / 莫斯 P3-A | #42 ID 正则 | ✅ 落地且**双向**去上限（glob 尾段 `*` + 正则 `{2,}`）；**订正**：只改正则不够 —— glob 也止于 W999，wave1000 文件根本不进扫面；夹具 case71h/71i/71j 三面（红/红/绿） |
| 深度审查 U-5 | memBasis 回流 | ✅ 落地 + **口径订正**（W71 合计列三采样点不可加，见 §一.7）；KDoc 计数句 13→10 同步 |
| 莫斯 P4-A | case75 成本 | 挂账观察不动刀（负面清单） |
| 莫斯 §五.1 | 悬置裁决收口 | ✅ docs/14 §D 悬置自动收敛条款（N=3，W72 起算）；**C5-1 不代裁**（W68-03 保守支 = 本波不做，是否 closed-dead 由用户下次议程确认） |
| 莫斯 §五.5 / v24 M18 | 真机坑迁手册 | ✅ docs/05 新增「真机实验铁律」常驻节（datastore 四护栏 / 日志三证 / 导航输入 / UUID 对账） |
| 第十六审 J1 | 条件真机臂三预置 | ✅ 执行（临时分支纪律 / 判据清单 / 文案联动）；A2（W51 三工具回灌）降级可选 —— Q4 首轮消息已等价证明模板面必炸 |
| 第十六审 J2 | datastore_tool | ✅ 落地（仓内 `scripts/`，否决仓外 `_ci-tools/` —— 无版本控制 = 唯一受许不可审计） |
| 第十六审 J3 | W71-02 档案挖掘 | 沿袭（P-1 n=2 禁落地不变；档案挖掘写进 W71-02 重启前提） |
| 第十六审 J4 | gpuSupported 了断 | ✅ 删除；**订正**：第十六审「夹具同步是机械操作」不成立 —— 它是 computed getter 非构造参数，删后 #34/#39/#41 零改动 |
| 第十六审 J5 | docs/10 聚合 | ✅ 落地（46→55，W69–W72 共 9 项）+ README 聚合句同步 + **P-2 记 ⚠️**（负结果不得记 ✅） |
| 第十六审 J6 | 用户触点打包 | ✅ gallery check② 已补全（待用户提交）；A1 手验 / C5-1 / W68-01 触发时机已并入 docs/14 §D |
| 第十六审 J7 | ThinkingBudget 余 22 | 不触（臂未改 K-1 文档面） |
| v24 M16 | 模型库页交叉引用 | ✅ 落地（KDoc 语义分工，零行为改动；落点实为 `ModelCard.kt` 非 `ModelsScreen.kt`） |
| v24 P1 | 锚 #1 动态验证 | ✅ 完成（Q4/Q5 真机实证） |

**内部质检**：开发席（柯码成）三单 + D 数据件全部磁盘核verified（arch-guard 42 亲跑、fulltest 740/1 亲跑、datastore_tool 13/0 + T-3 亲行使）；方案席（沈思远）计划 v3 + 取证 harness（`DIST 10/2/0/2` 亲跑复核）；CI 席（崔续程）产出按磁盘采信 + 主理人补完。

---

## 五、挂账（W72 delta；权威版 = `docs/15-backlog.md`）

**销账（W72 关闭）**：无（本波无 parked 项满足销账判据）。

**新增**：
- **W72-01**（parked）LOSSY 态监控面 —— 任何人修掉 `+ messages[0].content` 的 system 分支抛错而不同时修 user 分支 ⇒ MiniCPM5 滑入不可见丢字；该 PR 必须重跑七格 harness。
- **W72-02**（parked）R1 / Phi-4 模板数组安全补证 —— 容器无明文模板 ⇒ 只能真机或上游内建模板源码；不补则 bump 评估按「未知即最坏」计入。
- **W72-03**（parked）datastore_tool 设备 I/O 长尾 —— 恶意输入路径仅离线覆盖；`--skip-index-check` 逃生阀裁断**不加**。

**修订**：
- **W68-06**（active）：bump **取数完成**（决策树 (b) 命中）⇒ blocker 语义从「单维持（锚 #1 静态缺失）」变「代价已实测（锚 #1 真机 P0 2/14 + 锚 #15 + 退化未修）」；bump 本体转 docs/14 §C6 用户裁决。
- **W68-07**（active）：重启前提更新 —— GPU init 已修 ⇒ 从「高显存设备 + 上游修复」双前提变「bump 后 + 高显存设备」单前提。
- **W68-03**（active）：docs/14 悬置自动收敛条款计数已满足 ⇒ 保守支 = 本波不做；是否 closed-dead 由用户确认（不代裁）。
- **W71-02**（parked）：重启前提补「档案挖掘先行」（W43/W44/W70 取证档有历史退化样本可扩 n）。

**沿袭**：法务 termsVersion / 0 tags / main FF / F4 / `TokenUsage.estimated` / fulltest 接 CI / gallery #1326 回复待用户提交（check② 已补全）/ docs/12 决策树 (b) 裁决 / evidenceLevel 徽章接线（🟡 已实现未接线同款）。

**明确未做（负面清单兑现）**：bump 本体（数据 ≠ 决策）/ P-1 判据落地（n=2）/ 接缝①②（行数余量 55/158/49 未触）/ docs/12 逐字模板入仓（170KB，改存判定表 + 复现命令）/ case75 提速 / `--skip-index-check` 逃生阀 / 人格加档 / tag。

---

## 六、下次接手须知

1. **🔴 日志关键词判据必须排除自家文案**：驱动脚本里 `NOT_FOUND` / `Unsupported model type` 这类旗标会被**本仓 knownBroken 预设的警告文案**命中（W72 首版即误读 (a)）。正确三证 = native/引擎侧日志行 + journal 有无 MODEL 消息 + tokensPerSecond 量级。已入 docs/05 §B。
2. **🔴 模板静态阅读 ≠ 运行期行为**：凡「静默 vs 抛错」的形态判断必须真机或离线 minijinja 复现（MiniCPM5 推演被打脸实例）；**harness 必须覆盖 `systemInstruction` 维度**（本仓恒下发 systemInstruction，失败点就在 system 分支；W53/W55 缺此维度 ⇒ 系统性高估安全面）。
3. **🔴 datastore 只经 `scripts/datastore_tool.py`，禁一次性脚本**（交接须知 §六.3 四条纪律已被工具收编）；**判据是「差异位置在 UUID 跨度内」不是「==28 字节」**。
4. **selftest `--only` 收守卫号**（`--only 42`），不是 case 名（`--only 71h` 会 exit 2 列出全部守卫号）；case 编号仍须先查重（本波用掉 71h/71i/71j、76/76b/76c，下一个空闲 **77**）。
5. **探针/自检前先发题面加载引擎**；**发完消息先 BACK 收键盘再导航**（沿 W71）。
6. **#39 现为双字段判据**（evidenceLevel + templateArraySafety 同遍）：新增预设必须同时显式赋值；`SAFE` 必须带 `W##` 锚、`UNVERIFIED` 必须带理由类别（`NO_TEMPLATE_IN_CONTAINER`/`NO_CONTAINER`）—— 三红面由 case76/76b/76c 钉死。
7. **bump 若启动**（C6 裁决过）：toml + `Capabilities→ModelInfo` 2 文件迁移 + #29 期望值 + `appliesToRuntime` 字面量 + knownBroken 三条复审（E2B/E4B·GPU 订正为「仅输出退化」、MiniCPM-V-4 清空）+ 非数组安全容器导入闸门（W72-01 监控面同步）。
8. **#42 运维提醒**：本波 delta ID（W72-01/02/03）已先入 docs/15（机械核对可过）。

---

## 七、CI run id

| workflow | run id | 结论 | 关键 job |
|---|---|---|---|
| **Build** | `39044348349` | ✅ success | @ `d979248d` |
| **Release** | `39044348294` | ✅ success（debug + R8 release 双轨产物） | @ `d979248d` |

> 对照基线：W71 Build `39030058303` / Release `39030058288` @ `3461a32`（均 success）。
> 本回填提交（纯 docs）按触发规则 0 run 为设计预期。
