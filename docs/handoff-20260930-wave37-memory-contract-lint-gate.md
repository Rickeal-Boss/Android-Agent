# Wave 37 交接：记忆存储契约（消灭静默成功）+ lint baseline 清障 + lint 转硬门禁

> 写于 2026-09-30。基线 `be2056d`（Wave 36 交接 docs）→ 本波 tip **`cd0f147`**（9 个功能/治理 commit）。
> **CI 双绿（含 lint 硬门禁）**：Build [36672180241](https://github.com/Rickeal-Boss/Android-Agent/actions/runs/36672180241)（Assemble Debug / Unit tests / **Lint (baseline gate)** 三 job 全 success）+ Release [36672180317](https://github.com/Rickeal-Boss/Android-Agent/actions/runs/36672180317)（success）。
> **产物**：release APK **70.2 MB** + debug APK **39.3 MB** + R8 mapping **4.3 MB**（Release 轨）；Build 轨另有 debug APK 39.32 MB + lint baseline + lint reports。
> 团队 SOP：沈思远（窄而深审计）→ 柯码成 ×2 并行（G1 记忆存储契约 / G2 lint 代码清障）+ 主理人自做 baseline 与守卫同步 → 严质衡（全量审查：**0 P0 / 0 P1 + 1 P2**）→ 修 P2 → 主理人集成 / 3 轮 CI 往返 / 产物验证。
> ⚠️ 本波因 3 轮 CI 往返，tip 与首轮推送点不同：首推 `5b8ce33`（红）→ `cd382f2`（绿但 lint 实际红）→ `d319f3a`（真绿）→ `cd0f147`（摘观察期）。

---

## 1. 本波交付

| ID | 内容 | 性质 | commit |
|---|---|---|---|
| **F1** | `AgentMemory.writeSync` 曾 `runCatching{...}.onFailure{ warn }` **吞掉落盘失败**，且 `upsert` 无条件回 `true` ⇒ **磁盘满 / 权限 / IO 失败时用户与模型都被告知「已记住」，实际一个字节都没落盘**（静默成功，比「写失败」更糟：错误不可观测）。改为 `writeSync` 返回 `Throwable?`；`upsert` → `MemoryWriteResult{Ok,TooLong,Corrupted,WriteFailed}`、`remove` → `MemoryRemoveResult{Removed,NotFound,Corrupted,WriteFailed}`（各带 `userMessage`）；删 `companion.upsertFailureReason`；失败时尽力回收残留 `.tmp` | **真实 bug** | `9104361` |
| **F2** | lint baseline **83 → 35**：删 28 条对已 `disable` 检查的失效条目 + 16 条已修的 `AutoboxingStateCreation` + 4 条 `RenderEffect` 冗余 `@RequiresApi(S)`；`arch-guard` 第 14 项冻结值同步；`arch-guard-selftest.sh` case11 期望守卫名同步；`build.yml` / `app/build.gradle.kts` 共 8 处注释如实化 | 治理 | `64db05c` + `5b8ce33` |
| **F3** | `AutoboxingStateCreation` 16 处 `mutableStateOf(0f/0/…)` → `mutableFloatStateOf`/`mutableIntStateOf`（跳过 `OnboardingScreen.kt:80` 的 `rememberSaveable` 站点） | 性能 | `9016172` |
| **F3 后半段** | **27 处** `.value` → **`.floatValue`**（原语访问器，`.value` 会装箱）—— 见 §3 第 2 条 | 性能（**修正 F3 的半成品**） | `d319f3a` |
| **F4** | `RenderEffect.kt` 删 4 处冗余 `@RequiresApi(Build.VERSION_CODES.S)`（minSdk 31 = S ⇒ 恒真），保留 TIRAMISU 那处与两个 import | 治理 | `61ec043` |
| **F1 补** | 修 `MemoryWriteResultTest` 的 `initializationError`（JUnit 4 要求测试方法 void） | **修 CI 红** | `546a0cd` |
| **F5（新守卫）** | `arch-guard` **第 15 项**「测试源集 `@Test` 方法必须返回 void」+ `scripts/check-test-void.py` + selftest `case12`/`case12b`（selftest **17 → 19**） | 守卫 | `cd382f2` |
| **F6（E8b）** | 摘除 lint step 的 `continue-on-error`，**lint 转硬门禁** | CI 治理 | `cd0f147` |

**改动量**：19 文件，**+1010 / −1128**；新增 2 个文件（`MemoryWriteResultTest.kt` / `scripts/check-test-void.py`），删除 1 个（`MemoryFailureReasonTest.kt`）。

---

## 2. 关键设计决策

### 2.1 F1：Boolean → sealed 结果类型（消灭静默的范式改动）
- **为什么必须改类型**：写入有**四种**互不隶属的结局（成功 / 正文超限 / 文件损坏拒写 / **落盘失败**）。Boolean 只能表达「成功 / 非成功」，而本缺陷**正是** `WriteFailed` 这条 —— **Boolean 方案在结构上无法消灭它**。
- **Wave 36 曾明确否决过 sealed**（理由：多出的价值「不解决当时那个缺陷」却要付 4 文件改动 + 5 处 Boolean 断言重写）。本波目标换成「修静默成功」⇒ 该否决前提失效，sealed 反而成为**必要**。
- **`writeSync` 返回 `Throwable?`**（成功 null）而非 Boolean：调用方需要原因来生成面向人的文案。
- **首次让「落盘失败」可被纯 JVM 测试钉住**（Wave 36 认为它「极难构造、拿不到测试覆盖」）：两条构造 —— ①「父路径是普通文件」（`mkdirs()` 失败 + tmp 写入抛 `IOException`）；②「目录 + 文件置只读」（POSIX 去掉父目录写权限 ⇒ 建 tmp 失败；Windows 文件只读 ⇒ `REPLACE_EXISTING` move 失败），**带 root 能力探测**（见 §2.4）。
- **消费端如实翻译**：`MemoryWriteTool` 不再把任何失败一律谎报成「文件已损坏」；`MemoryDeleteTool` 不再把「文件损坏」谎报成「不存在标题为…」；`MemoryViewModel` 只访问接口成员 `userMessage`（**避开跨模块 smart cast 坑**）。

### 2.2 F2：先读 `lint-reports` artifact，再动 baseline
- **`lint-reports-<sha>` artifact 是唯一零 CI 成本的真实 issue 来源**。本波用它**证伪**了 Wave 36 交接里的猜测「baseline 可能已因代码移动失配」——实测 **55 条真实抑制仍精确匹配，未失配**。
- 并发现 **28 条是对已 `disable` 检查的失效条目**（`GradleDependency` 19 / `NewerVersionAvailable` 6 / `AndroidGradlePluginVersion` 3）—— lint 自己在报告里以 **`LintBaselineFixed`** 主动建议删除（理由：留着会让「被重新引入的问题」静默命中旧条目而永不报出）。
- ⇒ **删除失效条目是 lint 认可的操作**，与「不要手工新增/合成条目」并不矛盾。此区分已写入 `app/build.gradle.kts` 注释。
- 取产物：`GET /repos/{o}/{r}/actions/artifacts/{id}/zip` 返回 **302**，需**手动跟随且第二步去掉 Authorization**（`_ci-tools/dl_artifact.sh`）；验证脚本 `_ci-tools/verify_lint.py`（判据：除 `LintBaseline`/`LintBaselineFixed` 外的任何 id 都是真实新问题）。

### 2.3 F3：换原语 state **只是第一步**
- `mutableStateOf(0f)` → `mutableFloatStateOf(0f)` 解决 `AutoboxingStateCreation`（**创建**时装箱）。
- 但**读写 `.value` 仍会装箱**（`.value` 是 `MutableState<Float>` 的桥接访问器，返回装箱 `Float`）⇒ lint 报 **`AutoboxingStateValueProperty`**（**26 条**，Error 级）。
- ⇒ **只做一半比不做更差**：16 条 → 26 条。补完 = 27 处改走**原语访问器 `.floatValue`**（`floatValue` 是 `MutableFloatState` 的**成员**，非扩展 ⇒ 零新 import；语义等价）。
- **`by` 委托不受影响**：Compose 为 `MutableIntState`/`MutableFloatState` 提供了专用 `getValue`/`setValue`（走原语），故 `var x by mutableFloatStateOf(0f)` 不装箱、不报该 issue。
- 同样不触发该 issue 的（**不要误改**）：`derivedStateOf{}`（静态类型是 `State<T>`）、`Animatable.value`（普通属性）、`mutableStateOf<Boolean|Job?|List>` 的 `.value`（非原语）。

### 2.4 环境相关测试必须加**能力探测**（沿用 Wave 36 符号链接用例惯例）
`remove 落盘失败` 依赖 POSIX 权限位生效；若执行环境以 **root** 运行会绕过权限 ⇒ 用例误红。故置只读后先探测「能否在只读目录里写文件」，能写即说明权限位被绕过 ⇒ **跳过断言而非 fail**。（CI 为 GitHub `ubuntu-latest` 非 root `runner`，实际会走到断言。）

---

## 3. 严质衡审查结论（0 P0 / 0 P1 + 1 P2）

**独立复算成立的三项**
- **F1 静默是否真被消灭**：独立走完 `writeSync` 三条失败路径 —— ① `mkdirs()` 失败（紧随的 `writeFile` 必抛）；② tmp `writeText` 抛；③ `ATOMIC_MOVE` 抛后**退化 `move` 再抛**（内层 catch 已在执行中 ⇒ 异常逃出 ⇒ 被外层 `runCatching` 捕获）。**三条都必然映射为 `WriteFailed`，无任何路径「落盘失败却回成功」。**
- **F3 16 处逐处（非抽样）类型推断 + import 双向核对**：漏删 ⇒ `UnusedImport`、多删 ⇒ 未解析引用，**两个方向都查过**。
- **F2 baseline 与代码改动一一对应**：现存 35 条与本波改过的源文件**交集为空**；刻意未改的 `OnboardingScreen.kt:80` 条目**仍在**。

**P2 处置**
- **P2-1（随本波修）**：`app/build.gradle.kts` 有 4 处过时注释仍写 83 / 「28 条留着无害」⇒ 已如实化，并把「不要手工编辑 XML」细化为「不要手工**新增/合成**条目；**删除**失效条目是允许的」。
- **P2-2（挂账）**：`readState()` 把「文件存在但**读不了**（权限/IO）」经 `runCatching.getOrElse` 坍缩成 `Corrupted` ⇒ `upsert` 回 `Corrupted`（文案「解析失败」）而非 `WriteFailed`。**不是静默成功**（仍回失败），只是原因误标；Wave4 起既有。
- **P2-3（不修）**：历史 handoff 文档里的旧描述。

---

## 4. 本波新教训（复用价值高，按价值排序）

### 🔴 4.1 「CI 双绿」**不等于**「lint 通过」—— 必须读 artifact
lint step 带 `continue-on-error: true`（观察期）时，lint **实际失败也不会红 job**。本波实测：一轮「Build success」的背后是 **26 条未被 baseline 吸收的新错误**。
⇒ **判定 lint 是否真通过，必须下载 `lint-reports-<sha>` artifact 看真实 issue 集**；只看 job 颜色会被骗。同族：**任何带 `continue-on-error` 的门禁都只是「报告」而非「门禁」**。
⇒ 反向结论：**观察期确实在起作用** —— 它拦住了「一翻转就红 CI」的结局，让我们有窗口先修干净再摘。

### 🔴 4.2 JUnit 4 要求测试方法返回 `void`；Kotlin 表达式体的返回类型由**末表达式**决定
`kotlin.test` 里 **`assertNotNull` / `assertIs` / `assertFailsWith` / `assertFails` 会返回值**（非 Unit）。以它们收尾 ⇒ 方法非 void ⇒ **整个测试类 `initializationError`** ⇒ **该类所有用例一个都不跑**，而 Gradle 只报「182 tests completed, **1 failed**」——「只挂 1 个」是**假象**。
- 可安全收尾的：`assertEquals` / `assertTrue` / `assertFalse` / `assertNull` / `assertSame` / `assertContains`。
- 需要 `assertNotNull` 的值语义时：先绑局部 `val`，再用 Unit 型断言收尾。
- **已固化为 `arch-guard` 第 15 项**（`scripts/check-test-void.py`，花括号深度跟踪 + 去字符串/注释后判末语句）。

### 🔴 4.3 守卫命令若依赖外部文件，必须按「脚本自身所在目录」定位
自测网会在**脚手架树**里以 `cwd=脚手架` 运行守卫 ⇒ 相对 cwd 的路径找不到脚本 ⇒ `exit 2` ⇒ 守卫自身故障把 4 个「应绿」case 全判红。
- 正解：`GUARD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"`。
- **并且必须加「被依赖文件缺失 ⇒ 判红」的前置断言**，否则守卫面失效时会静默通过（僵尸规则）。

### 🔴 4.4 自测网自己也会假绿
`assert_red` 按**守卫名字符串**匹配 `::error::[$want]`，而「守卫命令自身执行失败」那行**也含守卫名** ⇒ 守卫自身故障时 case 照样 PASS。
⇒ **「守卫报红」不等于「守卫真的命中了」**。修法：追加断言「输出里必须能看到**违规行本身**」（`case12b` 用 `grep -qF "assertNotNull"`）。

### 🔴 4.5 主理人给的指令又错了一次（本波第二次，Wave 36 也有一次）
我让 G1 用「父路径是普通文件」构造 `remove` 的写失败 —— 但那条路径 `readState()` 走 `Absent` 直接 `NotFound`，**永远到不了 `writeSync`**。G1 自己换成「目录 + 文件置只读」并上报。
⇒ **凡给测试构造，先自己把控制流走一遍**；同族教训（Wave 36）：「挂账描述本身可能是错的，动手前先回源码核验」。

### 其他（工程细节）
- **`data object` + `override val x: String? = null` / `get() = …` 在 Kotlin 2.3 合法**；sealed 变体带 `userMessage` 可让调用方**无法「顺手」把失败当成功**。
- **`Result.onFailure{}` 返回原 `Result`（非 Unit）** ⇒ `.onFailure{…}.exceptionOrNull()` 链语义正确；`onFailure` 内丢返回值**不会**掩盖原异常。
- **捕获可变局部变量**（`var tmp: File? = null` 在 `runCatching{}` 内赋值、`onFailure` 内读取）Kotlin 允许（编译成 `Ref`），但**不能依赖对它的智能转换**（报 captured by a changing closure）—— 让实际 I/O 走非空局部 `val`，`tmp` 只作清理引用即可。
- **`MemorySection` 允许缺字段解码**（`updatedAtMillis` 有默认值）⇒ 测试里可直接写 `[{"title":"x","content":"c"}]`。
- **`mutableIntStateOf` 全仓首次使用**（`mutableFloatStateOf` 已有先例 `DrawBackdropModifier.kt:4`）；同包同 artifact，解析无疑。
- **`arch-guard.sh` / `arch-guard-selftest.sh` 都接入 CI**（`build.yml:224-225,233-234` + `release.yml:185`）⇒ 新增守卫会在 CI 强制生效，不会只活在本地。

---

## 5. 挂账（Wave 38+，按优先级）

1. **剩余 35 条 baseline 的继续清障**（每一项都已有精确定位，见 `app/lint-baseline.xml`）：
   - `UseKtx` **9**：需先核实 `androidx.core:core-ktx`（catalog 有声明 `libs.versions.toml:57`）**是否已在相关模块实际应用** —— 本波 grep 未在 `build.gradle.kts` 里找到引用；若确实没应用，需先加依赖（**注意 arch-guard 第 4 条禁依赖清单不含 core-ktx**）。
   - `ObsoleteSdkInt` **7**：`LiquidGlassCapabilities.kt`(2) / `LiquidAgentApplication.kt`(1) / `DeviceCapability.kt`(1) / `AndroidManifest.xml`(1) / `RuntimeShader.kt`(1) / `mipmap-anydpi-v26`(1) —— 多为 `if (SDK_INT >= …)` 分支解构或资源限定符重命名，**比本波的纯删除风险高**。
   - `ModifierParameter` **4**：`Modifier` 参数需提到「第一个可选参数」位置 ⇒ **改签名顺序要同步动调用点**。
   - `ConfigurationScreenWidthHeight` **3** / `OldTargetApi` **2** / `NewApi` **2** / `AnnotateVersionCheck` **2**（`RuntimeShader.kt:111` 改 `= true` 可**一次干掉 `ObsoleteSdkInt` + `AnnotateVersionCheck` 两条**）/ `MissingPermission` 1 / `DiscouragedApi` 1 / `DataExtractionRules` 1 / `NotShrinkingResources` 1 / `MonochromeLauncherIcon` 1 / `OnboardingScreen` autoboxing 1。
2. **P2-2**：`readState()` 区分「不可读」与「解析失败」（现在都回 `Corrupted`）。
3. **Wave 36 挂账仍在**：P2-2（写盘在途取消串台）、P2-6（沙箱子目录系统返回键）、E9（arch-guard 编号缺 11）、E10（diagnostics 卡片化）、原生通道「同 run 内恢复」、**工具 schema 真机实证（R1）**、记忆向量检索 / 去重 / 分区、**THIN 0.21**、路径 B 列表回看优化。

---

## 6. 真机验收清单（CI 查不出，adb 解禁后执行）

**Wave 33 的「Gemma GPU 加载引擎错误」仍是用户标记的待解决项，观测基建已就位。**

1. **F1 记忆写入（优先验）**：设置页新增/编辑记忆 → 正常路径应显示「已记住」；**把设备存储填满（或用只读挂载）后再写** → 应出现「记忆写入**磁盘**失败（…）：本次未保存，请检查存储空间后重试」，**且对话框不关、输入保留**（旧实现是「对话框关闭 + 无提示 + 输入丢失」）。模型侧：`memory_write` 失败应回**精确原因**而不是一律「文件已损坏」。
2. **F3 手势回归**：`LiquidBottomTabs` / `GlassSegmented` / `GlassSlider` / `GlassSwitch` 的拖动与吸附是否与改前**手感一致**（本波把 3 个文件里 27 处 `.value` 换成 `.floatValue`、16 处工厂换成原语版 —— 语义等价但**必须真机确认手感无回归**，尤其 `panelOffsetPx` 的拖动累加与 `DampedDragAnimation.targetValue`）。
3. **lint 硬门禁**：下次改代码后若引入 lint 问题，`Lint (baseline gate)` job 应**直接红**（这是 E8b 的验收点）。
4. Wave 36 遗留：E6 沙箱下钻（面包屑 / 上下文返回 / 子目录内预览与打开是否同一文件）、E5 记忆失败可见化、E1 通知分闩（两类 WARN 各出现一次）。
5. Wave 33 遗留关键字：`preface 渲染诊断` / `preface 校验失败` / `角色通道播种失败` / `会话重建原因`；`GPU 后端不可用` 后跟的错误原文。

---

## 7. 相关产物与工具

- 提交区间：`be2056d..cd0f147`（9 commit）
- **新增工具**：`_ci-tools/dl_artifact.sh`（artifact 302 手动跟随）、`_ci-tools/verify_lint.py`（lint 产物判据）、`_ci-tools/joblog.sh`（job 日志 302 手动跟随）、`_ci-tools/check_test_void.py`（本波扫描器原型，**正式版已移入 `scripts/check-test-void.py`**）、`_ci-tools/baseline_trim.py`（baseline 精确删除）
- 上一波：`docs/handoff-20260930-wave36-backlog-clearance-sandbox-drilldown.md`
- 本地静态闸门：`bash scripts/arch-guard.sh`（**15 项**）、`bash scripts/arch-guard-selftest.sh`（**PASS=19**）、`python balance_check.py`
  ⚠️ 本机 Git Bash 下 arch-guard 约 **1m20s–1m40s**、selftest 约 **4m–5m**，**默认 120s 超时会 SIGTERM 掉它们**；selftest 会输出完整结果后卡在 EXIT trap（本沙箱 `rm` 包装产物）。**本地请后台跑或加长超时**；CI（Ubuntu）不受影响。

## Suggested skills

无必须项。接手时沿用「沈思远窄而深审计 → 柯码成 ×N 文件面互斥并行 → 严质衡审查 → 修 P2 → 主理人集成提交 → CI 双绿 → **下载 lint-reports artifact 验证真实 issue 集**」SOP。
⚠️ **本波起，lint 已是硬门禁**：任何改动后除看 job 颜色外，**必须读 `lint-reports-<sha>` artifact**；新增问题一律**修代码**，绝不 regen 进 baseline（`arch-guard` 第 14 项冻结值 35 会直接红）。
