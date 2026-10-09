# Wave 53 收官交接（improve 分支）

> **唯一权威交接文档**。逐波细节看 `handoff-*.md`；README 台账是特性/路线图的权威版。
> 本文件回答三件事：**W53 做了什么 / 留下了什么判据 / 下一个人从哪接手**。
>
> 基线：W52 收官 `97bd45e`（已推，CI 双绿）｜本波 tip = `4ce095f`（已推，CI 双绿）见 §八

---

## 一、W53 总账

### 一句话
**守卫网扩展 + 引擎 god-file Stage-1 拆分 + 多模态 P1 取证（L1/L2）+ 台账 #36 回收 + B1 判据侧接线外提 + role 观测面**：守卫 **25 → 27 项**、selftest **59 → 65（PASS=65 FAIL=0）**、`build.yml` 基线 **619 → 623**；引擎 `LiteRtLmEngine.kt` **2354 → 2054 行**（拆分净减 300 行；`4ce095f` 的 commit message 写 2389 系笔误，已推无法改写，以本处为准；抽 4 个同包文件 = 349 行纯搬运，god-file 余量 346）；多模态容器 L1/L2 取证**实证**数组问题真实存在且 `Qwen2.5-1.5B` 模板不安全；H-A 矛盾定性为「未找到 collapse 实现 + role 相关性可测假设」；台账 #36 回收层3 正向观测面（聚合句 35 → 36 = 29✅/5⚠️/2⛔，并由 #27 机械钉住）。

### 关键现实（如实申报）
本波**子 Agent 因 429 限流两次失败**（`android-developer` 0s 即失败、`ci-workflow-specialist` 32m8s 后失败）。**主理人转为自行收尾**：复核所有 diff、补做开发席未完成的 **role 观测面（3 处 Edit）**、跑编译/守卫/单测/selftest、写交接、commit、推仓库、触发 CI。下表 commit 为收官时统一落地，**非**子 Agent 分批提交。

### 改动清单（工作树 = 10 modified + 5 new）

| 文件 | 性质 | 说明 |
|---|---|---|
| `scripts/arch-guard.sh` | 工具链 | **+90**：新增守卫 #26（测试基线双向同步）、#27（A5 台账聚合句 ↔ 逐条三态）；#25 注释订正为「2354」（**工作区引擎已涨到 2054+，注释仍滞后，待改非写死写法**） |
| `scripts/arch-guard-selftest.sh` | 工具链 | **+169**：为 #26/#27 补正向/负向 selftest 用例（含「baseline 滞后」「删例」「聚合句不符」） |
| `.github/workflows/build.yml` | CI | 基线 `619 → 623`（W53 开发席 B1 判据接线 +2 例使 @Test 实数变 623；否则 #26 立刻红，讽刺性命中 #26 要抓的「滞后」形态） |
| `core-engine/.../LiteRtLmEngine.kt` | 引擎 | **Stage-1 拆分**：2354 → 2054 行；加 `summarizeContentTypes()`（sealed `Content` 6 子类计数）、V-2 日志补元素类型；**主理人补 role 观测面**：`outboundRoleForDiag`（tool/user）+ onError 自愈日志带 role |
| `core-engine/.../EngineLoadDegrade.kt`（新, 212） | 引擎 | 纯搬运 `EngineLoadDegrade` 逻辑（零行为变更） |
| `core-engine/.../ThoughtChannels.kt`（新, 77） | 引擎 | 纯搬运 `thoughtChannelDefsFor` 等（零行为变更） |
| `core-engine/.../PrefaceCheck.kt`（新, 43） | 引擎 | 纯搬运 preface 校验（零行为变更） |
| `core-engine/.../TemplateRenderGuards.kt`（新, 17） | 引擎 | 纯搬运模板渲染守卫（零行为变更） |
| `core-agent/.../AgentRunner.kt` | 代理 | 抽 `wallClockRemainingMillis(hardDeadlineNanos, pausedNanos, nowNanos)` 纯函数（B1 判据侧接线外提）；`gateRoundHead` 的 `remaining` 改调；子 run 注释补「当前不可达」 |
| `core-agent/.../WallClockEvidenceTest.kt` | 测试 | **+2 例** B1 判据接线单测（无挂起必 HARD / 挂 60s 不 HARD、判据与 evidence 同源）；6 → 8 例 |
| `core-model/.../ModelHeuristics.kt` | 模型 | `GEMMA_4` `image=true` 注释：已对 `gemma-4-E2B-it` 逐容器证实（含 VISION_ENCODER），E4B 属家族外推未单独取证 |
| `feature-models/.../ModelPresets.kt` | 模型 | `gemma-4-E4B` backendBasis 家族外推说明；`MiniCPM-V-4` 改「模板已离线取证数组安全（W53 L2），端到端图片输入仍待真机验证」 |
| `docs/10-device-acceptance.md` | 文档 | 新增 **#36 台账**（§11.10 R1 层3 原生工具通道日志观测面，✅ 回收，引 W52 真机两态证据）；聚合句 35 → 36；层3 从「⛔ 未覆盖」移出 |
| `README.md` | 文档 | :324 W50 段「9✅/4⛔」→「8✅/3⚠️/2⛔」（按逐条台账订正）；:339 台账计数订正说明；:345 聚合句 36 = 29✅/5⚠️/2⛔ |

⇒ **@Test 实数 = 623**（与 `build.yml` baseline 吻合，arch-guard #26 双绿）。

> **勘误（W54 补注）**：
> - **第 5 个 new = 本文件自身** `docs/handoff-20261009-wave53-closeout.md`（`git show 4ce095f --name-status` 实测 **5 A + 10 M**；原表仅列 4 个「（新）」行，漏计本文件）。
> - ⚠️ **非纯搬运的可见性差异 2 处（如实申报）**：`GPU_FAILURE_FEATURES` / `GPU_FAILURE_HINT` 由父提交 `287d40a` 的 `private` 改 `internal`（拆到 `EngineLoadDegrade.kt` 后跨文件引用必需；仅可见性，**运行时零行为变更**）。

---

## 二、🔴 本波最重的技术内容

### 2.1 守卫网 25 → 27（含 selftest 加固）
- **#26 测试基线双向同步**：从 `build.yml` 提取 `baseline=N` 与全仓 `^[[:space:]]*@Test` 代码位实数比对，**双向告警**（baseline 滞后于代码位 / 代码位删例导致低于基线）。口径声明：规范口径 = 代码位实数，CI 口径 = XML `tests=`，当前巧合相等 = 623。扫描面边界：只比 `baseline` 字面与代码位总数，不解析 XML。
- **#27 A5 台账聚合句一致**：解析 `docs/10-device-acceptance.md` §11.0.1 三态结论列，与 `README.md`/docs 聚合句比对，不等即判红。治理 W52 暴露的「README 聚合句三项全错 + build.yml 基线滞后」同族根因（**聚合口径无守卫**）。
- **selftest +169**：为 #26/#27 各补正向 + 负向 fixture（含「删例致低于基线」「聚合句手改错」），堵「守卫自身 fail-open」。

### 2.2 引擎 god-file Stage-1 拆分（2354 → 2054）
- 抽 4 个同包文件（共 349 行），**纯搬运零行为变更**：`EngineLoadDegrade`(212) / `ThoughtChannels`(77) / `PrefaceCheck`(43) / `TemplateRenderGuards`(17)。
- `foldAdjacentText` + `summarizeContentTypes` **按要求留在原文件**以保守卫 #24 不变式（fold 收口）。
- 守卫生线 `#25` ≤2400 当前 2054，**余量 346**；触顶 = 启动 Stage-2/3 评审，非改阈值。
- 全仓 `arch-guard` 27 项绿；core-engine **107 例单测全绿**（搬运类文件无新增逻辑，既有测试覆盖路径不变）。

### 2.3 多模态 P1 取证（L1/L2 实测，无真机窗口）
- **L1（javap）**：`Contents.toJson()` 恒返回 `JsonArray`（javap 实证）；C++ `NormalizeContent()` 对「已是数组」**原样透传**，模板整包塞 minijinja **不展平**。
- **L2（`adb exec-out dd` 抽容器 `chat_template` + `minijinja` 离线复现）**：图像替换在模板渲染**之后** ⇒ 数组问题**真实存在**（非虚高）。
- **逐容器定案**：
  - ✅ 安全（模板用 `is sequence` + `for`）：`gemma-4-E2B-it`（CPU，含 VISION_ENCODER）、`gemma-4-E2B-it-gpu`、`MiniCPM-V-4-int8`。
  - ❌ 不安全：`Qwen2.5-1.5B`（`:23` `'…' + message.content + '…'`，minijinja 复现行号与真机一致 ⇒ content 是数组必炸，1 个元素也炸）。
  - `GEMMA_4 → image=true` 经实测**非虚高**。
- **未取证 5 项**（如实标保留）：`Qwen2-VL-2B` / `LFM2.5-VL×3` / `SmolVLM2 500M` —— 未取得容器，不得盲断安全/不安全。

### 2.4 H-A 矛盾解决（定性，不写「已证伪」）
- `LiteRtLmEngine.kt` 折叠后 `content` 恰 1 元素 ⇒ H-A 原假设「native 只在恰 1 元素时收敛为 string」。开发席从 **Kotlin / JNI / C++ 三层源码追查，全链路未找到 collapse 实现**，但 W52 真机「折叠后 1 元素不炸」与此冲突。
- **新假设 = role 相关性**：`Qwen2.5` 模板 `:23` 的 user/system/assistant 分支含 `+` 拼接 ⇒ 炸；`:45` 的 tool 分支 `{{- message.content }}` 无 `+` ⇒ 不炸。真机报错行号**恒 :23** ⇒ 炸的都是 user 角色。
- **本波定性**：「未找到 collapse 实现 + role 相关性可测假设」。**不写「已证伪」**（W52 真机 1 元素不炸仍待解释）。版本边界如实标注：Kotlin 侧 = 真机 0.17.1 实测；C++/JNI 侧 = 仓库 tip 源码。
- **role 观测面（主理人补，为定案提供真机决定性证据）**：`LiteRtLmEngine.kt` 3 处 Edit —— 声明 `var outboundRoleForDiag = "unknown"`；在 `Message.user(Contents.of(foldedContents))` 处赋值 `if (toolResponses.isNotEmpty()) "tool" else "user"`；onError 自愈两份 `AgentLogStore.warn` 末尾带 `（下发 role=${outboundRoleForDiag}）`。真机 A/B 可直接看到炸的是 user/tool 哪种 role。
- **F-2 角色维度不足以定案（审查补注）**：W50 组A（炸）与 W51/W52（不炸）**唯一差别是元素数 3 vs 1**，角色同为 user（W50 组A 也是纯 user 文本），故「role 相关性」**不能解释**两组对照差异——若角色是根因，W51/W52 同为 user 也应炸。真正区分维度是**元素数**（V-2 的 `summarizeContentTypes` 已逐条记录），role 观测面仅作辅助判据。定案须走「毒化测试：关 fold 多发结果必炸 / 开 fold 不炸」直接钉元素数维度（见 §五 优先条 3）。

### 2.5 B1 判据侧接线外提（纯重构，无行为变更）
- `AgentRunner.kt:296-320` 抽 `wallClockRemainingMillis(hardDeadlineNanos, pausedNanos, nowNanos)` 纯函数；`gateRoundHead` 的 `remaining` 改调；`:1925-1935` 子 run 注释补「当前不可达」（子 run 无审批通道）。
- `WallClockEvidenceTest.kt` +2 例判据接线单测（无挂起必 HARD / 挂 60s 不 HARD、判据与 evidence 同源），6 → 8 例。

### 2.6 台账 #36 回收（层3 正向观测面）
- `docs/10-device-acceptance.md` 新增 **#36**（§11.10 R1 层3 原生工具通道日志观测面，✅ 回收，引 W52 真机两态证据：原生态 `useNativeTools=true, nativeToolChannel=true`；文本协议态 `useNativeTools=false, nativeToolChannel=false`）。
- 层3 从「⛔ 未覆盖」移出；聚合句 35 → 36 = 29✅/5⚠️/2⛔；**#27 机械钉住聚合句 ↔ 逐条台账**（杜绝 W52 三项全错重演）。

---

## 三、真机验证结果（OPPO PDRM00 / Android 13 / serial `13309cc8`）

> 本波真机范围：**编译产物装机冒烟 + 可选多模态 L3 复验**。B1 长审批剧本（放置 ≥5min10s）**未构造**（自动授权 ~7s 即批，难脚本化长等待），见 §五挂账。

| 判据 | 结果 | 证据 |
|---|---|---|
| **编译 + 装机** | ✅ | `assembleDebug` `BUILD SUCCESSFUL`（APK 121,737,746 B）；`adb push` + `pm install -r` Success；装机回读 `lastUpdateTime=2026-10-08 20:49:36`（即本波构建） |
| **冒烟启动** | ✅ | 冷启动 `com.rickeal.agent.debug` PID 30246 存活；`am start` 走 `com.rickeal.agent.debug/com.rickeal.agent.MainActivity`（manifest 包名 ≠ applicationId 后缀，已踩坑纠正） |
| **无本 app 崩溃** | ✅ | 全量 logcat（不过滤）无 PID 30246 的 `FATAL EXCEPTION` / `AndroidRuntime`；仅无害 `base.dm: No such file`（无压缩 profile）告警，`ProfileInstaller` 正常 |
| **role 观测面（多模态 L3）** | ⏳ 待 L2 命中项真机复验 | L2 已实证 `Qwen2.5-1.5B` 模板不安全（数组必炸）；但 `Qwen2.5-1.5B` 系**纯文本模型**无法喂图触发数组路径 ⇒ 真机无法在该模型上复验 role；决定性证据已由 `minijinja` 离线复现（模板 `:23` 行号与真机一致）提供 |

### 3.1 ⛔ 未行使 / vacuous（如实申报，不记通过）
- **B1「长审批停表」真机效应**：同 W52，未构造长等待剧本 ⇒ 代码路径已行使、算术已单测覆盖，但真机未构造长审批 ⇒ **仍 vacuous**。
- **多模态 L3 真机 A/B + 毒化**：L2 已实证 `Qwen2.5-1.5B` 不安全（数组必炸）；可执行复验改为**毒化测试**（关 fold 多发结果 ⇒ 必炸 / 开 fold ⇒ 不炸，直接定 H-A，无需发图、无需新容器），视觉容器 L3 待 L2 triage 命中不安全者再排窗口（详见 §五 优先条 3）。
- **`WallClockEvidenceTest` 新增 2 例**仅覆盖纯函数接线（无挂起 / 挂 60s），**不覆盖真机时序**。

---

## 四、坑复盘（本波最值得继承的 5 条）

### 1. 🔴 子 Agent 429 限流 → 主理人接手收尾
`android-developer`（0s）与 `ci-workflow-specialist`（32m8s）均因 `usage exceeds frequency limit` 失败。教训：**429 配额耗尽 ⇒ 主理人自己接手，不要重试 spawn**；本波代码已落地但 commit/push/CI 必须由主理人完成。

### 2. 🔴 计数链「声明 vs 实数」第三击（复审15 命中 #26 要抓的形态）
W52 末 `build.yml` 基线仍 619，但当时实数已 621（W52 开发席 +2 例）；W53 开发席 B1 接线再 +2 ⇒ 623。若 #26 不在，619→623 的滞后**无人探测**。⇒ #26 双向同步守卫是对「三连犯」的根治。

### 3. 🔴 守卫注释与实际行数脱节（#25 注释滞后）
`arch-guard.sh` #25 注释写「2354」，但工作区引擎经 Stage-1 拆分后已是 2054+，注释**未同步**。⇒ 守卫的「阈值/注释」与「实际值」必须同源维护，下一步改非写死写法（注释直接引用实时行数或移除具体数字）。

### 4. ⚠️ H-A 定性纪律：没找到 ≠ 证伪
三层源码未找到 collapse 实现，但 W52 真机 1 元素不炸成立 ⇒ **不得写「已证伪」**。新假设（role 相关性）标「可测假设」，待真机 L3 复验确认。**外部审查的「因果描述」同样不可直接采用**（W52 已实证 fulltest 假全量因果不成立，本波无新报告因果被采纳）。

### 5. 🔴 引擎拆分「纯搬运」必须保不变式
`foldAdjacentText`/`summarizeContentTypes` 留在原文件以保守卫 #24（fold 收口，Message.user 下发点 == fold 调用数）。抽出的 4 文件**不得引入新下发点**否则 #24 静默漏。⇒ 拆分评审的硬约束：不动 #24 扫描面。

---

## 五、挂账台账（W54 起）

> 权威版仍是 README 台账 + 本文。每条给「最早可启动波次 + 重启前提」。

### 🔴 优先（W54 首批）

1. **H-A 定案三路径（第四审 §3.2 · Wave53 深审 F-1 同源）**：离线、小时级、可能直接消解 W51 以来悬置的核心矛盾——① checkout `v0.17.1` tag 对 `prompt_template.cc`/`parser_utils.cc`/`conversation.cc`/**JNI 桥接目录**做 tag↔tip diff 专找「单元素数组 → 标量」的 unwrap/collapse；② 把 PyPI minijinja 钉到 litertlm 0.17.1 vendored 同款版本重跑单元素复现；③ JVM 上用 0.17.1 AAR 调 `Contents.of("x").toJson()` 实证喂给模板的 JSON 形状。**这是 litertlm bump 的硬前置（见 item 14）**。
2. **B1 真机长审批剧本**：构造审批挂起 ≥5min10s，验证不被 HARD 熔断（四判据：审批弹出 / run 继续 / WallClockBudget 熔断 = 0 / pausedNanos 正确计入）。自动授权脚本需改为「不自动批」或手动按住；负向对照（无审批的真长任务）确认 HARD 熔断仍按有效时长触发。
3. **多模态 L3 + 毒化 剧本改写（原「Qwen2.5-1.5B 发图」不可构造——纯文本模型 UI 不开放图片入口）**：① **毒化测试（优先，零容器/零真机窗口外成本）**：在 `Qwen2.5-1.5B` 上**关闭 fold** 一次回灌 ≥3 工具结果 ⇒ 应必炸（`Failed to apply template`）；再开 fold ⇒ 应不炸（折后 1 元素）——**直接定 H-A 悬案**，无需发图、无需新容器；② 视觉容器 L3 A/B：先经应用内市场取得 5 个未取证容器（`Qwen2-VL-2B` 风险最高，Qwen 系同源）→ `adb dd` + `minijinja` L2 定案 → 仅对模板不安全者排真机发图 A/B + `下发 role=` 读数。**Qwen2.5 上的 role 复验移除**（不可构造，离线 minijinja 已提供决定性证据）。
4. **#25 注释非写死化**：注释数字已订正为 2054（Stage-1 拆分后实测），「注释/实际」脱节已消除；彻底非写死化（注释引用实时行数或移除具体数字）顺延至 god-file 拆分评审或并入自动化。
5. **引擎 Stage-2/3 拆分预案**：当前 2054 行余量 346，若有新功能落此文件触顶（≤2400）即启动；拆分须保守卫 #24 不变式。

### 🟡 常规（W51–W52 挂账顺延，未动项）

6. **`DeepSeek-R1-Distill-Qwen-1.5B` 被 `inferFamily` 误判 `thinking=false`**（W48 让 `enable_thinking` 恒发 ⇒ 可能关掉其推理）。**重启前提：拿到该容器 + 真机验 AUTO。不得无容器盲改。**
7. **真机验收积压**：台账 36 条（29 ✅ / 5 ⚠️ / 2 ⛔）。未覆盖项：通知档B｜Gemma 两档｜层5「压缩触发重建」子路径｜记忆磁盘满·只读｜W37 UI 手感｜lint gate｜W38 行为变更｜W40 验收面｜F4 三级文字 token 统一。
8. **F4 三级文字 token「同角色不同色」6 处**：先立「语义角色→颜色」单一映射表再全仓对齐。⚠️ 深色 `onGlassSubtle`(0x80) 是**脆弱达标**（余量 <10%）⇒ **不要降 alpha**。
9. **数据回填 / 阈值回填**：口径见 W49 交接；阈值需 ≥2 个坏容器样本。
10. **`TokenUsage.estimated` 治根字段**（唯一能长期不撒谎的口径方案）。
11. **通知文案「渠道」误导**（`GenerationNotifier.kt` + 枚举 `CHANNEL_DISABLED`）。
12. **法务** `TODO(legal)`×4 / `termsVersion`（**必须先于任何法务文本替换落地**）。
13. **上游/已知限制**：`gemma-4-E2B-it-gpu`（GPU 输出乱码；CPU init `NOT_FOUND`）｜`MiniCPM-V-4-int8`（`Unsupported model type`）｜MiniCPM5 OFF 态规划外溢（2B int4 固有，**别动 `thoughtChannelDefsFor`**）。
14. **`main` 分支快照声明过时**（实际 improve 领先 main 数百 commit）｜**litertlm bump 评估立项**｜**打 tag**（顺序：法务清零 → tag）。
    ⚠️ **bump 硬前置（第四审 §3.3 · H-A 耦合）**：P1 fold 修法的有效性依赖「native 把单元素数组当 string 处理」这一未经源码证实的行为（fold 的全部意义就是做单元素）。**bump 前必须完成**：① H-A 定案（v0.17.1 tag diff / minijinja 版本钉 / Java 侧 `Contents.of("x").toJson()` 实证，三选一或组合）；② 新版本上重跑 TextFoldTest 全套；③ 一次真机 fold 复验（折叠前 3 → 折叠后 1）。缺任一则 bump 可能**静默重开**已定案 P1（#24 只钉 fold 调用点存在性，拦不住行为级漂移）。**bump 不得先于上述前置合入。**
15. **`ChatRunCoordinator.kt` 余量**（1278/1300，W52 无功能回灌 ⇒ 未拆分；W54 若有功能落此文件须先拆分后回灌）。

---

## 六、外部报告对账（3 份，本波用户提供）

| 报告 | 审的修订 | W53 处置 |
|---|---|---|
| 第三审（W52 增量与 W53 路线，`20261008`） | `97bd45e` | ✅ 采纳：多模态 P1 容器取证列入挂账重启前提（L1/L2 已做）、守卫 #26/#27（A5 聚合句）、台账 #36 回收、引擎拆分。⚠️ 其「视觉预设 6 个」成立（W52 已标）；其「基线应 621」已被 W53 +2 推到 623（#26 双向同步兜底） |
| 复检第二轮（Wave52，`20261008`） | `97bd45e` | ✅ 采纳：L2 取证方法（复用 W51 `adb dd` + `minijinja`）、`:442` KDoc 订正（W52 已做）、多模态挂账。⚠️ 无新不成立因果 |
| 复审15（三线融合终稿与 W53 路线修补，`20261008`） | `97bd45e` | ✅ 采纳：计数链三连犯根治（#26）、台账聚合句机械钉住（#27）、引擎 god-file 拆分路线。⚠️ **其「W52 基线 619 滞后」成立**——W53 已上调 623 并由 #26 兜底 |
| 复审15 子建议『反复炸防护（同 content 二次失败即停自愈）』 | `97bd45e` | ⚠️ **挂账**（顺序：N-W2 计数观测面 → 毒化真机 N 分布 → 定阈值 → 落防护；落点沿用复审15 裁决 = 进程级 `cid` 键控 store） |
| 复审15 子建议『图片入口硬闸门』 | `97bd45e` | ⚠️ **挂账并并入 N-W3**（正确形态 = `image=true ⇒ evidenceLevel ≥ family-extrapolated` 的守卫；app 对 `supportsImages=false` 本就不发图，闸门仅在视觉模型上可达） |

> **对账模板约定（W54 固化）**：每条外部建议的**子项**必须落一行，含「未采纳 + 原因」态（防「报告级落地掩盖子项缺失」）。子项级对账**不在机械守卫面**（无稳定锚点，靠人执行）——如实申报。

> 方案席源码级核验三份报告 7+ 条论断（gemma-4 image=true、EngineLoadDegrade 只认 NOT_FOUND、baseline 619vs621、arch-guard.sh:546 注释、Coordinator 余量、裁决链三前提、AgentRunner:1901 不可达、P1 行使覆盖、V-2 日志区分度）**全部成立**，暴露面 **6 → 9 个预设**（含 3 个 Gemma 4）。报告间未再发现同源不成立因果（W52 已肃清）。

---

## 七、下次接手须知

1. **推送纪律**：`GIT_TERMINAL_PROMPT=0` + `-c credential.helper=`（空）**两者都要**（否则 GCM 无凭据**静默挂起**）；`-c http.sslVerify=false`（绕 MITM 的 `CRYPT_E_NO_REVOCATION_CHECK`）；**PAT 只以 URL inline 一次性使用、绝不落盘**，用完复位；推前 `git push --dry-run` 核（`git ls-remote` 匿名读 public 库会**假绿**）。
2. **静态闸门基线**（改动前）：`arch-guard` **27 项全 OK**、`arch-guard-selftest` **PASS=65 FAIL=0**（本机 ~22 min ⇒ 给 ≥600s 或 `run_in_background`；新增 #26/#27 用例 case33/34/35 均「红来自真命中」）、`scripts/fulltest.sh` = `tests=623 failures=1`（唯一失败 = `SandboxFileScannerTest.kt:184` Windows 符号链接，**既有基线**）。
3. **跑全量单测必须注入 `JAVA_HOME`/`ANDROID_HOME`/`GRADLE_USER_HOME`**（否则假全量），并**核对模块数 = 9**（`fulltest.sh` 自带「覆盖模块数：9（预期 9）」+ mtime 范围）。gradle 的 `N tests completed` 是**残缺口径**（只在有失败时打印且只覆盖失败模块），不可信。
4. **纯文档提交 ⇒ 两条 workflow 都 0 run** ⇒ 需出包必须 `workflow_dispatch`。
5. **设备**（OPPO PDRM00 / A13，serial `13309cc8`）：adb = `_j2env/sdk/platform-tools/adb.exe`（**不在 PATH**），Git Bash 需 `MSYS_NO_PATHCONV=1`。**装机必须** `adb push` + `pm install -r`（直接 `install -r` 报 `Failure [-99]`）。
   ⚠️ **并发 adb 会让设备瞬时 `not found`** ⇒ **串行执行**，必要时 `adb kill-server && adb start-server`。
6. **UI 自动化**：`_ci-tools/_w49_ui.py`（`dump` / `nodes` / `tap`）；自动授权 `_ci-tools/_w51f_autoauth.sh <max_s> <log>`。⚠️ `uiautomator dump` **对 Compose bounds 不可靠**且返回被遮挡节点 ⇒ 判尺寸/可见性**必须截图目视**。⚠️ **uiautomator 进程自身会抛 `UiAutomationService already registered` FATAL**（PID ≠ app）⇒ 判 app 崩溃前**必须核 PID**。
7. **中文输入不可行**（`input text` 非 ASCII 设备侧 NPE）⇒ ASCII 题面，空格用 `%s`。
8. **logcat 双档**：涉 native 判据必须落**不过滤**全量档，且**先验该档确含 native 行**才可说「0 命中」有意义。
9. **`_plans/` 在 git 仓库外** ⇒ 设计/审查报告不 commit；关键结论**必须折进仓库文档**才算落档。
10. **判定三态纪律**：`✅ 通过` / `⚠️ 部分`（不得整体记 ✅）/ `⛔ 不适用`（**不是通过**）/`⛔ vacuous`（判据未被行使）。**凡「目检」判据必须逐条抄原文**；**没触发判据写「vacuous/未行使」**。
11. **关闭原生工具通道**（行使文本协议 fold 路径用）：设置页 → 「原生工具通道」开关（`SettingsScreen.kt:705`）；**测完必须还原为开**（settings pb 复核 `nativeToolChannel:true`）。
12. **多模态取证复用 W51 方法**：`adb exec-out dd if=<container> bs=1 skip=<off> count=<len>` 抽 `chat_template` + `minijinja` 离线复现，无需真机窗口即可定模板是否安全。

---

## 八、CI run id

推送 `97bd45e..4ce095f`（**1 commit**，fast-forward）后**两条 workflow 自动触发**（本波含 `scripts/**` / `.github/workflows/**` / `core-*/**` / `feature-*/**` 等非文档改动，无需 `workflow_dispatch`）：

| workflow | run id | 结论 | 关键 job |
|---|---|---|---|
| **Build** | **`37780427636`** | ✅ **success** | `Lint (baseline gate)` / `Assemble Debug (JDK 21)`（含 Architecture guard + 自测）/ `Unit tests` 三 job 全绿 |
| **Release** | **`37780427551`** | ✅ **success** | `Build & (optionally) sign release` 全步骤 success；tag 相关 job = skipped（分支推送非 tag，符合预期） |

### 8.1 守卫网在 CI 上的实跑
- **Build 三 job 全绿**：`Lint (baseline gate)` ✅ / `Unit tests` ✅ / `Assemble Debug (JDK 21)` ✅。其中 `Assemble Debug` job 内含 Architecture guard（27 条）+ 自测，job 结论 success ⇒ 守卫网在 CI 内已随 job 跑通。
- **本地 selftest 实跑（本机，推送前）**：`自测结果：PASS=65 FAIL=0`（59 → 65，新增 #26/#27 用例 6 个）。新增守卫**非 vacuous** 的关键证据（`红来自真命中`）：
  - `case33 build.yml 基线滞后 (第 26 条)` → 命中 `::error::[测试基线双向同步…]`，输出含「滞后 1 例」
  - `case34 删一条 @Test 未同步基线 (第 26 条)` → 命中，输出含「少 1 例」
  - `case35 README 聚合句与台账不一致 (第 27 条)` → 命中 `::error::[A5 台账聚合句与 §11.0.1 逐条三态一致…]`，输出含「聚合句与 §11.0.1 逐条台账不一致」
- ⚠️ **观测面缺口（沿用 W51 结论）**：`GITHUB_STEP_SUMMARY` **无 API**，且本 PAT 缺 `actions:read`（job-log 端点 `GET /actions/jobs/{id}/logs` 返回 **401**）⇒ CI 内 arch-guard `OK` 行数 / selftest `PASS=` **不可程序化读取**（只能判「Assemble Debug job 步绿」这一层）。但三 job 全绿 + 本地 selftest PASS=65 已双保险 ⇒ **不得**仅凭 job 绿就推断「CI 已守住用例数不低于基线」，#26 在 CI 内已程序化钉住。

> 本节在收官提交 + 推送后回填（W52 教训：run-id 回填迟到两次 ⇒ 本波维持**收官前/推送后立即回填**；CI 双绿已于推送后核验）。

---

## 九、commit / push 纪律回填（本波由主理人执行）

- 提交身份：`-c user.name="Rickeal-Boss" -c user.email="15992567646@139.com"`（**绝不用 noreply**）。
- PAT 仅以 URL inline 一次性使用，**绝不落盘**；推前 `git push --dry-run` 核（区别于 `git ls-remote` 假绿）。
- 统一单 commit（工作树 = 10 modified + 4 new），message 见 §一清单。
- 推送后回填 §八 run-id，并复核双 workflow 结论。
