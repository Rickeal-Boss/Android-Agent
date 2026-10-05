# Wave 49 收官交接 —— 真机回归 + R-A 通道数据驱动 + 加固批 + 本波复盘 + 挂账台账

> 2026-10-05 收官。本文是 W49 的**唯一权威交接**。
> 逐波细节（工作区交付物，**不在 git 仓库内**）：`_plans/wave49-design.md`（方案）、`_plans/wave49-device-verification.md`（真机回归报告）、`_plans/wave49-rA-design.md`（R-A 设计）、`_plans/wave49-rA-reverify-protocol.md`（复验协议）、`_plans/wave49-rA-reverify.md`（复验结果）、`_plans/wave49-doc-notes.md`（收尾工作稿）、`_plans/wave49-ci.md`（CI 准备）、`_plans/wave49-fix-notes.md`（逐件实现说明）。
> 仓库：`D:/WBfil/2026-09-18-18-27-37/Android-Agent`，分支 **`improve`**，基线 **`fe6044f`**（W48 tip）。

---

## 一、本波总账

**分支口径**：`improve` 仍是本仓主开发分支（W48 起）。

**本波共 10 个 commit**（`fe6044f..c795db7`）：

| # | commit | 一句话 |
|---|---|---|
| 1 | `21cd464` | **docs(token-ledger)：D-1** —— 修正 Token 账本「引擎回报」措辞（本仓第 18 处「描述不成立」） |
| 2 | `b7704d1` | **fix(scripts)：D4** —— `arch-guard-selftest` 裸 `python` 改探测式（`python3` 优先） |
| 3 | `6e57536` | **docs(agent)：注释面统一** —— 清掉同类「引擎回报」错误措辞（KDoc/注释面） |
| 4 | `8400e84` | **fix(chat)：R-E** —— salvage 落库前加**内容级 HARD 闸门**，防退化输出进用户历史 |
| 5 | `070389d` | **fix(engine)：E1** —— 探针**判据分通道** + 采样对齐模型档案（**修正外部审查的因果描述**） |
| 6 | `42865fa` | **fix(engine)：R-A** —— 思考通道 def **数据驱动化**，默认 `null` 信任容器元数据 |
| 7 | `6790cdd` | **ci(scripts)：R-B** —— 新增 `scripts/fulltest.sh`（`--continue` 纪律载体）+ 守卫 + 自测网 |
| 8 | `912ee45` | **ci(scripts)：R-C** —— arch-guard 新增 `ChatRunCoordinator` 行数守卫（feature-chat 两文件行数表） |
| 9 | `d306c5a` | **ci(scripts)：R-D** —— 冻结 `CapabilitySource.USER` 章印**唯一写入路径** |
| 10 | `c795db7` | **fix(chat)：R-E 补** —— 诊断日志钉住熔断前提，措辞与事实一致 |

> **CI run id 占位**：Build = `<待填>`、Release = `<待填>`（push 后回填；两条 workflow 因本波含非文档改动而**自动触发**，无需 `workflow_dispatch`）。

**核心交付（三项）**：

1. **W48 核心修法的真机回归**（本波唯一未离线验证项的闭环）：N1 ✅ / 1-B ⚠️ 部分（**非 W48 引入**）/ 能力位迁移+持久+门控 ✅。
2. **R-A 通道 def 数据驱动化**：消除「else → Gemma def」这个 **N1 机制本体**，为**预设扩容**清路（外部审查列为「次序级最高优先」）。
3. **加固批 7 件**：D-1 / D-4 / E1 / R-E / R-B / R-C / R-D ⇒ **arch-guard 18 → 21 项、selftest 32 → 44**。

---

## 二、本波坑复盘（重要）

### 🔴 1. 「判据」被写进报告却没人执行 —— 1-B 的「无标记推理泄漏」缺口

- **设计阶段就预埋了这条判据**：1-B 的判据是「`thinking` 空 **且** `text` 非空、不含 `<think>`（**直答**）」，并明确要求**对 OFF 用例目检 `text[0:70]`**，出现推理腔（「让我」「首先」「Let me」）⇒ **判 ❌ 不通过**。
- **实现/报告阶段漏执行**：报告把 `W49-1B-MiniCPM5` 记为 **✅ 通过**，但同节又自认「关思考后 text 不是直答，而是把元规划文本吐进正文」—— **自相矛盾**。
- **审查席独立回设备读 journal 原文**才把它实证出来（`text[0:70]` =「这是对当前任务完成情况的一个判断。现在，先检查是否已经有相关的长期记忆可以提供帮助。通过查询长期」）。
- **判据沉淀**：**「判据写进方案」≠「判据被执行」** —— 判据一旦要求「目检」，就必须在报告里**逐条抄出被目检的原文**（不是写「已目检」）。本波全波口径因此从「8 项通过」更正为「**7 项通过 + 1 项部分通过 + 2 项上游限制**」。

### 🔴 2. 外部审查的因果描述不成立 —— E1 的机制被实现席推翻

- **报告原说法**：「thinking 混进 sample ⇒ 思考烧光 `PROBE_MAX_TOKENS=96` ⇒ 判据 C（空输出）命中 ⇒ 健康模型误判 BAD」。
- **回源码核验（不成立）**：`isEffectivelyEmpty(sample)` = `sample.none { isLetterOrDigit() }` ⇒ **只要 thinkingDelta 非空，C 恒不命中** —— 混装的真实效果是 **C 被掩盖（判据失效）**，方向与报告说的**相反**。
- **真正的误报源**：**重复类判据被思考污染**（B1/B2/detector 的输入含思考文本，思考的结构化重复可命中 HARD）。
- **真正的「思考烧光输出」机制**：`config.copy(maxTokens = PROBE_MAX_TOKENS)` 后**缺 `.coerce()`**，打破了 `InferenceConfig` 的**成文不变量** `thinkingTokenBudget < maxTokens`（用户设 2048 + 探针钉 96 ⇒ 思考预算 95/96 ⇒ 正文 ≈ 空）。
- **判据沉淀**：外部审查的**机制描述**同样只是线索；`ModelSamplingProfiles.appliedTo` 会把 `maxTokens` 抬到 `minimalMaxTokens`，所以「先 `appliedTo` → 再 `.copy(96)` → **最后 `.coerce()`**」三步缺一不可。

### 🔴 3. `--continue` 只解决了「模块被中止」，**没解决「打印口径」**

- W48 的教训是「必须带 `--continue`，否则 fail-fast 会中止后续模块」。
- **本波实证**：`scripts/fulltest.sh` **带着 `--continue` 跑**，gradle 的汇总行**仍只报 `71 tests completed, 1 failed`** —— 因为它只覆盖**失败的那个模块**（`core-data`），而全量 9 模块的真实用例数是 **598**（汇总 53 个 `TEST-*.xml`）。
- ⇒ **`--continue` 解决「中止」，`TEST-*.xml` 汇总才解决「口径」**。二者都要。
- 判据沉淀：**任何「N tests completed」形式的数字都不可信**，必须汇总 `build/test-results/**/TEST-*.xml`。

### 🔴 4. 新守卫的假绿被**自己的自测网**抓住

- R-B 的第 19 条守卫首版判据是裸 `grep -F --continue` —— 被 **case19 fixture 注释里的「--continue」** 命中 ⇒ **假绿**。
- **selftest 首轮 `PASS=42 FAIL=2` 把它暴露**；改判据为 `^[^#]*--continue`（只认行内第一个 `#` 之前的**代码位**），且 fixture **故意保留注释提及以长期钉住该行为**。
- ⇒ 这条再次证明本仓铁律「**每新增守卫必须同步新增 selftest case**」的价值。

### 🟡 5. 守卫判据要按**赋值形态**而非裸符号

- R-D 若用裸 `CapabilitySource.USER` 会**误红**合法读取/比较（`ModelHeuristics.kt:219` 的 `== CapabilitySource.USER`）与 KDoc（`ModelDescriptor.kt:72-73`）。
- 正解：用**赋值形态** `capabilitiesSource = CapabilitySource.USER` + 显式排除 `src/test/`。

### 🟡 6. 验证的产物 vs 推送的产物 —— 要主动核对差异范围

- R-A 真机复验期间 tip 从 `42865fa` 前进到 `d306c5a`（CI 席的 `scripts/*` + `ModelRepository.kt` KDoc）⇒ 实现席**主动标注**「这两 commit **未触 R-A 文件** ⇒ R-A 代码在两 commit 间完全一致、结论不受影响」。这是「验证==推送」该有的自觉。

### 🟡 7. 审查期间队友正在改文件 ⇒ 会撞出**假 FAIL**

- 审查席首跑 `arch-guard-selftest.sh` 在 line 215 报 `syntax error` —— 复核 mtime 后判定为 **CI 席正在编辑该文件造成的读写竞态**，**非缺陷**（最终文件 `bash -n` rc=0、完整跑通 `PASS=44`）。
- 判据沉淀：**并发编辑期的失败要先核 mtime / 重跑一次再定性**，别急着报缺陷。

### 🧱 环境坑（沿用旧判据 + 本波新增）

- **沙箱会在命令间杀掉 adb daemon** ⇒ 每条 adb 命令加重试 3~4 次，`device not found` 不是设备掉线。
- **`adb shell input text` 对非 ASCII 在设备侧 NPE** ⇒ 中文输入**不可行**（设备无 `cmd clipboard`、App 无 `ACTION_SEND`）；用 **ASCII prompt + 同题 ON/OFF A/B**。
- **`logcat -s <TAG>` 会过滤掉 native 日志** ⇒ 涉 native 判据（如 `Ignoring thinking budget constraint`）必须落**不过滤**全量档，且**先验它确含 native 行**才可说「0 命中」有意义。
- **`ModelCard` 是 `heightIn(420dp) + verticalScroll`** ⇒ 按钮需在**卡内**滑 3~5 次；`uiautomator dump` 找不到 **≠** 不存在。
- **`files/conversations/<cid>.json` 的 `config.thinking` 恒为创建时默认**，不反映 run 实际配置 ⇒ 判 run 配置必须读 settings pb 或 logcat。
- **`SandboxFileScannerTest` 的 Windows 符号链接用例（`:184`）是基线既有失败**（Windows 语义差异，CI 为 Linux 不受影响），非回归。
- **`balance_check.py` 是 Kotlin 括号配平器** ⇒ 对 `README.md` 报 FAIL 是**既有假阳性**（`git show HEAD:README.md` 可对照），只传 `.kt` 文件即可。
- **`_plans/` 在 git 仓库外** ⇒ 所有计划/报告/协议都是**工作区交付物**，**关键结论必须折进仓库文档**才算落档。

---

## 三、挂账台账（W50+）

> 每条**必须写明「重启前提」**（对齐 `SegmentedHistoryStore` 类头的三前提范式）。

| 优先 | 项 | 重启前提 |
|---|---|---|
| 🔴 **P1** | **§11 积压回收顺位 1–8 全清单未执行** | 本波单设备窗口时间全部用于把 N1 / 1-B / 能力位 / R-A 四组核心用例做扎实。**重启前提**：真机 + debug 包 + 设备端**不过滤** `logcat -f` 落盘；按 `_plans/wave49-design.md` §三顺位 1–8 执行。⚠️ 顺位 1（§11.10 R1 原生工具通道三层判据）是**唯一无法离线验证项** |
| 🔴 **P1** | **R-A 的「预算静默失效」只在 MiniCPM5 上验过** | R-A 走 `null` 后 `front()` 来自**元数据排序**，若某模型元数据未把思考通道排第一，W47 预算会**静默失效**（思考超长 ⇒ 撞墙钟熔断、**零报错**）。本波只验了 MiniCPM5（通过）。**重启前提**：预设扩容时，**每个新增 thinking 模型**都要跑一遍复验协议（判据：logcat 无 `Ignoring thinking budget constraint` + `settled=ModelStopped` + 负向对照） |
| 🟡 P2 | **`DeepSeek-R1-Distill-Qwen-1.5B` 被启发式误判 `thinking=false`** | `inferFamily`（`ModelHeuristics.kt:72,78`）按文件名判家族，含 `qwen` 不含 `qwen3` ⇒ 落 `OTHER`；而 W48 让 `enable_thinking` **恒发** ⇒ 可能关掉它的推理。**重启前提**：① 拿到该容器（或任何 `deepseek-r1*`/`qwen3*` 推理件）；② 真机验 AUTO 模式是否仍推理；③ 若确被关，扩 `inferFamily` 识别推理模型（含单测）——**不得在无容器时盲改** |
| 🟡 P2 | **数据回填（W46 丢失回复）** | 丢失的 MODEL 回复仍在 journal。口径见 `_plans/wave46-persist-fix-design.md` §3.2 —— **按 USER 交错插入，不能 append**。**重启前提**：先裁定触发时机（升级迁移 / 手动按钮）与去重判据。⚠️ 回填文本建议**顺手跑 R-E 的健康判据**（旧 journal 里的退化输出别原样搬回历史） |
| 🟡 P2 | **自检阈值回填** | W44 真机数据显示坏容器「预期 BAD 实为 DEGRADED」。**重启前提**：拿到 ≥2 个坏容器样本 + 裁定「DEGRADED 是否足以阻断推荐」。⚠️ **E1 已修**（判据分通道 + 采样对齐档案）⇒ 本项**现在才具备**采集干净判据分布的前提 |
| 🟡 P2 | **`gemma-4-E2B-it-gpu` 上游错配** | GPU 后端可加载但**输出乱码**；CPU 后端 engine init 即 `NOT_FOUND: TF_LITE_PREFILL_DECODE`。**重启前提**：① 上游发布含该变体支持的新版本后 bump 并重测；或 ② 换上游单文件双后端容器；或 ③ 从预设下架。恢复前 preset 维持 `recommended=false` |
| 🟡 P2 | **`MiniCPM-V-4-int8` 无法建会话** | `createConversation` 即 `INVALID_ARGUMENT: Unsupported model type`（角色通道播种与 legacy 回退**双双失败**）⇒ 本 App chat 路径下无观测面。W45 已存在同款失败。**重启前提**：确认是容器/runtime 错配还是需要专门适配；若不可解 ⇒ 从预设下架或标注「仅用于其他 runtime」 |
| 🟡 P2 | **`fulltest.sh` 未接进 CI 工作流** | `build.yml` 的 unit-tests job 仍直接跑 `gradle … test --continue`，打印的仍是 gradle 的残缺口径（`71` vs 真实 `598`）。**重启前提**：裁定是否把 `fulltest.sh` 接进 unit-tests job（让 CI 也走 `TEST-*.xml` 真实用例数口径）；接入后需现场确认 `files==0 ⇒ 失败` 的防假绿分支 |
| 🟡 P2 | **「引擎回报」措辞的 UI 标签面未统一（B-7）** | D-1 已清注释/KDoc 面；`ChatContextMeter.kt:38/42/92`、`ChatRunCoordinator.kt:450/630/771`、`ChatViewModel.kt:162/165`、`RunTokenLedgerTest.kt:84/107` 仍是旧措辞。**重启前提**：裁定 UI 标签是否改为「本仓自算」口径（或保留功能等价措辞 + 加注释）——改措辞 = **展示行为变更**，需单独评审 |
| 🟡 P2 | **MiniCPM5「OFF 态规划外溢」** | 关思考后正文为元规划/记忆检查文本、**不直答**。已定性为 **MiniCPM5-2B int4 固有能力限制**（W47 `bbd8db82` 同款，**非 W48 引入**）。**重启前提**：① 产品裁定「接受 vs 加缓解（OFF 态输出未过健康判据时提示/回退 ON）」；② 若走**提示词面**（评估 `MEMORY_MAINTENANCE` 段是否被 2B 模型当待办复述）需先做对照实验。**不要**动 `thoughtChannelDefsFor` / 1-B 通道逻辑 |
| ⚪ P3 | **`docs/10-device-acceptance.md` §11 剩余项** | 已回收 18 / 剩 ≈14（逐条见 §11.0.1）。**重启前提**：真机 + debug 包 + 不过滤 `logcat -f` 落盘 |
| ⚪ P3 | **法务 `TODO(legal)` ×4 / `termsVersion` / 0 tags** | `termsVersion` **必须先于任何法务文本替换落地**（反序则老用户同意状态不可区分、不可补征）。**0 tags / 0 releases**：外部审查建议趁 `improve` 成为主开发分支之际打首个 tag（`1a0b45a` 冻结点 / `fe6044f` / `c795db7` 是自然锚点）——**涉及发布决策（版本号口径 / 是否公开 Release），本波已上报用户、未自行决定** |
| ⚪ P3 | **`ModelsScreen.kt:472` 用已废弃 `Icons.Filled.OpenInNew`** | 预存在（最后改动 `eb55330` / W43），**非 CI 阻塞**（lint baseline 冻结 4 条不含它）。修法 = 换 `Icons.AutoMirrored.Filled.OpenInNew` |
| ⚪ P3 | **i18n / RTL**、**J4 覆盖层 VM 直构造** | 沿 README「挂账台账」旧账，各条重启前提见该节 |

**本波清掉的挂账**：N1 真机回归（W49 已验证）、能力位迁移效果（W49 已验证）、`ChatRunCoordinator` 无守卫（R-C）、能力位 `USER` 章印无守卫（R-D）、`--continue` 纪律载体在仓库外（R-B）、selftest 裸 `python`（D-4）、探针采样口径（E1）、salvage 窄路径（R-E）、Token 账本注释面措辞（D-1）。

---

## 四、下次接手指南

1. **CI 结果（首要）**：本波 push 后回填 Build / Release 两条 **run id**（见 §一占位）。按 lint 门禁纪律核对（判真通过须下 `lint-reports-<sha>` artifact，冻结值 4）。推送方式沿用旧纪律（PAT 走 URL 一次性 + 用完 `git remote set-url` 清除）。
2. **推送前预检已建立**：`scripts/fulltest.sh`（全量单测，真实用例数口径）+ `:app:lintDebug`。本波实测基线：**`tests=598 failures=1`（唯一失败 = Windows 符号链接基线）**、**lint 新增 = 0**。
3. **计划件位置**：全部在仓库兄弟目录 `…/2026-09-18-18-27-37/_plans/`（**不在 git 内**）：`wave49-design.md`（方案）、`wave49-device-verification.md`（真机回归）、`wave49-rA-design.md`（R-A 设计）、`wave49-rA-reverify-protocol.md` + `wave49-rA-reverify.md`（复验协议与结果）、`wave49-doc-notes.md`（收尾工作稿）。
4. **本波已闭环的核心结论**：N1（真机✅）、1-B（⚠️ 部分，已定性非回归）、能力位三项（真机✅）、R-A（真机✅ 无回退）。**唯一仍未验的是 §11 顺位 1–8 全清单**（P1）。
5. **R-A 的隐式不变量（最重要的一条交接）**：`null` 分支下 **`front()` 来自容器元数据的通道排序** —— 预设扩容时**每个新增 thinking 模型**都必须跑复验协议，判据是「logcat 无 `Ignoring thinking budget constraint`」+「`settled=ModelStopped`」+「低难度负向对照也不撞熔断」。
6. **取证脚本复用**：`_ci-tools/_w49_ui.py`（Compose 语义树驱动，含 `find --scroll=` 卡内滚动）、`_w49_conv_deep.py`（会话 JSON 深扫 + N1 自动判定）、`_w49_n1_verify.py`、`_w49_cap_probe.py`、`_w49_read_prefs.py`（读 settings pb）、`_w49_rotate_pat.py`（换 PAT）、`_ci-tools/localbuild.sh`（本地构建封装）。
7. **换 PAT 只需换 3 个文件**（`_ci-tools/ghapi.sh` / `err.sh` / `write_secrets.py`）；其余脚本运行时从 `ghapi.sh` grep 取。**本波已轮换过一次**（新值已写入这 3 个文件，`GET /user` → `Rickeal-Boss` 验证通过）。
8. **专家团 SOP**：`cam-p-wave49` 团队；知识库 `references/cam-p-knowledge-base.md` 实为**另一 App**（`com.rb.cybermonitorpro`）的机构记忆，与本仓（LiquidAgent `com.rickeal.agent`）**不同项目**，不可混用。
