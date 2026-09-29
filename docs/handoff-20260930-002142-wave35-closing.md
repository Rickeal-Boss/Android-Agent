# Handoff: Wave 34+35 收尾（原生工具通道 + 记忆 pull 化 + 静态可证批处理）

日期：2026-09-30 00:21 · 分支 `harness-improve` · tip `1ffd1c1`（CI 轮询中，前置 tip `496d701` 已双绿）

## Current state

### 本会话完成的工作

1. **Wave 34（原生工具通道 = 回显根治解）已全部落地并推送**，详见 `docs/handoff-20260930-wave34-native-tools-memory-pull.md`：
   - 引擎侧：`NativeToolBridge.kt`（ToolSpec→OpenApiTool→`tool()` 工厂；`execute()` 抛异常红线）、探针（在 `capabilities()` 内、开关门控——放 load() 会被 sameEngine 短路变成死开关，开发者实况纠偏后采纳懒探测）、`toolCallDelta` 上抛、`Message.tool(ToolResponse)` 回灌 + 配对闸门 `awaitingNativeToolResponse`、MODEL/TOOL 播种带配对、`nativeToolsRejected` 证伪自愈。
   - 上层：`useNativeTools` 三重门（能力位 ∧ enableTools ∧ config.nativeToolChannel）、native 激活时提示词删工具清单段（回显面物理消失）、每轮 `nativeToolsDrifted()` 漂移检测。
   - Settings 开关**默认关闭**（schema 形状离线不可验证，R1）；记忆 pull 化（`memory_search` + `renderIndex(600)` 标题索引，正文绝不进 systemText——否则每轮 re-prefill）。
2. **Wave 35（五份外部报告驱动的静态可证批处理）已落地**：D1 代理对截断 `String.truncateSafe` 统一、D4.2 通知权限三出口留痕、D2 `.tmp_` 判据收紧（最后一个 `.tmp_` 后全数字）、D6 记忆单条 2000 上限（纵深防御，工具层本有前置校验——如实定性）、B2 子 run 墙钟 evidence 口径修正、B1 `SummarizingContextCompressor` 判死删除、A1 预览竞态（cancel+对账双层）、A2/A5/A6/A7/A8 沙箱小修（A6 用 `LaunchedEffect(Unit)` 朴素优先）、D7 CancellationException 一致性（只改真吞 CE 的两处）。
3. **收口复审两轮**：第一轮 2 P0（legacy 回退工具能力静默归零 + 每轮 re-prefill；证伪后本 run 静默空窗）已修并验证成立。第二轮又揪出 2 P1，**本会话最后两个 commit 修复**：
   - `54a80cf`（P1-2）：会话重建不能无条件复位配对闸门——按播种历史初始化（最后一条播种是否带 tool_calls 的 MODEL），否则重建当轮构造 `model(tool_calls) → user(文本)` 半套配对且不自愈。
   - `1ffd1c1`（P1-1）：`readHead` 的 `truncateSafe(keep)` 是**空操作**（切片长度恒等于 keep ⇒ 短路必然命中），改手工回退（尾部高位代理丢 1 个 code unit，marker 预算 -1 吸收保住「恒 ≤ 限量」不变量）；`readRange` 同口径补齐且回退时 `shownEnd` 同步收缩（续读 offset 不跳字）；测试改 `readRange` 多组 limit（100..139）扫奇偶，消灭假绿灯。
4. **CI 前态**：`496d701` 双绿（Build 36593914994 / Release 36593915364，产物 release APK 70.2MB / debug 39.3MB / mapping 4.3MB）。`1ffd1c1` 是其上仅 2 个小 fix，轮询脚本 `_ci-tools/wait_w33.py` 已指向它。

### 关键裁决记录（勿复议）

- FE 流未改 `LiquidAgentApp.kt` 是正确的（routeTop 最长前缀匹配自动归页签；顶层重复注册会 duplicate-destination 崩溃）。
- 探针放 `capabilities()` 而非 `load()`（sameEngine 短路 ⇒ 死开关）。
- A6 朴素写法（不赌 `@OptIn` / 全仓首次 import）；权限名用字符串常量（防 InlinedApi 触发 warningsAsErrors）。
- `memory_read` 不退役（旧 journal/恢复路径兼容）；命中门维持 `score>0` 不改阈值。

## Next steps（优先级序）

1. **确认 `1ffd1c1` CI 双绿**（后台轮询中；若红，编译风险清单见收口复审报告——最高风险点是 `sendMessageAsync(Message, ...)` 重载与 `NativeToolCall`/`Message.model` 3 参构造签名，可用 `_ci-tools/jdk/.../javap.exe -classpath _research/models/_l171/cls <类>` 反查）。
2. **真机验收（adb 已解禁）**——按 wave34 handoff §验收清单，重点：
   - Gemma GPU 加载引擎错误（**用户已标记待解决**，Wave 33 观测基建已就位：ChatScreen 诊断小字会显示「请求 GPU 已降级 CPU 运行」；抓 `GPU 后端不可用` 日志原文）。
   - 回显是否根治：开原生通道开关跑长任务；若仍回显，第一条动作 grep「原生工具通道」确认是否 fallback。
   - 审批卡必须照常弹出（红线验收）；native+压缩后不出现「角色通道播种失败」。
   - 沙箱浏览器：>200 文件两处计数一致、预览竞态、目录 Toast。
3. **P2 挂账（可并入下一波）**：`GenerationNotifier` 三出口共用闩锁（按 reason 分闩）、`EngineContract` diag.nativeToolChannel KDoc「同一判据」表述不实、`AgentMemory.renderForPrompt` 成事实死代码（补显式留痕或删）、`toToolProviders()` 每轮重建（下沉到建会话分支）、`MemoryViewModel` 超限误报「文件已损坏」已修但 UI 文案可再分化。
4. **Wave 36 候选**：原生通道「同 run 内恢复」（capabilities 取到轮内）、提示词瘦身（真机语料回收后）、沙箱递归浏览、diagnostics 卡片化。

## Relevant artifacts

- `Android-Agent/docs/handoff-20260930-wave34-native-tools-memory-pull.md` — Wave 34+35 设计意图/取舍/真机验收清单（主文档）
- `Android-Agent/docs/handoff-20260928-wave33-preface-gate-sandbox-files.md` — 上一波（preface 第三态闸门 + 沙箱浏览器）+ §7 挂账
- `Android-Agent/docs/01-architecture.md` — 头部已加过期声明（5088 行旧文档，以 handoff+源码 KDoc 为准）
- 提交区间：`b17303d..1ffd1c1`（12 commits，含 10 个 Wave34/35 主体 + 2 个收口修复）
- 反查素材：`_research/models/_l171/cls`（litertlm 0.17.1 classes）、`_research/models/litertlm171_*.kt`（源码快照）、`_ci-tools/{ghapi,gitfetch}.sh`（GitHub API + 推送，PAT 内嵌）
- 外部报告（已消化）：`C:\Users\16896\Downloads\` 下 5 份 issues/深审/复审6

## Suggested skills

- 无必须项。若下一会话需接手 CAM-P 团队工作流：沿用「沈思远方案 → 柯码成×N 文件面互斥并行 → 严质衡审查 → 主理人集成提交 → CI 双绿」SOP；守卫 = `bash scripts/arch-guard.sh` + `python balance_check.py`（均须真实退出码 0）。
