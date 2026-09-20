# 修复的理论依据：为什么这些改动能保证"理论通过"

> 配套：`docs/06-device-verification-checklist.md`（真机逐项验证清单）
>
> **本文档的目的**：说明本轮 15 个提交中每一类改动，凭什么可以在**没有真机的情况下**判定为正确。
> **本文档的边界**：它证明的是"代码逻辑自洽、符合官方契约、与对标仓库一致"，
> **不能**证明"在真机上一定观察到预期现象"。后者只能靠清单逐项实测。

---

## 一、判定框架：什么算"理论通过"

一条修复被判定为理论通过，需要满足**至少一条**硬依据：

| 依据类型 | 强度 | 说明 |
|---|---|---|
| **① 平台行为契约** | 最强 | 该行为由 Android 官方明确定义（如协程取消语义、`CancellationException` 必须上抛、ViewModel 生命周期）。违反它就是 bug，符合它即正确 |
| **② 官方行为变更** | 强 | 针对特定 `targetSdk`/`minSdk` 的平台行为变化（如 16KB 页要求、edge-to-edge 强制、预测返回）。代码与变更后的一致即正确 |
| **③ 对标仓库实证** | 中强 | 同一依赖版本（LiteRT-LM 0.11.0）的官方/成熟实现怎么做。同版本同用法可直接采信 |
| **④ 逻辑/数学可证** | 中 | 可由代码推导证明（如复杂度从 O(n²) 降到 O(n)、边界条件全覆盖） |
| **⑤ 一致性/对称性** | 中 | 同一项目内两条等价路径的处理应当对称；不对称处即为缺陷（这是我们本轮发现最多的类型） |

**不能作为依据的**：直觉、"看起来更严谨"、以及"官方文档可能这么说过但没查证"。
本轮所有结论都标注了依据类型；凡是只有推断没有实证的，都列在第五节"无法理论保证"。

---

## 二、A 类：原生引擎与生命周期

### A1. 生成中释放 native 引擎导致 SIGSEGV（ENG-3，P0）
- **依据 ②**：Android 15 起对 16KB 页设备有明确要求，但这条崩溃与之无关，是**更基础的 native 生命周期问题**
- **依据 ④（逻辑可证）**：`LiteRtLmEngine` 的 `releaseInternal()` 会调用 `engine.close()` 释放 native 对象。
  若此时 LiteRT 的解码回调仍在进行，native 层即 **use-after-free** → SIGSEGV。
  这类崩溃**在 Kotlin 层无法捕获**（不是异常，是信号），`runCatching` 完全无效——这一点决定了它必须是 P0：
  一旦发生，既没有堆栈也没有日志。
- **改动的正确性**：在释放前先 `cancelProcess()` 主动请求停止，再等待 `activeGenerations` 归零；
  超时则**放弃本次操作并抛出可恢复的 `EngineException`**，而不是硬释放。
  "放弃"优于"硬释放"是可证的：前者最坏是用户看到一句提示，后者是进程死亡。
- **关键判定**：`ModelsViewModel.loadModel()` / `onUnload()` 直接持有 `engineFactory.create()` 的单例，
  **完全绕开 `AgentRunner`**（该处代码注释自己写明"这条路径完全不经 AgentRunner"）。
  因此加在 `AgentRunner` 上的互斥锁对这条路径**无效**，锁必须下沉到引擎自身——这是由调用图直接推出的结论。

### A2. `unload()` 与 `load()` 契约不对称（ENG-9，P1）
- **依据 ⑤（对称性）**：`load()` 在生成未收敛时抛异常，`unload()` 却静默 `return`。
  静默 return 后 `loaded` 仍为 `true`，而唯一调用方在 `runCatching` 后**无条件**提示"已卸载"
  → 界面与真实状态不一致。用户随后点"加载"时，同模型会走 `sameEngine` 短路（看似成功）、
  换模型才会抛错——两种表现都与刚显示的"已卸载"矛盾。
- **改动**：让 `unload()` 与 `load()` 同构抛错。安全性可证：全仓 `.unload()` 只有一个调用点，
  外层有 `runCatching`，抛出去不会扩散。

### A3. 卸载没有真正释放引擎（G-1，P1）
- **依据 ③（对标仓库实证）**：`google-ai-edge/gallery`（同为 LiteRT-LM **0.11.0**，已在
  `Android/src/gradle/libs.versions.toml` 核实版本一致）的 `cleanUp()` 是
  **先 `conversation.close()` 再 `engine.close()`**，两步都做、顺序固定。
- **改动后的正确性**：复用项目内已有的 `releaseInternal()`，语义即是"彻底卸载"。
  不会造成双重释放——可证：卸载后若再 `load()`，`sameEngine` 判据中 `loaded == false` 会短路为 false，
  走正常重建路径，不是重复释放。

### A4. 采样参数/能力位变更不生效（ENG-2、ENG-10，P1）
- **依据 ③**：gallery 每次创建 Conversation 都读取当前 config；
  `SamplerConfig` 是 **Conversation 创建期的不可变属性**（官方 `resetConversation` 在参数变化时用新参数重建会话）。
- **依据 ④**：`visionBackend` / `audioBackend` 属于 `EngineConfig`（**引擎构造期**参数），
  `createConversation()` 拿不到——所以它们变化时必须**整机重建**，只重建会话是无效的。
  二者的区分（sampling → 会话重建；vision/audio → 整机重建）由参数所属层级直接推出。
- **ENG-10 的额外可证点**：判据必须比较**解析后的值**而非用户配置的原始值。
  若比较原始值，`wantsVision=false`（引擎无视觉后端）时两侧可能都是 `null` ⇒ 判定"可复用" ⇒ 不重建 ⇒
  模型被标成支持视觉但底层没有视觉后端。这是由数据同源性推出的结论。

---

## 三、B 类：并发与协程取消

### B1. `CancellationException` 被吞（AGT-1，P0）
- **依据 ①（平台契约，最强）**：Kotlin 协程的取消语义要求——
  捕获 `Throwable` 后**必须重新抛出 `CancellationException`**，否则协程无法正常取消，
  `structured concurrency` 的结构化取消被破坏。这是语言/框架级契约，不是约定俗成。
- **改动的关键细节（可证的顺序问题）**：`TimeoutCancellationException` 是 `CancellationException` 的**子类**。
  因此必须先排除它、再上抛其余 CE，否则"工具执行超时"（本应是可恢复错误）
  会变成"整个 run 被取消"。顺序错了就等于把 P0 换了个方向重犯。
- **改动后**：工具执行期间用户点停止 → 主循环收到取消 → 不再发起下一轮推理。

### B2. read-modify-write 无锁导致丢数据（DAT-A6/B1/B2，P1/P2）
- **依据 ④（逻辑可证）**：`upsert()` / `appendMessage()` 都是"读快照 → 修改 → 整体写回"。
  两次调用交错时，后写的整份覆盖先写的，先写的那条**静默消失**。
- **改动**：加 `kotlinx.coroutines.sync.Mutex`。注意 **Mutex 不可重入**，
  因此 `ConversationRepository` 采用"`save()` 不加锁、由调用方各自锁住读-改-写全过程"的写法，
  并把原本转调 `rename()` 的 `autoTitle()` 改为自己 `save()`——否则会自锁挂起。这是可证的死锁规避。

### B3. 会话写入顺序（DAT-B4，P2）
- **依据 ④**：`save()` 会写 `<id>.json` 与 `index.json` 两个文件，两次写各自原子但**彼此之间没有原子性**。
  中间被杀时：若先写全文后写索引，则索引里没有该会话 → 用户在列表页**完全看不到**（无声丢失）；
  反过来则是"列表有一条、点进去是空的"（用户可见、可重建）。
- **改动**：交换顺序。正确性可证——两种失败形态中，后者严格优于前者（可见性差异）。

---

## 四、C 类：存储与文件

### C1. SAF 导入没有 `.part` 保护（DAT-A1，P1）
- **依据 ⑤（对称性）**：本项目**已有** `.part` 约定——DownloadManager 那条路径下到 `<name>.part`，
  扩展名不命中 `MODEL_EXTENSIONS`，因此扫描永远扫不到它；写完后才 `rename` 转正。
  但 SAF 导入这条路径直接写最终名 `xxx.litertlm` 且失败不清理 → 半截文件会被扫成"正常模型"
  → 用户点加载 → LiteRT 解析失败 → **native 崩溃**。
  两条导入路径不对称是它被漏掉的直接原因。
- **改动后的正确性**：走同样的 `.part` + rename，`finally` 里清理半截文件。

### C2. 主线程阻塞 IO（DAT-A2、DAT-B3、UI-06，P1）
- **依据 ②（官方行为）**：Android 的 **StrictMode** 会检测主线程磁盘/网络访问；
  主线程长时间阻塞触发 **ANR**。这两条都是平台明确行为。
- **额外风险（依据 ④）**：通过 `content://` 打开文件时，Provider 可能是**网盘**（Google Drive / 各家云盘），
  首次打开需要联网拉取——这是"可能耗时数秒"的 I/O，放在主线程必然 ANR 风险。
- **改动**：`ModelDownloader` 四个方法、附件导入、缩略图解码全部改为 `suspend` + `Dispatchers.IO`。
  调用点已逐个 grep 确认在 `viewModelScope.launch` 内，调用 `suspend fun` 合法。

### C3. 大文件解码/读取（ENG-1、AGT-5，P1/P2）
- **依据 ②（官方指南）**：Android 官方"高效加载大图"的做法是先
  `inJustDecodeBounds` 取尺寸、算出 `inSampleSize`、再按采样率解码——**绝不先全量解码再缩放**。
- **依据 ④（可量化）**：4000×3000 ARGB_8888 全量解码 = 约 48MB 一次性分配；
  `FileReadTool` 的 `readText()` 对 200MB 文件会产生约 400MB 的 String（UTF-16 双字节）。
  在已驻留 2~4GB 模型权重的进程里，这就是 OOM。
  且 `catch (t: Throwable)` 抓到 OOM 时已无内存可用，救不回来——所以防御必须前置到**打开之前**。
- **改动**：图片二级降采样；文件读取加体积闸门 + 流式读取前 N 字符。

### C4. 崩溃残留的临时文件清理（DAT-A3，P2）
- **依据 ④**：`write()` 生成的临时文件名是 `$fileName.<nanoTime>.<pid>.tmp`，
  而 `delete()` 只删固定名 `$fileName.tmp` → **永远清理不掉**。
- **改动的细节（可证的正确性）**：清理用**完整正则**而非 `startsWith(prefix)`——
  因为后者会让 `delete("index")` 连带删掉 `index.json.*.tmp`，属跨条目误删。

---

## 五、D 类：UI / Compose 与配置变更

### D1. 导航返回栈无限增长 + 返回键在页签间倒着走（APP-2 / UI-01，P1）

> **完整证据链与可复制的修复模板见 [`docs/09-back-navigation.md`](09-back-navigation.md)。**
> 本节只留结论。注意本条**被修过一次但没修对**：第一版只加了 `popUpTo`，
> 而那条 `popUpTo` 从写下起就是失效的——这是它挂这么久的直接原因。

- **依据 ②（平台行为变更）**：Android 14 起引入、Android 15/16 强化的**预测返回（Predictive Back）**。
  `OnBackPressedCallback` 具有向后兼容性，平台始终会调用它。
- **依据 ④（源码实证）**：已下载 `navigation 2.8.9` 的 sources jar 核实——
  `NavHost.kt:514` 有 `PredictiveBackHandler(currentBackStack.size > 1)`。
  因此只要返回栈有多条，返回键就会被**导航层**消费，变成"在上个页签间倒着走"，退出需按 N 次。
- **真正的根因（第一版漏掉的，也是这个坑最阴的地方）**：`startDestination` 与 composable
  注册的 route **不同源**。`NavDestination.kt:240` 与 `NavGraph.kt:522` 都用
  `createRoute(route).hashCode()` 生成 id，所以 `"chat"` 与 `"chat?conversationId={conversationId}"`
  是**两个不同的 id**；`popUpTo(graph.startDestinationId)` 因而永远匹配不到，而
  `NavController.kt:611-621` 对"栈里没有这个 id"的处理是**打一行 `Log.i` 然后 `return false`，
  一条都不 pop** —— 不抛异常、不崩溃。
  **即：`popUpTo` 写对了也照样失效，且没有任何报错。**
  首启却是正常的，因为 `NavGraphNavigator.kt:74-79` 找起始目的地走的是 **route 字符串匹配**
  而非 id —— "能启动"和"能正确 pop"是两件事，只验首启发现不了它。
- **另一半危害（依据 ①）**：每个 `chat` backstack entry 拥有独立的 `ViewModelStore`，
  所以每次切回对话页都是**全新 `ChatViewModel`** —— 草稿、滚动位置丢失，
  **正在流式生成的回答也从界面消失**（旧 VM 仍在后台运行）。这是 ViewModel 作用域的直接推论。
- **改动（两层，缺一不可）**：
  1. `startDestination` 改用 `ChatRoute.PATTERN`，与 composable 注册的 route **同源** → `popUpTo` 恢复生效。
     首启不崩的论证见 09 文档（`NavGraphNavigator.kt:90` 跳过参数合并；`conversationId` 为
     `nullable = true` + `defaultValue = null`，`NavArgument.kt:234` 的 `missingRequiredArguments` 为空）。
  2. 在 `NavHost` **之后**加显式两段式 `BackHandler`：非对话页或栈里还有别的条目时先回对话页，
     已在对话页且栈里只剩它则 `finish()` 退出。
  3. 兜底：`navigateTop` 之后强制 pop 到只剩栈底的对话页，让"再按一次退出"**不依赖 `popUpTo` 是否生效**
     —— 否则规则 1 一旦被破坏，退化结果是"按返回键毫无反应且无报错"。

### D2. 键盘遮挡（UI-09，P1）
- **依据 ②（官方行为变更）**：Android 15（targetSdk 35）起 **edge-to-edge 强制**，
  且 `setDecorFitsSystemWindows` 在 API 35+ 被禁用。
  在 edge-to-edge 下，键盘避让要么靠 `adjustResize` 真正缩小窗口，要么靠 `WindowInsets.ime`。
- **依据 ④**：`Type.ime()` 只报**键盘自身高度**，不含导航栏——因此正确的写法是
  `imePadding()` **与** `navigationBarsPadding()` **相加**（顺序：先 ime 后 nav），
  只加一个会少避让一段。
- **诚实标注**：`EndpointEditDialog` 是 Dialog（独立窗口），能否拿到正确的 `WindowInsets.ime`
  取决于该窗口的 `decorFitsSystemWindows` 设置。修复属**无害加固**——
  最坏情况是"没生效"，不会变坏，但需真机确认。

### D3. 每帧写 DataStore（UI-04，P1）
- **依据 ①**：DataStore 的 `edit{}` 是**事务性磁盘写入**（含 fsync）。
  滑块拖动以约 60fps 触发 `onValueChange` ⇒ 约 **60 次/秒**磁盘事务。
- **改动**：改为拖动时只更新内存态（preview）、松手 `onValueChangeFinished` 才落盘（commit）。
  与项目内 `ChatParamsPanel` 已有的 preview/commit 约定保持一致（依据 ⑤ 对称性）。

### D4. 配置变更状态丢失（UI-10/11/12，P2）
- **依据 ①**：Compose 中 `remember` 的值在**配置变更（旋转 / 折叠 / 深色切换）**后会被重建丢弃；
  需要跨配置变更保留的状态必须用 `rememberSaveable`。这是 Compose 状态 API 的基本契约。
- **其中一条后果最重（依据 ④）**：`FirstRunGate` 用 `rememberCoroutineScope()` 落盘"已同意"，
  而该作用域的协程会在**组合退出时被取消**——旋转正好触发。
  若 DataStore 的 `edit` 尚未提交就被取消，这次同意就丢了，叠加 `step` 由持久化标记重算，
  用户会看到**已经同意过的条款页再来一遍**。改用 `NonCancellable` 可证地解决。

### D5. 触摸目标与对比度（UI-13、UI-15，P2）
- **依据**：项目自己在 `GlassTokens.minTouchTarget = 48.dp` 定了标准；
  WCAG 对正文级文本要求对比度 **≥ 4.5:1**。
- **可量化验证**：`onGlassSubtle = 0x66` 叠浅色玻璃后实际渲染约 `#96969A`，
  对比度约 **2.6:1**（远低于 4.5:1），却承担大量 `labelSmall`（11sp）文本。
  改动后理论约 6.6:1。这是可计算的，不依赖真机。

### D6. 玻璃节点逐个模糊的开销（UI-07，P1）
- **依据 ④**：每个玻璃节点每帧都要分配离屏 RenderTarget、绘制壁纸层、跑一次 Skia 高斯模糊
  （半径 14~40dp，在 xhdpi/xxhdpi 下折算 **42~120 px**）。一屏十几个节点 × 60fps 即持续掉帧。
- **关于 `BlurEffect` 兼容性的实证**：已下载 `androidx.compose.ui:ui-graphics:1.10.3` 的 sources jar 核实——
  `BlurEffect(radiusX, radiusY, edgeTreatment)` 是 **@Stable 公开工厂函数，未废弃**；
  `RenderEffect` 的官方 KDoc 明确"仅 Android 12 及以上支持，更低版本被忽略"。
  本项目 **minSdk 31 = Android 12，全覆盖** ⇒ **API 31–36 区间无兼容缺口**。
  所以这是**性能问题**，不是兼容性问题——这个区分决定了它定 P1 而非 P0。
- **改动的取舍（诚实标注）**：审查建议的"模糊结果共享"（N 次模糊降为 1 次）需要重写每个玻璃节点的取样绘制，
  在没有本地编译与真机的条件下，改错的后果是**整屏玻璃糊掉或消失**；
  而它换取的纯粹是性能，不涉及崩溃或功能失效。
  因此本轮只做低风险的"设置页开关"，把大改造留到真机能看帧率之后——这是一个**风险收益判断**，不是技术判断。

---

## 六、E 类：数据一致性与本地化

### E1. `models.json` 解析失败导致永久丢失（MOD-2，P2）
- **依据 ④（可证的放大链）**：`SamplingParams.init` 的 `require` 在**反序列化**时抛异常
  → 整个 `models.json`（`List<ModelDescriptor>` → `InferenceConfig` → `SamplingParams`）解析失败
  → `refresh()` 把"只剩扫描到的"写回文件 → **用户手动登记的模型永久消失**。
- **修复方向的判定（本轮纠正过一次）**：正确方向是**容错**而非收紧。
  若把 `require` 收紧到 `> 0f`，会让历史文件中 `temperature == 0f` 的数据**也**抛异常，把问题放大。
  最终采用：`SamplingParams.init` **保持原样**，`refresh()` 在解析失败时**一个字节都不写**。
  可证：这两者结合后，即使反序列化失败也不会丢失数据，链条被切断。

### E2. Locale 敏感的格式化与大小写（AGT-6、AGT-7、MOD-3、UI-16，P2）
- **依据 ①**：`String.format` 使用**默认 Locale**。在德语/法语等地区小数点会变成逗号，
  阿拉伯语等地区甚至会出现不同的数字符号。
- **危害的可证性**：计算器工具输出 `3,3333333333` 时，模型可能把逗号当作千分位或列表分隔符
  → 后续推理出错，且**没有任何报错**（静默错误）。
- **同类问题**：`lowercase()` 在土耳其语 Locale 下 `"I" → "ı"`，导致重复检测签名不稳定。
  统一使用 `Locale.US` / `Locale.ROOT`。
- **依据 ⑤**：项目内 `ChatContextMeter.formatTokenCount` 早已规避了这个问题——
  两处口径不一致本身就是缺陷的证据。

---

## 七、F 类：凭据安全

### F1. 异常消息未脱敏上屏（ENG-8，P1）
- **依据 ④（泄露路径可完整推导）**：
  `RemoteEndpoint.name` 为空时回退为 `baseUrl`（`SettingsViewModel.onSaveEndpoint()`）
  → `OpenAiCompatibleEngine.load()` 的异常文案是 `"${remote.name} 需要填写 API Key"`
  → UI 直接 `error = "加载失败：${throwable.message}"` 上屏
  → 而用户自建反代的 `baseUrl` 完全可能带 `?key=sk-xxxx`
  → 凭据进入**截图 / 录屏 / bug report**。
- **取向的裁决**：有方案建议"UI 只显示通用文案"以回避风险。**已否决**——
  那会让"为什么加载不了"重新变成静默缺陷，与本项目"宁可多说话、防护放在出口"的一贯取向相悖。
  采用**上屏 + 脱敏**：复用 `AgentLogStore` 已有的 `sanitize()`（覆盖 `Bearer …` / `sk-…` / `?key=` 三类，
  **只替换值、保留参数名**，所以用户仍能看出是哪个参数漏了）。
- **有力的佐证（依据 ⑤）**：`ModelsViewModel` 同一 catch 块内，
  给**日志**的那条做了 `take(120)` 截断并明确不记端点/Key，给**屏幕**的那条什么都不做。
  同一个函数里日志比屏幕更小心——说明原作者清楚风险所在，只是漏了上屏这条路径。

### F2. 端点列表直接显示含凭据的完整 URL（ENG-11，P1）
- **与 ENG-8 的区别（可证）**：ENG-8 依赖"恰好抛出一个带凭据的异常"；
  **ENG-11 不经过异常消息**——只要用户保存端点，凭据就稳定显示在列表上并**落盘进 `endpoints.json`**。
  因此 ENG-8 那套修法覆盖不到它，必须单独处理。
- **改动**：治本（不用完整 URL 当端点名）+ 纵深防御（显示处再包一层脱敏）。

---

## 八、无法用理论保证、必须真机验证的项

以下各项**无法**通过本文档证明，只能靠 `docs/06-device-verification-checklist.md` 实测：

### 8.1 16KB 页设备的实际行为（最高优先级）
- **已做的取证**：下载 `litertlm-android-0.11.0.aar` 并解析 ELF 程序头，实测结果：

  | `.so` | PT_LOAD `p_align` | RELRO 末端 | 末端 % 16K |
  |---|---|---|---|
  | `liblitertlm_jni.so` | 0x4000 | 0xe9d000 | **4096（未对齐）** |
  | `libLiteRt.so` | 0x4000 | 0x4c8000 | 0（对齐） |
  | `libLiteRtClGlAccelerator.so` | 0x4000 | 0x348000 | 0（对齐） |

- **可下的结论**：Google 官方的 16KB 合规检查标准看的是 **PT_LOAD 段的 `p_align` 是否 ≥ 16384**。
  三个库**全部是 0x4000（16384），按官方标准合规**，且 `vaddr % 16K == offset % 16K` 全部成立。
- **不能下的结论**：只有 `liblitertlm_jni.so` 的 `PT_GNU_RELRO` 末端未按 16KB 对齐，
  这在运行时**是否真的导致 SIGSEGV** 取决于 linker 对 `mprotect` 向上取整的处理——**无法静态证明**。
- **因此**：本轮**没有升级 LiteRT-LM 版本**（会一次性引入大量未知 API 漂移，而当下无法本地编译验证），
  而是把它列为真机验证第一梯队。若实测崩溃，再动版本目录（那需要单独授权打破"禁止改版本目录"约束）。

### 8.2 `temperature = 0` 的真实语义
- 我们现在把 0 钳到 `0.01`；按 HF/llama.cpp 语义 `temperature <= 0` 才是**贪心（确定性）**，
  `0.01` 属于"极低温采样"，仍有随机性。而官方 gallery 直接把 slider 的 `0.0` 原样传给 `SamplerConfig`。
- **当前只改了注释，未动逻辑**。真机判据：同 prompt 连跑两次，输出完全一致 ⇒ LiteRT 内部已做贪心 ⇒ 应去掉钳制；
  若出现 NaN 或崩溃 ⇒ 保留钳制并记录机型。

### 8.3 8 条 preset 的 `sizeBytes` / `requiredRamBytes`
- 这是**唯一完全无法用任何静态手段验证**的项。它们决定了存储闸门是否误报、内存闸门该不该拦。
- 在此之前，卡片上的容量/内存提示都应视为**估算值**，仅用于提示，**不可作为不可绕过的判据**。

### 8.4 需要真机定性的"观感/手感"项
- 流式输出的跟随手感（`LaunchedEffect(streamingText)` 每个 token 重启一次滚动动画，是否够顺滑）
- `EndpointEditDialog` 的 `imePadding` 是否真的生效（见 D2 的诚实标注）
- 背景模糊开关前后的帧率差异
- `ENG-10` 修复后，发图到没有视觉后端的引擎时，native 层是**报错**还是**静默忽略**
  （这决定 ENG-10 最终定 P1 还是降 P2）

---

## 九、本轮的方法论产出：7 处被纠正的判断

审计过程中主动纠正了 7 处错误判断。它们比缺陷清单更有价值——
每一条都对应一个"照着做就会把好代码改坏"的陷阱：

| # | 被纠正的判断 | 依据 |
|---|---|---|
| 1 | 16KB 页"上架即崩"的 P0 → **降级** | 实测 `p_align` 全 0x4000，官方标准合规（依据 ④） |
| 2 | AGT-2 的 P0 → **降 P2** | `isStreaming`/`isGenerating` 只有同时置位/清除的写点，两标志恒等，当前不可达（依据 ④） |
| 3 | ENG-3 的 P1 → **升 P0** | 找到无需竞态的可达路径（模型页加载/卸载）（依据 ④） |
| 4 | MOD-3"收紧 `require`" → **改为容错** | 收紧会让 `temperature=0` 的历史数据也抛异常，放大问题（依据 ④） |
| 5 | 异架构 token 表含 `linux` → **去掉** | 会误杀自己的 preset（`Gemma3-1B-IT_cpu-arm64-linux.task`）（依据 ④） |
| 6 | `AgentLogFileStore` 与 `AgentLogStore` 串文件 | 前者在 core-data（落盘），后者在 core-model（内存环形缓冲） |
| 7 | "需新增下载前存储预检" → **本已有** | `StorageGateBlock` / `availableStorageBytes()` / `onDownloadIgnoringStorageGate` 已完整存在 |

其中第 4 条是我自己转述指令时下错的方向，被施工方以实证驳回后纠正；
第 1 条是我推翻审查员的定级。**两者都说明：结论必须以当前代码 / 实测数据为准，不能依赖转述或旧快照。**

---

## 十、结论

- **可以理论保证的**：A、B、C、D、E、F 六类共 **33 项**改动，均满足第一节的至少一条硬依据，
  且均在 CI（`:app:assembleDebug`）验证范围内可编译。
- **无法理论保证的**：集中在第八节，共 **4 大类**，全部需要真机实测。
- **残留风险**：16KB 页设备的实际行为是唯一可能导致"上架级问题"的未决项，已列为验证第一梯队；
  其余未决项均属**体验/观感/数值校准**，不阻塞真机测试的启动。
