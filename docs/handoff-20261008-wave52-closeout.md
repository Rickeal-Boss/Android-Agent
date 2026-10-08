# Wave 52 收官交接（improve 分支）

> **唯一权威交接文档**。逐波细节看 `handoff-*.md`；README 台账是特性/路线图的权威版。
> 本文件回答三件事：**W52 做了什么 / 留下了什么判据 / 下一个人从哪接手**。
>
> 基线：W51 收官 `c2c83c0`（已推，CI 双绿）｜本波 tip 见 §八

---

## 一、W52 总账

### 一句话
**清观测盲区 + 挂账对账 + 小件行为修复**：B1「审批等待停表」（外部复审点名的行为级 P2）落地、B2 层3 判据首次有正向观测面、V-2 补齐 H-A 观测面；守卫 **23 → 25 项**、selftest **52 → 59**、`fulltest.sh` 假全量**对症**加固（原报告因果不成立）、CI 用例数基线 598 → 619；台账聚合计数三项全错已订正；**真机复验 P1 fold 路径（折叠前 3 → 折叠后 1）非 vacuous**。

### 5 个 commit（`c2c83c0` → tip）

| commit | 内容 | 性质 |
|---|---|---|
| `29d573f` | **B1** 审批等待停表（`pausedNanos` 从墙钟预算剔除 + 子 run 继承墙同步延长）+ **B2** 层3 正向日志 + 纯函数 `effectiveElapsedMillis` + 2 例边界单测 | 行为修复 |
| `d7be800` | **V-2** fold 观测面（折叠前/后元素数）+ **C1** 订正 `:442` 不成立的 KDoc 背书 | 观测 + 注释 |
| `8374ba6` | **C3** 6 个视觉预设标注「图片输入尚未验证」 | 文案 |
| `b4e77e6` | **CI**：守卫 #24（fold 收口不变式）+ #25（引擎行数）+ `fulltest.sh` 假全量对症加固 + 基线 619 | 工具链/CI |
| `97bd45e` | **docs**：台账聚合计数订正 + 多模态 P1 独立挂账 + CI 数值缺口记录 | 文档 |

⇒ **arch-guard 23 → 25 项、selftest 52 → 59 例**。
⇒ **全量单测 619 → 621**（+2：`WallClockEvidenceTest` 边界例）；唯一失败仍是
`SandboxFileScannerTest.kt:184`（Windows 符号链接，**既有基线**，Linux CI 不受影响）。

---

## 二、🔴 本波最重的技术内容

### 2.1 B1 审批等待停表（行为级）

**问题**：墙钟硬预算 `hardDeadlineNanos`（`AgentRunner.kt:1280-1281`）自 run 起点起算，判定在**轮头**（`gateRoundHead`）；而工具审批等待发生在**轮中**（`onApprovalRequested`）⇒ 用户审批犹豫的时长被全额计入任务耗时预算，批准后下一轮轮头可能立即 HARD 熔断（W51 有一个污染 run 正是此现象：授权对话框挂起 ~5min）。

**修法**：
- `RunState` 加 `var pausedNanos`；审批调用点 `try/finally` 累计（异常 / `CancellationException` 路径同样累计）。
- `gateRoundHead` 的 `remaining` 改为 `(hardDeadlineNanos + pausedNanos - now)`；evidence / 日志 / 诊断卡耗时统一走 `effectiveElapsedMillis()`。
- **子 run 继承墙**同步加 `parent.pausedNanos`（父 run 停表后子 run 不按未延长的墙判）。
- 算术抽**文件级纯函数** `effectiveElapsedMillis(elapsedMillis, pausedNanos)`（含 `coerceAtLeast(0)`）+ 2 例边界单测。
- ⚠️ **取舍（如实申报）**：停表**只**排除审批挂起时长；模型生成与工具执行时长**照常计入**。

**已证明的不变量**（审查席精确推演）：父 `pausedNanos` 在子 run 的 `remaining` 中**只出现一次**（子 = 父 remaining + 子自身 paused）⇒ **不重复计入**。

### 2.2 B2 层3 判据首次有正向观测面

**问题**：`nativeToolsDrifted` 仅在**不一致时**落 warn，且 `sessionDiagnostics==null` 时恒返 false ⇒ 无正向日志 ⇒ 离线永远判不了「引擎侧原生工具通道是否真失效」（层3 判据恒 `⛔`）。
**修法**：每 run 落一条 info 报 `useNativeTools=<bool>` 与引擎 `nativeToolChannel=<bool|null>`（诊断未产出报 `null`，**不冒充**一致）。
**真机实证**：原生态 `useNativeTools=true，引擎 nativeToolChannel=true`；文本协议态 `useNativeTools=false，引擎 nativeToolChannel=false`（未冒充）。

### 2.3 V-2 H-A 观测面

主折叠点（`LiteRtLmEngine.kt:1766`）在**折叠前元素数 ≥2** 时落 info「多元素 content 下发：折叠前 N → 折叠后 M」（单元素不落，防刷屏）。为 H-A（native 是否只在恰 1 元素时收敛为 string）提供日志侧观测面。
**真机实证**：文本协议下模型一次发 3 个 tool_call ⇒ `折叠前 3 → 折叠后 1`（命中 3 次），且 `Failed to apply template` = 0。

### 2.4 ⚠️ 明确**不覆盖**（沿用 W51 申报）

含 `ImageBytes`/`AudioBytes` 的消息 content 恒 ≥2 元素 ⇒ **仍可能触发同一模板错误**；自愈（仅重建会话）**不改变 content 结构** ⇒ 可能反复炸。已补**独立挂账**（§五.2），6 个视觉预设已加标注。

---

## 三、真机验证结果（OPPO PDRM00 / Android 13 / serial `13309cc8`）

> 被测产物：W52 debug APK `121,737,470 B`（`adb push` + `pm install -r` = Success；装机回读
> `lastUpdateTime = 2026-10-08 18:50:24`）。取证档 `/data/local/tmp` 同源全量 logcat（**不过滤**，
> 5.5 MB；先验含 native 行）。模型 Qwen2.5-1.5B / CPU / thinking=OFF。

| 判据 | 结果 | 证据 |
|---|---|---|
| **P1 回归（fold 真实行使）** | ✅ **非 vacuous** | 文本协议（原生通道 OFF）下模型一次发 3 个 tool_call（`current_time,file_write,current_time`）⇒ `多元素 content 下发：折叠前 3 → 折叠后 1`（命中 **3 次**）；`Failed to apply template` **0 命中** |
| **B2 层3 正向日志** | ✅ | 原生态 `useNativeTools=true，引擎 nativeToolChannel=true`；文本协议态 `useNativeTools=false，引擎 nativeToolChannel=false`（**未冒充**） |
| **B1 审批停表路径** | ✅ 行使 | 审批卡弹出并被批准 **5 次**（`工具「file_write」请求授权`）；run 继续执行；**WallClockBudget 硬预算熔断 = 0** |
| **沙箱防线** | ✅ 正面确认 | `file_write` 绝对路径被正确拒绝（`拒绝绝对路径：请使用相对沙箱根目录的路径`，`ok=false`） |
| **无本 app 崩溃** | ✅ | 3 条 `FATAL EXCEPTION` 均属 `uiautomator` 进程（PID 22083/23058/25282 ≠ app 31605，异常为 `UiAutomationService already registered`） |

### 3.1 ⛔ 未行使 / vacuous（如实申报，**不记通过**）
- **B1 的「长审批停表」效应**：审批由自动脚本在 ~7 秒内批准，**无可观测的长等待** ⇒ 停表带来的「不提前熔断」**未被行使**（代码路径已行使、算术已单测覆盖，但真机未构造长审批剧本）。
- **`WallClockEvidenceTest` 新增 2 例**仅覆盖纯函数算术（`paused=0` / 正常扣减 / `paused>elapsed` 钳 0），**不覆盖真机时序**。

### 3.2 还原状态（收尾回读）
原生工具通道 = **开**（测试期间曾关闭以行使文本协议，已还原；settings pb 复核 `nativeToolChannel:true`）；`backend=CPU`；`thinking=OFF`。

---

## 四、坑复盘（本波最值得继承的 5 条）

### 1. 🔴 外部审查的「因果描述」再次不成立：`fulltest.sh` 假全量（本波第 30 处候选）
报告（复审14 §四.1 / 第八轮 新-1 / 第二审 §4.5）均称「漏设 `JAVA_HOME` ⇒ gradle 未跑，脚本仍汇总上一批残留 XML，输出 `tests=389`/5 模块」。**核验不成立**：`fulltest.sh:71-76` 的 `rm -rf test-results` **自脚本创建（`6790cdd`）起就在 gradle 之前**；方案席本会话**空环境实测**：植入残留 XML → 跑默认模式 → 残留被删 + gradle 快速失败（rc=1）+ 打印 `::warning::` + `exit 1` ⇒ **无假全量**。
⇒ **真实 fail-open 面收窄**为「`rm -rf` 静默失败（`2>/dev/null || true` 吞错）+ gradle 也失败」的组合。修法据此**对症**（rm 失败可见化 + mtime 范围 + 模块数告警），而非报告的「前置标注」。
⚠️ 附带教训：**报告之间会互相引用同一个不成立的因果**（三份报告同源）⇒ 引用前必须回源码/实测。

### 2. 🔴 计数链三层断点：声明层 vs 实数层
- **README 聚合句**：`README.md:336` 原「台账已记 31 条 / 27 ✅ / 4 ⛔或⚠️部分」——**总数 / ✅ / 非✅ 三项全错**。
- **逐条实数**（`awk -F'|'` 计结论列）：**35 行 = 28 ✅ / 5 ⚠️ / 2 ⛔**。
- **`build.yml` 基线**：W51 自己建的 soft-check 基线仍为 `598`，但 W51 后实测 **619** ⇒ 「用例数回退 21 例都探测不到」。
⇒ 三处**独立翻车**，同族根因 = **聚合口径没有守卫**。本波订正实数 + 基线 619；**聚合句守卫（A5）未做**（对文案格式脆弱，留 W53）。

### 3. 🔴 同文件注释自相矛盾（第 N 次）
本波订正了 `handoff §五.13` 的台账数字，却**漏了同一文件的 §五.5**（仍写「31 条 / 27 / 4」）⇒ 审查席按「同文件 grep」独立发现（**派单清单本身不完备**的又一实证）。
⇒ 纪律：**改一个数字必须全仓 grep 该数字的所有形态再改**（本波已执行：`31 条 / 27 ✅ / ≈32 条` 全仓 grep）。

### 4. ⚠️ 测试通过 ≠ 口径被验证（vacuous 判定）
`WallClockEvidenceTest` 4 例**全绿**，但它是**纯函数文案测试**（入参为字面量），**从不触碰** `pausedNanos`/`effectiveElapsedMillis()` ⇒ B1 停表算术**零测试覆盖**。审查席据此判「B1 口径未被测试锁定」，开发席随后抽纯函数补 2 例。
⇒ 「跑完 + 无信号 + 无法证明失效」= `⛔`，**不得**读成「已回归保护」。

### 5. 🔴 守卫只钉「结构」，但**扫描面边界必须显式声明**
守卫 #24 宣称冻结「`Message.user` 下发点」这一**通用**不变式，实现却**只扫 `LiteRtLmEngine.kt` 单文件**。当前全仓仅此文件出现 ⇒ 成立；但将来别处新增 `Message.user(...)` 会**静默漏报**。已补边界声明注释。
⇒ 守卫的「覆盖范围」与「不变式范围」不一致时，必须在注释里写明边界。

---

## 五、挂账台账（W53 起）

> 权威版仍是 README 台账 + 本文。每条给「最早可启动波次 + 重启前提」。

### 🔴 优先（W53 首批）

1. **多模态消息的 P1 未覆盖**（本波新增独立条目，与 H-A 定案**分列**）：含 `ImageBytes`/`AudioBytes` 时 content 恒 ≥2 元素 ⇒ 仍可能触发模板 `+` 错；自愈（仅重建会话 + 清水印）**不改变 content 结构** ⇒ 每次重发同一多模态消息都可能再炸（文档原写的「防循环」只防「反复**证伪**」，**不防「反复炸」**）。
   **用户可达**：6 个视觉预设（label 含「· 视觉」，全 `image=true`）。
   **重启前提**：视觉容器 `chat_template` 取证（`adb dd` + `minijinja` 离线复现，复用 W51 方法，**无需真机窗口**）——若模板不用 `+` 拼 content ⇒ 销账；若用 ⇒ 升级 P1，评估「预设标注 / 独立 content 结构 / 上游缺陷」。
2. **B1 真机长审批剧本**：验证「长审批（如 3-5 分钟）后不被 HARD 熔断」——本波只行使了审批路径，未构造长等待。
3. **台账聚合句 awk 守卫**（A5，本波有意未做）：解析 `docs/10-device-acceptance.md` §11.0.1 结论列三态，与 `README.md` 聚合句比对，不等则判红。**前提**：容忍「≈N 条」这类非精确表述，或先统一为精确口径。
4. **README:324（W50 段）聚合计数**：`§11 真机回收 9 ✅ / 4 ⛔` 与逐条台账（W50 段 13 行 = 8 ✅ / 3 ⚠️ / 2 ⛔）**不符**——本波未改（属 W50 叙事段，非聚合句），留 W53 统一口径。
5. **层3 判据补 `useNativeTools` 日志**：✅ **本波已落地**（B2）；W53 可据真机日志正式回收层3（原恒 `⛔`）。

### 🟡 常规（W51 挂账顺延，未动项）

6. **`DeepSeek-R1-Distill-Qwen-1.5B` 被 `inferFamily` 误判 `thinking=false`**（W48 让 `enable_thinking` 恒发 ⇒ 可能关掉其推理）。**重启前提：拿到该容器 + 真机验 AUTO。不得无容器盲改。**
7. **真机验收积压**：台账 35 条（28 ✅ / 5 ⚠️ / 2 ⛔）。未覆盖项：通知档B｜Gemma 两档｜层5「压缩触发重建」子路径｜记忆磁盘满·只读｜W37 UI 手感｜lint gate｜W38 行为变更｜W40 验收面｜F4 三级文字 token 统一。
8. **F4 三级文字 token「同角色不同色」6 处**：先立「语义角色→颜色」单一映射表再全仓对齐。⚠️ 深色 `onGlassSubtle`(0x80) 是**脆弱达标**（余量 <10%）⇒ **不要降 alpha**。
9. **数据回填 / 阈值回填**：口径见 W49 交接；阈值需 ≥2 个坏容器样本。
10. **`TokenUsage.estimated` 治根字段**（唯一能长期不撒谎的口径方案）。
11. **通知文案「渠道」误导**（`GenerationNotifier.kt` + 枚举 `CHANNEL_DISABLED`）。
12. **法务** `TODO(legal)`×4 / `termsVersion`（**必须先于任何法务文本替换落地**）。
13. **上游/已知限制**：`gemma-4-E2B-it-gpu`（GPU 输出乱码；CPU init `NOT_FOUND`）｜`MiniCPM-V-4-int8`（`Unsupported model type`）｜MiniCPM5 OFF 态规划外溢（2B int4 固有，**别动 `thoughtChannelDefsFor`**）。
14. **`main` 分支快照声明过时**（实际 improve 领先 main **491** commit）｜**litertlm bump 评估立项**｜**打 tag**（顺序：法务清零 → tag）。
15. **`ChatRunCoordinator.kt` 余量 22 行**（1278/1300）：**W52 无功能回灌进它** ⇒ 本波未拆分；W53 若有功能落此文件，须**先拆分后回灌**（触顶 = 启动评审，非改阈值）。

---

## 六、外部报告对账（6 份）

| 报告 | 审的修订 | W52 处置 |
|---|---|---|
| 复审14（三线融合） | `c2c83c0` | ✅ 采纳：P1 行使覆盖补齐（真机已行使 fold 3→1）、台账计数订正、`build.yml` 基线 619、审批停表、H-A 主动捕获（V-2）。**未采纳**：观测面 HTML fallback 固化（GitHub 平台限制，本仓改不了）。⚠️ **其 §四.1「fulltest 假全量」因果不成立**（见 §四.1） |
| 深审（Wave51） | `157d7ff` | ✅ 采纳：多模态挂账 + 视觉预设标注。⚠️ **其「视觉预设 2 个」不成立**（实为 6 个）；`ModelHeuristics.kt:100/110/130` 引用不成立（那是 Gemma 三族） |
| 复检（第二轮） | `157d7ff` | ✅ 采纳：视觉容器取证列入挂账重启前提、`:442` KDoc 订正、多模态挂账。⚠️ **其「视觉模型 5 个」需修正**（实为 6 个，含 LFM2.5-VL 450M） |
| 第八轮深审（W49-51） | `c2c83c0` | ✅ 采纳：守卫 #24（第 5 处 fold 守卫）。⚠️ **其「新-1 更硬判据 = gradle 前 rm -rf」已是现状**（非新建议）；**新-2 重建限次**本波**未做**（风险高于收益，留挂账） |
| v14 | `c2c83c0` | ✅ 采纳：V-2 自愈 warn 带折叠前后元素数、fulltest 模块数硬闸（改为告警，见 §四.1）、`:442` 订正 |
| 第二审（W34-51） | `c2c83c0` | ✅ 采纳：引擎文件行数守卫（#25）、层3 日志。⚠️ **其「@Test 624」不成立**（实测 619）；**其「Coordinator 先拆分」**本波不适用（无功能回灌进它） |

---

## 七、下次接手须知

1. **推送纪律**：`GIT_TERMINAL_PROMPT=0` + `-c credential.helper=`（空）**两者都要**（否则 GCM 无凭据**静默挂起**）；`-c http.sslVerify=false`（绕 MITM 的 `CRYPT_E_NO_REVOCATION_CHECK`）；**PAT 只以 URL inline 一次性使用、绝不落盘**，用完复位；推前 `git push --dry-run` 核（`git ls-remote` 匿名读 public 库会**假绿**）。
2. **静态闸门基线**（改动前）：`arch-guard` **25 项全 OK**、`arch-guard-selftest` **PASS=59 FAIL=0**（本机 ~22 min ⇒ 给 ≥600s 或 `run_in_background`）、`scripts/fulltest.sh` = `tests=621 failures=1`（唯一失败 = `SandboxFileScannerTest.kt:184` Windows 符号链接，**既有基线**）。
3. **跑全量单测必须注入 `JAVA_HOME`/`ANDROID_HOME`/`GRADLE_USER_HOME`**（否则假全量），并**核对模块数 = 9**（本波 `fulltest.sh` 已自带「覆盖模块数：9（预期 9）」输出 + mtime 范围）。
4. **纯文档提交 ⇒ 两条 workflow 都 0 run** ⇒ 需出包必须 `workflow_dispatch`。
5. **设备**（OPPO PDRM00 / A13，serial `13309cc8`）：adb = `_j2env/sdk/platform-tools/adb.exe`（**不在 PATH**），Git Bash 需 `MSYS_NO_PATHCONV=1`。**装机必须** `adb push` + `pm install -r`（直接 `install -r` 报 `Failure [-99]`）。
   ⚠️ **本波新坑**：`adb` 命令**并发**调用（如后台 logcat + 前台命令同时启动 daemon）会让设备瞬时 `not found` ⇒ **串行执行**，必要时 `adb kill-server && adb start-server` 恢复。
6. **UI 自动化**：`_ci-tools/_w49_ui.py`（`dump` / `nodes` / `tap`）；自动授权 `_ci-tools/_w51f_autoauth.sh <max_s> <log>`。⚠️ `uiautomator dump` **对 Compose 的 bounds 上报不可靠**且会返回被遮挡节点 ⇒ 判「尺寸/可见性」**必须截图目视**。⚠️ **uiautomator 进程自身会抛 `UiAutomationService already registered` FATAL**（PID ≠ app）⇒ 判 app 崩溃前**必须核 PID**。
7. **中文输入不可行**（`input text` 非 ASCII 在设备侧 NPE）⇒ ASCII 题面，空格用 `%s`。
8. **logcat 双档**：涉 native 判据必须落**不过滤**全量档，且**先验该档确含 native 行**才可说「0 命中」有意义。
9. **`_plans/` 在 git 仓库外** ⇒ 设计/审查报告不 commit；关键结论**必须折进仓库文档**才算落档。
10. **判定三态纪律**：`✅ 通过` / `⚠️ 部分`（不得整体记 ✅）/ `⛔ 不适用`（**不是通过**）。**凡「目检」判据，报告里必须逐条抄出被目检的原文**；**没触发判据写「vacuous/未行使」**。
11. **关闭原生工具通道**（行使文本协议 fold 路径用）：设置页 → 「原生工具通道」开关（`SettingsScreen.kt:705`）；**测完必须还原为开**（settings pb 复核 `nativeToolChannel:true`）。

---

## 八、CI run id

推送 `c2c83c0..97bd45e`（**5 commit**，fast-forward）后**两条 workflow 自动触发**（本波含 `scripts/**` / `.github/workflows/**` / `core-*/**` / `feature-*/**` 等非文档改动，无需 `workflow_dispatch`）：

| workflow | run id | 结论 | 关键 job |
|---|---|---|---|
| **Build** | **`37768242503`** | ✅ **success** | `Lint (baseline gate)` / `Assemble Debug (JDK 21)`（含 Architecture guard + 自测）/ `Unit tests` **三 job 全绿** |
| **Release** | **`37768242425`** | ✅ **success** | `Build & (optionally) sign release` 全步骤 success；tag 相关 job = skipped（分支推送非 tag，符合预期） |

### 8.1 守卫网在 CI 上的实跑（从 job 日志正文取，非只看 job 结论）
- **Build `Assemble Debug` job**：`OK  ` 行 = **25 条**（首条 `litertlm 仅存在于 core-engine`、末条 `LiteRtLmEngine.kt 总行数 ≤ 2400`）；末行 `架构守卫全部通过。`；`自测结果：PASS=59 FAIL=0`。
- 新增守卫在 CI 实跑可见：`OK  LiteRtLmEngine.kt fold 收口不变式`；selftest `case29 第 2 处 Message.user 未包 fold (第 24 条)` / `case31 超行数上限 (第 25 条)` / `case32 缺失` **均判红且红来自真命中**。
- 该 job 内**真 `FAIL [` = 0**、**行首 `::error::`/`::warning::` 真注解 = 0**。
- **Unit tests job**：`BUILD SUCCESSFUL`（CI 在 Linux 上跑，`SandboxFileScannerTest` 符号链接用例**通过** ⇒ 本地那 1 个失败确认是 **Windows-only 既有基线**）。
- ⚠️ **观测面缺口（沿用 W51 结论）**：`GITHUB_STEP_SUMMARY` **无 API** ⇒ CI 的「用例数数值（621）」**不可程序化读取**（只能判「unit-tests job 步绿」这一层）⇒ 属 `⛔ 不可程序化核验`，**不得**据此推断「CI 已守住用例数不低于基线」。

> 本节已在收官提交前回填（W51 教训：run-id 回填迟到两次 ⇒ 本波改为**收官前回填**）。
