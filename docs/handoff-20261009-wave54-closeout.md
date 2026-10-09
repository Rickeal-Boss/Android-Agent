# Wave 54 收官交接（improve 分支）

> **唯一权威交接文档**。逐波细节看 `handoff-*.md`；README 台账是特性/路线图的权威版。
> 本文件回答三件事：**W54 做了什么 / 留下了什么判据 / 下一个人从哪接手**。
>
> 基线：W53 收官 `48b6880`（含其本地修补，本波随同推送）｜本波 tip = `improve` 分支最新（W54 共 12 commit；**CI 触发推送 = `ac5a362..04efed3`**，其后 2 个 docs-only 回填实测 0 run），CI 见 §八
> 前置文档：`docs/handoff-20261009-wave53-closeout.md`（W53 逐项记录）

---

## 一、W54 总账

### 一句话
**四份外部审查报告对账 + 观测面补强 + 审查收口**：**报告1 的「唯一现行缺陷」N-W1 经回源码证伪**（其修法会破坏 Wave 31 不变式，明确否决）；落地 **自愈重建计数观测面**（毒化测试前置）、**父子墙继承算术加固**（钉住「父挂起只计一次」，堵 N-W1 式乱改的回归口）、**#25 注释非写死化**（治 HEAD 处活 stale）、**M1 R-E 耗时口径声明**、**对账子项级固化**（补 2 条 W53 漏账）+ **W53 文档勘误**；`build.yml` 基线 **623 → 624**（本波 +1 例 @Test）；引擎 `LiteRtLmEngine.kt` **2063 → 2087 行**（余量 313）。

### 关键现实（如实申报）
本波改动**行为中性**：1 处纯函数外提（零行为变更）+ 1 处观测计数器 + 1 处 KDoc + 守卫/文档注释。**无新增用户可见行为** ⇒ 真机验证范围为**装机冒烟**（§三），非行为验收。

### 改动清单（9 commit = 1 W53 修补 + 8 W54；7 文件）

| 文件 | 性质 | 说明 |
|---|---|---|
| `core-engine/.../local/LiteRtLmEngine.kt` | 引擎 | **+** 实例级 `@Volatile var templateRebuildCount`（`:241`，`releaseInternal` `:2079` 复位）；`onError` 模板失败分支 `++`，两处 `warn` 文案带 `（会话重建 #N）` |
| `core-agent/.../AgentRunner.kt` | 代理 | 抽文件级纯函数 `childDeadlineNanos(parentDeadlineNanos, parentPausedNanos)`，spawn 点（`:1956`）改调（**零行为变更**） |
| `core-agent/.../WallClockEvidenceTest.kt` | 测试 | **+1 例**（void）：钉「父挂起只计一次」——`childDeadlineNanos(D,P) − D == P` 且「子(自身 paused=0) remaining == 父(含 P) remaining」。8 → **9 例** |
| `feature-chat/.../AssistantPersistence.kt` | 文档 | `shouldSalvageOutput` KDoc 补「W52 B1 起 `WallClockBudget` 耗时口径 = 有效执行时长」（判据/集合**未动**） |
| `scripts/arch-guard.sh` | 工具链 | #25 注释**去写死数字**（只留阈值 2400 + 理由，实测行数以违规输出 `当前 $n 行` 为准）；#27 补「README 台账聚合计数订正行（双四元组）不在扫描面」边界申报。**守卫逻辑/输出格式一字未动** |
| `.github/workflows/build.yml` | CI | 用例数基线 **623 → 624**（#26「测试基线双向同步」强制；全仓 `@Test` 代码位实数 = 624） |
| `docs/handoff-20261009-wave53-closeout.md` | 文档 | W53 勘误：2389 → **2354**（父提交 `287d40a` 实测）/ `GPU_FAILURE_*` private→internal 申报 / 4new → **5new** / 对账表补 2 条子项级表态 + 模板约定 / #25 stale 陈述订正 / 行数加 commit 锚 |

⇒ **@Test 实数 = 624**（与 `build.yml` baseline 吻合，#26 双绿）。

---

## 二、🔴 本波最重的技术内容

### 2.1 N-W1 证伪（报告1 的「唯一现行缺陷」= 不成立的因果）
报告1 声称：父 run 审批停表（`pausedNanos`）**不向子 run 传播** ⇒ 长审批后 `ask_actor` 子 run「出生即熔断」。其证据链第 2 步把 `AskSubagentTool:229` 的 `parent.deadlineNanos` 当成「原始绝对墙」。

**回源码证伪**：该字段在**构造点**已被父 run 折入停表量 ——

| 环节 | 文件:行 | 代码 |
|---|---|---|
| **子墙构造（关键）** | `AgentRunner.kt:1936` | `deadlineNanos = state.hardDeadlineNanos + state.pausedNanos` |
| 子 run 透传 | `AskSubagentTool.kt:229` | `deadlineNanos = parent.deadlineNanos` |
| 子 run 接参/建墙 | `AgentRunner.kt:731` → `:1354` | `hardDeadlineOverrideNanos = request.deadlineNanos` → `hardDeadlineNanos = override ?: (now + 5min)` |
| 子 run 自身 paused | `AgentRunner.kt:1384` | `var pausedNanos: Long = 0L` |

⇒ 子 run `remaining = (D_parent + P_parent) + 0 − now` **= 父 run remaining** ⇒ **父子共用同一堵墙**，长审批后子 run 不会出生即熔断。引入时点 `git blame` = `29d573f`（W52 B1），报告1 对其**自身基线** `ac5a362` 即判错。

**报告建议的修法必须否决**：若给 `ParentContext` 加 `pausedNanosBaseline` 作子 run `pausedNanos` 初值，则
```
child_remaining = (D_parent + P_parent) + P_parent − now = parent_remaining + P_parent
```
⇒ 子 run **凭空多出「父已停表时长」的额度**，重开 Wave 31 修掉的「上界放大」缺陷。**故否决实现、采纳其测试意图**（见 §2.2）。

### 2.2 N-W1-R 反向加固（`a123187`）
spawn 点算术 `:1936` **此前零测试覆盖**（`WallClockEvidenceTest` 只钉了两条纯函数）⇒ 未来若有人照报告1 实现 baseline，**没有任何测试会红**。本波把该算术外提为纯函数 `childDeadlineNanos` 并 +1 例单测（钉「父挂起只计一次」）。

### 2.3 N-W2 自愈重建计数观测面（`47a03a7` + 收口 `02e15f8`）
`LiteRtLmEngine` 加实例级 `@Volatile var templateRebuildCount`（与 `nativeToolsRejected` / `conversationDirty` 同域、同 `@Volatile` 纪律），`releaseInternal` 复位；`onError` 的 `isTemplateRenderFailure` 分支 `++`，两处 `warn` 带 `（会话重建 #N）`。
- **用途**：① W54 毒化测试的判据（关 fold 多发必炸 / 开 fold 不炸，直接钉「元素数」维度定 H-A）；② 将来「重建限次软熔断」的数据面。
- ⚠️ **阈值待真机 N 分布确定，勿现在拍**（本字段只做观测，不做判据）。

### 2.4 #25 注释非写死化（`ba5025d`，治 HEAD 处活 stale）
W53 的 `48b6880` 这个「#25 注释数字订正」提交**同时**改了 `LiteRtLmEngine.kt`（净 +9 行），把注释刚订正的 2054 **当场改过期**（实测 2063）。本波把注释里的「实测行数」表述删除，改为「**实测行数以本守卫违规输出 `当前 $n 行` 为准，注释不写死**」——把同步负担从注释转移到输出（输出本来就是每次运行实况）。**守卫逻辑与输出格式未动 ⇒ 不触发「新增守卫须配 selftest」约束。**

### 2.5 M1 R-E 耗时口径声明（`b4fdf3b`）
`shouldSalvageOutput` KDoc 补：W52 B1 起 `WallClockBudget` 的耗时口径 = **有效执行时长**（墙钟 − 审批挂起 `RunState.pausedNanos`，见 `wallClockRemainingMillis`）。故「审批等待导致的墙钟熔断」已消失、「真耗尽」的判定边界随之收紧——对账 evidence 与 breaker 记录须用此口径。**只陈述口径，未动判据与 `SALVAGEABLE_BREAKER_KINDS`。**

---

## 三、真机验证结果（OPPO PDRM00 / Android 13 / serial `13309cc8`）

> 本波真机范围 = **编译产物装机冒烟**（改动行为中性，无新增用户可见行为可验）。

| 判据 | 结果 | 证据 |
|---|---|---|
| **编译 + 装机** | ✅ | `assembleDebug` `BUILD SUCCESSFUL`（APK 121,737,746 B）；`adb push` + `pm install -r` **Success**；装机回读 `lastUpdateTime=2026-10-09 14:51:19`（即本波构建） |
| **冒烟启动** | ✅ | 冷启动 `com.rickeal.agent.debug` PID **13804** 存活；`am start` 走 `com.rickeal.agent.debug/com.rickeal.agent.MainActivity` |
| **无本 app 崩溃** | ✅ | 全量 logcat（**不过滤**）无 PID 13804 的 `FATAL EXCEPTION` / `AndroidRuntime` |
| **UI 渲染** | ✅ | 截图目视：Liquid Glass 首页（「新对话 / 开始一段对话 / 导入本地… / 下载推荐…」）+ 底部导航 对话/模型/工具/记忆/设置 全部正常 |

### 3.1 ⛔ 未行使 / vacuous（如实申报，不记通过）
- **native 相关判据**：本波 logcat 档**不含 native 行**（未加载模型 ⇒ 未走引擎）⇒ 按本仓铁律，**不得**据此声称「native 相关 0 命中」，只能申报为冒烟范围。
- **B1 长审批停表真机效应**：同 W52/W53，未构造长等待剧本 ⇒ **仍 vacuous**（代码路径已行使 + 算术已单测，真机未构造）。
- **N-W2 计数器的真机读数**：需模板渲染失败才触发 ⇒ 本波**未行使**（毒化测试留 W55）。
- **N-W1-R 新例**：仅覆盖纯函数接线，**不覆盖真机时序**。

---

## 四、坑复盘（本波最值得继承的 4 条）

### 1. 🔴 外部审查的「因果描述」会不成立（第 31 处「描述不成立」）
报告1 的 N-W1 是**本轮唯一被标为「现行缺陷」**的项，且**给出了一行式修法**——但它漏看了构造点 `:1936` 的 `+ state.pausedNanos`，结论与 W52 交接已证的「父 paused 只计一次」不变量**直接冲突**。教训：**外部报告的因果链必须逐环节回源码核**（本仓已累计 31 处）；**尤其当修法「很短很漂亮」时**，先问「它改的是不是我读漏的那一行」。

### 2. 🔴 「审查报告的修法」可能是负优化
N-W1 的建议修法若被执行，会**双重计入**审批等待、重开 Wave 31 已修的上界放大缺陷。⇒ **采纳测试意图、否决实现**是本波的正确形态：把「报告想防的回归」变成**正向不变式测试**（§2.2），而不是照抄它的补丁。

### 3. 🔴 修完一个数字，可能当场把它改过期
W53 的 `48b6880` 一边订正 #25 注释数字、一边改同文件代码（净 +9 行）⇒ 注释当场 stale。**根因不是「忘了同步」，是「把活数字写进散文」这个形态本身**。⇒ 本波改为「不写死、以违规输出为准」（§2.4）。

### 4. ⚠️ 子 Agent 自述与实测会不一致（本波 1 处）
开发席自述「`templateRebuildCount` 随 `releaseInternal` 复位」，实测 `releaseInternal` 无该复位 ⇒ 审查席判 **P1（描述不成立）**，收口 `02e15f8` 补上。教训：**「KDoc 声称的机制」必须与「代码实际做的」逐条对齐**（本仓「描述不成立」的又一形态）。

---

## 五、挂账台账（W55 起）

> 权威版仍是 README 台账 + 本文。每条给「最早可启动波次 + 重启前提」。

### 🔴 优先（W55 首批）

1. **H-A 定案（毒化通道，零容器成本）**：在 `Qwen2.5-1.5B` 上**关闭 fold** 一次回灌 ≥3 工具结果 ⇒ 应必炸（`Failed to apply template`）；再开 fold ⇒ 应不炸。**直接钉「元素数」维度**，无需发图、无需新容器。⚠️ **前置件 N-W2 计数观测面已在 W54 落地**（`47a03a7`）⇒ 本次可读「会话重建 #N」分布，避免终态混叠。
2. **B1 真机长审批剧本**：构造审批挂起 ≥5min10s（审批卡本身即暂停开关：不自动批 + 熄屏等待后手动批，**无需改产品代码**），验证不被 HARD 熔断（四判据：审批弹出 / run 继续 / `WallClockBudget` 熔断 = 0 / `pausedNanos` 正确计入）。
3. **多模态 L1 剩 5 个未取证预设**：`Qwen2-VL-2B` / `LFM2.5-VL×3` / `SmolVLM2 500M` —— 零真机成本（`adb exec-out dd` + `minijinja`），每容器 ≈10 分钟。⚠️ 不盲断安全/不安全。
4. **多模态 L3 真机 A/B**：需一个已取证安全的视觉模型（L1 三选）+ 发图 UI 路径；协议须同时带 **role + 元素数**（附件消息走多元素 `Contents`）。

### 🟡 常规（顺延，未动项）

5. **N-W3 预设 `evidenceLevel` 数据化 + 守卫**（`recommended=true ⇒ 非 unverified`；`image=true ⇒ ≥ family-extrapolated`）：**并入「预设扩容波」**（`ModelPresetSchemaKeys` 是 Wave44 桩，其 KDoc 明写「迁移逻辑必须单独立项」，会同时动 `core-model` + `feature-models` 两模块匹配面）。**「图片入口硬闸门」并入本条**（正确形态 = 该守卫；app 对 `supportsImages=false` 本就不发图）。
6. **反复炸防护（同 content 二次失败即停自愈）**：顺序 = 毒化真机 N 分布 → 定阈值 → 落防护（落点沿用复审15 裁决：进程级 `cid` 键控 store）。**别现在拍阈值。**
7. **`DeepSeek-R1-Distill-Qwen-1.5B` 被 `inferFamily` 误判 `thinking=false`**（W48 让 `enable_thinking` 恒发 ⇒ 可能关掉其推理）。**重启前提：拿到该容器 + 真机验 AUTO。不得无容器盲改。**
8. **真机验收积压**：台账 36 条（29 ✅ / 5 ⚠️ / 2 ⛔）。未覆盖项：通知档B｜Gemma 两档｜层5「压缩触发重建」子路径｜记忆磁盘满·只读｜W37 UI 手感｜lint gate｜W38 行为变更｜W40 验收面｜F4 三级文字 token 统一。
9. **F4 三级文字 token「同角色不同色」6 处**：先立「语义角色→颜色」单一映射表再全仓对齐。⚠️ 深色 `onGlassSubtle`(0x80) 是**脆弱达标**（余量 <10%）⇒ **不要降 alpha**。
10. **数据回填 / 阈值回填**：口径见 W49 交接；阈值需 ≥2 个坏容器样本。
11. **`TokenUsage.estimated` 治根字段**（唯一能长期不撒谎的口径方案）。
12. **通知文案「渠道」误导**（`GenerationNotifier.kt` + 枚举 `CHANNEL_DISABLED`）。
13. **法务** `TODO(legal)`×4 / `termsVersion`（**必须先于任何法务文本替换落地**）。
14. **上游/已知限制**：`gemma-4-E2B-it-gpu`（GPU 输出乱码；CPU init `NOT_FOUND`）｜`MiniCPM-V-4-int8`（`Unsupported model type`）｜MiniCPM5 OFF 态规划外溢（2B int4 固有，**别动 `thoughtChannelDefsFor`**）。
15. **`main` 分支快照声明过时**（improve 领先数百 commit，README/Release 下载入口指向冻结分支）⇒ 绑定「打首个 tag 前」执行（fast-forward 或 README 顶部横幅）。**别挂到 tag 之后。**
16. **litertlm bump 评估立项**：⚠️ 硬前置（同 W53）——① H-A 定案；② 新版本重跑 `TextFoldTest` 全套；③ 一次真机 fold 复验（折叠前 3 → 折叠后 1）。**bump 不得先于上述前置合入**（#24 只钉 fold 调用点存在性，拦不住行为级漂移）。
17. **`ChatRunCoordinator.kt` 余量**（1278/1300）：若有功能落此文件须**先拆分后回灌**。
18. **`fulltest.sh` 接进 CI 运行步**（W50 挂账顺延）：现有 soft-check + #26 已钉口径，接入只剩收益。
19. **引擎 Stage-2/3 拆分预案**：当前 **2087** 行余量 **313**；触顶（≤2400）即启动；拆分须保守卫 #24 不变式。

---

## 六、外部报告对账（4 份，本波用户提供）

| 报告 | 审的修订 | W54 处置 |
|---|---|---|
| 第九轮深审（W52-53，`20261009`） | `ac5a362` | ⛔ **N-W1 否决**（回源码证伪，其修法会双重计入）；✅ 采纳 N-W1-R（测试意图）、N-W2、N-W4（**不适用**：#26 预案 W53 已落地）、N-W5（挂账）；⛔ N-W3 挂账 |
| 复审16（三线融合终稿，`20261009`） | `ac5a362` | ✅ 采纳：发现 A（对账子项级固化，补 2 条漏账）、发现 C（2389→2354）、发现 D（private→internal / 5new）、发现 E（#27 边界**申报**，**不扩扫描面**——双四元组是历史订正行，扩面会引入假红） |
| 全局问题清单 final（15 轮收敛终账） | `ac5a362` | ✅ 采纳：C3/C4/C13（#25 非写死化 + R-E KDoc 口径）；其余 20 项与 README 台账 + W53 §五**完全对齐，无漏项** |
| harness-improve v15 | `ac5a362` | ✅ 采纳 M1（R-E KDoc 口径）、M2（#25 非写死化形态）；M3（多模态 L3 前置）已折入 §五 优先条 4 |

> **子项级对账（W53 漏账回收）**：复审15 的 2 条子建议在 W53 对账表**无行**，本波补表态——
> - 「反复炸防护」→ ⚠️ **挂账**（§五 常规 6；顺序 = N-W2 → 毒化真机 N 分布 → 定阈值）
> - 「图片入口硬闸门」→ ⚠️ **挂账并并入 N-W3**（§五 常规 5；正确形态 = evidenceLevel 守卫）
>
> **模板约定（防「报告级落地掩盖子项缺失」）**：每条外部建议的**子项**必须落一行，含「未采纳 + 原因」态。子项级对账**不在机械守卫面**（无稳定锚点，靠人执行）——如实申报。

---

## 七、下次接手须知

1. **推送纪律**：`GIT_TERMINAL_PROMPT=0` + `-c credential.helper=`（空）**两者都要**（否则 GCM 无凭据**静默挂起**）；`-c http.sslVerify=false`（绕 MITM 的 `CRYPT_E_NO_REVOCATION_CHECK`）；**PAT 只以 URL inline 一次性使用、绝不落盘**。推前 **`git push --dry-run`** 核（`git ls-remote` 匿名读 public 库**假绿**；本地 `origin/<branch>` 跟踪引用可能**陈旧**——W54 实测本地显示 ahead 51、实为 9）。
2. **静态闸门基线**（改动前）：`arch-guard` **27 项全 OK**、`arch-guard-selftest` **PASS=65 FAIL=0**（本机 ~22 min ⇒ 给 ≥600s 或 `run_in_background`）、`scripts/fulltest.sh` = `tests=624 failures=1`（唯一失败 = `core-data` 的 `SandboxFileScannerTest.kt:184` Windows 符号链接，**既有基线**；gradle 会因它 exit 1，**不是**任务校验错误）。
3. **跑全量单测必须注入 `JAVA_HOME`/`ANDROID_HOME`/`GRADLE_USER_HOME`**，并**核对模块数 = 9**（`fulltest.sh` 自带「覆盖模块数：9（预期 9）」+ XML mtime 范围）。⚠️ **`GRADLE_OPTS` 的代理端口取 `${https_proxy}`**（本机实测 **4708**，非脚本注释里的 1799 兜底值）。gradle 的 `N tests completed` 是**残缺口径**，不可信。
4. **纯文档提交 ⇒ 两条 workflow 都 0 run** ⇒ 需出包必须 `workflow_dispatch`。
5. **设备**（OPPO PDRM00 / A13，serial `13309cc8`）：adb = `_j2env/sdk/platform-tools/adb.exe`（**不在 PATH**），Git Bash 需 `MSYS_NO_PATHCONV=1`；**`adb push` 的本地路径要写 Windows 形式**（`D:/...`），否则 `cannot stat`。**装机必须** `adb push` + `pm install -r`（直接 `install -r` 报 `Failure [-99]`）。
   ⚠️ **并发 adb 会让设备瞬时 `not found`** ⇒ **串行执行**；⚠️ **沙箱会拦「多命令链式」（`;` / `&&`）**，报 `decisionRecord missing actual resource subject` ⇒ **拆成单条**。
6. **UI 自动化**：`_ci-tools/_w49_ui.py`（`dump` / `nodes` / `tap`）。⚠️ `uiautomator dump` **对 Compose bounds 不可靠**且返回被遮挡节点 ⇒ 判尺寸/可见性**必须截图目视**；⚠️ **uiautomator 进程自身会抛 `UiAutomationService already registered` FATAL**（PID ≠ app）⇒ 判 app 崩溃前**必须核 PID**。
7. **中文输入不可行**（`input text` 非 ASCII 设备侧 NPE）⇒ ASCII 题面，空格用 `%s`。
8. **logcat 双档**：涉 native 判据必须落**不过滤**全量档，且**先验该档确含 native 行**才可说「0 命中」有意义（W54 冒烟档**不含** native 行 ⇒ 不得声称 native 0 命中）。
9. **`_plans/` 在 git 仓库外** ⇒ 设计/审查报告不 commit；关键结论**必须折进仓库文档**才算落档。
10. **判定四态纪律**：`✅ 通过` / `⚠️ 部分`（不得整体记 ✅） / `⛔ 不适用`（**不是通过**） / `⛔ vacuous`（判据未被行使）。**凡「目检」判据必须逐条抄原文**；**没触发判据写「vacuous/未行使」**。
11. **关闭原生工具通道**（行使文本协议 fold 路径用）：设置页 →「原生工具通道」开关（`SettingsScreen.kt:705`）；**测完必须还原为开**（settings pb 复核 `nativeToolChannel:true`）。
12. **多模态取证复用 W51 方法**：`adb exec-out dd if=<container> bs=1 skip=<off> count=<len>` 抽 `chat_template` + `minijinja` 离线复现，无需真机窗口即可定模板是否安全。

---

## 八、CI run id

推送 `ac5a362..04efed3`（**10 commit**，fast-forward）后**两条 workflow 自动触发**（本波含 `scripts/**` / `.github/workflows/**` / `core-*/**` / `feature-*/**` 等非文档改动，无需 `workflow_dispatch`）：

> 其后 `04efed3..02e6708`（本回填 commit，**docs-only**）**实测 0 run**（复核 run 列表未新增）——与 §七.4 一致。

| workflow | run id | 结论 | 关键 job |
|---|---|---|---|
| **Build** | **`37896782929`** | ✅ **success** | `Assemble Debug (JDK 21)` ✅ / `Lint (baseline gate)` ✅ / `Unit tests` ✅（**三 job 全绿**） |
| **Release** | **`37896782933`** | ✅ **success** | `Build & (optionally) sign release` 全步骤 success |

### 8.1 守卫网在 CI 上的实跑（本波**首次可读 CI job log**）
本波所用 PAT **具备 `actions:read`** ⇒ 首次直接读到 CI 内 job log（W51–W53 均因缺权限只能判「job 绿」这一层）：
- `Assemble Debug (JDK 21)` job 内：step 9 `Architecture guard` → **`架构守卫全部通过。`**（27 条）；step 10 `Architecture guard self-test` → **`自测结果：PASS=65 FAIL=0`** + `arch-guard 自测全部通过。`
- **非 vacuous 证据**：case33/34/35（#26 基线滞后 / 删例、#27 聚合句不符）的「红来自真命中」断言**均在 CI 日志中可见**。
- ⚠️ **CI 内 selftest 仅 ~9.5 s**（`07:03:22.000` → `07:03:31.479`），而**本机同脚本 ~22–24 min** —— 差异来自 Windows 文件系统（守卫的 `grep -rn` 全仓扫描 + 每 case 建临时树）。**判据以「PASS=65 FAIL=0」为准，耗时不是判据。**
- `Unit tests` job：`test` 任务 Gradle job summary = ✅；checkout 实测 SHA = `04efed3950e558a071e5b6b8b2750e1c4542192a`（= 本波 tip）。
  ⚠️ job log 首部的 `Commit: c3d12f33…` 是 **GitHub Runner 镜像版本**（两份 job log 均有），**非仓库 commit**，勿误读。
- ⚠️ **观测面缺口（修正 W51 结论）**：`GITHUB_STEP_SUMMARY` 仍无 API；但 **PAT 有 `actions:read` 时 `GET /actions/jobs/{id}/logs` 返回 200 可取**（仓库外 `ghapi.sh` 内置的旧 PAT 已失效 401 ⇒ **需换新 PAT**）。⇒ W51 记的「CI 内 arch-guard `OK` 行数 / selftest `PASS=` 不可程序化读取」**已不再成立**。

> 本节已在推送后**立即回填**（W52 教训：run-id 回填迟到两次）。

---

## 九、commit / push 纪律回填（本波由主理人执行）

- 提交身份：`-c user.name="Rickeal-Boss" -c user.email="15992567646@139.com"`（**绝不用 noreply**）。
- PAT 仅以 URL inline 一次性使用，**绝不落盘**；推前 `git push --dry-run` 核。
- 推送后回填 §八 run-id，并复核双 workflow 结论。
