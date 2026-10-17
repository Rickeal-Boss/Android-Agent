# W71 波次收官交接（Wave 71 Closeout · 产品波第三发）

> 基线：W70 收官 `d7bf462`｜本波 commit 见 §二（回填于推送后）
> 单一执行依据：`_plans/w71-plan.md`（v1，方案席实拉基线 + 6 处报告差异订正）
> 前置：W70 交接 §五/§六 + 五份外部审查报告（2026-10-17 批次）+ 0.18.0 离线锚批

---

## 一、一句话 + 关键现实

**W71 = 产品波第三发**：治理槽「守卫自愈与台账订正批」（G1–G5 五子项一件）+ 产品件「诊断页 PASS × knownBroken 并列呈现 + 免责一行」（真机端到端 ✅）+ 工程件四路（0.18.0 锚批入档 + P-1 离线判分 + README N1-N5 + 表述小刀）+ 真机窗三件（T-2 冒烟 / W69-02 内存批 / P-2 budget=96 臂）。

关键现实（动手前必读）：
1. 🔴 **静态闸门**：arch-guard **42 项不变**（G2 改 glob、G5 迁 #29 均不加编号）；selftest **+case75（G1 自洽）+ case71f/71g（G2）+ case74/74b（G5）**，**case54 保持 #37 家族**（G1 新 case 一度重号 54 ⇒ 质检席 P2-1 查获后改 75，见 §六.1）；`@Test` **730→735** == `build.yml:502 baseline`；fulltest 9 模块 **tests=735 failures=1**（唯一失败 = 既有 core-data `SandboxFileScannerTest` Windows 符号链接，仅 Windows；CI 绿）。
2. 🔴 **0.18.0 离线锚批完成**（研究线程，`_research/w71-0180-anchor-batch.md`）：14 锚静态可判 12 项**仅锚 #1 ⛔ 缺失**（v0.18.0 `generic_data_processor.cc` 重写、`MessageToTemplateInput` 覆盖删除、`requires_typed_content` 三文件 0 命中）；**锚 #5 ✅ 双 ABI `.so` 符号在** ⇒ **W68-06 bump blocker 从双维持变单维持（仅剩锚 #1）**；锚 #12/#13 ✅（minijinja `=2.14.0`、`tojson` 单参未放宽）。**制品位置订正**：0.18.0 从未上 Maven Central（repo1 路径整组 404），只在 Google Maven（20,905,807 B，2026-10-06）——查证勿再查错地方。
3. 🟡 **P-2 budget=96 臂（第十四审假设）实测未命中，归因精确化**：budget 确认生效（logcat `think 96`），两轮 thinking 仅 **188/180 字符 ≈ 36-37 token**（r≈5）≪ 96 ⇒ 预算从未触顶；正文 211/265 字符非空（N-2 前提「thinking 非空 ∧ 正文空」不成立）。⇒ **压低预算 ≠ 造出 N-2**（需「思考 ≥ 预算」且「耗尽后不产正文」的模型行为面）；W70-01 维持 parked、重启条件收紧（见 §五）。
4. 🟡 **W69-02 内存实测批销账**：Phi-4-mini 峰值 PSS **5.20 GiB**（+swap 0.18 = 5.28）vs 预测 5.6；E4B·GPU **5.00**（+0.18 = 5.01）vs 5.2；MiniCPM-V-4 执行失败不可测（失败路径 0.40 GiB，如实申报）。两可测容器实测**低于**预测（余量 0.19–0.32 GiB）⇒ **tail 1.25 维持不改**。
5. 🟡 **新发现（W70 数据订正）**：W70 驱动脚本 Phi-4 的 UUID 错一位（`4c95` vs 设备索引实际 `4d95`）⇒ W70 §3.6「Phi-4 歧义」实为该轮**从未选中模型**；其余 7 容器 UUID 与索引逐字一致。已入 W69-03 台账订正。
6. ⚠️ **过程事故（主理人，已完整恢复）**：一次性恢复脚本踩 W70 档案明写的 datastore 坑（切片搜索 `m.start()` 相对位置当绝对位置用）⇒ 把 R1 UUID 写进 `has_seen_intro` 值区、首启标记丢失、引导页复现（**同日第二次同类事故**）。用健康快照推回 + 编译态正确 API（`pat.search(b, ki)`）恢复，终态逐字节核验（仅 UUID 区 28 字节差）、journal 139 会话无损。教训见 §六.3。
7. 🟡 **探针 PASS 语义已到用户面**（T-2）：诊断页 PASS 卡现为「结论：通过（自检面）」+「该模型已登记已知不可用：<原文>」+ 免责行「PASS = 未检出已知失败面，不构成输出质量保证」——W70 申报的探针保真度缺口从 KDoc 走到用户可见面。

---

## 二、改动清单（4 commit + closeout）

| commit | 性质 | 内容 |
|---|---|---|
| `664bde2` | feat(guard) | **A 治理批**：G1 selftest `--only` 归一同源（normalize_only_set + case75）+ G2 #42 glob 去上限（`wave@(6[89]\|[7-9][0-9]\|[1-9][0-9][0-9])` + 失效波次教训注释 + case71f/71g）+ G3 docs/16 全量 21 锚复合锚 re-anchor + G4 台账订正三件（W69-03 判据订正 / W70-02/03 补登 / M12 续更）+ G5 #29 迁 `preset_blocks.py`（`--applies-to-runtime` 消费入口 + fail-closed + case74/74b + 模块单测 ⑪⑫⑬） |
| `5ff100b` | feat(settings) | **B 产品件**：DiagnosticsViewModel `knownBrokenOn` 派生流 + DiagnosticsScreen `probePresentation` 纯函数化（PASS∧knownBroken 并列 + 免责行）+ SettingsRoute 接线 + `DiagnosticsProbePresentationTest` 5 例 + build.yml baseline 730→735 |
| `5f9ce50` | docs | **C 工程件**：docs/12 0.18.0 锚批入档（14 锚打勾 + 专节 + 决策树 (a)/(b)/(c) 预登记 + 已知盲区登记簿）+ README N1-N5（补 W68/69/70 三波 + 特性表三件 + 七处指针收口 + E4B 8192 披露）+ 表述小刀（PersonaPresets KDoc 三处 / SettingsScreen re-prefill / build.yml 沿革头 / feature-settings build 注释 / test_preset_blocks 头注） |
| （本交接） | docs | **D 收官**：本交接 |

---

## 三、真机复验（OPPO A13 `13309cc8`，有线 adb，W71 包 19:1x 安装）

### 3.1 T-2 冒烟（产品件端到端）✅

激活 `gemma-4-E2B-it-gpu.litertlm`（knownBroken，uuid `d0de1e7a…`）→ 发英文题面加载引擎 → 设置→诊断信息→运行自检：
- verdict = **模型自检通过**（logcat `模型自检通过`）；
- UI dump 逐字判据：**「结论：通过（自检面）」= True / 「该模型已登记已知不可用：」= True / 免责行 = True / 裸「结论：通过（PASS）」单独出现 = False**；
- FATAL=0；datastore 逐字节还原 ✅；
- 同场 logcat 抓到激活态重校验提示原文（knownBrokenOn 数据链实证）：`[WARN] 激活态重校验提示：已知不可用：0.17.1 引擎初始化 NOT_FOUND（容器缺 TF_LITE_PREFILL_DECODE section；W43 另实测输出退化）。仍可继续，但可能失败。`

**过程**：首跑漏了「先发题面加载引擎」步 ⇒ `模型自检未运行：ENGINE_NOT_LOADED`（W70 流程隐含此步）；补步后二跑仍 `NAV_RETRY`（发完消息键盘挡底部导航 tab）⇒ 加 BACK 收键盘后三跑通过。两坑已入 §六.4。

### 3.2 W69-02 内存实测批 ✅（只测不改）

| 容器 | 公式预测 | 峰值 PSS | 峰值 SWAP | 合计 | 差 |
|---|---:|---:|---:|---:|---:|
| Phi-4-mini-instruct | 5.6 GiB | **5.20 GiB** | 0.18 GiB | 5.28 | −0.32 |
| gemma-4-E4B-it-gpu | 5.2 GiB | **5.00 GiB** | 0.18 GiB | 5.01 | −0.19 |
| MiniCPM-V-4-int8 | 6.5 GiB | 0.40 GiB（失败路径） | 0.00 | — | 不可验证（`Unsupported model type`，执行失败≠质量结论） |

PSS/swap 口径定案 = `dumpsys meminfo` 的 TOTAL PSS 与 TOTAL SWAP PSS **分列**（PSS 不含 swap）。两可测容器均低于预测 ⇒ tail 1.25 维持。

### 3.3 P-2 budget=96 臂 ⚠️（行使完整，未命中，归因精确）

Arm B 纪律：改 `ModelSamplingProfiles.kt` R1 档 `minThinkingTokenBudget` 1536→96 → 构建安装 → R1 真机 2 轮（英文题面）→ **还原 → 重建 → 装回干净包 → git 干净**（`git diff` 空核验）。
- 补丁生效实证：logcat `模型采样档案生效：…/maxTok 2048/think 96/ctx 4096`（两轮均出现）；
- 两轮 journal：thinking **188 / 180 字符**（≈36-37 token @ r≈5）、正文 **211 / 265 字符**（`</think>` 通道标记泄漏 = R1 已知形态）；
- N-2 warn **0 行**、预算耗尽/截断 **0 行**、FATAL=0。

**归因**：96 预算是实测思考（~37 token）的 2.6 倍 ⇒ 预算从未触顶；且预算耗尽路径本应强制转正文 ⇒ N-2 的「正文空」前提不成立。**第十四审 P-2 的「R1 思考上限 ~200 token ⇒ budget=96 可现 starvation」假设被实测证伪**（该题面下思考仅 36-37 token）——负结果留痕，W70-01 重启条件据此收紧。

---

## 四、审查对账

1. **质检席（严质衡）终判 = 需修改（有条件通过），2 重要 + 4 建议，无阻断**（`_plans/w71-review.md`，110 行，闸门实数亲跑）。**处置**：
   - **P2-1（case54 双跑 49 例 ≥37min/遍，且开发侧从未跑完）→ 已修**：① 标签撞车（case54 家族 W65 起归 #37）⇒ G1 case 改编号 **75**；② 递归靶 `34 39 41`（49 例）→ **#27+#20（6 例）**（归一为选择器层性质，与守卫无关；单值无空格区分度 ⇒ 必须 ≥2 守卫）；实跑 `--only 75` **PASS=1 / 9m21s**（原 ~80min），两形态各 PASS=6 计数相等。
   - **P2-2（G5 兄弟面「另立台账 ID」只写代码注释）→ 已修**：`docs/15` 立 **W71-01**（parked，四要素）+ W71 续更行 ④ 登记。
   - **P3-1（B3 免责行对任意 PASS 生效 = 申报级偏离）→ 维持 + 本交接申报**：偏离正当（假阴性盲区对健康模型同样成立；与 docs/12 M14 登记簿、README 特性表同口径；纯展示层）。**此处如实记录**：计划文本原意是「与 knownBroken 登记同卡绑定」，实现为「任意 PASS 均渲染免责行」——后续读计划者勿误判为 bug。
   - **P3-2/3/4 → 已修**：`test_preset_blocks.py` 头注补 ⑪⑫⑬；`feature-settings/build.gradle.kts` JVM 单测注释补 DiagnosticsProbePresentationTest；`normalize_only_set` 的 tr 集补 **Tab/CR**（`$'34\t39'` 残余零跑形态）。
2. **主理人独立复跑**（不信申报）：arch-guard 42 OK/0 FAIL exit 0；fulltest 9 模块 735/1（已知）；feature-settings 22 例 0 失败（TEST-XML 实测）；`--only 53` PASS=1；`test_preset_blocks.py` 13/0；case75 PASS（9m21s）。
3. **子代理故障两起（如实申报）**：① 工程件代理 500 中断（死前仅落 P-1 脚本）⇒ **主理人接手直办**（P-1 报告 / docs/12 入档 / README / 盲区登记簿），不重试 spawn（W69/W70 先例）；② 治理席 A1–A5 全部磁盘产出完成后失联（无完工汇报）⇒ 以磁盘实测采信（arch-guard 42 亲验 + diff 逐项复核），不重试；③ 质检席完稿后 500（终版已落盘，按磁盘采信）。

---

## 五、挂账（W71 delta；权威版 = `docs/15-backlog.md`）

**销账（W71 关闭）**：**W69-02**（内存实测批：2/3 可测容器低于预测、tail 1.25 维持；MiniCPM-V-4 执行失败不可测如实申报）。

**新增**：
- **W71-01**（parked）**#41 方向 C-1 兄弟面 raw string 污染提取假红**——G5 迁移 #29 时申报、质检席 P2-2 查获「承诺未入账」后补登；修法 = 随 #41 扫描器下次大修迁 preset_blocks。
- **W71-02**（parked）**P-1 脚本混合判据进探针候选**——离线判分退化样本命中/健康零误报，但 n=2（退化 n=1）⇒ 数据面补齐 + 阈值标定先行，禁凭 n=2 落地。

**修订**：
- **W70-01**（维持 parked）**重启条件收紧**：压低预算 ≠ 造出 N-2（budget=96 臂实测思考 36-37 token ≪ 96、正文非空）；需「思考 ≥ 预算 ∧ 耗尽后不产正文」的模型/题面组合；离线替身测试保持权威判据。
- **W68-06**（active，**单维持**）锚 #5 已验（0.18.0 双 ABI 符号）⇒ 仅剩锚 #1 收敛缺失；决策树 (a)/(b)/(c) 已预登记 docs/12。
- **W69-03**（closed-done）Phi-4 歧义根因订正（UUID 错位，非陈旧行）。

**沿袭**：bump blocker（单维持）/ 0 tags / W68-01/02/03/07/08 / W69-04/05/12/13 / 法务文本 / F4 / `TokenUsage.estimated` / fulltest 接 CI / N-2 聚合键 / `.md` CRLF 编辑纪律 / 上游出货（gallery #1326 回复待用户提交；0.18.0 静态差异 = 仅收敛语义，可作可选升级素材）/ docs/10 §11.0.1 聚合补登 W69/W70 设备件（README 已指向，聚合待补）。

**明确未做（负面清单兑现）**：bump（单维持）/ 0.18.0 条件真机臂（GPU init 复测 + collapse 首跑判据，另立项）/ evidenceLevel 徽章（C5-1 未裁）/ 接缝①（docs/16 触发三问全否，**主理人背书方案席裁断**：re-anchor 做扎实，预案就绪未触发）/ 接缝② / W68-01 数据回填（用户裁决）/ I-6 ThinkingBudget KDoc 回收 / 探针新判据落地（P-1 只离线判分）/ gpuSupported 删除 / main FF / tag / 人格加档。

---

## 六、下次接手须知

1. **selftest case 编号纪律（W71 新增教训）**：新增 case **必须先 `grep -oE "case[0-9]+" scripts/arch-guard-selftest.sh | sort -u` 查重**——W71 G1 新 case 一度编 54，与 W65 起在册的 **case54 家族（#37 LoopHelpers）撞车**，且 `META_CASES="53 54"` 错误放行。现编号：**case75 = G1 自洽**（`META_CASE2=75`）；下一个空闲号从 76 起。
2. **selftest 数字必标来源**：本波起 `--only` 回报一律附 **PASS 计数 + case 名单 + 实拉命令**（W70 97/95 误报、W71 case54 未跑完两次实例）；CI 全量绿仍是唯一权威信号。
3. 🔴 **datastore 补丁铁律（第三次重申，W71 同日再犯）**：① **写前 `run-as cp` 快照 + 拉回工作区**（脚本已内置）；② 补丁只用**编译态** `pat.search(b, ki)`（pos 参数，绝对位置）——**切片法 `m.start()` 必须 + ki**，否则会写进别的字段值区（W70 毁过 `has_seen_intro`，W71 同一坑再犯一次）；③ 还原后用**逐字节 diff** 核验（不要只查 UUID 子串存在——子串在错误位置也会「通过」）；④ 设备真原值：R1 = `0e0d75b0-36c0-49ed-b97e-5f4be8103ee0`。
4. **真机驱动新坑（W71 新增 2 条）**：① **探针前必须先发题面加载引擎**（否则 `模型自检未运行：ENGINE_NOT_LOADED`——W70 流程隐含此步）；② **发完消息键盘在屏会挡底部导航 tab** ⇒ 导航前先 `input keyevent 4` 收键盘。
5. **模型索引 UUID 以设备 `files/model_index/models.json` 为准**（W70 脚本 Phi-4 错一位教训）；脚本内 UUID 清单复用时先对账。
6. **#42 运维提醒（不变）**：本交接 §五 delta ID（W71-01/W71-02/W69-02/W70-01/W68-06/W69-03）**已先入 docs/15**，机械核对可过。
7. **W72 入口候选**：主刀 = 0.18.0 条件真机臂（GPU init 复测 + W51 collapse 首跑判据，需临时分支 + 用户裁决 (b) 分支）/ 产品件 = evidenceLevel 徽章（待 C5-1）或人格加档 / 治理槽 = #41 兄弟面迁移（W71-01）/ 真机件 = docs/10 聚合补登 + P-1 数据面补齐（W71-02）。

---

## 七、CI run id

| workflow | run id | 结论 | 关键 job |
|---|---|---|---|
| **Build** | `39030058303` | ✅ success（Assemble Debug / Unit tests / Lint 三 job 全绿） | @ `3461a32` |
| **Release** | `39030058288` | ✅ success（debug + R8 release 双轨产物） | @ `3461a32` |

> 对照基线：W70 Build `39001830338` / Release `39001830345` @ `3d92af3`（均 success）。
> 本回填提交（纯文档）按触发规则不跑 CI（`build.yml` paths-ignore 含 `**/*.md`；`release.yml` paths 正白名单不含 docs）—— 0 run 为设计预期，非故障。
