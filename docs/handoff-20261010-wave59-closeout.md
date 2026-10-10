# W59 波次收官交接（Wave 59 Closeout）

> 基线：W58 收官 `b8b9446`｜本波 tip = `93d8f96`（6 commit 已推，CI 回填见 §八）
> 前置文档：`docs/handoff-20261010-wave58-closeout.md`（§六 = 本波挂账来源）
> 方案：`_plans/w59-strategy.md`（仓外，方案席沈思远，全部行号实测于 b8b9446 工作树）｜审查：`_plans/w59-review.md`（仓外，审查席严质衡）｜真机取证：`_litert_forensics/_w59_smoke/`

---

## 一、一句话 + 关键现实

**W59 = Stage-2 候选 2「加载集群」拆分波（引擎 2345→1970，余量 55→430）+ A3 契约固化 + A4 token 幂等 + 守卫 #30/#31 + 一行件包 + 修补 B UI 可达性 + bump 前置⑥成文；真机批部分行使（安装+冷启动过，文本 run/翻转复位因设备 tap 注入失效挂 W60）。**

关键现实（动手前必读）：
1. **引擎余量重置**：`LiteRtLmEngine.kt` **1970/2400（余 430）**，新文件 `LiteRtLmEngineLoader.kt` **704 行**（无行数守卫钉它——是否补守卫挂 W60 裁量）。`AgentRunner.kt` 2802 未动（#31 钉 ≤2900）。
2. **A3 契约已机械钉死**：「切开关不触发引擎重建」从注释语义升级为 `EngineSameEngineContractTest` 2 例（仅 `nativeToolChannel` 异 ⇒ same=true；backend/contextLength 变 ⇒ false）——未来把开关塞进 `sameEngine` 判据会立即红。
3. **A4 token 幂等防御已落**（generation-scoped）：`handleTemplateRenderFailure` 过期世代处置丢弃（证伪置位/计数++/persist 写点②全在门控后），K 生效前置承诺兑现；当前代恒等 ⇒ 行为面零变化（机制先行，真机行使面挂 W60）。
4. 🔴 **真机 tap 注入通道本波升级为「设备级失效」**：launcher/本 app 全坐标 tap、swipe、motionevent 全部无效，keyevent 正常——W57 tap 可用 → W58 后期失效 → W59 全失效，**W58 判据「充电态 tap 不稳定」应升级为「USB 充电态 tap 不可靠，多步剧本优先无线 adb（本波已验证 `adb tcpip 5555` + `connect` 可用）或物理交互」**。设备现存 `show_touches=1`（背景，因果未定）。
5. 用户对「无线 adb 后继续」裁夺为**跳过剩余真机批**（冷启动判据已达成），文本 run/翻转复位挂 W60。

---

## 二、改动清单（6 commit）

| commit | 性质 | 内容 |
|---|---|---|
| `9a75526` | refactor(engine) | **Stage-2 拆分 + A3 同 commit**：加载集群（`load`/`loadLocked` 313 行/GPU 两段尝试/模态降级族/加载态字段组 ≈390 行）外提 `LiteRtLmEngineLoader.kt`（704 行，含全部 KDoc/接缝申报）；三接缝 = ①`releaseInternal` 顺序契约（引擎侧保留 + `loader.releaseLoadState()` 下沉，两 close 相对顺序逐字保持；无读穿插的字段清零按会话/加载重新分组——审查席逐字段对账等价成立）②`buildSession` lambda（无早退/无挂起差异）③开发席补申报 `onResampleSessionReset`（短路分支「仅采样参数变化」动全会话态，经回调回引擎原样执行）；A3：`resolveVisionBackend`/`resolveAudioBackend`/`isSameEngine` 三纯函数单源化（判据签名**不收 nativeToolChannel**——契约用签名缺席表达）+ `EngineSameEngineContractTest` 2 例 + 复位块注释因果申报；重锚清单两类分列（行号锚改符号引用；#24/#28 迁出区间代码位命中实测 0，计数零改——拆分后实跑 4==4 / adopt 1 / persist 2 复核）；baseline 641→643；selftest PASS=77 维持 |
| `cbf024a` | feat(engine) | **A4 token 幂等**：`generationSeq`(AtomicInteger)/`currentGenerationToken`(@Volatile Long)；flow 首行（复位块之前）自增；`handleTemplateRenderFailure` 首参 token 校验，过期世代「处置丢弃」info 日志；onError 闭包 + 同步 catch 两调用点传局部 token；复位块注释申报关 P3#6/#7；开发席修正 Int→Long 需 `toLong()`（方案笔误，commit message 申报） |
| `031fb17` | test(guard) | **守卫 #30**（`ChatRunCoordinator` run 级 `model = uiState.value.activeModel` 读点冻结 ==3，:539/:721/:864 实测；红输出含复合键迁移前置提示）+ **守卫 #31**（AgentRunner ≤2900，#25 家族第 4 员，触顶=启动拆分评审语义）+ selftest case41/41b/41c/42/42b/42c/42d（红面含违规事实行真命中断言；#30 scaffold 铺满 3 处） |
| `a5f9a85` | fix(settings) | **修补 B UI 可达性**（深度审查 H-1 方案①）：原生工具通道开关副文案（ON/OFF 双分支）补可执行序列「关闭后需先发送一条消息，再重新开启，原生通道才会重试」——纯文案零行为变更 |
| `264aad7` | fix(engine)+docs | **一行件包**：P3-Ⓐ 锚定面清单命名「前置⑥」+ #29 红输出加指向行（超集追加，case39 家族实跑零改）｜P3-Ⓑ 两处 KDoc 补「bump 前临时 K=3 已定」｜P3-Ⓒ `clearAll` KDoc 补跨 cid 连带面两句+取舍论证｜P3-Ⓔ `ModelPresets` #41→#42｜`SwitchResetTest` 补 (null,false)/(true,false) 2 例（**纠偏：审查任务书猜的两例已有，实缺这两组**，6 组合穷尽）；baseline 643→645 |
| `93d8f96` | docs | **bump 前置⑥成文**：`docs/12-litertlm-0171-anchors.md`（十二项 0.17.1 行为锚定面，第六审 A5 表为底稿，每项标定案波次与依据；minijinja=2.14.0 标「上游版本锚」待 bump 复核）+ H-3 清键钩子启用时点成文（「待删除会话 UI 立项激活，休眠不撤」） |

⇒ **@Test 实数 = 645**（641→643 契约测试 +2 → 645 SwitchReset +2），`build.yml` baseline **=645**（两步同步，#26 双向绿）。**守卫 29 → 31 项，selftest PASS 77 → 84**（+7：case41 家族×3 + case42 家族×4）。

行数预算：引擎 2345 → **1970**（迁出 ≈390 − 委托/接缝 ≈15）；Loader **704**（高于方案预估 440：保留全部 KDoc/申报注释）；本波行为面新增 ≈ +20（A4 + P3-Ⓑ）⇒ 拆分后 ≈1990 实测 1970。

---

## 三、真机验证（本波判据）

### 3.1 ✅ 台账 #44（⚠️ 部分行使）：W59 构建真机冒烟（冷启动段）

- APK = W59 工作树构建（`93d8f96`，localbuild assembleDebug 16:24 BUILD SUCCESSFUL）；`adb push` → `pm install -r` **Success**（`lastUpdateTime 2026-10-10 17:08:21`，包 `com.rickeal.agent.debug`）。
- 冷启动（monkey LAUNCHER）**FATAL EXCEPTION = 0**（设备侧全量档 15.3MB 已落 `_litert_forensics/_w59_smoke/w59_smoke_full.log`，app 相关行 899 条先验在档 ⇒ 「0 命中」有意义）。
- ⛔ **文本 run / 换模型真重建 / 翻转复位端到端未行使**（见 3.2）。

### 3.2 ⚠️ 如实申报：行使面中断（tap 注入设备级失效）

- 时间线：解锁（用户手动）→ app 前台 → tap「开始一段对话」（dump 精确 bounds (540,682)）×6 种姿势（tap/swipe-in-place/motionevent DOWN+UP/双 tap/重启 app 后重试/launcher 交叉验证）**全部无效**；`input keyevent`（APP_SWITCH/WAKEUP/BACK）正常；dumpsys 确认屏幕 ON + keyguard 未锁。
- 结论：**触摸注入通道设备级失效**，非 app 问题、非锁屏问题（launcher 上 tap 同样无效）。W57→W58→W59 递进恶化，与 USB 充电态强相关。
- 已验证的替代通道：**无线 adb**（`adb tcpip 5555` + `connect 192.168.0.20:5555` 成功，pull 验证可用）；用户裁夺跳过（本波真机批收窄），挂 W60。
- 补偿覆盖：拆分主行使路径（加载集群）在冷启动已部分行使（app 正常启动至首页 = Loader 类装配成功）；文本 run 与换模型重建判据挂 W60 首位。

### 3.3 未覆盖（挂 W60）

- 文本 run 两轮（收尾路径回归）｜换模型触发真重建（加载集群主行使）｜翻转复位 OFF→ON 端到端（新副文案截图 + warn 判据）｜多模态剩 2 容器 L3（`Qwen2-VL-2B` / `LFM2.5-VL-3B fixB`）。

---

## 四、审查对账

- 审查席报告（`code-quality-reviewer`，`_plans/w59-review.md`）：**结论 = ✅ 通过（0 P0 / 0 P1 / 0 P2 / 4 P3）**。
- 关键核验：**接缝①逐字段对账等价成立**（老 releaseInternal 26 条语句 = 新引擎 15 + loader 11，每条恰出现一次；两 close 相对顺序保持；重排 8 条区间无读穿插；`releaseLoadState` 不取锁 ⇒ 回调不重入死锁）；A3 契约测试非 vacuous；A4 token 链完整（自增在 flow 首行、恰 2 调用点同代、三件处置全在门控后）；数字独立复算全吻合（@Test 645 真注解口径 / baseline 两步同步 / 引擎 1970 / Loader 704 / AgentRunner 2802 / model 读点恰 3）。
- **P3×4（不阻塞，处置挂账）**：
  1. 契约测试例 2 为双变异（backend+contextLength 同改，钉面弱化）——系方案原文自带，W60 拆单变异补 1 例；
  2. 守卫 #30 计数判据对 KDoc 提及无排除（红=有意动作化已申报，观察项）；
  3. `docs/12` #12「上游版本锚」表述语义摆动（bump 复核时统一）；
  4. A4 无直接单测（native 重类 JVM 不可实例化，已如实申报）——真机批行使。

---

## 五、挂账（W60 起；权威版见本节 + README）

1. **真机批（首位，wireless-adb 剧本）**：文本 run 两轮 + **换模型真重建**（加载集群主行使）+ 翻转复位 OFF→ON 端到端（判据 = warn「用户重新开启，已清证伪」+ 随后 run 原生态生效 + 新副文案截图）。
2. **多模态剩 2 容器 L3**（`Qwen2-VL-2B` 异族 / `LFM2.5-VL-3B fixB` 同族最贵；`_w57_l3` 剧本）。
3. **审查 P3**：契约测试例 2 拆单变异补例｜#30 KDoc 误红观察｜docs/12 #12 表述统一｜A4 真机行使。
4. **bump 重启监视**：上游 tag 季扫（前置④）+ 前置⑥清单（docs/12）逐项复核后才动 toml（#29 红输出已加指向）。
5. 沿袭：K 回填（等 bump 或剧本灌 N 次）｜`DeepSeek-R1` 误判解除阻塞后 AUTO 验证｜会话删除 UI 立项（激活 delete 卫生位）｜上游 issue 提交｜selftest 快慢网拆分评估（本波 PASS=84 耗时 33min 继续走陡）｜`Loader` 行数是否补守卫（704 行无钉）｜N-W3 `evidenceLevel`｜`TokenUsage.estimated`｜法务 `termsVersion`｜0 tags｜main 快照｜fulltest 接 CI。

---

## 六、下次接手须知

1. **静态闸门基线（本波后）**：`arch-guard.sh` **31 项**；`arch-guard-selftest.sh` **PASS=84 FAIL=0**（本机 ~33 min）；`fulltest.sh` = **tests=645 failures=1**（唯一失败 = core-data `SandboxFileScannerTest` Windows 符号链接，既有）；引擎 **1970/2400（余 430）**；`AgentRunner` 2802（#31 钉 ≤2900）。
2. 🔴 gradle 本机环境：`JAVA_HOME=_j2env/jdk/jdk-21.0.12.1+1` + `GRADLE_USER_HOME=_j2env/gradle-home` + `--offline`（默认 GRADLE_USER_HOME 会联网拉发行版失败）；`_ci-tools/localbuild.sh` 在**工作区根**不在仓内。
3. 🔴 **真机 tap 教训升级**：USB 充电态 tap 不可靠（W59 设备级失效实证）；**先切无线 adb**（`tcpip 5555` + `connect <ip>`，本波已验证），仍失效再考虑重启设备；`show_touches=1` 是设备现存设置。
4. 🔴 journal 目录按会话键控：「新目录」判据只适用首轮；同会话看新 `run_*.jsonl`。
5. 🔴 复用真机剧本时先 `pm install -r` 后核 `lastUpdateTime` 确认装的是新包（本波 17:08:21 实证）。

---

## 七、CI run id

> **推送后回填**（W52 教训：立即回填）。

| workflow | run id | 结论 | 关键 job |
|---|---|---|---|
| **Build** | `38046679587` | ✅ success（head `93d8f96`） | Lint / Assemble Debug / Unit tests 全 success |
| **Release** | `38046679607` | ✅ success | — |

**CI 内实测（job log 直读，PAT `actions:read`）**：
- `Assemble Debug` job：`架构守卫全部通过。`（31 项）+ `自测结果：PASS=84 FAIL=0`。
- `Unit tests` job：`baseline=645`（soft-check 未触发 ⇒ tests ≥ 645）。
- 本文档 commit 为 docs-only（`paths-ignore` 覆盖 `docs/**` + `*.md`）⇒ 预期 **0 run**。

---

## 八、复审 18 建议对账 checklist（8 条）

| # | 复审 18 建议 | W59 处置 | 态 |
|---|---|---|---|
| 1 | Stage-2 候选 2 升格开波即执行 + 锚连锁重锚清单 | `9a75526` 首刀执行；重锚两类分列（行号锚→符号引用；守卫锚实测零改）；@Test 口径差异因审查中已澄清 | ✅ |
| 2 | onError 幂等防御置顶 | `cbf024a` 落地（generation-scoped token，K 生效前置承诺兑现） | ✅ |
| 3 | K 论证件与分布灌测合并窗口 | 未做（勿挤占拆分，按建议顺延）——K 拍值口径两处 KDoc 已同步（P3-Ⓑ） | ⛔ 不适用（按建议顺延） |
| 4 | 守卫 #30（复合键防误入） | `031fb17` 落地（读点冻结 ==3 + 红输出含复合键前置提示） | ✅ |
| 5 | AgentRunner 行数守卫补盲 | `031fb17` #31 落地（≤2900，#25 家族第 4 员） | ✅ |
| 6 | 一行件包（P3-Ⓐ/Ⓑ/Ⓒ/Ⓔ + SwitchReset 补例） | `264aad7` 全落（SwitchReset 实缺两组合经实测纠偏后补齐 6 组合穷尽） | ✅ |
| 7 | 真机补验（翻转复位 + 2 容器） | 冷启动冒烟达成；行使面因 tap 设备级失效 + 用户裁夺跳过 ⇒ 挂 W60（无线 adb 剧本已验证） | ⚠️ 部分 |
| 8 | 协作面（口径标注/引擎行数口径/子项级对账） | 本表即行使；@Test 一律标注口径；触顶预估统一引擎行数口径 | ✅ |

**深度审查 H-1 对账**：方案①副文案已落（`a5f9a85`）；②③（时间戳复位/重试按钮）挂账裁量。
**第六审 A3 对账**：sameEngine 契约固化随拆分同 commit 落地（纯函数签名缺席 nativeToolChannel + 2 例单测 + 复位块因果注释）——本波最优先修补件，✅。
