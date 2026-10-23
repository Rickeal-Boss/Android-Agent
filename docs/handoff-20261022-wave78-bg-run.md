# W78 后台保活强化波 — 交接（2026-10-22）

> 分支 `UI-change-1019`，`92f65d6..5fd319d` 共 11 提交，CI run 318/319/320 三连 success；**后续 `ee0df65` + `12bef26` 为 W78-12 跟进批（冻结感知生成超时），本地三闸门全绿但未推 CI（本环境无 GitHub 凭据，见 §七）**。
> 本波设计权威 = `docs/w78-bg-run-design.md`（1090 行，r1–r12）；挂账权威 = `docs/15-backlog.md`。
> ⚠️ **设计权威边界**：W78-12（冻结感知生成超时）**不在**该设计文档内 —— 它是本波收尾后的跟进批，
> 设计记录见本文件 §七 + `docs/15-backlog.md` 的 W78-12（复审 34 P3-4）。

## 一、本波交付（按提交序）

| 提交 | 内容 |
|---|---|
| `10ea330` | C3 对齐设计 r3：主线程串行刷新（`mainHandler.post` + `pendingText`/`refreshScheduled`/`destroyed`，返回值 = 「已受理」）、`backgroundNotificationHint()` 双成因披露、slot 用例隔离 |
| `54c7684` | 借道槽语义收口 + `isBackgroundRunChannelBlocked()` 收紧为 private |
| `8731809` | 设置卡语义解耦：AOSP 豁免 ≠ OEM 后台管控（8 块结构，删两句效果承诺） |
| `37bf7e5` | C5 抽出 `RunWakeLockKeeper`（先拆后加；服务 298/300 触顶是动因） |
| `3d7f757` | **C6 修 P0**：Service 构造期 `getSystemService()` NPE 打死进程 |
| `9176513` | 守卫 #45（background/ 包 ≤1000 / 单文件 ≤300）+ #46（OEM 证据全 schema）+ selftest case79/79b/80/80b/80c/80d + baseline 764 |
| `6c019af` | 台账：ColorOS 灭屏冻结实证（推翻 W77-04 销账）+ W78-08 |
| `26195e4` | W78-11 设置卡尾注点名「锁屏后数秒内冻结本进程」机制 |
| `4377495` | 订正代码里两处被真机推翻的表述 + keeper 压回 110 行 |
| `5fd319d` | W78-10 已答 + W78-09 开放问题入账（纯文档，0 run 正常） |
| `ee0df65` | **W78-12 跟进批①（断点续跑而非误掐）**：新增 `FreezeClock` 接口 + `MonotonicFreezeClock`、`ScreenStateMonitor`（灭屏累计，过近似冻结窗口）、`AgentRunner.freezeAwareTimeout`（看门狗 `scopeJob.cancel(GenerationTimeoutCancelledException)`，复用「超时→重建引擎+重试一次」链路）、`FreezeAwareTimeoutTest` 3 例、`AppContainer` 注入 `screenStateMonitor` 为 `freezeClock`；`@Test` baseline 764→767 |
| `12bef26` | **W78-12 跟进批②（再测试 + 改进）**：`ScreenStateMonitor` 冷启灭屏态预置（`PowerManager.isInteractive==false` 时 `offAtNanos` 起算，防首段冻结被误算可运行）+ 注册升级 `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`（API33+ 强制显式 flag） |

**门禁基线**：arch-guard **46/46**；`@Test` **767** = `build.yml` baseline（W78-12 新增 3 例，764→767）；`supportsRecentsLock` **0 命中**（D1 删除干净）；`background/` 包 **974/1000**（W78-12 新增 `ScreenStateMonitor` 75 行）、服务 **221/300**（W79 复核实测；原 212 是 C6 修复前快照，复审 34 P3-1）、keeper **110**（余量 0，硬上限）；`AgentRunner.kt` **2815/2900**（#31 守卫余 85）。

## 二、🔴 本波最重要的发现（下波必读）

### 1. ColorOS 灭屏进程冻结 = 保活的真实硬上限
灭屏 **~10–11s**，`OplusHansManager` 走 `SM_EVENT_LCD_OFF_UNIFIED_FREEZE` → `F enter()` → `freeze uid: 10310` → netlink `FROZEN_TRANS` 到内核（**cgroup 级**）。实测一次冻 **416s**。**锁被强制回收的时刻 = 进程被冻结的时刻（同一毫秒）**。

**冻结不受六项因素影响**（逐一控制变量，四轮受控 + 两个对照臂）：
① FGS（`isForeground=true` / `foregroundId=1002` / `specialUse` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`）
② WakeLock（390s 超时 + 120s 周期续期）
③ AOSP 电池优化豁免
④ **ColorOS「允许完全后台行为」（截图证实为 ON，仍冻）**
⑤ 进程新旧（`am force-stop` 冷启新 pid 复测）
⑥ 飞行模式开关（开/关各测一轮）

⇒ **本仓 FGS + WakeLock + 续期是必要不充分条件。** 后续方向应是「降低被冻概率」而非「冻结后续命」（候选见 W78-08 重启前提）。

### 2. 🔴 锁态判据禁用段位检测（方法论级，比结论更重要）
`dumpsys power` 的 `Restored Wake Locks` 段条目**在 REL 后不清理**（实证 `08:26:29.804 REL` 后该条目仍在段内）⇒ 用「段里有没有 `LiquidAgent::Run`」判 held **必然假绿**。**W77-04 的「四轮锁全程 held=true」就是这样假绿的**，其销账已被本波推翻（W77-04 → reopened，四态 ⚠️）。

**权威判据 = Wake Lock History 区 ACQ/REL 事件对**（末条 ACQ 且无后续 REL ⇒ 持有）。
⚠️ **坑**：`REL` 行无 `(partial)` 后缀 ⇒ grep 必须写 `(ACQ|REL) LiquidAgent::Run` 且**不带尾随空格**，否则只见 ACQ 不见 REL ⇒ 把 RELEASED 判成 HELD。

### 3. 冻结不杀 run，只拉长 TTFT（W78-10 已答）
同题面同设备同设置：TTFT **9.8s vs 462.4s（47 倍）**，差异只在是否落在冻结窗口内。续期与解冻**同一毫秒**触发（差 4ms/9ms/8ms）⇒ `delay(120s)` 冻结期间不推进、解冻立即补发。
⇒ W77 判据是「journal 有 settled」未记 TTFT，故慢 47 倍也显示「正常结束」。
**方法论沉淀：以「最终结算/成功」为判据的验证，必须同时记录时延。**

### 4. D9 续期的正确定位
机制本身**完全正常**（120006ms 准时、无条件 re-acquire 有效、30min 上限 break 留痕、解冻后立即补锁并持续 HELD）。但**不能对抗 OEM 冻结** —— 冻结期间协程不执行。
⇒ 定位 = 「**冻结窗口外的 CPU 保持 + 解冻后立即补锁**」。对用户的真实意义：**灭屏 7 分钟回来那一下是快的**，不是「灭屏期间一直在跑」。

## 三、真机取证手法（可复用）

- **审批挂起是续期真机取证的唯一加速器**：普通 run 仅 1–21s（引擎热），等不到 120s 续期。`file_write` 是 `dangerous + requiresConfirmation` 双标，模型调它后 run **无限期挂起** ⇒ FGS 长活。用完必须点「拒绝」收尾，否则审批框挡住输入框。
- **输入校验必须防「读到消息气泡」**：首跑四臂实验因校验逻辑 grep 到历史消息文本而全部假通过。修法 = 每臂 `am force-stop` 冷启（输入框与历史都干净）后 `grep -qF "$期望题面" dump.xml`。
- 脚本族：`_ci-tools/_w78_{dev,grace,timeline,notif_timeline,bg_timeline,bg_dump,shade,lockline,lockstate,renew,v8,ctl,repro,repro_analyze}.sh`。**权威锁态用 `_w78_lockstate.sh`**（ACQ/REL 历史），段位脚本已废。

## 四、遗留与开放（详见台账）

- **W78-09**（**已关闭**）：ColorOS 三开关「允许唤醒前台 / 自启动 / 关联启动」是否与冻结豁免构成组合条件 —— 2026-10-22 晚实测四开关全开仍被 `freeze uid: 10310`（scene 含 `LcdOff`）⇒ **组合不构成豁免**，已并入 W78-08 上限判据（详见 §七 与 `docs/15` W78-09 closed-done；复审 34 P3-2 订正本节旧状态）。
- **W78-05**：续期销账判据已按「间歇窗口」口径修订，仍待 ≥2min 长 run 场景。
- **W78-01~04**：9 族 OEM 真机取证 / FLYME 加锁 / OriginOS 冻结层 / 小米 PowerKeeper 探针，均 parked 待设备。
- **W78-06**：keeper JVM 单测（`RunWakeLockKeeper(null)` 可构造，四条含 scope 顺序坑）。
- **W78-07**：守卫 #47（Service 字段期禁 `getSystemService`），必须成对配 case81 + **case81b（onCreate 形态必绿，81b 比 81 更重要）**；selftest 下一空闲 case = **81**。
- **UI 波 U1–U5** 仍待用户裁决。

## 五、⚠️ keeper KDoc 压缩说明（防下一波误读）

`4377495` 为把 keeper 从 117 压回 110（硬上限），删了五句细节：`Class.newInstance` / `RuntimeException: ` 前缀 / 「连续错过 2 次」/「全仓无第二处 acquire」/「静默自释」。**核心判据一条未丢**，完整上下文在 `docs/w78-bg-run-design.md` §2.4（keeper 行为规格）与 §六（R15 / P0 记录）。
**分工原则**：注释 = 代码能自证的最小事实；设计文档 = 判据与上下文的家。不要为「补全注释」去动一个已过守卫的文件。

## 六、流程教训（本波实摔）

1. 🔴 **子代理汇报的 commit hash 必须 `git cat-file -t <hash>` 验存**。本波出现**三次**假 hash / 假完成：solution-strategist-2 两次声称完成实为零写入（四次 Write 漏传 `content` 参数）；android-developer-2 汇报 `c76c0d3`（不存在）且 keeper 行数虚报 110 实为 117。
2. 🔴 **`git status --short` 的输出可能被沙箱截断而显示为「干净」** —— 我曾据此误判「无未提交改动」，实际两个文件是 modified。必须用完整 `git status` 复核。
3. 🔴 **台账行数必须随每次改动复核**。C6 把 keeper 加到 117，开发席如实报 116，但方案席 r6/r9 与主理人沿用 C5 旧值 110 ⇒ 台账失真（非实现超标）。教训：**行数是快照，不是常量**。
4. **三层闸门全绿 ≠ 服务能被创建**（C6 P0）：编译不执行、单测不构造 Service、lint 无生命周期时机规则。框架实例化组件在字段初始化期碰系统服务，必须真机冷启验证。
5. **「纯搬迁」的判据是执行时机等价，不是代码行等价**（C5→C6）：代码逐字等价，但执行时机从 `onCreate` 前移到字段初始化 ⇒ 崩溃。

## 七、W78-12 跟进：冻结感知生成超时（再测试 + 改进，2026-10-22 收尾）

> 交付提交 `ee0df65`（主体）+ `12bef26`（跟进改进）；台账 **W78-09 → closed-done**、**W78-12 → closed-done**（均见 `docs/15-backlog.md`）。本项**不防冻结**（W78-08 硬上限不可破），只保「冻结期间不消耗超时预算 ⇒ 解冻后生成继续、不误掐」。

### 1. 根因（用户要的「思考 / 首字延迟时锁屏不断线」）
原 `withTimeout(generationTimeoutMillis)` 的截止时刻由 OS 单调钟决定。ColorOS 灭屏 ~10s 后 cgroup 级冻结线程全停、但单调钟照走；长锁屏解冻瞬间「墙钟流逝 > 预算」→ 误取消 run → 用户感知「断线」。W78-08 已坐实冻结期间协程不执行、只拉长 TTFT（47 倍），故正确语义应是「超时预算剔除冻结时段」。

### 2. 机制落位（双模块分层）
- **`core-agent`（平台无关）**：`FreezeClock` 接口（`nowNanos()` / `runnableElapsedNanos(startedNanos)`）+ `MonotonicFreezeClock`。`AgentRunner.freezeAwareTimeout(budgetMs, clock, block)` 用 `coroutineScope` 起看门狗 `launch`，每 `FREEZE_AWARE_WATCHDOG_STEP_MS` 查 `clock.runnableElapsedNanos(startedNanos) >= budgetNanos` → `scopeJob.cancel(GenerationTimeoutCancelledException(...))`（与 kotlinx `withTimeout` 同款取消传播，避免子协程 `throw` 被 `block` 自身 `delay` 取消异常覆盖、外层收不到类型的坑）。自定义 `GenerationTimeoutCancelledException` 复用原「超时→重建引擎 + 重试一次」链路。
- **`core-data`（Android 实现）**：`ScreenStateMonitor(context): FreezeClock` 承接 `FreezeClock`，进程级单例不注销（与 `RunWakeLockKeeper` / `ThermalGovernor` 同口径）。监听 `ACTION_SCREEN_ON/OFF`，灭屏记 `offAtNanos`、亮屏把 `(now-off)` 累加进 `screenOffAccumNanos`；`runnableElapsedNanos = nowNanos() - startedNanos - screenOffAccumNanos`。**灭屏整段算冻结是安全的过近似**（只让计时更宽松，绝不会误掐在跑生成；与 W78-05「续期不能对抗冻结」结论一致）。`AppContainer` 在 `LiquidAgentApplication.onCreate`（主线程，有 Looper）构造 ⇒ `registerReceiver` 无 C6 式崩溃风险。

### 3. 跟进改进 `12bef26`（再测试发现的两条风险）
- **冷启灭屏态**：进程在「屏幕已灭」时被冻结杀死后重启，`offAtNanos` 默认 -1 致首段冻结被误算可运行。构造时读 `PowerManager.isInteractive`，若 false 则 `offAtNanos.set(System.nanoTime())` 起算首段。
- **注册 API 升级**：API 33+ 必须显式 `RECEIVER_NOT_EXPORTED`。改用 `ContextCompat.registerReceiver(ctx, receiver, filter, RECEIVER_NOT_EXPORTED)` 跨版本安全，替代旧 `context.registerReceiver(receiver, filter)` + `@Suppress("UnspecifiedRegisterReceiverFlag")`。进程内接收、不被外部唤起，语义不变。

### 4. 验证（本地三闸门全绿）
- **单测**：`FreezeAwareTimeoutTest` **3/3**（monotonicClockFiresWhenBudgetExceeded / completesWhenWithinBudget / frozenIntervalDoesNotConsumeBudget）—— 证明超时剔除冻结时段、预算内不误杀、冻结视角恒 0 不超时（首字延迟/思考期间锁屏存活）。
- **编译**：`:core-agent:testDebugUnitTest` 全量 + `:app:assembleDebug` BUILD SUCCESSFUL；`:app:lintDebug` 无新增。
- **守卫**：arch-guard **46/46** 全过（含 `@Test`=767、`background/` 包 974≤1000、单文件 `ScreenStateMonitor` 75≤300）；`build.yml` baseline 764→767 同步并写变更注释。

### 5. 真机复验（设备 `13309cc8` / OPPO PDRM00 / Android 13 / ColorOS）
用 **FQCN** `com.rickeal.agent.debug/com.rickeal.agent.MainActivity` 启动（debug `applicationId=com.rickeal.agent.debug` 与源码包 `com.rickeal.agent` 错位，直接写 `...debug/.MainActivity` 报 Error type 3 不存在）。结果：进程 `com.rickeal.agent.debug` pid 30529 **存活**（证明主线程 `registerReceiver` 无 C6 式崩溃）；屏幕熄灭后 logcat 出现 `LiquidAgent/Freeze: screen OFF — freeze window started` ⇒ `ScreenStateMonitor` 真机收到广播、冻结窗口计时接通。这是「三层闸门全绿但真机崩」未知面的排除。

### 6. 推送状态（环境限制，待用户）
本环境无 GitHub 凭据（`git push --dry-run` 报 `could not read Username for 'https://github.com': terminal prompts disabled`）。两提交留在本地分支 `UI-change-1019`，需用户给 PAT 或手动 `git push origin UI-change-1019` 触发 CI（run 318/319/320 之后续跑）。
