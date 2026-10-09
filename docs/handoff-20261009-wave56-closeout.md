# W56 波次收官交接（Wave 56 Closeout）

> 基线：W55 收官 `985719b`（W55 共 10 commit，其 CI 触发推送 = `19c1dab..6efc0fb`；docs-only `60b86f4` 起实测 0 run）｜本波 tip = `improve` 分支最新（W56 commit 数见下；**CI 触发推送 = 推送后回填**）
> 前置文档：`docs/handoff-20261009-wave55-closeout.md`（W55 逐项记录）
> 方案：`_plans/w56-phase1-strategy.md`（仓外，方案席沈思远）｜真机取证：`_litert_forensics/_w56_tmpl/L3-RESULT.md` + `POISON-AB-RESULT.md`（仓外）

---

## 一、一句话 + 关键现实

**W56 = 多模态 L3 端到端真机打通（首臂通过）+ 毒化防护链首次真机行使（三件套全中）+ 进程级韧性 store 落码 + litertlm bump 正式挂起（前置④红）。**

关键现实（动手前必读）：
1. **litertlm bump 被正式阻断**：最新 tag **v0.18.0**（`b2f686e2e`）源码全树（*.cc/*.h/*.rs）`git grep` **零命中** `MessageToTemplateInput` / `requires_typed_content` / `convert the content to a string` ⇒ 「1 元素 text 数组→string」收敛已删且**无等价替代** ⇒ bump 过删除点 = fold 失效 = P1 静默回归（`#24` 守卫只钉调用点存在性，拦不住行为级漂移）。
2. **多模态从「未覆盖风险」转为「已验证能力」**（仅实测容器）：`LFM2.5-VL-450M_int8` 在 0.17.1 真机发图全链路打通；**同族不外推**（同家族 ≠ 同模板）。
3. **M（折叠后元素数）是模板炸点唯一维度，且 M≥2 存在不经 fold 的生产可达路径**（同参护栏提醒 + 工具结果 = 2 元素回灌，W56 Arm B 实测炸 `:23`）——该路径 fold ON 下被收口，fold 失效场景（如 bump）下即炸。
4. 本波 dev 自述质量显著改善（主动申报 vacuous 与题面缺陷）；审查席结论**通过**（1 P2 + 2 P3，无 P1；P2 = JVM 单测口径偏宽，已在交接收窄）。

---

## 二、改动清单（3 commit；待 CI 回填后补第 4 个 docs commit）

| commit | 性质 | 内容 |
|---|---|---|
| `a7975fe` | feat(engine) | **进程级会话韧性 store**（6 文件 +257/-8）：`EngineResilienceStore` 接口 + `ProcessEngineResilienceStore`（core-engine，cid 键控、进程级内存）；`DefaultEngineFactory` 构造注入；`AppContainer` 唯一组装点；`LiteRtLmEngine` 双写双读（写点 = `handleTemplateRenderFailure` 收尾 `persistResilienceToStore`；读点 = `ensureConversation` 首行 `adoptResilienceFromStore` 单调合并 OR/max，先于通道判定）；`conversationDirty`/`awaitingNativeToolResponse` **保持实例级**（方案明确：跨实例持久反而引入 stale 置位）；软熔断 `TEMPLATE_REBUILD_FUSE_THRESHOLD = Int.MAX_VALUE`（**K 勿拍值**，KDoc 标注待分布回填，行为与 W55 一致）；新增 `EngineResilienceStoreTest` 3 例，`build.yml` baseline **624 → 627** |
| `900f2df` | docs | bump 挂起落档：fold 出口注释补「W56 挂起裁定（v0.18.0 tag `b2f686e2e` grep 零命中 ⇒ 前置④不通过）+ 重启条件两条」；W55 交接 §五.4 尾部追加（不重写历史） |
| *本提交* | docs | 台账 **#39/#40** + §11.0 聚合句 **38 → 40 = 33 ✅ / 5 ⚠️ / 2 ⛔** + README 波次段 + `ModelPresets` 450M 条目升级为「端到端已真机验证（W56 L3）」+ 本交接文档 |

⇒ **@Test 实数 = 627**（新增 store 单测 3 例），`build.yml` baseline 已由开发席同步 624 → 627（#26 双向同步）。

---

## 三、真机验证（本波核心判据）

### 3.1 ✅ 台账 #39：多模态 L3 端到端（LFM2.5-VL-450M_int8）

- 容器完整性：双源全文件 SHA256 一致（`f941a5f9…106dda`）。
- **push 直放一等公民实测成立**：`adb push` 至 `files/Download/` → 冷启动自动登记（模型页「共 6 个」）→ `vl` 文件名 ⇒ `ModelHeuristics` 图片能力位自动命中 → 附件菜单出现「只能添加图片」提示。
- 会话创建成功（MiniCPM-V-4 式 `Unsupported model type` **未复现**；native loader 确认 vision encoder 在档）。
- 发图 run：`折叠前 2 → 折叠后 2；Text=1/Image=1`（**fold 硬边界不收口** ⇒ 模板 `for item in content` 真实行使）｜`Failed to apply template` = **0**｜`settled=ModelStopped(rounds=0)`｜无 FATAL｜native 行 14 条。
- 内容判别：模型答出**黄条 + 红色主背景**（与测试图双锚点逐点相符；⚠️ 数字 "37" 未读出 = 450M OCR 弱，非管道问题）。性能 in 856/out 132 · 23.1 tok/s · 首字 4.5s。

### 3.2 ✅ 台账 #40：毒化 A/B 防护链

- **Arm A（fold ON，含 store 新代码）**：3 tool_call 回灌 `折叠前 3 → 折叠后 1`、0 模板失败 ⇒ 回归通过 + store 新代码真机无副作用。
- **Arm B（fold 全局 identity 临时补丁，测后已还原 `git status` 干净）**：
  - round 0 单 tool_call 回灌 **M=1 收敛不炸**（H-A 单元素臂再证）；
  - **M=2 炸点复现**（同参护栏提醒 + 工具结果 = 2 Text，**不经 fold 的回灌路径**——方案 §2.2 预判的生产可达空白被实证）⇒ `Failed to apply template … (in template:23)`；
  - **自愈三件套首次真机行使**：`（下发 role=user）（会话重建 #1）（来源=同步下发）`——W54 计数 + W55 同步自愈 + role 观测面同时命中；
  - 引擎重建 → 重试成功 → **`正常结束：3 轮` 未复炸**。
- ⚠️ **如实申报（审查 P2 口径收窄）**：store「跨实例计数累加」真机判据 **vacuous**（仅 1 次失败）；**JVM 单测只覆盖 store 类语义（读写/cid 隔离），引擎侧 `adoptResilienceFromStore` 读回接线零覆盖**。直接行使需「fold 失效 + 连续两轮 M≥2」（bump 回归场景）。
- 附带真机行使（全按设计工作）：同参调用守卫/护栏、工具连击熔断、熔断救援不落库、审批「相同调用不再询问」。

---

## 四、技术内容：进程级韧性 store（治 W55 审查 P2#1）

- **问题**：W55 落地的自愈置位（`nativeToolsRejected`/`templateRebuildCount`）是**引擎实例级**字段；`AgentRunner` 首败即 evict+新建 ⇒ 置位随旧实例清零 ⇒ 用户每次 rebuild 都重走必炸路径、熔断计数永远归零。
- **修法（方案 A）**：载体从「即将被 evict 的实例」挪到「活过 evict 的进程级 store」——cid 键控快照读写；实例字段保持读速路径（双写）；读点在 `ensureConversation` 首行（cid 建会话时才可知，且必须先于通道判定才拦得住首炸——**与方案「初始化读回」的偏差已申报并被审查接受**）。
- **边界（审查 P3，KDoc 待补一句）**：同实例先后服务多 cid 时证伪不回退（单调 OR）；预存行为 W56 未加重，evict 后更干净。
- **回滚**：单 commit revert（纯增量）。

---

## 五、坑复盘（真机操作，跨波复用）

1. 🔴 **A13 `MEDIA_SCANNER_SCAN_FILE` 广播不生效**（result=0 但媒体库不收录）⇒ 必须 `content call --uri content://media/ --method scan_file --arg <path>`。
2. 🟡 SAF「图片」collection 不索引 Pictures 目录新增文件（须 scan）；Download collection 按 MIME 过滤显示「没有任何相符项」是 scan 未完成的表象。
3. 🔴 **键盘弹出后输入栏整体上移**：发送键 y 从 ≈1968 移到 ≈900–1350（视布局）；tap 旧坐标 = 点空 ⇒ **消息留输入框、run 不启动、journal 无新目录**。判据：发送后必须核 journal 新 run 目录存在。
4. 🔴 **应用商店弹窗抢前台吞 tap**（`com.heytap.market`）：审批对话框 tap 可能打进商店 ⇒ 「授权成功」必须以**对话框消失 + diag 推进**双验；必要时 `am force-stop com.heytap.market`。
5. 🔴 uiautomator 对 Compose 的 clickable bounds 错位（模型卡片主体报在视口上方）⇒ **tap 副作用必须 datastore/pb 实读验证**（如 `active_model_id`），不能信 dump 反馈。
6. 🟡 `--rerun-tasks` 才是真跑（UP-TO-DATE 是假跑）——JVM 单测判据一律带此参。

---

## 六、挂账（W57 起；**权威版**见本节 + README）

1. **Bump 重启监视**：上游 tag 季扫（恢复「1 元素收敛」即重开 bump 评估，四前置重走；当前四前置 ①✅ ②✅ ③并入 L3 已 ✅ ④❌ v0.18.0 无收敛）。
2. **store 引擎侧读回接线覆盖**（审查 P2）：补「同 store 跨引擎实例读回」JVM 用例；真机行使挂 bump 场景。
3. **store KDoc 边界句**（审查 P3）：「cid 天然隔离」与单调 OR 的张力补一句；`ConcurrentHashMap` 全限定名改 import 风格（P3）。
4. **软熔断 K 回填**：待「fold 失效 + 连续 M≥2」分布数据（bump 场景）。
5. **多模态**：其余 4 视觉容器端到端（SmolVLM2-500M / LFM2.5-VL-1.6B fixB / Qwen2-VL-2B / LFM2.5-VL-3B；两候选容器已下载校验于 `_research/w56_l3/`）；「只见图片顶部 1/4」上游 bug（LiteRT-LM#3246）在 int8 上未复现但单图样本不足以排除。
6. **N-W3** 预设 `evidenceLevel` 数据化 + 守卫（并入预设扩容波；「图片入口硬闸门」并此）。
7. 其余沿用 W55 §五：`DeepSeek-R1` 误判 `thinking=false`（无容器不得盲改）｜数据/阈值回填｜`TokenUsage.estimated` 治根｜通知文案「渠道」误导｜法务 `termsVersion`（先于法务文本替换）｜**0 tags**｜main 快照过时｜`ChatRunCoordinator` 余量 22 行（先拆分后回灌）｜F4 三级文字 token 统一（先立语义角色→颜色映射表）｜`fulltest.sh` 接进 CI 运行步。

---

## 七、下次接手须知

1. **静态闸门基线（本波后）**：`arch-guard.sh` **27 项**、`arch-guard-selftest.sh` **PASS=65**、`fulltest.sh` = **tests=624+store3=627 口径**（`SandboxFileScannerTest` 既有失败为环境相关非常量，判据 = 无新增失败 + 模块数 9）。
2. 🔴 **全量单测必须注入 `JAVA_HOME`/`ANDROID_HOME`/`GRADLE_USER_HOME` 并核对模块数 = 9**；JVM 单测判据必须 `--rerun-tasks`。
3. 🔴 新分支第一件事核 workflow 触发列表；docs-only 提交 0 run。
4. 🔴 真机判据：涉 native 落**不过滤**全量档且先验含 native 行；`uiautomator` FATAL 核 PID；adb 串行；**发送/授权后必须核 journal 新目录**（键盘位移坑）。
5. 多模态再验别的容器时，直接复用本波通道：容器 SHA256 双源 → push Download → 冷启动 → 看图片能力位 → SAF 选图（先 scan_file）→ ASCII 正文 → journal 判 settled。

---

## 八、CI run id

> **推送后回填占位**（W52 教训：run-id 回填须在推送后立即回填）。

| workflow | run id | 结论 | 关键 job |
|---|---|---|---|
| **Build** | `37927457910` | ✅ success（head `e00162595`） | Lint / Assemble Debug / Unit tests |
| **Release** | `37927457769` | ✅ success | — |

---

## 九、外部报告对账

- 审查席报告（2026-10-09）七点核验逐条与实况一致（守卫/单测实跑复核、baseline 机械复核 627、store 接线源码级核验）；**未采信开发席自述，全部独立回源码**——本波 dev 自述与实况**无不符项**（W55 教训的改善确认）。
- 方案席策略文档两处行号订正（fold 调用点 4 处：`:1493/:1811/:1866/:1891`；附件路径非 `:1752`）已回核采纳。
