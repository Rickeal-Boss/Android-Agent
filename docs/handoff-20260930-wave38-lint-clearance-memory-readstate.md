# Wave 38 交接：lint baseline 35→4 清障 + 记忆读态语义 + W36 挂账清零 + 六份外部报告收编

> 写于 2026-09-30 晚。基线 `15f12d0`（Wave 37 交接 docs）→ 本波 tip **`a822407`**（9 个 commit）。
> **CI 双绿**：Build [36708838527](https://github.com/Rickeal-Boss/Android-Agent/actions/runs/36708838527)（Lint (baseline gate) / Assemble Debug / Unit tests 三 job 全 success）+ Release [36708838443](https://github.com/Rickeal-Boss/Android-Agent/actions/runs/36708838443)（success）。
> **产物**：release APK **70.18 MB** + debug APK **39.32 MB** + R8 mapping **4.33 MB**（Release 轨）；Build 轨另有 debug APK 39.32 MB + lint baseline + lint reports。
> 团队 SOP：主理人侦察（含**证伪两条挂账失实**）→ 沈思远（窄而深审计 + 文件面切分）→ 柯码成 ×4 并行（D1 core-data/engine、D2 core-design/models、D3 app/chat/资源、D4 core-agent 记忆/settings）→ 崔续程（D5 baseline 收口 + 守卫/CI 注释）→ 严质衡（全量审查：**0 P0 / 1 P1（不确定）/ 7 P2**）→ 修 P1/P2 → 主理人集成 / **2 轮 CI 往返**（首轮红：2 个独立根因）→ 产物验证。
> ⚠️ 本波 tip 与首推点不同：首推 `ad7e825`（**红**：unit-tests + lint 两 job）→ `a822407`（**双绿**）。

---

## 1. 本波交付

| ID | 内容 | 性质 | commit |
|---|---|---|---|
| **F1** | **lint baseline 35 → 4**：真修 31 条底层代码问题（UseKtx 9 / ObsoleteSdkInt 7 / ModifierParameter 4 / ConfigurationScreenWidthHeight 3 / AnnotateVersionCheck 2 / NewApi 2 / MissingPermission 1 / DiscouragedApi 1 / DataExtractionRules 1 / MonochromeLauncherIcon 1），逐条删除对应 baseline 条目 | 治理 | `45347a6` `66d3c01` `ad7e825` |
| **F2** | **记忆读态语义（W37 P2-2）**：`readState()` 把「读不了（权限/IO）」与「能读但解析失败」**分开**；`ReadState` / `MemoryWriteResult` / `MemoryRemoveResult` 各新增 `Unreadable(cause)`；4 个穷举 `when` 同步补齐；新增 JVM 用例 | **真实缺陷**（原因误标 + 有害建议） | `2412c5a` |
| **F3** | **沙箱子目录系统返回键（W36 P2-6）**：新增 `BackHandler(enabled = currentDirPath.isNotEmpty())`，与顶栏返回钮同语义（先上溯、根层才退出子页） | 用户可见一致性 | `b10e622` |
| **F4** | 死 import 清理（`MemoryScreen.kt` 的 `LocalAppContainer`，W36 挂账 C4）+ `DrawerBackHandler` KDoc 补 LIFO 限定 + `SandboxFilesScreen` 注释补「抽屉优先性不可达」前提 | 债 / 文档真实性 | `b10e622` |
| **F5** | **守卫第 15 项扩展**：由「@Test 必须返回 void」扩为「**void + 反引号名不含 JVM 非法字符**」+ selftest `case12c`/`case12d`（19 → **21**） | 守卫（本波实案驱动） | `e729753` |
| **F6** | `isShrinkResources = false` **回滚**（试删失败，见 §3.1）+ baseline 恢复该条 ⇒ 冻结值 3 → 4 | 修 CI 红 | `227e9a9` |
| **F7** | 信息卫生（外部审查 R4）：中性化 5 份 handoff 里的「凭据落盘路径」披露 + `.gitignore` 加 `_ci-tools/` `_research/` `.recon-harness/` `__pycache__/` | 安全卫生 | `a822407` |
| **F8** | 测试方法名去 JVM 非法字符 `/`（见 §3.2） | 修 CI 红 | `19587ed` |

**改动量**：44 文件，**+531 / −467**；新增 2 个文件（`res/xml/data_extraction_rules.xml`、`res/mipmap-anydpi/ic_launcher.xml`），删除 1 个（`res/mipmap-anydpi-v26/ic_launcher.xml`）。

---

## 2. 关键设计决策

### 2.1 `UseKtx` 9 条：**零依赖改动**（证伪了一条挂账）
W37 挂账写「需先核实 `core-ktx` 是否已在相关模块实际应用——本波 grep 未在 `build.gradle.kts` 里找到引用」。**实测为假**：依赖早已以 `implementation(libs.androidx.core.ktx)` 形式存在于**全部 9 个模块**，此前的 grep 模式（`core-ktx|coreKtx`）漏了 camelCase 点号访问器。
- 独立佐证：外部审查 v3 §N5 用不同方法得出同一结论（「前提已满足，可直接动手」）；而 v8 §G3 结论相反（「全仓 0 应用」）——**v8 用的也是同一个有缺陷的 grep 模式**。
- ⇒ `UseKtx` 9 条是**纯代码改动**。lint 自己能报出 `UseKtx` 这一事实本身就是「该模块 classpath 里有 core-ktx」的证明（lint 只在扩展可得时才建议）。

### 2.2 `ObsoleteSdkInt` 7 条：**只常量化恒真判断，保留真实降级判据**（回应外部审查 D-2 / C1 的警告）
外部审查警告「`>=TIRAMISU` 是 API 31~32 玻璃降级的**真判据**，紧邻恒真的 `>=S`；误常量化它 lint 不报、CI 全绿、真机炸」。本波逐条核验：
- `LiquidGlassCapabilities.tier`：三档 `when` → `if (SDK_INT >= TIRAMISU) FULL else BLUR_ONLY` ⇒ **31~32 → BLUR_ONLY 的降级语义逐字保留** ✓
- `hasRuntimeShader` / `isRuntimeShaderSupported()`：`SDK_INT >= TIRAMISU` **原样保留**（只补 `@ChecksSdkIntAtLeast`）✓
- 被常量化的只有 `>= S`（`hasBlurEffect` / `isRenderEffectSupported()`）—— minSdk 31 = S，lint 自己就断言「SDK_INT is always >= 31」⇒ 恒真、非降级判据 ✓
- ⇒ **不存在「反向加项」**。但仍按外部审查建议，把「31/32 玻璃降级」补进真机验收清单（§6）。

### 2.3 `ConfigurationScreenWidthHeight` 3 条：**就地 `@Suppress` 而非改 API**
lint 建议改用 `LocalWindowInfo.current.containerSize`。拒绝理由：`containerSize` 是 **px** 且语义为 ComposeView 容器尺寸，需 `LocalDensity` 换算；改口径会让 `WindowSizeClass` 的 600/840/480/900 断点在边界翻档——而该断点**已被 JVM 单测钉住**、且 `MainActivity.kt:19` 有明文约定。⇒ 函数级 `@Suppress` + 理由注释，**零行为变更**。

### 2.4 记忆读态：**B-full（对称类型）而非 B-min（复用 WriteFailed）**
`readState()` 曾用 `runCatching{ 读+解析 }.getOrElse { Corrupted }` 把两类失败坍缩成一个，于是**权限问题也被报成「文件解析失败，请人工修复或删除」**——而「删除」对权限问题是有害建议（会诱导用户删掉内容完好的文件）。
- 选 B-full（新增 `Unreadable` 变体）而非 B-min（复用 `WriteFailed`）：后者仍误标（把读失败说成写失败），而本缺陷的**本质就是误标**。
- 与 W37「把结局做成类型」同构。穷举点全仓 grep 复核为 **4 处**（`AgentMemory`×2 同文件、`MemoryTools`×1、`MemoryViewModel`×1），全部补齐。
- `readSync()`（只读渲染路径）**刻意不落日志**：该路径可能高频调用且类内无「只报一次」的闩，逐次 warn 会刷屏；可观测性由写路径各落一条 `error` 兜住。

### 2.5 `@SuppressLint("MissingPermission")` 收窄到新私有函数（审查 P2-5）
原抑制面是整个 `onTick`。虽当前安全（:108 的 `hasPostNotificationsPermission()` 确实守卫了 `notify`），但将来在 `onTick` 里新增权限敏感调用会被**静默吞掉**。⇒ 外提 `postGenerationNotification(text)`，抑制只覆盖它；**零 import 变更**（`notification` 用类型推断，不写 `Notification` 类型名）。

---

## 3. 🔴 本波 CI 两轮往返：**两个独立根因，都是「本地无 JDK 查不出」的类别**

### 3.1 `NotShrinkingResources`：**只读 lint 的 id 会误判**（第 1 类）
原判断（沈思远 + 主理人 + D3 三方一致）：「AGP 的 `isShrinkResources` 默认即 false ⇒ **删掉显式 `= false` 即可消除该 issue**，且行为逐字节等价」。
**实测为假**。CI 报：
```
app/build.gradle.kts:87: Error: If enabling minification, also set isShrinkResources = true [NotShrinkingResources]
```
该检查的真实语义是「**开了 `isMinifyEnabled` 就必须也开 `shrinkResources`**」，在**两种情形都会报**：
| 代码形态 | lint message |
|---|---|
| 显式 `isShrinkResources = false` | `Avoid setting isShrinkResources = false` |
| 缺省（但 `isMinifyEnabled = true`） | `If enabling minification, also set isShrinkResources = true` |

⇒ 删掉显式 false **只是换了一条消息**，issue 照旧。而真正翻 `true` 属 **release 行为变更**（R8 资源收缩会删掉「只被动态引用」的资源：按名查资源 / `getIdentifier`），必须真机验证后才可动。
**修法**：恢复显式 `false` + 如实注释 + 把该条**恢复进 baseline 豁免**（冻结值 3 → 4）。
- **教训**：**清障前必须读 artifact 里的完整 `message`，不能只看 id。** 已写进 `arch-guard` 第 14 项与 `build.yml` 注释。
- 反向收获：本条也**实证了「baseline 按 `errorLine` 文本匹配、行号漂移不影响」**——恢复后的 `isShrinkResources = false` 落在第 94 行（baseline 记 88），CI 仍精确吸收（`LintBaselineFixed` 零条）。

### 3.2 `Name contains illegal characters: /`（第 2 类）
```
e: .../MemoryWriteResultTest.kt:102:9 Name contains illegal characters: /.
```
JVM 规范 §4.2.2 禁止方法名含 `. ; [ /`（`<` `>` 亦不可）。本波新增用例名 `文件存在但读不了 —— upsert / remove 均回 Unreadable 而非 Corrupted` 里的那个 `/` 让 `core-agent:compileDebugUnitTestKotlin` 直接失败 ⇒ 整个 unit-tests job 红。
- **教训**：Kotlin 允许 `fun \`任意中文与符号\`()`，但**编译到 JVM 时仍有字符禁区**。反引号里想写「A / B」一律改成「A 与 B」「A、B」。
- **已固化为守卫**（见 F5）：`scripts/check-test-void.py` 新增 `scan_illegal_names()`；`arch-guard` 第 15 项守卫名同步扩展；selftest 新增 `case12c`（含 `/` ⇒ 必红）+ `case12d`（红必须来自**真命中**，防「守卫自身故障」假绿）。
- 本机验证：仓库内零输出（无假阳性）、对合成违规文件正确命中。

### 3.3 两个根因的共性（值得记住）
都属「**本机一次 `compileDebugKotlin` / `lintDebug` 就能拦住**」的类别。外部审查 v8 §5 与 09-29 报告 §3 都点名过这条（Wave 30–33 至少三轮 CI 红同属此类）。**建议见 §7 挂账第 1 条。**

---

## 4. 严质衡审查结论（0 P0 / 1 P1（不确定）/ 7 P2）

**独立确认（非抽样）**
- **import 双向核对全核**：7 个被删 import 全部确认无残留引用；5 个新增 import 全部确认被使用且路径正确。
- **穷举 `when` 全核**：`MemoryWriteResult` 的消费点只有 2 处 `is/!is Ok`（**无 `when`**）；`MemoryRemoveResult` 的 2 处 `when` 与 `ReadState` 的 2 处 `when` 均已覆盖 ⇒ **无编译红**。
- **`readState()` 非局部返回合法性**：`runCatching` / `Result.getOrElse` 均为 `@InlineOnly inline` ⇒ `getOrElse { return … }` 合法；两条既有行为（`!exists → Absent`、`blank → Absent`）逐字不变。
- **新测试的 JUnit void 推导**：`withTempDir` 是**非 inline** 泛型函数；lambda 末表达式是 `try { … assertTrue(…) } finally { … }` —— Kotlin 中 `try/finally` 的类型**由 try 块决定**（finally 的值被丢弃）⇒ `T = Unit` ⇒ **合规**。并实跑 `python scripts/check-test-void.py -v` 零输出。
- **XML / 资源实跑解析**：4 个 XML 全部 well-formed；`mipmap-anydpi` 是合法限定符且比 `-v26` 更接近默认 ⇒ 不可能新增 `MissingDefaultResource`；`grep "tools:" app/src/main/AndroidManifest.xml` 零命中 ⇒ 删 `xmlns:tools` 安全。
- **`Modifier` 重排调用点全列**：4 处唯一调用点**全部具名实参**，无 `@Preview` ⇒ 零风险。
- **`BackHandler` LIFO 语义四步论证**：`DisposableEffect` 注册/注销（**不累积**）；`enabled=false` 是「留在链上但被 dispatcher 跳过」；`OnBackPressedDispatcher` 从链尾向前找第一个 `enabled` ⇒ **LIFO**；根层回落 app 两段式 ✓。
  - **额外发现（已修）**：新回调 `enabled` 只看 `currentDirPath`、不看 `drawerState.isOpen` ⇒ 若「抽屉开着且在子目录」会**先上溯目录而非先关抽屉**，与「抽屉优先」不变式相反。严质衡进一步核了可达性（`gesturesEnabled` 只对 CHAT 页为 true、沙箱页无汉堡入口）⇒ **当前不可达**，故非缺陷；已在注释里写明该前提与「未来加抽屉入口须收紧 enabled」。

**P1-1（不确定，需 CI 确认）**：`isRenderEffectSupported() = true` 后，5 处 `if (!isRenderEffectSupported()) return` 是否被 lint 报新问题。
- 严质衡读了 lint 的 `ObsoleteSdkInt` explanation 原文（"flags **version checks** that are not necessary…"）⇒ 判定是**过程内**分析、不做跨函数常量传播 ⇒ 不会报。
- **CI 实证**：真实新问题 = 0 ✓（**该风险已被证伪**）。保留的 5 处守卫是**死守卫**（minSdk 31 下恒不触发），已在 KDoc 如实交代 + 记为可清理项。

**P2 处置**：7 条全部处置（计数残留 7 处如实化、`RuntimeShader` KDoc 补「5 个文件的 6 处」与死守卫交代、`LiquidGlassCapabilities` 的零消费者成员加「属可清理项」说明、`DrawerBackHandler` KDoc 补 LIFO 限定、`SandboxFilesScreen` 注释补前提）。未改：`readState` 的 `runCatching` 仍吞 `Throwable`（既有形态，记录不修）、`data_extraction_rules.xml` 的 `path` 省略（判定不必改）。

---

## 5. 本波新教训（复用价值高，按价值排序）

### 🔴 5.1 「只读 lint 的 id 会误判」——清障前必须读 artifact 里的**完整 message**
`NotShrinkingResources` 的 id 与 message 语义**不一致**：id 说「不收缩资源」，而真实判据是「开了 minify 就必须也开 shrinkResources」。**同一个 id 在两种代码形态下报不同 message，且删掉显式声明不会消除它。**
⇒ **可复用判据**：动 baseline 前先 `_ci-tools/dl_artifact.sh` 取 `lint-reports-<sha>`，逐条读 `message` 原文；**id 只是索引，message 才是判据**。

### 🔴 5.2 JVM 方法名的字符禁区 —— Kotlin 反引号不是「任意字符」
`fun \`中文名\`()` 合法，但 JVM 规范 §4.2.2 禁止方法名含 `. ; [ /`（`<` `>` 亦不可）。**编译期硬报错、本地无 JDK 完全查不出**。已固化为守卫（`check-test-void.py` 的 `scan_illegal_names`）。
⇒ 中文测试名里的「A / B」改成「A 与 B」「A、B」。

### 🔴 5.3 baseline 匹配按 `errorLine` 文本，**行号漂移不影响**（本波二次实证）
Wave 37 实证过一次（`MissingPermission` 条目记 `line=101` 而实际在 133，仍精确命中）；本波再次实证（恢复的条目落在 94 行，baseline 记 88，仍精确吸收）。
⇒ 加注释导致的行号位移**不会**让 baseline 失配；真正会让它失配的是**改动那一行本身的文本**。

### 🔴 5.4 「挂账描述本身可能是错的」——本波**第 3 次**同类实案
W36 一次、W37 一次、本波一次（`core-ktx` 零应用）。三次的共同形态：**挂账里的「已核实」结论来自一次有缺陷的检索**。
⇒ 凡挂账写「已 grep 确认 X」，动手前**换一种检索方式复核**（本波：`core-ktx|coreKtx` 漏了 `libs.androidx.core.ktx`）。

### 5.5 外部审查报告之间也会互相矛盾 —— 用**独立证据**裁决
同一事实（core-ktx 是否已应用）在 v3 §N5 与 v8 §G3 给出**相反**结论。裁决方式不是「看谁更权威」，而是回到可判定的证据（lint 能报出 `UseKtx` 本身就证明扩展可得）。
⇒ 外部报告一律**只作线索**（本仓既有纪律），且**报告之间冲突时必须自己取证**。

### 5.6 「删掉冗余声明」不总是等价改动
`isShrinkResources = false` 确实是 AGP 默认值 ⇒ 删除后**行为等价**，但**门禁不等价**（lint 的 message 换了，issue 仍在）。⇒ 判断「能否删」要同时问两件事：**行为是否等价** + **门禁是否仍满足**。

### 5.7 长中文 commit message 走 `-F <file>`，不要内联
含双引号的 commit message 内联进 shell 会破坏引号（本波实测：`error: pathspec 'setting' did not match any file(s)`，整条命令被 shell 拆错）。⇒ 长/含引号的 message 一律 `Write` 到文件再 `git commit -F`。

---

## 6. 真机验收清单（CI 查不出）

**Wave 33 的「Gemma GPU 加载引擎错误」仍是用户标记的待解决项，观测基建已就位。**

1. **先升级再复测（防追鬼影，外部审查 R3）**：安装本波 Release APK（`a822407`，70.18 MB）→ 复测用户原始两场景（「只输出提示词」/「Gemma GPU 有问题」）→ 分叉：复现则抓关键字，不复现则记录关闭。
2. **F2 记忆读态（本波新增）**：把 `agent_memory/memory.json` 置为**不可读**（如 `run-as` 改权限）后新增/删除记忆 → 应报「**无法读取**记忆文件（…）：为避免覆盖既有内容，本次未写入。请检查存储权限后重试」，**而不是**「文件解析失败，请人工修复或删除」。
3. **F3 沙箱返回键（本波新增）**：子目录内按**系统返回键** → 应**上溯一层**（与顶栏返回钮一致）；根层按返回 → 回对话页（再按退出）。
4. **玻璃降级 31/32（回应外部审查 D-2）**：在 API 31~32 设备（或 32 模拟器）上确认**仍是「仅模糊」**（`tier == BLUR_ONLY`、无 AGSL 折射）—— 本波把 `tier` 的 `when` 改成 `if/else`，须确认降级判据未被误常量化。
5. **旋转行为（本波行为变更）**：删掉 `android:screenOrientation="user"` 后，横竖屏旋转与**旋转锁**表现应与改前一致（横屏、大屏多窗口各测一次）。
6. **主题化图标（本波行为变更）**：Android 13+ 开启主题图标后，`<monochrome>` 复用前景矢量是否**糊成一团**；若糊，需新建专用单色剪影 drawable。
7. **备份/迁移（本波新增）**：`dataExtractionRules` 全量排除后，云备份与设备间直传（D2D）应**都拿不到任何应用数据**。
8. **lint 硬门禁**：下次改代码引入 lint 问题，`Lint (baseline gate)` job 应**直接红**（W37 E8b 的验收点，本波已二次确认其有效——`NotShrinkingResources` 就是被它拦下的）。
9. Wave 36/37 遗留：E1 通知分闩（两类 WARN 各一次）、E5 记忆失败可见化（对话框不关、输入保留）、E6 沙箱下钻（面包屑 / 上下文返回 / 子目录内预览与打开是否同一文件）、F1 写盘失败（填满存储）、F3 手势手感回归。
10. Wave 33 遗留关键字：`preface 渲染诊断` / `preface 校验失败` / `角色通道播种失败` / `会话重建原因`；`GPU 后端不可用` 后跟的错误原文。
11. **原生工具通道（R1）**：开关打开 → **审批卡必须照常弹出**（红线）；关开关跑一遍必须与改前逐字节一致。

---

## 7. 挂账（Wave 39+，按优先级）

### 7.1 🔴 外部六份审查报告带来的**新增**可离线推进项（本波只做掉了 R4）
- **R1（P1）验收取证通道断裂**：6 组验收关键字**全是 `AgentLogStore.info` 级**，而该日志是**纯内存 200 条环形缓冲**（KDoc 明写「不落盘、杀进程即清空」），落盘的 `AgentLogFileStore` **只转发 ERROR**，且不写 `android.util.Log`（adb logcat 抓不到）；诊断页无复制/导出。
  ⇒ **R1-甲**：debug 构建给 `AgentLogStore` 挂一个 `Log.d` 出口（~5 行，验收流程不变）；**R1-乙**：诊断页加「复制全部」（ClipboardManager + 已有 `recent()`）。
- **R2（P2）验收清单分散在 5+ 份 handoff**，无合并 checklist ⇒ 建 `docs/device-acceptance-checklist.md`（每行 = 操作 / 预期 / 关键字 / 来源波次）。
- **R3（P2）**：验收第一行固定「升级最新 Release → 复测用户原始两场景」。
- **G1（验证链可信度专项）**：① **`arch-guard` 前置存在性断言只列 6 个模块**（9 个模块里缺 `feature-chat`/`feature-models`/`feature-settings`），且**该前置断言自身无 selftest case**；② 实测 `bash -c 'grep -rn … dir/ | grep -vE …'` 在 `dir/` 缺失时返回 **rc=1**（非 2），**`set -o pipefail` 也救不了**（管道取最右非零退出码）⇒ 带 `| grep -vE` 的守卫在目标目录被改名时会**静默判绿**（僵尸规则）—— 目前被模块存在性循环兜住，但**清单不全**；③ 确认 `build.yml:639` 的 `continue-on-error`（baseline regen 兜底）是**有意保留**并显式标注。
- **N1/N2/N3/N4**：`onPreview`/`openSandboxFile` 未过 `resolveWithinSandbox`（实害≈0，建议加信任边界注释）；`sessionDiagnosticsHintOf` 提纯函数 + 4 分支单测；A10 误报路径 KDoc 留档；README 路线图/挂账台账同步到实况。
- **#23/#24 生产 KDoc 矛盾**：`RunTokenLedger` 类头红线未更新（仍写「接线前必须先解决错配（两方案）」，与已完成的第三条路接线矛盾）；引擎 KDoc「错位/丢失」表述过宽（归一化子串结构性只能证「丢失」）。
- **D-3 补强**：`readState` 的 `Unreadable` 用例应**追加断言「IO 失败不落盘」**（当前只断言了返回类型）。
- **D-6**：`is_tos_accepted` 是**无版本 boolean**（`SettingsRepository:70`）⇒ 换法务文本当天老用户会「未同意却视为已同意」。**termsVersion 必须先于文本替换落地**（可与挂账三项里的 `formatVersion` 合并一波）。
- **G4**：沙箱文件经 `ACTION_VIEW` **出应用**（三选一：设置开关默认关 / 首次确认 / README 写清边界）。
- **莫斯线**：R1 的**三层判据**（schema 层 / 模型能力层 / 探针假阳性层）与 **Plan B** 必须在真机窗口**前**写进 handoff；`THIN 0.21` 溯源为**液态玻璃底色设计参数**（不是依赖版本），建议选「提取具名常量」这一零视觉风险的工程解。

### 7.2 本波自身遗留
- `LiquidGlassCapabilities` 的 `tier` / `hasBlurEffect` / `Tier` 与 `RuntimeShader.isRenderEffectSupported()` 的 5 处守卫均为**零消费者/死守卫**（已在 KDoc 如实标注为可清理项）。
- `RuntimeShaderCache.obtainRuntimeShader` 的 `@Suppress("NewApi")` 是**文档化豁免**（未改 `@RequiresApi`，避免级联）。

### 7.3 Wave 36/37 仍在的历史挂账（逐项已定性，不再重复调研）
原生通道「同 run 内恢复」（莫斯裁决：**降级为 R1 后置项**，R1 证伪则自动销账）｜工具 schema 真机实证（R1，唯一无法离线验证项）｜记忆向量检索（禁引新依赖）/ 去重 / 分区｜`THIN 0.21`（跨 6 波未裁决）｜路径 B 列表回看优化（待真机帧时间）｜`SubagentProgress` 事件 / `ConversationRepository` 增量写 / `formatVersion` 三项**挂账蒸发**（需逐项重新定性）｜i18n/RTL｜法务 `TODO(legal)`×4｜0 tags / 0 releases。

---

## 8. 相关产物与工具

- 提交区间：`15f12d0..a822407`（9 commit）
- CI：Build [36708838527](https://github.com/Rickeal-Boss/Android-Agent/actions/runs/36708838527) / Release [36708838443](https://github.com/Rickeal-Boss/Android-Agent/actions/runs/36708838443)
- **新增工具**：`_ci-tools/baseline_trim2.py`（**通用** baseline 精确删除：按 `(id, 文件子串)` 规格，命中 0 条报错、命中 N 条删 N 条、并提示同步冻结值）
- 上一波：`docs/handoff-20260930-wave37-memory-contract-lint-gate.md`
- 本地静态闸门：`bash scripts/arch-guard.sh`（**15 项**，本波实测 **exit 0**）+ `bash scripts/arch-guard-selftest.sh`（**PASS=21**，本波实测 **exit 0**）+ `python ../balance_check.py <files>`
  ⚠️ 本机 Git Bash 实测：arch-guard **1m45s**、selftest **5m19s**。**默认 120s 超时会 SIGTERM 掉它们** ⇒ 本地请给 ≥300s / ≥600s 或后台跑。本波 selftest **未**卡 EXIT trap（干净退出）。

## Suggested skills

无必须项。接手时沿用「主理人侦察（含证伪挂账失实）→ 沈思远窄而深审计 + 文件面切分 → 柯码成 ×4 文件面互斥并行 → 崔续程 baseline/守卫收口 → 严质衡全量审查 → 修 P1/P2 → 主理人集成 → CI → **下载 lint-reports artifact 验证真实 issue 集**」SOP。
⚠️ **本波两条新铁律**：① 动 baseline 前必须读 artifact 里的**完整 message**（不能只看 id）；② 中文测试名里不得出现 `/`（改用「与」/「、」），已由 `arch-guard` 第 15 项静态拦截。
