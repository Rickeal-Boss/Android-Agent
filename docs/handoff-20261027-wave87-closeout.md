# W87 交接 — 收尾就绪度波（2026-10-27）

分支 `core-fix`，波前 tip `ce976ef`（W86）。输入 = Desktop 六份外审/对读报告
（三仓对读 · NVIDIA SimReady ／ 第五轮深度审查 · 收尾就绪度 ／ W86 收尾阻塞项清单 ／
第五轮深度审查 · 深水区收官 ／ w86-improvement-plan v32 ／ 复审 38 三线合订）
＋ 主理人逐条回源核验 ＋ 真机窗口批。

## 一、波次定性：收尾期的门槛不是缺陷，是「第一次真·发布构建」和「承诺与现实的差」

六份报告独立收敛到同一判断，我复核后认同：**代码级 P0/P1 存量已清空**（第五轮深审甚至宣告
「五轮来首次零代码级新发现」）。收尾期真正挡在 tag 前面的只有两件事：

| 门槛 | 状态 | 本波处置 |
|---|---|---|
| **R8 / 签名轨从未在 `core-fix` 上跑过** | 🔴 真实且高危（`debug { isMinifyEnabled = false }` ⇒ debug 全绿对 R8 零证明力） | **W87-22 实跑通过**（run `40498254820`，见 §四） |
| **「承诺与现实的差」无载体** | 🟡 README 停在 W79 时代（`@Test 735` / 守卫 42）、canonical 口径三处矛盾、无 Known Limitations | W87-12~16 + W87-13 |

⇒ 本波**不开新码面**，只做三件：① 让 R8 见光；② 把「全兜 / 全绿 / canonical」这类
**表述**校准到与实码一致；③ 把「有意不做」的部分写成决定而不是欠债。

## 二、辩证核验（本波最重要的一节：**3 项处方被现役树证伪**）

### 2.1 证伪三项

| # | 外审主张 | 我的回源核验 | 处置 |
|---|---|---|---|
| **V1** | **C-3**：「`persistOrWarn` 17 处全在 `SettingsRepository`，`ModelRepository` / `JsonFileStore` / `WallpaperStore` **零命中**」⇒ `ModelsViewModel.onDelete → store.write → IOException → viewModelScope 无 CEH ⇒ 进程被打死`；「磁盘接近写满是本 App 高频状态，用户此时正想删模型腾空间」 | ⛔ **不成立**。`JsonFileStore.write` **内部自兜**（`catch (t: Throwable)` → 返回 `Boolean`，失败记 `AgentLogStore.error`，**不抛**）；`WallpaperStore` 走 `bitmap.compress` + `WallpaperImportFailure` 失败分类上屏（机制③）。⇒ C-3 描述的可达崩溃路径**不存在**。同批另一份报告（第五轮深审）与复审 38 的 F7 **结论与我一致**（「`JsonFileStore.write` 本就 fail-safe，ModelRepository 经它自动覆盖」）—— **两份外审互相矛盾，我回源判第三份对** | **缺陷降级为表述注记**（W87-02：`PersistenceGuard` KDoc 补「全兜 = 三机制并存」）；**但顺带挖出真缺口**，见 §三.1 |
| **V2** | **N-A**：「`check-backlog-refs.sh` 在提交树实测 **16 处**，MIN_CLAIMS=24 当场触发骤降告警；32 疑为草稿树测量」 | ⛔ **不成立**。干净树实测 **34 处 OK**，`MIN_CLAIMS=24` **未触发**（EXIT=0）。另一份报告称 32 处，同样不成立 | 把「申报数字一律取 commit 后干净树实测，不采信任何报告转述数」写成 `MIN_CLAIMS` 旁的**明文纪律**（W87-17）；并在治理注记里登记「16 / 32 / 34 三个数字并存」这一事实 |
| **V3** | **C-2**：「无签名密钥时 release **静默退 debug**，tag 当天才发现」⇒ 处方「tag 上必须硬失败」 | ⛔ **门禁早已存在**。`release.yml:216-222` 的 `Gate tag release on signing secrets`（`if: startsWith(github.ref, 'refs/tags/v')`，`available != true` ⇒ `::error::` + `exit 1`）由 **Wave4 D-P0-1** 落地，**比这两份报告都早**；run `40498254820` 里该步 = **skipped**（dispatch 的 ref 是分支，属期望行为）。两份报告**都没查到该步骤** | **不新增任何门禁**（复制判据 = 两处必漂移）。真缺陷改为**口径矛盾**：文件头「核心约定」写「缺失时…**绝不失败**」与 :216 直接冲突 ⇒ W87-20 订正为两段式表述，并同批订正两处同源文案 |

### 2.2 口径修正两项

| 项 | 报告说 | 实测 | 处置 |
|---|---|---|---|
| `@Test` 计数 | 复审 38 的 Android 线报 **880**（rg 宽口径，含 KDoc 提及） | 严格口径（`^\s*@Test` 且排除注释行）**870 → 874**（本波 +4） | 以 874 为准；`build.yml baseline=874` 同 commit 双向核对 |
| arch-guard 条数 | 三仓对读报 **60** | **59** | 以 59 为准（三仓对读是「60 条守卫」的近似表述，非精确计数） |

### 2.3 采纳两项（原本我倾向不做，核后认账）

- **F7「全兜」表述失真**：交接称「`ModelRepository` / `WallpaperStore` 同法（套 `persistOrWarn`）」，
  实码是另外两条机制。**效果面闭环、无行为缺陷**，但按字面审计会漏后两者 ⇒ 只改注记，不加壳
  （加壳会造成双份日志）。
- **F13 `AppContainer.kt:174` KDoc 残缺**（`istoryStores]` 缺 `[h`）：⛔ **证伪** —— 现役树无此残缺
  （开发席实测）。这是第 4 例「外审描述被现役树证伪」，记入 §八。

### 2.4 三仓对读的处置（用户已定：skill / MCP 延后，先落文档）

| 建议 | 处置 |
|---|---|
| N1-01 `acceptance-criteria.md`（rule id + 双向一致性） | ✅ **落骨架 + 抽样**（`docs/16`）：格式约定 + 双向规则 + DL/ARCH 首批 8 条（每条带真实守卫号/台账 id）。**不做** 59 条全量重构（会碰守卫脚本，收尾期风险不可接受） |
| N1-03 反向清单（「只有文档没有实现」） | ✅ **落地**（`docs/17-known-limitations.md`，带 `Verified as of 2026-10-27` 日期戳） |
| N1-04 仓内 `AGENTS.md` | ✅ **落地**（231 行；persona + standing rules（每条指到仓内机制/台账 id）+ 能力边界自陈 + 何时问用户 + Current Repository Notes）。**零外部文本搬运**（许可证红线） |
| N1-02 CI 每条违规一个 check run ／ N2-01 `JournalTrajectoryExporter` ／ N2-02 `evals/` ／ N3 BOM | ⬜ **本波不做**（属表达层改造与新功能面，收尾期不开） |
| 「`.md` 仍 CRLF」 | ⛔ 证伪：实测 **LF**（文档席核） |
| 「`_ci-tools/` 被文档引用但不在仓内」 | ✅ 成立 ⇒ `AGENTS.md` 一律引用**仓内** 6 条脚本链（`scripts/*.sh`），`_ci-tools/` 只作本机便捷封装、**不入仓** |

## 三、落地清单

### 3.1 代码面（W87-01~11，`core-data` / `core-agent` / `core-model` / `app` / `feature-chat`）

**W87-01（真缺口）`JsonFileStore` 取消透传 —— 全仓最后一条漏网的落盘路径**

- `write` 的 `catch (t: Throwable)` 与 fallback 的 `runCatching` **都会把 `CancellationException`
  吞成「落盘失败 ⇒ 返回 false」**；`read` 的 `catch (t: Throwable)` 会把它改写成 `null`
  （后者更危险：`ModelRepository.refresh` 见 null 会走「首次启动可放心写回」⇒ **一次取消就能把
  损坏清单覆盖成只剩当前扫描到的**）。
- 修法：三处（`read` / `write` 主 catch / `write` fallback）**前置透传取消**；fallback 的
  `runCatching` 改显式 try/catch（`runCatching` 捕 `Throwable`，是同一陷阱的另一种写法）。
- ⚠️ **如实申报：这是防御性修正**。当前 `write` 的 block 内**没有挂起点**（`writeText` / `Files.move`
  是阻塞 IO），`withContext` 又会在返回时按 Job 状态重抛 ⇒ 「吞取消」眼下**不产生用户可见的错误结果**。
  纪律的价值在于：将来 block 内一旦出现挂起点（例如换成可取消的流式写），取消不会被静默改写。
  **不许被后人读成「修了个真 bug」。**
- 可测性接缝：`JsonFileStore` 新增 `pidProvider: () -> Long = { Process.myPid().toLong() }`
  （与既有 `json` 参数同性质；不开 `returnDefaultValues` 时 `Process.myPid()` 会抛 not-mocked，
  整条 `write` 路径在 JVM 上不可达）。**生产调用点不传即默认值，非行为改动。**
- 测试：`JsonFileStoreTest` **4 例**（取消必 rethrow ×2 路径 / 落盘失败返回 false / 成功返回 true）。
- **同族复制**（本波自己的主题）：`AgentLogFileStore.append` 的 `runCatching` 换成显式
  try/catch + 内层 `catch (CancellationException) { throw }`（**两层都要改，只改内层 = 死代码**）。

**W87-02（表述精度）「全兜」= 三机制并存**
- `PersistenceGuard` KDoc 补一节：① `persistOrWarn` 套壳（`SettingsRepository` 全部 DataStore 写点）；
  ② `JsonFileStore.write` 内部自兜（`ModelRepository` / `ConversationRepository` / 索引走此路径）；
  ③ 调用方失败分类上屏（壁纸导入 `WallpaperImportFailure`）。
- **不给 `ModelRepository` 重复套壳**（会造成双份日志）。
- 新增写点的归属规则写进 KDoc：**DataStore 写点走 ①，JSON 文件写点走 ②。**

**W87-03（F9）journal 尾读读失败不再静默**
- `readTailWindow` 读失败原为 `TailWindow(emptyList(), fullCoverage = true)` ⇒ `findUnsettled` 当空文件、
  **静默跳过该未决 run**，无 warn（对比：超窗回退有 WARN）。
- 改为**加 warn 留痕**且文案与「超窗回退」可机械区分；**不改控制流**（收尾期保守支，理由写进注释）。
- ⚠️ 如实申报：**未补测试**（Windows 上不可移植，同 W84/W86 既有口径）。

**W87-04 落盘异常日志口径统一**：`persistOrWarn` / `AppContainer` CEH 原记 `t.message`（DataStore
异常消息可能含完整路径）⇒ 统一为「只记异常类名 + tag」。

**W87-05 `LOG_LINE_BUDGET=396` 消除跨模块手抄**：`AgentLogStore.MAX_MESSAGE_CHARS` 从 `private`
放宽为 `public const`（Kotlin `internal` 按**模块**隔离，`core-model` 的 internal 到不了 `app`），
`CrashSceneLogger` 改为 `LOG_LINE_BUDGET = MAX_MESSAGE_CHARS - SANITIZE_GROWTH_RESERVE(4)`
⇒ **「每行不超出口上限」这个承诺由构造保证，不靠人记**。

**W87-06 `Sha256Verifier` 双份 1 MiB 缓冲**：删冗余 `BufferedInputStream` 包装，KDoc 订正瞬时堆口径。
**W87-07 `stripDuplicateSuffix` 命名纪律**：**不改正则行为**，只写清「新增预设文件名不得以 `-<数字>` 结尾」
+ 14 条预设零踩雷实测结论。
**W87-08 `FileTools.intArg`** 降级分支加 warn 留痕（科学计数法形态会走到这里），不改返回值语义。
**W87-09 纯减法**：`AppContainer` 861 行（净 −24，删 `SegmentedHistoryStore` 池 + 访问器）；`FileTools` 581/600。
**W87-10 文案**：`verifyBeforePromote` 的 UNREADABLE ERROR 去字面 `**`（markdown 星号会进诊断面板/剪贴板）；
`ChatScreen.terminationHintOf` 的 GateRejected 分支改用**与 error 横幅不同措辞**的短句
（两者会同屏出现，字面相同 = 用户读同一句两遍）。
**W87-11** `FileTools` 末尾空行清理。

### 3.2 治理面（W87-17~21）

- **W87-17 承接行销账判据**：`W83-12`（源行）收口为 `closed-done` 并加「由 W84-15 收口」指针；
  `check-backlog-refs.sh` 新增**附加④**：源行含「承接 / 转 Wxx-yy / 由 Wxx-yy 收口」且目标行已终态
  ⇒ 源行仍 `active`/`parked` 则 stderr 提示（advisory，不阻断）。**红绿双向夹具实测**
  （干净树不误报 + 注入违例必报）。同批把「申报数字取干净树实测」写成明文纪律。
  ⚠️ **性能判据（本波新坑）**：首版用 bash 逐行 grep，脚本从「几秒」拖到 **4 分 52 秒**（sys 4m33s 全在 fork）
  ⇒ 改成**单进程 awk** 回到秒级。**5 分钟的工具 = 没人会跑 = 守卫自动作废。**
- **W87-18 台账状态位**：`W86-08` `parked → active`（它是 W84-18 四子项重启的**硬前置**，不是「候选第 2 位」那么远）；
  `W86-09` 从候选序移出，改判「**环境可达才排**」（HF 直连本沙箱恒 502 ⇒ 占着候选位只会每波空转）。
  `docs/15` 新增**波末清单**段：每波收官必须本地跑 `check-backlog-refs.sh` 并处置 stderr 的 4 条 advisory
  （它们按设计在 CI 不可见 ⇒ 不写进清单就持续空转）。
- **W87-19 软守卫毕业判据成文（方案 B）**：`check-new-file-guard.sh` 的 advisory 是**终态**，不翻硬门禁。
  理由（实测数据，非推测）：受行数闸门覆盖的 `.kt` 只有 **19 个**，其余 **123 个**是「从未打算加闸门」的常规件
  ⇒ 判红会一次制造 100+ 条假红。**翻案条件**留门但明确：判据是**闸门覆盖率**（如覆盖面扩到全部 ≥300 行文件），
  **不是波数** —— 方案 A（连续 3 波零真信号）的触发条件不可机械判定，会退化成「靠人记得数三波」。
- **W87-20 `release.yml` 口径订正**（见 §2.1 V3）：文件头「核心约定」改两段式 + 同批订正两处同源文案。
- **W87-21 `docs/15` 候选段改道**：头部加「本节裁决状态已收编至 `docs/14 §E`（E2）」；
  UI-01~06 **逐条回源标状态**（每条附 grep 出处）；另标注「`docs/03` / `docs/07` 里的 UI-01/UI-06 是旧审查编号，
  与本表不是同一条」的撞车提醒。

### 3.3 文档面（W87-12~16）

- **W87-12 canonical 三处口径合一，裁决 = `core-fix`**：`docs/14` §C2:131 与 §D#5 原推荐 `improve`
  → 改 `core-fix`，**保留沿革注记**（「原推荐作废的原因是**三支已合流、原落点已无对应现实**，
  不是原推荐当时判错」）；`README` 分支口径段补 `core-fix` 为 canonical + 如实写明
  「不在 `release.yml` 白名单 ⇒ R8 只能靠手动 dispatch」。
- **W87-13 README「现状快照（2026-10-27）」**：活数字一行 + W84/W85/W86 三波各一行 + handoff 指针；
  头部「开荒阶段」→「**收尾阶段（首个 tag 前）**」。
- **W87-14 `AGENTS.md`（新，231 行）**：见 §2.4。
- **W87-15 `docs/17-known-limitations.md`（新，145 行）**：L1 真机矩阵单设备 / L2 TTFT / L3 ColorOS 灭屏冻结 /
  Qwen2.5 无锚点 / 仅中文 / 无障碍未做 / litertlm pin 0.17.1 …每条给「现象 / 影响 / 为什么首版不做 / 台账 id / 重启前提」。
  定调：**「写在这里的每一条都是决定，不是欠债」**。
- **W87-16 `docs/16-acceptance-criteria.md`（新，256 行）**：`AREA.NNN` 三行结构 + id 永不复用 +
  **双向一致性规则**（文档有而脚本不抛 = FAIL；脚本抛而文档无 = FAIL；`REVIEW.*` 豁免）+ Tokens 表 +
  DL/ARCH 首批 8 条抽样（每条带真实出处）+ 三批迁移计划。明确写「本文件当前是**骨架 + 抽样**，
  不是 59 条的完整替代」。

### 3.4 发布链（W87-22，主理人执行）

**`core-fix` 上手动 `workflow_dispatch` release.yml —— 该分支史上第一次过 R8 + 签名。**
见 §四。

## 四、闸门结果（本地）

| 闸门 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | BUILD SUCCESSFUL（全 9 模块） |
| `scripts/fulltest.sh` | **874 / 0 / 0 / 0**，9 模块全覆盖，`TEST-*.xml` mtime 新鲜（20:24:15–20:24:20） |
| `scripts/arch-guard.sh` | **59 / 59 OK，EXIT=0** |
| `:app:lintDebug` | BUILD SUCCESSFUL，**0 新增**（3 errors + 1 hint 由 baseline 冻结） |
| `scripts/check-backlog-refs.sh` | **34 处申报 OK，EXIT=0**；仅剩 W86-09 一条已登记 advisory |
| `scripts/check-new-file-guard.sh --all` | 未在本波改动面内（W87-19 只成文，未改脚本逻辑） |

`@Test` 基线 **870 → 874**（+4：`JsonFileStoreTest`），`build.yml` 沿革注释与 baseline 同 commit 同步。

⚠️ **两次假失败（如实记录）**：`fulltest.sh` 连续两轮报「N 个 test-results 目录未能清除」
（W74-05 的 Windows 句柄锁形态）—— **脚本拒绝判绿是正确行为**，不是脚本 bug。处置 =
`python shutil.rmtree` 清目录（`rm -rf` 在 Git Bash 下对锁定的目录会失败，python 会先清只读属性）
+ 等 10s 让句柄释放 + 重跑；第四轮得 874/0/0 干净汇总。CI（Ubuntu）无此问题。

## 五、真机验收（OPPO PDRM00 `13309cc8` / Android 13 / SDK 33）

| 项 | 判据 | 结果 |
|---|---|---|
| 冷启（新包） | `am start -W` + logcat `Displayed` | ✅ **273 ms**（`Displayed +273ms`，LaunchState=COLD）—— W86 为 655ms，**无回归且更快** |
| 冷启（旧包同窗口对照） | 同上 | 533 ms（COLD）—— 同一窗口内两次测量，量级一致 |
| 崩溃现场帧预算（回归） | `am crash` → `last_errors.log` | ✅ 新记录「前 **5** 帧」**无省略号**；同文件旧记录（W84 前）为「前 10 帧」且行尾 `…` 被截 —— **新旧对照即证据** |
| 正常生成未被新闸门误拒 | 会话 USER→MODEL | ✅ 生成在跑（`13.4 tok/s · 首字 20.1s`），`file_write` 正常触发审批卡 |
| W84-03 权限档位（真机臂） | 设置页切 READ_ONLY → 新对话 → 写工具仍走审批 | ✅ **部分取证**：设置页「AI 能力」切「只读」生效（副文案切为「写操作（改文件、写记忆、写剪贴板）执行前都会先问你」）；点「新对话」后 `file_write` **仍弹授权卡**（拒绝生效，日志见 `工具被拒绝：file_write`）。⚠️ **未直接取证**「`ChatUiState.capabilityMode` 在新对话后仍为 READ_ONLY」这个**状态值**（无 UI 暴露面，`capabilityMode` 只进审批缓存 key），该断言仍由 `NewConversationStateTest` 5 例 JVM 覆盖 |
| W86-02 篡改必拒（真机臂） | 下载 1 条有锚点的预设 → 篡改 1 字节 → 必拒 | ⬜ **未走（如实申报）**：最小有锚点预设是 `sizeBytes = 360822960`（≈344 MB，`ModelPresets.kt:633`），本轮设备窗口不足以完成「下载 + 篡改 + 复验」全链。**判据由 `Sha256VerifierTest` 4 例（含 MISMATCH）覆盖**；建议下次设备窗口直接锁定该条为靶 |
| W82-18 journal 对账（取数即闭） | — | ⬜ 未走（需定位特定未决 run，收益低于本轮窗口成本） |
| W82 防抖四步 / 帧统计聚合 / LQ-1 泄漏题面 / W82-03 下载取消 / 逃逸多路径多样性 | — | ⬜ 未走（本轮窗口优先给了「新包回归 + 两条硬判据」；仍留 W86-07） |

**W86-07 设备窗口批结算**：8 项中 **2 项推进**（① W84-03 部分取证、② W86-02 定位到靶但未走），
**6 项未走**。剩余面继续挂在 W86-07（active），不新开条目。

## 六、CI 实跑

### 6.1 Release 冒烟（本波最高价值动作）

- `POST /actions/workflows/{release}/dispatches` `{"ref":"core-fix"}` ⇒ HTTP **204**
- run **`40498254820`**：`Release` / `workflow_dispatch` / ref `core-fix` / head **`ce976ef`** / 结论 **success**
- 逐步骤（关键项）：

| # | 步骤 | 结果 |
|---|---|---|
| 9 | Detect signing secrets | success |
| 10 | Architecture guard | success（**发布轨也过守卫**） |
| 11 | Architecture guard self-test | success |
| 12 | Gate tag release on signing secrets | **skipped**（正确：dispatch 的 ref 是 branch） |
| 13 | Assemble debug APK (fallback artifact) | success |
| 14 | Verify 16KB ELF page alignment (debug APK) | success |
| 16 | **Decode keystore** | success（⇒ **签名密钥在位**） |
| 17 | **Assemble release APK** | **success** |
| 18 | **Assemble release AAB** | **success** |
| 19 | **Verify release APK signature** | **success**（apksigner v2+v3） |
| 20 | **Verify release AAB signature** | **success** |
| 22 | Upload release APK / AAB | success |
| 23 | **Upload R8 mapping** | success |
| 24 | Publish GitHub Release | **skipped**（正确：dispatch 不建 Release） |

⇒ **R8 + 签名轨在 `core-fix` 上首次见光且全绿**。这一条把「打 tag 那天才第一次跑 R8」的风险
从「未知」变成「已验证，且知道怎么重跑」。

🔁 **纪律（写进 `README` 与 `docs/14`）**：**每次 RC 前重跑一次 dispatch** —— 代码在动，R8 结论有时效性；
上一次全绿只证明 `ce976ef` 那一刻成立。`tags: - 'v*'` 独立于 `branches` 过滤 ⇒ 打 tag 本身会触发 release，
不能等打 tag 那天才第一次跑。

### 6.2 build.yml（push 后由 CI 出结果，本交接 §6.2 待回填）

> ⚠️ **待回填**：本波 push 后的 `build.yml` run id / 三 job 结论。若本次 CI 出现 FAIL，
> 以 CI 为准（本地未跑全量 selftest —— 每 case 重跑 `arch-guard.sh`，200+ case 的 spawn 开销不可接受；
> 用户已明确指导「本地只冒烟、全量归 CI」，CI 绿 = 全量真绿）。

## 七、挂账权威表更新摘要（`docs/15-backlog.md`）

- `W83-12` → **`closed-done`**（由 `W84-15` 收口；保留原始四要素与沿革，注明「收口 ≠ 剩余面消失，
  Qwen2.5 锚点 + 摘要落库仍在 W86-09」）。
- `W86-08` → **`active`**（四要素补齐：现状 1381/1400 余 19 / 判据 = W84-18 硬前置 / 落点 =
  下载管线约 400 行外提 `ModelDownloadController` 分两刀 / 验收 = ≤1000 行 + 新控制器同批进 line-table）。
- `W86-09` → 维持 `parked`，但**移出候选序**，改判「环境可达才排」；并如实登记「`handoff-20261026-wave86-coverage.md`
  §候选 仍列它 ⇒ 附加① 每波会为它打一条 advisory，**处置 = 下一波重写候选段时移除，不要为了让警告消失而翻状态**」。
- 新增**治理注记**两节：① 软守卫 advisory 是终态（W87-19 方案 B + 翻案条件）；② C-2 处方被现役树证伪（第 3 例）。
- 新增**波末清单**段（四条 advisory 的处置义务 + `MIN_CLAIMS` 骤降的复核顺序）。
- `docs/15` UI 候选段：加改道指针 + UI-01~06 逐条状态（含编号撞车提醒）。
- active 总数 **19 → 19**（W83-12 收口 −1、W86-08 转 active +1，净 0）。

## 八、本波新坑与教训（铁律增补）

1. 🔴 **「采纳外审处方前必须先 grep 现役树」——本波 3 项处方被证伪，全部是「时点差」**。
   报告查的是它那一版的树，处方描述的是「它以为的树」。前两例（W86）：`--all` 逐条告警形态、
   `14/14` 锚点目标；本波第三例：C-2 的 tag 门禁**早已存在**（Wave4 D-P0-1）。
   ⇒ 纪律：**已存在 ⇒ 缺陷降级为口径/文档订正，不新增门禁**（复制一份判据 = 两处必漂移）。
2. 🔴 **两份外审互相矛盾时，回源判第三份**。C-3（「ModelRepository 零兜底 ⇒ 崩溃」）与
   第五轮深审（「`JsonFileStore.write` 本就 fail-safe」）直接对立 —— 我回源后判后者对。
   **不许因为「报告是外审」就采信，也不许因为「两份都说了」就取交集。**
3. 🔴 **工具的耗时本身就是判据**（本波新形态）：`check-backlog-refs.sh` 的附加④首版用 bash 逐行 grep，
   脚本从「几秒」变 **4 分 52 秒**（sys 4m33s 全在 fork）⇒ **5 分钟的工具没人会跑，守卫自动作废**。
   与「永远 exit 0 / 永远 exit 1 = 同一个病」是同族：**没人用的检查等于不存在**。
   修法 = 单进程 awk（并保留「别改回逐行 grep」的注释 + 方言兼容实测）。
4. 🔴 **「防御性修正」必须如实标注**：`JsonFileStore` 的取消透传当前**不可观测**（block 内无挂起点）。
   若写成「修复了一个会导致进程崩溃的 bug」，下一个人会按「曾有真实崩溃」去排查不存在的现场。
5. 🔴 **取消透传的适用范围 = 挂起点，不是所有 `catch`**：包住纯阻塞 IO（`writeText` / `Files.move` / `readText`）
   的 `catch (t: Throwable)` 属**形式对齐**，不做不算缺陷。把纪律用过头会演化成「全仓 catch 都要改」的伪工作量
   （本仓反复踩「纪律用过头」）。残留面（`ModelDownloader.kt:184` / `ModelRepository.kt:364,452` /
   `PerformanceMonitorManager.kt:309`）**发布前不动**（都在发布关键路径上），登记为后续小批。
6. 🔴 **同一屏上的两处文案必须措辞不同**：把两处「上一轮仍在生成」收敛成同一个常量，会让他们
   **同屏出现时用户读同一句两遍**（error 横幅 + 终止提示小字）。「同源」的实质是「指向同一原因、同一处改」，
   **不是字面相同**。这是「收敛」本身的副作用，只有回看渲染结果才能发现。
7. **`am start -W` 的 `LaunchState=UNKNOWN` + ~3s 在本机有两种成因**：dexopt 未稳，**以及 keyguard 未解**
   （本次实测：屏幕在 AOD，`Displayed` 行根本没产出）。判据 = 先 `dumpsys window policy` 确认
   `screenState=SCREEN_STATE_ON` + `interactiveState=AWAKE`，再看 `Displayed`。
8. **Windows 上 `rm -rf` 清不掉被锁的 `test-results` 目录，`python shutil.rmtree` 能**（前者不处理只读属性）。
   `fulltest.sh` 拒绝判绿是**正确行为**，处置顺序 = python 清 + 等 10s 让句柄释放 + 重跑。

## 九、下波建议（W88 候选，按优先级）

1. **W86-07 设备窗口批收尾**（剩 6 项 + 2 项硬判据的完整臂）：`W86-02` 篡改必拒锁定
   `ModelPresets.kt:633`（≈344 MB，最小有锚点预设）为靶；`W84-03` 的状态值断言需要先想清「怎么让
   `ChatUiState.capabilityMode` 可观测」（可选：诊断页加一行，或把 mode 写进 journal）。
2. **W86-08 `ModelsViewModel` 预拆**（已转 active；余 19 行，W84-18 四子项全以它为落点）。
3. **W84-16 journal reconcile→reclaim**（一页纸已备，顺序铁律：**先对账后 reclaim**；同批可收口 W68-01）。
4. **W83-11 `SegmentedHistoryStore` 文件本体删除** + `DefaultEngineFactory.kt:45` 过期注释（与 ③ 同批）。
5. **发布链剩余**：法务 `termsVersion` 正式文本 + 托管地址（**唯一外部阻塞，待用户**）→ 首个 tag。
6. **决策会**：`docs/14 §E` 余项（E2 U1–U5 / E3 无障碍 / E4 法务 / E5 tag 落点 / E6 bump / E9 i18n）。
7. **W86-09** Qwen2.5 锚点（**环境可达才排**）。

## 十、一句话结论

**代码面已经清完了，收尾期真正挡在 tag 前的只剩「第一次真·发布构建」和「承诺与现实的差」——本波把这两件都办了：
R8/签名轨在 `core-fix` 上首次见光且全绿（run `40498254820`），README / canonical / Known Limitations / AGENTS.md
四件载体全部落地，并把三份外审的过期处方按现役树证伪（C-3 / N-A / C-2）。
剩下的唯一外部阻塞是法务文本，其余全部是可随后续版本走的已知限制。**

