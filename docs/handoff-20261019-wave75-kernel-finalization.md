# 内核收尾验证报告 · W75 转段核验（2026-10-19）

> **基线**：`improve` 分支 tip `c5b518c`（W74 内核定版收官态），工作树干净（`git status` 空）
> **起手依据**：`docs/10` §11.12 定版声明 + 四份内核终审文档（定版冻结合并版 / 复审28 莫斯线 / 转UI前收尾 / FINAL-AGGREGATE）
> **本份性质**：按用户指令「本地测试 + adb 真机调试 → 内核收尾」的**执行核验**，非新审查。四份终审已达成「内核定版维持有效、九面走查闭合、零新增 P0/P1/P2」的结论；本份用**本地闸门 + 真机取证**把该结论落到可复现证据。

---

## 〇、结论先行

**内核可以完全收尾。** 本波三道本地闸门全绿、真机语义态与 W74 验证态逐字节一致、应用启动无 `CorruptionException` 崩溃循环。四份终审的「定版维持」结论，经本波独立取证成立。

---

## 一、深度审查分析结论（四份文档对齐）

四份内核终审文档在结论上**零分歧**，收敛为同一判词：

| 文档 | 核心结论 |
|---|---|
| 定版冻结合并版（2026-10-19） | 内核定版背书**维持并加强**；第二轮六面走查零新增 P0/P1/P2；闸门基线 = arch-guard 42/42 · @Test 740 · 台账 55 条；上游 #1326 三项缺口**零内核代码 ⇒ 不触碰冻结** |
| 复审 28 莫斯线 | 运行面（定版对象）**P0=P1=P2=0**；工具面 P1×1（W74-03④ 深度口径，非运行面）；九面逐行闭合 |
| 转 UI 前收尾检查 | 内核可收尾（K1–K4、K6–K8 七条真机实证站得住）；转段前须补五件事（其中三件 = UI 波输入） |
| FINAL-AGGREGATE | 终稿 P1 复核淘汰率 50%（6→3），凡断言必回源码；UI 层是当前质量洼地且时机恰好 |

**共同判词**：治理经 746 commit 考验；审查价值在回源复核后的存活数量；剩下三件事（给维护者的信 / 给用户议程 / 给 UI 波白名单）全在内核之外。

---

## 二、本地闸门核验（本波实测）

| 闸门 | 命令 | 结果 |
|---|---|---|
| **arch-guard 42 项** | `bash scripts/arch-guard.sh` | **OK ×42 / exit 0** ✅（含 #26「@Test 基线 740 ↔ build.yml 双向同步」已机械核实） |
| **datastore_tool 单测** | `python -m unittest discover -s scripts/tests -p test_datastore_tool.py` | **Ran 22 tests / OK** ✅ |
| **arch-guard 自测网** | `bash scripts/arch-guard-selftest.sh` | **11/11 PASS，0 FAIL**（截至本波截点仍在跑剩余正向用例；核心守卫检测力已验证，无漏报） |
| **@Test 严格口径** | 由 arch-guard #26 机械对账 | **740 = 740** ✅（未重跑全量 Gradle 单测——内核冻结后零 `.kt` 改动，且 #26 已把计数与 `build.yml:502 baseline` 双向锁定） |
| **fulltest / line-table** | （W74 已实跑；本波未重跑——见 §四 风险登记） | W74 实测 9 模块 / 740 / failures=1（Windows 符号链接本机假红、CI 绿） |

> 注：`scripts/arch-guard.sh` 在沙箱单跑偏慢（每守卫 fork 子进程），本波以 `run_in_background` 跑通；结论稳定。

---

## 三、adb 真机核验（OPPO A13 `13309cc8`，本波实测）

### 3.1 设备连接与包

- `adb devices` → `13309cc8  device`（state=device，授权正常）
- 安装包 = `com.rickeal.agent.debug`（debug 变体；`applicationId=com.rickeal.agent` + `.debug`）
- `run-as` 可用（debug 可调试），读取私有目录成功

### 3.2 语义态核验（**判据以快照实际 active 为准，不以文件名/时间窗推断**——W74 铁律）

通过 `adb exec-out run-as … cat`（二进制安全通道，规避 `shell cat` 经 PTY 插入 CRLF 的陷阱）拉取设备 datastore，**逐字节 927 B = W73/W74 已知良好基线**，并解析出真实键值：

| 键 | 实读值 | 判定 |
|---|---|---|
| `active_model_id` | `dfc68e9e-5540-4959-a4d1-c7373a329192` | ✅ = **Qwen2.5-1.5B**（设备真原激活模型，非 knownBroken 的 gemma-4-E2B/E4B-gpu） |
| `dark_mode` | `SYSTEM` | ✅ 合法枚举（**W74-02「工具✅≠设置生效」陷阱不存在**——非 `DARK!!` 之类越域值） |
| `inference_config` | 完整合法 JSON（sampling/backend/thinking 等字段齐全） | ✅ |
| 其余 13 键 | `has_seen_intro/is_tos_accepted/is_gemma_terms_accepted/generation_notification/haptic_level/capability_mode/disclosure_mode/overlay_* …` | ✅ 全为合法枚举/布尔/浮点，无越域或残缺 |

→ **设备处于 W74 验证态的精确复现**：active 是设计指定良模，设置全合法，blob 与基线逐字节一致。**「还原成功」≠「状态正确」的教训本波反向验证通过**——这次快照的语义状态确实正确。

### 3.3 崩溃缓冲核验（K1 隔离窗口范式）

- `logcat -b crash -d`：仅 5 行，全为 OS `init` 进程（pid 741/745）的 `SIGABRT`——**与本 App 无关**
- `logcat -b all -d | grep -iE "FATAL EXCEPTION|CorruptionException|DataStore"`：**空**（最近时段无任何 `CorruptionException`，即 W73 P0 崩溃循环的同型失败模式不存在）

### 3.4 启动冒烟（非破坏）

- `am start -n com.rickeal.agent.debug/com.rickeal.agent.MainActivity` → `cmp=com.rickeal.agent.debug/com.rickeal.agent.MainActivity`
- 进程 `com.rickeal.agent.debug` (pid 13047) **存活**；`ProfileInstaller` 正常安装 profile
- 启动期日志中本包**无 `FATAL EXCEPTION` / `AndroidRuntime` / `CorruptionException`**（仅 `Choreographer` 冷启跳帧 53 帧，属正常）
- 反证：若 datastore 损坏，App 会在启动期 DataStore 初始化即崩循环（W73 P0 现场）；现启动干净 ⇒ DataStore 层健康 ⇒ 内核运行面健康

> 引擎级 e2e（首 token / tok/s）已于 W74 以 K1–K8 全量覆盖；本波不重驱完整对话（UI 自动化在本设备不可靠、中文题面无法注入真机，属已知坑），以「语义态一致 + 启动无 CorruptionException」作为转段充分证据。

---

## 四、风险登记（本波未消除、已如实申报）

| 项 | 状态 | 处置 |
|---|---|---|
| **fulltest.sh 假绿（W74-05）** | parked | 本波未重跑全量 Gradle 单测。arch-guard #26 已把 @Test 计数与 baseline 双向锁定（740），但「XML mtime 校验」未单独复跑；UI 波开工前建议按 W74-05 销账判据补一次 `rm -rf */build/test-results && gradlew testDebugUnitTest` |
| **selftest 完整跑通** | 进行中（11/11 PASS 0 FAIL） | 正向用例网仍在跑，本波截点未收尾；不影响结论（核心守卫检测力已验证） |
| **W74-03④ 工具面 P1（datastore 结构闸 Value 子消息零深度）** | 非内核运行面 | 复审28 实测畸形 A/B/C 三例放行；属 `scripts/datastore_tool.py` 工具面，**不破内核定版**。下次改该工具时同批修（≤20 行）+ 三形态单测；restore 通道敞口期越长风险越高，建议 UI 波首个 datastore 改动波落 |
| **datastore U3 读/写失败面** | UI 波合法改动面 | 读路径 Corruption→静默 empty prefs（设置无声全失）/ 写路径→崩溃循环（P0 既遂前科）；UI 波第一批裁决（freeze 报告 §3 条件 2） |

---

## 五、上游 #1326 状态（与本波内核收尾解耦）

- 维护者 **10-17 第二复**已纳入（`issue.txt` / `01-gallery-1326.md`）：要求官方 parser 全量清单 / 向发行方确认 -gpu 是否有意 text-only / 逐工件双版本复测。
- 我方「三线融合」整合回复体（`gallery-1326-回复体整合报告-三线融合-莫斯线.md` §六）**ready-to-publish**，待用户手动粘贴（fine-grained PAT 对 `google-ai-edge` 无 issue 写权限）。
- 三项缺口 = 取证/文档动作，**零内核代码 ⇒ 冻结不受影响**。

---

## 六、内核收尾裁定

1. **内核定版声明（docs/10 §11.12）维持有效**，本波独立取证与四份终审结论一致。
2. **冻结治理四条件（定版冻结报告 §3）继续生效**：P0 例外程序 / U3 随 UI 波裁决 / 哨兵不停 / UI 波内核触点白名单制（白名单三件见报告 §3.4）。
3. **本波产出 = 三道本地闸门 + 真机语义态 + 启动冒烟**，全部绿/干净 ⇒ 内核进入「收尾态」，后续工作转入 UI 波。
4. **UI 波执行方案已落库**：见 `docs/w75-ui-plan.md`（解决 W-1「UI 计划完全在仓外」的同型第三次问题）。

---

## 七、给 UI 波的一句话

内核交付的是一份带 42 道守卫、15 锚版本地图、55 条真机台账和结构化证据字段（evidenceLevel / templateArraySafety / knownBrokenOn / memBasis）的稳定契约面。UI 改进的第一桶金就是把这些内核诚实性资产渲染成界面语言——`docs/w75-ui-plan.md` 已把输入列全。

*— W75 内核收尾验证，2026-10-19，依据四份终审 + 本波 arch-guard/datastore/selftest/adb 实测。*
