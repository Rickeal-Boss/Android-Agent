# W77 设计：模型运行切后台 / 锁屏持续运行（白名单）

> 基线：`UI-change-1019` tip `ae2be7f`（W76 UI 波收官）。真机 OPPO A13 `13309cc8` = **Android 13 / SDK 33**。
> 性质：动手前的设计裁决 + 落点清单。本波实现范围见 §6。

---

## 一、三份外部报告的冲突裁决

三份报告对同一件事给出了**互斥**的结论。逐条回源核验后：

| # | 争点 | 报告 A | 报告 B | 报告 C | **本波裁决** | 依据 |
|---|---|---|---|---|---|---|
| 1 | FGS 类型 | `mediaProcessing` 为主 | `specialUse` 不可达，只剩 `shortService` | `specialUse` 首选 | **`specialUse`（唯一）** | 官方 service-types 页实拉：`specialUse` 的 Runtime prerequisites = **None**；`mediaProcessing` 官方定义仅「对媒体资产做耗时操作（转码等）」，**不含端侧推理** |
| 2 | `specialUse` 门槛 | — | 列 demo mode / Device Owner / ROLE_EMERGENCY / 精确闹钟 / VPN ⇒ 判不可达 | 无运行时前置 | **B 错**：该清单是 **`systemExempted`** 的门槛，被误栽到 `specialUse` 头上 | 同上（两类型条目分列，B 抄串行） |
| 3 | run 生命周期 | 上提到 `applicationScope`（消灭「划掉即取消」） | 建议 FGS 只做进程保活，不接管 run | 同 B | **不上提**，保持 `viewModelScope` | `ChatRunCoordinator` 类头 KDoc 明写 scope 契约；上提需把 `_uiState`/`_streaming` 全部进程级化 ⇒ 动 feature-chat 主链，收益（划卡继续跑）与「用户显式终止意图」语义冲突 |
| 4 | 真机探针能否验证类型合规 | 未提 | 主张 30 分钟探针验 `specialUse` 是否抛异常 | 未提 | **该探针在本机 vacuous** | FGS 类型强制自 **targetSdk≥34 且平台≥34** 生效；本机 SDK 33 ⇒ 平台不校验。探针只能验 FGS 通知 / oom_adj / WakeLock / 冻结行为，**不能**验类型合规 |
| 5 | 划掉最近任务 | 视为要支持的场景 | 未覆盖 | `onTaskRemoved` → `stop()` 干净收尾 | **干净收尾**（不保活） | 划卡 = 用户显式终止；`stopWithTask` 默认 true 会销毁服务 |
| 6 | `onCleared` 丢半截答案 | 未提 | §六 指出，建议同波修 | 未提 | **本波修** | 见 §4（有一个必须绕开的协程陷阱） |
| 7 | 电池白名单 | 不建议申请 | 建议 L2 | 建议引导不申请 | **只做免权限引导** | `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 属受限设置，本项目未上架无收益、有政策成本 |

**一句话**：报告 C 的架构方向正确（宿主层、`isBusy` 观察、specialUse、WakeLock 超时兜底）；报告 B 的 `specialUse` 否决是**误判**，但其「`onCleared` 丢答案」与「探针方法学」两条是真发现；报告 A 的 `mediaProcessing` 结论**无出处**，其「上提 scope」不建议采用。

---

## 二、场景行为矩阵（W77 后）

| 场景 | 现状 | W77 后 | 机制 |
|---|---|---|---|
| 生成中按 HOME | 进程存活则可跑，但 cached 态 ~1min 后可被冻结（ColorOS 更激进） | **不被冻结**，run 正常结算 | FGS 提升进程优先级 + 免冻结 |
| 生成中锁屏 | CPU 可 suspend，推理停滞/变慢 | **CPU 保持**，run 正常结算 | `PARTIAL_WAKE_LOCK`（带超时） |
| 生成中划掉最近任务 | VM 销毁 → `run.cancel()` → 半截答案**不落库** | 走 `onStop()` 收尾：**Cancelled + 半截答案落库** | §4 修复 + `stopWithTask` 默认销毁服务 |
| 生成中切会话 | 同上（旧 VM 销毁，答案丢失） | 同上，落回**原会话** | 同上 |
| 进程被系统强杀 | journal 恢复卡 | 不变（FGS 不保证零查杀） | 既有 W2/W47 兜底 |

---

## 三、FGS 类型与平台分派

- Manifest：`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` + `WAKE_LOCK`；service 声明
  `android:foregroundServiceType="specialUse"` + `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="on_device_llm_inference_completion"/>`。
- 运行时：`SDK ≥ 34` → `ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_SPECIAL_USE)`；`SDK 31~33` → `startForeground(id, notification)`（不传 type，避免旧平台对未知位报错）。
- **合规叙事**（Play 申报用）：端侧 LLM 推理由用户前台发起、单 run 有 300s 墙钟硬预算与 44.9℃ 热熔断双重边界，用户离开应用后中断会作废已消耗算力 ⇒ 需前台服务维持至结算。
- 明确不选：`mediaProcessing`（语义不符 + 6h/24h 上限 + onTimeout ANR 面）、`dataSync`（零网络叙事冲突）、`shortService`（3min < 5min 硬预算）。

---

## 四、`onCleared` 半截答案丢失（本波必修）

**现象**：`ChatViewModel.onCleared()`（:529）只 `run.cancel()`；`ChatRunCoordinator.onStop()`（:552）才有 `flushNow → 快照 → cancel → resetStreaming → commitAssistant`。

**陷阱（报告 B 未提，但会让修复失效）**：`commitAssistant → commit()`（:1148）用 `scope.launch { repository.appendMessage(...) }` 落库，而 `onCleared` 执行时 `viewModelScope` **已被取消** ⇒ 直接改成 `run.onStop()` 只会更新内存 UI 状态，**磁盘仍不落**（假修复）。

**修法**：给 `ChatRunCoordinator` 增加 `persistScope` 构造参数（进程级 `SupervisorJob + IO`），`commit()` 的落库协程改走它；`onCleared` 改调 `run.onStop()`。`persistScope` 与 `scope` 职责分离：`scope` 管 run 生命周期（VM 死即取消），`persistScope` 只管「已产出的文本必须落盘」这一件不可取消的事。

---

## 五、通知策略

FGS 必须有常驻通知，而 `GenerationNotifier` 受用户开关控制且 `stop()` 会 `cancel(1001)`。

**裁决：单一 ID（1001）覆盖语义，service 与 notifier 共用。**
- service 启动时自建通知（`"端侧生成中" / "正在后台运行 · 点击返回"`，带 `contentIntent` 回 App）并 `startForeground(1001, …)`；
- 用户开着速度通知时，`notifier.onTick` 每秒 `notify(1001, …)` 覆盖刷新为速度文案（同 ID 覆盖，不新增第二条）；
- 用户关掉开关时，通知停留在 service 的基础文案（系统强制，不可避免；文案不谎报速度）；
- run 终态：`notifier.stop()` 与 `stopForeground(REMOVE)` 双保险。

**代价**：FGS 通知内容会被 notifier 覆盖（速度文案），这是有意的 —— 用户看到的是同一条通知的两种状态。

---

## 六、落点清单

| # | 文件 | 动作 |
|---|---|---|
| 1 | `core-data/.../background/RunForegroundPolicy.kt` | 新增：纯策略（宽限窗、类型分派、wake lock 超时） |
| 2 | `core-data/.../background/AgentForegroundService.kt` | 新增：FGS 壳 + WakeLock |
| 3 | `core-data/.../background/AgentRunForegroundController.kt` | 新增：观察 `isBusy` 启停服务 |
| 4 | `core-data/.../background/BackgroundRestrictionStatus.kt` | 新增：电池优化豁免检测 + 跳转 Intent |
| 5 | `core-data/src/main/AndroidManifest.xml` | 声明 service + property |
| 6 | `app/src/main/AndroidManifest.xml` | 三个权限 |
| 7 | `core-data/.../AppContainer.kt` | 装配 controller + 暴露 `processScope` |
| 8 | `app/.../LiquidAgentApplication.kt` | 冷启动预热改用 `container.processScope` |
| 9 | `feature-chat/.../ChatRunCoordinator.kt` | 增加 `persistScope`，`commit()` 走它 |
| 10 | `feature-chat/.../ChatViewModel.kt` | `onCleared` → `run.onStop()`；传 `persistScope` |
| 11 | `feature-settings/.../SettingsScreen.kt` / `SettingsViewModel.kt` | 「后台运行保障」卡（三态 + 引导 + 诚实口径） |
| 12 | `scripts/arch-guard.sh` | 新增 2 条守卫（service 类型/权限成对；控制器单例装配点） |
| 13 | `core-data/src/test/.../RunForegroundPolicyTest.kt` | 新增：纯策略单测 |

**内核零触碰**：`core-engine` / `core-agent` 一行不改；`AgentRunner.isBusy` 是既有公开面。

---

## 七、风险与反证条件

| # | 风险 | 反证条件（什么情况下本结论作废） |
|---|---|---|
| R1 | `specialUse` 在 A14+ 仍需运行时前置 | 官方 service-types 页出现 `specialUse` 的 Runtime prerequisites 非 None |
| R2 | 本机 SDK 33 无法验证类型合规 | 若换到 A14+ 真机实测 `startForeground(type)` 抛 `SecurityException` ⇒ 类型/权限配对有问题 |
| R3 | FGS 启停与 `isBusy` 抖动（引擎重建 / 子 run）造成启停风暴 | 真机连续两条消息间隔 <10s 时，服务生命周期计数 >1 次启动 |
| R4 | WakeLock 泄漏（异常路径未释放） | run 终态 10min 后 `dumpsys power` 仍见 `LiquidAgent::Run` |
| R5 | `persistScope` 落库与 VM 生命周期解耦后出现双写 | 同一回答出现两条 MODEL 消息（`persistState` 判据失守） |
| R6 | 通知 ID 共用导致 FGS 通知被 `stop()` 提前撤掉 | 后台 run 中通知消失但服务仍 `isForeground=true` |
| R7 | ColorOS 仍冻结 FGS 应用 | 锁屏 10min 后 tok/s 归零 / journal 停更 ⇒ 需白名单引导（本波已做卡） |

---

## 八、真机验证清单（区分可验 / 不可验）

**本机可验（SDK 33）**
| # | 场景 | 判据 |
|---|---|---|
| V1 | 发起 run → 立即 HOME | `dumpsys activity services` 显示本服务 `isForeground=true`；run 正常结算；通知在栏 |
| V2 | 发起 run → 锁屏 5min → 解锁 | run 仍在跑 / 已正常结算；`dumpsys power` 有 `LiquidAgent::Run` 锁 |
| V3 | run 结算后静置 10min | 服务消失、wakelock 0 持有、通知消失 |
| V4 | run 中划掉最近任务 | 半截答案落库（journal settled / 会话里有 MODEL 消息） |
| V5 | 连续两条消息间隔 <10s | 服务只启停一次（宽限窗生效） |
| V6 | 设置页「后台运行保障」卡 | 三态正确显示；按钮跳到系统页 |

**本机不可验（需 A14+ 真机或静态论证）**
| # | 命题 | 处置 |
|---|---|---|
| N1 | `specialUse` 类型在 targetSdk 36 + 平台 34+ 的强制与权限配对 | 文档论证 + 守卫钉死声明与权限成对；真机到货后补测 |
| N2 | `mediaProcessing` 的 6h 上限 / `shortService` 3min 超时 | 本波不采用该类型 ⇒ 无需验 |

---

## 九、开放问题（留给用户裁决）

1. 是否需要用户可见的「后台继续运行」开关（默认开）？本波**不做**（减少状态面），只做保障卡。
2. 通知是否要加「停止」action？本波**不做**（需新增 BroadcastReceiver），点通知回 App 即可停。
3. ColorOS 深链（`com.coloros.safecenter`）本波**不做**，只跳系统电池优化列表 + 应用详情页兜底。

---

## 十、🔴 真机实证：OEM 后台管控是独立于 AOSP 的第二套系统

**实验（2026-10-20 22:38–22:43，OPPO PDRM00 / ColorOS，Android 13）**

| 臂 | 前置 | 操作 | 观测 |
|---|---|---|---|
| A | 未加入任何白名单 | 发起 run → HOME → 灭屏 | 灭屏 10s 后 `dumpsys power` 的 `Wake Locks` 列表里 `LiquidAgent::Run` 消失 |
| B | `cmd deviceidle whitelist +com.rickeal.agent.debug`（等价于 `isIgnoringBatteryOptimizations=true`） | 同上 | **仍然消失**（同样 ~10s） |

**系统侧证据（决定性）**

```
10-20 22:39:06.496  2284  3908 D OplusProxyWakeLock: add uid: 10310
    wakelock: lock:142198198 PARTIAL_WAKE_LOCK 'Liqui' (uid=10310 pid=13242)
    to FroceReleaseWakeLock list
```

（`Froce` 为 ColorOS 自身拼写）⇒ **ColorOS 的 `OplusProxyWakeLock` 会在灭屏后把第三方
`PARTIAL_WAKE_LOCK` 放进「强制释放」名单**，AOSP doze 白名单对它无效。

**这意味着三件事**

1. **我们的 WakeLock 代码是对的**（应用侧日志 `WakeLock 已获取（超时 390000ms，held=true）`），
   被回收是 OEM 行为，不是实现缺陷；
2. **「白名单」必须按厂商分别引导** —— 只教用户去 AOSP「电池优化」页在 ColorOS 上等于没做；
3. **诚实口径**：不能承诺「设置后永不被杀」。FGS 仍能保证进程不被冻结（实测 `isForeground=true`
   在灭屏后持续保持），但 CPU 是否被挂起由 OEM 决定。

**副产物**：同一实验解释了 W77 第一轮「TTFT 200.9s」——灭屏 + 锁被回收 ⇒ CPU 深睡 ⇒
prefill 被拉长到分钟级。这是「不加白名单的代价」的量化样本。

## 十一、各国产 OEM 的后台管控差异（W77 实现面）

`OemBackgroundPolicy`（core-data，纯函数可 JVM 单测）按 `Build.MANUFACTURER` + `Build.BRAND`
归类，并给出**该家族可照着点的路径**：

| 家族 | 判定 | 引导路径（卡上原文） | 最近任务加锁 |
|---|---|---|---|
| ColorOS | OPPO / realme / OnePlus | 设置 → 电池 → 应用耗电管理 → 本应用 → 允许后台活动 + 允许完全后台行为 | ✅ |
| HyperOS / MIUI | Xiaomi / Redmi / POCO | 设置 → 应用设置 → 应用管理 → 本应用 → 省电策略 → 无限制 | ✅ |
| EMUI / HarmonyOS | HUAWEI | 手机管家 → 应用启动管理 → 本应用 → 关闭「自动管理」，允许自启动/关联启动/后台活动 | — |
| MagicOS | HONOR | 手机管家 → 应用启动管理 → 本应用 → 手动管理，允许自启动/关联启动/后台活动 | — |
| OriginOS / FuntouchOS | vivo / iQOO | 设置 → 电池 → 后台耗电管理 → 本应用 → 允许后台高耗电；i 管家 → 自启动管理 | ✅ |
| One UI | Samsung | 设置 → 电池 → 后台使用限制 → 加入「从不休眠的应用」 | — |
| Flyme | Meizu | 手机管家 → 权限管理 → 后台管理 → 本应用 → 允许后台运行 | — |
| AOSP | 其它 | 设置 → 电池 → 电池优化 → 本应用 → 不优化 | — |

**判定顺序的坑**：Redmi / POCO 的 `Build.MANUFACTURER` 仍是 `Xiaomi`，必须靠 `BRAND` 命中；
荣耀独立后 `MANUFACTURER=HONOR`（走 MagicOS），老荣耀仍是 `HUAWEI`（走 EMUI）——两者菜单不同，
**不能合并**。这些都由 `OemBackgroundPolicyTest` 的 8 条用例钉死。

**明确不做**：按厂商硬编码深链 Intent（`com.coloros.safecenter` / `com.miui.securitycenter` 等）。
理由：跨版本不稳定、失败后无反馈、且部分 ROM 会拦截外部 Intent —— 「跳到系统页 + 给准确路径」
的确定性高于「赌一个深链」。
