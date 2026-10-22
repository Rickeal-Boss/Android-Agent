# W78 设计：后台/锁屏保活强化（通知契约 + OEM 文案 + 证据分级）

> 基线：`UI-change-1019` tip `92f65d6`（W77 收官）｜真机 OPPO PDRM00 / ColorOS / **Android 13（SDK 33）**
> 性质：**动手前的裁决 + 可直接实现到行**的接口契约。本文件不改任何代码。
> 输入：五份外部审查（复审31 合卷 / 深度审查·改进效果 / 深度审查·OEM 专项 / oem-background-adaptation-review / oem-official-docs-matrix）+ 源码逐行复核。
> 铁律：不改 run 生命周期上提（保持 `viewModelScope`）、不碰 `core-agent`/`core-engine`、不引新依赖、不留死代码、测试钉属性不钉手段。

---

## 〇、一句话结论

W77 把「进程不被冻结」这半步做成了真的，但**三处「把未达成呈现为已达成」**必须收口：
① 通知 id 共用使第二轮起可能「已前台化但用户不可见」（B-2 / 第二轮 B-1，被单测钉死成设计）；
② 设置卡用 AOSP 单一口径回答 OEM 效果问题，双向误导（B-1）；
③ 9 族文案里只有 ColorOS 有真机证据，而卡片把未验证的路径与已验证的路径呈现为同一种口吻（第二轮 B-6）。

本波做五件事：**通知所有权拆开**（D2）、**卡片语义解耦**（D3）、**文案按官方源补全到 9 族并压进长度预算**（D4）、**证据强度数据化 + 上守卫**（D5/D8）、**WakeLock 周期续期**（D9）。不做：停止 action（D7）、深链、A14+ 补测、其余 8 族真机取证。

> **D9 是本次补充里最要紧的一条**：审批挂起无超时（`ToolApprovalHandler.onApprovalRequested` 裸 suspend）且挂起时长被加回预算（Wave 52 B1 停表）⇒ run 的墙钟存活时间**无上界**，而锁 390s 后静默自释、无重取路径。用户在审批框前放下手机 7 分钟，回来批准时 CPU 已深睡 —— 这条比通知问题更直接决定「能不能跑完」。

> ⚠️ 流程提醒（不属本设计范围）：复审31 §六.1「三件套效力裁决」仍悬空，需总裁在 W78 开工前明确 A/B，否则本波会续犯「披露缺口」。

---

## 一、十一点裁决（结论 / 依据 / 代价 / 本波做否）

### D1 `supportsRecentsLock`：**删除**（既不改值，也不接线）

| 项 | 内容 |
|---|---|
| **裁决** | **删除函数**；把「最近任务下拉加锁」的事实修正**直接写进各族 `path` 文案**；再用**9 族全量断言**把「文案含加锁的族集合」钉死。 |
| **依据（源码为准）** | ① 全仓生产**零消费**——`grep -rn supportsRecentsLock` 仅命中 `OemBackgroundPolicyTest:117-121`（复审31 §四 N2 成立）；② 口径自相矛盾成立：`OemBackgroundPolicy.kt:116` 对 `ORIGIN_OS` 返 `true`，而 `guideText(ORIGIN_OS)`（:103-105）**没有**加锁文案；③ 第二轮 A-1 的事实也成立：华为/荣耀官方均支持下拉加锁，代码判 `false`（:117 `else -> false`）是错的；④ 测试只断言 5/8 族（`OemBackgroundPolicyTest:116-122`），EMUI/MAGIC 恰好漏测。 |
| **为什么不是「改值但保持不接线」** | 那仍然是死代码，且会变成**第二事实源**：`supportsRecentsLock` 与 `guideText` 各自表达「支不支持加锁」，漂移就是 N2 那条 bug 的复现形态。本仓铁律「不留死代码」直接否决。 |
| **为什么不接线（A-1 的隐含建议）** | 接线只能做成「`path` + 统一后缀」，但**加锁在 9 族里位置不同**：ColorOS/HyperOS/OriginOS/MagicOS 是**增强**（末尾追加即可）；`EMUI_HARMONY` 在 HarmonyOS 5+ 是**替代路径**（不是增强，必须写成「5+ 改用下拉加锁」）。统一后缀会与 5+ 分叉文案重复甚至冲突。⇒ 「支不支持加锁」这条知识**属于文案本身**，不属于代码分支。 |
| **代价** | 删 5 行 + 删 1 个测试方法 + 改写 1 个测试方法（5 族断言 → 9 族全量）；EMUI/MAGIC/ORIGIN 文案各加 1 句（见 §二.5）。 |
| **本波做** | ✅ |

### D2 通知 id：**FGS 独占 1002，速度通知「借道」刷新同一条**（服务是唯一写者）

| 项 | 内容 |
|---|---|
| **裁决** | `RunForegroundPolicy.ONGOING_NOTIFICATION_ID = 1002`；速度通知 id 保持 1001；**FGS 活跃期间，速度文案交给服务刷新 1002**，notifier 不再自己发。`GenerationNotifier.stop()` **一行不改**（永远只 cancel 1001）。 |
| **依据（源码为准，与报告有出入）** | ① 复审31/深度审查称 `stop()` 有 5 处调用；**实为 8 处**：`ChatRunCoordinator:204/542/729/872/1108` + `ChatViewModel:550` + `SettingsViewModel:180` + `LiquidAgentApplication:53`（`grep -rn "generationNotifier.stop()" --include=*.kt` 10 命中，去掉 2 条注释行）。⇒ **改 id 比改 8 个调用点稳得多**，这是本裁决的量化理由；② `RunForegroundPolicyTest:67` 把 id 共享钉死（`assertEquals(1001, …)`），是「钉手段」的典型，必须换掉；③ 失败链已被源码确证：run1 终态 `stop()` → `cancel(1001)` → `isBusy=false` → 控制器 `delay(10s)`；窗口内新 run 因 `started=true` **不会**重新 `startForeground()`（`AgentRunForegroundController.kt:57` + `RunForegroundPolicy.shouldStart`）⇒ 关掉速度通知的用户整轮无通知。 |
| **为什么不选 ②（两 id 并存 + `setGroup`）** | 分组**不消除重复**：展开后仍是两条，且 Android 会给组加一条 summary；用户划不掉的常驻通知从 1 条变 2 条 —— 正是 W77 §五 想避免的形态。 |
| **为什么不选「notifier 自己写 1002」** | 两个 builder 写同一个 id：notifier 的 builder 没有 `contentIntent`（`GenerationNotifier.kt:139-147`），会把 FGS 通知刷成**点不动的死通知**；渠道/`setOngoing` 也会来回翻。⇒ **单写者**是硬要求。 |
| **代价** | 新增 1 个 ~45 行纯 JVM 可测的小对象（`OngoingNotificationSlot`）+ 服务加 ~30 行（attach/detach/refresh）+ notifier 抽 1 个 `buildText` + 换 1 个单测 + 新增 FGS 专用通知渠道（1 个常量 + app 侧 8 行）。控制器 / 8 个 `stop()` 调用点 / `ChatRunCoordinator` **零改动**。 |
| **跨模块依赖方向** | `core-data/background` → `core-data/notify`（既有方向，服务原本就 import `AndroidGenerationNotifier.CHANNEL_ID`）。**反向禁止**：slot 必须放在 `notify` 包，不能让 `notify` 反向依赖 `background`。`core-agent` / `core-engine` 全程不可见、不参与。 |
| **本波做** | ✅ |

### D3 设置卡语义解耦：**状态行只报 AOSP 事实，效果限定行独立成句**

| 项 | 内容 |
|---|---|
| **裁决** | 三块信息拆开，且**删除效果承诺句**：<br>① 状态行（二态，只报事实）：`已加入系统电池优化豁免` / `未加入系统电池优化豁免`；<br>② 限定行（恒定，OEM 相关）：`仅 Android 系统层面；厂商可能另有独立的后台管控（见下方路径），本项不覆盖。`；AOSP 族：`仅 Android 系统层面；本机为接近原生的系统，无额外厂商管控。`；<br>③ 路径行（族名 + **证据括注** + 路径）。 |
| **依据** | ① 深度审查 §二 B-1 的双向误导表 + W77 自己的 A/B 双臂实验（AOSP 白名单 `true` 但 `OplusProxyWakeLock` 仍 ~10s 回收）；② 现行文案 `SettingsScreen.kt:630-634` 的「切后台或锁屏时，系统更不容易冻结本应用」是**效果承诺**，在 OEM 上被本波实验证伪 ⇒ 必须删；③ OEM 侧**不可程序化检测**（`OemBackgroundPolicy.kt:19-20` 已写「无公开 API」）⇒ 限定行不能写成可检测的二态。 |
| **代价** | 卡片 2 处文案改 + `SettingsUiState` 加 2 个字段（`oemVendorManaged` / `oemEvidenceNote`）+ `BackgroundRestrictionStatus` 加 2 个一行方法。 |
| **本波做** | ✅ |

### D4 OEM 文案范围：**8 族 → 9 族（新增 NUBIA）**，逐条按「官方源可查」补强

| 子项 | 裁决 | 理由 |
|---|---|---|
| 新增 `NUBIA`（nubia / ZTE / RedMagic） | **做** | 官方协议页（`externdevice.nubia.com/agreement/explain_cn.html`）证明中兴系有独立「智能耗电保护」；现归 AOSP = 给出**已知不足**的建议。红魔是游戏向机型，恰是端侧 AI 目标用户群。成本 ~15 行 + 2 断言。 |
| ColorOS 补「自启动 / 关联启动」两开关 | **做** | 官方白皮书 + 三方一致：耗电管理页内是三个独立开关；现文案只写 2 个。**诚实限定**：真机只实测了「允许完全后台行为」这一项有效（W77-04 四轮），另两个开关未单独 A/B ⇒ 证据档仍 `MEASURED`，但锚点必须写明「实测项 = 允许完全后台行为」。 |
| HYPER_OS 补「应用智能省电」官方名 + 后台自启动 | **做**（合并进 1 行） | O1 是两份报告共同的 P2：缺「后台自启动」则 FGS 被杀后无法自愈；「应用智能省电」是小米官方文档（`dev.mi.com pId=1628`）的正式名。 |
| EMUI/HarmonyOS 5+ 分版本 | **做，但降级为半句** | 5+ 菜单已改版，现文案会指向不存在的位置 —— **给错路径比不给更糟**。不新增枚举（版本号不可靠可得：`Build.VERSION.RELEASE` 在 HarmonyOS 上是 Android 兼容层版本，不是 HarmonyOS 版本），只加「HarmonyOS 5+ 改用下拉加锁 + 关闭省电模式」。 |
| MAGIC_OS 补「电池优化」第二步 | **做** | 荣耀官方把它与启动管理**并列**为必要项（`honor.com/cn/support/content/zh-cn00406916`）；现文案只覆盖第 1 步。 |
| ORIGIN_OS 补「加速白名单」 | **做** | 官方/三方一致：这是**独立于电池优化的一道杀**（一键加速直接清）。 |
| ORIGIN_OS 补「应用冻结」 | **不做** | 菜单名跨版本差异大（`设置→电池→应用冻结` 并非所有 OriginOS 版本存在），且与加速白名单语义重叠；给错路径的代价 > 收益。挂账。 |
| ONE_UI 补第二路径（应用信息→电池→不受限制） | **做** | 三星开发者文档（`developer.samsung.com/mobile/app-management.html`）说明两套口径互斥等价，第二路径**一步到位**，比翻四列表快。 |
| FLYME 补自启动 + 层级名（隐私权限） | **做** | 官方层级名与现文案不同（「隐私权限」vs「权限管理」），版本差异 → 并列写。 |
| FLYME 补「最近任务加锁」 | **不做** | 证据仅 COMMUNITY 级（社区口径），且加错会白耗用户时间。挂账为「待官方/真机确认」。 |
| AOSP | **不动** | 标准路径，成立。 |
| **长度预算（硬约束）** | **每族 `path` ≤ 80 字符**（CJK/ASCII 各计 1）；实测 9 族落在 26–78；**整卡 ≤ 16 视觉行**（通知权限缺失时 ≤18，因多出第 7 块披露；`labelSmall` ≈ 11sp，屏宽 360dp，内边距 14dp×2 ⇒ ≈26–28 汉字/行）。超预算时按此顺序裁剪：**证据括注 → 尾注的「部分机型还会在灭屏后限制 CPU」→ 限定行**。 | 文案越长卡越难读；W77 卡已 10 行，本波只允许涨到 16 行（缺通知权限时 18 行；其中 +1 行来自 W78-11 的机制点名句，见 §2.7 第 8 块）。 |
| 本波做 | ✅（除 ORIGIN_OS 应用冻结、FLYME 加锁） | |

### D5 证据强度：**做，3 档（不是 4 档），且必须被生产消费**

| 项 | 内容 |
|---|---|
| **裁决** | 新增 `OemEvidence { MEASURED, OFFICIAL, COMMUNITY }`，每族一档 + 一行锚点，落在 `OemBackgroundPolicy` 的数据表里（不是注释）。**消费方有两个**：① 卡片路径行的括注（`evidenceNote()`，用户可见）；② 守卫 #46（机械判据）。 |
| **为什么是 3 档而不是报告建议的 4 档** | `OFFICIAL_DOC` 与 `OFFICIAL_SUPPORT` 的边界**不可机械判定**（荣耀的支持页算 DOC 还是 SUPPORT？小米开发者文档 vs 小米社区客服口径？）⇒ 边界一模糊，守卫就退化成「评审看措辞」，违反「判据必须可机械判定」。合并为 `OFFICIAL`（域名属厂商官方/官方开发者站）后边界清晰：**官方域名 vs 第三方**。 |
| **与 `ModelPresets.evidenceLevel` 是否同构** | 同构的是**纪律**：证据必须数据化、`MEASURED` 必须能指认本仓波次锚点（`W##`）。不同构的是**取值**：不复用 `ModelPreset.EvidenceLevel`（那是模型容器面 schema，混用会让 #39 的判据语义扩散）。 |
| **是否上卡面** | **不上枚举本身**，只上**一句人话**（`本机已实测` / `官方说明，未在本机实测` / `社区经验，未在本机实测`）。理由：用户不需要知道我们的内部分级，需要知道「这条路可信到什么程度」。`OFFICIAL` 的括注刻意**不带「厂商」二字**——AOSP 族的官方源是 Google，写「厂商官方说明」在那里读不通。 |
| **死代码风险处置** | `evidence` 有生产消费（`evidenceNote()`）。`evidenceAnchor` 的唯一消费者是守卫 #46 —— 这与 `ModelPresets.evidenceLevel` 的**现状完全同构**（全仓零生产读点，仅 #39 消费），本仓已接受该形态：**证据数据不是逻辑，它的消费者是判据**。此条明写在此，防止下一波有人以「死代码」为由删掉它。 |
| **代价** | 1 个 enum + 1 张 9 行表 + 2 个访问器 + 1 条守卫 + 2 个断言。 |
| **本波做** | ✅ |

### D6 通用兜底两句：**都做，且都不新增行**

| 句 | 落点 | 理由 |
|---|---|---|
| ①「部分 ROM 可能在灭屏后限制 CPU，即使完成上述设置」 | **卡片尾注**，与既有「耗电与发热 / 不保证不被回收」合并成一段（尾注 64 字符 ≈ 2–3 行） | 它是**效果免责**，与耗电/发热/不保证同族；放进 9 族 `path` 会 9 份复制、9 份长度预算。 |
| ②「若路径不符，可在设置顶部搜索框输入『电池优化』/『应用启动管理』定位」 | **并入既有的「路径可能因系统版本略有差异。」那一行**（`SettingsScreen.kt:653-659` 已存在） | 它是**导航兜底**，紧跟路径读最有用；同样 9 份复制的问题。并入既有行 ⇒ **零新增行数**。 |
| 是否合并成一句 | **否**：① 是「效果」② 是「导航」，合并会让用户在找菜单时读到一句关于 CPU 的话，语义串味。 | |

**顺带裁决 N3（复审31 §四）**：`guideText` 的 KDoc 承诺「末尾统一提示版本差异」，实际该句硬编码在 `SettingsScreen.kt:654`。裁决 = **改 KDoc 对齐现实**（不动代码位置）。理由：句子里还要带 ②（通用导航），它不属于厂商差异，放在卡上才对；把两句塞进 `guideText` 会让 9 族文案各长 45 字符，直接击穿 D4 长度预算。

### D7 停止 action（W77-02）：**本波不做**

| 项 | 内容 |
|---|---|
| **裁决** | **不做**，维持 parked，但**升级重启前提**。 |
| **依据（源码为准）** | ① `AgentRunner` **没有公开 cancel 面**（`AgentRunner.kt` 只有 `runMutex` + `isBusy`，取消靠协程作用域）；② 取消权在 `ChatRunCoordinator.runJob`（feature-chat，挂在 `viewModelScope`）—— 通知的 `PendingIntent` 触达不到它；③ 服务能做的只有 `stopSelf()`。 |
| **为什么「只停服务」比不做更糟** | 服务停了 ⇒ FGS 消失 ⇒ 进程回落 cached ⇒ 更快被冻结；而 run 还在跑。用户看到通知没了，以为停了，实际答案在后台被冻住后走 journal 恢复卡 —— **把「用户显式终止」演成「系统随机中断」**，直接违反本仓诚实口径。 |
| **若要做，前置条件（写死）** | 必须先有**进程级取消通道**：`core-data` 暴露 `RunCancelBus`（`MutableSharedFlow<Unit>` 或 `@Volatile` 标志），`ChatRunCoordinator` 订阅并把「取消 = 用户显式停止」映射到 `run.onStop()`；然后通知 action（`PendingIntent.getService` 指向 FGS 自身 + `ACTION_STOP`，**不新增 Receiver**）→ 服务 `cancelRun()` → 通道 → run 收尾。这动到 **run 生命周期语义面**（本波明令不动），且需真机四项判据（journal settled / 服务销毁 / 通知消失 / 半截答案落库）。 |
| **重启前提（改写 backlog）** | 原「用户提出诉求」→ 新：**「进程级取消通道」设计通过评审 + 后台 run 成为常用路径**。 |
| **本波做** | ❌ |

### D8 守卫：**加 2 条（#45 / #46）**，其余走 JVM 元测试

| 编号 | 判据 | 为什么是守卫（而不是单测） |
|---|---|---|
| **#45** | `core-data/.../background/` **包级合计 ≤ 1000 行**且**单文件 ≤ 300 行** | 「新文件当波补守卫」（#35 范式）的包级版；本波正是该包的膨胀波（+2 文件、+2 文件改写）。跨文件规模面 shell 才能看。 |
| **#46** | `OemFamily` 全 schema：枚举值 ↔ 证据表键**双向一致**、档位值合法、`MEASURED` 必须带 `W##` 锚点 | 数据 schema + 载体蒸发（文件/表被删），与 #39 同族。 |
| **不进守卫的三条** | ① FGS id ≠ 速度 id（纯常量关系 → 单测 `assertNotEquals`）；② 加锁文案 ⇔ 官方支持集合（纯函数属性 → 9 族全量元测试）；③ slot 的 attach/publish/detach 行为（纯 JVM 对象 → 单测） | 「守卫只钉跨文件契约/载体蒸发/数据 schema；单测能钉的别搬进 shell」。判据写进 shell 越深，注入成本越高、误报面越大。 |
| selftest | case **79/79b**（#45）、case **80/80b**（#46）；`KNOWN_GUARDS` + 映射表 + 元 case53 同步 | 「每新增守卫必同步 selftest case」；79b/80b 必须断言输出含**违规事实行**（防「守卫报红≠真命中」）。 |

### D9 WakeLock 续期：**做（周期续期 + 总持有上限）**——真缺口，且比 D2 更影响「能不能跑完」

| 项 | 内容 |
|---|---|
| **裁决** | 服务存活期间**周期性续期**：周期 `120_000ms`，每次无条件 `acquire(390_000ms)`（既重置超时，也在被 OEM 回收后重新申请）；**总持有上限 `1_800_000ms`（30min）**，达上限即停止续期并留痕 —— **物理断路器保留**（最坏退化成今天的行为：锁自然到期）。 |
| **前提核实（源码为准）** | 缺口成立，且比报告描述更硬：① 审批挂起**无超时**——`ToolApprovalHandler.onApprovalRequested`（`core-agent/.../approval/ToolApprovalHandler.kt:31`）是裸 `suspend fun`，等待时长由用户决定，**没有上界**；② 挂起时长被**加回**预算：`AgentRunner.kt:1076-1081` 的 `wallClockRemainingMillis` 把 `state.pausedNanos` 加回（Wave 52 B1 停表）⇒ **run 的墙钟存活时间可以任意长**；③ 而锁只在 `onCreate`/`onStartCommand` 各 acquire 一次（`AgentForegroundService.kt:51/59`），390s 后静默自释，**无任何重取路径**（全仓无 `acquire` 第二处调用点）。⇒ 用户在审批框前放下手机超过 6.5 分钟，回来批准后 CPU 已在深睡，TTFT 直接劣化到分钟级，而日志只有一行「已获取」。 |
| **为什么不是「改成无超时 `acquire()`」** | 违反 W30 物理断路器思想：控制器卡死时锁会永久持有（真耗电事故）。断路器必须留。 |
| **周期取值依据** | ① **容错比 ≥3**：`390s / 120s = 3.25` ⇒ 连续错过 2 次续期（Doze 让 `delay` 推迟、OEM 抖动）仍不会断锁；② **不与 OEM 打抢锁循环**：ColorOS 的 `OplusProxyWakeLock` 在灭屏 ~10s 后回收，120s 周期意味着「10s 有 CPU / 110s 深睡」的低占空比重取，而不是每秒抢一次；③ **日志量**：`AgentLogStore.DEFAULT_CAPACITY = 200` 条环形缓冲（`core-model/.../AgentLogStore.kt:24`）⇒ 续期日志必须**由结构保证有界**：30min / 120s = 最多 15 行，占容量 7.5%，不会把诊断日志冲掉。 |
| **周期的合法区间推导（与既有三个数字自洽）** | 下界来自「**不要打扰常规 run**」：本机 run 生命期 = 生成 30–63s + `GRACE_MILLIS` 10s ⇒ **常规 ≤73s**；周期若 ≤73s，每一次普通 run 都会触发 1 次续期（多一次 binder + 一行日志 + 一次与 OEM 的抢锁），纯噪声 ⇒ 周期必须 **>73s**。上界来自容错比：`WAKE_LOCK_TIMEOUT / 3 = 130s`（错过 2 次仍不断锁）⇒ 周期必须 **≤130s**。合法区间 = **(73s, 130s]**，取 **120s**（贴近上界 ⇒ 续期次数与日志最少，同时离下界仍有 47s 余量）。**与 300s 硬预算的关系**：硬预算是「计算时长」预算，续期周期与它无耦合 —— 但 `WAKE_LOCK_TIMEOUT = 硬预算 + 90s 余量` 这个既有推导必须保持不变，续期只在超时被重置上做文章，**不动 390s**。 |
| **日志节流：不加独立节流器** | 理由 = **有界性由结构保证**，不需要另一套闩：单次服务生命期最多 `1800/120 = 15` 条，且 `AgentLogStore` 是 200 条环形缓冲（占 7.5%）。再加节流器反而会掩盖「续期是否真的发生」—— 而这条日志的全部价值就是可归因。**代价可控性由单测钉死**：`wakeLockRenewalLogVolumeIsBounded` 断言 `MAX_HOLD / INTERVAL ≤ 20`，谁把周期改小到 30s（60 条日志）就会红。 |
| **判据（本机能否验 / 怎么读）** | **能验，且不需要改生产代码做「加速开关」**——关键洞察：**审批挂起本身就是加速器**（它把 run 的墙钟拉长，而这正是缺陷的成因）。双档真机臂：<br>· **弱臂（~2.5min，先做）**：发必触发审批的题面 → 灭屏放置 **130s** → 解锁批准。判据 = ① 应用日志出现 ≥1 行「WakeLock 续期（已持 ~120000ms）」；② 设备侧 `logcat -b system` 落盘里出现 ≥1 次对本应用 wakelock 的 acquire 记录（系统侧证据，不依赖我们的日志）；③ `dumpsys power` 的 `Wake Locks` 仍有 `LiquidAgent::Run`。**弱臂只证明「续期路径被执行」，不证明「跨过 390s 超时」**。<br>· **强臂（>7min，缺陷正证）**：同上但放置 **>7min（>420s）**。判据 = ① 灭屏 420s 后 `dumpsys power` 仍有 `LiquidAgent::Run`（**W77 在此处必然消失**，这就是反证点）；② 解锁批准后 run 正常结算；③ 结算后 10s `Wake Locks: size=0`。<br>· **不做「可注入周期/超时」的生产缝**：`AgentForegroundService` 不接测试参数（生产代码不装测试分支）。参数化只发生在**纯函数**层——`wakeLockTimeoutMillis(hardBudget, margin)` 与 `shouldRenewWakeLock(heldMillis)` 都已带参数，单测直接喂边界值；算术正确性由单测钉死，**真机只验「服务存活期间锁不消失」**（即上两臂的 ①②③）。<br>· **vacuous 声明**：若某次真机上审批流程不产生挂起（如工具被 auto-approve / 题面没触发危险工具），则弱臂与强臂**都不成立**，须记 `⛔ vacuous`，不得记「通过」——回退到「单测已钉 + 本机无法验」。 |
| **上限取值依据** | 30min = 6 个完整墙钟预算（300s）。它同时是两件事的上界：① **合法场景**——用户放下手机半小时再回来批准，锁仍在；② **故障场景**——控制器卡死时的最坏耗电（30min PARTIAL_WAKE_LOCK ≈ 1–2% 电量）。超上限后回到今天的行为（锁到期、run 可能被冻），**不静默**：落一行「续期已达上限」。 |
| **续期协程不能变成新泄漏/耗电源（逐条）** | ① 作用域 = 服务私有 `CoroutineScope(SupervisorJob() + Dispatchers.Default)`，`onDestroy` 里 `renewJob?.cancel()` + `scope.cancel()`，**结构上不可能比服务活得久**；② 循环自身有终止条件（达上限 `break`），不是 `while(true)`；③ `delay` 不忙等（Default 调度器的定时器，非常规轮询）；④ 每周期只有 1 次 binder 调用 + 1 行日志；⑤ 锁对象仍是 `setReferenceCounted(false)`（既有），重复 `acquire(timeout)` **不累积计数**（AOSP `acquireLocked()` 对非引用计数锁即使 `mHeld==true` 也会重新向系统请求并重置超时——这正是本裁决依赖的语义）。 |
| **诚实限定（r11 实测后整段改写，原稿那句已作废）** | 续期**不能对抗 OEM 进程冻结**。真机实测（2026-10-22，见 R15 / §4.4）：ColorOS 灭屏 ~10s 触发 **cgroup 级进程冻结**，锁被强制回收与进程被冻结**同一毫秒**；续期 re-acquire 后仅 **2.7s** 锁即被回收，且**冻结期间协程根本不执行** ⇒ 下一次续期迟到 **420s**。<br>⚠️ **原稿「把永远深睡变成每 120s 有 10s CPU 的间歇窗口」是错的，已作废**：实测间歇窗口只有 ~2.7s，且那个窗口是**冻结本身造成的**（系统在冻结前给的一小段），不是续期争取来的。<br>**续期的正确定位（三句，不许简化成「能对抗 OEM」）**：① **冻结窗口外**（用户在用 App / 刚解冻）锁可靠持有，CPU 不睡；② **解冻后立即补锁**（实测解冻后 `ACQ` 之后无 `REL`，持续 HELD）；③ **冻结期间无能为力** —— 协程不执行、续期不来、锁也进不去。<br>⇒ 对用户的真实意义：**灭屏 7 分钟回来那一下是快的**（不用从零 prefill），而不是「灭屏期间一直在跑」。判据见 §4.2 V8（已被 r11 修订）与 §4.4。 |
| **代价** | `RunForegroundPolicy` +18 行（2 常量 + `shouldRenewWakeLock` + KDoc）；服务侧 +75 行（含 D2 的 attach/detach/refresh 与 D9 的 scope/job/renew/两处 cancel）—— **C4 拆分后这 75 行分布于 `AgentForegroundService`（≤230）与 `RunWakeLockKeeper`（≈90）两个文件**；单测 +3 例。 |
| **本波做** | ✅ |

### D10 通知 id 解耦的两个连带面：**① 做（防御性清理）② 做（条件披露）**

| 子项 | 裁决 | 依据 / 形态 |
|---|---|---|
| **① 冷启动清理 id 1002** | **做**，但定性为**纵深防御**而非缺陷修复 | 事实边界要说清：普通 `notify()` 通知**跨进程存活**（所以 W77 冷启动必须 `notifier.stop()` 清 1001，`LiquidAgentApplication.kt:50-53` 的理由成立）；而 FGS 通知在服务/进程销毁时**通常**由 AMS 一并撤掉。但「通常」不够——本仓既有 P2-1 的口径就是「冷启动时不可能有合法生成中，任何残留都是脏数据」，同一条口径对 1002 同样成立，且成本只有 4 行。⇒ **做**，`AgentForegroundService.companion.clearStaleNotification(context)`（public，因为 `RunForegroundPolicy` 是 internal 而 app 层要调用）。 |
| **② 通知不可见的披露** | **做**，且**条件显示**（`backgroundNotificationHint != null` 时才渲染一行） | 两个成因**都可检测**，所以不做「模糊免责」：① 未授予 `POST_NOTIFICATIONS`（运行时权限，`needNotificationPermission` 已是 `SettingsScreen:100` 的局部 val）；② 用户把 `background_run` 渠道关掉了（`getNotificationChannel(...).importance == IMPORTANCE_NONE`，免权限、minSdk 31 恒可用）。文案两选一（见 §2.7 第 7 块），**只说事实、不承诺「一定有通知」**，也不说「影响保活」（FGS 在 A13/A14 都继续跑）。 |
| **本波做** | ✅ / ✅ |

### D11 服务 → notifier 的运行时状态通道：**进程级 object 槽（不是 DI 依赖，也不需要接口）**

| 项 | 内容 |
|---|---|
| **裁决** | 通道 = `core-data/.../notify/OngoingNotificationSlot`（`internal object`，§2.2）。**写方** = 服务（`attach/detach` + `publish` 的 sink 由服务提供）；**读方** = `AndroidGenerationNotifier.onTick`（同包直调）。**不引入接口、不改 AppContainer**。 |
| **为什么不能注入** | 服务由**系统实例化**，拿不到 `AppContainer`；而 `core-data` 不能依赖 `app` 的 `LiquidAgentApplication`（模块方向）。⇒ 「服务侧注入」在结构上不可能。这与 `AgentLogStore`（`core-model` 的 `object`，被全仓直接调用）的存在理由**完全同构**。 |
| **DI 铁律的边界** | 铁律约束的是**业务依赖**（仓库/引擎/控制器）必须从 `AppContainer` 取；**进程级平台状态槽**（日志、通知槽）用 `object` 是本仓既有范式。且本槽**没有**业务语义（不持有 run、不碰 isBusy），只是「谁在写这条通知」的登记簿 ⇒ 不构成第二套 DI。 |
| **为什么不造接口（`interface OngoingNotificationSink`）** | `AndroidGenerationNotifier` 依赖 Android `Context`/`NotificationManager`，本来就没有 JVM 单测；为它造接口只会多一层**无消费者的抽象**（YAGNI）。可测面放在槽本身（纯 JVM，3 例单测）。若将来真要测 notifier，再抽接口不迟。 |
| **方向（硬约束）** | `background`(服务，写) → `notify`(槽) ← `notify`(notifier，读)。**禁止**槽反向 import `background`（否则包循环）。`core-agent`/`core-engine` 全程不可见。 |
| **为什么不用退路「两条通知 + setGroup」** | 槽方案总成本 = 45 行 + 3 例单测；退路的代价是**用户长期看到两条划不掉的常驻通知**（产品债），且「两条」正是 W77 §五 想避免的形态。成本差一个数量级，不值得用产品形态去省。 |
| **本波做** | ✅（已在 §2.2 定稿，本节只补「为什么不是注入/接口」的论证） |

---

## 二、接口契约（可直接实现）

### 2.1 `RunForegroundPolicy`（core-data/background）

```kotlin
/**
 * FGS 常驻通知 id。**必须与速度通知 id 不同** —— 后者会被 GenerationNotifier.stop() cancel。
 */
const val ONGOING_NOTIFICATION_ID: Int = 1002

/**
 * WakeLock 续期周期（W78 D9）。
 *
 * 取值依据 = **容错比 ≥3**：`WAKE_LOCK_TIMEOUT_MILLIS(390s) / 120s = 3.25`
 * ⇒ 连续错过 2 次续期（Doze 让 delay 推迟 / OEM 抖动）仍不会断锁；
 * 同时 120s ≫ ColorOS 灭屏 ~10s 的回收阈值 ⇒ 不会变成「每秒抢锁」的耗电循环。
 */
const val WAKE_LOCK_RENEW_INTERVAL_MILLIS: Long = 120_000L

/**
 * 单次服务生命期内 WakeLock 的**总持有上限**（含续期）= 30 分钟（W78 D9）。
 *
 * 为什么必须有这条：续期会把「390s 物理断路器」的兜底效果取消（锁可以被无限续下去），
 * 所以必须另立一个上界。30min = 6 个完整墙钟预算：
 *  - 合法侧：审批挂起无超时上限，用户放下手机半小时再批准是真实场景；
 *  - 故障侧：控制器卡死时最坏 30min PARTIAL_WAKE_LOCK ≈ 1–2% 电量，且有界。
 * 达上限后停止续期 ⇒ **行为退回今天**（锁自然到期），并留一行日志。
 */
const val WAKE_LOCK_MAX_HOLD_MILLIS: Long = 1_800_000L

/** 是否还应续期（纯函数，JVM 可测）。 */
fun shouldRenewWakeLock(heldMillis: Long): Boolean = heldMillis < WAKE_LOCK_MAX_HOLD_MILLIS
```

> **通知渠道常量不放这里**：`RunForegroundPolicy` 是 `internal`（模块级），而 `app` 模块要创建渠道 ⇒ 渠道 id 必须挂在**公开面**：`AgentForegroundService.companion` 的
> `const val NOTIFICATION_CHANNEL_ID: String = "background_run"`（见 §2.4）。这是本契约唯一受可见性约束的常量，**不要**在 `RunForegroundPolicy` 里重复定义。

- 其余常量（`HARD_WALL_CLOCK_MILLIS` / `WAKE_LOCK_TIMEOUT_MILLIS` / `GRACE_MILLIS` / `WAKE_LOCK_TAG` / `EXTRA_SMALL_ICON` / `SPECIAL_USE_SUBTYPE` / `usesTypedForeground` / `shouldStart` / `shouldScheduleStop`）**一律不动**。
- KDoc 需改：原「与 notifier 的 1001 共用」整段删除，改写为「共用 = 互相 cancel（W78 D2）；去重由『单写者 + 借道刷新』解决，不由 id 共用解决」。

### 2.2 新文件 `core-data/.../notify/OngoingNotificationSlot.kt`（~45 行，纯 JVM）

```kotlin
package com.rickeal.agent.core.data.notify

/**
 * 「FGS 常驻通知」的进程内借道槽（W78 D2）。
 *
 * ## 它解决什么
 * FGS 必须有一条用户可见的常驻通知；速度通知每秒刷新。若两者共用 id，
 * `GenerationNotifier.stop()`（8 处调用点）会把 FGS 通知一起 cancel ⇒ 第二轮起
 * 「已前台化但用户不可见」。若两者各自发一条，通知栏出现两条划不掉的常驻通知。
 *
 * 解法 = **单写者 + 借道**：FGS 通知只由 `AgentForegroundService` 写（id 1002）；
 * FGS 活跃期间，速度文案经本槽交给服务刷新**同一条**，notifier 不再自己发。
 *
 * ## 依赖方向（硬约束）
 * 本对象在 `notify` 包、被 `background` 包使用 ⇒ 方向恒为 `background → notify`。
 * **禁止**让本对象反向 import `background` 包的任何类型（会形成包循环）。
 *
 * ## 线程模型
 * `attach/detach` 在服务主线程调用；`publish` 可能在任意线程（onTick 来自流收集）。
 * 故内部加锁 + sink 调用包 runCatching：**通知失败绝不能打崩推理**。
 */
internal object OngoingNotificationSlot {

    /** 速度通知的固定 id。与 `RunForegroundPolicy.ONGOING_NOTIFICATION_ID` 必须不同（单测钉死）。 */
    const val SPEED_NOTIFICATION_ID: Int = 1001

    private val lock = Any()
    private var owner: Any? = null
    private var sink: ((String) -> Boolean)? = null

    /** 服务进入前台后登记刷新口。`sink` 返回 false 表示「刷新失败」，调用方需自行兜底。 */
    fun attach(owner: Any, sink: (String) -> Boolean) {
        synchronized(lock) { this.owner = owner; this.sink = sink }
    }

    /** 服务销毁时注销。**按 owner 身份校验**：新旧服务交错时，旧服务不会清掉新服务的 sink。 */
    fun detach(owner: Any) {
        synchronized(lock) {
            if (this.owner === owner) { this.owner = null; this.sink = null }
        }
    }

    /** FGS 是否活跃（= 有服务登记了刷新口）。 */
    fun isActive(): Boolean = synchronized(lock) { sink != null }

    /**
     * 把速度文案交给 FGS 通知。
     *
     * 返回值语义 = **「已受理」而非「已上屏」**：
     *  - `false` = 无活跃 FGS（或 sink 抛异常）⇒ 调用方回落自己发 1001；
     *  - `true`  = sink 已受理（服务侧可能仍在主线程队列中，见 §2.4 硬点 1）。
     * **调用方不得把「刷新失败」当作 `false` 处理** —— 那会多发一条通知。
     */
    fun publish(text: String): Boolean {
        val target = synchronized(lock) { sink } ?: return false
        return runCatching { target(text) }.getOrDefault(false)
    }
}
```

> **刻意不提供 `resetForTest()`**：生产代码不装测试专用 API（本仓「不留死代码」的同一把尺）。清场由测试侧用 `detach(owner)` + `@Before assertFalse(isActive())` 完成，且**漏清场会让下一个用例直接红**，而不是被静默清空（见 §2.8 硬点 2）。

### 2.3 `AndroidGenerationNotifier`（core-data/notify）改动

```kotlin
override fun onTick(tps: Float, ttftMillis: Long) {
    if (!enabled) return
    val now = SystemClock.elapsedRealtime()
    if (now - lastEmitAt < THROTTLE_MS) return
    lastEmitAt = now

    // 权限/渠道两道守卫保持在原位（顺序不变：先确认「能发」再构造文案）
    if (!hasPostNotificationsPermission()) { warnNoNotificationOnce(PERMISSION, "缺少 POST_NOTIFICATIONS 权限"); return }
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) { warnNoNotificationOnce(CHANNEL_DISABLED, "通知已被系统 / 渠道关闭"); return }

    val text = buildText(tps, ttftMillis)          // ← 从原 postGenerationNotification 里抽出（逐字节不变）
    // W78 D2：FGS 活跃 ⇒ 借道服务的常驻通知（同一条），不再另发一条
    if (OngoingNotificationSlot.publish(text)) return
    postGenerationNotification(text)
}

/** 文案主体（原 `postGenerationNotification` 内的 text 构造，逐字节搬出）。 */
private fun buildText(tps: Float, ttftMillis: Long): String { /* …原实现… */ }
```

- `postGenerationNotification(text)` 的签名/行为**不变**。
- `stop()` **一行不改**（只 `cancel(SPEED_NOTIFICATION_ID)`）；Kdoc 补一句「永远不得 cancel FGS 的 id」。
- companion 里的 `private const val NOTIFICATION_ID = 1001` **删除**，改用 `OngoingNotificationSlot.SPEED_NOTIFICATION_ID`（id 只留一个家）。

### 2.4 `AgentForegroundService`（core-data/background）改动

```kotlin
class AgentForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var iconRes: Int = 0                  // 新增：刷新通知时复用（onStartCommand 写入）

    // ---- W78 D9：续期协程的作用域（服务私有，onDestroy 必取消）----
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var renewJob: Job? = null
    private var wakeLockHeldSince: Long = 0L      // elapsedRealtime 基准，用于总持有上限

    // ---- W78 硬点 1：通知刷新的线程模型（主线程串行 + 合并 + 销毁后不复活）----
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var pendingText: String? = null   // 只保留最新一帧（合并）
    @Volatile private var refreshScheduled: Boolean = false
    @Volatile private var destroyed: Boolean = false     // onDestroy 先置位 ⇒ post 出去的 Runnable 自我作废

    override fun onCreate() {
        super.onCreate()
        AgentLogStore.info("后台保活服务创建（前台化前）")
        acquireWakeLock()
        startWakeLockRenewal()                    // ← W78 D9
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        iconRes = intent?.getIntExtra(RunForegroundPolicy.EXTRA_SMALL_ICON, 0) ?: 0
        acquireWakeLock()
        return try {
            startInForeground(iconRes, BASE_TEXT)
            // W78 D2：登记速度文案刷新口（幂等：重复 onStartCommand 覆盖同一 sink）
            OngoingNotificationSlot.attach(this) { text -> refreshOngoing(text) }
            AgentLogStore.info("后台保活服务已进入前台（startId=$startId）")
            START_NOT_STICKY
        } catch (e: RuntimeException) { /* 原降级路径不变：解锁 + stopSelf + warn */ }
    }

    override fun onDestroy() {
        // 顺序很关键：① 置 destroyed（让已 post 的刷新自我作废）② 停续期 ③ 注销槽
        // ④ 撤前台 ⑤ 解锁。若先 stopForeground 再置位，队列里的刷新会把通知「复活」。
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        renewJob?.cancel(); renewJob = null
        serviceScope.cancel()                     // 结构上保证续期协程不活得比服务久
        OngoingNotificationSlot.detach(this)
        AgentLogStore.info("后台保活服务销毁（撤前台 + 解锁）")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        releaseWakeLock()
        super.onDestroy()
    }

    // ---- W78 D9：WakeLock 周期续期 ----

    /**
     * 每 [RunForegroundPolicy.WAKE_LOCK_RENEW_INTERVAL_MILLIS] 续一次，直到
     * [RunForegroundPolicy.WAKE_LOCK_MAX_HOLD_MILLIS]（30min）为止。
     *
     * **为什么无条件 acquire 而不是「只在 !isHeld 时补」**：OEM（如 ColorOS 的
     * `OplusProxyWakeLock`）是在系统侧把锁回收，客户端 `isHeld` 仍为 true ⇒
     * 「按 isHeld 判断」永远判不出被回收。无条件重申请才既重置超时又抢回被回收的锁。
     *
     * **日志有界**：30min / 120s = 最多 15 行（`AgentLogStore` 是 200 条环形缓冲），
     * 不会冲掉 run 的诊断日志。
     */
    private fun startWakeLockRenewal() {
        if (renewJob != null) return
        wakeLockHeldSince = SystemClock.elapsedRealtime()
        renewJob = serviceScope.launch {
            while (isActive) {
                delay(RunForegroundPolicy.WAKE_LOCK_RENEW_INTERVAL_MILLIS)
                val held = SystemClock.elapsedRealtime() - wakeLockHeldSince
                if (!RunForegroundPolicy.shouldRenewWakeLock(held)) {
                    AgentLogStore.info(
                        "WakeLock 续期已达上限（已持 ${held}ms / 上限 " +
                            "${RunForegroundPolicy.WAKE_LOCK_MAX_HOLD_MILLIS}ms），不再续期：锁将自然到期",
                    )
                    break
                }
                renewWakeLock(held)
            }
        }
    }

    private fun renewWakeLock(heldMillis: Long) {
        val lock = wakeLock ?: run { acquireWakeLock(); return }
        // 非引用计数锁的 acquire(timeout)：AOSP 对 `!mRefCounted` 分支即使 mHeld==true
        // 也会重新向系统申请并重置超时（注释原文：这类锁并不总能被正确释放）⇒ 正好是本处所需。
        runCatching { lock.acquire(RunForegroundPolicy.WAKE_LOCK_TIMEOUT_MILLIS) }
            .onFailure { AgentLogStore.warn("WakeLock 续期失败（${it.javaClass.simpleName}）") }
        AgentLogStore.info("WakeLock 续期（已持 ${heldMillis}ms，held=${lock.isHeld}）")
    }

    /**
     * 刷新常驻通知文本（**主线程串行 + 合并**，W78 硬点 1）。
     *
     * 返回值语义 = **「已受理」而非「已上屏」**：
     *  - `false` = 无活跃 FGS / 服务已销毁 ⇒ 调用方（notifier）回落自己发 1001；
     *  - `true`  = 已受理（可能仍在主线程队列里）。**刷新本身失败不回 false** ——
     *    否则 notifier 会再发一条 1001，变成「两条通知」，比丢一帧速度文案糟得多。
     *    失败由服务侧 warn 留痕 + 下一次 tick（1s 后）自愈。
     */
    private fun refreshOngoing(text: String): Boolean {
        if (destroyed) return false
        pendingText = text                      // 合并：只保留最新一帧
        if (refreshScheduled) return true       // 已有一次待执行 ⇒ 不排队（防队列堆积）
        refreshScheduled = true
        mainHandler.post {
            refreshScheduled = false
            val latest = pendingText ?: return@post
            // 双重检查：从 post 到执行之间服务可能已销毁 ⇒ 不能把通知「复活」
            if (destroyed) return@post
            runCatching { startInForeground(iconRes, latest) }
                .onFailure {
                    // OEM 限流 / 渠道被关 / 竞态：只留痕，不下沉成「发第二条通知」
                    AgentLogStore.warn("后台保活通知刷新失败（${it.javaClass.simpleName}）：本条通知停留在上一帧文案")
                }
        }
        return true
    }

    private fun startInForeground(iconRes: Int, text: String) { /* 原 startInForeground + buildNotification(iconRes, text) */ }

    private fun buildNotification(iconRes: Int, text: String): Notification =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)  // ← 改用 FGS 专用渠道
            .setSmallIcon(if (iconRes != 0) iconRes else android.R.drawable.stat_sys_download)
            .setContentTitle("端侧生成中")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .apply { contentIntent?.let { setContentIntent(it) } }
            .build()

    private companion object {
        const val BASE_TEXT = "正在后台运行 · 点击返回"
    }
}
```

- ⚠️ 上例的 `private companion object` 必须改成 **public companion**，把渠道 id 露出来（`BASE_TEXT` 保持 `private`）：

  ```kotlin
      companion object {
          /**
           * FGS 常驻通知渠道 id（W78 D2）。**独立于** `AndroidGenerationNotifier.CHANNEL_ID`
           * （"generation_speed"）：用户关掉速度通知渠道时，FGS 通知不能跟着消失 ——
           * 那正是「已前台化但用户不可见」的形态。渠道由 `LiquidAgentApplication.onCreate`
           * 创建（幂等）。放这里而不是 `RunForegroundPolicy`：后者是 internal，app 模块看不见。
           */
          const val NOTIFICATION_CHANNEL_ID: String = "background_run"

          /**
           * 冷启动清理 FGS 常驻通知（W78 D10①）。
           *
           * 普通 `notify()` 通知跨进程存活，FGS 通知则通常随服务销毁由 AMS 撤掉 ——
           * 所以本方法是**纵深防御**，不是缺陷修复。依据与既有 P2-1 同口径：
           * 「冷启动时界面上不可能有合法的『生成中』，任何残留通知都是脏数据」，
           * 且 ongoing 通知在 Android 13 及以下用户划不掉。
           *
           * 调用点：`LiquidAgentApplication.onCreate`，紧挨 `generationNotifier.stop()`。
           */
          fun clearStaleNotification(context: Context) {
              runCatching {
                  NotificationManagerCompat.from(context).cancel(RunForegroundPolicy.ONGOING_NOTIFICATION_ID)
              }
          }

          private const val BASE_TEXT = "正在后台运行 · 点击返回"
      }
  ```

  新增 import：`android.os.Handler` / `android.os.Looper` / `android.os.SystemClock`、`kotlinx.coroutines.CoroutineScope` / `Dispatchers` / `Job` / `SupervisorJob` / `cancel` / `delay` / `isActive` / `launch`、`androidx.core.app.NotificationManagerCompat`。

- **刷新用 `startForeground` 而不是 `NotificationManager.notify`**：前者同时保持「前台服务 ↔ 通知」的绑定，且已有 `usesTypedForeground` 分派可复用（A14+ 的类型位不会因刷新丢失）。
- 服务**不再 import** `AndroidGenerationNotifier`（渠道 id 已独立）。

**硬点 1 契约：`refreshOngoing` 的线程模型（写死，不许实现时自由发挥）**

| 项 | 契约 | 理由 |
|---|---|---|
| 调用线程 | `OngoingNotificationSlot.publish` **可能在任意线程**（`notifier.onTick` 来自流收集，不保证主线程）⇒ 服务侧**必须自己切主线程**（`Handler(Looper.getMainLooper()).post`），**不得**假设调用方已在主线程 | `startForeground` 与 `stopForeground`（在 `onDestroy`，主线程）并发时会竞态：后台线程的刷新可能落在 `stopForeground(REMOVE)` **之后**，把通知「复活」成一条没人撤的常驻通知 —— 这正是「泄漏形态」，不是理论风险 |
| 返回值语义 | `true` = **已受理**（可能仍在主线程队列中，**不保证已上屏**）；`false` = 无活跃 FGS / 服务已销毁 | 若把「刷新失败」也返回 `false`，notifier 会回落去发 1001 ⇒ **通知栏出现两条**。丢一帧速度文案（1s 后下一 tick 自愈）远好于多一条常驻通知 |
| notifier 的回落判据 | `if (OngoingNotificationSlot.publish(text)) return` —— 只在「无活跃 FGS」时回落，**不在刷新失败时回落** | 同上；且 `stop()` 永不 cancel 1002（D2） |
| 每秒一次刷新的限流 | **合并（coalescing）**：`pendingText` 只保留最新一帧 + `refreshScheduled` 保证队列里最多一个 Runnable ⇒ 无论 tick 多密，主线程队列长度恒 ≤1 | 防 OEM 上 `startForeground` 变慢时队列堆积（堆积 = 越刷越旧 + 内存增长） |
| OEM 限流/渠道被关的降级 | `startInForeground` 抛异常 ⇒ `runCatching` + **warn 留痕**（「本条通知停留在上一帧文案」），**不重试、不改道去发 1001、不影响推理** | 与 notifier 的 fail-open 给推理 / fail-closed 给通知同哲学；R2 的风险在这里落成明确行为 |
| 销毁后不复活 | `onDestroy` **第一件事**：`destroyed = true` + `mainHandler.removeCallbacksAndMessages(null)`；Runnable 执行时**双重检查** `destroyed` | 顺序写进注释（先置位再 stopForeground），实现时不得调换 |

> **C5 拆分后的归属（主理人令本波拆，已落地）**：本节的 WakeLock 部分（`serviceScope` / `renewJob` / `wakeLockHeldSince` / `acquireWakeLock` / `startWakeLockRenewal` / `renewWakeLock` / `releaseWakeLock`）全部迁入 **`background/RunWakeLockKeeper.kt`**（实测 **110** 行；⚠️ 本波**不再为它加注释** —— 「降级路径为何会泄漏锁」这层推理依赖设计稿上下文，判据留本文档，代码只留最小事实，否则正好破了「先拆后加」换来的 ≤110 水位）；服务只保留三个调用点（`wakeLockKeeper.acquire()` / `.startRenewal()` / `.release()`）。
> **行为契约（周期 120s / 上限 30min / 无条件 acquire / 取消点）一字不变** —— 拆的是类边界，不是语义。本节的代码块保留原样，作为**行为规格**读，不因拆分而失效。
>
> **构造签名（实测，比本设计原稿更好，按实现为准）**：
> ```kotlin
> internal class RunWakeLockKeeper(
>     private val powerManager: PowerManager?,                  // ← 不是 Context
>     private val tag: String = RunForegroundPolicy.WAKE_LOCK_TAG,
>     private val timeoutMillis: Long = RunForegroundPolicy.WAKE_LOCK_TIMEOUT_MILLIS,
>     private val renewIntervalMillis: Long = RunForegroundPolicy.WAKE_LOCK_RENEW_INTERVAL_MILLIS,
>     private val maxHoldMillis: Long = RunForegroundPolicy.WAKE_LOCK_MAX_HOLD_MILLIS,
> )
> ```
> 服务侧构造：`RunWakeLockKeeper(getSystemService(PowerManager::class.java))`。
> **为什么这比我原稿的「持 `Context`」更好**（设计要认账）：① 它既不持 `Service` 也不持 `Context` ⇒「`release()` 后仍摸到已销毁 Context」这类问题**从结构上不存在**，不是靠纪律避免；② `powerManager: PowerManager?` 的 **null 分支可在 JVM 单测里构造**（`RunWakeLockKeeper(null)` ⇒ acquire 走 warn 降级），给了一条本波没用的可测缝（见 §五 W78-06）；③ 四个时长参数带默认值 ⇒ 测试可喂边界值而生产零改动。
> **单一取消点（实测已对齐）**：`release()` 内部顺序 = `renewJob?.cancel()` → `renewJob = null` → `serviceScope.cancel()` → `runCatching { if (isHeld) release() }` → `wakeLock = null`；服务 `onDestroy` 里**不再**单独 cancel job/scope（只有 `wakeLockKeeper.release()` 一处）。

> 🔴 **实现修正了本设计的一处真实缺陷（r7，必须留档）**：本设计 r1/r2 的服务契约里，`startForeground` 失败的降级路径写的是「原降级路径不变：解锁 + stopSelf」，即只调 `releaseWakeLock()`、**不取消续期协程**。而续期循环里是 `wakeLock ?: run { acquireWakeLock(); return }` ⇒ 降级后下一 tick 会**把锁重新拿回来**，且循环还活着会一直续到 30min 上限 —— 即「服务已 `stopSelf`、锁却被一个将死的协程重新持有」的**锁泄漏**。
> C5 把 `release()` 做成唯一取消点（并让降级路径也调它）之后，这条泄漏**结构性消失**：任何释放路径都先杀续期再解锁。⇒ **这不是「C5 的行为差」，是本设计被实现修好的一处缺陷**；契约以 C5 为准。
> **真机实测的价值定位（r11，2026-10-22）**：机制本身完全正常 —— `08:37:32.812 ACQ` → `08:39:32.823 续期（已持 120006ms）`，周期精确命中、无条件 re-acquire 有效、`held=true`。**但它补不回 OEM 冻结**：续期后 2.7s 锁即被强制回收，且冻结期间协程不执行（下一次续期迟到 420s，直到 `am start` 解冻才补发）；解冻后 `08:46:32.126 ACQ` 之后无 `REL`，锁持续 HELD ⇒ **价值在「冻结窗口外 + 解冻后补锁」，不在「对抗冻结」**。证据链见 §4.4 / R15。
> **代价（诚实的另一面）**：取消后同一实例上「释放 → 再 acquire」不会再有续期（旧实现靠循环自愈，C5 不再自愈）。当前**不可达**（`startRenewal()` 只在 `onCreate` 一次；`release()` 只在降级路径与 `onDestroy`；控制器在启动失败后 `started` 仍为 true，宽限窗内不会再 `startService`，等下次 run 时旧实例早已销毁）。若将来加复用路径，修法见 §五 W78-06（keeper 自愈：`startRenewal()` 里判 `!serviceScope.isActive` 则重建 scope，**重建必须排在 `renewJob != null` 短路之前**，顺序反了会静默不续期）——**不要在服务侧补调 `startRenewal()`**，那会重新变成两个启动点。

### 2.5 `OemBackgroundPolicy`（core-data/background）——重写为「一张表 + 三个访问器」

```kotlin
internal object OemBackgroundPolicy {

    enum class OemFamily {
        COLOR_OS, HYPER_OS, EMUI_HARMONY, MAGIC_OS, ORIGIN_OS,
        /** nubia / RedMagic / ZTE 系（W78 新增）：官方协议页背书的独立「智能耗电保护」。 */
        NUBIA,
        ONE_UI, FLYME, AOSP,
    }

    /** 每族引导文案的证据强度（W78 D5）。3 档，边界按「来源是否厂商官方」切分。 */
    enum class OemEvidence {
        /** 本仓真机实测：锚点必须指向本仓波次（`W##`）。 */
        MEASURED,
        /** 厂商官方文档 / 官方支持页 / 官方协议页背书，**未在本机实测**。 */
        OFFICIAL,
        /** 仅社区 / 三方资料口径。 */
        COMMUNITY,
    }

    fun classify(manufacturer: String, brand: String): OemFamily
    fun guideText(family: OemFamily): String
    fun displayName(family: OemFamily): String
    fun evidence(family: OemFamily): OemEvidence
    fun evidenceAnchor(family: OemFamily): String

    /** 该族是否有独立于 AOSP 的厂商后台管控（AOSP = false ⇒ 卡片限定行换一句）。 */
    fun isVendorManaged(family: OemFamily): Boolean = family != OemFamily.AOSP

    private data class Guide(val displayName: String, val path: String)

    private val GUIDES: Map<OemFamily, Guide> = mapOf(/* 见下表 */)

    /** 家族 × 证据档 × 取证锚点（#46 的数据面；一行一族，供机械解析）。 */
    private val EVIDENCE: Map<OemFamily, Pair<OemEvidence, String>> = mapOf(/* 见下表 */)

    /** 缺失即抛（fail-closed）：漏填家族的后果是「设置卡点开就崩」，不是「静默给错路径」。 */
    private fun guide(f: OemFamily): Guide = GUIDES.getValue(f)
}
```

**`classify` 新增分支**（置于 `FLYME` 之后、`else` 之前）：

```kotlin
containsAny(m, "nubia", "zte") || containsAny(b, "nubia", "redmagic") -> OemFamily.NUBIA
```

> ⚠️ `containsAny` 是子串匹配（既有 v22-F3 已知面，未在本波修）；`"zte"` 理论上可命中含 zte 子串的怪厂商名，实测影响≈0，**记为已知面**，不为此改造匹配算法（改算法要动全部 8 族判定，回归面远大于收益）。

**9 族文案定稿（可直接粘贴；每族 ≤80 字符，实测值在括号内）**

| 族 | `displayName` | `path`（定稿） | 字符 |
|---|---|---|---|
| `COLOR_OS` | `ColorOS / realme UI / OxygenOS` | `设置 → 电池 → 应用耗电管理 → 本应用 → 允许后台活动 + 允许应用自启动 + 允许关联启动 + 允许完全后台行为；最近任务下拉加锁` | 70 |
| `HYPER_OS` | `HyperOS / MIUI` | `设置 → 应用设置 → 应用管理 → 本应用 → 省电策略 / 应用智能省电 → 无限制；应用权限 → 后台自启动 → 允许；最近任务下拉加锁` | 71 |
| `EMUI_HARMONY` | `EMUI / HarmonyOS` | `手机管家 → 应用启动管理 → 本应用 → 关闭「自动管理」，允许自启动/关联启动/后台活动；HarmonyOS 5+ 改用下拉加锁 + 关闭省电模式` | 75 |
| `MAGIC_OS` | `MagicOS` | `手机管家 → 应用启动管理 → 本应用 → 关闭「自动管理」，允许自启动/关联启动/后台活动；电池优化 → 本应用 → 不允许；最近任务下拉加锁` | 72 |
| `ORIGIN_OS` | `OriginOS / FuntouchOS` | `设置 → 电池 → 后台耗电管理 → 允许后台高耗电；i 管家 → 自启动管理 → 允许；i 管家 → 省电管理 → 加速白名单 → 加入；最近任务下拉加锁` | 78 |
| `NUBIA` | `nubia / RedMagic（中兴系）` | `设置 → 电池 → 智能耗电保护 → 关闭，或自定义 → 允许后台运行；最近任务下拉加锁` | 44 |
| `ONE_UI` | `One UI` | `设置 → 电池 → 后台使用限制 → 从不休眠的应用 → 添加本应用；或：应用信息 → 电池 → 不受限制` | 53 |
| `FLYME` | `Flyme` | `手机管家 → 隐私权限（部分版本叫「权限管理」）→ 后台管理 → 允许后台运行；自启动 → 允许` | 48 |
| `AOSP` | `Android` | `设置 → 电池 → 电池优化 → 本应用 → 不优化` | 26 |

**证据表定稿（一行一族；`MEASURED` 必须含 `W##`）**

| 族 | 档 | 锚点（`evidenceAnchor` 原文） |
|---|---|---|
| `COLOR_OS` | `MEASURED` | `W77-04 四轮灭屏 A/B：允许完全后台行为后零回收（其余两开关未单独实测）` |
| `HYPER_OS` | `OFFICIAL` | `小米开发者文档 pId=1628/1624（PowerKeeper / 自启动权限）` |
| `EMUI_HARMONY` | `OFFICIAL` | `华为支持页：启动管理三开关；HarmonyOS 5+ 另有一套` |
| `MAGIC_OS` | `OFFICIAL` | `荣耀官方支持页 zh-cn00406916：启动管理 + 电池优化两步` |
| `ORIGIN_OS` | `OFFICIAL` | `vivo 官方适配指南 + 支持文档：后台高耗电 + 自启动 + 加速白名单` |
| `NUBIA` | `OFFICIAL` | `红魔装备库官方协议页：智能耗电保护 + 最近任务加锁` |
| `ONE_UI` | `OFFICIAL` | `三星开发者文档 app-management：四列表 + 应用信息→电池→不受限制` |
| `FLYME` | `COMMUNITY` | `魅族无官方后台管控文档；社区三方口径（手机管家→隐私权限→后台管理）` |
| `AOSP` | `OFFICIAL` | `Android 官方：电池优化豁免（AOSP 标准路径）` |

> `AOSP` 走 `OFFICIAL` 而非 `MEASURED`：本机是 ColorOS，AOSP 路径**从未在本机走过**。

### 2.6 `BackgroundRestrictionStatus` 新增（core-data/background，public 面）

```kotlin
/** 当前设备是否有独立于 AOSP 的厂商后台管控（卡片限定行用）。 */
fun hasVendorBackgroundLayer(): Boolean = OemBackgroundPolicy.isVendorManaged(oemFamily())

/** 当前族引导路径的证据强度，转成一句用户可读的括注（不含枚举名）。 */
fun backgroundEvidenceNote(): String = when (OemBackgroundPolicy.evidence(oemFamily())) {
    OemEvidence.MEASURED  -> "本机已实测"
    OemEvidence.OFFICIAL  -> "官方说明，未在本机实测"
    OemEvidence.COMMUNITY -> "社区经验，未在本机实测"
}

/**
 * 后台运行期间「通知可能不可见」的诚实提示（W78 D10②/硬点 3）。
 *
 * 两个成因**都可检测**，所以不写成一句模糊的免责：
 *  ① 未授予 `POST_NOTIFICATIONS`（API 33+ 运行时权限）；
 *  ② 用户把 `background_run` 渠道关掉了（`getNotificationChannel(...).importance == NONE`，API 26+，免权限）。
 * 返回 null = 通知可见（调用方据此**不渲染**任何提示 ⇒ 绝大多数用户零噪音）。
 *
 * ⚠️ 措辞纪律：只说事实（通知栏不会有提示），**不承诺**「一定有通知」，
 * 也不说「会影响保活」—— FGS 在 A13/A14 上都会继续跑，说「影响保活」同样是错的。
 */
fun backgroundNotificationHint(): String? {
    val permissionMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(appContext, PERMISSION_POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    return when {
        permissionMissing -> "未授予通知权限：后台运行仍会继续，但通知栏不会显示常驻通知。"
        isBackgroundRunChannelBlocked() ->
            "「后台运行」通知渠道已被关闭：后台运行仍会继续，但通知栏不会显示常驻通知。"
        else -> null
    }
}

/** `background_run` 渠道是否被用户关闭（免权限；API 26+ 才有渠道概念，minSdk 31 ⇒ 恒可用）。 */
internal fun isBackgroundRunChannelBlocked(): Boolean {
    val manager = appContext.getSystemService(NotificationManager::class.java) ?: return false
    val channel = manager.getNotificationChannel(AgentForegroundService.NOTIFICATION_CHANNEL_ID) ?: return false
    return channel.importance == NotificationManager.IMPORTANCE_NONE
}
```

- 新增 import：`android.app.NotificationManager`、`android.content.pm.PackageManager`、`android.os.Build`（已有）、`androidx.core.content.ContextCompat`。
- 权限名字面量沿用 `AndroidGenerationNotifier` 的口径（`"android.permission.POST_NOTIFICATIONS"`，避免 InlinedApi lint）；两处各写一次可接受，或提到一个共享常量——**本波不为此新建文件**（少一个公共面）。

既有 `isBatteryOptimizationIgnored()` / `backgroundGuideText()` / `oemDisplayName()` / `openBatteryOptimizationSettings()` / `openAppDetails()` **全部不动**。

### 2.7 设置卡（`SettingsViewModel` + `SettingsScreen`）

**状态字段新增 3 个**（`SettingsUiState`，其余不动）：

```kotlin
val oemVendorManaged: Boolean = false,   // 该族是否有厂商后台管控
val oemEvidenceNote: String = "",        // 证据括注（人话）
val backgroundNotificationHint: String? = null,  // 通知不可见时的诚实提示（null = 不渲染）
```

`refreshBackgroundRestriction()` 里补三行赋值（`hasVendorBackgroundLayer()` / `backgroundEvidenceNote()` / `backgroundNotificationHint()`）。

**卡片逐行定稿**（`itemIndex = 4` 不变；顺序即渲染顺序）：

| # | 元素 | 文案（定稿，可直接粘贴） | 样式 |
|---|---|---|---|
| 1 | `GroupTitle` | `后台运行保障` | — |
| 2 | 状态行（二态） | `已加入系统电池优化豁免` / `未加入系统电池优化豁免` | `bodyMedium` / `onGlass` |
| 3 | 限定行（恒定） | 厂商族：`仅 Android 系统层面；厂商可能另有独立的后台管控（见下方路径），本项不覆盖。`<br>AOSP 族：`仅 Android 系统层面；本机为接近原生的系统，无额外厂商管控。` | `labelSmall` / `onGlassSubtle` |
| 4 | 路径行 | `"${oemName}（${oemEvidenceNote}）：${oemGuideText}"`（如 `ColorOS / realme UI / OxygenOS（本机已实测）：设置 → …`） | `labelSmall` / `onGlassSubtle`，`padding(top = 8.dp)` |
| 5 | 兜底行 | `路径可能因系统版本略有差异；找不到时，在设置顶部搜索框输入「电池优化」或「应用启动管理」。` | `labelSmall` / `onGlassSubtle`，`padding(top = 4.dp)` |
| 6 | 按钮 | `去系统设置` / `应用详情`（不变） | `GlassButton` |
| 7 | **通知不可见披露（条件）** | 仅当 `backgroundNotificationHint != null` 时渲染该字符串（两种成因二选一，见 §2.6）：<br>· 缺权限：`未授予通知权限：后台运行仍会继续，但通知栏不会显示常驻通知。`<br>· 渠道被关：`「后台运行」通知渠道已被关闭：后台运行仍会继续，但通知栏不会显示常驻通知。` | `labelSmall` / `onGlassSubtle`，`padding(top = 10.dp)` |
| 8 | 尾注（**W78-11 已实现 `26195e4`**：r12 定稿，主理人裁「加」） | `后台生成会增加耗电与发热；即使完成上述设置，切后台后仍可能被冻结或回收；部分机型会在锁屏后数秒内冻结本进程，此时生成会暂停、解锁后自动继续。中断后可从恢复卡继续。` | `labelSmall` / `onGlassSubtle`，`padding(top = 10.dp)` |

> **第 7 块为什么不并入尾注（对主理人「不得新增行」的处置，需主理人确认）**：
> 并入尾注 = 给**所有用户**永久加一句与他们无关的话（绝大多数用户权限已授予、渠道正常）；条件渲染 = **受影响用户看到 1–2 行，其余用户 0 行**。所以本设计选择条件块，`不得新增行` 的约束在「未受影响用户」这一侧是**严格满足**的（视觉行数不变）。
> **若主理人坚持无条件并入尾注**，替换方案（照此逐字替换尾注末句即可）：
> `后台生成会增加耗电与发热；即使完成上述设置，切后台后仍可能被冻结或回收，部分机型还会在灭屏后限制 CPU。若未授予通知权限或关闭了「后台运行」通知渠道，后台运行仍会继续但通知栏不会有提示。中断后可从恢复卡继续。`（+41 字符，全用户可见，卡行数 +1）

> 第 7 块的措辞纪律（D10②）：只说**事实**（通知栏不会有提示），**不承诺**「一定有通知」，也不说「会影响保活」——在 A13/A14 上 FGS 仍会运行，说「影响保活」同样是错的。两句都**不含**「可能」「建议」这类模糊词：成因是确定的（权限/渠道二态可查），措辞也必须确定。

> **第 8 块（尾注）W78-11 的措辞纪律（r12 定稿，`26195e4` 已逐字照抄实现）**：
> 1. **只说「部分机型」，不说具体厂商** —— ColorOS 是实测到的，但未证遍（W78-09 未答）；写成「ColorOS 会在…」就是把一次实测说成通用事实。
> 2. **「锁屏后数秒内冻结本进程」是机制点名**，实测依据 = R15（灭屏 ~10s 触发 `SM_EVENT_LCD_OFF_UNIFIED_FREEZE` + `freeze uid` + `FROZEN_TRANS`，四轮 + 六项控制变量，含「允许完全后台行为」= ON 的截图证据）。**不带具体秒数**（不同机型/版本阈值不同，写「10 秒」会变成伪承诺）。
> 3. **「生成会暂停、解锁后自动继续」是实测行为描述，不是承诺** —— 依据 = 冻结只卡 CPU、不杀 run，解冻后续跑（本次 `F stay=416` 解冻后 run 继续）。⚠️ **反证条件**：若某机型解冻后 run 不续（例如冻结期间累计触发 300s 墙钟硬预算被熔断），本条必须改为「解锁后可能无法继续，可从恢复卡继续」——**不许为了让文案好看而留着不准确的旧句**。
> 4. **不写「必然 / 一定 / 保证」**，也**不写「建议关闭 XX 开关」**（W78-09 未证，任何开关建议都是无据指导）。
> 5. **长度纪律**：新句**替换**了旧句里的「部分机型还会在灭屏后限制 CPU」（该机制被点名后旧句冗余），净增 **17 字符**（64 → 81），视觉行 2.3 → 2.9 ⇒ 整卡上限由「≤15（第 7 块出现时 ≤17）」调整为「**≤16（第 7 块出现时 ≤18）**」；裁剪顺序不变（证据括注 → 尾注的机制点名句 → 限定行）。

**删掉的旧文案（必须删，不是改）**：`切后台或锁屏时，系统更不容易冻结本应用` / `锁屏或长时间留在后台时，系统可能冻结本应用，生成会中断`。理由：前者是效果承诺（被 W77 A/B 证伪于 OEM），后者把「冻结」与「电池优化」绑成因果（冻结是 app-freezer/OEM 层，不是电池优化开关的直接结果）。

### 2.8 测试契约

**`RunForegroundPolicyTest`**（替换 `ongoingNotificationIdIsSharedWithSpeedNotifier`，净 0）：

```kotlin
@Test
fun ongoingNotificationIdMustDifferFromSpeedNotificationId() {
    // 属性：FGS 通知 id 与速度通知 id 必须不同 —— 速度通知的 stop() 会 cancel 自己的 id，
    // 共用即「第二轮起 FGS 无通知」。改回同值 = 这条红。
    assertNotEquals(
        OngoingNotificationSlot.SPEED_NOTIFICATION_ID,
        RunForegroundPolicy.ONGOING_NOTIFICATION_ID,
    )
}
```

**`RunForegroundPolicyTest` 续期三例**（+3，D9）：

```kotlin
@Test
fun wakeLockRenewIntervalToleratesTwoMissedRenewals() {
    // 属性：390s 超时 / 120s 周期 ≥ 3 —— 连续错过 2 次续期（Doze 推迟 delay / OEM 抖动）
    // 仍不断锁。周期改大到 ≥195s 或超时改小 = 这条红。
    val tolerance = RunForegroundPolicy.WAKE_LOCK_TIMEOUT_MILLIS /
        RunForegroundPolicy.WAKE_LOCK_RENEW_INTERVAL_MILLIS
    assertTrue("容错比 $tolerance < 3：一次续期丢失就可能断锁", tolerance >= 3)
}

@Test
fun wakeLockRenewalStopsAtHoldCap() {
    // 属性：续期有硬上限，到点必须停 —— 否则物理断路器被续期架空。
    assertTrue(RunForegroundPolicy.shouldRenewWakeLock(0L))
    assertTrue(RunForegroundPolicy.shouldRenewWakeLock(RunForegroundPolicy.WAKE_LOCK_MAX_HOLD_MILLIS - 1))
    assertFalse(RunForegroundPolicy.shouldRenewWakeLock(RunForegroundPolicy.WAKE_LOCK_MAX_HOLD_MILLIS))
    // 上限必须显著大于单次超时（否则续期毫无意义），且 ≤ 6 个墙钟预算（故障侧耗电有界）。
    assertTrue(RunForegroundPolicy.WAKE_LOCK_MAX_HOLD_MILLIS > RunForegroundPolicy.WAKE_LOCK_TIMEOUT_MILLIS)
    assertTrue(RunForegroundPolicy.WAKE_LOCK_MAX_HOLD_MILLIS <= 6 * RunForegroundPolicy.HARD_WALL_CLOCK_MILLIS)
}

@Test
fun wakeLockRenewalLogVolumeIsBounded() {
    // 属性：续期日志条数由结构保证有界 —— AgentLogStore 只有 200 条环形缓冲，
    // 若上限/周期被改成几百条，run 的诊断日志会被冲掉。
    val maxLines = RunForegroundPolicy.WAKE_LOCK_MAX_HOLD_MILLIS /
        RunForegroundPolicy.WAKE_LOCK_RENEW_INTERVAL_MILLIS
    assertTrue("续期日志上限 $maxLines 条，超过环形缓冲容量的 1/10", maxLines <= 20)
}
```

**新文件 `OngoingNotificationSlotTest`**（+3，纯 JVM，无 Android 依赖）：

**硬点 2 契约：object 状态的用例隔离（写死，不许省）**

`OngoingNotificationSlot` 是进程级 `object` ⇒ **JUnit 同一 JVM 里状态跨用例存活**。若某用例 `attach` 后断言失败退出，残留的 sink 会让后续用例读到别人的状态而**假绿**（本仓「测试假绿」的历史形态之一）。三条硬性写法：

```kotlin
class OngoingNotificationSlotTest {

    // ① 每个用例一个**新的** owner 实例（identity 语义靠它，见用例 3）
    private val owner = Any()

    @Before
    fun assertCleanState() {
        // ② 进门先自证干净：上一个用例漏了清场 ⇒ 这里立刻红（fail-closed，
        //    而不是被静默 reset —— 「清空」会掩盖泄漏，「断言」会暴露泄漏）
        assertFalse("上一个用例泄漏了 sink（@After 没清场）", OngoingNotificationSlot.isActive())
    }

    @After
    fun cleanUp() {
        // ③ 出门必清场：按 owner 身份 detach（本用例的 owner，不会误清别人的）
        OngoingNotificationSlot.detach(owner)
        assertFalse("清场失败：sink 仍在", OngoingNotificationSlot.isActive())
    }
    // …用例见下
}
```

1. `attachDeliversTextAndReportsAccepted`：`attach(owner) { true }` 后 `isActive()` 为 true、`publish("x")` 返回 true 且 sink 收到 `"x"`；
2. `publishReturnsFalseAfterDetach`：`detach(owner)` 后 `isActive()` 为 false、`publish("x")` 返回 false（notifier 据此回落自己发 1001）；
3. `detachWithAnotherOwnerKeepsSink`：**两个不同的 owner 实例**——`attach(ownerA)` 后 `detach(ownerB)`（`Any()` 新实例，identity 不等）⇒ `isActive()` **仍为 true**、`publish` 仍投递；再 `detach(ownerA)` ⇒ false。这条钉的是「新旧服务交错时，旧服务不会清掉新服务的 sink」；也正因为要测它，`owner` 必须是**实例**而不是 `Unit`/字符串常量（后者在 Kotlin 里可能被常量池合并 ⇒ identity 断言失效）。

> 清场用 `detach` 而不是新增 `resetForTest()`：生产代码不加测试专用 API（见 §2.2 末尾说明）。

**`OemBackgroundPolicyTest`**（替换 1 + 新增 3）：

1. 替换 `recentsLockOnlyForFamiliesThatSupportIt` → `recentsLockHintOnlyForSupportedFamilies`：
   ```kotlin
   val supporting = setOf(COLOR_OS, HYPER_OS, EMUI_HARMONY, MAGIC_OS, ORIGIN_OS, NUBIA)
   OemFamily.values().forEach { f ->
       val t = OemBackgroundPolicy.guideText(f)
       if (f in supporting) assertTrue("$f 支持最近任务加锁，文案必须提示：$t", t.contains("加锁"))
       else assertFalse("$f 未确认支持加锁，文案不得写：$t", t.contains("加锁"))
   }
   ```
   （**9/9 全量**，堵住「只断言 5/8」的漏测洞。）
2. `everyFamilyHasEvidenceLevel`：`values().forEach { assertNotNull(evidence(it)); assertTrue(evidenceAnchor(it).isNotBlank()) }`
3. `measuredFamiliesCarryWaveAnchor`：`evidence == MEASURED ⇒ anchor 含 Regex("W\\d\\d")`（防「没测过却说测过」）
4. `nubiaFamilyClassified`：`classify("ZTE","RedMagic") == NUBIA`、`classify("nubia","nubia") == NUBIA`

**新增 FGS 渠道**（`LiquidAgentApplication.onCreate`，幂等）：

```kotlin
NotificationChannel(
    AgentForegroundService.NOTIFICATION_CHANNEL_ID,   // public companion 常量（见 §2.4）
    "后台运行",
    NotificationManager.IMPORTANCE_LOW,
).apply { description = "生成期间保持进程与 CPU 不被系统冻结"; setShowBadge(false) }
```

> ⚠️ 可见性：`RunForegroundPolicy` 是 `internal`（模块级）⇒ **app 模块看不见**，所以渠道 id 挂在 `AgentForegroundService.companion`（public）上，`RunForegroundPolicy` **不重复定义**。**这是本契约里唯一需要按可见性调整的一处**，实现时以「app 能编译通过」为准。

**`@Test` 计数**：755 → **764**（slot +3、续期 +3、OEM 策略 +3，替换 2 个不增减）⇒ 落地后必须同步 `.github/workflows/build.yml:506` 的 `baseline=755 → 764`（严格口径：`grep -rnE "^\s*@Test\b" --include=*.kt . | wc -l`）。

### 2.9 守卫契约（#45 / #46）

**#45 —— `background/` 包级行数上限**

```bash
check "W78 background 包行数上限（合计 ≤1000 / 单文件 ≤300；触顶 = 启动 background 拆分评审，非改阈值）" \
  bash -c 'D=core-data/src/main/java/com/rickeal/agent/core/data/background
           if [ ! -d "$D" ]; then echo "::error::$D 不存在（第 45 条守卫面已失效）"; exit 1; fi
           n=$(wc -l "$D"/*.kt 2>/dev/null | tail -1 | awk "{print \$1}")
           [ -n "$n" ] && [ "$n" -gt 0 ] || echo "::error::$D 未扫到 .kt（提取为空 ⇒ 禁 fail-open）"
           [ "$n" -le 1000 ] || echo "background/ 合计 $n 行，超 1000 行上限（触顶 = 启动 background 拆分评审，非改阈值）"
           for f in "$D"/*.kt; do
             m=$(wc -l < "$f")
             [ "$m" -le 300 ] || echo "$(basename "$f") $m 行，超单文件 300 行上限（触顶 = 拆分评审：建议 WakeLock 持有 / 通知构建 各外提，非改阈值）"
           done'
```

- **实测（r6 / C5 落地后，以 `wc -l` 为准，主理人已复跑）**：包合计 **884**；`AgentForegroundService.kt` **212**（拆分前 298）｜`RunWakeLockKeeper.kt` **110**（新文件；设计估 ≈90，差值全是类头 KDoc）｜`OemBackgroundPolicy` 198｜`BackgroundRestrictionStatus` 155｜`RunForegroundPolicy` 114｜`AgentRunForegroundController` 95。`notify/OngoingNotificationSlot.kt` 57 行**不在本守卫扫描面**（`background/` 之外，按设计不纳入）。
  - 拆分本身把包合计从 857 抬到 884（+27 = keeper 类头 KDoc）—— **这是「先拆后加」的正常代价**，不是膨胀：单文件水位从 99.3% 降到服务 70.7%（212/300）、keeper 36.7%（110/300）。
  - 单文件**守卫**余量（对 300 而言）：服务 **88** 行 / keeper 190 行 / policy **102** 行；包级余量 **116** 行（884/1000）。⚠️ keeper 的 190 只是「离守卫 300 还有多远」，**不是可用的**——它的交接级硬上限是 110（余 0），见下表。

- **W78 已在本波内完成拆分（C5，主理人令：不接受把重构债留给下一波）**：298/300 = 99.3% 的水位不允许进交接 —— 那样等于把「下一波第一件事必然是重构」写死。抽出 `background/RunWakeLockKeeper.kt`（WakeLock 全套：acquire / startRenewal / release + 私有 scope/job + 30min 上限，实测 **110** 行），服务回落 **212** 行，包合计 **884**（纯搬迁，无新增逻辑）。
  - ⇒ **#45 阈值 1000 / 300 不变**，单文件余量从 2 行恢复到 **88 行**；包级余量 **116** 行。
- **若再次触顶（预案）**：优先外提 **`OngoingNotificationBuilder`**（build + contentIntent + refresh 合并逻辑，≈60 行）；服务本体预计回落 ≈170 行。仍留在 `background/` 包内 ⇒ 包合计不变。**任何情况下都不调阈值**。
- fail-closed 三条：目录缺失 / 提取为空 / 计数非数字 ⇒ 红。

#### 🔒 已知水位表（交接表口径 —— 可直接引用本节）

| 对象 | 实测 | 上限 | 余量 | 上限性质 |
|---|---|---|---|---|
| `background/` 包合计 | **884** | 1000 | 116 | 守卫 #45 强制 |
| `AgentForegroundService.kt` | **212** | 300 | 88 | 守卫 #45 强制 |
| **`RunWakeLockKeeper.kt`** | **110** | **110** | **0** | ⚠️ **交接级硬上限（非守卫强制）** |
| `OemBackgroundPolicy.kt` | 198 | 300 | 102 | 守卫 #45 强制 |
| `RunForegroundPolicy.kt` | 114 | 300 | 186 | 守卫 #45 强制 |
| `AgentRunForegroundController.kt` | 95 | 300 | 205 | 守卫 #45 强制 |

**keeper 的 110 必须按「余量 0」读，不能按「离 300 还有 190」读** —— 后者会让人以为还有空间，前者才是交接事实。它是「先拆后加」这一刀换来的水位，**不是** #45 给的余量。下一波若要给它加代码 ⇒ **先按「先拆后加」外提**（可外提候选：续期循环体 → `WakeLockRenewalLoop`，或 `renew()` 的日志/降级分支），**不许靠删 KDoc 换空间**：KDoc 是「三个不变量 + 周期推导 + 诚实限定」的唯一载体，删了等于拿判据的可读性换行数。

**为什么本波不把它做成守卫**：守卫统一钉 300，为单个文件加 110 特例属于「为 1 个文件开 1 条规则」，还要同步 selftest 注入用例；当前由交接表 + 审查清单兜。若这条被踩过（有人加到 111+ 且没外提）⇒ 再升级为 #45 的单文件例外上限（成本 ≈2 行 + 1 个 case）。

**#46 —— OEM 引导全 schema**

```bash
check "W78 OEM 引导全 schema：每族一档证据 + MEASURED 必须带本仓取证锚点" \
  bash -c 'F=core-data/src/main/java/com/rickeal/agent/core/data/background/OemBackgroundPolicy.kt
           if [ ! -f "$F" ]; then echo "::error::$F 不存在（第 46 条守卫面已失效）"; exit 1; fi
           fams=$(awk "/enum class OemFamily \{/,/^    \}/" "$F" \
                  | grep -oE "^[[:space:]]{8}[A-Z][A-Z0-9_]*" | tr -d " " | sort -u)
           [ -n "$fams" ] || echo "::error::未能提取 OemFamily 枚举值（第 46 条守卫面失效，禁 fail-open）"
           rows=$(grep -oE "^[[:space:]]*OemFamily\.[A-Z][A-Z0-9_]* to \(OemEvidence\.[A-Z]+ to \"" "$F" \
                  | sed -E "s/.*OemFamily\.([A-Z0-9_]+) to \(OemEvidence\.([A-Z]+) to \"/\1 \2/")
           keys=$(printf "%s\n" "$rows" | awk "{print \$1}" | sort -u)
           [ -n "$keys" ] || echo "::error::未能提取证据表条目（第 46 条守卫面失效，禁 fail-open）"
           [ "$fams" = "$keys" ] || echo "OemFamily 枚举值与证据表键不一致（缺档或孤儿）：枚举=[$(echo $fams)] 表=[$(echo $keys)]"
           bad=$(printf "%s\n" "$rows" | awk "\$2!=\"MEASURED\" && \$2!=\"OFFICIAL\" && \$2!=\"COMMUNITY\" {print}")
           [ -z "$bad" ] || echo "证据档位取值非法（只允许 MEASURED/OFFICIAL/COMMUNITY）：$bad"
           grep -oE "^[[:space:]]*OemFamily\.[A-Z][A-Z0-9_]* to \(OemEvidence\.MEASURED to \"[^\"]*\"" "$F" \
             | while IFS= read -r line; do
                 printf "%s" "$line" | grep -qE "W[0-9][0-9]" \
                   || echo "MEASURED 条目缺本仓取证锚点（W##）：$line"
               done'
```

**判据纪律（照 W77 事故写死）**

| 纪律 | 本条怎么落 |
|---|---|
| 钉**行首代码形态**，禁裸 grep | 两个提取正则都以 `^[[:space:]]*OemFamily\.` / `^[[:space:]]{8}[A-Z]` 锚定；KDoc/注释里的 `OemFamily.COLOR_OS to (…)` 示例**不会**命中（`*` 后要求 `to (` 且整行形态）。⚠️ 因此**禁止在 KDoc 里写完整示例行**（写了会污染提取，是本条自带的已知面，注释里明写）。 |
| 0 命中 ≠ 通过 | 提取为空一律 `::error::` + 判红（fail-closed），不静默放行。 |
| 守卫报红 ≠ 真命中 | case79b / 80b 必须断言输出含**违规事实行**（如 `超 1000 行上限` / `MEASURED 条目缺本仓取证锚点`），不能只按守卫名匹配。 |
| 每新增守卫必同步 selftest | case79（#45：往 `background/` 注入 1 个 **350 行**假 `.kt` ⇒ 实测 857+350=1207 越 1000、单文件 350 越 300，**两条判据同时触发**；⚠️ 夹具与断言都**不得写死包合计的精确值**——包体会随波次变化，只认「超 1000 行上限」「超单文件 300 行上限」两条事实行）/ case79b（断言输出里 `超 1000 行上限` 与 `超单文件 300 行上限` **两条事实行都在** ⇒ 一次夹具行使两条判据）；case80（#46：删掉 `OemFamily.NUBIA to (OemEvidence.OFFICIAL …)` 一行 ⇒ 枚举↔表不一致）/ case80b（另注入一条 `OemEvidence.MEASURED` 但锚点无 `W##` ⇒ 命中第三条判据）。 |
| 元数据同步 | `KNOWN_GUARDS` 追加 `45 46`；映射表追加 `background/*) echo "45"` 与 `OemBackgroundPolicy.kt) echo "45 46"`（**具体行必须在通配行之前**，bash `case` 先匹配先赢）；`RunForegroundPolicy.kt` 由 `"43"` 改 `"43 45"`；case53 元判据必须复跑（W77 曾因注释写成「第 43 条」导致 case53 红）。 |


**#47（parked —— 下一波落地，本波时间不够跑第二次全量 selftest）—— Service 字段初始化期不得取系统服务**

> 起因 = R14（真机 P0，C6 已修当前实例）。**C6 修的是这一个实例，不是这一类写法** —— 下一波任何人再写一次字段初始化就会原地复现，而这条 NPE 编译期、单测、lint **三层全不报**（见 R14）。所以守卫必须补，只是不挤在本波。

| 项 | 形态（可机械判定） |
|---|---|
| 扫描面 | `core-data/src/main/java/com/rickeal/agent/core/data/background/*Service.kt`（保守起点；确认误报面可接受后再扩到全仓 `**/*Service.kt`） |
| 违规判据 | **类体字段声明行**里出现 `getSystemService(`：`^[[:space:]]*(private|internal|protected)[[:space:]]+(val|var)[[:space:]].*getSystemService\(` ⇒ 判红，输出**行号 + 该行原文**（便于定位） |
| **反向断言（必须配，否则误杀正确写法）** | `onCreate` 内的 `wakeLockKeeper = RunWakeLockKeeper(getSystemService(PowerManager::class.java))` 是**合法**的，不得判红。判据能区分二者，靠的是行首 `val/var`：字段行有、赋值行没有 ⇒ **正则必须带 `(val|var)`**，只匹配 `getSystemService(` 会把合法写法一起杀掉 |
| fail-closed | 扫描面为空 / 目录缺失 / 提取不到任何 `*Service.kt` ⇒ 红（禁静默绿） |
| selftest | **case81**：注入「字段初始化形态」⇒ 必红，且输出含行号与该行原文；**case81b**：注入「onCreate 内赋值形态」⇒ **必绿**。⚠️ case81b 比 case81 更重要 —— 它证明反向断言成立；只有 81 没有 81b 的话，守卫可能把正确写法也判红而无人发现（误杀比漏杀更难察觉，因为修法是「改代码绕过守卫」） |
| 编号依据 | 现役守卫到 **#46**（`KNOWN_GUARDS` 含 45 46、`check` 共 46 条），故本条为 **#47**；selftest 下一个空闲 case 号为 **81**（79/80 已用于 #45/#46） |

---

## 三、实现清单（顺序即提交序，建议 5 个提交）

| # | 文件 | 动作 | 估行 | 提交 |
|---|---|---|---|---|
| 1 | `core-data/.../notify/OngoingNotificationSlot.kt` | 新增（§2.2 全文） | +45 | C1 通知所有权 |
| 2 | `core-data/.../notify/GenerationNotifier.kt` | 抽 `buildText`；`onTick` 加借道；删私有 `NOTIFICATION_ID`；`stop()` KDoc | ±25 | C1 |
| 3 | `core-data/.../background/RunForegroundPolicy.kt` | `ONGOING_NOTIFICATION_ID = 1002`；`WAKE_LOCK_RENEW_INTERVAL_MILLIS` / `WAKE_LOCK_MAX_HOLD_MILLIS` / `shouldRenewWakeLock`；KDoc 改写 | ±18 | C1 |
| 4 | `core-data/.../background/AgentForegroundService.kt` | attach/detach/refresh + `iconRes` + 专用渠道 + `companion.NOTIFICATION_CHANNEL_ID` + `companion.clearStaleNotification`；**WakeLock 全套已按主理人令外提（C5）**，此处只留三个 `keeper.*` 调用点 | 实测 **212** | C1 / C5 |
| 4b | `core-data/.../background/RunWakeLockKeeper.kt` | **新增（C5「先拆后加」）**：`acquire` / `startRenewal` / `release` + 私有 scope/job + 30min 上限；行为规格见 §2.4 与 §一 D9 | 实测 **+110** | C5 |
| 5 | `app/.../LiquidAgentApplication.kt` | 新建 `background_run` 渠道（幂等）+ **`clearStaleNotification(this)`（D10①）** | +12 | C1 |
| 6 | `core-data/src/test/.../OngoingNotificationSlotTest.kt` | 新增 3 例 | +45 | C1 |
| 7 | `core-data/src/test/.../RunForegroundPolicyTest.kt` | 替换 id 测试（净 0）+ **续期 3 例（D9）** | +45 | C1 |
| 8 | `core-data/.../background/OemBackgroundPolicy.kt` | 重写为表 + 9 族 + 证据 + 删 `supportsRecentsLock` | ~134 → ~200 | C2 文案/证据 |
| 9 | `core-data/.../background/BackgroundRestrictionStatus.kt` | +2 方法 + `backgroundNotificationHint()` / `isBackgroundRunChannelBlocked()` | +40 | C2 |
| 10 | `core-data/src/test/.../OemBackgroundPolicyTest.kt` | 替换 1 + 新增 3 | +30 | C2 |
| 11 | `feature-settings/.../SettingsViewModel.kt` | 状态 +3 字段、refresh 赋值 +3 行 | +12 | C3 卡片 |
| 12 | `feature-settings/.../SettingsScreen.kt` | 卡片 8 块文案（§2.7，第 7 块条件渲染） | ±30 | C3 |
| 12b | `feature-settings/.../SettingsScreen.kt` | **W78-11（r12 定稿，主理人裁「加」）**：尾注第 8 块文案替换（§2.7 定稿逐字照抄），净增 17 字符；**不动第 7 块的条件渲染逻辑** | ±2 | C6 文案
| 13 | `scripts/arch-guard.sh` | #45 / #46 | +60 | C4 守卫 |
| 14 | `scripts/arch-guard-selftest.sh` | case79/79b/80/80b + `KNOWN_GUARDS` + 映射表 | +80 | C4 |
| 15 | `.github/workflows/build.yml` | `baseline=755 → 764` | ±1 | C4 |
| 16 | `docs/15-backlog.md` | W77-02 重启前提改写（D7）；新增 W78-01（9 族真机取证）/ W78-02（FLYME 加锁待证）/ W78-03（OriginOS 应用冻结）/ W78-04（小米 PowerKeeper intent 探针）/ W78-05（D9 续期真机 V8 复验，含 ≥2min 长 run 场景） | +6 行 | C4 |
| 17 | `docs/handoff-20261022-wave78-bg-run.md` | 交接（含 `foregroundId=1001 → 1002` 的口径更新，W77 交接 §四 的 `foregroundId=1001` 已成历史值；含 D9 的诚实限定） | 新文件 | C4 |

> C1 一次性提交（1–7）的理由：`ONGOING_NOTIFICATION_ID` 改值、服务改渠道、notifier 改借道、续期三件事共享同一次真机验证（V1–V4、V8），拆开提交会出现「中间态不可运行」的窗口。

**零改动面（硬约束，守卫 #44 会盯）**：`AgentRunForegroundController` / `AppContainer` / `ChatRunCoordinator` / `ChatViewModel` / `core-agent/**` / `core-engine/**` / 8 处 `generationNotifier.stop()` 调用点。

---

## 四、验证判据

### 4.1 单测 / 守卫 / 闸门（本机可全跑）

| # | 判据 | 通过线 |
|---|---|---|
| U1 | `OngoingNotificationSlotTest` | 3/3 绿 |
| U2 | `RunForegroundPolicyTest` | **10 例**全绿（7 原 − 1 替换 + 1 替换 + 3 续期） |
| U3 | `OemBackgroundPolicyTest` | **11 例**全绿（8 原 + 3 新，替换 1） |
| U4 | `arch-guard.sh` | **46/46** exit 0 |
| U5 | `arch-guard-selftest.sh --only 45,46` | PASS=4 FAIL=0（79/79b/80/80b） |
| U6 | `arch-guard-selftest.sh` 全量（含元 case53） | 全绿；⚠️ 只跑 `--only` 不算口径被验证（W77 §9.1 实摔） |
| U7 | `:app:lintDebug`（`_ci-tools/localbuild.sh` 口径） | `no new issues`（W77 §9.1：新增代码后本地必须补跑） |
| U8 | `assembleDebug` | BUILD SUCCESSFUL |
| U9 | `scripts/fulltest.sh`（注入三环境变量） | 9 模块 / tests=**764** / failures=1（`SandboxFileScannerTest` Windows symlink 已知假红） |
| U10 | 严格 `@Test` 计数 | 764，且 `build.yml` baseline 同步 |

### 4.2 真机（OPPO PDRM00 / ColorOS / SDK 33）—— 每项给出「怎么读」

| # | 场景 | 判据（可机械读） |
|---|---|---|
| V1 | **连续两轮 run（间隔 <10s）+ 关闭速度通知开关** | 第二轮全程 `dumpsys notification --noredact` 中本包含 `id=1002`；日志有「已进入前台」但**无**第二次「服务创建」⇒ 服务续用且通知在。**这是 B-2 的正面判据**（W77 无此判据，故漏了）。 |
| V2 | 开启速度通知 + 后台 run | ① 通知文本随 run 推进变化（含 `token/s` 与 `首字 … ms`）；② `dumpsys notification` 里本包 notification **条数 = 1**（不出现两条常驻）。 |
| V3 | 关闭速度通知 + 后台 run | 通知文本恒为「正在后台运行 · 点击返回」（**不谎报速度**）；`id=1002` 在栏。 |
| V4 | run 终态后 10s | 服务消失（`dumpsys activity services` 无本服务）+ 通知消失 + `dumpsys power` 的 `Wake Locks: size=0`。 |
| V5 | 设置卡真机文本 | `uiautomator dump` 取文本，与 §2.7 各块**逐字**一致（W78-11 落地后尾注须含「锁屏后数秒内冻结本进程」）；ColorOS 括注 = `本机已实测`；整卡视觉行数 ≤16（第 7 块出现时 ≤18）；**权限已授予且渠道正常时第 7 块不出现**；在系统设置里关掉「后台运行」渠道后返回，第 7 块必须换成「渠道已被关闭」那句（V5b）。 |
| V6 | 从系统电池优化页返回 | 二态行刷新（`LifecycleResumeEffect` 既有面）；限定行与路径行**不随二态变化**（语义解耦的判据）。 |
| V7 | 连续 3 轮 run 后 | `dumpsys notification` 无残留 / 无重复 id；无「僵尸 sink」（服务已销毁但 1002 仍在栏 ⇒ R1 反证）。 |
| **V8（r11 已修订，原判据作废）** | **续期机制 + ColorOS 冻结窗口** | 发一条必触发审批的题面 → 灭屏放置 **> 7 分钟** → 解锁批准 → run 正常结算。判据链（**必须用 §4.4 的 ACQ/REL 历史，禁用段位检测**）：<br>① **续期机制**：应用日志出现「WakeLock 续期（已持 120006ms）」，且 History 区该时刻有一条 `ACQ` ⇒ 机制正常；<br>② **冻结窗口**：`SM_EVENT_LCD_OFF_UNIFIED_FREEZE` + `freeze uid:` + `FROZEN_TRANS` 出现，且 History 区在续期 `ACQ` 之后 **~2.7s** 即出现 `REL` ⇒ 冻结窗口内续期无效（**这是预期，不是失败**）；<br>③ **解冻补锁**：`am start` 唤起后 History 区出现 `ACQ` 且其后无 `REL` ⇒ 解冻后立即补锁成功；<br>④ **收尾卫生**：结算后 10s 内 `Wake Locks: size=0`、无新增续期行（协程随服务取消，**无泄漏**）。<br>⚠️ **原判据「锁全程未消失」已作废** —— 它把「冻结」当成失败，而冻结是 ColorOS 的既定行为（连「允许完全后台行为」= ON 也拦不住，见 R15）。**正确问法**：机制有没有按周期续？解冻后补没补上？而不是「锁有没有一直握着」。 |
| **V9** | **D10① 残留通知清理** | run 中 `adb shell kill -9 <pid>`（模拟进程被强杀）→ 记 `dumpsys notification` 是否有 1002 残留 → 冷启动 App → 复核残留已清。**若 kill -9 后本无残留，则如实记「AMS 已回收，清理为纵深防御，本条判据 vacuous」**（不假装它是修复）。 |

### 4.3 本机不可验（如实挂账，不写「已验证」）

| 命题 | 处置 |
|---|---|
| A14+ FGS 类型强制 / `specialUse` 运行时行为 | W77-01 维持 parked（SDK 33 平台不校验 ⇒ 任何探针 **vacuous**） |
| 其余 8 族路径真实性（含新增 NUBIA） | 无设备。用 `OemEvidence` 在卡面明示「官方说明，未在本机实测」—— **这是本波对 B-6 的处置方式**：不假装验证，而是把未验证这件事本身产品化。 |
| `specialUse` subtype 过 Play 人审 | 无上架动作，无法验；降级路径（`runCatching + warn`）已就位 |
| OEM 深链可达性 | 本波不做（W77-03） |
### 4.4 🔴 锁态测量方法论（r11 新增 —— 比 R15 本身更重要）

**这一节存在的理由**：W77-04 的销账是**用错的判据量出来的**。同一段日志，用两种判据得到相反结论；而被采信的那种（段位检测）恰好是假阳性的那种。**判据方法失效，比结论偏差更难察觉** —— 结论错了会有人质疑，方法错了会被当成「实测通过」写进台账。

#### 4.4.1 段位检测为何失效

`dumpsys power` 有两个语义完全不同的区：

| 区 | 语义 | ColorOS 实测行为 |
|---|---|---|
| `Wake Locks`（托管段）/ `Restored Wake Locks` | **当前托管中的锁** | ⚠️ **锁释放后不清理**：历史区已 `08:26:29.804 REL LiquidAgent::Run`，该段**仍列着** `LiquidAgent::Run (uid=10310 pid=28455)` |
| **Wake Lock History** 区 | **ACQ/REL 事件流**（历史事实） | 权威：`ACQ` / `REL` 逐条留痕，不随释放清理 |

⇒ **判 held 必须用 History 的 ACQ/REL 事件对**：最后一条为 `ACQ` 且其后无 `REL` ⇒ 持有；出现 `REL` ⇒ 已释放。**用「段里还有没有」判 held 必然假绿** —— W77-04 四轮的「全程 held=true」即此因。

#### 4.4.2 判据形态（可直接粘）

```bash
# 权威锁态：ACQ/REL 事件对
adb shell dumpsys power | grep -E "(ACQ|REL) LiquidAgent::Run"

# OEM 强制回收计数（W77 同轮「0 条 force-release」同样不成立，改用同源历史后可见）
adb shell dumpsys power | grep -E "FroceReleaseWakeLock"
```

⚠️ **尾随空格坑（本波实踩）**：`REL` 行**无 `(partial)` 后缀**（`ACQ` 行有），所以
- 错：`grep -E "(ACQ|REL) LiquidAgent::Run "`（带尾随空格）⇒ **只匹配到 ACQ、漏掉全部 REL** ⇒ 只见「已获取」不见「已释放」⇒ 假绿；
- 对：`grep -E "(ACQ|REL) LiquidAgent::Run"`（不带尾随空格）。

这与本仓「关键词判据必须排除自家文案」是同一族纪律：**判据的正则本身要能被证伪** —— 只匹配到「想要的」那半边时，必须回源看原文，而不是采信计数。

#### 4.4.3 派生纪律（三条）

1. **同一段日志用两种判据得到相反结论时，回源看原文**，不采信任何一种 grep 的计数（W77 的「0 条 force-release」与本次的「全程 held=true」都栽在这里）。
2. **「未检出」≠「未发生」**：段位检测查不到 force-release，不等于系统没回收（本次 `add … 'Liqui' LONG … to FroceReleaseWakeLock list` 一直都在）。
3. **判据要先自证会响**：任何「X 未发生」的结论，必须先有一次「注入 X ⇒ 判据变红」的演练，否则那条判据从未被行使。


---

## 五、不做清单与理由

| # | 不做的事 | 理由 |
|---|---|---|
| 1 | **W77-01** A14+ 真机补测 | 无 API 34+ 设备；本机 SDK 33 上类型强制不生效 ⇒ 探针 vacuous（W77 设计 §一.4 已定案） |
| 2 | **W77-02** 通知「停止」action | D7：无进程级取消入口；「停服务不停 run」= 把用户终止演成系统中断，比不做更糟。重启前提已改写 |
| 3 | **W77-03** OEM 深链 | 无「系统页也找不到入口」的实测反馈；跨版本不稳 + 失败无反馈。重启前提维持 |
| 4 | 其余 8 族真机取证 | 无设备。**用证据档显式标注代替**，不写「已适配」 |
| 5 | 小米 PowerKeeper 页面 intent（唯一官方背书的程序化跳转） | 官方文档说「任何应用都可以调用」，但 intent 的 action/component 未在真机验证 ⇒ 属「探针先行」项。**挂 W78-04（W79 探针）**：真机 `am start` 可达 + 落到本应用限制页才算通过 |
| 6 | vivo「后台弹出界面」权限 / 商店审核红线申报 | 属分发渠道面（本仓 gallery/开源分发，无商店动作）；且该权限影响的是通知横幅形态，不是 run 存活 |
| 7 | OriginOS「应用冻结」文案 | 菜单名跨版本差异大 + 与加速白名单语义重叠（D4） |
| 8 | FLYME「最近任务加锁」文案 | 证据仅 COMMUNITY（D4）；加错会白耗用户时间。挂 W78-02 |
| 9 | One UI「不在 Sleeping/Deep sleeping 列表」等价路径 | 与「从不休眠的应用」等价，写了只是同一件事两种说法（D4） |
| 10 | **把续期改成「无超时 `acquire()`」** | D9：违反 W30 物理断路器思想 —— 控制器卡死时锁永久持有 = 真耗电事故。续期必须带总持有上限（30min） |
| 11 | 续期期间「按 `isHeld` 判断是否被回收」 | D9：OEM 在系统侧回收，客户端 `isHeld` 仍为 true ⇒ 判不出。故无条件重申请 |
| 12 | 续期与 run 的实际计算状态联动（审批等待时不持锁） | 服务只能看到 `isBusy`（审批等待时也为 true），要区分需从 run 侧开一条「正在计算」通道 ⇒ 动 run 语义面。用 30min 总上限兜住即可 |
| 13 | `setGroup` 分组方案 | 分组不消除重复（D2） |
| 14 | run 生命周期上提 / `applicationScope` | 铁律 + W77 已裁（`ChatRunCoordinator` 类头 scope 契约） |
| 15 | 「后台继续运行」用户开关 | W77 §九.1 已裁不做，本波不翻案（少一个状态面） |
| 16 | 修 `containsAny` 跨字段误命中（v22-F3） | 改匹配算法要动 9 族判定，回归面 >> 收益；记已知面（D4 注） |
| 17 | 修 `ChatRunCoordinator:880` 注释里的「10 个 `generationNotifier.stop()`」 | 实际该文件 5 处、全仓 8 处 ⇒ 注释数字陈旧。属 P3 文案，本波不动 feature-chat（零改动面），挂 P3 |
| 18 | 复审31 §四 的 13 项 P3 + 8 件小修 | 不属本波范围（复审31 §六.1 待总裁裁决 A/B） |
| 19 | **W78-06：`RunWakeLockKeeper` 单测 + 复用路径自愈** | ① **可测性**：`RunWakeLockKeeper(null)` 能构造 ⇒ 可断言「`powerManager` 为空时 `acquire()` 只 warn 不崩、`isHeld` 恒 false」；四个时长参数带默认值可喂边界。② **待补三条用例**：`null` 分支 / `release()` 幂等（连调两次不抛）/ `startRenewal()` 幂等（连调两次只起一个 job）。③ **若将来出现「服务被保留 + run 重来」的复用路径**：**keeper 自愈，顺序不许反** ——<br>`startRenewal()` 内 **先** `if (!serviceScope.isActive) serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`，**再**做 `renewJob != null` 的幂等短路。<br>⚠️ **反了就是静默失效**：若先短路，则「scope 已死 + `renewJob` 已被置 null」这一态下短路不生效、继续 `launch` 进**已取消的 scope** ⇒ 协程立即结束、锁不再续期且**零日志零异常**（正是本仓「判据在但不行使」的形态）。这是本条唯一容易写反的地方。<br>**不得**在服务侧补调 `startRenewal()` —— 否则重新变成两个启动点，把 C5 收敛掉的东西又长回来。本波不做的理由：加用例要同步 `build.yml` baseline，且续期**算术**已由 `RunForegroundPolicy` 三条纯函数用例覆盖。⚠️ 补用例时**不许**顺手往 keeper 里加代码：**110 行是交接级硬上限**（见 §2.9 水位表），先外提再改 |

> 冷启动清理 id 1002 原在本清单（「不做」），**经主理人补充后改为做**（D10①）—— 定性为纵深防御，判据见 V9，且如实标注「若 kill -9 后本无残留则本条 vacuous」。

---

## 六、风险与反证条件

| # | 风险 | 反证条件（出现即本结论作废） |
|---|---|---|
| R1 | 僵尸 sink：服务被系统单独销毁但 `detach` 未走 ⇒ notifier 把文案投进虚空（用户看不到速度，也看不到 1001） | V7：服务已销毁但 `id=1002` 仍在栏 / 速度文案不再出现 |
| R2 | 反复 `startForeground` 刷新在 OEM 上被限流（通知文本不更新） | V2 红：run 中通知文本恒为初值 |
| R3 | 用户关掉 `background_run` 渠道 ⇒ FGS 通知不可见（新引入的面） | **可检测**（`getNotificationChannel(id).importance == IMPORTANCE_NONE`，免权限）⇒ 设置卡第 7 块直接披露成因，不是「靠文案兜底」。反证：`dumpsys notification` 显示该渠道 blocked 而卡上第 7 块没出现 ⇒ 检测没接上 |
| R4 | 证据档标错（把 COMMUNITY 标成 OFFICIAL） | 锚点里的出处不是厂商官方域名。守卫只能验「有没有锚点」，**验不了真伪** ⇒ 只能靠评审抽查；本条如实申报为判据上限 |
| R5 | 文案变长导致卡片被挤出首屏 / 文本截断 | V5 红：`uiautomator dump` 文本与草稿不一致，或视觉行数 >15 |
| R6 | 删 `supportsRecentsLock` 后，未来需要「按族渲染不同控件」 | 出现该需求 ⇒ 那时再建表（YAGNI 不预付） |
| R7 | `@Test` 计数漂移未同步 `build.yml` | CI 红（W77 §9.1 同类） |
| R8 | `NOTIFICATION_CHANNEL_ID` 放 `RunForegroundPolicy`（internal）导致 app 编译红 | 编译期即暴露 ⇒ 按 §2.8 的可见性说明移到 `AgentForegroundService.companion` |
| **R9** | 续期协程泄漏（`serviceScope` 未取消 / 循环无终止条件） | V8③：run 结算 10s 后 `Wake Locks: size=0` 且日志不再出现「续期」；若结算后仍有续期行 ⇒ 协程比服务活得久 |
| **R10** | 续期日志冲掉诊断日志（环形缓冲 200 条） | V8②：一次 run 的续期行 >20 ⇒ 周期/上限被改坏（单测 `wakeLockRenewalLogVolumeIsBounded` 先拦） |
| **R11** | 续期让「物理断路器」名存实亡（锁被无限续下去） | `shouldRenewWakeLock` 在 30min 处返回 false；若出现 >30min 的持续持有 ⇒ 上限判据失效 |
| **R12** | 续期被误读为「已解决 ColorOS 深睡」 | **r11 已实证：确实没解决** —— 续期后 2.7s 锁即被回收，冻结期间协程不执行。处置 = ① §一 D9 已把定位改成「冻结窗口外 + 解冻补锁」三句 ② §二.4 已作废原稿「每 120s 有 10s CPU」那句 ③ 设置卡文案不得宣称能对抗冻结（§2.7 尾注口径不变，但禁止在文档/交接里升级措辞）。反证：若有人据此关闭 W78-05 的复验 ⇒ 本条复发 |
| **R13** | ~~服务 298/300 仅余 2 行~~ **由 W78 C4 消除**（本波内已落地：`RunWakeLockKeeper` 外提 ⇒ 服务 **212**、单文件余量 **88** 行、包 884） | **残余风险 = 包合计**：884/1000（余 116 行）。下一波若**同时**改 OEM 文案（`OemBackgroundPolicy` 198）与服务本体，先看行数台账再动手；`scripts/line-table.sh` 存在则以它为准（不存在则直接 `wc -l`）。反证：为省几行而把 300 或 1000 改大 ⇒ 本守卫失去意义 |
| **R14** | **Service 字段初始化期取系统服务 ⇒ 构造期 NPE ⇒ 进程级崩溃（P0；真机首跑抓到，三层闸门全绿）**：`private val wakeLockKeeper = RunWakeLockKeeper(getSystemService(PowerManager::class.java))`。Service 由框架 `Class.newInstance()` 构造，**此时 `attachBaseContext()` 尚未调用** ⇒ `getSystemService` 内部 base 为 null ⇒ NPE ⇒ `Unable to create service` 的 RuntimeException **直接打死整个进程**（真机表现「一发送就闪回桌面」，连续崩两次）。**为什么三层闸门全绿**：`assembleDebug` 只编译不执行 ／ `:core-data:testDebugUnitTest` 不构造 Service（无 Robolectric）／ `:app:lintDebug` 认为「`getSystemService` 在字段初始化里」是合法 API 用法（lint 没有「Service 生命周期时机」这条规则） | 反证（= 风险已实现）：`adb logcat -b crash` 出现 `Unable to create service ... AgentForegroundService`。**处置 = C6**：改 `onCreate` 注入（`private lateinit var wakeLockKeeper`，`onCreate` 首行赋值）+ KDoc 警示「字段初始化期 base 为 null，取系统服务必须等 attachBaseContext」。**静态兜底 = 守卫候选 #47（parked，判据见 §2.9）** |
| **R15** | 🔴 **ColorOS 灭屏 ~10s 触发 cgroup 级进程冻结 —— 保活的真实硬上限（真机 2026-10-22 取证，四轮受控 + 两个对照臂）**。日志链（落盘 `_w78_dev/`）：`08:39:25 notifyAppGoBackground` → `08:39:32 p_job/p_service` → `08:39:35.552 SM_EVENT_LCD_OFF_UNIFIED_FREEZE to uids=[10310]` → `08:39:35.559 can transition from M to F` → `08:39:35.565 freeze uid: 10310 … pids: [28455] scene: |airPlane|LcdOff` → `08:39:35.566 HANS sendMessageToKernel: … FROZEN_TRANS`；**同一毫秒** `OplusProxyWakeLock: add … 'Liqui' LONG … to FroceReleaseWakeLock list`（⇒ **锁被回收 = 进程被冻结，同一事件的两面**）。`08:46:32.124 unfreeze … reason: Activity scene` / `F exit(), F stay=416` ⇒ **冻了 6 分 56 秒**，直到 `am start` 唤起 Activity 才解冻。<br>**已逐一控制变量，冻结均不受影响**：① FGS（`isForeground=true` / `foregroundId=1002` / `specialUse` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`，manifest 已核）② WakeLock（390s 超时 + 120s 周期续期）③ AOSP 电池优化豁免 ④ **ColorOS「允许完全后台行为」= ON（`shot_sw5.png` 截图证实）** ⑤ 进程新旧（`am force-stop` 后冷启新 pid 10656 / 12795 复测）⑥ 飞行模式开（ctl1）/ 关（ctl2）各一轮。<br>**⇒ W77-04 的销账被推翻**：不是结论微调，而是**判据方法失效**（段位检测，见 §4.4）。**三层闸门全绿 ≠ 灭屏后进程还在跑** —— 与 R14 同族：闸门只验「代码对不对」，验不了「OEM 放不放你活」。 | 反证（=风险已实现）：`adb logcat -b system`（设备侧落盘）出现 `SM_EVENT_LCD_OFF_UNIFIED_FREEZE to uids=[<uid>]` + `freeze uid:` + `FROZEN_TRANS`；锁态用 §4.4 的 ACQ/REL 历史判。**处置**：不试图对抗（对抗不了），改为 ① 锁态判据换方法（§4.4）② 续期价值定位改为「冻结窗口外 + 解冻补锁」（§一 D9）③ 冻结期间用户体感由**恢复卡 / journal 恢复**兜底，不由保活兜底 ④ 设置卡文案不得宣称能对抗冻结。 |

---

## 七、与历史坑点/知识库的对照（本波判据的出处）

| 纪律 | 本波怎么落 |
|---|---|
| 「判据写进方案」≠「判据被执行」 | 每条新守卫都带注入用例（case79/79b/80/80b），且 79b/80b 断言**违规事实行** |
| 「0 命中 ≠ 通过」/ WARN 有闩 | #45/#46 的提取为空一律判红（fail-closed），不静默放行 |
| 「关键词判据必须排除自家文案」（W77 实摔） | #46 的提取正则钉 `^[[:space:]]*OemFamily\.… to (` 形态；并**禁止在 KDoc 写完整示例行**（自带的已知面，注释里明写） |
| 「测试必须钉属性而非手段」 | D2：`assertEquals(1001, id)` → `assertNotEquals(speedId, ongoingId)`；D1：5 族断言 → 9 族全量 + 文案含/不含 |
| 「守卫只钉结构不钉措辞」 | #46 只验 schema（键一致 / 档位合法 / MEASURED 有锚点），不验文案措辞；文案措辞由 9 族元测试与 V5 真机文本管 |
| 「外部报告可能不成立，回源码核验」 | 本波实核出 1 处报告错：`generationNotifier.stop()` 调用点报告称 5 处、**实为 8 处**（以源码为准，且该差异直接支撑了 D2 的「改 id 不改调用点」）；其余关键事实（零消费 / ORIGIN_OS 文案缺加锁 / 5-of-8 断言）经复核成立 |
| 「不留死代码」 | D1 删函数；D5 的 `evidence` 有生产消费（卡面括注），`evidenceAnchor` 的消费者是 #46 —— 与 `ModelPresets.evidenceLevel`（全仓零生产读点、仅 #39 消费）同构，明写防误删 |
| 「记忆 ≠ 交付物」 | 交接必须同步 `foregroundId=1001 → 1002` 的口径（W77 交接 §四 的旧值），否则下一波会照旧值写判据 |
| 「WARN 判据有闩 / 0 命中 ≠ 通过」在 D9 的对应形态 | 续期日志**不是**「有就行」：判据必须是「≥3 行续期 + 锁全程未消失 + 结算后归零」三件同时成立；只看到 1 行续期 ⇒ 可能第 2 次就达上限或协程被取消（要回日志看 `已持 …ms` 的递进） |
| **「三层闸门全绿」≠「服务能被创建」**（W78 R14 实摔） | `assembleDebug` 只编译不执行、JVM 单测不构造 Service（无 Robolectric）、lint 没有「Service 生命周期时机」规则 ⇒ **一条能打死进程的构造期 NPE 可以三层全绿**。凡涉及「框架实例化的组件」（Service / Receiver / Provider / Application）在**构造期/字段初始化期**碰系统服务或 Context，都必须真机首跑验证，不能以闸门绿代替 |
| **「纯搬迁」的判据是执行时机等价，不是代码行等价**（W78 R14 同一根因） | C5 把 `getSystemService` 从 `onCreate` 搬到字段初始化，逐字等价但时机变了 ⇒ 崩溃。任何「只搬不改」的重构，交付前必须回答一句：**这段代码的执行时机变了吗？**（初始化序 / 线程 / 生命周期阶段三者任一变化，就不是纯搬迁） |
| 设备面（知识库 §2） | 真机一律有线 adb + `MSYS_NO_PATHCONV=1`；发送键坐标随键盘漂移 ⇒ V1~V9 的 tap 必须动态 `uiautomator dump`；判「run 真的起来了」看 `Wake Locks` 出现 `LiquidAgent::Run` + 会话文件 mtime 推进；V8 需要「灭屏 >7 分钟」，`svc power stayon` 被沙箱拦 ⇒ 用 `input keyevent 224` 的反面（`input keyevent 26` 灭屏）并全程设备侧落盘 logcat |

---

## 八、修订记录（给主理人的核对表）

本文档经十二轮追加，**不是重写**。若某条内容在您手上的版本里「找不到」，请按本节定位：

| 版本 | 追加内容 | 落点 |
|---|---|---|
| **r1（首版）** | D1–D8 裁决 + §二 全部接口契约（含 9 族文案定稿）+ §三~§七 | §一 D1–D8、§二.1–§二.9 |
| **r2** | D9（WakeLock 续期）/ D10（两个连带面）/ D11（状态通道）+ 续期常量与服务契约 + 卡面第 7 块 + 风险 R9–R12 | §一 D9–D11、§二.1 续期常量、§二.4 续期实现、§二.7 第 7 块 |
| **r3** | ① D9 补「周期合法区间推导（与 10s 宽限 / 300s 硬预算自洽）+ 日志节流裁决 + 双档真机臂与加速验证 + vacuous 声明」<br>② 硬点 1：`refreshOngoing` 线程模型契约（主线程 post / 返回值=已受理 / 合并 / 销毁不复活 / 限流降级）<br>③ 硬点 2：`OngoingNotificationSlot` 用例隔离契约（`@Before assertFalse` + `@After detach` + 两 owner 实例用例）<br>④ 硬点 3：第 7 块披露**落地**并扩到「渠道被关闭」这一可检测成因；R3 从「只能靠文案」改为「可检测」 | §一 D9（三行新表）、§二.4「硬点 1 契约」表、§二.8「硬点 2 契约」、§二.6 `backgroundNotificationHint()`、§二.7 第 7 块 |
| **r4（守卫落地校准）** | 按落地后**磁盘实测**校准 #45 阈值推导（包 860、服务 298），把「服务仅余 2 行」写成**有意的冻结式上限**并给出拆分预案；case79 夹具改「不得写死包合计精确值」；新增风险 R13；#46 提取命令在干净树上**实跑通过**（枚举 9 = 表 9，档位合法，MEASURED 带 W77-04 锚点） | §二.9 阈值段、§二.9 判据纪律表、§六 R13 |
| **r5（拆分提前到本波）** | **主理人令：298/300 立即拆分，不接受把重构债留给下一波**（「先拆后加」是本仓终裁纪律）。文档改三处：§二.9 从「冻结式上限 + 下一波拆分」改为「C4 已落地 `RunWakeLockKeeper`（≈90 行）⇒ 服务 ≤230、单文件余量 ≥70」；预案降级为「若再触顶，优先外提 `OngoingNotificationBuilder`（≈60）」；R13 从「下一波必触」改为「C4 已消除，残余=包合计 860/1000」 | §二.9 #45 段、§六 R13、本节 |
| **r6（C5 落地回写）** | C5 落地实测回写：服务 **212** / keeper **110** / 包 **884**；keeper 构造按实现改为 **`PowerManager?` + 四个可注入时长**（比本设计原稿「持 Context」更好：不持 Context/Service ⇒ 销毁后误用从结构上不存在，且 `null` 分支可 JVM 测）；`release()` 顺序与**单一取消点**实测已对齐；新增 W78-06（keeper 可测但本波不加用例） | §二.9 实测段、§二.4 构造签名、§三 行 4/4b、§六 R13、§五 第 19 项 |
| **r7（缺陷留档）** | 实现复查发现**本设计 r1/r2 的降级路径存在锁泄漏**（只解锁不杀续期 ⇒ 续期循环下一 tick 把锁抢回来并续到 30min）；C5 的「`release()` 单一取消点」已结构性修好，契约以 C5 为准。同时记下其代价（取消后同实例不再自愈）与复用路径的自愈修法（keeper 内重建 scope）⇒ W78-06 扩写 | §二.4 末段、§五 第 19 项 |
| **r8（顺序钉死）** | 开发席指出 W78-06 ④ 的**唯一易写反点**：`startRenewal()` 必须「先重建 scope、再 `renewJob != null` 短路」，反了会在「scope 已死 + renewJob 已置 null」时 launch 进死 scope ⇒ **静默不续期**（判据在但不行使）。已写进 §五 第 19 项与 §二.4。同时确认**代码不再为这条加注释**（keeper 保持 110 行，判据留文档，避免破水位） | §五 第 19 项、§二.4 代价段 |
| **r9（水位口径）** | 主理人定：**`RunWakeLockKeeper` 110 行 = 硬上限**（交接级纪律，非守卫强制）。§2.9 新增「已知水位」表（包 884/1000、服务 212/300、keeper **110/110**、policy 198/300…），并写明**下一波加代码先外提、不许删 KDoc 换空间**、以及「本波不做成守卫」的理由与升级条件（被踩过再升 #45 例外上限） | §2.9 水位表、§五 第 19 项 |
| **r10（P0 留档）** | 真机首跑抓到本波**唯一 P0**：`Service` 字段初始化期调 `getSystemService` ⇒ 构造期 base 为 null ⇒ NPE ⇒ `Unable to create service` 打死进程（「一发送就闪回桌面」）。⚠️ **「C5 = 纯搬迁、行为零变更」这个判断不成立** —— 代码行确实逐字等价，但**执行时机**从 `onCreate` 前移到字段初始化，而这正是 Service 生命周期里 base Context 尚未 attach 的那一段。⇒ 判据要改成「搬迁后**执行时机**是否等价」，不是「代码行是否等价」。**这条是用户真机首跑发现的，不是三层闸门也不是审查发现的**（`assembleDebug` 不执行 / 单测不构造 Service / lint 无生命周期时机规则）。处置 = C6（onCreate 注入）；静态兜底 = #47（parked） | §六 R14、§二.9 #47、§七 判据纪律 |
| **r11（推翻 W77-04 销账）** | 真机取证（2026-10-22，设备 `13309cc8`）**推翻 W77-04 销账**，原话：「**W77-04 销账被推翻，判据方法（段位检测）失效而非结论微调**」。核心事实：ColorOS 灭屏 ~10s 触发 **cgroup 级进程冻结**，锁被强制回收与进程被冻结**同一毫秒**；已控制 FGS / WakeLock / AOSP 豁免 / **「允许完全后台行为」= ON** / 进程新旧 / 飞行模式六个变量，冻结均不受影响。W77-04 的「四轮锁全程 held=true」与「0 条 force-release」均系**段位检测假阳性**（`Restored Wake Locks` 段在锁释放后不清理 + grep 尾随空格漏掉 REL）。D9 续期的真实表现：**机制完全正常**（120006ms 周期精确命中），**但补不回冻结**（2.7s 后即被回收，冻结期间协程不执行 ⇒ 下次续期迟到 420s；解冻后可靠）。⇒ 续期定位改为「冻结窗口外保持 + 解冻立即补锁」。台账已由主理人更新（W77-04 reopened、W78-05 判据修订、新增 W78-08） | §六 R15、§一 D9 诚实限定、§二.4 实测段、§4.2 V8 修订、§4.4（新节）、§五 开放问题 |
| **r12（设置卡机制点名，W78-11）** | 主理人裁「**加，但只加机制点名，不改『可能』这个量词**」：§2.7 第 8 块尾注定稿为「……仍可能被冻结或回收；部分机型会在锁屏后数秒内冻结本进程，此时生成会暂停、解锁后自动继续。中断后可从恢复卡继续。」。定稿要点：① 只说「部分机型」不点厂商（W78-09 未证）② 不带具体秒数 ③ 「暂停/解锁后继续」是实测行为非承诺（附反证条件）④ 不写必然/保证、不给开关建议。新句**替换**旧句「部分机型还会在灭屏后限制 CPU」（机制点名后旧句冗余）⇒ 净增 17 字符，整卡上限 15→16（第 7 块出现时 17→18）。**W78-10 由主理人实测四臂回答，方案席不猜** | §2.7 第 8 块 + 措辞纪律、§一 D4 长度预算、§三 行 12b、§四 V5 |

**三个硬点的落点速查**

| 主理人提出的硬点 | 文档位置 | 一句话结论 |
|---|---|---|
| 硬点 1：`refreshOngoing` 线程模型 | §二.4「硬点 1 契约」表（6 行）+ §二.4 代码 | **必须切主线程**（否则后台刷新可落在 `stopForeground` 之后把通知复活）；返回值 = **已受理**，刷新失败**不回落**（回落会变成两条通知）；每秒一次用 `pendingText` 合并，队列恒 ≤1；限流/渠道被关 → warn 留痕 + 停留上一帧 |
| 硬点 2：object 测试隔离 | §二.8「硬点 2 契约」代码块 + 3 条用例 | 每用例新 `owner` 实例；`@Before assertFalse(isActive())` 暴露泄漏（而不是静默清空）；`@After detach(owner)` + 再断言；「非 owner detach 不清空」用**两个 `Any()` 实例**测（不能用 `Unit`/常量，可能被常量池合并） |
| 硬点 3：D10② 卡面披露 | §二.7 第 7 块 + §二.6 `backgroundNotificationHint()` | **已落地**，条件渲染（受影响者 1 行、其余 0 行）；覆盖**两种可检测成因**（缺权限 / 渠道被关）；若坚持并入尾注，替换文案已备（+41 字符，全用户可见） |
| 漏项 D9 | §一 D9（r2 已有，r3 补齐推导与判据） | **做**。周期 120s 的合法区间 (73s, 130s]（下界=不打扰常规 run，上界=容错比≥3）；30min 总上限保住断路器；续期协程随服务 `onDestroy` 取消；真机双档臂（弱 130s / 强 >420s），审批挂起本身就是加速器，不需要在生产代码装测试开关 |

## 九、开放问题（未证 —— 不得当结论用）

> 本节两条**都不是结论**。写在这里的目的是：防止它们被下一波的人（或我们自己）读成「已验证的必要项 / 已确认的无效项」。在拿到证伪证据之前，任何文案、交接、评审都不得引用它们作为依据。

| # | 开放问题 | 现状与为什么悬着 |
|---|---|---|
| W78-09 | **ColorOS 三个剩余开关是否与冻结豁免构成组合条件** | 「允许唤醒前台」「允许应用自启动」「允许应用关联启动」目前均为 **OFF**，adb 点击无响应（行容器与开关本体都试过，状态不变）。它们是否与「允许完全后台行为」构成组合条件、组合后能否豁免灭屏冻结，**未证**。⇒ 需要一次能点得动人机交互（非 adb tap）的实测，或 ROM 侧说明。**在证伪之前，不得写成「必要项」或「无效项」任一种结论**。 |
| W78-11 | **（不是开放问题，是已裁待实现）**设置卡尾注机制点名句 —— 主理人已裁「加」，定稿见 §2.7 第 8 块，由开发席实现；列在此仅防与 W78-09/10 混淆：**它的依据是 R15 的实测机制，不依赖 W78-09/10 的答案**（所以不必等它们） |
| W78-10 | **W77-04 四轮「run 正常结算」与本次「冻结 6m56s」如何并存** | 两轮都开着「允许完全后台行为」，但一轮 30–63s 内正常结算、另一轮冻结近 7 分钟。差异变量未知（题面长度 / 后台停留时长 / 冻结触发时机 / 设备侧设置在两轮之间是否被系统改回）。⇒ 需要复测「短题面 + 灭屏」与「长题面 + 灭屏」对照，才能判断 W77-04 四轮究竟是「从未冻结过」还是「冻结前已结算」。**这是 W77-04 reopened 后必须先回答的问题** —— 否则 reopened 也只是把一个结论换成另一个结论。 |
