# W81 性能开销波第二轮 — 交接（2026-10-24）

> 分支 `core-fix`（续 W80 tip `e2e6830`）。
> 本波主题：**性能开销改进第二轮**——五份外审（复审 35 W79W80 联审 / w80-review / 深度审查 / 第二十审 W80 核验与 W81 路线 / core-fix 深度审查）汇总落地。
> 挂账权威 = `docs/15`（W81-01~12）；依据入仓 = `docs/w81-perf-evidence.md`；审批路由 = `docs/14` §C7/§D。

## 一、交付

| 提交 | 内容 |
|---|---|
| C1 `fix(agent)` | **W81-01 G-1 run 级墙钟熔断冻结感知**（深度审查最高优先）：`AgentRunnerLoopHelpers` 三纯函数（`effectiveElapsedMillis` / `wallClockRemainingMillis` / `childDeadlineNanos`）加 `frozenNanos` 参；`RunState` 加 `frozenBaselineNanos`（起点同刻取基线，**历史冻结不进本轮**——与 `freezeAwareTimeout` 同款纪律）+ `frozenSinceRunStartNanos()`；`gateRoundHead` 接线（nowNanos 先读、frozenNanos 后读 = 冻结略过计的安全方向）；spawn 点父冻结只计一次。修 W78-12「生成不被掐」的**半截修复**——解冻后下一个轮头 remaining ≤ 0 ⇒ HARD 熔断（多轮工具调用 run 锁屏必死；单轮无第二个轮头故此前不可见） |
| C2 `perf(data)` | **W81-02 零依赖帧统计采集器**（深度审查 P0-1 的 07a 档）：新增 `FrameStatsCollector`（Choreographer.FrameCallback **主线程纪律**、120 帧滑窗、delta > 20ms 记 janky、`FLAG_DEBUGGABLE` 门控 release 零行为、发布限 4Hz）；AppContainer 装配；诊断页「帧统计」卡（仅 debug 显示）。**不引 JankStats**：JankStats 底层即 Choreographer 封装，滚动/生成期掉帧率只需 ~30 行零依赖采集 ⇒ 不触发版本锁死纪律 |
| C3 `chore(ci)` | **W81-03/04 双守卫**：arch-guard **#47** stability.conf 承重墙（conf 存在+非空 fail-closed / FQN 可解析防陈旧 / 类无 var 属性）+ **#48** ConversationCache 行数闸门（#25 家族第 10 员，≤200）。防「给 stable 类加 var ⇒ 编译器照单全收 ⇒ 重组静默退化 ⇒ 闸门全绿无人察觉」 |
| C4 `perf(ui)` | **W81-05~08 观测面四件**：a) acquire 语义区分（run 窗口 true 重置 / 页面窗口 false 不清空——修「run 结束开诊断页曲线被抹空」）；b) CPU 曲线按 `atElapsedMillis` 时间轴定位 + >2.5s 缺口**显式断线**（修「W78 实测冻结 416s 缺口被画成连续线」）；c) 电池温度同源暴露（BroadcastReceiver 同 ThermalGovernor `batteryTempTenths` extra，不轮询）；d) availMem 从 1Hz binder 降为 5s 粒度（与 PSS 同款，消除自相矛盾） |
| C5 `chore(data)` | **W81-09/10 顺手卫生批**：G-4 delete 落盘顺序（文件删除挪 index 重写后——中途失败最多留无索引孤儿文件，不出现点不开的死条目）；P2-2 ConversationCache KDoc 边界申报；childCount `list()` 替 `listFiles()`；F5 ChatScreen 三回调 remember(viewModel)；F7 GlassImageThumb 单 openSource + BufferedInputStream mark/reset 两遍；G-5 MidRunFreezeFakeClock 锚定假设成文；G-6 圆角三处单源派生（GlassDefaults.RadiusLg getter + LiquidShapes 前四档派生 Tokens，extraLarge 48.dp 按 W79 决策保持独立） |
| C6 `test(data)` | **W81-11 RunWakeLockKeeperTest**（销 W78-06）：null PowerManager 测试缝 3 例（acquire 不崩且 isHeld 恒 false / startRenewal 幂等早退 / release 幂等）；keeper KDoc 补「复活路径与自愈指针」段 |
| C7 `chore(ci)` | `build.yml` baseline 784→**799**（WallClockEvidenceTest +5 / RunWakeLockKeeperTest +3 / FrameStatsCollectorTest +7） |
| C8 `docs(w81)` | 本交接 + `docs/15` W81-01~12 + `docs/w81-perf-evidence.md` + `docs/14` §C7 审批路由 |

## 二、判据与实测

| 项 | 结果 |
|---|---|
| fulltest | **799 / 0 / 0**（9 模块 XML 汇总，与 baseline 799 精确吻合） |
| `:app:assembleDebug` / `:app:lintDebug` | 绿 / 0 新增 |
| arch-guard | **48/48**（#47/#48 新增；#26 @Test baseline 双向同步 799） |
| selftest | **本地全量由用户取消**（取消前 173 PASS / 0 FAIL，无失败）；新增守卫六夹具（case81/81b/81c/81d/82/82b）已由 `--only 47,48` 实证 15 PASS / 0 FAIL；**全量覆盖由 CI 权威兜底**（build.yml:249 跑全量 selftest，push 后核） |
| @Test 严格口径 | **799** = build.yml baseline |
| 行数闸门 | background/ 974→**981/1000**（余 19）；AgentRunner 2823→**2881/2900**（余 19）；ChatRunCoordinator 1253（禁触未动）；ConversationCache 84（#48 上限 200）；DiagnosticsScreen ~1030（无单文件闸门） |
| 真机（OPPO PDRM00 / A13） | ① 冷启 **664ms / Fully drawn +667ms**（锚生效）；② 流式端到端（USER→MODEL STOP、8 tok/s、journal+conversations 全文落盘）；③ **acquire 语义决定性证据**：hello run 后开诊断页「样本 23/180」（旧行为会清到 ~2）；④ 电池温度行「31.1℃（保护线 44.9℃）」；⑤ 帧统计采集器闭环 + **观测者效应实证并修复**（见三.1） |

## 三、关键决策

1. **观测者效应：帧统计采集器把自己拖成 5fps（真机实证）**：初版每帧重算即发布 StateFlow ⇒ 诊断页整页每帧重组 ⇒ 实测 FPS 5–6 / 掉帧 98.5%。修法 = 窗口仍每帧累积、**发布限 4Hz（250ms）**，修复后 57fps / 16.7% 掉帧。教训：**性能观测面本身进重组路径时，发布频率就是被测对象的一部分**——与 gfxinfo 不可用同源的「看性能」难题，这次靠节流解。
2. **零依赖 Choreographer 方案绕过版本锁死纪律**：外审（第二十审）建议 JankStats，但 W80-07 卡在「等用户批依赖版本」；W81 识别 JankStats 底层即 FrameCallback 封装 ⇒ ~30 行零依赖采集器覆盖「滚动/生成期掉帧率」判据，不触发锁死纪律。JankStats 完整指标（jank 分类/聚合报表）降格为后续，审批请求已路由 `docs/14` §C7（W80-07 同步更新）。
3. **G-1 的「冻结感知」与 `freezeAwareTimeout` 基线纪律同款**：`frozenBaselineNanos` 在 run 起点同刻取，历史冻结（起点之前）不进本轮预算；`gateRoundHead` 里 nowNanos 先读、frozenNanos 后读 ⇒ 冻结时段被少计（顺延）而非多计（误掐）——安全方向与 pausedNanos 审批停表一致。
4. **IMP-4（onStartCommand 补调 startRenewal）登记不修，回源实证不可达**：前台化失败后控制器 `started` 仍为 true ⇒ `shouldStart` 恒 false 不会二次 startService；stopSelf 后下次 run 必得新实例 ⇒ 无「同实例复活」路径；且 `w78-bg-run-design.md:499` 明确「不要在服务侧补调（会变成两个启动点）」。落地形态 = keeper KDoc 自愈指针段（W81-11），不是代码。
5. **「detailed guide」类题面确认式回复 = 容器长期行为，非回归**：W81 真机新增 3 例全文题面（含拼写偏差注入），模型均语法正确、切题但只确认不执行（12–14 token STOP）；全量扫描设备 183 个历史会话证实 W81 之前即此形态，而推理/数学/工具类任务能产出 509–1198 token 连贯输出（数学题 60 km/h 计算正确）。⇒ 语言质量无劣化签名，登记为容器行为画像，不挂缺陷。
6. **F6 采样/通知 1Hz 合并重提挂号（W81-12 parked）**：触 GenerationNotifier tick 与采样线程生命周期所有权，非顺手项，待单独立项。

## 四、遗留（详见 docs/15）

- **W81-12**（parked）：F6 采样/通知 1Hz 合并（触 GenerationNotifier，非顺手项）。
- **W80-07**（parked，部分解锁）：JankStats 完整指标路径（等 `docs/14` §C7 用户裁决；不批则 closed-dead——零依赖采集已够用）。**残留**：生成期干净帧数据未采到（窗口只覆盖最近 2s + 导航摩擦，⛔ 登记）。
- **W80-08**（parked）：模糊滚动降强度——零依赖采集已产出设置页 57fps/16.7%（健康但 adb swipe 有负载污染、非手指滚动口径），生成期数据未采到 ⇒ 不急着判死。
- **W80-06 / W80-09 / W79-07 / W79-08** 等维持原状；**W79-07 余量 26→19**（keeper KDoc +7 行），拆留下波（本波无功能增量需求）。
- **真机未走完项（申报）**：G-4 删除 UI 流（屏幕锁摩擦，代码路径已核验）；F7 缩略图渲染（UI 流程摩擦）；生成期帧数据（导航摩擦）。三项均非判据缺口——G-4 有 JVM 侧 ConversationRepositoryTest 覆盖落盘顺序，F7/F6 有编译+单测背书。

## 五、教训

1. 🔴 **子代理报告「称做了」≠ 代码做了**：availMem 降频在子代理交付报告里列为完成，主理人复核 diff 时发现字段都没落地（`grep lastAvail` 零命中）——「信任但验证」再次兑现。凡跨代理交付，**逐条 grep 判据词**再采信。
2. 🔴 **观测者效应是性能观测面的一等公民**：帧统计器每帧发布 ⇒ 诊断页每帧重组 ⇒ 把被测帧率打到 5fps。凡是「高频数据源 + Compose 订阅」组合，先想发布节流再想窗口算法。
3. **Compose 输入框不吃 `input keyevent 67` 退格**（真机注入摩擦）：长题面注入后清空只能换新对话；`adb input text` 的空格必须 `%s` 转义（未转义时 journal 里用户输入只剩首词——曾误判为「模型丢输入」，实为注入手法 bug）。教训同既有铁律：**判据异常先查输入侧喂了什么**。
4. **测试算术错误被单测当场抓住**（`300 + 50 − 60 = 290` 误写 240）：G-1 首轮 core-agent 单测即红——这正是把停表算术外提为纯函数的价值（接线错误在 JVM 层暴露，不等真机）。
5. **fulltest 是脚本不是 gradle task**（`bash scripts/fulltest.sh`，须仓库根 + 三环境变量；`localbuild.sh fulltest` 直接用法错误秒败）——本地闸门链入口判据回到仓内脚本头。
6. 🔴 **台账条目必须进 `## 主台账` 表内，不能新开「## Wxx 续更」标题节**：本波一度把 W81-01~12 建成独立节（与 W79/W80「行追加进主台账表 + 节内摘要行」的同构形态偏离），守卫 #42（ledger-era 对账）当场抓红——它的 awk 只扫 `## 主台账` 到下一个 `## ` 之间的表格行。形态修正（行并入 + 摘要行随附）后 48/48 转绿。教训：**台账的机械核对面是「主台账节」这个物理范围，新开节 = 把条目移出核对面**——与 W71 G2「数字写死守卫自己过期」同族：判据的扫描范围本身就是承重结构。
7. **#47 红面经主理人独立复验**（不只信子代理的 selftest 报告）：临时给 `ChatMessage` 主构造注入 `var id` ⇒ 守卫精确报 `10: var id: String = newId(),` 并判红 ⇒ 红面真实非僵尸；还原后 48/48。新守卫落地时，「注入违规看红、还原看绿」两步都该由主理人亲手跑一遍。
8. 🔴 **本地全量 selftest 的时间黑洞（本波实测，下波起改流程）**：`arch-guard-selftest.sh` ~183 用例，每用例完整跑一遍 arch-guard.sh（48 项）。本机（Windows Git Bash）**进程 spawn 成本实测 ~200-300ms**（bash -c 302ms / grep 198ms；Linux 为 2-5ms，差 50-100 倍）⇒ 脚手架树单次 arch-guard ~32s、真实树 **207s**（bash -x 时间戳 trace 定位：#13「AgentRequest 可空字段无孤儿」对 5 个模块树逐字段递归 grep，独占 94.9s）⇒ 本地全量 2.5h 只跑到 175/183（用户取消，0 失败）。**CI 上同一全量 selftest 所在的 assemble-debug job 总共只要 4m02s**（W80 run 39990487075 实测，含 gradle 构建）。⇒ **结论：全量 selftest 是 CI 职责（build.yml:249 一直在跑），本地只跑 `--only <新守卫号>` 验红绿面**（本次 `--only 47,48` 五用例 159s）。下波流程据此改写；#13 的单字段递归 grep 合并为单次 `grep -E` 属本地体验优化，挂 W82+（CI 无此瓶颈，不阻塞）。

---

*判据来源：主理人一手复跑（fulltest 799/0/0、arch-guard 48/48、selftest 173/0 后由用户取消全量、真机五项 + 观测者效应实证修复）+ 三子代理并行实施（guard-smith 双守卫 / perf-observer 观测面四件 / hygiene-bot 卫生十项，均经主理人 diff 复核）+ 五份外审论断逐条回源核验。*
