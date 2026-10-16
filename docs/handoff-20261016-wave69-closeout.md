# W69 波次收官交接（Wave 69 Closeout · 产品波）

> 基线：W68 收官 `172f398`｜本波 5 个 commit：`f2ea37f` → `d4ad977`（见 §二）
> 单一执行依据：`_plans/w69-plan.md`（v1 定稿）
> 前置：W68 交接 §五/§六.8 + 五报告（第十四审 / 复审24 莫斯线 / 深审 W68 / v22 / v21）+ **用户会话裁决**（2026-10-15）

---

## 一、一句话 + 关键现实

**W69 = 产品波**（README:334 构成规则自本波起执行的首波）：**治理槽**（预设守卫解析层统一 —— 把「三解析器同判据」从操作纪律变单一实现）+ **产品件**（C3 会话删除 UI，真机 E2E 绿）+ **工程件批**（S-2/S-3/S-4/Y2/S-5/S-6）+ **C8**（`executeWithGuard` 抽至 `ToolExecutionGuard.kt`，🟡-5 单调时钟实施）+ **议程销账**（C4 三裁决 + C1 决策）。第十四审 H2 的「构成性风险」由**用户会话已发起**消解（**未启逃逸条款**）。

关键现实（动手前必读）：
1. 🔴 **K-1 单源链不变**：CEIL=9 ↔ 4608 ↔ 5.06 ↔ 1536（本波零触碰）；第 5 处人工锚 `appliesToRuntime`×3 新增**机械校验**（#29 扩展，取红不取 warn）。
2. 🔴 **静态闸门基线上移**：arch-guard **41 项全绿**（#29 扩展为同批版本校验；#39 由内联 awk 迁为 `check-evidence-level.py`）；selftest 新增 **case70 家族**（一夹具三杀）+ **case70b**（块注释散文假红绿面）；`@Test` **727** == `build.yml:502 baseline`（沿革 718→725→727 逐 commit 点账）；fulltest 9 模块 `tests=727 failures=1`（唯一失败 = **既有** core-data `SandboxFileScannerTest` Windows 符号链接；无失败模块单独跑 exit 0，已证非本波回归）；行数表 10 行（AgentRunner **2787 → 2742**（C8 抽取）；ChatRunCoordinator **1245/1300 余 55 不变**；ChatViewModel 692/1600）。
3. 🟡 **三解析器已合一**：`scripts/preset_blocks.py` 为唯一块解析源；#34（266→105 行）/ #41（319→188 行）改消费模块；#39 迁为 `scripts/check-evidence-level.py`（含 fail-closed 包装网）。
4. 🟡 **C3 产品件行为面**：会话抽屉每行删除图标 → 确认框（默认焦点 = 取消；三取消路径不删）→ `ConversationRepository.delete` → 级联清理（韧性快照 / 审批缓存 `revokeAll(cid)` / 子代理会话 `clearConversation(cid)` / journal+history 的 `<cid>/` 目录递归删）；**不清理** blob 池（只增池，如实申报）。防误删判据核 `isSafeConversationDirName` fail-closed。
5. 🟡 **C5 判据同源化（已申报的窄行为改动）**：GPU 放行判据直连 `ModelGpuSupport.isGpuVerified`；与旧式 `preset.gpuSupported` 严格恒等，**唯一分歧面 = `MiniCPM5-2B_int8.litertlm`**（白名单有、预设表无）—— 该面旧 UI 给 false 而引擎保命道放行 ⇒ 本批属**修正性对齐**（仅 SAF 导入该名文件可感知）。`ModelPreset.gpuSupported` 经本批后**零生产消费方**（见 §五 沿袭）。
6. 🟡 **真机窗部分行使**：C3 E2E 绿 + knownBroken 激活态回归绿（C2/C6 真机验证）；**阈值校准 / D7 / 2047 补丁臂 / Y3 未行使**（如实挂账，见 §三.3）。
7. 🟡 **治理槽只占一件**：C4/C1 落档属「议程销账」（docs-only，随用户会话闭环），不占治理槽。

---

## 二、改动清单（5 commit）

| commit | 性质 | 内容 |
|---|---|---|
| `f2ea37f` | feat(guard) | **A 治理槽**：`preset_blocks.py` 共享解析模块 + #34/#41 迁移消费 + #39 迁为 `check-evidence-level.py` + **复审24 P1-① 修复**（赋值/表达式前缀形态整块静默逃逸：开启判据改行内具名位匹配 `(?<![A-Za-z0-9_])ModelPreset[ \t]*\(` + 前词非 `class`）+ **P2-① 修复**（#34 块注释/KDoc 散文假红：判定链先剥 `/* */`）+ **#29 扩展**（`appliesToRuntime` == toml `litertlm` 版本）+ **W69-04 销账**（#39 开启行提取锚定化）+ selftest case70/70b |
| `b8a292c` | feat(chat) | **B 产品件 C3**：会话删除 UI（行尾图标 + 确认框默认取消 + 忙时禁用）+ 删除接线 + 级联清理（`cleanupConversationArtifacts`）+ 防误删判据核 `isSafeConversationDirName`（白名单式 fail-closed）+ `delete` 钩子移出 writeMutex 锁外 + `ConversationDeletionCleanupTest` 7 例 + baseline 718→725 |
| `72cae00` | feat(engine) | **C 工程件**：C2 `EngineLoadDegrade` 提为 public object（唯一 public 成员 `gpuInvolved`，其余 internal）+ `activationWarningFor` 复用同一组合子（+2 单测）+ C5 判据同源化 + C6 提示尾句 clause-aware + C7 措辞（ModelsViewModel KDoc / Coordinator 注释）+ baseline 725→727 |
| `27e32a8` | refactor(agent) | **C8**：`executeWithGuard` 抽至新文件 `ToolExecutionGuard.kt`（93 行）+ 🟡-5 单调时钟（`nanoTime` 差值）+ 🟡-1/🟡-4 设计草案入 KDoc；AgentRunner 2787→2742 |
| `d4ad977` | docs | **D 议程销账**：C4 三裁决落档（README Wave 3 行 / docs/11 #6·#9 / docs/15）+ C1 决策落档（docs/14 §C1 / docs/15 W68-08 / README `termsVersion` 行三处一致）+ **新建 `docs/16-chatruncoordinator-split-plan.md`**（Coordinator 接缝预案入仓，替代指向仓外的 wave48-design）+ docs/15 新增 W69-06..W69-13 + **M12 全量对账**（补 3 处缺口）+ README:334 追加 docs/15 delta 纪律 |

---

## 三、真机复验（OPPO A13 `13309cc8`，有线 adb，W69 包 02:0x 安装）

### 3.1 C3 会话删除端到端（产品件主验证，✅）

| 步 | 实测 |
|---|---|
| 打开抽屉 | 汉堡 `content-desc="打开抽屉"` 动态定位 ⇒ tap |
| 删除入口 | 抽屉内 `content-desc="删除会话"` **8 个节点**（= 可见行数）⇒ 点第 1 行 |
| 确认框 | 弹出：**取消按钮 (618,1396) 在左、删除按钮 (856,1396) 在右**（与「默认焦点 = 取消」设计一致）⇒ 点「删除」 |
| 删除 + 级联 | journal 目录 **121 → 120**、conversations **122 → 121**；被删 cid `d6547462-111e-4fd2-a7c2-6fa75c6412b7` 的 **journal 目录与会话文件双双消失** ⇒ delete 本体 + 级联清理**同时验证** |
| 稳定性 | `FATAL EXCEPTION` 行数 **0**；级联清理 WARN 行数 **0**（清理静默成功，符合设计） |

判据以设备侧文件为准（`_w69_dev/` + `_ci-tools/_w69_c3_e2e.py`）；未依赖 UI dump 的语义断言。

### 3.2 knownBroken 激活态回归（本波 C2/C6 改动的真机回归，✅）

W69 改了 `activationWarningFor`（C2 签名 + C6 文案）⇒ 必须回归。用 W68 同款协议（active = `gemma-4-E2B-it-gpu`，题面 `Hello please reply OK`）：

- `LiquidAgentDiag` WARN **恰好 1 次**：`激活态重校验提示：已知不可用：0.17.1 引擎初始化 NOT_FOUND（容器缺 TF_LITE_PREFILL_DECODE section；W43 另实测输出退化）。仍可继续，但可能失败。`
- **不含「回落 CPU」**（grep 命中 0）⇒ **C6 clause-aware 在真机逐字验证**（knownBroken-only 子句不带回落尾句）；
- run **未被拦截**（journal settled `ModelStopped`）；`FATAL`=0。

### 3.3 未行使项（如实挂账）

- **阈值回填校准（W69-03）** / **D7（R1 探针 verdict）** / **2047 补丁臂** / **Y3（ChatParamsPanel GPU + 白名单外模型行使样本）**：本波真机窗用于产品件 E2E 与激活态回归，四项顺延（挂账见 §五）。
- 题面英文铁律（`adb input text` 非 ASCII NPE，沿袭申报）；真机久置自动息屏 ⇒ dump 会抓到 systemui ⇒ 驱动脚本须先 `input keyevent 224` + `cmd statusbar collapse`（本波新增经验，已入 `_w69_c3_e2e.py`）。

---

## 四、审查对账

1. **审查席（严质衡）C3 专项**：报 **1 P1 + 2 P2 + 3 P3**，主体 8/9 必查项 ✅。
   - P1 = 新增 5 例 `@Test` 未同步 `build.yml` baseline ⇒ #26 判红 ⇒ **主理人收官统一改 718→727**（单值共享，逐 commit 点账）；
   - P2 = `conversationArtifactDirs` 漏拒 `"."`（`File(root,".")` 等价 root ⇒ 会删光全部会话目录）⇒ 修为判据核 `isSafeConversationDirName`（拒绝空白/`.`/`..`/含 `..` 子串/路径分隔符/ISO 控制字符）+ 补 2 例（含正向 UUID 不误伤）；审查席**回源复核通过**（逐形态复现 + `.` 形态不再 vacuous）；
   - P2 = `onConversationDeleted` 在 `writeMutex` 锁内（递归删目录阻塞其他会话写）⇒ 主理人**裁决本波修**（授权扩 `ConversationRepository.kt` 改动面，仅 `delete` 一函数）⇒ 钩子移锁外；
   - P3 ×3（NUL/控制字符随 P2 吃掉 / 符号链接不可达仅记录 / KDoc 磁盘键格式补全）⇒ 处置完毕。
2. **复审24（莫斯线）**：P1-①（三解析器赋值前缀形态静默逃逸）**已修并独立实证**——主理人侧 fixture-first 对照：**旧版对真 ModelPresets.kt 副本注入 `val evil = ModelPreset(...)` ⇒ rc=0 零输出（静默逃逸）**；新版三守卫同夹具三红（#34 归因事实行 / #39 缺 evidenceLevel / #41 方向A 缺档案）。P2-①（块注释假红）已修 + case70b 绿面。P3-①/P3-② 挂账不动刀。
3. **第十四审**：H1（三解析器）→ 治理槽；H2（构成性风险）→ **用户会话消解**（产品件 = C3，未启逃逸条款）；H4（真机窗顺序）→ 部分行使（见 §三.3）；H5（外部报告受理门 `git cat-file -e`）→ 已作为对账第一步执行（本波五报告基线均在本仓可验）。
4. **深审 W68**：S-1（Coordinator 接缝预案入仓）→ `docs/16`；S-2（`activationWarningFor` 口径分叉）→ C2 修 + 真机回归绿；S-3（三处版本字面量人工同步）→ #29 扩展（取红）；S-4（docs/15 delta 纪律）→ README:334 成文；S-5/S-6 → C7 措辞。
5. **v22**：Y1 前提**被现役树证伪**（`ModelPreset.gpuSupported` 本就是 `ModelGpuSupport` 白名单的投影，KDoc 明载「判定必须是同一数据源」）⇒ 主理人先裁「撤销」，后因 C 席已落 `ModelsScreen.kt` 直连而**复核等价性后追认**（分歧面仅 `MiniCPM5-2B_int8`，属修正性对齐）；Y2 → C6；Y3 → 未行使（挂账）。
6. **v21**：M10/M11 备忘（不动）；M12（handoff↔台账对账）→ docs/15 新增 M12 节，**实测补登 3 处缺口**（W69-05 / W69-10 / W69-11 交接有台账无，根因同 W68 §五手工聚合面）。

**过程记录（如实申报）**：方案席（`solution-strategist`）两次派单均因**基础设施 500 错误**中断、零产出 ⇒ Phase 1 分析（C4 裁决 + G4 评审）由主理人直办，未虚构成员产出（该两实例已不产出任何文件）。

---

## 五、挂账（W69 起；权威版 = `docs/15-backlog.md`，本节只写 delta）

**销账（W69 关闭，判据见 docs/15）**：W69-01（ToolExecutionGuard 拆分 + 🟡-5 已实施；🟡-1/🟡-4 转 W69-12/W69-13）｜**W69-04**（#39 开启行提取锚定化）｜W68-04 的 C4 部分（裁决落地 = W69-06）｜第十四审 H1（三解析器）｜复审24 P1-①/P2-①｜深审 S-1/S-2/S-3/S-4/S-5/S-6｜v22 Y1/Y2｜v21 M12。

**新增/顺延**：
- **W69-05** #41 镜像/续行形态覆盖（parked，D6 补登）
- **W69-06** C4 裁决落地（closed-done）｜**W69-07** 人格系统（保留，重定范围 = system prompt 人格档位；parked）｜**W69-08** 定时任务（closed-dead）｜**W69-09** 插件化装载（closed-dead，docs/11 #6 随同）
- **W69-10** D7 + 2047 补丁臂真机件（active，D6 补登；本波未行使）
- **W69-11** #40/#41 锚对账机械化（active，D6 补登）
- **W69-12** 工具超时孤儿写代次校验（parked；设计草案在 `ToolExecutionGuard.kt` KDoc）｜**W69-13** 超时 indeterminate 记账（parked，同上）
- **沿袭**：`ModelPreset.gpuSupported` 经 C5 后**零生产消费方**（保留为白名单投影便利访问器，待判死或复用；本波如实申报）｜W69-03 阈值校准（active，前提仍满足）｜W68-01 数据回填 / W68-06 bump blocker / W68-07 gemma GPU 用户故事 / W68-08 tag·法务·main FF｜法务 `termsVersion`（C1 语义已裁，等正式文本）｜0 tags｜fulltest 接 CI｜F4｜`TokenUsage.estimated`｜bump 双轴季扫｜上游出货 B1/B2（用户手动）｜审查 P3-d｜N-2 warn 聚合键｜`.md` CRLF 纪律

**明确未做（负面清单兑现）**：bump（锚 #5 未验）｜seam②｜main FF｜tail 调整｜OFF 档硬阈（C5-3 维持 B）｜🟡-1 代次校验实施（仅设计草案）｜parity P3-① 三边界｜真机窗四项（阈值校准/D7/2047/Y3）。

---

## 六、下次接手须知

1. **静态闸门基线（本波后）**：arch-guard **41**；selftest **+case70 家族 + case70b**（全量口径以 CI 日志为准）；`@Test` **727** == `build.yml:502 baseline`；fulltest 9 模块 `tests=727 failures=1`（唯一失败 = core-data `SandboxFileScannerTest`，**仅 Windows**，本地跑必红、CI 绿）；行数表 10 行（现行值一律 `bash scripts/line-table.sh`）。
2. **三解析器已合一**：改任何 `ModelPresets.kt` 块解析语义 ⇒ **只改 `scripts/preset_blocks.py` 一处**（三守卫自动同判据）；改后跑 `python scripts/tests/test_preset_blocks.py` + `bash scripts/arch-guard-selftest.sh --only 34,39,41`。**开启判据含「前词非 class」排除** —— 改判据前先读模块 KDoc。
3. **K-1 单源链**：CEIL=9 ↔ 4608 ↔ 5.06 ↔ 1536（未动）；`appliesToRuntime`×3 现由 **#29 机械校验**（== toml `litertlm` 版本，取红）。
4. **C3 数据流**：抽屉删除图标（`content-desc="删除会话"`）→ `LiquidDialog` 确认（默认焦点 = 取消）→ `ConversationRepository.delete`（**钩子在锁外**）→ `AppContainer.cleanupConversationArtifacts` 四步。**改判据核 `isSafeConversationDirName` 前先读其 KDoc**（放行 = 递归删目录，不可逆）。
5. **C2 口径**：`activationWarningFor(backend, visionBackend)` 与引擎 `gpuInvolved` 同源；`EngineLoadDegrade` 现为 public object（唯一 public 成员 `gpuInvolved`）。**真机判据锚点**：`ChatRunCoordinator.kt:260` 的 `AgentLogStore.warn("激活态重校验提示：…")`。
6. `.md` 是 CRLF（Edit 工具，禁 `sed -i`）；`.kt` LF。真机：`MSYS_NO_PATHCONV=1` + Windows 路径 push + `pm install -r`；**先 `input keyevent 224` + `cmd statusbar collapse`** 再 dump（否则抓到 systemui）；判据以 journal/设备侧文件为准。
7. **W70 入口**：主刀候选 = 阈值校准（W69-03，前提已满足）→ D7 → 2047 臂 → Y3；治理槽候选 = W68-09 轻量核对守卫（M12 实测 3 缺口强化立项依据）；产品件候选 = evidenceLevel 徽章（W68-03，待 C5-1 裁决）或人格档位（W69-07）。

## 七、CI run id

| workflow | run id | 结论 | 关键 job |
|---|---|---|---|
| **Build** | `38785057208` | （待回填） | @ `d4ad977` |
| **Release** | `38785057262` | （待回填） | — |

> 对照基线：W68 Build `38570098857` / Release `38570098851` @ `792acd7`。
