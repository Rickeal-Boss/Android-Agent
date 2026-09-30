# Wave 36 交接：挂账清零 + 静态清障 + 沙箱下钻

> 写于 2026-09-30 上午。基线 `7c2ff84`（Wave 35 收尾，上一功能 tip `1ffd1c1` 已双绿）→ 本波 tip **`7d35200`**（7 commit）。
> **CI 双绿**：Build [36652053917](https://github.com/Rickeal-Boss/Android-Agent/actions/runs/36652053917)（Assemble Debug / Unit tests / Lint gate 三 job 全 success）+ Release [36652053864](https://github.com/Rickeal-Boss/Android-Agent/actions/runs/36652053864)（success）。
> **产物**：release APK **70.2 MB** + debug APK **39.3 MB** + R8 mapping **4.3 MB**（Release 轨）；Build 轨另有 debug APK 39.3 MB + lint baseline/reports。
> 团队 SOP：沈思远（范围裁决 + 设计决策 + 文件面互斥切分）→ 柯码成 ×3 并行（G1 引擎+通知 / G2 记忆 / G3 沙箱）+ 崔续程（CI 注释纠偏，与开发并行）→ 严质衡（全量审查：**0 P0 / 0 P1 + 7 P2**）→ 三个开发各修 P2 → 主理人（复核 / 集成 7 commit / 推 CI / 轮询双绿）。

---

## 1. 本波交付（E1~E6 + E8a）

| ID | 内容 | 性质 | commit |
|---|---|---|---|
| **E1** | `GenerationNotifier` 三条降级出口（缺 `POST_NOTIFICATIONS` / 通知被关 / `notify` 抛异常）此前**共用单个进程级布尔闩** ⇒ 第一条命中的出口置闩后另外两条**永不落痕**。改为按 `NotifyWarnReason` 三分类各闩一次 | **真实行为缺口** | `72685a8` |
| **E2** | `EngineContract` 的 `nativeToolChannel` KDoc 原称「与 `capabilities()` 是同一判据」——**不实**。改为如实描述（两个不同表达式 + 唯一真实分叉路径） | 文档真实性 | `13fd779` |
| **E3** | 判死删除 `AgentMemory.renderForPrompt`（全仓零引用，已被 `renderIndex` 取代）+ 四要素留痕；顺带清掉因此变死引用的 `truncateSafe` import | 债 | `6124fed` |
| **E4** | `toToolProviders()` 原在 `ensureConversation` 顶端、**早于** `existing != null` 早退 ⇒ 主循环每轮白造一整批 `ToolProvider` 再丢弃。下沉到早退之后 | 性能/结构 | `fc4cd54` |
| **E5** | 设置页粘贴超长记忆正文时**完全静默**（对话框关闭、无提示、**输入丢失**）。改为失败**不关框**、原因内联显示在对话框内、只有成功才关；顺带修 `remove` 的自反 no-op、删僵尸字段 `corrupted` | **真实 bug** | `6124fed` + `2262630` |
| **E6** | 沙箱文件浏览器由「仅根层」改为**逐层下钻**（非递归）+ 结构化路径穿越防护 + 面包屑 + 上下文返回 | 用户可见功能 | `9bd3a3b` |
| **E8a** | `build.yml` 陈旧声明纠偏（`abortOnError` 实况已翻转 / 「尚未点火」实况是 9 模块全有测试源集 / 3 处运行时 summary 误导文案） | CI 治理 | `7d35200` |

**改动量**：15 文件，+679 / −122；新增 3 个测试文件、**新增 14 个用例**；新增 1 个生产类（`NotificationWarnLatch`）。

---

## 2. 关键设计决策（含对外部输入的两处纠偏）

### 2.1 E1 分闩形态：按**原因枚举**分闩 + 提炼纯 Kotlin 闩类
- **不按 `Set<String>`**：`NOTIFY_FAILED` 的 reason 含**动态异常类名** ⇒ 按串分闩会让闩集合无界、语义模糊；按固定三类分闩**上界恒为 3 行/进程**，天然满足 `onTick` 每秒一次的不刷屏要求。
- **可测缝隙**：本仓无 Robolectric / mockk ⇒ `AndroidGenerationNotifier`（依赖 `Context`）本体不可 JVM 测。把闩提成**零 Android 依赖**的纯 Kotlin 类（`Int` 位掩码 + `@Synchronized`），即可直测「同 reason 一次 / 三 reason 互不干扰 / 并发恰一次」。
- **行为变化申报（有意交付）**：单进程内 warn 行数由「恒 1 行」变「最多 3 行」——这正是修复本质。

### 2.2 E5 失败原因如何传出：**不改 core 签名**
`AgentMemory.upsert` 的 `false` 出口**只有两条且互斥穷尽**（正文超 `MAX_CONTENT_CHARS` / `ReadState.Corrupted`）⇒ 按「去空白后的正文长度」即可在 UI 侧**无歧义**还原原因。
- 拒绝的备选：改返回类型为 sealed/enum —— 多出的价值（`WriteFailed`）不解决本缺陷，却要付 4 文件改动 + 既有 5 处 `Boolean` 断言重写 + 破坏性签名变更，且 `WriteFailed` 分支在纯 JVM 里**极难构造**（拿不到测试覆盖）。
- **判断法则（可复用）**：**「要不要改 API」前先问「出口集合是否互斥穷尽」**。

### 2.3 E6 逐层下钻（**不递归**）与路径安全
- **不递归**的理由：`scan()` 本来就是「扫一层」；递归会把 `totalEntries`/`truncated` 语义从「本层」改写成「整棵子树」，与工具页入口卡的「根层」口径打架（A7 已修过的坑），且撞上 `SandboxFileScanner` KDoc 已预警的 journal 大目录时间预算问题。
- **`relativePath` 恒为 root-relative**，分隔符恒用**字面 `'/'`**（不用 `File.separator` —— CI Linux / 本地 Windows 会产出不同路径串）⇒ `onPreview` 与 `openSandboxFile` **零改动**；`file_paths.xml` 的 `files-path path="agent_sandbox/"` 是**目录前缀**，整棵子树本就在 FileProvider 覆盖内。
- **路径安全 = 三层结构化判定**（`resolveWithinSandbox`）：① 拒绝对路径 ② **段白名单**（`/`、`\` 都当分隔符，拒空段 / `.` / `..`，**不用字符串 `contains("..")`**）③ canonical 前缀比对兜**符号链接逃逸**。**两层必须叠加** —— 只有 canonical 会因平台实现差异漏判，只有段白名单挡不住符号链接。fail-closed（解析异常 → null）。
- **纵深第二层**：对目录条目做 canonical 包含性检查，把「指向沙箱外的符号链接目录」从 listing **剔除**（目录是下钻的导航向量，放行会让用户一路点出沙箱）。KDoc 注明这是**有意过滤**。

### 2.4 主理人的两处纠偏记录
1. **E2 的「分叉场景」我一开始给多了**：我要求写「通道激活 ∧ 本轮无工具 ∧ **`createConversation` 失败**」，柯码成反驳并给出证据 —— `registeredToolsSignature = if (roleChannelActive && !toolsDroppedOnRetry) toolsSignature else null`，工具集为空时**恒为 null**，与是否失败无关 ⇒ 分叉在**正常建成**路径上就成立。**已核实其反驳正确**。另：原 KDoc 把「证伪重试」列为分叉场景也是错的（该路径会置 `nativeToolsRejected = true`，使引擎级同步翻 false）。
2. **我上一轮给 G2 的 E5 理由是错的**：我说「`message` 在列表末尾会被滚出视野」，实况是 `LazyColumn(Modifier.weight(1f))` 之后的**固定 Box，常驻可见**。`editError` 的选择依然正确（理由改为「写失败原因属编辑上下文，应与输入同框就近显示」），但 KDoc 已按事实改写。

---

## 3. 严质衡审查结论（0 P0 / 0 P1 + 7 P2）

**独立复算成立的三项**（非「看起来没问题」）：
- **E4 等价性**：四情形真值表逐格核对（含「通道激活但本轮工具为空」）—— 新旧 `toolsSignature` 皆产 `null`，**不会**出现空串 `""` 导致每轮 re-prefill；grep 证实 `nativeTools` 在早退前**零引用**。
- **E6 路径穿越**：逐一构造逃逸（`..` / `a/../..` / `/abs` / `\abs` / `a//b` / `a/./b` / `a\..\b` / UNC / **符号链接**）—— **全部被挡**；目录逃逸过滤**不误伤**既有回归锚测试。
- **E5 交互闭环**：失败不关框 / 错误可见（`colors.danger` 为本文件既有色）/ 成功才关 / `editError` 残留路径全查 / 长度口径与 `upsert` 的 `>` 判据同源。

**P2 处置**：
- **随本波修**：P2-1（E2 分叉场景不实）、P2-3（测试 KDoc 通道写错）、P2-4（`MemoryUiState` KDoc 理由不实）、P2-5（下钻在途连点会拼出幻影路径 `sub/sub` → 加在途守卫）、P2-7（补「`\` 在 POSIX 属**有意过度拒绝**、无逃逸」的取舍留痕）。
- **挂账**：P2-2（写盘在途取消后 `editError` 可能串台；ms 级窗口、可自愈、非回归）；P2-6（子目录里按**系统返回键**走 app 级两段式策略跳回对话页，非本波引入，且是该 app 既有明文约定）。

---

## 4. 本波新教训（复用价值高）

- **🔴 挂账描述本身可能是错的**：Wave 35 挂账写「设置页超长粘贴会看到**误报『文件已损坏』**」——实况是设置页**从无该文案**（那句在 `MemoryTools.kt` 的**模型工具**错误串里，且已被前置校验分流）、`MemoryUiState.corrupted` 是**被赋值但无人消费**的僵尸字段、真实症状是「对话框关闭 + 无提示 + **输入丢失**」（更糟）。⇒ **挂账只作线索，动手前先回源码核验症状。**
- **🔴 「失败可见化」要查三件事，缺一即假达成**：① 状态**是否真被 UI 读取**；② 承载提示的组件**是否在用户视线内**；③ **用户输入是否被保留**（`MemoryEditDialog` 的「保存」按钮**自己就调 `dismiss()`**，与父层回调无关 ⇒ 失败必丢输入）。
- **🔴 复审给的「必要条件」也可能多一条**（本波主理人自己犯的，见 §2.4-1）。**凡「两条路径都会分叉」的断言，先查两条路径是否都置了同一标志位。**
- **列表在途不清 `entries` ⇒ 连点会拼出幻影路径**：`navigateInto` 用**过期**的 `currentDirPath` 计算父路径 ⇒ 连点两次得 `sub/sub`。修法 = 在途守卫 `if (_uiState.value.loading) return`（读真源，不用 collect 快照）。
- **`viewModelScope` 默认 `Dispatchers.Main.immediate`** ⇒ `launch` 体内首个挂起点之前**同步执行** ⇒ 「置 `loading = true`」在调用栈内立即生效。
- **infix 函数优先级高于 `!=`**：`warnedMask and bit != 0` 按 `(warnedMask and bit) != 0` 解析（正确），但**加显式括号**可让读者不依赖优先级知识。
- **提 `File.separator` 前先想 CI 平台**：跨平台一致性的路径串必须用**字面 `'/'`**；只有与 `canonicalFile.path` 比对时才该用 `File.separator`。
- **注释里的跨文件行号一律标快照**（「Wave N 时位于」）并同时写出表达式本身，防行号漂移。

---

## 5. 挂账（Wave 37+，按优先级）

1. **`AgentMemory.writeSync` 吞落盘失败 + `upsert` 恒回 `true` = 静默成功**（本波新发现，独立于 E5）：磁盘写失败时用户看到「已记住」但实际未落盘。修它需要「记忆存储契约」级改动（返回类型改 sealed/enum，波及 `MemoryTools` + 5 处 `Boolean` 断言），配专门测试设计。**建议独立一波。**
2. **E7 lint baseline 清障**：起手式 = **先读上一轮 CI 的 `lint-reports-<sha>` artifact**（保留 14 天）确认真实 issue 集与 baseline 是否已因代码移动而**失配**（Wave 34/35/36 大改过 `LiteRtLmEngine` / `AgentMemory` / `SandboxFileScanner` / `MemoryScreen` 等，这些文件若有 baseline 条目则大概率已错位）。**这是唯一零 CI 成本的事实来源**，动任何 lint 代码前必须先做。本波不做 E7 的三条硬理由：位置指纹级联、UseKtx 需**全仓首次 import**（`androidx.core.net.toUri` / `androidx.core.graphics.extension.scale` 全仓 0 命中）、离线不可自证。
3. **E8(b)**：`build.yml` 的 lint step 仍带 `continue-on-error: true`（观察期）；摘除前提已写入 workflow 注释。
4. **P2-2 / P2-6**（见 §3）。
5. **E9**（arch-guard 编号缺 11，纯注释）、**E10**（diagnostics 卡片化，无缺陷无 testable seam）。
6. 历史挂账仍在：**原生工具通道「同 run 内恢复」**、**工具 schema 形状真机实证（R1，唯一无法离线验证项，决定题 A 成败）**、记忆向量检索（禁引新依赖）/ 自动去重 / 按会话分区、**THIN 0.21**（已跨 5 波未裁决）、路径 B 列表回看优化（`DrawBackdropModifier` 每帧全量重录，待真机帧时间实测）。
7. **`MemoryScreen.kt:46` 的 `LocalAppContainer` 是 HEAD 既有的死 import**（非本波引入，未顺手改 —— 避免在审查关闭后引入未审改动）。可顺手清。

---

## 6. 真机验收清单（CI 查不出，adb 解禁后执行）

**Wave 33 §6 的「Gemma GPU 加载引擎错误」仍是用户标记的待解决项，观测基建已就位。**

1. **E6 沙箱下钻（本波新增，优先验）**：进入子目录 → 面包屑是否显示 root-relative 路径、顶栏返回是否**上溯一层**（根层才退出子页）；空子目录文案是否为「此目录为空 / 点左上角返回上一层」；子目录内文件「预览」与「打开」是否**打开的是同一个文件**（`relativePath` 恒 root-relative 的验收点）；造一个指向沙箱外的符号链接目录，确认**不出现在列表里**。
2. **E5 记忆失败可见化**：在设置页粘贴 >2000 字符正文 → **对话框应保持打开**、输入**不丢**、输入框下方出现红色原因文案；改短后可直接重试成功。
3. **E1 通知分闩**：关掉通知权限后开「生成速度通知」跑一次生成 → 诊断页应出现**一条**权限 WARN；再单独关掉通知渠道（权限保留）→ 应出现**另一条**渠道 WARN（旧实现在这里恒静默）。
4. **E4 性能回归观察**：长工具会话（10+ 轮）中 grep `LiteRT-LM 会话重建` —— 应只在工具开关变化 / 系统提示词变化 / 上下文版本变化时出现，**不应每轮出现**。
5. **E2 诊断小字**：开启原生工具通道但本轮无工具启用时，会话级与引擎级判据可能不一致（已知分叉）—— 确认 UI 小字不误报。
6. Wave 33 遗留：`preface 渲染诊断` / `preface 校验失败` / `角色通道播种失败` / `会话重建原因` 四组关键字；`GPU 后端不可用` 后跟的错误原文。

---

## 7. 相关产物

- 提交区间：`7c2ff84..7d35200`（7 commits）
- 反查素材：`_research/models/_l171/cls`（litertlm 0.17.1 classes）、`_research/models/litertlm171_*.kt`（源码快照）
- 工具：`_ci-tools/{ghapi,gitfetch}.sh`（GitHub API + 推送，**PAT 唯一落盘处 = `ghapi.sh`**）、`_ci-tools/wait_w36.py`（CI 轮询模板）
- 上一波：`docs/handoff-20260930-002142-wave35-closing.md`
- 本地静态闸门：`bash scripts/arch-guard.sh`（14 项）、`bash scripts/arch-guard-selftest.sh`（PASS=17）、`python balance_check.py`
  ⚠️ **本机 Git Bash 下 arch-guard 约 1.5–2 分钟、selftest 约 3–4 分钟**（第 13 条逐行 spawn 子进程），**默认 120s 超时会 SIGTERM 掉它们**；selftest 还会在输出完整 `PASS=17 FAIL=0` 后卡在 EXIT trap（本沙箱 `rm` 包装产物）。**本地请给 ≥300s / ≥600s 或后台跑**；CI（Ubuntu）不受影响。

## Suggested skills

无必须项。接手时沿用「沈思远方案 → 柯码成 ×N 文件面互斥并行 → 严质衡审查 → 开发修 P2 → 主理人集成提交 → CI 双绿」SOP；**三组文件面必须核验零重叠**才可并行。
