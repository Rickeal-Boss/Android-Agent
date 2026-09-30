# Wave 39 交接：验收取证通道 —— logcat 出口 + 诊断页复制全部 + 验收清单合并 + 守卫 6→9 模块

> 写于 2026-09-30 晚。基线 `436e6f0`（Wave 38 交接 docs）→ 本波 tip **`89b61db`**（1 个 commit，15 文件）。
> **CI 双绿**：Build [36721485565](https://github.com/Rickeal-Boss/Android-Agent/actions/runs/36721485565)（Lint (baseline gate) / Unit tests / Assemble Debug (JDK 21) 三 job 全 success）+ Release [36721485755](https://github.com/Rickeal-Boss/Android-Agent/actions/runs/36721485755)（success）。
> **产物**：release APK **70.19 MB** + debug APK **39.32 MB** + R8 mapping **4.33 MB**。
> **lint 实证（不看 job 颜色）**：`verify_lint.py 36721485565` ⇒ 报告里**只有 `LintBaseline` 一个 Hint** ⇒ **真实新问题 = 0**；基线吸收 3 errors + 1 hint = **4**（与冻结值一致）；**无 `LintBaselineFixed`**（无失配）；**无 `LogConditional`**（本波最大预判红点被证伪）。
> 团队 SOP：主理人侦察（**2 条记忆失实**）→ 沈思远方案（**7 条证伪**）→ 柯码成 ×4 文件面互斥并行 → 崔续程 CI 通道核验 → 主理人集成 + 补完面 C（**子 Agent 全程频繁撞 429 配额，面 C 与多轮对话中断后由主理人接手**）→ 本地闸门 → push → CI 双绿 → artifact 验证。

---

## 1. 本波交付

| ID | 内容 | 性质 | 文件 |
|---|---|---|---|
| **R1-甲** | **logcat 取证出口**：`AgentLogFileStore.install()` 的**既有 sink lambda 内并入** `Log.d(TAG, "[${level.name}] $message")`，TAG = `LiquidAgentDiag`，**全级别**（INFO/WARN/ERROR）；落盘仍维持**只收 ERROR** | **真实缺陷**（验收取证通道断裂） | `core-data/.../AgentLogFileStore.kt` |
| **R1-乙** | 诊断页**「复制全部」**：两区（磁盘/内存）当前可见内容拼纯文本进剪贴板，**成功与失败都有 Toast**（含条数） | 用户可见 | `feature-settings/.../DiagnosticsScreen.kt` |
| **R2/R3** | 验收清单合并：新增「**第 0 步 先升级再复测（防追鬼影）**」+ **§11 跨波次积压（Wave 33–38）** 8 张主题分表（操作/预期/关键字/来源波次）+ §11.0 取证前提 | 治理 | `docs/10-device-acceptance.md` |
| **G1/H3** | 模块存在性清单 **6 → 9**（补 `feature-chat`/`feature-models`/`feature-settings`）+ 新增 `case13`/`case13b` + `scaffold()` 同步铺 9 个模块 | 守卫（僵尸规则） | `scripts/arch-guard.sh`、`arch-guard-selftest.sh` |
| **D-3** | `Unreadable` 用例追加「**IO 失败不落盘**」断言（恢复读权限后逐字节比对原文件） | 测试补强 | `core-agent/src/test/.../MemoryWriteResultTest.kt` |
| **#23** | `RunTokenLedger` 类头红线更新：错配已按「第三条路」接线解决，方案①②作废；并写明**结论前提**（消费方只读 `sentTokens`，读累计口径则错配复活） | KDoc 真实性 | `core-agent/.../token/RunTokenLedger.kt` |
| **#24** | `EngineContract` + `LiteRtLmEngine`：preface 诊断「丢失/错位」**收窄为「丢失」**（判据是归一化**子串** `contains`，对位置/role 段不敏感） | KDoc 口径 | `core-engine/.../EngineContract.kt`、`.../local/LiteRtLmEngine.kt` |
| **N1/G4** | 沙箱 `openSandboxFile` / `onPreview` 补**信任边界完整前提链**；申报全仓唯一 `ACTION_VIEW` 出口的「**出应用**」边界（三选一留待用户裁决，本波不实现） | 债 / 安全申报 | `feature-settings/.../tools/SandboxFilesScreen.kt`、`.../SandboxFilesViewModel.kt` |
| **D-6** | `IS_TOS_ACCEPTED` 上方固化**时序风险**（无版本 boolean ⇒ 换法务文本当天老用户被视为已同意；`termsVersion` 必须先于文本替换落地）。**只记录不修** | 债（时序） | `core-data/.../SettingsRepository.kt` |
| **THIN 0.21 销账** | 核实「与 KDoc ≤0.15 冲突」的**前提已不存在** ⇒ 直接销账；`docs/01-architecture.md` 陈旧副本加历史快照声明 | 销账 | `core-design/.../GlassMaterial.kt`、`docs/01-architecture.md` |
| **N4** | README 路线图 + 挂账台账同步实况（新增 `termsVersion`；销账 THIN 0.21 / 三项「挂账蒸发」/ G1③） | 文档真实性 | `README.md` |

**改动量**：15 文件，+约 600 行（含大量 KDoc）。**无新增文件、无删除文件、无依赖变更、无 `build.gradle.kts` 改动、无 `lint-baseline.xml` 改动。**

---

## 2. 关键设计决策

### 2.1 R1-甲 的真实卡点（**外部报告完全没提**）：`setSink` 是单槽覆盖式
v9 报告把 R1-甲描述为「debug 构建给 `AgentLogStore` 挂一个 `Log.d` 出口（~5 行）」。回源码核实后有**三条硬约束**使字面实现不可行：

1. **`AgentLogStore` 在 `:core-model`**，而 `arch-guard.sh` 第 3 项强制「`core-model` 无 `android.`/`androidx.` 依赖」（匹配**所有出现**而非只 import）⇒ `android.util.Log` 一旦进 `core-model` **本地 arch-guard 立刻红**。出口只能落在 `:core-data` 或 `:app`。
2. **`AgentLogStore.setSink` 是单槽覆盖式**：内部只有一个 `@Volatile private var sink: AgentLogSink?`，后一次调用**整个顶掉**前一次。而现役唯一装配点就是 `AgentLogFileStore.install()`。⇒ 若在别处再调 `setSink`，会把 **W38 刚建的 ERROR 落盘静默顶掉**（回退级事故）。
3. **全仓零 `BuildConfig`**（`app/build.gradle.kts` 的 `buildFeatures` 只有 `compose = true`）⇒ 不存在 `BuildConfig.DEBUG`。

**裁决**：**并入既有 lambda**（不是新增 sink），级别**全转**（不能只转 info，见 2.2）。

### 2.2 为什么必须**全级别**转发（证伪「6 组关键字全是 info 级」）
外部报告称 6 组验收关键字「全是 `AgentLogStore.info` 级」。回源码逐条核实后实为 **3 info / 4 warn**，无一 error：

| 关键字 | 级别 | 位置（Wave 39 时） |
|---|---|---|
| `preface 渲染诊断` | INFO | `LiteRtLmEngine.kt:818` |
| `会话重建原因` | INFO | `LiteRtLmEngine.kt:696/705` |
| `preface 校验失败` | **WARN** | `LiteRtLmEngine.kt:833` |
| `角色通道播种失败` | **WARN** | `LiteRtLmEngine.kt:903` |
| `GPU 后端不可用` | **WARN** | `LiteRtLmEngine.kt:534` |
| `原生工具通道判据不一致` | **WARN** | `AgentRunner.kt:767` |

⇒ **只转 info 会漏掉 4 组判据**。主结论（info/warn 都不落盘、adb 抓不到）不变，但取证时要抓的**以 warn 为主**。

### 2.3 release 零开销**不需要** `BuildConfig.DEBUG`
`app/build.gradle.kts:95-98` 的 release 走 `proguard-android-optimize.txt`，配合 `app/proguard-rules.pro:124-129` 的 `-assumenosideeffects class android.util.Log { … v(...); d(...); i(...); }` ⇒ **`Log.d` 连同 tag 与消息的字符串拼接会被 R8 整条删掉**。

- ⚠️ **唯一决定项是 `proguard-android-optimize.txt`（开优化）**，不是 `proguard-rules.pro` 本身 —— 后者自己的注释就写明「只在 `-optimizations` 生效时才真正去除」。引用依据时两处都要引，只引 proguard 规则会漏掉「必须开 optimize」这个前提。
- ⇒ 与既有先例 `core-design/.../Lens.kt:38/141` 完全同款。**不要**为了「release 也能抓」改成 `Log.w`：那会实打实留在发布包里（`w`/`e` 不在剥离列表内）。
- ⛔ **绝不能为拿 `BuildConfig.DEBUG` 去开 `buildFeatures { buildConfig = true }`**：全仓已有 5 处裸 `Log`（`LiquidAgentApp.kt:267/665/683` 的 `Log.w` + `Lens.kt:38/141` 的 `Log.d`）都不在 baseline，而 `LogConditional` 当前不报的**唯一合理解释是 `BuildConfig` 不存在导致该检查无法判定** —— 一旦开启，该检查会突然生效并**一次性报 5 存量 + 1 新增** ⇒ baseline 4 → 10 ⇒ arch-guard 第 14 项红。
  - ✅ **本波 CI 实证了这条推断**：lint 报告里**没有 `LogConditional`**（见 §3.2）。与 W38 §2.1「lint 能报出 `UseKtx` 本身即证明 core-ktx 可得」同构 —— **lint 的行为本身是配置的证据**。

### 2.4 R2「新建 `device-acceptance-checklist.md`」会造成**第三份**清单
`docs/10-device-acceptance.md`（492 → 613 行，用户视角，已有「操作/预期/不合格时」三列）与 `docs/06-device-verification-checklist.md`（346 行，引擎侧）**都已存在**。再建一份会让「清单分散」从 2 变 3 —— 正是 R2 想解决的问题本身。⇒ **合并进既有 §11**，不新建文件。

### 2.5 G1/H3 的严重性**被高估**，但缺口真实
外部报告称「带 `| grep -vE` 的守卫在目录缺失时静默判绿 ⇒ 最严重」。实测机制**坐实**，但**实际暴露面 ≈ 0**：

- 带 `| grep -vE` 的守卫共 **5 条**，扫描目标分别是 `core-model/`（在模块断言内）、`core-agent/`（在内）、`.` 根 ×3（不会被改名）⇒ 静态暴露面 ≈ 0。
- 真正的缺口是**第 13 条宿主源集里的 3 个 `feature-*`**：被改名时 `app/` + `core-data/` 仍在 ⇒ 赋值点照样能被 grep 到 ⇒ 守卫是**缩面**（不是判绿）。**缩面比判绿更难发现**。
- ⇒ 本波把清单补到 9 个即堵住该缺口。**没有**去改「把守卫命令改成 `bash -c 'set -o pipefail; …'`」—— 那会一次性改变现有 5 条带管道守卫的判据，需单独评估假红风险（已记入挂账）。

### 2.6 D-6 为什么只记录不修
`termsVersion` 的**行为实现**涉及一个产品/法务决策：老用户的 `boolean = true` 到底算「已同意第 1 版」（据此仅对第 2 版重新征求）还是算「未同意任何版本」（据此全量重新征求）。这不是工程侧能自行裁定的。且**法务文本替换尚未发生** ⇒ 时序风险**未到期**。⇒ 本波只把债务固化成可见注释 + README 台账，并写明**落地顺序是硬约束**。

### 2.7 THIN 0.21 直接销账（连「提取具名常量」都不必做）
核实「与 KDoc 规范 ≤0.15 冲突」的前提**已不存在**：全仓 `.kt` grep `0.15` / `≤0.` 在 `GlassMaterial.kt` **零命中**；「≤0.15」只活在 2026-09-26 的两份 handoff。现役 KDoc **已把 0.21 写成有意值**（`:36`「0.21/0.22，Wave 9 捋正」、`:44-45`「完全相同（0.21）」、`:99`「→0.09/0.21/0.22/0.36」）。⇒ 跨 6 波的挂账销账，**零改动优于零视觉风险**。

---

## 3. CI 结果与 artifact 实证

### 3.1 双绿（一轮即绿，无往返）
| workflow | run | 结论 |
|---|---|---|
| `Build` | [36721485565](https://github.com/Rickeal-Boss/Android-Agent/actions/runs/36721485565) | ✅ `Lint (baseline gate)` / `Unit tests` / `Assemble Debug (JDK 21)` **三 job 全 success** |
| `Release` | [36721485755](https://github.com/Rickeal-Boss/Android-Agent/actions/runs/36721485755) | ✅ `Build & (optionally) sign release` success |

### 3.2 lint artifact 实证（**不看 job 颜色**）
```
$ python _ci-tools/verify_lint.py 36721485565
artifact: lint-reports-89b61db1d76c0203f659d530a9f82781033d7d88 (id=11100176315)
=== 报告里的 issue ===
  Hint     LintBaseline
基线吸收: 3 errors and 1 hint were filtered out because they are listed in the baseline file
基线未匹配条目: （无 LintBaselineFixed）
=== 判定 ===
✅ 真实新问题 = 0
```
- **真实新问题 = 0**（除 `LintBaseline` 外零 id）
- 基线吸收 **3 errors + 1 hint = 4** ⇒ 与冻结值 **4** 一致，本波**未动 baseline**
- **无 `LintBaselineFixed`** ⇒ 无失配条目
- **无 `LogConditional`** ⇒ §2.3 的推断被**证实**（本波预判的「最可能的红点」被证伪）
- 本波新增的 `Log.d`（`AgentLogFileStore.kt:89`）与新增 Compose UI（诊断页「复制全部」）**都未引入新 lint issue**

### 3.3 产物
| 轨 | 产物 | 大小 |
|---|---|---|
| Release | `liquidagent-release-apk-harness-improve` | **70.19 MB** |
| Release | `liquidagent-release-mapping-harness-improve` | 4.33 MB |
| Release | `liquidagent-debug-harness-improve` | 39.32 MB |
| Build | `liquidagent-debug-89b61db1…` | 39.32 MB |
| Build | `lint-reports-89b61db1…` | 24 KB |
| Build | `lint-baseline-89b61db1…` | 749 B |

### 3.4 本地静态闸门（改动后实测）
| 闸门 | 结果 | 耗时 |
|---|---|---|
| `bash scripts/arch-guard.sh` | **exit 0**，15 项全 OK | **1m40s** |
| `bash scripts/arch-guard-selftest.sh` | **exit 0**，**PASS=23 FAIL=0** | **4m58s** |
| `python scripts/check-test-void.py -v` | **exit 0**（40 个测试源文件，零违规） | <1s |

⚠️ 本机 arch-guard / selftest 需 ≥300s / ≥600s 超时或 `run_in_background`（默认 120s 会 SIGTERM）。**CI（Ubuntu）不受影响。**

---

## 4. 本波新教训（复用价值高，按价值排序）

### 🔴 4.1 `git ls-remote` 对 public 库**会假绿** —— push 通道的真判据是 `git push --dry-run`
本波开局两个 PAT 全部失效。**`git ls-remote` 仍然成功**（public 库匿名可读）⇒ 拿它判断凭据有效性会得出「凭据正常」的错误结论。真判据是 `git push --dry-run`：凭据失效时报 `remote: Invalid username or token. Password authentication is not supported for Git operations.`。
⇒ **可复用判据**：`ls-remote` 只能证明「网络通」，不能证明「凭据有效」。

### 🔴 4.2 凭据可能**同时存在两枚**，换 PAT 必须全部脚本一起换
本波发现 `_ci-tools/ghapi.sh` 与 `_ci-tools/err.sh` + `size-audit/{audit,dl,dl2}.sh` 内嵌的是**两枚不同**的 PAT，且**同时失效**。⇒ 只换一处必漏。换完要用「提取 token 算 md5 / 比长度 / 比尾 4 位」自查一遍。

### 🔴 4.3 两条 workflow **都有**路径过滤，但形态不同 —— 「Release 照样跑」是错的（本波主理人被带偏的一次）
- `Build`（build.yml）用 **`paths-ignore` 黑名单**（`docs/**`、`**/*.md`、`.github/ISSUE_TEMPLATE/**` 等）。
- `Release`（release.yml:39-51）用 **`paths` 正向白名单**：`app/**`、`core-*/**`、`feature-*/**`、`gradle/**`、`gradle.properties`、`gradlew`、`settings.gradle.kts`、`build.gradle.kts`、`.github/workflows/**`、`scripts/**`。**没有 `docs/**` 与 `README.md`**。
- ⇒ **纯 docs/README 提交：Build 与 Release 都是 0 run**（本波 `837e4f7` 实测 `total: 0`，与本波此前 `436e6f0` 的行为一致）。
- ⚠️ **事故经过（值得记住）**：CI 专家 grep `paths-ignore` 计数为 0 ⇒ 得出「Release 无路径过滤 ⇒ docs 提交 Release 照样跑」，**只查了关键字、没看 `on:` 段全文**；方案分析师原判断「面 C 文档改动 0 run」**本来是对的**却被驳回；主理人采信了这条「修正」并写进交接文档与记忆 —— **直到本波 docs 提交实测 `total: 0` 才翻案**。
- ⇒ **可复用判据**：判断「某提交会不会触发某 workflow」，必须读 `on:` 段**全文**（`paths` 与 `paths-ignore` 是两个方向），**grep 单个关键字 ≠ 理解触发条件**；最终裁决永远是「推一个实测」。
- 附带收益：**docs-only 提交零 CI 成本** ⇒ 交接文档可以放心在 CI 绿之后单独补推（本波就是这么做的：`89b61db` 代码双绿 → `837e4f7` 交接文档 0 run）。

### 🔴 4.4 `verify_lint.py` 判红时**只给 id、不给 message** —— 与 W38 铁律直接冲突
原实现用正则只抽 `id` + `severity`，判红时**不输出 `message`**。而 W38 最贵教训正是「只读 id 会误判」（`NotShrinkingResources` 同一 id 两种形态两种 message）。⇒ 已补 `message` / `errorLine1` / `file` 输出（`_ci-tools/verify_lint.py`，仓库外）。
⇒ **可复用判据**：**任何**自动化的 lint 判读工具，输出里**必须带完整 `message`**，否则等于把 W38 的坑固化成工具行为。

### 🔴 4.5 引用某证据前先确认它**早于**该结论 —— 新形态的「证据来源缺陷」
崔续程把 dev-a **本波刚写的** `AgentLogFileStore.kt:89` 当成「存量裸 `Log`」（报「6 处」），并拿**同一份新增里**的 KDoc 注释「本仓不生成 BuildConfig」当作「一手实证」去证明该结论本身 —— 这是**循环论证**。
- 判据：用 `git diff --stat` 看该处有没有 `+`（本波该文件是 `+57 / -0` 纯新增）。
- 本仓第 **8** 次「证据来源有缺陷」，但**形态是新的**：前 7 次是**检索方式缺陷**，这次是**证据时序错位**（把刚写下的注释误当历史既存事实）。
- ⇒ 沈思远的 5 处清点是**对的**，被误加的第 6 处是本波新增。

### 4.6 多路径 `grep` 其一缺失 ⇒ rc=2，**即使有命中**
本机实测（Git Bash + GNU grep 3.0 / bash 5.3）：

| 形态 | rc |
|---|---|
| `grep -rn X nodir/` | 2 |
| `grep -rn X nodir/ \| grep -vE '…'` | **1** ← 关键 |
| `grep -rn X nodir/ exist.txt`（另一路径有命中） | **2**，且 stdout 仍有命中行 |
| 上一行再接 `\| grep -vE '…'`（右侧有命中） | **0** ← 完全静默 |
| 目录存在但为空 | 1 |

⇒ 写 `grep -rn X a/ b/ c/` 型守卫时，**任一路径缺失都会让 rc 变 2**，与「有没有命中」无关。`set -o pipefail` **两层都救不了**：① 本脚本顶部的 `set -uo pipefail` **不继承进 `bash -c` 子壳**；② pipefail 取的是「**最右的那个非零退出码**」而非最大值 —— 形态 ② 里右侧 `grep -vE` 自己也是 1 ⇒ 管道 rc 依旧是 1。

### 4.7 「解决分散」的正确动作不一定是「新建一个统一文件」
本波差点把验收清单从 2 份做成 3 份（R2 原方案是新建 `device-acceptance-checklist.md`）。⇒ **动手前先 `ls docs/`**。

### 4.8 转述失真也是「挂账失实」的一种
本波「无本地 JDK」的失实**发生在转述环节**：长期记忆原文写的是「虽有 JDK 21（`_ci-tools/jdk/jdk-21.0.12.1+1`）但跑不了 gradle」（准确），被转述成「项目一直假定本机无 JDK」。⇒ **转述记忆/挂账时，必须回原文行号**。

---

## 5. 真机验收（CI 查不出）

**本波没有新增验收项**（改动本身是取证基建）。**全部验收项已合并到 [`docs/10-device-acceptance.md`](10-device-acceptance.md) §11**（8 张主题分表，四列：操作 / 预期 / 关键字 / 来源波次），并新增：

1. **§「第 0 步」（置于 §0 之前）**：任何验收开始前，**先安装本波最新 Release APK 再复测**用户原始两场景（「只输出提示词」/「Gemma GPU 有问题」），并记录包 SHA。**防追鬼影** —— 用户反馈的症状可能来自不含修复的旧包。
2. **§11.0 取证前提**：本波起，6 组关键字**可以用 adb 抓到了**。
   - `adb logcat -s LiquidAgentDiag:V`
   - 行格式 `[LEVEL] message`；⚠️ **4 组是 WARN**，不要只过滤 INFO
   - **必须用 debug 包**（release 的 `Log.d` 被 R8 剥离）
   - 诊断页新增「**复制全部**」按钮（设置 → 诊断），带条数 Toast 反馈
   - 落盘 `last_errors.log` **只收 ERROR**，别指望在里面找 INFO/WARN

**Wave 33 的「Gemma GPU 加载引擎错误」仍是用户标记的待解决项**，观测基建已就位。

---

## 6. 挂账（Wave 40+，按优先级）

### 6.1 🔴 验收取证通道已通，**该做真机验收了**
本波修好的是「**去做也拿不到判据**」这个前置障碍。⇒ 下一步唯一真正重要的是**执行** §11 的 ≈32 条，并**逐条回收**（当前零回收）。顺序：**第 0 步（升级）→ §11.1 回显与引擎 → 其余**。

### 6.2 本波自身遗留
- **守卫管道的 `pipefail` 加固**（§2.5）：把守卫命令改成 `bash -c 'set -o pipefail; …'` 能把形态 ④ 从「静默 rc=0」救回 rc=2，但会**一次性改变现有 5 条带管道守卫的判据** ⇒ 需单独评估假红风险。
- **`Log.d` 出口的 release 面**：本波只在 debug 包可取证（release 由 R8 剥离）。若将来需要 release 现场取证，需另设计（如受设置开关控制的 `Log.i`），**不要**默认抬高级别。
- **`agentLogFileStore` 单槽装配**已加 KDoc 约束，但**没有**编译期强制（仍是文档约束）。若将来 sink 增多，可考虑改成多播（需评估测试与「纯内存模式」语义）。

### 6.3 Wave 33–38 仍在的历史挂账（逐项已定性，不再重复调研）
**工具 schema 形状真机实证（R1）**（唯一无法离线验证项，决定原生工具通道成败；三层不确定性 + **Plan B 必须真机窗口前落档**）｜原生通道「同 run 内恢复」（降级为 R1 后置项）｜`MemoryViewModel` 误报｜记忆向量检索（禁引新依赖）/ 去重 / 分区｜路径 B 列表回看优化（待真机帧时间）｜i18n / RTL｜法务 `TODO(legal)` ×4｜**`termsVersion`（时序风险，必须先于法务文本替换落地）**｜`G4 ACTION_VIEW` 三选一｜**0 tags / 0 releases**（建议随「原生通道转正」打首个 tag）｜**本地 gradle 基建波**（出网可达，最小可用集 1.2~2.0 GB / 约 1 小时；compileSdk 36 / AGP 9.3.2 / Kotlin 2.3.0 / wrapper 9.7.1 / 无 NDK ⇒ 能把「编译错误 / 类型签名 / lint 二次 issue」从「一轮 CI 20 分钟」压到「本地 2 分钟」，属跨波复利）。

---

## 7. 相关产物与工具

- 提交区间：`436e6f0..89b61db`（1 commit，15 文件）
- CI：Build [36721485565](https://github.com/Rickeal-Boss/Android-Agent/actions/runs/36721485565) / Release [36721485755](https://github.com/Rickeal-Boss/Android-Agent/actions/runs/36721485755)
- 上一波：`docs/handoff-20260930-wave38-lint-clearance-memory-readstate.md`
- 本波方案与 playbook（**仓库外，不入库**）：`_plans/wave39-plan-沈思远.md`、`_plans/wave39-ci-playbook.md`
- **工具变更**：`_ci-tools/verify_lint.py` 补 `message`/`errorLine1`/`file` 输出（§4.4）；5 个脚本的 PAT 全部轮换（§4.2）
- 本地静态闸门：`bash scripts/arch-guard.sh`（**15 项**，实测 **exit 0**）+ `bash scripts/arch-guard-selftest.sh`（**PASS=23**，实测 **exit 0**）+ `python ../balance_check.py <files>`

## Suggested skills

无必须项。接手时沿用「主理人侦察（含证伪挂账失实）→ 沈思远窄而深审计 + 文件面切分 → 柯码成 ×N 文件面互斥并行 → 崔续程 CI 通道核验 → 主理人集成 → **本地三闸门** → push → CI 双绿 → **下载 lint-reports artifact 验证真实 issue 集**」SOP。
⚠️ **本波三条新铁律**：① `git ls-remote` 会假绿，push 通道真判据是 `git push --dry-run`；② `Release` 没有 `paths-ignore`，但有 paths 正向白名单（app/**、core-*/**、feature-*/**、gradle/**、gradle.properties、gradlew、settings.gradle.kts、build.gradle.kts、.github/workflows/**、scripts/**，不含 docs/ 与 *.md）⇒ 纯 docs 提交 Build（paths-ignore）与 Release（paths 白名单）双 0 run；⚠️ paths 对 tag 推送不生效（tag 无 changed files），tag 总会跑；③ 任何 lint 判读工具的输出**必须带完整 `message`**。
⚠️ **子 Agent 配额**：本波 4 路并行后频繁撞 429（重置 2026-10-01 01:01）⇒ 面 C 与多轮对话中断，**主理人接手补完**。下波建议：**关键面由主理人亲自兜底**，或把并行度降到 2–3。
