# W57 波次收官交接（Wave 57 Closeout）

> 基线：W56 收官 `00d7356`（W56 共 4 commit）｜本波 tip = `improve` 分支最新（**CI 触发推送 = 推送后回填**）
> 前置文档：`docs/handoff-20261009-wave56-closeout.md`（W56 逐项记录，§六 = 本波挂账来源）
> 方案：`_plans/w57-phase1-strategy.md`（仓外，方案席沈思远）｜真机取证：`_litert_forensics/_w57_l3/L3-RESULT.md`（仓外）

---

## 一、一句话 + 关键现实

**W57 = W56 审查 P2 收口（韧性 store 读回接线 JVM 可测化）+ 守卫 #28 落地 + 多模态容器真机扩容两臂（异族 + 同族 fixB 双通过）+ W57 构建真机冒烟。**

关键现实（动手前必读）：
1. **P2 的病灶是「承重语义藏在 native 重类里 ⇒ JVM 不可测」**。解法沿用本仓既有范式（W54 `childDeadlineNanos`）：把 OR/max 单调合并**外提为纯函数** `mergeResilienceState`，引擎两处读回点改为复用 ⇒ 语义获 JVM 覆盖；**接线存在性**另由守卫 #28 钉（语义 + 接线双钉，**二者互补、都不得夸大为全覆盖**）。
2. **读回接线不止一处**：除 `adoptResilienceFromStore`（建会话）外，`generateStream` 的**软熔断闸门**也直读 store 取 `max(实例, store)`。W57 把两处**统一到同一纯函数** ⇒ 消除「同一 cid 两处口径各写、改一处漏一处」的漂移面。
3. **多模态族覆盖扩到 3 个容器**：LFM2.5-VL-450M_int8（W56）+ SmolVLM2-500M（W57，**异族**）+ LFM2.5-VL-1.6B_int4_fixB（W57，**同族 fixB**）。**同族 ≠ 同模板** 的教训仍适用 ⇒ 剩 2 个容器不外推。
4. **litertlm bump 仍挂起**：上游最新 tag 仍为 **v0.18.0**（`b2f686e2e`，本波复查无新 tag）⇒ 前置④仍红，bump 不动。
5. 🔴 **本波新踩的取证事故（跨波复用）**：`adb shell <cmd> > file` 经 pty **把 LF 翻成 CRLF** ⇒ 用它读二进制（datastore pb）备份即被污染，写回后 app `CorruptionException` 启动即崩。**读二进制一律 `adb exec-out`**；写二进制走 `adb push` → `/data/local/tmp/` → `run-as cp`（`run-as` **读不到** app 外部 files 目录）。

---

## 二、改动清单（3 commit；本提交 = docs 回填）

| commit | 性质 | 内容 |
|---|---|---|
| `c1c4eb9` | feat(engine) | **读回接线可测化**：`EngineResilienceStore.kt` 新增 top-level `internal fun mergeResilienceState(instance, snapshot)`（OR/max 单调合并，KDoc 声明不变式与边界）；`LiteRtLmEngine.adoptResilienceFromStore` 改为调用它（**保留 R1**：warn transition 用**合并前**值，避免每次 adopt 刷日志）；`generateStream` 软熔断闸门同步复用（`mergeResilienceState(...).templateRebuildCount` ≡ 原 `maxOf(实例, store)`）。新增 `EngineResilienceMergeTest` **8 例**。P3：`ConcurrentHashMap` 改 import 风格；store KDoc 补「cid 键隔离 vs 单调合并」张力边界句。`build.yml` baseline **627 → 635** |
| `6dbc2fa` | test(guard) | **守卫 #28**「韧性 store 双写双读接线」：adopt/persist 调用点各 1（计数法，排除 def 行与注释位）+ 读回行号 < 首个 `nativeToolChannelActive()` 代码位调用行号（钉读回顺序，**脆性边界已在注释显式声明**）；文件缺失判红（不 fail-open）。selftest 新增 **case36/36b/37/37b/38**（红=删 adopt 调用 / 红=行序颠倒 / 绿=原样 + KDoc 提及仍绿）+ 脚手架 fixture 补 adopt/persist/通道调用 |
| `7c075e3` | docs | 本交接文档 + README W57 波次段 + 台账 #41/#42 + 取证落档 |

⇒ **@Test 实数 = 635**（W57 +8 例 `EngineResilienceMergeTest`），`build.yml` baseline 同步 627 → 635（#26 双向同步）。

---

## 三、真机验证（本波核心判据）

### 3.1 ✅ 台账 #41：多模态 L3 扩容两臂（异族 + 同族 fixB）

| 臂 | 容器 | 族 | 容器 SHA256（本地单源） | 元素数 | 模板失败 | FATAL | settled | 内容判别 | 判定 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | `SmolVLM2-500M` | SmolVLM2（**异族**） | `b808b328…60ad0` | 2→2 | **0** | 0 | ModelStopped | 红底 + **"37"** | ✅ |
| 2 | `LFM2.5-VL-1.6B_int4_fixB` | LFM2.5-VL（同族 fixB） | `79ca9db8…d565c` | 2→2 | **0** | 0 | ModelStopped | 红/白/黄 + **"37"** | ✅ |

- 共同前置：`adb push` 直放 `files/Download/` → 冷启动自动登记（模型页 6 → 7 → **8**）→ `vl` 文件名 ⇒ 图片能力位自动命中（附件菜单「只能添加图片」）→ SAF 选图（先 `content call --method scan_file`）→ ASCII 题面 → journal 判 settled。
- 判据全命中：`多元素 content 下发：折叠前 2 → 折叠后 2；Text=1/Image=1`｜`Failed to apply template` = 0（臂1 native 行 220 / 臂2 native 行 124，**先验满足** ⇒ 「0 命中」有意义）｜`FATAL EXCEPTION` = 0｜`settled = ModelStopped`。
- **内容判别**：臂1 读出红底 + 数字 37（**优于 W56 的 450M**，后者读不出数字）；臂2 读出**三色全中** + 数字 37。
- 臂2 额外价值：上游 **LiteRT-LM#3246**（「只见图片顶部 1/4」）在 **fixB 变体上未复现**（整幅布局被正确描述）。

### 3.2 ✅ W57 构建真机冒烟（验证 store 改动无副作用）

- APK = W57 工作树构建（dex 实测含新符号 `mergeResilienceState`）；`pm install -r` Success（OPPO A13 走 push + `pm install`，直接 `install -r` 报 `-99`）。
- 冷启动 `FATAL EXCEPTION` = 0；active model 与模型登记跨重装保留。
- **文本 run**（行使改写后的 `adoptResilienceFromStore`）：`Reply with one word: OK` → 回复 `OK`（in 589/out 1）；journal 新目录生成；`FATAL` = 0。

### 3.3 未覆盖（如实申报）

- 剩 `Qwen2-VL-2B` / `LFM2.5-VL-3B fixB` 两容器端到端**未验**（L1 模板安全已知；**不外推**）。
- 守卫 #28 判据③（读回行序）**对重构敏感**（已在注释声明）；合并语义的「warn transition 触发」JVM 侧**不测**（`AgentLogStore.warn` 副作用不在纯函数内），靠人工审查 + 守卫钉调用点。

---

## 四、技术内容：读回接线可测化（治 W56 审查 P2）

- **问题**：W56 落地的 `adoptResilienceFromStore` 单调合并（OR/max）是 P2#1 的承重语义，但 `LiteRtLmEngine` 是 native 重类、JVM **不能**实例化 ⇒ 该语义零 JVM 覆盖（W56 审查 P2）。
- **修法（方案 A）**：外提纯函数 `mergeResilienceState(instance, snapshot)`（放 `EngineResilienceStore.kt`，与 data class 同址；**不占**引擎 god-file 余量）。引擎两处读回点改调用：
  - `adoptResilienceFromStore`：**保留 R1** —— warn 的 transition 判据用**合并前**的实例值（`before`），与改前 `snapshot.x && !instance.x` 逐语义等价；
  - 软熔断闸门：原 `maxOf(templateRebuildCount, storeCount)` → `mergeResilienceState(...).templateRebuildCount`（逐字节等价；`EngineResilienceState` 默认 `nativeToolsRejected=false` 不影响该处取值）。
- **覆盖矩阵（8 例）**：M1/M2 OR 双向单调、M3/M4 max 双向单调、M5 双字段独立、M6 空 store 默认不拉退、M7 幂等、M8「同 store 跨引擎实例」组合（复刻 `adopt=merge(实例, store.read)` / `persist=store.write(实例快照)` 数据流）。
- **口径申报（关键）**：M1–M8 钉的是**合并语义**，**不是**引擎的字面调用序列（那需实例化引擎）⇒ 与守卫 #28 构成「语义 + 接线」双钉。**不得**声称任一为全覆盖。
- **回滚**：单 commit revert（纯增量，引擎字段与写点纪律未变）。

---

## 五、坑复盘（真机操作，跨波复用）

1. 🔴 **`adb shell <cmd> > file` 经 pty 做 LF→CRLF 翻译**：读二进制（datastore pb）会拿到污染档；写回即 `CorruptionException`（app 启动崩）。**读二进制用 `adb exec-out`**（不经 pty）。复原法：`\r\n → \n`（注意区分**孤立 CR** —— 可能是合法长度字节 0x0d）。
2. 🔴 **`run-as` 读不到 app 外部 files 目录**（`/storage/emulated/0/Android/data/<pkg>/files/...` ⇒ `Permission denied`）⇒ 二进制写回走 `adb push` → **`/data/local/tmp/`** → `run-as ... cp`。
3. 🔴 **本沙箱 adb server 每条命令后即退出**（每条命令都打印 `daemon not running; starting now`）⇒ **host 侧后台 `logcat` 必被打断**。**正解 = 设备侧落盘**：`adb shell "nohup logcat -b all -f /data/local/tmp/x.log &"`，事后 `adb pull`。
4. 🟡 SAF 图片选择器**单击可能进预览**而非选中（非确定性）⇒ 判据以「附件缩略图出现在输入栏上方」为准。
5. 🟡 键盘弹出后发送键上移（本次 y 1968 → 1352）⇒ 判据 = 发送后 journal 新目录存在。
6. 🟡 `fulltest.sh` 的 `BUILD FAILED` 只是既有 `SandboxFileScannerTest`（Windows 符号链接）基线失败 ⇒ 先看汇总 `failures=N` 与模块数（9）再判。

---

## 六、挂账（W58 起；**权威版**见本节 + README）

1. **多模态剩 2 容器端到端**：`Qwen2-VL-2B`（异族）/ `LFM2.5-VL-3B fixB`（同族最贵）；剧本复用 `_litert_forensics/_w57_l3/L3-RESULT.md` 前置链 + 0–10 判据。
2. **Bump 重启监视**：上游 tag 季扫（本波复查仍 **v0.18.0**，无新 tag ⇒ 四前置④仍红）。
3. **软熔断 K 回填**：待「fold 失效 + 连续 M≥2」分布数据（bump 场景）。
4. **N-W3** 预设 `evidenceLevel` 数据化 + 守卫（并入预设扩容波；「图片入口硬闸门」并此）。
5. 其余沿用 W56 §六.7：`DeepSeek-R1` 误判 `thinking=false`（无容器不得盲改）｜数据/阈值回填｜`TokenUsage.estimated` 治根｜通知文案「渠道」误导｜法务 `termsVersion`（先于法务文本替换）｜**0 tags**｜main 快照过时｜`ChatRunCoordinator` 余量 22 行（先拆分后回灌）｜F4 三级文字 token 统一｜`fulltest.sh` 接进 CI 运行步。
6. **W57 审查 P3（不阻塞，挂 W58）**：① `cid == null` 防御分支零 JVM 覆盖（需引擎可实例化或另设 seam，属既有取舍）；② 守卫 #28 判据③（读回行序）**对重构敏感**（已注释声明，重构时人工确认）；③ 合并语义的「warn transition 触发」仅人工审查覆盖（`AgentLogStore.warn` 副作用不在纯函数内）。

---

## 七、下次接手须知

1. **静态闸门基线（本波后）**：`arch-guard.sh` **28 项**（新增 #28）、`arch-guard-selftest.sh` **PASS=70 FAIL=0**（新增 case36/36b/37/37b/38 = **+5**）、`fulltest.sh` = **tests=635 failures=1**（唯一失败 = core-data `SandboxFileScannerTest` Windows 符号链接，**既有基线**；判据 = 无新增失败 + 模块数 9 + XML mtime 全为本轮）；`LiteRtLmEngine.kt` = **2291 行**（阈值 2400，余量 109）。
2. 🔴 **全量单测必须注入 `JAVA_HOME`/`ANDROID_HOME`/`GRADLE_USER_HOME` 并核对模块数 = 9**；`--rerun-tasks` 才是真跑。
3. 🔴 新分支第一件事核 workflow 触发列表；docs-only 提交 0 run。
4. 🔴 真机判据：涉 native 落**不过滤**全量档且先验含 native 行；`uiautomator` FATAL 核 PID；adb 串行；发送后必须核 journal 新目录。
5. 🔴 **本沙箱读二进制用 `adb exec-out`、写二进制走 push + `/data/local/tmp` + `run-as cp`**（§五.1/§五.2）。

---

## 八、CI run id

> **推送后回填占位**（W52 教训：run-id 回填须在推送后立即回填）。

| workflow | run id | 结论 | 关键 job |
|---|---|---|---|
| **Build** | `37937473475` | ✅ success（head `7c075e3fb`） | Lint / Assemble Debug / Unit tests 全 success |
| **Release** | `37937473619` | ✅ success | — |

**CI 内实测（job log 直读，PAT `actions:read`）**：
- `Assemble Debug` job：`架构守卫全部通过。`（**OK 计数 = 28**，含新增第 28 条）；`自测结果：PASS=70 FAIL=0`（selftest case36/37 红面在 CI 内亦 PASS）。
- `Unit tests` job：`baseline=635`（soft-check 未触发 ⇒ tests ≥ 635）。
- 本文档 commit 为 docs-only（`paths-ignore` 覆盖 `docs/**` + `*.md`）⇒ 预期 **0 run**。

---

## 九、外部报告对账

- 方案席两处**订正主理人派单描述**（纪律正面）：
  1. 「guard #24 判据 = 赋值形态」**不成立** —— 实为**计数法**（`arch-guard.sh:540-541` `fold_n == msg_n`）；
  2. 派单要求读的 `references/cam-p-knowledge-base.md` **在本仓不存在**（已 `find` 亲核）⇒ 改以交接文档 + 源码回核为据。
- 审查席报告（`code-quality-reviewer`，2026-10-09）：**结论 = ✅ 通过**（**0 P1 / 0 P2 / 3 P3**）。独立回源码核验要点：
  - **A 行为等价性全过**：`adoptResilienceFromStore` 改写**保留 R1**（transition 用合并前值）；OR/max 方向正确；熔断闸门改写**逐字节等价**（`EngineResilienceState` 默认 `nativeToolsRejected=false` 不影响该处取值）；读回顺序未变；无新写点。
  - **B 用例有效性全过**：8 例**真调** `mergeResilienceState`（非自证）；无 vacuous；测试方法均 block-body、无反引号非法字符（独立 grep 核实）。
  - **C 机械口径全过**：`@Test` 代码位实数 **635** == `build.yml` baseline；守卫 #28 **非 fail-open**（文件缺失判红）；selftest 红/绿两面齐（含 KDoc 提及仍绿）。
  - **D 独立复跑**：`arch-guard.sh` **28 项全 OK**；`core-engine:testDebugUnitTest --rerun-tasks` **118 例 0 失败**；`arch-guard-selftest.sh` **PASS=70 FAIL=0**。
  - **P3（不阻塞）**：① 文档静态闸门读数需同步为 28 项 / PASS=70（已在本文档 §七.1 落档）；② `cid == null` 防御分支**零覆盖**（`LiteRtLmEngine` 不可 JVM 实例化，属既有取舍，本波如实申报）；③ 熔断闸门复用纯函数**接受**（口径统一收益 > 轻微耦合，且逐字节等价）。
