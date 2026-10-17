# W70 波次收官交接（Wave 70 Closeout · 产品波）

> 基线：W69 收官 `fc0f7c5`｜本波 4 commit：`ee3ca12` → `7450b21`（+ 本 closeout 第 5 个）
> 单一执行依据：`_plans/w70-plan.md`（v1 定稿，方案席沈思远实拉基线无订正项）
> 前置：W69 交接 §五/§六.7 + 上游两份情报（用户 2026-10-16 手动提交 #3576 评论 / gallery #1326，维护者已答复）

---

## 一、一句话 + 关键现实

**W70 = 产品波**：治理槽「对账机械化批」（#42 交接↔台账守卫 + #41 方向 C docs/12 锚对账，W69 先例合并为一件）+ 产品件「人格档位」（W69-07 重启）+ 工程件「上游文档批」（docs-only）+ 真机窗四件（阈值校准→D7→2047 臂→Y3）+ 人格冒烟。

关键现实（动手前必读）：
1. 🔴 **静态闸门基线上移**：arch-guard **41→42 项全绿**（#42 = ledger-era 交接 delta ID ⊆ docs/15 台账，fail-closed）；selftest 新增 **case71 家族（7 例）+ case72（#41C-1 漂移）+ case73（#41C-2 假锚）**，实拉 `--only 42` PASS=7 / `--only 41` PASS=36（开发席申报的 97/95 系口径误报，以实拉为准）；`@Test` **727→730** == `build.yml:502 baseline=730`（B4 人格档位 3 例同步）。
2. 🔴 **阈值校准结论 = 候选判据「低字符多样性 <0.15」校准后不采用**（W69-03 销账，KDoc 留痕）：退化样本（gemma-4-E2B-it-gpu 多语种垃圾输出）多样性 **0.722** 与健康样本（gemma-4-E2B-it）**0.748** 均远高于阈 ⇒ 无判别力。**附带发现（探针保真度缺口申报）**：该退化形态不被现役任何判据覆盖，探针判 PASS（假阴性）——成本受控健康快照使命下的已知盲区，非缺陷。
3. 🟡 **D7**：R1（DeepSeek-R1-Distill-Qwen-1.5B）探针 verdict = **通过（PASS）**，采样**（空）**、耗时 27020ms——思考吃满预算正文空，判据 C 两通道合并判定不误判 BAD（W66 预判在 W70 复验）。
4. 🟡 **Y3**：gpuClause 警告「该文件未列入 GPU 白名单，GPU 路径未经验证（可能崩溃）。仍可继续，但可能失败**或被自动回落 CPU**。」真机逐字命中；**GPU→CPU 回落闸同场实证**（「强行加载会闪退」保命道，非白名单模型 R1 触发）。
5. 🟡 **2047 臂 = Arm B 纪律完整行使**（改 ModelSamplingProfiles.kt:171/192 minThinkingTokenBudget 1536→2047 → 真机 2 轮 → 还原 → 重建 → git 干净）：**N-2 warn 未命中**——R1 实际思考仅 ~14-200 token（远低于 2047 预算，无 starvation），且模型输出非工具 JSON 块为「答案」（剥离后非空 ⇒ 不走空答分支）。离线替身测试（ThinkingBudgetN2SubstituteTest）为触发链权威判据；真机臂按计划定位降级为回归确认 ⇒ 结论 = 臂构建稳定运行、触发条件未复现（**模型行为事实**，非判据失效）。残留挂账 W70-01。
6. 🟡 **人格冒烟**：设置页「简洁直接」chip 点选 → 65 字预设**逐字**写入 systemInstruction（datastore 实证）——产品件端到端成立。
7. ⚠️ **过程事故申报**：13:5x 主理人临时实验脚本（datastore UUID 补丁）的切片搜索位置 bug 损坏过一次设备 datastore（首启标记丢失、闪引导页）——13:18 快照完整恢复，应用与数据无损；根因与恢复法已入当日日志。

---

## 二、改动清单（4 commit + closeout）

| commit | 性质 | 内容 |
|---|---|---|
| `ee3ca12` | feat(guard) | **A 治理批**：#42 交接↔台账守卫（arch-guard.sh +64，ledger-era 命名空间防假红 + fail-closed）+ #41 方向 C（check-preset-profile-parity.py +228：C-1 appliesToRuntime↔docs/12 锚 #14 非 vacuous / C-2 豁免锚对账现役 0 命中如实标注）+ selftest case71 家族/72/73（+235）+ preset_blocks.py KDoc 订正 |
| `be43745` | feat(chat) | **B 产品件人格档位**（W69-07 重启）：PersonaPresets.kt（3 档原创预设 + matches 逐字相等）+ SettingsScreen GlassChip 选择器（+36，诚实口径免责文案）+ ChatParamsPanel 零改动（自定义逃生阀）+ PersonaPresetsTest 3 例 + build.yml baseline 727→730 |
| `8d795b6` | docs | **C 上游文档批**：docs/14 §B1/§B2 状态回填（实际落点改投 gallery#1326 如实登记）+ 新建 gallery-1326-reply-comment.md（答检查①③，②如实申报）+ docs/12 入 0.18.0/#3576 情报（toml 零触碰） |
| `7450b21` | docs(model) | **D 阈值校准 KDoc 留痕**（W69-03 销账判据）：候选判据不采用 + 探针保真度缺口申报 + verdict 分布摘要 |

---

## 三、真机复验（OPPO A13 `13309cc8`，有线 adb，W70 包 15:0x 安装）

### 3.1 阈值回填校准（W69-03，主刀）✅

8 容器探针批（`_ci-tools/_w70_probe_e2e.py`）+ D7 单容器（`_ci-tools/_w70_d7_r1.py`）：

| 容器 | 类型 | verdict | 证据强度 |
|---|---|---|---|
| gemma-4-E2B-it | healthy | **PASS**（10586ms） | UI 卡 + logcat 事件双证 |
| gemma-4-E4B-it | healthy | **PASS**（11482ms） | logcat 事件 |
| gemma-4-E2B-it-gpu | knownBroken | **PASS**（1748ms，退化样本「我cannOlivКи blockchains…」多样性 0.722） | UI 卡 + logcat 事件双证 |
| gemma-4-E4B-it-gpu | knownBroken | **PASS**（13162ms） | logcat 事件 |
| MiniCPM-V-4-int8 | knownBroken | **Failed：Unsupported model type**（执行失败≠BAD） | UI 卡 + logcat 事件双证 |
| Qwen2.5 / Phi-4-mini | healthy | 歧义（logcat 陈旧行污染，见 §3.6） | — |
| DeepSeek-R1 | r1_d7 | **PASS**（27020ms，采样（空）） | D7 单容器脚本修复后捕获 |

**校准结论**：候选判据（低字符多样性 <0.15）**不采用**——健康/退化样本多样性均远高于阈；误报成本高口径（任务书）不予落地。KDoc 已留痕（含探针对 E2B-GPU 退化形态假阴性的保真度缺口申报）。

### 3.2 D7（W69-10a）✅

R1 探针 verdict = **通过（PASS）**；采样**（空）**、耗时 27020ms；判据 C 两通道合并判定（thinking 非空 ⇒ 不误判 BAD）——W66「R1 容器不认 enable_thinking=false」预判在 W70 复验。D7 = 一条 verdict 实读，达成。

### 3.3 2047 补丁臂（W69-10b）⚠️（行使但触发未复现）

Arm B 纪律完整：改 `ModelSamplingProfiles.kt:171/192`（1536→2047）→ 构建安装 → R1 真机 2 轮（问候 + 数学题）→ 还原 → 重建 → git 干净。**N-2 warn 0 次**。归因（journal 双通道实证）：R1 实际 thinking 422-610 字符 ≈ 14-200 token ≪ 2047 预算（无 starvation）；正文为非工具 JSON 块（剥离后非空 ⇒ 不走空答分支）。⇒ 触发条件需「思考长度 ≈ 预算」（当前设备 2s/token 下 2047 token ≈ 70min，不现实）⇒ **离线替身测试（ThinkingBudgetN2SubstituteTest）保持触发链权威判据**；残留挂账 W70-01（重启 = 长思考模型或低预算档配置）。

### 3.4 Y3 ✅

13:53 logcat（探针批搭车）：gpuClause 警告完整尾句「…仍可继续，但可能失败或被自动回落 CPU。」；15:07 臂行使时 GPU→CPU 回落闸命中（「强行加载会闪退」）。第 3 道回落 + 第 4 道提示联动均真机行使，补 gpuClause 零行使记录。

### 3.5 人格档位冒烟（产品件端到端）✅

设置页滚动 2 屏定位人格 chip → 点「简洁直接」→ datastore `systemInstruction` = 65 字预设**逐字一致**（`回答请直接给出结论…`）。B2 UI → 既有落盘路径全链通。

### 3.6 过程问题申报（真机驱动脚本，如实记账）

1. **logcat 陈旧行污染**：`logcat -c` 只清主缓冲 + 多 nohup 进程叠加 ⇒ 探针批容器 3-8 的 verdict 首条匹配命中陈旧行（Qwen2.5/Phi-4-mini 判据歧义）。**已修**（唯一日志文件 + pkill + 时间戳过滤 > tap-10s），D7 单容器脚本修复后捕获正常。歧义两容器不影响校准结论（关键样本 E2B-it/E2B-GPU/MiniCPM-V-4 双证在列）。
2. **datastore 损坏事故**（主理人实验脚本切片搜索位置 bug）：13:5x 损坏过一次，13:18 快照恢复（见 §一.7）。**教训入档**：编译态 `pat.search(b, ki)`（pos 参数）才是正确写法；切片法须 `m.start()+ki` 修正。
3. 生成中应用拦截页签导航（run 未 settled 点「设置」无效）+ 激活警告横幅在位时输入栏上移 y≈1284（硬编码 540/1969 点空）——均已入当日日志。

---

## 四、审查对账

1. **开发席（柯码成）**：治理批（#42/#41C/selftest）+ 产品件（人格档位）两单。**派单核实**：arch-guard 实跑 42 项 exit=0；selftest 实拉 --only 42 PASS=7 / --only 41 PASS=36（申报的 97/95 系口径误报，事实无误）；`@Test` 实拉 730 == build.yml baseline；PersonaPresetsTest 自跑 3/3（TEST-XML tests=3 failures=0）；feature-settings UI 编译 BUILD SUCCESSFUL；GlassChip 组件存在性核实通过。人格 prompt 全部原创（Apache-2.0 红线自查：Operit 系零搬运）。
2. **方案席（沈思远）**：W70 计划（基线实拉无订正项）+ 上游文档批 5 文件（C1 状态回填 / C2 gallery 回复文案 / C3 docs/12 情报）。文档批质量核实：分寸纪律到位（不代上游下结论）、登记链如实（ModelScope 镜像来源不伪称 HF）、toml 零触碰。申报的「guard OK 行数 42 疑点」已核实：42 = 41 基线 + #42 新增（W69 后基线 41，A1 落地后 42，计划 §1.1 的 41 系 A1 落地前口径）。
3. **M12 对账（本波收官执行）**：W69 交接 §五/§六 项全部在台账（W69 已对账）；W70 本波 delta（W68-09/W69-11/W69-03/W69-10/W69-07 销账 + W70-01 新增）已按四要素同步 docs/15；#42 机械守卫对收官文档做 ledger-era 核对（本交接 §五 的 W70-01 已先入台账）。

---

## 五、挂账（W70 起；权威版 = `docs/15-backlog.md`，本节只写 delta）

**销账（W70 关闭）**：**W68-09**（docs/15 轻量核对守卫——#42 落地，交接↔台账机械核对成立）｜**W69-11**（#40/#41 锚对账机械化——#41 方向 C 落地）｜**W69-03**（阈值校准——候选判据不采用，KDoc 留痕；探针保真度缺口如实申报）｜**W69-10**（D7 ✅ R1 verdict 实读；2047 臂 Arm B 完整行使、N-2 未命中归因模型行为（见 §3.3）；Y3 ✅ 双闸真机命中）｜**W69-07**（人格系统——人格档位产品件交付 + 冒烟）。

**新增/顺延**：
- **W70-01** N-2 warn 真机命中（parked；重启 = 端侧模型思考长度可逼近预算档（≥1.5k token 推理链）或低预算档配置；当前 R1 实测 ~14-200 token 远低于 2047 预算且 2s/token 下达预算需 ~70min，不现实）
- **沿袭**：bump blocker（W68-06）/ 0 tags / W68-01/02/03/06/07/08 / W69-02/04/05/12/13 / 法务文本 / F4 / TokenUsage.estimated / fulltest 接 CI / N-2 聚合键 / `.md` 编辑纪律（docs/15 系 CRLF 用 Edit 工具）/ 上游出货（gallery 回复待用户提交）

**明确未做（负面清单兑现）**：bump（锚 #5 未验 + 锚 #1 收敛缺失双维持）｜检查② 0.18.0 复测（需 0.18.0 AAR，另立项）｜evidenceLevel 徽章（W68-03，C5-1 未单独裁决）｜seam②｜main FF / tag｜内存 tail 调整（等 W69-02）｜OFF 档硬阈 / F1 强抑制｜🟡-1/🟡-4（W69-12/13）｜人格 16-MBTI/SOUL.md 形态（C4 裁决外）。

---

## 六、下次接手须知

1. **静态闸门基线（本波后）**：arch-guard **42**；selftest **+case71 家族 + case72 + case73**（全量口径以 CI 日志为准）；`@Test` **730** == `build.yml:502`；fulltest 模块数 9（本地跑须注入 JAVA_HOME/ANDROID_HOME/GRADLE_USER_HOME 三件套——**直接 `bash scripts/fulltest.sh` 不注入会因 JAVA_HOME 缺失秒败**，`localbuild.sh fulltest` 也不是合法 task；正确式 = env 注入 + `bash scripts/fulltest.sh`）；行数表 10 行（人格件零触碰 Coordinator 余 55）。
2. **#42 运维提醒**（开发席）：W71 收官交接（`docs/handoff-*-wave71-*`）落笔前，其 §五 delta 的 W71-xx ID **必须先入 docs/15 主台账**，否则 #42 判红（设计意图 = G1 机械兜底，非误报）。任何增删 `appliesToRuntime` 字面量处数必须同 commit 改 docs/12 锚 #14 依据列（「三条」+「第 3 处」两处同步）——#41 方向 C-1 机械钉住。
3. **真机驱动脚本坑（W70 新增 3 条 + 事故）**：① 生成中应用拦截页签导航（必须 journal settled + 无「停止」+ 15s 稳定化 + 导航后以页面标记验证 + BACK 重试）；② 激活警告横幅在位时输入栏上移（必须动态 find EditText bounds）；③ logcat 陈旧行污染（唯一日志文件 + pkill + verdict 时间戳过滤）；④ 🔴 datastore UUID 补丁：编译态 `pat.search(b, ki)` 正确，切片法须修正位置——写坏过一次，恢复法 = 事前快照 push 回 `files/datastore/liquid_agent_settings.preferences_pb`。
4. **K-1 单源链**（零触碰）：CEIL=9 ↔ 4608 ↔ 5.06 ↔ 1536（2047 臂已还原）。
5. **W71 入口候选**：主刀 = bump 首跑判据（W68-06 blocker，锚 #5 未验）或 seam②（AgentRunner 余 158）；治理槽候选 = #42 的 M12 自动化延伸（W70-01 之外的台账守卫扩展）或 W69-05（#41 镜像/续行形态）；产品件候选 = evidenceLevel 徽章（W68-03，待 C5-1 裁决）或人格档位加档/调档；真机件 = 检查② 0.18.0 复测（需 AAR，另立项）。

---

## 七、CI run id（push 后回填）

| workflow | run id | 结论 | 关键 job |
|---|---|---|---|
| Build | 待回填 | — | @ `7450b21`（或收官 commit） |
| Release | 待回填 | — | 同上 |
