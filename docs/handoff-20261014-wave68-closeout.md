# W68 波次收官交接（Wave 68 Closeout · 转段波）

> 基线：W67 收官 `32c1bbe`｜本波 17 个 commit：`f185b64` → `792acd7`（见 §二）
> 单一执行依据：`_plans/w68-plan.md`（v1 定稿 313 行；主理人三点复议后确认）
> 前置：W67 交接 §五（挂账权威版）+ 六报告（第十三审 / 复审22 / 复检W67二轮 / 深审二轮 / 第十二轮 / W67交接）+ 复审23（莫斯线终裁，见 §四.3 适用性裁定）

---

## 一、一句话 + 关键现实

**W68 = 转段波**（第十三审 §4.3）：治理急救（docs/15 单一事实源 + G1 两账恢复）+ 真实性刀（路线图订正）+ 产品件（**knownBroken 激活态重校验功能刀**，tag 前硬前置）+ 守卫网双修与硬化（#34/#39/#41）+ 真机批（三件全绿）。README 已入**波次构成规则**（治理 ≤1 件/波 + 每波 ≥1 用户可见项，自 W69 起执行）。

关键现实（动手前必读）：
1. 🔴 **K-1 单源链不变**：CEIL=9 ↔ 4608 ↔ 5.06 ↔ 1536（本波零触碰）。
2. 🔴 **静态闸门基线上移**：arch-guard **41 项**（+`#40` core-model 禁 feature-models 字样 / `#41` 预设↔档案双向一致）；selftest 新增 17 面（case61d/61e/64/64b/64d/65/65b/66/66b/67/68/68b/69/69b/69d/69e/69f）；`@Test` **718** == `build.yml` baseline（沿革 690→694→696→703→711→716→718 逐 commit 点账）；行数表 10 行不变（ChatRunCoordinator 1245/1300 余 55、AgentRunner 2787/2900 余 113、ChatViewModel 683/1600）。
3. 🟡 **B2 功能刀行为面**：对话页 run 发起时，active 模型命中 `(fileName ∈ knownBrokenOn) OR (backend==GPU ∧ fileName ∉ gpuVerifiedFileNames)` ⇒ 一次性 dismissible 内联提示（含 knownBrokenOn 原文 + 「仍可继续，但可能失败」）；**不拦 run**、与模型库页 `confirmBrokenLoad` 两语义互斥。数据源 = core-data `activeModelKnownBroken` 派生只读流（精确文件名匹配，禁 contains）；运行时只读不回写。
4. 🟡 **B3 收尾**：`ModelPreset.appliesToRuntime`（三坏条目 = "0.17.1"）= **人工复审锚、无机械联动**（与 toml 第 3 处版本字面量，bump 须人工同步——KDoc 已如实申报）；docs/12 加锚 #14「knownBroken / GPU 白名单过期复审」入 bump 锚清单；R-5 归因补强（N-2 warn 观测 ⇒ 诊断卡加「疑似不服从开关」，零行为）；W68-D 删除钩子休眠成文（等 C3 议程）。
5. 🟡 **E4B 单文件补采样档案**（复检 Q-2 根治）：此前 14 预设中唯一无 `forFileName` 档案 ⇒ contextLength 可拉 32768 超 KV 预算 4× 且漏温度下限保护；现同族钳制（temp 1.0 / 0.3..1.5 / topK 64 / ctx 8192）。**这是行为面变化**（E4B 单文件用户上下文上限从 32768 收敛到 8192）。
6. 🟡 **内存公式不改尾**（预裁量②）：`estimateRequiredRamBytes` KDoc 记首个实测样本（E4B：实测/公式 1.14、含 swap 1.34），tail 1.25 维持；补实测批挂 W69-02。
7. 🟡 **议程卡随本交接附出**（docs/14）：六项 + 新增 C4（Wave 3 三大项裁决）/ C5（evidenceLevel 接线 / F1-A / W68-B 表决）+ §D 一页纸决策清单——**用户 20 分钟会一次清**。

---

## 二、改动清单（17 commit）

| commit | 性质 | 内容 |
|---|---|---|
| `f185b64` | ci | **A5**：line-table `--check` 接入 build.yml arch-guard step（深审 🟡-6；投毒=措辞漂移 RC=1 已还原） |
| `07e9125` | docs | **C1+C4**：新建 `docs/15-backlog.md` 单一事实源（四要素+delta 纪律）；G1 两账恢复（数据回填/阈值回填，W57→W58 丢账实证复盘）；README:58 13→14；W66 交接 C=16 勘误两处；判例六成文；P4×3 |
| `7f6d697` | docs | **C2**：路线图真实性一刀（M4 拆子项 / Wave 3 行订正 / 特性表验证列刷新 / docs-11 九件判死活 / 波次构成规则入 README）；Q-3 薄窗口落 docs/12 |
| `496874d` | docs | **C3**：docs/14 议程卡（六项推荐选项 + C4/C5 + §D 一页纸决策清单 + G3 等待天数披露约定） |
| `20da147` | fix(guard) | **A1**：守卫 #39+#34 双修（复审22 P2-① 形态A/B 逃逸：统一开启判据 + #34 块收尾 + KNOWN_BROKEN_SET 去行首锚定）+ case61d 一夹具三杀 + case61e 绿面 |
| `f5ca9e7` | feat(model) | **A2**：E4B 单文件补采样档案（复检 Q-2 根治）+ note/闸门 7.1GiB 对齐 + `estimateRequiredRamBytes` KDoc 记首个实测样本 + baseline 694 |
| `fd79569` | feat(guard) | **A3**：新守卫 **#40**（core-model 禁 feature-models 字样，Q-5）+ **#41**（预设↔档案双向一致，Q-2 方向守卫）+ parity 扫描器 + selftest case64-67 |
| `d9612be` | test(agent) | **A4**：N-2 触发链离线替身单测（第十二审 R-4：>阈值行使面解脱真机唯一路径）+ baseline 696 |
| `fadfa56` | refactor(selftest) | A3 case 编号归一（65c/d/e→66/66b/67，dir↔label 同号） |
| `eb17d99` | docs | docs/15 +W69-04（#39 开启行无锚定 match 边界，严质衡 A1 复审 P3） |
| `519cff9` | fix(guard) | **A1-R1**：#34 字符串内容假红面修补（`blank_strlits` 对偶 awk strlit）+ docstring 说谎订正 + P2 分歧声明 + case68/68b |
| `5874a2d` | fix(guard) | **A3-R1**：#41 扫描器硬化（严质衡 A2/A3 复审 3 重要+1 逃逸：豁免令牌加 W## 锚点 / 文件名具名位提取 / 三解析器括号换行同步）+ case69 家族 + 13 计数存量订正 5 处 |
| `1638525` | feat(model) | **B1**：core-data `activeModelKnownBroken` 派生只读流（精确匹配单一数据源）+ 坑1 判定键三断言 + baseline 703 |
| `f477292` | feat(chat) | **B2**：`activationWarningFor` 判据式 + run 发起时触发（四路径共享起点）+ `ActivationWarningGate`（每会话一次 dismissible）+ ChatScreen 警示卡 + baseline 711 |
| `5852d81` | feat(model) | **B3**：`appliesToRuntime` 复审锚 + docs/12 锚 #14 + R-5 归因补强（零行为）+ W68-D 休眠成文 + baseline 716 |
| `45c4ff0` | fix(chat) | **B-R1**：新会话字段保活（`forNewConversation` 保留 activeModelKnownBroken，修严质衡 Finding 1 静默失效）+ 空文件名防御 + appliesToRuntime 措辞订正 + baseline 718 |
| `792acd7` | fix(guard) | **A3-R2**：#41 字段级散文置空（复检 N2，f2/f8 修复保持）+ selftest 外层门修复（复检 N1：`--only 34/41` 不再静默跳过）+ case69d 断言升级（N3） |

---

## 三、真机复验（OPPO A13 `13309cc8`，有线 adb，W68 包 20:33 安装）

### 3.1 功能刀端到端（B2 主验证，三件全绿）

| # | 件 | 协议 | 实测 |
|---|---|---|---|
| ① | 健康模型回归 | E2B CPU（`96329c4f`）题面 `Hello please reply OK` | 正文 **`OK`**、thinking 非空、settled `ModelStopped`；**激活态提示 0 命中**（无误报）|
| ② | knownBroken 激活态提示 | 切 active = E2B·GPU（`d0de1e7a`，knownBrokenOn=NOT_FOUND）发起 run | **恰好 1 次**：`激活态重校验提示：已知不可用：0.17.1 引擎初始化 NOT_FOUND（容器缺 TF_LITE_PREFILL_DECODE section；W43 另实测输出退化）。仍可继续，但可能失败。`（LiquidAgentDiag WARN 21:11:13）；run 未被拦截（journal 走完 run_started→settled）|
| ③ | 新会话重提示（Finding 1 修复 E2E） | 同坏模型新会话再 run | 新 journal 会话（`d6547462` ≠ ②的 `e2ab0068`）中提示**再现 1 次** ⇒ 字段保活 + gate 复位链路真机闭环 |

判据以 journal/应用日志为准（`_w64_dev/results/w68_{e2b_reg,knownbroken,newconv_rewarn}_{run.jsonl,log.txt}`）；uiautomator 对 Compose 卡片不可靠未作 UI 断言。

### 3.2 未行使项（如实挂账）

- **D7（R1 探针 verdict 实读）**、**2047 补丁臂真机确认**、**阈值回填校准**：真机窗口用于功能刀三件，顺延 W69（§五）。
- 题面英文铁律（`adb input text` 非 ASCII NPE，沿袭申报）。

---

## 四、审查对账

1. **A1**（严质衡）：对抗夹具 + 变异测试（旧守卫+新 case ⇒ FAIL=4，非 vacuous）——修补本体 ✅；P1 字符串内容假红面 → A1-R1 修复（主理人直办）并经其两夹具复跑判绿。
2. **A2/A3**（严质衡二号实例）：A2 ✅（含 4 组变异测试：删分支/改值/家族漂移/真 catch-all 各杀其标）；A3 #41 三重要弱面（f1/f8/f2）+ f6 逃逸 → **A3-R1** 修复 → 其**原夹具复跑销账**：f1/f8/f6 假过→红、f2 假红→绿、f17 合法豁免仍绿、f3/f7/f9/f10/f12 零回退；另报 N1（case69 被嵌套 `--only 41` 跳过）/N2（行级限定覆盖收窄）/N3 → **A3-R2** 修复（主理人直办；`--only 41/34` 门开逻辑实测）。
3. **复审23（莫斯线终裁）适用性裁定**：其基线（6 commit / 42 守卫 / @Test 693 / README:528 / Banner.kt / `137237e..363d3e1`）与本仓现役树（17 commit / 41 守卫 / @Test 718 / 无 Banner.kt）**不符**——该报告的落刀侧为另一重建链，其 commit 在本仓均不存在（`git cat-file` 实测）。本波仅采纳其可迁移项：P2-④（#34 注释假红）经实测**本树不成立**（split_blocks 已 strip_comment + A1-R1 blank_strlits，夹具注释陷阱判绿）；「锚修刀四层全扫」「fixture 演练路径≠主路径」两条治理教训记入 docs/15 治理注记。
4. **B 轴**（严质衡三号实例）：Finding 1（重要：新会话提示静默失效）+ 建议②（空文件名防御）+ 建议①（措辞订正）→ **B-R1** 全修并复审**通过** ✅（坑1 反证独立复现用 startsWith 形态、四 run 路径共享起点确认、`appliesToRuntime` 非字段裸奔辨析）。

---

## 五、挂账（W69 起；权威版 = `docs/15-backlog.md`，本节只写 delta）

**销账（W68 关闭，判据见 docs/15）**：复审22 P2-①/P3×4/P4×3｜第十三审 G1/G4（→parked W69-01）/G5 部分（Q-3 落 docs/12）｜第十二审 R-1/R-2/R-3/R-4/R-5/R-6｜复检 Q-1（部分，补实测转 W69-02）/Q-2/Q-3/Q-5｜深审 🟡-6/W68-A/W68-B/W68-D｜W67 §五.1（功能刀落地；tag 前硬前置维持至 tag）。

**新增/顺延（docs/15 全量表）**：
- **W69-01** ToolExecutionGuard.kt 拆分实施（G4 修法草案已成文，parked）
- **W69-02** 内存公式补实测批（权重最大三条 + PSS 含 swap 定案，parked）
- **W69-03** 阈值回填校准（3 坏容器已在机，前提已满足，active）
- **W69-04** #39 开启行 evidenceLevel 无锚定 match 污染面（parked）
- **W69-05** #41 镜像/续行形态覆盖（A3-R2 已改字段级根治主面；残余 exotic 边界见扫描器 docstring，parked 观察）
- **W68-01..10** 数据回填 / F1-A+W68-B（议程）/ evidenceLevel 接线 / docs/14 六项+C4 / 删除 UI / bump（blocker 维持）/ gemma GPU 用户故事 / tag 拓扑 / docs/15 轻守卫（parked）/ 模型直答腿（closed-dead）

**明确未做（负面清单兑现）**：bump（锚 #5 未验）/ seam② / main FF / tail 调整 / OFF 档硬阈 / D7+2047+阈值校准真机件（窗口让位功能刀）。

---

## 六、下次接手须知

1. **静态闸门基线（本波后）**：arch-guard **41**；selftest **PASS=134+17=151 面**（全量 CI 口径以本次 Build run 日志为准；本地 `--only` 抽样实测见 §二各 commit）；`@Test` **718** == baseline；fulltest 9 模块；行数表 10 行（现行值以 `bash scripts/line-table.sh` 为准）。
2. **K-1 单源链**：CEIL=9 ↔ 4608 ↔ 5.06 ↔ 1536（未动）。**新增第 5 处关联**：`appliesToRuntime`="0.17.1" ×3（人工复审锚，bump 须同步，docs/12 锚 #14）。
3. **#34/#39/#41 三解析器同判据**：统一开启判据（含括号换行形态）+ 块收尾 + 字段级散文置空（#41）/ blank_strlits（#34）——**改任一解析器前先跑 `--only 34,39,41`**（A3-R2 后三门在 `--only` 下可达）。
4. **功能刀数据流**：core-data 派生只读流 → ChatUiState 镜像 → run 发起时判据（`activationWarningFor`）→ `ActivationWarningGate` 频控。**改 ChatUiState 重建逻辑必须保留 `activeModelKnownBroken`**（用 `forNewConversation()`，Finding 1 教训：冷流不重发 + 整体重建丢字段）。
5. **E4B 单文件行为面**：上下文上限 8192（同族钳制，原 32768）。用户可感知变化，已在预设 note/闸门口径成文。
6. `.md` 是 CRLF（Edit 工具，禁 sed -i）；`.kt` LF。沙箱坑：长跑 gradle/selftest 偶发 SIGTERM ⇒ nohup 后台 + 轮询；Write 工具 `/tmp` 与 Git Bash `/tmp` 不通 ⇒ 脚本放工作区真实路径。
7. adb：`MSYS_NO_PATHCONV=1` + Windows 路径 push + `pm install -r`；发送键动态 dump `content-desc="发送"`；模型 id 映射在设备 `files/model_index/models.json`；判据以 journal/日志为准。
8. **W69 入口**：主刀 = #40/#41 锚对账机械化（复审23 §四，适用部分）+ docs/14 议程卡清账（C4/C5 用户裁决）+ D7/2047/阈值校准真机窗 + ToolExecutionGuard 实施（W69-01 评审后）。

## 七、CI run id

| workflow | run id | 结论 | 关键 job |
|---|---|---|---|
| **Build** | `38570098857` | **completed success**（推送后 CI 轮询确认） | @ `792acd7` |
| **Release** | `38570098851` | **completed success**（推送后 CI 轮询确认） | — |

> 对照基线：W67 Build `38348809838` / Release `38348809828` @ `c60abf3`。
