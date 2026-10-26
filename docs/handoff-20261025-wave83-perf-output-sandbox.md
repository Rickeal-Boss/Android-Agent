# W83 交接 — 性能开销/输出质量/沙箱取证波（2026-10-25）

分支 `core-fix`，tip `0aa2a7d`（12 commits on `0018748`）。树干净，已推 CI。

## 一、波次范围与输入

四份外审报告（Desktop 2026-10-24/25）逐条回源核验后立项：性能开销改进
（background/ 拆分行数闸门、TTFT 披露）、沙箱逃逸（审计节流键细化、留痕
端到端验证）、模型输出语言质量（LQ-1 输出侧收口）+ 外审指出的登记面裂缝
（同型第七次）+ CI 同口径（fulltest 接 CI）。另读 `docs/handoff-20261025-wave82-quality-perf.md`
与 `docs/15-backlog.md`。

## 二、落地清单（12 commits）

| ID | 内容 | 关键文件 |
|---|---|---|
| W83-01 | 登记面对账：补登 W82-15~18 + W83-01~10 + LQ 编号勘误；**新建 scripts/check-backlog-refs.sh** | docs/、scripts/ |
| W83-02 | ChatParamsPanel 系统提示词落盘（W82-13，三边界 flush）+ **真机抓 bug 补 BackHandler**（第四边界） | feature-chat/×4 |
| W83-03 | LQ-1 输出侧收口：TextToolProtocol 工具名 trim 归一 + 拒绝可读说明行 + AgentLogStore.warn 留痕（**AgentPromptComposer 0 字节**） | core-agent/×2 |
| W83-04 | EscapeAudit 按 toolName+reason 分键节流 + ScanEscapeAudit 同改（MAX_KEYS 64/16 淘汰最旧） | core-agent/+core-data/×4 |
| W83-05a | 代码拆分：background/ 数据表移 core/data/oem/（996→953）+ FrameStatsCollector 会话段外提（249→208） | core-data/×5+feature-settings |
| W83-05b | 闸门批：#45 递归化 + #46 双文件 + **#52 对账接线** + #54 FileTools ≤600 / #55 PerfMon ≤600 / #56 FrameStatsSession ≤100 / **#57 ModelsViewModel ≤1400**（#53 避元 case 号）+ selftest case80e/86~90b | scripts/×3 |
| W83-05c | 守卫改号 #53→#57 勘误 commit | scripts/×2+docs |
| W83-06 | CI 同口径：unit-tests 改调 fulltest.sh + PR 门禁 ['**'] + baseline 818→830 | build.yml |
| W83-08 | 崩溃 handler（CrashSceneLogger 纯函数+3 单测）+ TTFT 文案披露 C 案 + 会话时长 label 订正 | app/+feature-chat/×4 |
| W83-07a | ChatMessageList colors 作用域修复（assemble 抓） | feature-chat/ |
| W83-07b | BackHandler 修复（真机抓） | feature-chat/ |
| W83-07c | selftest 夹具修正 + 4 测试失败修复 + 台账 W83-11~14 补登/8 项 closed-done | tests+scripts+docs |

@Test 基线 **818→830**（EscapeAudit +4 / ScanEscapeAudit +2 / TextToolProtocol +3 / CrashSceneLogger +3，逐文件对账见 build.yml 沿革注释）。

## 三、闸门结果（本地）

| 闸门 | 结果 |
|---|---|
| arch-guard（真仓） | **56/56 OK**（#52 成功静默包装修正后） |
| fulltest.sh | **830/0 BUILD SUCCESSFUL**（测试全绿；⚠️ rm test-results 在 Windows 偶发句柄锁 ⇒ 脚本按 W74-05 拒绝判绿，本地环境产物，CI 不受影响——详见 §七.8） |
| assembleDebug / lintDebug | 双绿（lint 新增 Kotlin 后按纪律跑过） |
| selftest 全量 | **本地未跑全量**（每 case 重跑 arch-guard.sh，spawn 开销 ×183 太贵；用户明确指导：本地只跑 `--only <新守卫号>` 验红绿面，全量归 CI）。本地冒烟 `--only 45,46,52,54,55,56,57` = **15 PASS / 2 FAIL**（两个 FAIL 都是 case80e，根因 = 本沙箱 safe-delete 包装拦掉 `rm -f`（mktemp 返回带 C: 盘符的 Windows 路径）⇒ 夹具没删成 ⇒ 守卫不红；**CI 全量实跑绿**见 §九，即该 case 在正常环境正确） |
| 真机验收 | 见 §四 |

## 四、真机验收批（W83-07，设备 13309cc8 回连）——四态如实

- **P1 ChatParamsPanel 落盘 ✅**：DataStore 硬判据（`files/datastore/liquid_agent_settings.preferences_pb` 直读）。scrim dismiss PASS；**BACK×2 PASS**（第一次收键盘、第二次到 BackHandler）+ 杀进程重进仍在。
- **P2 沙箱逃逸留痕 ✅（部分）**：端到端 1 条落盘 `沙箱逃逸被拒：工具=file_read 原因=含 .. 路径=../../etc/hostname`（ERROR 级持久化）。多路径多样性未取到——小模型跑偏反复调 memory_delete 烧完 8 轮（见 §七.2）；分键/节流逻辑由 EscapeAuditTest 5 例确定性覆盖。
- **P5 崩溃 handler ✅**：`am crash` 真崩，现场（线程名+异常类+cause 链+堆栈前 10 帧+最近 20 条日志）经 AgentLogStore.error 落盘可见。
- **P3 防抖四步 ⛔ 未走**：W82-07 设置页面，本波无改动（时间预算+设备锁屏事故挤占）。
- **P4 帧统计会话聚合 ⛔ 未走**：需完整 run+诊断页核对，同因未走。

## 五、外审条目处置台账（31 条回源风格）

- **已实施**：P0-1 登记裂缝（W83-01+脚本+#52 接线）/ W82-13 落盘（W83-02）/ LQ-1 收口（W83-03）/ 逃逸节流（W83-04）/ background 拆分（W83-05）/ fulltest 接 CI+PR 门禁（W83-06）/ P2-7 崩溃 handler（W83-08）/ Z-7 会话时长口径（W83-05a）/ Z-1 老文件补闸 #54/#55/#57（W83-05b）。
- **挂账 parked（新）**：W83-09 commitParams 丢更新面 / W83-10 LQ-2 逐例取证 / W83-11 SegmentedHistoryStore（**外审「零调用点」部分证伪**：AppContainer 有访问器+池但无生产调用方 ⇒ 运行期死代码）/ W83-12 下载完整性校验（外审 P1-1）/ W83-13 journal reconcile→reclaim+schema 版本（P2-2/P2-3 一石三鸟）/ W83-14 coroutines-test 可测性评审（P2-4）。
- **证伪/不成立**：无新增（本波外审条目基本全部成立或部分成立）。

> ⚠️ 勘误（W84）：W83-12 / W83-13 已在 W84 转 active（承接行 = W84-15 / W84-16），本节「parked」表述仅代表 W83 收尾时点状态。

## 六、挂账权威表更新摘要（docs/15-backlog.md）

- W83-01~08 八项 **closed-done**（销账判据逐行写明）。
- 新登 **W83-09~14**（四要素齐全，重启前提明确）。
- 续更节重写：本波全貌+守卫 51→56+@Test 818→830+W82-13/LQ-1 收口指向。
- `check-backlog-refs.sh` 复跑 **18 处申报全过**。

## 七、本波新坑与教训（铁律增补候选）

1. **自绘 sheet 的 BACK 键不过 onDismiss**（W83-02 真机抓）：`ChatParamsSheet` 是 AnimatedVisibility 非 ModalBottomSheet ⇒ 硬件 BACK 直接关窗不触发任何回调。「关抽屉」有三个物理路径（关闭键/scrim/BACK），**commit 边界必须三路全覆盖**——scrim+关闭键同路，BACK 独立，漏一路就是静默数据丢失侧门。
2. **真机驱动多轮 run 必须监控审批卡**（用户提示）：小模型会调危险工具（memory_delete）⇒ run 无限期挂起等授权，UI 上与「卡死」完全同型（生成 stats 冻结）。处置：dump 找「授权/拒绝」bounds → 勾「相同调用不再询问」→ 点「拒绝」→ run 续行。镜像应用：审批挂起也是续期取证的加速器（W78 铁律）。
3. **adb input text 空格要 %s 转义**；中文题面仍禁入（非 ASCII NPE）。
4. **BACK 第一次被输入法吃**：输入框聚焦时 BACK#1 收键盘、BACK#2 才到应用 ⇒ 关抽屉判据要按两次。
5. **uiautomator dump 在 Git Bash 下必须 MSYS_NO_PATHCONV=1**：否则 `/sdcard/...` 被转成 Windows 路径，dump 报成功但文件不在 ⇒ 定位全失败假象。
6. **设备灭屏/锁屏让 uiautomator 返回 null root node**：`svc power stayon true` + 每批前 WAKEUP/dismiss-keyguard。
7. **selftest 运行中编辑脚本 = 执行错位**（bash 增量读取）：`--only 46` 重跑途中改号补丁落了地 ⇒ case80e 假 FAIL。纪律：**跑着不改，改完不跑旧进程**（立即 TaskStop 再重启）。
8. **#52 包装必须成功静默**：被调脚本成功时打的 OK 汇总行会被 check() 当违反输出 ⇒ 55 OK+1 假红。包装范式：`out=$(bash x); rc=$?; [ $rc -eq 0 ] || printf '%s' "$out"`。
9. **新守卫编号要避 selftest 元 case 号**：#53 = 元 case（KNOWN_GUARDS↔arch-guard↔映射表↔case 四方一致性）专属 ⇒ ModelsViewModel 守卫取 #57，三处同步+KDoc 留纪律说明。
10. **Windows fulltest rm test-results 偶发句柄锁**（本波 4 次中 3 次）：上一轮 gradle 进程滞留句柄 ⇒ `rm -rf` 失败 ⇒ 脚本按 W74-05 拒绝判绿（**行为正确**）。本地对策：`./gradlew --stop` + 等 20s 重跑；CI（Ubuntu）无此问题。测试本身全绿（BUILD SUCCESSFUL + 830 汇总）。

## 八、下波建议（W84 候选，按优先级）

1. **W83-12 下载完整性校验**（外审 P1-1，用户点名"性能开销"同族的数据正确性）：importFromPath size 复核（JVM 可测）→ preset sha256 锚点 → 诊断页留痕。
2. **W83-13 journal reconcile→reclaim**（一石三鸟：W68-01 收口+磁盘治理+W82-17 schema 收敛；方案席一页纸先行）。
3. **P3 防抖四步 + P4 帧统计会话聚合补走**（设备在，各 15 分钟）。
4. **W83-14 可测性评审**（coroutines-test 走 bump 季程序）。
5. **W82-15 预热裁决**（产品裁决 A/B/C，C 案文案已落地止血）。
6. UI 波 U1–U5 仍待用户裁决（挂账中）。

## 九、CI 实跑记录

- push：`0018748..a3f41e2 core-fix -> core-fix`（13 commits）。
- **CI run 40202269171（push，head_sha=a3f41e2bf7322b13df1ba6cfbd730ee9b56293ca）三 job 全 success**：
  - Unit tests（121195750792）：success —— 内含 Architecture guard（arch-guard.sh 真仓 56/56）+ **Architecture guard self-test（全量 selftest，含新增 case80e/86~90b；脚本有 FAIL 即 exit 1 ⇒ CI 绿 = 全量真绿）** + fulltest.sh 830/0（W83-06 接线后 CI 与本地同口径的首跑）。
  - Lint (baseline gate)（121195750959）：success。
  - Assemble Debug (JDK 21)（121195751086）：success。
- **本地 vs CI 差异如实申报**：本地 `--only` 冒烟 case80e 两 FAIL 是**沙箱环境产物**（safe-delete 拦 `rm -f` 带盘符前缀的 mktemp 路径），非脚本缺陷；CI 全量绿为权威判据。教训：删夹具类 case 的本地可跑性受 agent 沙箱 rm 包装影响，判绿以 CI 为准。
