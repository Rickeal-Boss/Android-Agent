# W79 core-fix 波设计（方案席 / 沈思远）

基线 `core-fix` = `9d7c2e6`。只做决策，不含实现。事实均回源码核验并标行号；无依据者标 **未证**。
**引用约定**：§1.1 与 §3 的行号是**改动前**（`9d7c2e6`）位置——它们描述「旧值 / 旧杠杆」，本就该按旧文件读；§1.2/§1.3/§1.5 已按**落地后现状**校准（`AgentRunner.kt` +6、`LiteRtLmEngineLoader.kt` +13、`ScreenStateMonitor.kt` 重写）。`build.yml` 的测试基线按 **token** `baseline=` 引用，不按行号（该行号每波插沿革都会漂）。**机械事实（行数 / 用例数 / 是否含某串）一律附可复现命令，且断言形态用「状态 / 上下界」，不用「等于某数」**——数值会漂、状态不会；下表的实测值是 W79 收尾快照，复现命令见各自条目。

## 0. 决策表
| 线 | 决策 | 理由 |
|---|---|---|
| A（P1） | `FreezeClock` 增 `frozenNanos()`（绝对累计）+ `freezeAwareTimeout` 起点打快照；**删**单参 `runnableElapsedNanos` | 累计量是「绝对事实」、基线是「本次 run 的局部变量」——历史与本次 run 在类型层面分离 |
| A（口径） | `frozenNanos()` **必须含在途灭屏窗口（live 累加）** | 否则 `now-started` 含该段而 frozen 不含 ⇒ 解冻瞬间误掐；起点落在灭屏中会跨 off→on 跳变 |
| A（测试） | `FreezeAccumulator` 落 `FreezeClock.kt` 内（**不新建文件**）+ 6 例 | 累加逻辑是唯一有竞态/跨窗口语义处，必须 JVM 可测；不新建文件 = 不欠「新文件同波补守」 |
| B（#1326） | 只做 **①失败诊断留档**；**不做** ② | 本仓已在降级链第 3 次尝试做纯文本 GPU 初始化（§2.1-2） |
| C（圆角） | 五 token 各 ≈−15%，**外加** `GlassCard` 默认 28dp → `GlassDefaults.RadiusLg`(=24) | 只改 token 对「未传 `cornerRadius`」的 28~30/40 个调用点**零效果**（默认值硬编码 28dp） |
| C（间距） | **新增 `cardGap = 14.dp`**，11 个卡片列表逐个替换；**不动 `gapSm` 值** | `gapSm` 34 个消费点里 31 个是卡内 `Spacer`/`padding`，改值=把卡内行距一起放大 |

## 1. A 线：冻结时钟跨 run 污染（P1）
**1.1 缺陷复核**（与主理人一致）：`screenOffAccumNanos`（`ScreenStateMonitor.kt:30`）进程级累计、永不清零；`runnableElapsedNanos`（`:69-70`）= `now - started - accum`，`accum` 含 `started` **之前**的灭屏 ⇒ 历史冻结被从本轮预算扣掉。

**1.2 契约**（`FreezeClock.kt` 的 `interface FreezeClock`，落地 `:22-46`）
```kotlin
interface FreezeClock {
    fun nowNanos(): Long
    /** 进程生命周期内累计冻结纳秒；单调不减、永不清零。默认 0 = 无冻结感知。 */
    fun frozenNanos(): Long = 0L
    /** 自 startedNanos 起、剔除冻结后的可运行时长。baseline 必须由调用方在起点取一次。 */
    fun runnableNanosSince(startedNanos: Long, frozenBaselineNanos: Long): Long =
        (nowNanos() - startedNanos) - (frozenNanos() - frozenBaselineNanos)
}
```
- **删除** `runnableElapsedNanos(startedNanos)`（唯一生产消费者 = `AgentRunner.kt` 的 `runnableNanosSince(...) >= budgetNanos` 判据，落地 `:239`；另 2 个测试 fake）：保留它 = 保留一个「语义必然错误」的单参入口，即本缺陷的复发通道。
- **实现方兼容**：只覆写 `nowNanos()` 的实现（`MonotonicFreezeClock`，落地 `:48`）零改动仍正确（默认 0），成本 = 一次常量减法（**不是** JIT 级零成本，如实申报）。
- 🔴 **禁止**给 `frozenBaselineNanos` 默认值：默认值只能调用时现取 ⇒ 每轮 delta 恒 0 ⇒ 冻结剔除**静默失效**（灭屏 416s 后解冻立即取消），比原缺陷更糟。签名缺席即契约（同 `isSameEngine` 的 `nativeToolChannel` 手法，`LiteRtLmEngineLoader.kt:674-677`）。

**1.3 改动**（`AgentRunner.kt` 的 `freezeAwareTimeout`，落地 `:233` 起点快照 / `:234` 基线 / `:239` 判据）：`:233` 后插 `val frozenBaselineNanos = clock.frozenNanos()`；`:239` 判据改 `clock.runnableNanosSince(startedNanos, frozenBaselineNanos) >= budgetNanos`。净 +2 行。

**1.4 公式与边界**：`runnable(t) = (now(t) − started) − (frozen(t) − frozenAtStart)`

| 场景 | 行为 | 结论 |
|---|---|---|
| 起点前已累计 416s | `frozenAtStart=416s` ⇒ delta 从 0 起算 | 历史被排除，300s 预算完整 ✅ |
| 运行中灭屏 120s 后亮屏 | `frozen` 增 120s ⇒ runnable 暂停 120s | 预算顺延 ✅ |
| 起点恰在灭屏中，随后亮屏（frozen 含在途窗口） | `frozenAtStart` 已含 `start−offAt`；off→on 时该段转入 accum、在途项消失 ⇒ **值连续** | 无跳变 ✅ |
| 同上但 frozen **不含**在途窗口 | 亮屏瞬间 delta 一次性跳 `on−offAt`（含起点前那段） | 过减 ⇒ 误掐 ❌ 故必须 live |
| 进程重启 | 两量同时归零 | 自洽 ✅ |
| 时钟回绕 / `frozen` 意外回退 | 回绕不处理（Long 纳秒≈292 年）；回退使 delta 变负 ⇒ 只延后不误掐 | 失效方向安全 ✅ |

**1.5 `ScreenStateMonitor` 实现（含竞态）**：`frozenNanos() = accum + (offAt >= 0 ? now − offAt : 0)`（落地：`accumulator` 字段 `:35`、接收器 `:40-47`、`init` 冷启灭屏态 `:52-58`、`frozenNanos` 覆写 `:69`）；接收器累加移入 `FreezeClock.kt` 的 `FreezeAccumulator`（落地 `:66`；`frozenNanos` 的 `synchronized` 在 `:101`，`offAtNanos` 与 `accumNanos` 同锁读），Monitor 退化为 Android 适配器。
🔴 **重复 off 幂等 —— 保留最早起点**（落地 `:79-81`，`if (offAtNanos < 0)`）：连续两次 `SCREEN_OFF`、或冷启已置起点后又来一条 off 广播时，若把起点**后移**，冻结时长**少计** ⇒ `runnable` 偏大 ⇒ **误掐**；保留最早 = **多计只放宽**。故方向必须取「保留最早」——与 §1.4「失效方向安全」同一判据。
🔴 **双读竞态**：`offAt.get()` 与 `accum.get()` 之间若亮屏广播落地 ⇒ **双计**整段灭屏 ⇒ 过减 ⇒ 误掐，且该竞态恰好与「解冻瞬间看门狗正在读」时间相关。必须 `synchronized(lock)` 包住读与写（接收器同锁），或「读 off→读 accum→复核 off 未变」双读校验。**不接受「概率低」**——失效方向是误掐。

**1.6 单测**（core-agent JVM；`core-data` 依赖 Android 不可测 ⇒ 累加逻辑必须上提）。`FreezeAwareTimeoutTest` **3→6 例**（既有 3 例全保留、其中第 6 条语义改写；新增 3 例）+ 新建 `FreezeAccumulatorTest` **3 例** ⇒ 全仓 `@Test` **净 +6 = 773**（既有 3 + 新增 3 + 新文件 3 = 9 例）：
1. `historicalFreezeDoesNotConsumeThisRunBudget`（**新增**）：`frozen` 恒 416e9、`now`=真实；budget 150ms/`delay(2000)` ⇒ 抛 `GenerationTimeoutCancelledException`。**旧实现此例「不超时」（runnable 恒负）⇒ 红转绿，本 P1 的判据锚。**
2. `midRunFreezeExtendsBudget`（**新增**）：`frozen = max(0, 自构造流逝 − 100ms)`；budget 150ms/`delay(600)` ⇒ 返回 `"survived"`（runnable 饱和 ~100ms）。
3. `midRunFreezeIsNotUnlimitedExemption`（**新增**）：同 fake，budget 80ms/`delay(600)` ⇒ 超时。
4. `monotonicClockFiresWhenBudgetExceeded`（**既有，逐字保留**）：`frozen = 0`；budget 150/`delay(2000)` ⇒ 超时。
5. `completesWhenWithinBudget`（**既有，逐字保留**）：`frozen = 0`；budget 5000/`delay(100)` ⇒ 返回 `"ok"`。
6. `frozenIntervalDoesNotConsumeBudget`（**既有，语义改写**）：原实现覆写 `runnableElapsedNanos()=0`（入口已删）⇒ 改为 `frozen` = 自构造真实流逝；budget 150/`delay(2000)` ⇒ 完成。

`FreezeAccumulatorTest`（**新建文件**，3 例，`now` 注入）：灭屏 200ms→亮屏后 `frozen` 恰 +200ms；起点落在灭屏窗口内（off 后 100ms 取 baseline、再 100ms 亮屏）⇒ 增量恰 100ms（**不含起点前那段**）；未亮屏期间两次读严格递增。
（主理人裁定：**不设** `>=` 边界用例 —— 生产时钟持续推进时 `>` 只推迟一个 tick（250ms，`AgentRunner.kt:198`），不存在「永不触发」的失效形态；用冻结时钟去钉比较符属过度规格化。）
**残留风险（登记，不修）**：`AgentRunner.kt:239` 的 `>=` 方向**无判据锚** —— 若将来有人改回 `>`，现有 773 例**全绿**。危害已评估为**低危**：生产时钟下仅使超时判定延后 ≤1 tick（250ms），不改变任何正确性不变量（「历史不污染」由第 1 例锚、「顺延非无限」由第 3 例锚）。本波按裁定不补例，此条留档待复审。
⚠️ **本记录的保护等级 = 「靠人读」**：`build.yml` 的 `paths-ignore` 含 `docs/**` 与 `**/*.md` ⇒ 只改本文档的提交 **0 run**，此条永远不会被任何 CI 步骤读到、也不会因失配报警（与守 #27 KDoc 自陈的同类盲区一致，`arch-guard.sh:599`）。

**1.7 闸门预算**（「落地实测」列已按 W79 实况回填，非预计值）
| 文件 | 现 → 落地实测 | 上限 |
|---|---|---|
| `core-agent/.../FreezeClock.kt` | 28 → **99**（+`frozenNanos`/`runnableNanosSince`/`FreezeAccumulator`；预算 68~75 估低，KDoc 占大头） | 无单文件闸门 |
| `core-data/.../ScreenStateMonitor.kt` | 75 → **74**（累加逻辑上提 −，`frozenNanos` 覆写 +KDoc 相抵 ⇒ 仅减 1 行，预算 62~66 估高） | 单文件 ≤300 ✅ |
| `background/` 合计 | 974 → **977**（keeper KDoc +4 / Monitor −1，净 +3；原预算「净减 954~960」方向错）⇒ **余量仅 23 行** | ≤1000 ✅ |
| `core-agent/.../AgentRunner.kt` | 2815 → **2823**（净 +8 = 判据重写 + KDoc + docs 指针 2 行）；在位判据 `grep -c "w79-core-fix-design"` = 1 | ≤2900（余 77）✅ |
| `FreezeAwareTimeoutTest.kt` / 新建 `FreezeAccumulatorTest.kt` | 63 → **121** / 0 → **72** | — |
| `@Test` 基线 | **不变量：`decl == 守 #26 实测代码位实数`**（本波实测 773 = 767 + 6）；引用走 `baseline=` token，**勿钉死数值**（下波增删即漂） | 守卫 #26 双向同步 |

**下波预算提示**（非本波结论，实测于 W79 收尾）：#25 家族当前最紧的不是 #31，而是 **#20 `ChatRunCoordinator.kt` 1253/1300（仅余 47 行）**——本波未碰它，但 W80 若在聊天侧加逻辑会先撞 #20。其余宽松：#31 2823/2900（余 77）、#32 717/900、#18 739/1600、#25 2009/2400。
**证据边界（勿把 773 当「全量绿」）**：`773` 是**代码位**口径（守 #26 判据命令实跑）。CI 的 XML 汇总当前亦为 773，但属**增量拼接**——仅 `core-agent`（22 文件/234 例）为 10-23 新跑，`core-data` 为 10-22，其余 7 模块仍是 10-20 ⇒ **不可据此宣称「9 模块同刻全量绿」**；真权威 XML 数须等推送后 CI `unit-tests` job summary。
**另：第 45 条守卫自身两个缺口**（本波不改，W80 治理批；改 `arch-guard.sh` 须同跑 selftest，不在审查前动）——① **扫描面非递归**：`wc -l "$D"/*.kt`（`arch-guard.sh:1176` 合计 / `:1182` 单文件循环）只数 `background/` 直接子文件 ⇒ 子包 `background/*/Foo.kt` 既不计合计也不进单文件检查（当前 0 子包 ⇒ 本波零风险；整包搬空会命中 fail-closed 判红，故**静默缺口仅限「部分文件进子包」**这一现实场景）。② **KDoc 推导数过期**：仍写「6 文件 899 行 / 余量 101」，实测 7 文件 977 行 / 余 23（KDoc 自身已声明「行数是移动值、勿写死进判据」⇒ 属文档时效，非判据错误，但下一个读的人会以为还有 101 行）。

## 2. B 线：issue #1326（**不升依赖、不搬上游代码**）
**2.1 源码核验**
1. **存在「默认启用多模态」路径**：`ModelHeuristics.kt:136-144` 对 `GEMMA_4` 家族硬写 `image=true, audio=true`（非容器探测）；`LiteRtLmEngineLoader.kt:309-310` 据此取 `wantsVision/wantsAudio`，`:323-331` 解析，`:475-482` 写进 `EngineConfig.visionBackend/audioBackend` ⇒ 对仅含 artisan 段的 GPU 容器，**首轮 EngineConfig 必带 vision+audio**，这是「为何不走 Artisan 路由」在本仓侧的第一嫌疑（**未证**：无真机 0.17.1/0.18.0 对照）。
2. **纯文本 GPU 初始化本仓已在做**：`EngineLoadDegrade.next`（`:193-207`）对 `NOT_FOUND` 走 `dropOneModality`（`:161-175`，先 AUDIO 后 VISION），`loadLocked` 驱动循环（`:474-573`）最多 3 次即达 `(GPU, vision=null, audio=null)` =「纯文本 + GPU」⇒ **候选②已存在，不是新工作**。终态仍失败（`ModelPresets.kt:405/447` W60 记录）⇒ artisan 路由在 0.17.1 下**可能**确实不可达（未证）。
3. **失败现场无留档**：`next == null` 的 `break` 发生在留档块（`:558-570`）**之前** ⇒ 最后一次尝试的 `EngineConfig` 与原始 message 不落日志；终态异常（`:574-604`，落地后）只在命中 `GPU_FAILURE_FEATURES`（`:211`：dlopen/OpenCL/INTERNAL/CompiledModel/ClGl）时补文案，而 `NOT_FOUND (TF_LITE_PREFILL_DECODE not found)` **不命中** ⇒ 拿不到「第几次尝试、带哪些模态」。这正是维护者要的 exact failing EngineConfig。

**2.2 决策**
- **做 ①**：终态失败处（`LiteRtLmEngineLoader.kt:574` 前）追加 `AgentLogStore.warn`：`attempt=${attemptIndex}`、`backend`、`visionBackend`、`audioBackend`、`contextLength`、`degraded`、容器 `fileName`+`sizeBytes`、原始 message（`take(200)`）。**只陈述事实、不做归因翻译**（维护者明确否决「通用 NOT_FOUND ⇒ GPU 不兼容」helper，`1326.md:303`）。落地 +13 行（704 → 717，闸门 #32 ≤900 ✅）。
- **不做 ②**：已被 §2.1-2 覆盖；再加「knownBroken ⇒ 强制 text-only」只会让 `knownBroken` 与降级事实分叉。若仍要动，唯一合法形态是把「已知容器缺段」写成预设的声明式事实（台账活，不在本波）。
- **不动 knownBroken**：`ModelPresets.kt:405/447` 原样；守卫 #34（`arch-guard.sh:819`）与 `appliesToRuntime == litertlm 版本`（`:732`）不得让步。判据纪律：本波**不新增任何**以 `NOT_FOUND` 为触发条件的自动降级/清空动作 ⇒「0 命中」不构成任何通过结论，失败面仍是「加载失败 + 一条事实日志」。文案/文档（候选③）本波不做（`ModelPresets.note` 已写清）。

## 3. C 线：圆角收敛 + 卡片间距
**3.1 圆角**（两套必须同步，否则同屏两种曲率）：`GlassTokens`（`GlassTokens.kt:13-18`）→ **7 / 12 / 16 / 24 / 30**（Xs/Sm/Md/Lg/Xl，`radiusFull` 不动）：8→7（−12.5%）、14→12（−14.3%）、20→16（−20%）、28→24（−14.3%）、36→30（−16.7%）。`radiusXs` 当前**零消费者**（全仓 grep `tokens.radiusXs` 0 命中），改它纯属对齐阶梯。

🔴 **真正的杠杆不是 token**：`GlassCard` 默认值硬编码 `28.dp`（`GlassSurface.kt:173`）；40 个 `GlassCard(` 调用点中仅 **12 处显式传 `cornerRadius`**（8 行窗口机械计数，为上界）⇒ 只改 token，其余 28~30 处纹丝不动。必须同时：① `GlassSurface.kt:173` `28.dp` → `GlassDefaults.RadiusLg`，`:164` KDoc「28dp 圆角」同步；② `GlassTokens.kt:56` `GlassDefaults.RadiusLg = 28.dp` → `24.dp`（与 `radiusLg` 是**两个独立字面量**，必须同改；消费者 `GlassSurface.kt:79`、`GlassButton.kt:64`、`LiquidGlassModifier.kt:105`）。证据：`docs/01-architecture.md:4414` 早已把 `GlassCard` 默认写成 **`24.dp`**，`:4409` 注释仍写 `radiusLg`（文档自相矛盾）；`git log -S "cornerRadius: Dp = 24.dp"` 显示 `47cd5b1`（「其余组件改胶囊」）从 24 抬到 28 ⇒ 本波回 24 = 回文档口径。**W79 主理人裁定：接受「卡片回 24dp」，不再继续下压 token。**
**落地核验（W79 实测）**：`GlassTokens.kt` 已为 7/12/16/24/30（`:13-17`）、`cardGap=14`（`:53`）、`GlassDefaults.RadiusLg=24`（`:71`，且已加「与 `radiusLg` 是两个独立字面量，改一处必须同改」警告）、`GlassCard` 默认改指 `GlassDefaults.RadiusLg`（`:177`）、`LiquidShapes` 已为 7/12/16/24/48（`LiquidAgentTheme.kt:31-36`）；`tokens.cardGap` 消费点实测 13 处（= 下表 11 行中两行各含 2 处）。

`LiquidShapes`（`LiquidAgentTheme.kt:27-33`）：`extraSmall 8→7`、`small 12` 不变、`medium 16` 不变、`large 28→24`、`extraLarge 48` **不动**（无消费者）。两套互不相通：M3 侧 = `MaterialTheme(shapes=)`（`:161`）隐式供给 `Snackbar`/`ModalBottomSheet`；自绘侧 = `LocalGlassTokens`。全仓 `MaterialTheme.shapes` 显式读 **0 命中**（已 grep）⇒ M3 侧只需对齐阶梯。`LiquidAgentApp.kt:1140` 的 `28.dp` 是竖向 padding，**不是圆角，勿改**。

**3.2 间距：新增 `cardGap = 14.dp`（不改 `gapSm` 值）**：`gapSm`（8dp）34 个消费点中仅 3 个是卡片↔卡片，其余 31 个是卡内 `Spacer(height/width = tokens.gapSm)` 与 `padding(top = tokens.gapSm)`（`ChatScreen.kt:320/332/342/352/464/501/566/599`、`ModelsScreen.kt:350/448/465/598/608/635/680/690/835/843`、`RecommendedModelDialog.kt:87/109/144/156/162`、`LegalScreen.kt:179/187/212`、`StorageScreen.kt:236/256/309`）⇒ 8 改 12 会**同时放大卡内行距**，与需求直接冲突。`gapLg`（20dp）零消费者。

**3.3 改动清单**（file:line → 旧 → 新；全部卡片↔卡片，不动卡内）
| 文件:行 | 旧 | 新 | 备注 |
|---|---|---|---|
| `feature-chat/.../ChatScreen.kt:302` | `gapSm` 8 | `cardGap` 14 | 状态卡栈（5~6 张）⇒ +6dp/张；**裁定：统一 14dp**，真机若挤压输入区再单独降 12dp 并注明「此处例外」 |
| `feature-chat/.../ChatMessageList.kt:155` | `12.dp` | `cardGap` | 需新增 `val tokens = LocalGlassTokens.current`（0 处） |
| `feature-chat/.../ChatParamsPanel.kt:70` | `12.dp` | `cardGap` | 需新增 tokens 取值（0 处） |
| `feature-settings/.../SettingsScreen.kt:136` | `12.dp` | `cardGap` | tokens 已在作用域 |
| `feature-settings/.../DiagnosticsScreen.kt:182` | `10.dp` | `cardGap` | 同上 |
| `feature-settings/.../storage/StorageScreen.kt:105` | `10.dp` | `cardGap` | 同上 |
| `feature-settings/.../memory/MemoryScreen.kt:99` | `10.dp` | `cardGap` | 需新增 tokens 取值（0 处，`:91` Column 前） |
| `feature-settings/.../LegalScreen.kt:109` | `12.dp` | `cardGap` | tokens 已在作用域 |
| `feature-settings/.../tools/ToolsScreen.kt:119` | `10.dp` | `cardGap` | 同上 |
| `feature-settings/.../tools/SandboxFilesScreen.kt:267` | `10.dp` | `cardGap` | 需新增 tokens 取值（0 处） |
| `feature-models/.../ModelsScreen.kt:167,168` | `12.dp` ×2 | `cardGap` ×2 | 模型卡双列网格（纵+横），卡片宽 −2dp |
| `feature-models/.../ModelsScreen.kt:756` | `gapSm` | **不动** | 对话框内密集选择行（`heightIn(max=440)`），非卡片 |

`MemoryScreen.kt:282`（10dp）是卡内子块**不动**；`ChatParamsPanel.kt:116/166`、`ChatMessageList.kt:186/273` 同理不动。

**3.4 测试/文档风险**：dp 数值断言——`grep -rn "radius|gapSm|spacedBy" --glob "**/src/test/**/*.kt"` 与 `**/src/androidTest/**` 均 **0 命中**（机械实数，非「没看到」）⇒ 无快照断言会被打破；**本波不建议新增 dp 断言**（视觉值不该被单测钉死）。文档漂移须同步（无守卫兜底）：`docs/01-architecture.md:3896-3920`（token 快照逐字复制）、`:4409`、`:4626-4631`（材质/圆角表）。视觉回归不可自动化 ⇒ 交付附「改前/改后同页截图」两张（设置页 + 模型页）作为唯一证据。

## 4. 最容易被实现席做错的点（按危害排序）
1. **A**：`frozenNanos()` 不含在途灭屏窗口 ⇒ 解冻瞬间误掐 + 起点在灭屏中时跳变。
2. **A**：给 `frozenBaselineNanos` 加默认值 ⇒ 冻结剔除静默失效（比原缺陷更糟）。
3. **A**：`offAt`/`accum` 双读不加锁 ⇒ 亮屏瞬间双计 ⇒ 过减 ⇒ 误掐。
4. **C**：只改 `GlassTokens`，漏 `GlassSurface.kt:173` 硬编码 28dp 与 `GlassDefaults.RadiusLg` ⇒ 近 30 张卡片无变化。
5. **C**：直接改 `gapSm` 值 ⇒ 31 个卡内 Spacer 一起变（与需求相反）。
6. **C**：把 `LiquidShapes.extraLarge 48` 也压到 30 ⇒ 无消费者，纯制造与 M3E 声明冲突。
7. **A**：忘同步 `build.yml` 的 `baseline=` token ⇒ 守卫 #26 判红（双向同步，非单向）。
8. **B**：把 ① 做成「通用 NOT_FOUND ⇒ GPU 不兼容」文案翻译 ⇒ 维护者已明确否决（`1326.md:303`）。
9. **B**：以「降级重试成功」为由清空 `knownBrokenOn` ⇒ 绕过守卫 #34 / `appliesToRuntime` 锚。

## 5. 设备可行性
- A：ColorOS/HyperOS 灭屏约 10s 后 cgroup 冻结、单调钟照走（`FreezeClock.kt:6-12` 同口径）。真机判据：单次冻结 416s 场景下本轮 run 仍须在 300s（可运行口径）处超时。**待真机验证**（本波只落 JVM 判据）。
- C：纯 Compose 令牌改动，无 OEM 差异；`ChatScreen.kt:302` 状态卡栈在 A13（720×1600）可能挤压输入区 ⇒ 真机点验。
- B：需装有 `gemma-4-E2B-it-gpu.litertlm`（2,008,432,640 B）的真机才能取证；当前激活模型非该容器 ⇒ **未证**。
