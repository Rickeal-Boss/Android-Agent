# W82 交接：质量与性能波（外审核验 + 正确性修复 + 提示词拆分第一刀 + 帧统计会话聚合）

- **分支**：`core-fix`｜基线 HEAD `8a2dcba`（W81 收尾）｜波次日期 2026-10-25
- **主题**：三份外部审查报告回源核验（4 证伪 / 7 漏报）→ 按其结论实施正确性与并发修复、性能观测面收窄、AgentRunner 拆分第一刀、沙箱逃逸可观测化、设置页写放大治理、守卫补漏
- **团队**：主理人祁研深 + 严质衡（审查）/ 沈思远（方案）/ 崔续程（CI）/ 柯码成 ×4（实施）；实施后期全员 429 配额耗尽，收尾（台账 / 基线 / 阈值 / 守卫注释 / 本交接）由主理人接手完成

## 一、波次数字面（冻结后实测）

| 项 | 改前 | 改后 | 闸门 |
|---|---:|---:|---|
| `@Test` 代码位实数 | 799 | **818** | build.yml `baseline=818`（#26 双向核对通过口径） |
| arch-guard 项数 | 48 | **51** | #49 FrameStatsCollector ≤250 / #50 DiagnosticsScreen ≤1150 / #51 AgentPromptComposer ≤400 |
| `AgentRunner.kt` | 2881 | **2715** | #31 ≤2900（余 185；docs/13 触发条件②「≥2880」已满足并执行） |
| `background/` 包 | 981 | **996** | #45 ≤1000（**余 4**——W79-07 拆分重启前提已实质逼近） |
| `FrameStatsCollector.kt` | 174 | **249** | #49 ≤250（余 1） |
| `DiagnosticsScreen.kt` | 1038 | **1085** | #50 ≤1150（余 65） |
| `AgentPromptComposer.kt` | —（新） | **202** | #51 ≤400 |
| selftest case 数 | 82b | **85b** | case83/83b/84/84b/85/85b（三条新守卫红绿面） |

**验证状态**：`:app:assembleDebug` BUILD SUCCESSFUL（5m32s）；其余闸门（lint / fulltest / arch-guard / line-table / selftest --only）与真机验收批见 §六，未完成项如实申报。

## 二、外审核验结论（四态判定，审查席交付 + 主理人复核）

三份报告（A = W81 下一步改进清单 / B = 深度审查与改进点清单 / C = 深度审查报告）31 条论断，**24 条行号逐字命中**，问题全在因果归因：

**证伪 4 条（未进码面）**：
1. B P0-1「gradle 退出码被吞」——`fulltest.sh:41` 有 `set -uo pipefail`、gradle 裸命令无管道、`gradle_rc=$?` 显式收集、`exit "$gradle_rc"` 透传；真实残留只是「rm 失败 ⇒ 陈旧 XML」（已由 W82-10 mtime 断言补刀）
2. A Y-1①「导航离开采集器仍在跑」——`SettingsRoute` 原有 `DisposableEffect{onDispose{stop()}}`（注：该 onDispose 在 W82-08 因 debug 恒开语义被删，但 `active: StateFlow` 已根治失同步，见 W82-08 台账条的审查席主动更正）
3. B P1-1「AgentLog 纯内存 ⇒ 崩溃后日志全失」——`AgentLogFileStore` ERROR 级已同步落盘（50 条环 + 原子压缩）
4. C 4.6「registerReceiver 是构造期 binder」——实为传给 ThermalGovernor 的 lambda 形参，冷启不触发

**漏报 7 条（审查席自查发现，已全部处理或登记）**：fulltest 在 CI 分叉（CI 直接 `gradle test` 不调脚本）/ 沙箱 fail-closed 与真空目录 UI 不可区分（W82-06 补 warn）/ 设置页整份覆盖丢更新（W82-07 修）/ `_samples` 竞态影响通知栏文案 / 连点两次下载 `.part` 孤儿（W82-03 补丁）/ FrameStatsCollector KDoc 主线程契约自相矛盾（W82-08 补申报）/ MIN_ROWS「派生−1」口径使删一条守卫不判红（W82-10 订正）。

## 三、码面改动清单（19 改 + 7 新）

### 正确性与并发（W82-03/04/05）
- `ModelsViewModel.kt` 1236→1314：下载取消三层防御（句柄 + 局部 id 守卫 + `invokeOnCompletion` 身份比对）；`onDownloadFromUrl` 开头 cancel 旧 job 后按「换了名字」条件 `discardPartFiles`（防孤儿 .part；同名重下不在此删——与 enqueue 并发会删正在写入的文件）
- `ModelDownloader.kt` 344→390：`missingTaskProgress()` 纯函数（空 cursor ⇒ `STATUS_FAILED` + `fromCursor=false`，过不了转正闸门）
- `PerformanceMonitorManager.kt` 409→479：7 字段 `@Volatile` + `_samples.update{}` CAS + KDoc 订正 + sampleCount 非原子申报
- `RunWakeLockKeeper.kt` 117→132：`released` 标志 + 三字段 `@Volatile` + acquire 不对称 KDoc 申报

### 安全可观测（W82-06）
- `FileTools.kt` 442→512：`EscapeAudit`（error 落盘 + 30s 节流 + 200 字符截断只记路径）；拒绝文案加具体示例（**提示词面变化已申报**：不进系统提示词、不改 echoCorpus 指纹，只改 ToolResult 回灌文本）
- `SandboxFileScanner.kt` 249→320：fail-closed 空结果与逃逸剔除补 `warn`（`ScanEscapeAudit` 同形态节流，独立一份——跨模块提 public 不值得）

### 性能观测面（W82-08/09）
- `FrameStatsCollector.kt` 174→249：会话级聚合（`sessionNanos` 取 Σ 帧间隔不取墙钟差——W78 冻结 416s 实证）+ `active` StateFlow + debug 恒开（AppContainer 装配后 start，release 恒零行为）+ 两个构造缝（postToMain/elapsedMillis）
- `DiagnosticsScreen.kt` 1038→1085：`FrameStatsCard` 卡片自订阅（4Hz 只重组卡片）+ 会话四行 + 重置会话按钮 + 温度保护线单源（`ThermalGovernor.BATTERY_FUSE_CELSIUS`）
- `SettingsRoute.kt` 190→174：删顶层 collect 与页面级 `stop()`（与 debug 恒开语义互斥，审查席核验成立）

### 设置页写放大（W82-07）
- `SettingsScreen.kt` 899→984：三态草稿 + 400ms 防抖 + 三边界 flush（失焦 hasFocus / onDispose / onCleared 申报）；否决 draft 搬进 VM（第二状态源）；否决 `draft != config` 脏判据（真值晚到会抹掉已存提示词）
- `SettingsViewModel.kt` 273→315：`onSystemInstructionCommit` 走 `processScope` + **落盘闭包只改本键**（修丢更新面；原 KDoc 把「勿改」写成纪律会永久固化缺陷，已随修法改向）

### 拆分第一刀（W82-02）
- `AgentRunner.kt` 2881→2715 + 新建 `AgentPromptComposer.kt` 202：提示词常量族 5 个 + 两个组装函数整段外提；调用点直调无薄委托；`echoCorpus` 口径不变
- **零行为变更三重证明**：搬移块 diff=0 / raw string 逐字节一致 / Golden 12 组 SHA-256（拆分前两次 + 拆分后一次实跑均 4/0 绿）

### 闸门（W82-10）
- `scripts/arch-guard.sh`：#49/#50/#51 + #48 注释家族枚举补三员
- `scripts/arch-guard-selftest.sh`：KNOWN_GUARDS + 映射表 + scaffold 铺三文件 + case83/83b/84/84b/85/85b
- `scripts/line-table.sh`：MIN_ROWS 12→13 + 维护口径订正
- `scripts/fulltest.sh`：mtime 断言（销 W74-05 判据②）
- `.github/workflows/build.yml`：baseline 799→818 + 沿革注释（末次 +2 为节流边界用例）

### 新增测试（799→818，+19）
`AgentPromptComposerGoldenTest` 4 / `EscapeAuditTest` 1 / `ModelDownloaderMissingTaskTest` 3 / `FrameStatsSessionTest` 5 / `PerfConcurrencyTest` 2 / `SandboxFileScannerTest` +3 / `RunWakeLockKeeperTest` +1。

## 四、模型输出语言质量取证结论（W82-11，详见 `docs/w82-output-quality-evidence.md`）

186 个历史会话全量分析，量化签名五项：
1. **`<think` 思考标记泄漏正文**（文本协议解析失败路径无兜底）
2. **原生工具 JSON 泄漏正文**（`{"tool":` 形态直接上屏）
3. **确认式回复不执行**：`detailed guide` 类长题面 12–14 token 只确认（W81 结论 = 容器长期行为，非回归）
4. **中文问英文答**：`buildSystemSections` 确认**无任何语言段**，人格三档也只讲语气
5. **真空答**：`finishReason=None` + usage 全零

**延迟实测**：首轮 TTFT 20.2s vs 二轮 1.5s ⇒ 冷启首 run 延迟主要是**模型加载**不是 prefill。

**修复不在本波**（W82-12 parked）：加语言段属「提示词面变化须真机 A/B」，且与 W82-02 的字节等价证明同波会互相污染（golden hash 失去基线意义）。落点候选排序：systemInstruction 语言段（必须英文写）> EXECUTION_DISCIPLINE 独立小段（不塞 STOP_CONDITIONS）> 压缩残留 > 采样参数。

## 五、遗留挂账（权威版 = docs/15-backlog.md）

| ID | 内容 | 状态 |
|---|---|---|
| W82-12 | 语言段 + EXECUTION_DISCIPLINE 修复（须真机 A/B + golden 哈希同步更新） | parked |
| W82-13 | `ChatParamsPanel` 系统提示词永不落盘（对话页改的文字不持久化） | **active**（修法已定：改走 onParamChange） |
| W82-14 | 下载轮询无 STALLED 判据（建议按 bytesDownloaded 无增长判停，非轮次上限） | parked |
| W79-07 | `background/` 拆分（**余 4**——下波动该包前必须先拆；唯一有效解 = 移出包到兄弟目录 `core/data/oem/`，禁子目录；可移面仅 GUIDES） | active |
| W80-06 / W80-07 / W80-08 | 写去抖 Phase B / JankStats / 模糊降强度 | parked |

## 六、验证状态与未完成项（如实申报）

**已完成**：
- `:app:assembleDebug` BUILD SUCCESSFUL（5m32s，RC=0）
- 拆分 Golden 三次实跑（拆分前占位采集 / 拆分前哈希填回 / 拆分后）均 4 tests 0 failures
- 台账 / baseline / 守卫注释 / 本交接已由主理人收尾落盘

**未完成（429 配额耗尽后主理人收尾范围外，须补）**：
1. `:app:lintDebug`（新增 Kotlin 后必须跑；feature-settings 有 lint baseline ≤4）
2. `bash scripts/fulltest.sh`（仓库根 + 三环境变量；核对模块数 = 9 + failures=0 + mtime 断言不误红）
3. `bash scripts/arch-guard.sh`（仓库根裸跑；预期 `grep -c "^OK "` = 51）
4. `bash scripts/line-table.sh --check`（预期 13 行 ≥ MIN_ROWS 13）
5. `bash scripts/arch-guard-selftest.sh --only 49,50,51`（预期 PASS=10 FAIL=0；**元 case 53/75 在 --only 下被跳过 ⇒ 四方一致性只能靠 CI 全量 selftest**）
6. **真机验收批**（全部申报未走）：防抖四步（打字<300ms 返回 / 切 tab / home / chip 竞态）× 设置页+对话页；沙箱逃逸留痕（同题复跑 3 次 + 杀进程后落盘可见 + 30s 节流）；下载取消（0 命中「模型登记失败」+ 可再取消 + 无 .part 残留）；帧统计会话聚合（采集时长 ≥ run 时长 ×0.9 + 会话帧数 > 窗口 120）
7. 提交推 CI（双绿判定）

## 七、本波教训（进记忆）

1. **外审行号可信度高于一般水平，但因果必须回源**：31 条中 24 条行号逐字命中，仍有 4 条因果被证伪——「先采信再核验」会直接把 A 报告「本轮最重要」的伪缺陷排成 P1
2. **新文件当波补守卫的纪律第七次重现**（W82 是 AgentPromptComposer 当场裸奔，比 W81 同波漏一半更差）⇒ 根因是机制缺陷（守卫逐条手写+事后补），W83 应考虑 `check-new-file-guard.sh` 入场券（仅本地/pre-push，CI 侧自指悖论）
3. **Edit/Write 工具长中文内容参数丢失**：本次收尾期间多次出现（>2KB 中文 payload 时参数不送达）⇒ 长文档改用 Python 脚本写入，短编辑直接 Edit
4. **`--only` 覆盖不到元 case**：selftest 的四方一致性断言（case53/75）在 `--only` 模式下被跳过 ⇒ 本地 `--only` 只作冒烟，全量归 CI（W81 教训⑧ 再次应验）
5. **基线必须在代码冻结后一次性定值**：本波 @Test 在 799→810→816→818 之间漂移 4 次（队友实时加例），先改基线必红
6. **审查席主动更正既有结论**（Y-1① 前提变化）：原证伪依据的 DisposableEffect 被删后主动申报——「证伪的前提失效时要更新证伪本身」，这是四态纪律的正确用法

## 补记（主理人收尾）：lint `SdCardPath` 判红与文案终态

W82-06 的拒绝文案首版含字面 `"/sdcard/..."`，`:app:lintDebug` 判红（`SdCardPath`：lint 把字符串里的
`/sdcard` 当硬编码外部存储路径）。**终态文案**（已改写并重跑 lint 验证）：

```kotlin
private const val ABSOLUTE_PATH_MESSAGE =
    "拒绝绝对路径：沙箱路径必须相对于沙箱根目录，例如 \"note.txt\" 或 \"docs/a.md\"；" +
        "不要使用 \"/Users/...\"、\"/home/...\" 或 sdcard 等外部存储根路径。"
```

- 改写只动「禁止形态」的表述（去掉斜杠形态、保留 sdcard 概念指引），**给模型的指导力不降**；
- KDoc 已补维护纪律：「文案内不得出现 "/sdcard" 字面子串」——防下一个人再把斜杠形态加回来；
- 这仍属 W82-06 已申报的提示词面变化范围内（ToolResult 回灌文本，不进系统提示词、不改 echoCorpus 指纹），
  真机验证项不变（同题复跑 3 次看被拒后是否改用相对路径）。
