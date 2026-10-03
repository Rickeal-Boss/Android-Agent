# Wave 48 收官交接 —— N1 / ChatRunCoordinator / 能力位来源 + 本波复盘 + 挂账台账

> 2026-10-03 收官。本文是 W48 的**唯一权威交接**。
> 逐波细节：`_plans/wave48-design.md`（708 行设计）、`_plans/wave48-review.md`（独立质量审查）。
> 仓库：`D:/WBfil/2026-09-18-18-27-37/Android-Agent`，分支 **`improve`**，基线 **`1a0b45a`**。

---

## 一、本波总账

**分支口径**：`improve` 为 W48 起的主开发分支，云端自 `harness-improve` 的**同一提交 `1a0b45a`** 新建（`harness-improve` 冻结保留）。

**本波共 15 个 commit**（截至本交接：`1a0b45a..f3190e7` 已提交 **12** 个 + 本 docs 收口 **1** + CI 席 **2**）。

| # | commit | 一句话 |
|---|---|---|
| 1 | `b7cb1c5` | **fix(core-engine)：N1 根修** —— 思考通道按模型身份选择 def（`thoughtChannelDefsFor`）+ 关思考显式下发 `enable_thinking=false` |
| 2 | `0050f64` | **feat(core-model,core-data)：能力位来源标记** —— 新增 `CapabilitySource`，语义由「并集只增不减」改为「用户显式设置优先」 |
| 3 | `528fa41` | **refactor(feature-chat)：run 编排外提 `ChatRunCoordinator`**（纯搬移，先减；`ChatViewModel` 1593 → ~720） |
| 4 | `4a33bb8` | **fix(feature-chat)：后增** —— Failed salvage 注释（D1）+ `Cancelled` thinking 快照上提为具名 val（G1） |
| 5 | `aca86fa` | **test(core-engine)：盲降路径跳过已降模态**（D2，补 `EngineLoadDegradeTest` 缺口） |
| 6 | `4249707` | **fix(core-data,core-agent)：F2/F3** —— 电池熔断单次读 + `AgentRunner` 过时注释订正 |
| 7 | `762f1de` | **docs：§11 回收口径订正 + 256 KiB 取证前提**（L4） |
| 8 | `3eea941` | **fix(core-engine)：思考通道单测 `front()` → `first()`**（Kotlin `List` 无 `front`，闸门编译修正） |
| 9 | `4868201` | **fix(core-data)：`beforeRound` 电池熔断单次读**（补齐 F2 第二处调用点） |
| 10 | `f7f0585` | **fix(core-data)：去掉 smart-cast 后的冗余安全调用**（`ThermalGovernor` 205/260） |
| 11 | `e2bcbfb` | **docs：收口审查 5 项**（P2-1/2/3 + P3-1/2，文档一致性 + 跨模块 KDoc 指向 `ChatRunCoordinator`） |
| 12 | `f3190e7` | **test(feature-chat)：消除 `HistoryWithProcessTest` 时间戳 flaky**（预存在，非本波回归，见 §二） |
| 13 | *（本 docs commit）* | **docs：W48 收官 —— README 台账同步 + 交接文档**（本文件） |
| 14 | *（CI 席，描述）* | **chore(ci)：`improve` 接入 `build.yml` / `release.yml` 触发**（含 `release.yml` 自测步骤置于 Publish 之前） |
| 15 | *（CI 席，描述）* | **fix(scripts)：`arch-guard.sh` #18 fail-closed（`exit 0` → `exit 1`）+ `arch-guard-selftest.sh` case17/17b** |

> **CI run id 占位**：本波尚未 push（待闸门 task #5 核验）。push 后请回填 Build / Release 两条 run id：
> `Build = <待填>`、`Release = <待填>`。推送方式沿用旧纪律（PAT 走 URL 一次性 + 用完 `git remote set-url` 清除）。

**核心交付（3 项）**：

1. **N1 根修**（`b7cb1c5`）：MiniCPM5 `<think>` 明文混正文 + 1-B「关思考仍有思考区」。
2. **能力位来源**（`0050f64`）：`CapabilitySource` + `resolveCapabilities`，用户显式设置优先。
3. **`ChatViewModel` 职责堆积**（`528fa41` + `4a33bb8`）：run 编排外提 `ChatRunCoordinator`，**1593 → 547 行**。

---

## 二、本波坑复盘（重要）

### 🔴 1. `--continue` 才暴露的 flaky —— 「N tests completed」是残缺口径

- **现象**：`HistoryWithProcessTest` 用例「纯拼接 —— 可见在前过程在后且 TOOL 与中间消息全保留」随机红。
- **根因**：`ChatMessage.createdAtMillis` 的默认值是**构造时**的 `System.currentTimeMillis()`（`core-model/.../ChatMessage.kt:17`）。该用例用**全字段 `assertEquals`** 比对**分别构造**的两个列表 —— expected / actual 各自 `new`，只要两次构造跨了毫秒边界，时间戳就不同 ⇒ 随机红。生产语义其实是「返回入参同一批实例、时间戳原样保留」，本可比；修法 = 给入参与 expected **显式钉同一固定时间戳**（断言仍覆盖 `createdAtMillis`，不是排除它）。
- **为何长期被掩盖**：本地跑 `testDebugUnitTest` 用 **fail-fast** —— `core-data` 先失败（Windows 符号链接基线失败）就**中止**，**`feature-chat` 的测试从来没跑到过**。直到本波改用 `--continue` 才现形。
- **判据沉淀（硬纪律）**：**本地跑全量单测必须带 `--continue`**，否则「N tests completed」是**残缺口径**（后面的模块根本没跑）。本仓历史上多次「全绿」结论可能都因此失真。
- 落档：`f3190e7`（含「勿回改」注释，写清根因与修法）。

### 🔴 2. 报告「fail-open」结论不成立（本仓第 17 次「描述不成立」）

- **原描述**（审查方 v9 §N-9.1 / deepdive §3）：`arch-guard.sh` #18 的 `exit 0` 静默判绿、fail-open、本波未修。
- **回源码核验**：`arch-guard.sh:45-60` 的 `check()` 契约是「**stdout 非空即判红**」—— `out=$(...); rc=$?` → `rc>=2` 判红 **或** `[ -n "$out" ]` 判红。原实现 `echo "$f 不存在…"; exit 0` 的 **stdout 非空** ⇒ 命中 `[ -n "$out" ]` ⇒ **本来就判红**。⇒「fail-open / 假绿」的结论**不成立**。
- **真正补的缺口**：#18 的「**文件缺失面此前无任何 selftest case**」这个**自测盲区**（不是判绿逻辑错）。新增 case17/17b：`mv` 改名制造缺失 → `assert_red`；并断言红来自**真命中**（含「ChatViewModel.kt 不存在…」行）而非「守卫命令自身执行失败」。自测网 PASS **30 → 32**。
- **纪律**：`exit 0` ≠ fail-open。判红契约在 `check()` 的**判据**里，不在子命令退出码里 —— 读守卫必须回读 `check()` 本体。

### 🟡 3. 设计 3 处被实现席更正（设计 ≠ 事实）

| # | 设计原方案 | 实现席更正 | 判定 |
|---|---|---|---|
| 1 | 迁移日志落点放在 `applyTo`（`ModelHeuristics`） | 改到 **`ModelRepository.refresh()`**（`applyTo` **之前**对读盘原值计数） | 更优：避免 `probe` / `import` 每次调用刷屏；且新导入经 `applyTo` 必非 null、不入 `known` ⇒ 不误记 |
| 2 | 行数预估 `ChatViewModel ≈700–750` / `Coordinator ≈900–950` | 实测 **`ChatViewModel` 547** / `ChatRunCoordinator` **1152** | 预估偏差（Coordinator 含类头/import/KDoc 更多）；结论不变（余量 ≫ 阈值） |
| 3 | F2 电池熔断只有 1 个调用点（`heatBlockReason`） | 实际**有 2 个调用点**（+ `asGate.beforeRound`） | 实现席补齐第二处（`4868201`），否则 `beforeRound` 仍双读 |

### 🔴 4. N1 根因收窄（比报告更精确）

- **原描述**：「引擎 thought 通道**未识别** `<think>` 格式」。
- **收窄后**：不是「未识别」，而是**配置的 `channels` 覆盖了容器元数据的 `<think>` 声明**。证据链（一手核验）：
  - `litertlm171_Engine.kt:135-155`：`ConversationConfig.channels` 非空即**覆盖**容器元数据通道（overwrite 语义）。
  - `conversation.cc:189-200`：`if (overwrite_channels.has_value()) channels = *overwrite_channels; else if (metadata.has_value()) …` ⇒ **配置非空 ⇒ 元数据通道被整体丢弃**。
  - `RM_MiniCPM5-2B.md:106/163`：MiniCPM5 的两个 bundle **本来就声明了** `<think>` / `</think>` 通道 —— 若 `channels=null` 会被 native 自动使用。
  - `LiteRtLmEngine.kt:85-88`：本仓 `THOUGHT_CHANNEL_DEFS` **只有 Gemma-4 标记**（`<|channel>thought`），且 **5 处构造点无条件下发** ⇒ MiniCPM5 永不切分。
- **关键陷阱（不能简单 append 第二 def）**：native 的 thinking 预算**只用 `channels.front()`** 的 start/end token ids（`conversation.cc:371-392`，含上游 `TODO(b/521921341)`）。**append 第二个 def 会让 W47 的 thinking 预算对 MiniCPM5 静默失效**（front() 仍是 Gemma 标记、永不匹配 `<think>`）—— 表现为「思考偶尔超长 ⇒ 撞墙钟熔断」**复发且无任何报错**。
- **本波修法**：`THOUGHT_CHANNEL_DEFS` 常量 → 纯函数 `thoughtChannelDefsFor(model)`，**按模型身份选 def**（MiniCPM5 → `<think>`/`</think>`；其余 → Gemma 标记），保证 `front()` 恒为该模型自己的思考通道 ⇒ **切分与预算同时正确**。
- **1-B 同源修**：MiniCPM5 int4 模板默认思考开；本仓「关思考」走的是**「不发 `enable_thinking`」**（absent）⇒ 模型按默认（开）走 ⇒ 关闭无效。**absent ≠ off** —— 改为显式 `mapOf("enable_thinking" to false)`（`conversation.cc:241-244` 保证 extraContext 优先）。
- ⚠️ **本波唯一未离线验证的核心结论 = N1 / 1-B 的真机行为**（见 §三、§四）。

### 🔧 5. 本波起验证策略变更

- 真机验收告一段落 ⇒ **默认不跑本地构建 / adb**，**以推 CI 为第一验证通道**（本地闸门（编译/单测/lint/守卫）按需在关键 commit 前跑）。
- 影响：`_ci-tools/` 下**无 W48 的 compile/gate 日志**，只有 `_w48_ut.log`（见 §四）。审查席据此**未能实锤**新增单测的编译/运行（源码推理判定「应通过」）—— 闸门（task #5）务必跑 `:core-model:test` / `:core-engine:test` / `:core-data:test` 并留档。

### 🧱 环境坑（沿用旧判据）

- Windows 符号链接用例 `SandboxFileScannerTest > 符号链接指向沙箱外的目录被拒绝并剔除`（`:184`）**为基线既有失败**（Windows 语义差异，CI 为 Linux 不受影响）—— 用 `git worktree` 检出基线双证，**非本波回归**。
- 全量单测必须 `--continue`（见 §二.1）。

---

## 三、挂账台账（W49+）

> 每条**必须写明「重启前提」**（对齐 `SegmentedHistoryStore` 类头的三前提范式）。

| 优先 | 项 | 重启前提 |
|---|---|---|
| 🔴 **P1** | **N1 / 1-B 真机回归未做** | 1-B（显式 `enable_thinking=false`）**影响所有 thinking 模型的关思考路径**，须**逐模型**真机验「关思考 → 无思考区 → 直答」；并验 MiniCPM5 正文不含 `<think>`、思考进 `thinking` 字段、`tok/s` 回落；回归 Gemma-4（CPU/GPU）思考仍正确进 `thinking`（不被 `<think>` 规则误伤）。**重启前提**：拿到真机（USB adb）+ 上述模型容器 |
| 🔴 **P1** | **能力位迁移真机效果** | 验「模型页关能力位 → 退出重进（触发 `refresh()`）→ 仍为关」；关 audio 后重开会话**不再付 AUDIO 降级重建**；旧安装首刷虚高归 false（Qwen2.5 image/audio、MiniCPM-V-4 thinking/toolCalling）。**重启前提**：真机 + 上述 5 个容器（`_w45_models.json` 有基线） |
| 🟡 P2 | **数据回填（W46 丢失回复）** | 丢失的 MODEL 回复仍在 journal；口径见 `_plans/wave46-persist-fix-design.md` §3.2 —— **按 USER 交错插入，不能 append**。**独立波次**（用户已裁决本波不做）。**重启前提**：先裁定回填的触发时机（升级迁移 / 手动按钮）与去重判据 |
| 🟡 P2 | **阈值回填（健康自检判据）** | W44 真机数据显示坏容器「预期 BAD 实为 DEGRADED」（仅 `channel_marker[SOFT]`，无 `<unusedNNNN>`）⇒ 判据 A 未命中。需真机采集更多坏容器样本后**回填/校准**判据阈值。**重启前提**：拿到 ≥2 个坏容器样本 + 裁定「DEGRADED 是否足以阻断推荐」 |
| 🟡 P2 | **Gemma-4 GPU 变体输出退化** | `gemma-4-E2B-it-gpu` 在 0.17.1 下退化（n-gram 死锁 / 保留未训练 token）+ 容器缺 AUDIO/VISION section（加载触发 2 次重建）。**重启前提**：① 上游 LiteRT-LM 发布含该变体支持的新版本后 bump 并重测；或 ② 改用上游单文件双后端容器；或 ③ 上游确认仅适配更高 runtime → 从预设下架。恢复前 preset 维持 `recommended=false` |
| 🟡 P2 | **`docs/10-device-acceptance.md` §11 剩余项** | 已回收 12 / 剩 ≈20（逐条见 §11.0.1）。**重启前提**：真机 + debug 包 + 设备端 `logcat -f` 落盘（PC 侧环形缓冲仅 256 KiB）。**注**：§11.10 R1 原生工具通道三层判据是**唯一无法离线验证项** |
| ⚪ P3 | **法务 `TODO(legal)` ×4 / `termsVersion` / 0 tags** | `termsVersion` **必须先于任何法务文本替换落地**（反序则老用户同意状态不可区分、不可补征）；行为实现需先裁定产品/法务问题（老用户 `true` 算「已同意第 1 版」还是「未同意任何版本」）。**重启前提**：产品/法务先出决策 |
| ⚪ P3 | **i18n / RTL**、**J4 覆盖层 VM 直构造** | 沿 README「挂账台账」旧账，各条重启前提见该节 |

**本波清掉的挂账**（W48 已解）：`ChatViewModel` 1593/1600 余量 7（→ 547，P1-1）；`Cancelled` thinking 快照上提（P3-3）；能力位虚高（P2）；N1（P1）。

---

## 四、下次接手指南

1. **CI 结果（首要）**：本波 push 后回填 Build / Release 两条 **run id**（见 §一占位）。按 lint 门禁纪律核对（判真通过须下 `lint-reports-<sha>`）。本波**尚未 push**。
2. **单测留档**：`_ci-tools/_w48_ut.log` —— 这是**改 `--continue` 之前**的 fail-fast 记录（`core-data` 71 tests / 1 failed 后中止，`feature-chat` 未跑）。**闸门务必用 `--continue` 重跑并留新档**，确认 `HistoryWithProcessTest` flaky 已消。
3. **计划件位置**：`_plans/wave48-design.md`（设计，708 行）、`_plans/wave48-review.md`（独立审查）。注意 **`_plans/` 不在 git 仓库内**，位于仓库兄弟目录 `…/2026-09-18-18-27-37/_plans/`（wave44~47 同处）。
4. **本波唯一未离线验证的核心结论 = N1 / 1-B 的真机行为**。代码侧已根修（`thoughtChannelDefsFor` + 显式 `enable_thinking=false`），离线单测只钉住「`front()` 与模型身份一致」，**通道是否真被 native 切分、预算是否 engage、1-B 是否关得住思考，全部只能真机证伪**。接手第一件事：按 §三 P1 两项跑真机。
5. **专家团 SOP**：`cam-p-wave48` 团队与成员留存；知识库 `references/cam-p-knowledge-base.md` 实为**另一 App**（`com.rb.cybermonitorpro`）的机构记忆，与本仓（LiquidAgent `com.rickeal.agent`）**不同项目**，仅两条通用纪律可引（见 `wave48-design.md` §0.2）。
6. **取证脚本复用**：`_ci-tools/_w46_conv_verify.py`（会话扫描）、`_w47_settled_scan.py`（journal 终态）、`_ci-tools/localbuild.sh`（本地构建封装）。
