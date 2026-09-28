# Wave 30 交接：物理断路器 + 生命周期 + 十路复审修复批

> 写于 2026-09-28 凌晨。基线 `e80f254`（Wave 29 后）→ 推送 `210af0a`。
> 外部输入：Operit/Operit2 启发分析（B.2/B.3/B.10/§3.4）+ 三项决策建议（复评 v4）。
> 团队：沈思远（蓝图）→ 柯码成（三批九 commit）→ 10 个复审 Agent 并行深度复审 → 主理人（裁决/收编/接线补全）。

---

## 1. 三项决策落地（用户裁决采纳）

| commit | 内容 |
|---|---|
| `ec7de90` | ③ README 特性表三态（代码状态 × 验证状态），删「导入导出」夸大项 |
| `2acdd28` | ② arch-guard 第 4 条补 `app.cash.sqldelight`（堵守卫盲区） |
| `533494c` | ① **history_v2 判死**：摘 archiveTurnNow 写入链（-74 行），恢复权威不变，启用三前提见 SegmentedHistoryStore 类头 |

## 2. Wave 30 本体（物理断路器 + 生命周期）

蓝图：`_plans/wave30-physical-breaker-plan.md`（579 行，沈思远）。三批九 commit + B.3：

| commit | 内容 |
|---|---|
| `8ee944d` | **B.3 onTrimMemory 接线**：close() → releaseEngineIfIdle()，生产死代码 → 活的内存压力响应 |
| `fe6ca80`/`d3f3b68` | **B1 RunTokenLedger**：core-agent/token/ 接口 + StateFlow 投影 + 池化 + AgentRunner 单点回写 |
| `045fbad`~`cda1753` | **B2 BreakerLedger 系列**：数据结构 / recordAttempt+ToolFailureStreak(≥4) / 振荡检测 / 墙钟 SOFT(3min)+HARD(5min)+TokenBudget |
| `d8a7dde`/`7a647b1`/`e152a8f` | **B3 Android 侧**：ThermalGovernor 四档 / 性能采样裁剪版（/proc 零权限）/ ThermalThrottle 回灌 |

## 3. 十路复审（并行，文件级互斥 + git index 铁律）

复审批 22 commit。**交叉发现的高价值缺陷**（全部修复，CI 验证）：

| 来源 | 缺陷 | 修法 |
|---|---|---|
| r2 | **RunTokenLedger 未接线**（生产恒 null，B1 承诺未兑现）→ 生命周期裁决：**本波不接线**（无消费方，等 token 可视化波次一起，同 history_v2 判死纪律），挂账 Wave 31 | KDoc 红线 + 三态挂账 |
| r5 | **振荡检测核心失效**：判据二取全量历史 distinct 而非滑动窗口 → A/B 交替抓不到；换参重试簇误杀 | `9c9a61c` 滑动窗口(6) + 真交替闸门 |
| r4 | **6 档归因 4 档死链**（ToolUnavailable/PermissionDenied/EngineFailure/MissingInput 生产不可达）+ 链式 replace 让运行时数据参与模板解析 | 主理人接线（743c39e）+ 单趟扫描（86f4e97） |
| r6 | LIGHT 热降档在 base<512 时**反向抬高** maxTokens；MODERATE 首轮也冷却 | `94e47d3` 单调不增 + `a1e37f7` round>0 |
| r7 | worker 句柄缺 @Volatile、/proc 静默失败、UI 线程 join 阻塞、jiffy 截断 | 5 commit（@Volatile/warnProcOnce/reaper/roundToLong/观测口） |
| r8 | **诊断卡 UI 断链**（Finished/Failed.report 被丢弃）+ onRetry 热闸先裁后拒 + 悬空 KDoc | 4 commit（7eada0e 等） |
| r1 | 注释漂移（OpenAI 遗留/过时行号）+ capabilities() 吞 CancellationException | b70e4d0 收编 |
| r10 | 测试断言质量（重言式/sleep 抖动/换算系数单向上界） | c281fbc 收编 |
| 主理人 | 归因接线补全：既有 6 判据 trip 登记 + EngineFailure report + ToolUnavailable 入账 + MissingInput 兜底档 KDoc | 743c39e/3ea5c66 |

## 4. CI 修复轮记录（无本地编译的迭代成本）

B2/B3/复审批共 5 轮 CI 红，根因分类：① enum 条目构造参数嵌套枚举短名不可用（11 处，需 `Severity.` 全限定）；② EngineFailure 非 BreakerKind 条目（trip 多余，归因走 engineCause）；③ `\r\n` 逐字符替换产生双空格（改 `\s+` 折叠）；④ nanoTime 未来刻度 5ms 偏移被 CI 调度抖动吃掉（偏移放大 5s）；⑤ 断言措辞与实现模板分歧 ×3。**生产代码缺陷为零**，全部是测试断言/接线完整性问题。

## 5. 挂账（Wave 31+）

- RunTokenLedger 生产接线（按 run 实例化或消费面确定后接线）+ token 可视化 + 估算口径标注
- 检索评分形状归一化（coverage + 阈值，Wave 31）+ `ToolSpec.keywords`（「算一下」词汇鸿沟正解）
- 摘要检查点（SummarizingContextCompressor 删除 + 轮边界重建）+ warmup 块
- `file_read` 分页（offset/limit）；`SubagentProgress` 事件；arch-guard 自测网 + 第 13 项方法体积守卫
- Lint baseline 毕业为阻断门禁；ConversationRepository 增量写；记忆 pull 化 + memory_search
- P2-8 CRITICAL 释放被 isBusy 跳过后无补释放（真机观察后定）；振荡阈值 3 vs 原型 5（真机数据定）
- D5 suggestion 未知来源换行（真机定位）；r6 commit message 残缺（a1e37f7，不改历史）

## 6. 真机验收（新增）

1. 热四档：SEVERE 拒新 run 有提示 / CRITICAL 释放无 SIGSEGV / MODERATE 轮间 2s 冷却（**首轮不冷却**）/ 降温回落正常重载
2. `adb shell ps -T <pid>`：空闲 5 分钟无 perf-sampler 线程
3. 诊断卡：热/墙钟/振荡/同参死锁 trip 后 UI 出「任务诊断」卡（render 文本）
4. ledger snapshot 与 Finished.usage 一致性
5. A/B 第 3 周期 trip 先于 maxRounds；合法换参重试与失败后重试不被误杀
6. 3min SOFT / 5min HARD 时间线；后台挂起不再被杀（onTrimMemory）
