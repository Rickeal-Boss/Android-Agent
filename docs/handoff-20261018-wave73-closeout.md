# W73 波次收官交接（Wave 73 Closeout · 清验证债 + 上游解锁波 + P0 事故波）

> 基线：W72 收官 `5c069f4`｜本波 commit 见 §二
> 单一执行依据：`_plans/w73-plan.md`（v1，方案席产出 + 主理人审订）+ 中继 `_plans/w73-checkpoint-20261018.md`
> 前置：W72 交接 §五/§六 + 五份外部审查报告（2026-10-18 批次：第十七审 / 深度审查 V 系 / 十六审 N 系 / 复审27-莫斯线 / v26）

---

## 一、一句话 + 关键现实

**W73 = 「清验证债 + 上游解锁」波**：上游线主刀（**0.18.0 收敛删除回归 issue 起草入仓** + C6 裁决材料四补）+ 内核验证真机批（振荡 ⚠️ 未行使 / ask_actor ⚠️ 部分行使 / 音频 section ✅ 虚高排除 / NPU 注记）+ 治理槽「工具化批」（G1–G4）+ **K2 层5 压缩门控正式判死**（主理人终裁，台账 W73-01）——**以及计划外插旗的 P0 事故**（用户报告「同意用户协议后即应用闪退」）：根因 = 测试夹具缺外层 protobuf 包裹 + 测试 ⑪ 绿面漏 mock `_apply`，带设备跑测试把夹具写进真机 datastore ⇒ DataStore CorruptionException 崩溃循环；两 commit 结构性了断（结构闸 + 设备隔离绊线），设备已恢复。

关键现实（动手前必读）：

1. 🔴 **P0 事故与了断**（本波最高价值产出，台账 W73-04 closed-done）：
   - 现象：App 启动即崩循环，`CorruptionException: Unable to parse preferences proto` × 5（15:35–15:50）；用户面感知 = 「同意用户协议后闪退」。
   - 根因两段：① 夹具 `_string_entry`/`_bool_entry` **缺外层 `0a <len>` map 条目包裹**（`_str_entry` 有）⇒ `make_string_blob` 产出畸形 43B；② 测试 ⑪ 绿面**只 mock `device_read` 不 mock `_apply`** ⇒ 带设备跑套件时真实 `_apply` 把夹具推进真机 datastore。App 是受害者（DarkMode 枚举无「LIGHT!」）。
   - 修复：`3ccb3ad`（`validate_preferences_blob` 整文件结构闸 + `_apply` 写前闸 + 夹具修正 + P0 回归四例）+ `6727b7e`（⑪ 绿面补 mock + run_adb 绊线 ⇒ **测试套件离线契约机械化**）。20/20 OK；变异自证两轮（掏空校验器 ⇒ ⑭/⑮ 红；find_key_offset 打回首现 ⇒ G1 两例红）。
   - 设备恢复：927B 全量快照经工具 restore 逐字节还原；App 健康（FATAL=0）。
2. 🔴 **新方法学教训（本波新增，已入 docs/05 待补）**：
   - 🔴 **测试套件的「离线承诺」必须在唯一收口机械化**：注释写「不碰 adb」不算数——mock 漏一层设备函数（只 mock 读不 mock 写）就会把夹具写进真机。绊线设在 `run_adb`（全部设备 IO 唯一收口）上才可证明离线。
   - 🔴 **`adb shell input text` 的设备侧 shell 会把 `;` 当命令分隔符** ⇒ 题面在第一个分号处被静默截断（osc4/osc5 实证）。修法：`adb shell "input text '<escaped>'"` 单引号包裹。
   - 🔴 **实测 gap 订正**：真编码 gap=**4**（键尾 122→UUID 126，间隔 `12 26 2a 24`；莫斯线申报的 2B 是键尾→0x2a 串标签距）⇒ 阈值上界零余量，禁放宽迁就。
3. 🟡 **真机批结论**（docs/10 §11.11 新节；原件 `_research/w73-kernel-verification.md`）：
   - **B1 振荡 ⚠️ 未行使**：四次诱导全记录（题干截断×2 → 批量调用 0 执行 → 幻觉脱稿）；离线 `ToolOscillationDetectorTest` 保持触发链权威判据（W69-10 先例）。
   - **B2 ask_actor ⚠️ 部分行使**：planner 全链双证（父 journal ok=true + logcat「子代理 planner 完成」×2）+ re-prefill 读数 21885ms；critic/summarizer 未行使（模型重复调 planner 后收尾）。
   - **B3 音频 section ✅ 虚高排除**：E2B-it/E4B-it 容器 AUDIO section 在档（同窗 VISION_ENCODER 可见 = 窗口有效性）⇒ 条件产品件不触发 ⇒ W73-03 销账。
   - **B4 NPU**：⛔ 无设备不可验证注记 + W73-02 parked。
   - **附带行为发现**：模型批量多工具调用 ⇒ App 文本协议**静默 0 执行**（复现 3 次；建议 UI 波评估可见提示）。
4. 🟡 **K2 层5 压缩门控 = 判死**（主理人终裁；压缩恒由 W41 估算开窗驱动、无失准事故；接线需解 runMutex 重入、动核心循环锁结构风险收益不对称）⇒ 台账 W73-01 closed-dead + docs/12 判死节 + AgentRunner/RunTokenLedger 两处 KDoc 补裁结论句（只加句）。
5. 🟡 **上游线**：`docs/upstream/0180-convergence-removal-regression-issue.md`（新件，⬜ 待用户手动提交；与 #3576 不同族声明在正文）+ docs/14 §B3 + §D #14 行；C6 材料四补（N-2 指纹策略格 / V-2 主推点名两处 / X4 / v26 三补充）。**与 gallery #1326 回复同一次用户触点提交**。
6. ⚠️ **过程**：平台 500 ×3（方案席 26m / CI 席 39m / 开发席 40m 均瞬时基础设施故障，恢复或主理人接管）+ CI 席 429（G1 解锁后中断，主理人核验其已落盘的 G1 半成品——工具侧完整、测试 22/22——补做变异自证后提交 `0248c36`）。真机批由主理人接管执行（开发席 500 中断后按预案行使）。

---

## 二、改动清单（10 commit）

| commit | 性质 | 内容 |
|---|---|---|
| `5390d72` | test(selftest) | **G3**：case 编号查重机械位（`uniq -d` 非空判红终局；N-3） |
| `ffac73e` | refactor(core-engine)+docs | **G4**：ThinkingBudget CEIL KDoc 推导长段（~43 行）移注 docs/12 K-1 归档段「W73 K4 移入」节；文件 128→90 行（余 22→60）；顶部「三处同步单」纪律块未动；InvariantTest 3/3 绿 |
| `3c09e50` | feat(datastore)+docs | **G2**：`set-string-key` 通用写命令（白名单制 + 编译态定位 + value 区 diff + 越界回滚；N-1）+ docs/05「先扩工具再实验」铁律句 + **P4-b**（零差异双消息订正 + tmp 清理） |
| `19895f4` | docs | **上游线与文档批**：A1 回归 issue 新件（`docs/upstream/0180-convergence-removal-regression-issue.md`，⬜ 待用户提交）+ A2 docs/14 §B3 + §D #14 + A3 C6 材料四补 + A4 V-1 KDoc 边界段 + A5 docs/12 七格取证判定表 + 锚#13 复核要点 + 季扫 + K2 判死三落点 + R4 NPU 注记 + W73-01/02/03 先入 docs/15 |
| `467961c` | docs | W73-03 销账（R3 结论）+ README M4 NPU 口径同步 |
| `3ccb3ad` | fix(datastore) | **P0 结构闸**：`validate_preferences_blob` + `_apply` 写前闸 + 夹具补外层包裹 + P0 回归四例（真机样本放行 / 事故字节拒杀 / _apply 零设备 IO / 夹具自洽） |
| `6727b7e` | test(datastore) | **P0 根因**：⑪ 绿面补 mock `_apply` + 索引测试补 mock + **run_adb 绊线**（套件离线契约机械化） |
| `c23a0b8` | docs | 真机结论入档：docs/10 §11.11 新节 + §11.8 音频段订正 + W73-04 台账 |
| `0248c36` | fix(datastore) | **G1**：find_key_offset 双断言（count≠1 + gap≤4 联用，真 gap=4 实测订正）+ P4-a 编码拒绝 + 2 单测（22/22）+ 变异自证（first-hit ⇒ failures=2） |
| （本提交） | docs | 收官交接 + README 波次段 |

## 三、真机复验（OPPO A13 `13309cc8`，有线 adb；载体系 Qwen2.5-1.5B）

### 3.1 内核验证批（详见 §一.3 与 `_research/w73-kernel-verification.md`）

B1 振荡 ⚠️ / B2 ask_actor ⚠️ 部分行使（planner ✅）/ B3 音频 ✅ 虚高排除 / B4 NPU 注记。datastore 全程快照 + 工具路径 + 逐字节还原；终态与 927B 全量快照 verify 0 差异。

### 3.2 P0 事故处置（时间线）

15:16/15:29 CI 席带设备跑测试（夹具上设备）→ 15:29 设备 datastore 被换成 43B 畸形 → 15:35–15:50 App 崩溃循环（用户 15:51 报告）→ 16:00 主理人 restore 927B → 16:11–16:16 主理人跑测试再次写入（45B）→ 16:22–16:37 结构闸 + 绊线两 commit → 16:41 restore 927B → 跑测试零设备接触（mtime 实证）→ 设备终态 verify 0 差异。

## 四、审查对账（五份报告逐条处置；详见 _plans/w73-plan.md §二 22 条判读）

| 来源 | 条目 | 处置 |
|---|---|---|
| 第十七审 | K1 回归 issue + C6 折算 | ✅ A1/A2/A3 落地（K1「暂不 bump + 押上游」框架入 C6 材料） |
| 第十七审 | K2 层5 裁决 | ✅ **判死**（主理人终裁；W73-01 closed-dead + 三落点） |
| 第十七审 | K3 内核验证批 | ✅ 执行（B1 ⚠️ / B2 ⚠️ / B3 ✅ / B4 ✅） |
| 第十七审 | K4 ThinkingBudget 瘦身 | ✅ G4（128→90） |
| 第十七审 | K5 LOSSY 监控机械化 | ✅ A5（docs/12 锚#13 + 季扫；不建守卫） |
| 第十七审 | 人格加档「免裁决保底」 | ⛔ **与 W72 负面清单冲突，不代裁**（留 §八 用户议程） |
| 深度审查 | V-1 UNSAFE KDoc 当前态边界 | ✅ A4（补边界不删原描述；:150-151） |
| 深度审查 | V-2 C6 主推点名 | ✅ A3（两处「≥2/14（含主推…）」） |
| 深度审查 | V-3 七格判定表入仓 | ✅ A5（docs/12 锚#1 节；脚本本体仍仓外，先例同 170KB 模板） |
| 深度审查 | V-4 LOSSY 验 user 分支 | ✅ 并入 A5（锚#1 复核流程第 9 条） |
| 十六审 | N-1 set-string-key | ✅ G2（①②同做） |
| 十六审 | N-2 UNVERIFIED 默认策略 | ✅ A3（指纹匹配 UNSAFE 才拦） |
| 十六审 | N-3 查重机械位 | ✅ G3 |
| 复审27 | P3-A 双断言 | ✅ G1（`0248c36`；gap 实测订正 4B） |
| 复审27 | P4-a/b | ✅ G2 搭车 |
| 复审27 | P4-c assert_red 前缀匹配 | ⛔ 负面清单（动断言机制语义面、无触发事故） |
| v26 | X4 + C6 三补充 | ✅ A3（docs/15 W72-02 + §C6 三选项各一行） |
| 全部 | 报告判据回源核验 | V-1 引文逐字核实（:150-151）；V-2 recommended 实拉；K2 自陈两处亲核；莫斯 gap 申报订正（2B→4B 同族口径） |

## 五、挂账（W73 delta；权威版 = `docs/15-backlog.md`）

**销账**：W73-03（音频虚高排除，closed-done）、W73-04（P0 事故，closed-done）。
**新增**：W73-01（K2 判死，closed-dead）、W73-02（NPU，parked）。
**修订**：W72-02 重启前提补 0.18.0 维度（X4）。
**沿袭**：法务 termsVersion / 0 tags / main FF / F4 / `TokenUsage.estimated` / fulltest 接 CI / **用户触点五件套**（gallery #1326 回复 + 回归 issue 提交 + A1 手验 + W68-01 + **C6 bump 裁决**）/ 人格加档（用户议程）/ C5-1 悬置条款首次行使确认 / W68-07（GPU 用户故事）/ P4-c（观察项）。
**负面清单兑现**：bump 本体（toml 零触碰）/ 人格加档不代裁 / templateArraySafety·evidenceLevel 不接 UI / K2 接线实现 / P4-c / R1·Phi-4 补证本体 / 七格 harness 整体入仓 / MiniCPM5 模板修复。

## 六、下次接手须知

1. 🔴 **测试套件离线契约已机械化**（run_adb 绊线）：给 `scripts/tests/test_datastore_tool.py` 加测试时必须整体 mock 设备层（`_apply`/`device_read`/`device_push`/`device_copy_back`/`_cleanup_remote`）；带设备跑套件如今安全，但**新增测试漏 mock 会被绊线当场炸出**——这是设计意图。
2. 🔴 **`adb shell input text` 分号截断**：题面含 `;` 必须 `adb shell "input text '<escaped>'"` 单引号包裹（`_ci-tools/_w73_run.sh` 已修，复用其写法）。
3. 🔴 **datastore 三道闸**（按序）：`validate_preferences_blob` 整文件结构闸（`_apply` 统一入口）→ `find_key_offset` 双断言（count≠1 + gap≤4，真 gap=4 零余量）→ 写后 diff 越界判红自动回滚。改任何一层前先跑 `python scripts/tests/test_datastore_tool.py`（**22/22**）。
4. **selftest `--only` 收守卫号**；case 编号查重已机械化（G3，`uniq -d`），下一个空闲 case **77**。
5. **振荡 A/B 真机诱导未成功**（四形态在案）：重启前先读 `_research/w73-kernel-verification.md` §一；驱动 `_ci-tools/_w73_run.sh`（am start + 前台校验 + 周期唤醒 + 单引号题面）可直接复用；**多轮对话后续航**（同一会话发后续消息）是下一步诱导的关键缺口（现驱动每次 force-stop 开新会话）。
6. **ask_actor critic/summarizer 补证**：同上——需要同会话多轮或更强模型；planner 双证范式（journal + logcat）已钉。
7. **K2 已判死**：AgentRunner:799-800 / RunTokenLedger:64-66 的 KDoc 已带「W73 裁决：判死」句；重启前提 = 估算失准事故（台账 W73-01）。
8. **UI 波入口候选**：`_plans/w73-plan.md` §十（evidenceLevel 徽章 / templateArraySafety UI / 人格加档 / 音频能力位下游 / 数据回填 / W69-12/13）+ 本波新增「批量工具调用 0 执行的可见提示」。
9. **ThinkingBudget 余 60**（G4 后）：bump 准备/C6 回填可写此文件，不再撞 #36。

## 七、CI run id

| workflow | run id | 结论 | 关键 job |
|---|---|---|---|
| Build | （推送后回填） | | |
| Release | （推送后回填） | | |
