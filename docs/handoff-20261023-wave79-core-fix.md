# W79 core-fix 波 — 交接（2026-10-23）

> 分支 `core-fix`（云端自 `UI-change-1019` tip `9d7c2e6` 新建），本波从 `9d7c2e6` 起。
> 设计权威 = `docs/w79-core-fix-design.md`（133 行）；挂账权威 = `docs/15-backlog.md`（W79-01~07）。
> 本波主题：**复审 34 的唯一 P1 清零 + issue #1326 处置 + UI 圆角/间距收敛 + core-fix 触发面**。

## 一、交付

| 提交 | 内容 |
|---|---|
| C1 `fix(core)` | **P1 冻结时钟跨 run 污染修复**：`FreezeClock` 拆 `frozenNanos()`（绝对累计，含在途灭屏窗口）+ `runnableNanosSince(started, baseline)`；`freezeAwareTimeout` 起点同取基线；累加逻辑上提 `core-agent.FreezeAccumulator`（offAt/accum 同锁、重复 off 保留最早起点）；删单参入口。**CI**：`build.yml` 加 `core-fix` 触发分支 + `baseline 767→773`。⚠️ keeper 的 P3-5「删 scope.cancel」**已回滚**（见 §三.4 / W79-08） |
| C2 `feat(ui)` | 圆角收敛：`GlassTokens` 7/12/16/24/30（≈−15%）、`GlassDefaults.RadiusLg 28→24`、`GlassCard` 默认值由硬编码 `28.dp` 改引用 `GlassDefaults.RadiusLg`（30 余处未传参调用点的真正杠杆）、`LiquidShapes` extraSmall 7 / large 24；新增 `cardGap=14.dp`，11 处卡片↔卡片列表替换（**不动 `gapSm`**） |
| C3 `feat(engine)` | **#1326 处置**：`LiteRtLmEngineLoader` 终态失败留档（attempt / backend / vision / audio / degraded / contextLength / container+size / message），只陈述事实、不做归因翻译；不升依赖、不改 `knownBroken` |
| C4 `docs(w79)` | 设计文档 + 本交接 + `docs/15` W79-01~08 + 复审 34 P3-1~4 文档订正 + `docs/01-architecture.md` 令牌快照 + README/docs/02 分支清单 |
| C5 `fix(w79)` | **审查修复**：回滚 keeper P3-5（1 P1 + 1 P2）、GlassTextField KDoc 36→30、docs/01 卡片 radiusMd→radiusLg、工具卡 18.dp→`tokens.radiusMd`、单测边界余量（饱和 100→200ms / 预算 150→300ms） |

**主理人直改（非成员产出）**：`AgentRunner.kt` 的 2 行 `>=` 判据说明注释（审查裁定项，方案席提出、主理人落盘；`grep -c w79-core-fix-design` = 1）。

## 二、判据与实测（全绿）

| 项 | 结果 |
|---|---|
| `FreezeAwareTimeoutTest` | **6/6**（XML `tests=6 failures=0 errors=0`），含红转绿锚「历史累计 416s 不占本轮预算」 |
| `FreezeAccumulatorTest` | **3/3**（`tests=3 failures=0`） |
| `:core-agent:testDebugUnitTest` | 全量绿（9 模块，CI 席 XML 汇总 **773**） |
| `:app:assembleDebug` / `:app:lintDebug` | 绿 / **0 新增**（baseline 3 errors + 1 hint 过滤） |
| `arch-guard` | **46/46 RC=0**（主理人 + CI 席 + 审查席各自复跑） |
| `@Test` 代码位 | **773** = `build.yml` baseline（严格口径，非推算） |
| `background/` 包 | **973/1000**（余 **27**；keeper 110 / Monitor 74 / 服务 221 / 其余不变） |
| `FreezeClock.kt` / `AgentRunner.kt` / loader | **105** / **2823**（#31 ≤2900）/ **717**（#32 ≤900） |
| 真机（OPPO PDRM00 / A13） | 冷启 pid 11692 存活，`logcat -b crash` 空、无 `Unable to create service`；设置页/模型页截图与改前逐像素 diff 确认圆角与卡片间距变化 |

## 二·五、审查结论（code-quality-reviewer，独立复核）

| 级别 | 发现 | 处置 |
|---|---|---|
| **P1** | keeper 被加到 114 行，踩过交接级硬上限 110（非守卫强制） | 整体回滚到 110（与 P2 同一动作） |
| **P2** | P3-5 修复空转：`startRenewal()` 只在 `onCreate` 调，二次 `onStartCommand` 只补 `acquire()` ⇒ scope 活不活都没人重启续期 | 同上；登记 W79-08 |
| P3 | `GlassTextField` KDoc 仍写 36dp | → 30dp |
| P3 | `docs/01` 组件表「卡片 / 列表项」写 radiusMd（实为 radiusLg） | 已订正 |
| P3 | `ChatMessageList` 工具卡 `18.dp` 是阶梯外字面量 | → `tokens.radiusMd` |
| P3 | 单测边界余量偏紧（tick 250ms / 饱和 100ms / 预算 150ms） | 饱和 200ms、预算 300ms |

审查席独立实测：`@Test` 773、`background/` 973、单文件最大 221、arch-guard 46/46 —— 与主理人实测一致。

## 三、关键决策

1. **P1 修法 = 复审 34 方向 1**：累计量是「绝对事实」、基线是「本次 run 的局部变量」；`frozenNanos()` **必须含在途灭屏窗口**（否则 off→on 跳变 ⇒ 过减 ⇒ 解冻瞬间误掐）；`frozenBaselineNanos` **禁止默认值**（默认值现取 ⇒ 冻结剔除静默失效）；`offAt/accum` 必须同锁（分读双计 ⇒ 过减 ⇒ 误掐）。
2. **#1326**：上游证据确认 GPU 容器只有 artisan 段、`NOT_FOUND` 是「请求 section 大小为 0」的查表失败，不是 GPU 缺算子；本仓降级链本就会走到「纯文本 + GPU」（`MAX_LOAD_ATTEMPTS=4`）⇒ **不再加自动降级**，只补失败现场留档。Artisan 路由可达性仍未证（需真机 + 0.17.1/0.18.0 对照）。
3. **UI**：圆角回 24 不是新审美 —— `docs/01-architecture.md` 早已写 `GlassCard` 默认 24dp（`47cd5b1` 才抬到 28）；本波是**回文档口径**。卡片间距用独立 `cardGap`，不改 `gapSm`（34 个消费点里 31 个是卡内 Spacer/padding）。
4. **P3-5 修复被两次判死（本波最重要的「不修」决策）**：初版按复审 34 删 `serviceScope.cancel()`。审查席先判「空转」（`startRenewal()` 只在 `onCreate` 调，二次 `onStartCommand` 只补 `acquire()` ⇒ scope 活不活都没人重启续期）。**主判据 = 收益恒为零**：全仓没有任何调用点在 `release()` 之后调 `startRenewal()`，删 `scope.cancel()` 对行为零影响，唯一效果是把 `w78-bg-run-design.md:499` 已归档的规范修法变成死代码。**辅判据（可达性）**：`release()` 仅两处（catch 降级分支 / `onDestroy`）；`stopSelf(startId)` 在新 `startService` 已递增 startId 时不生效 ⇒ 同实例复用**可达但极窄**（还需控制器在宽限窗内发 `startService`）—— **不写「结构上不存在」**。**整体回滚到 110 行**，只登记 W79-08。教训：**修一个当前不可达的缺陷前，先证明收益不为零**；否则修的是注释里的想象。

## 四、遗留（详见 docs/15）

- **W79-05**（parked）：借道竞态窗 1001 残挂（复审 34 P3-7）。
- **W79-06**（parked）：守卫 #45 KDoc 行数陈旧（写 899/101，实测 977/23）+ `background/*.kt` 枚举非递归。改守卫注释须跑全量 selftest（~52min），本波不付。
- **W79-07**（active）：`background/` 包余 23 行，下波加任何 OEM/冻结策略前先拆（候选：9 族文案表抽独立对象）。
- 沿袭：W77-01（A14+ 类型合规）/ W78-01~04（其他 OEM 取证）/ W78-05（≥2min 长 run）/ W78-06（keeper 单测）均等外部条件。

## 五、教训

1. 🔴 **行数预测不是行数**：设计 §1.7 预测 `background/` 954~960，实测 **977**（keeper KDoc +4、Monitor 仅 −1）。凡是预算表，落地后必须实测回写。
2. 🔴 **「谁落的盘」必须同步**：主理人直改 2 行注释后未通知，导致方案席/实现席/审查席三方都不认领。交付表必须区分「成员产出 / 主理人直改」。
3. **计数口径要写清「新增」还是「总数」**：方案席把「timeout 用例总数 6」当成「新增 6」，导致 773 一度被质疑；正确账 = 既有 3 + 新增 3（timeout）+ 新增 3（accumulator）= 9 ⇒ 767+6=773。
4. **上游 issue 的回复可以改写本地结论的性质**：#1326 把「容器缺段」从猜测变成 artifact 级实证，同时明确否决「通用 NOT_FOUND ⇒ GPU 不兼容」文案；这类回复应落成「诊断留档」而不是「用户可见归因」。
5. **`>=` 边界无用例是登记过的取舍**，不是遗漏：`>` 仅延后 ≤1 tick（250ms），补例需冻结时钟钉比较符 = 过度规格化（设计 §1.6 + 代码注释 + W79-01 三处留痕）。

---

*判据来源：主理人一手复跑（arch-guard 46/46、@Test 773、真机截图 diff）+ CI 席 XML 全量 773 + 审查席独立复核。*
