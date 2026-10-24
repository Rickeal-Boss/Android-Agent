# W80 性能开销波 — 交接（2026-10-24）

> 分支 `core-fix`（续 W79 tip `9dc8115`）。
> 本波主题：**性能开销改进**——三份外审（第十九审 core-fix 深审 + 性能优化审查 + core-fix-review）一致建议的路线落地。
> 挂账权威 = `docs/15`（W80-01~09）；设计判据源头 = 三份外审 + 方案席 Phase 1 分析（主理人终裁）。

## 一、交付

| 提交 | 内容 |
|---|---|
| C1 `feat(app)` | **W80-01 测量基建**：MainActivity `reportFullyDrawn` 锚（LaunchedEffect 一次性 + runCatching 防 OEM SecurityException）；工作区 `_ci-tools/gfxinfo-capture.sh` 三场景脚本（不入仓）。**关键取证：`dumpsys gfxinfo` 在 PDRM00/ColorOS 帧统计恒 0（设备级不可用）⇒ scroll/generation 场景 vacuous，帧统计唯一路径 = 程序化采集（W80-07 parked）** |
| C2 `perf(ui)` | **W80-02 P1-render 四件套**：a) Compose 稳定性报告（DSL 产出，留存 `_ci-tools/_w80_compose_stability_report/`，`MessageRow(message)` unstable→stable 两态实证；CLI `-P` 在 Kotlin 2.3.0 静默失效——坑已留档）；b) `feature-chat/stability.conf`（只声明报告背书的全 val 类；conf 注释只能 `//` 不能 `#`）；c) 流式收集收窄（顶层 `map{isStreaming}.distinctUntilChanged()` 低频订阅；文本订阅下沉 streaming item；滚动跟随 `snapshotFlow→flow.map+collectLatest` 语义等价）；d) 行内 remember（key 全覆盖）。`collectAsState` 初值用 `remember(streamingFlow){ streamingFlow.value.isStreaming }`（防中途返回闪空态；裸读 `.value` 触发 lint，remember 包裹通过） |
| C3 `perf(data)` | **W80-03 会话读缓存 Phase A**：新增 `ConversationCache`（纯 JVM LRU，accessOrder LinkedHashMap + @Synchronized，上限 8 可注入）；接线 load（命中短路）/ save（**唯一写方，落盘成功才写穿**）/ delete（锁内 invalidate）。**审查席 P1（load 未命中回填 TOCTOU ⇒ 陈旧快照丢消息）+ P2（JsonFileStore 吞 IO 异常被缓存成永久熔断）合并修法**：load 一律不回填 + parseFailed 二态删除 + appendMessage 探测/读取合并（`exists → load ?: 拒写本次`）⇒ 热路径 2 次全量读 → 稳态 0 次，损坏保护语义与 HEAD 等价 |
| C4 `perf(data)` | **W80-04 沙箱装饰-排序-去装饰**：一次遍历缓存 stat 值（原 sortedWith selector O(N log N) 次 lastModified）+ 逃逸剔除前移（等价性：比较子与剔除正交、删子集不改余序、totalEntries 口径不变）⇒ stat 3N+2N·logN → O(N) |
| C5 `fix(agent)` | **W80-05 onScreenOn 返回 (delta,total)**：消除亮屏日志第二次 `frozenNanos()` 取锁；FreezeClock 105→**108**（W81 订正：交接/台账原记 109，实测 wc -l = 108，净 +3 与 numstat 一致；纯记账误差，无判据影响）；background/ 包 973→**974（余 26）** |
| C6 `chore(ci)` | `build.yml` baseline 773→**784**（ConversationCacheTest 10 + SandboxFileScannerTest 1，代码位实测 784） |
| C7 `docs(w80)` | 本交接 + `docs/15` W80-01~09 + README/docs/02 如需 |

## 二、判据与实测（全绿）

| 项 | 结果 |
|---|---|
| fulltest | **784 / 0 / 0**（9 模块 XML 汇总，与 baseline 784 精确吻合） |
| `:app:assembleDebug` / `:app:lintDebug` | 绿 / 0 新增 |
| arch-guard | 46 项：前 40 项实证 OK（含 #26 基线双向同步、@Test void），后 6 项与 W80 零交集（修复前完整跑仅 1 error 已修）；CI 终验 |
| @Test 严格口径 | **784** = build.yml baseline |
| 行数闸门 | ChatMessageList 490→**507**（自守 600）；FreezeClock 105→109；ChatRunCoordinator 1253（禁触未动）；background/ 974/1000 |
| 真机（OPPO PDRM00 / A13） | ① 冷启存活 crash 缓冲空；② **Fully drawn +671ms**（锚生效，与 TotalTime 670 重合）；③ 流式生成端到端（Qwen2.5-1.5B：USER→MODEL "hello"、finishReason STOP、journal+conversations 全文落盘、WakeLock ACQ）；④ 杀进程重启后抽屉/会话页消息完整渲染（**load 缓存路径无回归**）；⑤ 工作区页文件列表正常（W80-04 无回归） |

## 三、关键决策

1. **写去抖被否决（本波最重要的「不修」）**：方案席实证 journal 兜底只对未 settled run 成立（settled 行先于 UI commitAssistant 落盘 ⇒ 窗口内崩溃无恢复卡）；每 run 仅 2 次 append 去抖合并不了；强制 flush 必须触 Coordinator（余 47 禁触）；onCleared 不能 await。⇒ **Phase A 只做读缓存**，Phase B 登记为 W80-06（硬边界清单成文）。教训同 W79-08：**修一个收益结构不成立的方案前，先证明它省的是真开销**。
2. **审查 P1 修法 = 结构合并而非补丁**：审查席建议「load miss 不回填」即可；主理人推演后升级为「load 永不回填 + parseFailed 删除 + appendMessage 探测/读取合并」——一次改动同时消 P1/P2，且稳态收益从 1 次读升到 0 次。**派修复单前主理人先走一遍控制流**（六场景：新会话首条 / 既有会话首条 / 损坏文件 / 并发 load / delete 后 / rename）。
3. **JankStats 不进本波**：版本锁死纪律（新增依赖需用户点头）+ debug/main 源集接线成本；gfxinfo 脚本先扛冷启维度。PDRM00 gfxinfo 0 帧取证后，JankStats 升格为 W80-07（唯一可行路径）。
4. **listTurnsSync 不修**：Wave 30 已判死代码（零生产调用点），修死代码零运行时收益（W80-09）。
5. **三份外审建议的「不做」清单全部维持**：baseline profile（新模块大工程）、缩略图 LRU（ImageDecoder 口径未裁定）、模型卡 tok/s（聚合持久化设计）、TokenEstimator（冻结纪律）、引擎 idle-evict / 记忆变更延后（引擎域设计评审，收益三个数量级但独立波）。

## 四、遗留（详见 docs/15）

- **W80-06**（parked）：写去抖 Phase B（硬边界清单 + kill -9 验收前置）。
- **W80-07**（parked）：JankStats 程序化帧统计（等用户批依赖版本）。
- **W80-08**（parked）：模糊滚动降强度（等 W80-07 帧数据标定）。
- **W80-09**（parked）：listTurnsSync 双解析（等 Wave 3.5 启用历史重建）。
- 沿袭：W79-05~08 / W77-01 / W78-01~04 等外部条件项不变；**W79-07（background/ 拆分）余量再收 1 → 余 26，优先级上调**。

## 五、教训

1. 🔴 **Kotlin IC ABI 快照损坏 = 模块级 clean 才能解**：四席并行构建后 `:app:compileDebugKotlin` 报 `LiquidAgentApp.kt` Unresolved `LocalAppContainer`（无人改该文件；jar 字节码完好 javap 可证；同包其他符号正常）——`--stop` daemon 无效、`:app:clean` 无效，**`:core-data:clean` 后恢复**。凡「Unresolved 但源码/依赖/import 全对」先怀疑上游模块 IC 快照，按依赖链 clean 上游。
2. 🔴 **沙箱对脚本的多形态误杀（SIGTERM）**：arch-guard.sh 在重定向/管道/分号链下多次被 SIGTERM 截断（stdout 尾部丢失）；**裸跑（无任何包装）最稳**；`adb shell "nohup ... &"` 客户端 SIGTERM ≠ 失败（设备侧存活，判据看设备文件 mtime）。
3. 🔴 **adb 并行调用互踩 daemon**（could not read ok from ADB Server）⇒ adb 一律串行。
4. **报告的行号会过时、但判据结构不会**：三份外审的行号与 W79 快照一致（因为都审 9dc8115）；方案席对每条建议做了「收益结构」复核——发现 P0-1 的真实热开销在「读」不在「写合并」，避免了一次高风险低收益改造。
5. **测试写法的守卫联动**：裸 `assertFailsWith{}` 收尾违反「@Test 必须 void」（整类 initializationError，其余用例全部不跑）——守卫抓到后修法是「绑 val + Unit 断言」（顺带增强断言），而不是简单加 `Unit` 占位。

---

*判据来源：主理人一手复跑（fulltest 784/0/0、arch-guard、真机五项）+ 审查席独立审查（P1/P2 实证 + 修复复核）+ 方案席 Phase 1 源码核验（journal 路径 / 死代码定性）。*
