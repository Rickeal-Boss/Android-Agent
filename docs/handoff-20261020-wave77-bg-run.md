# W77 交接：模型运行切后台 / 锁屏持续运行（白名单）

> 分支 `UI-change-1019`｜基线 tip `ae2be7f`（W76 UI 波收官）｜真机 OPPO PDRM00（ColorOS / **Android 13 / SDK 33**）
> 设计文档（含三报告裁决全文）= `docs/w77-bg-run-design.md`。本文件是**执行结果与判据**，不重复设计论证。

---

## 一、一句话结论

**需求已落地且真机验证**：run 期间由前台服务（`specialUse`）+ `PARTIAL_WAKE_LOCK` 维持，切后台/锁屏不再被冻结；
同时修掉了「VM 销毁丢半截答案」的既有缺陷。**但本机实测发现 ColorOS 会强制回收第三方 WakeLock**
（`OplusProxyWakeLock`），AOSP 电池优化白名单对它无效 ⇒ 白名单引导必须按 OEM 分别给路径，已实现 8 个家族。

---

## 二、三份外部报告的裁决（详见 design §一）

| 争点 | 裁决 | 依据 |
|---|---|---|
| FGS 类型 | **`specialUse`**（唯一） | 官方 service-types 页：`specialUse` Runtime prerequisites = **None**；`mediaProcessing` 仅限媒体转码 |
| 报告 B「specialUse 不可达」 | **误判** | 它列的 demo mode / Device Owner / ROLE_EMERGENCY / 精确闹钟 / VPN 是 **`systemExempted`** 的门槛 |
| 报告 A「端侧推理 = mediaProcessing」 | **无出处** | 官方描述只覆盖媒体资产处理，且该类型 6h/24h 上限 + onTimeout ANR 面 |
| run 生命周期 | **不上提 `applicationScope`**（保持 `viewModelScope`） | Coordinator 类头契约；上提需把 `_uiState`/`_streaming` 全部进程级化，且与「划卡 = 用户终止意图」冲突 |
| 报告 B 的 30 分钟探针 | **在本机 vacuous** | 类型强制自「targetSdk≥34 且平台≥34」生效；本机 SDK 33 平台不校验 ⇒ 只能验 FGS 机制，不能验类型合规 |
| `onCleared` 丢半截答案 | **本波修**（报告 B §六 的真发现） | 见 §三.4 的协程陷阱 |

---

## 三、实现清单

### 1. 后台保活（core-data，新包 `background/`）

| 文件 | 作用 |
|---|---|
| `RunForegroundPolicy.kt` | 纯策略：WakeLock 超时 390s（= 墙钟硬预算 300s + 90s 余量）、宽限窗 10s、类型分派阈值 34、通知 id 1001、subtype 常量 |
| `AgentForegroundService.kt` | FGS 壳：`startForeground` + `PARTIAL_WAKE_LOCK` + `onDestroy` 释放；`START_NOT_STICKY`；`stopWithTask=true` |
| `AgentRunForegroundController.kt` | 观察 `AgentRunner.isBusy`（复用 ThermalGovernor 范式）：忙则起服务，闲则 10s 宽限后停；启动被拒降级为「本次无保活」并留痕 |
| `OemBackgroundPolicy.kt` | 8 个 OEM 家族归类 + 每族可导航路径 + 最近任务加锁支持面（纯函数） |
| `BackgroundRestrictionStatus.kt` | 免权限检测 `isIgnoringBatteryOptimizations` + 跳系统电池优化页 / 应用详情页 |

### 2. Manifest / 权限

- `core-data/src/main/AndroidManifest.xml`：`<service android:foregroundServiceType="specialUse">` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE=on_device_llm_inference_completion` + `stopWithTask="true"`。
- `app/src/main/AndroidManifest.xml`：`FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` / `WAKE_LOCK`。
- **刻意不声明** `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（受限设置，无上架收益）。

### 3. 装配

- `AppContainer`：新增 `processScope`（落库专用，不可取消）、`backgroundRunController`（`init` 里 `start()`）、`backgroundRestrictionStatus`。
- `LiquidAgentApplication`：删掉私有的 `applicationScope`，冷启动预热改用 `container.processScope`（进程级 scope 全仓单点）。

### 4. 🔴 `onCleared` 半截答案丢失（本波修）

- `ChatRunCoordinator` 增加 `persistScope` 构造参数，`commit()` 的落库协程改走它。
  **陷阱**：`onCleared` 执行时 `viewModelScope` 已取消，若仍用 `scope.launch`，改成 `run.onStop()` 只会更新内存 UI、**磁盘零写入**（假修复）。
- `ChatViewModel.onCleared()`：`run.cancel()` → **`run.onStop()`**（flush → 快照 → cancel → reset → commit）；`run.cancel()` 方法已删除。
- 语义收敛：VM 销毁 = 用户显式停止，取消权只在用户，不在 Activity 栈。

### 5. 设置页「后台运行保障」卡

- 三态：已豁免 / 未豁免 + 当前 OEM 的**具体路径**（ColorOS / HyperOS / EMUI / MagicOS / OriginOS / One UI / Flyme / AOSP）。
- 两个按钮：去系统设置（`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`）/ 应用详情（兜底）。
- 诚实口径：「即使加白名单也不能保证不被系统强制回收」「后台生成会增加耗电与发热」「路径可能因系统版本略有差异」。
- `LifecycleResumeEffect`：从系统设置返回时重读豁免状态（不缓存、不落盘）。

### 6. 守卫

| 编号 | 判据 |
|---|---|
| **#43** | service 的 `specialUse` 声明 + 配对权限 + subtype 与 `RunForegroundPolicy.SPECIAL_USE_SUBTYPE` **逐字一致** |
| **#44** | `backgroundRunController.start()` 装配点 **恰好 1**；`ChatViewModel.onCleared` 必须 `run.onStop()`，禁裸 `run.cancel()` |

两条都有注入用例（`arch-guard-selftest.sh` case77 / case78），**证明判据会真响**，不是只在干净树上绿的僵尸规则。
⚠️ #44 的判据必须钉**行首代码形态**：本波实摔一次——`onCleared` 的注释里写着「原先只 run.cancel()」，裸 grep 把自家说明文字判成违规（「关键词判据必须排除自家文案」第 N 次复发）。

---

## 四、真机取证（OPPO PDRM00 / ColorOS / Android 13）

### V1 切后台（HOME）继续跑 ✅

```
22:24:21  ACQ LiquidAgent::Run（run 起）
22:24:24  HOME
22:27:48  [INFO] 正常结束：0 轮，模型自行给出最终答案
```
- `dumpsys activity services`：`isForeground=true foregroundId=1001`、`createdFromFg=true`、`allowStartForeground=PROC_STATE_TOP`（**满足 A12+ 后台启动 FGS 限制**）。
- 应用侧日志：`后台保活服务创建 → WakeLock 已获取（held=true）→ 已进入前台`。

### V2 锁屏 ✅（进程不被冻结）/ ⚠️（CPU 被 OEM 挂起）

- 灭屏后服务仍 `isForeground=true`；run 未被打断。
- 但 **`dumpsys power` 的 `Wake Locks` 里 `LiquidAgent::Run` 在灭屏约 10s 后消失**。

### 🔴 关键发现：ColorOS 强制回收第三方 WakeLock（A/B 双证据）

| 臂 | 前置 | 结果 |
|---|---|---|
| A | 无任何白名单 | 灭屏 ~10s 锁消失 |
| B | `cmd deviceidle whitelist +com.rickeal.agent.debug`（= `isIgnoringBatteryOptimizations=true`） | **仍然 ~10s 消失** |

系统侧证据：
```
D OplusProxyWakeLock: add uid: 10310 wakelock: ... PARTIAL_WAKE_LOCK 'Liqui' (uid=10310 pid=23440)
    to FroceReleaseWakeLock list
```
⇒ **AOSP doze 白名单在 ColorOS 上无效**，必须走 OEM 自己的「应用耗电管理」。
⇒ 同轮实验解释了首测的 **TTFT 200.9s**（灭屏 + 锁被回收 ⇒ CPU 深睡 ⇒ prefill 被拉长到分钟级）。

### V3 收尾卫生 ✅
- 22:27:48 run 结束 → 10s 宽限后服务销毁：`后台保活服务销毁（撤前台 + 解锁）`，`dumpsys activity services` = `(nothing)`，`Wake Locks: size=0`。

### V6 设置卡 ✅（真机文本）
```
后台运行保障 / 未加入电池优化豁免 / 锁屏或长时间留在后台时，系统可能冻结本应用，生成会中断
ColorOS / realme UI：设置 → 电池 → 应用耗电管理 → 本应用 → 允许后台活动 + 允许完全后台行为；最近任务里下拉卡片加锁
路径可能因系统版本略有差异。
后台生成会增加耗电与发热；即使加白名单也不能保证不被系统强制回收，中断后可从恢复卡继续。
```

### 未验（已挂账 W77-01 / W77-04）
- A14+ 平台的 FGS 类型强制（本机 SDK 33 无法取证）。
- 在 ColorOS 设置里**真实开启**「允许完全后台行为」后锁是否存活（只验了 AOSP 白名单臂）。

---

## 五、闸门实测（2026-10-20）

| 闸门 | 结果 |
|---|---|
| `assembleDebug` | ✅ BUILD SUCCESSFUL（增量 50s） |
| `arch-guard.sh` | ✅ **44/44**（新增 #43/#44；#26 基线双向同步 755） |
| `arch-guard-selftest --only 43,44` | ✅ PASS=4 FAIL=0（case77/77b/78/78b） |
| `scripts/fulltest.sh`（注入三环境变量） | ⚠️ **9 模块 / tests=755 / failures=1**，唯一失败 = `SandboxFileScannerTest.符号链接指向沙箱外的目录被拒绝并剔除`（**Windows symlink 已知假红，CI 绿**，W77 之前既有） |
| 新增单测 | ✅ `RunForegroundPolicyTest` 7 例 + `OemBackgroundPolicyTest` 8 例 |
| `@Test` 严格计数 | **755**（740 → 747 → 755），`build.yml:502 baseline=755` |

⚠️ 运维提醒：`fulltest.sh` 不注入 `JAVA_HOME` / `ANDROID_HOME` / `GRADLE_USER_HOME` 会**秒败**并沿用陈旧 XML（本波首跑即中，见 W74-05）。

---

## 六、挂账 delta（权威版 = `docs/15-backlog.md`）

| ID | 条目 | 状态 |
|---|---|---|
| W77-01 | A14+ 真机补测 FGS 类型合规（本机 SDK 33 无法取证） | parked |
| W77-02 | FGS 通知「停止」action（需新增 BroadcastReceiver） | parked |
| W77-03 | OEM 深链（`com.coloros.safecenter` 等）本波明确不做 | parked |
| W77-04 | ColorOS **真实白名单**臂未验（只验了 AOSP doze 白名单） | active |

---

## 七、下一步建议

1. 手动在 ColorOS 上开启「应用耗电管理 → 允许后台活动 + 允许完全后台行为」后复跑灭屏实验（W77-04 销账判据已写死）。
2. A14+ 真机到位后补类型合规真机臂（W77-01）。
3. 若后台 run 成为常用路径，再考虑通知「停止」action（W77-02）。
4. UI 波剩余项（U1–U5 用户裁决）不受本波影响。

---

## 八、自查（审查席两次因模型拥挤失败，主理人接手）

审查席（code-quality-reviewer）两次派单均因上游 429 失败，按配额纪律由主理人自查。核过的点与结论：

| 面 | 核法 | 结论 |
|---|---|---|
| `flushNow` 是否依赖已取消的 scope | 读实现（:157-168，全同步，无 `launch`） | ✅ onCleared 路径可正常排空缓冲 |
| `commit` 落库作用域 | 读 `commit`（`persistScope.launch`）+ `onStop` 调用链 | ✅ VM 销毁后仍能写盘 |
| 双提交竞态 | `persistState.shouldCommitAssistant` 两条判据（id + run 级文本） | ✅ 未改判据，onStop 与 Cancelled 互斥 |
| `AppContainer.init` 顺序 | 属性声明序：`observationScope` → `agentRunner` → `thermalGovernor` → 控制器 → `init` | ✅ 无前向引用 |
| FGS 异常路径 | `catch (RuntimeException)` → 解锁 + `stopSelf` + WARN | ✅ 不会打崩 App，降级为「无保活」 |
| 类型分派 | `usesTypedForeground(34)` + 单测 7 例 | ✅ 33 不传、34+ 传 |

**两条已知限制（P2，不阻塞）**

1. `ChatViewModel.onCleared` 会 `generationNotifier.stop()`（cancel 1001），而服务还要走 10s 宽限才停 ⇒ 宽限窗内可能出现「服务仍在前台、通知已撤」的 ≤10s 空窗。无功能影响，且服务随后销毁。
2. 控制器的 `started` 是「我们认为服务在跑」，不等于系统里的真实存活；若服务被系统单独杀掉，要等下一次 `isBusy` 翻转才会重新拉起（此时 run 本身多半也已结束，走 journal 恢复）。

**CI 触发面修正（同波）**：`UI-change-1019` 从 W76 起就不在 `build.yml` 的 `on.push.branches` 里 ⇒ 这两波推送**从未触发 CI**。已在 `d2dd023` 补入（`release.yml` 不同步，避免签名双轨占配额）。
