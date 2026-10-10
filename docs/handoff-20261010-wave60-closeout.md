# W60 波次收官交接（Wave 60 Closeout）

> 基线：W59 收官 `a4e9365`｜本波 tip = `（收官刀后回填）`（6 刀代码 + 1 刀文档）
> 前置文档：`docs/handoff-20261010-wave59-closeout.md`（§五 = 本波挂账来源）
> 方案：`_plans/w60-strategy.md`（仓外，方案席沈思远，行号实测于 a4e9365）｜审查：`_plans/w60-review.md`（仓外，审查席严质衡）
> 真机取证：`_litert_forensics/_w60_smoke/`（`w60_full.log` 41.3MB 全档 + `w60_l3.log` 5.2MB）

---

## 一、一句话 + 关键现实

**W60 = 治理补强波（A4 世代门控纯函数外提 + 守卫 #32/#33 + #30 注释排除 + 契约例拆变异 + 命名/文档修补）+ 真机批全面行使（文本 run 两轮 / 换模型真重建 / 翻转复位 OFF→ON）+ 13 模型全量下载与功能测试 + 剩 2 容器 L3 图像测试关闭。**

关键现实（动手前必读）：
1. **A4 世代门控已 JVM 可测化**：`shouldDropStaleDisposal(token, current)` 外提为 `LiteRtLmEngine.kt` 顶层纯函数（`token != current`，与原内联逐字等价）；3 例 JVM 单测 + 守卫 #33 钉「调用点存在性 + 传参形态」⇒ **语义与接线双钉**（单测钉不到接线，守卫补钉；二者互补、互不越界宣称）。
2. **守卫 31 → 33 项**：#32 `LiteRtLmEngineLoader.kt ≤ 900`（#25 家族第 5 员；阈值 900 显著宽于家族「≈+2.5%」哲学，注释如实申报张力 + 三条辩护理由）｜#33 世代门控接线（`shouldDropStaleDisposal(` 调用==1 且 `handleTemplateRenderFailure(token,` 调用==2）。#30 判据加注释位排除（与 #24/#28 同款，封堵「改一句 KDoc 就假红」）。
3. 🔴 **真正更紧的 AgentRunner 约束是方法级、不是文件级**：`executeBodyUnchecked` 实测 **540/550（守卫 #12，余 10）**，而非文件级余 98（#31）。**下轮 AgentRunner 改动的第一道闸是 #12**。候选接缝 `onSend/onToolLoop` 在 `AgentRunner.kt` **实测 0 命中**（不存在，首刀须先造）⇒ 本波采纳「挂账前置（触顶才评审）」，零改动落 AgentRunner。
4. 🔴 **真机 tap 注入本波全程可用**（USB 有线；W59「设备级失效」未复现——W59 当时疑为屏幕 Dozing / 通知栏聚焦所致）。用户裁夺：**全程使用有线 adb**。
5. 🔴 **模型切换的稳健通道 = 改 datastore 的 `active_model_id`**（等长 UUID 字节替换 + `run-as cp` + 重启），**绕开 Compose 卡片点击的不可靠性**（模型卡在 uiautomator dump 中不可点）。但**翻转复位必须走 UI 开关**（`lastSeenSwitchOn` 是引擎实例字段，外部改 datastore 不被运行中的 app 感知；且中途 Activity 重建会清 null ⇒ 失效）。

---

## 二、改动清单（6 刀代码）

| commit | 性质 | 内容 |
|---|---|---|
| `1af069b` | refactor(engine) | **A4 世代门控纯函数外提**：`internal fun shouldDropStaleDisposal(token: Long, current: Long): Boolean = token != current` 置于 `LiteRtLmEngine.kt` 顶层（紧邻 `foldAdjacentText`/`summarizeContentTypes`，实测 :200）；`handleTemplateRenderFailure` 门控改调纯函数（:1408），体内 info 日志/return 逐字不变；**两调用点（异步回调/同步下发）文本未动** |
| `7f409ef` | test(engine) | 新 `EngineStaleDisposalGateTest.kt`（3 例：同代 false / 异代 true / (-1,-1) false）+ `EngineSameEngineContractTest` 例 2 拆单变异（2→3 例：仅 backend 异 / 仅 contextLength 异）+ `EngineResilienceSwitchResetTest` 例 4 改名「持续关闭不判翻转」（+ 内联注释订正）+ `build.yml` baseline **645→649**（同 commit，守卫 #26 双向） |
| `06a7055` | test(guard) | **守卫 #32** `LiteRtLmEngineLoader.kt ≤ 900`（fail-closed；selftest case43/43b/44/44b + `scaffold()` 补 Loader fixture） |
| `a8d059d` | test(guard) | **守卫 #33** 世代门控接线（判据② 正则 `handleTemplateRenderFailure(token,` **天然不匹配 def**——def 形参 `token:` 是冒号、KDoc 引用为 `[handleTemplateRenderFailure]` 无 `(` ⇒ 无需注释排除即精确命中 2）；selftest case45/45b/45c + `scaffold()` 补 token 形参/2 调用点/1 门控调用 |
| `5e4feae` | test(guard) | **守卫 #30 计数排除注释位**（`grep -nE … \| grep -vE "^[0-9]+:[[:space:]]*[*/]" \| wc -l`，与 #24/#28 同款）；selftest case46（KDoc 注入 ⇒ 仍绿） |
| `8f5e3b6` | docs | `docs/12-litertlm-0171-anchors.md` #12 定案波次列改「—（外部锚：上游 minijinja 版本，无本仓定案波次）」（消歧，不动 #1–#11） |

⇒ **@Test 实数 = 649**（645 + 3 A4 + 1 契约拆分），`build.yml` baseline **=649**。**守卫 31 → 33 项，selftest PASS 84 → 92**（+8：case43/43b/44/44b + case45/45b/45c + case46）。引擎 **1970 → 1990**（+20，KDoc 密度高于方案预估；余 410 安全）；`LiteRtLmEngineLoader.kt` 704（不变）；`AgentRunner.kt` 2802（**不变**）。

---

## 三、真机验证（本波判据，全部行使）

APK = W60 工作树构建（`8f5e3b6`，localbuild assembleDebug BUILD SUCCESSFUL）；`adb push` → `pm install -r` **Success**（`lastUpdateTime 2026-10-10 21:24:14`，包 `com.rickeal.agent.debug`）。设备侧全量 logcat 落盘 `_litert_forensics/_w60_smoke/w60_full.log`（41.3MB，**FATAL EXCEPTION = 0**）。

### 3.1 ✅ 台账 #44 回收（W59 挂账的行使面全部补齐）

| W59 挂账行使面 | W60 判据 | 态 |
|---|---|---|
| **文本 run 两轮** | 同会话连续两轮：`Say OK` → 回复 `OK`（in 583/out 1）｜`Say GO` → 回复 `GO`（in 586/out 1）；均 `settled=ModelStopped`；全档模板失败=0 | ✅ |
| **换模型真重建** | 多次模型切换（LFM2.5-VL-1.6B → MiniCPM5-2B → gemma-4-E2B-it → … → LFM2.5-VL-3B）均触发 `LiteRT-LM 会话重建`（系统提示词变化 0→N 字）+ 新 `会话已建`；冷启动类装配成功（Loader 类装配） | ✅ |
| **翻转复位 OFF→ON 端到端** | 设置页开关 OFF（`引擎 nativeToolChannel=false`）→ run A → 开关 ON → run B ⇒ **`[WARN] 原生工具通道：用户重新开启，已清证伪与重建计数，重新给机会（再炸再证伪，计数与日志留痕）`** + 随后 `useNativeTools=true，引擎 nativeToolChannel=true`（即时生效）；W59 修补 B 的新副文案「关闭后需先发送一条消息，再重新开启」截图可见 | ✅ |

### 3.2 ✅ 台账 #45：13 模型全量下载 + 功能测试（用户指令）

- **下载**：5 个缺失预设从 ModelScope（HF 直连被沙箱阻断）下载，**字节数逐一精确吻合预设 `sizeBytes`**：Gemma 4 E4B·GPU 2969059328 / DeepSeek-R1 1.5B 1833451520 / Phi-4-mini 3910090752 / Qwen2-VL 2B 1783424544 / LFM2.5-VL 3B 2352023888（合计 ≈12GB）。`adb push` 到 app Download 目录（~35MB/s）⇒ 冷启动后模型页 **「共 13 个」**，`models.json` 实读 13 条。
- **功能测试（题面 `Say OK`，逐模型）**：

| 模型 | settled | 判据/备注 |
|---|---|---|
| LFM2.5-VL-1.6B_int4_fixB | ModelStopped ✅ | 回复 `OK`（另轮 `GO`） |
| MiniCPM5-2B_int4 | ModelStopped ✅ | 回复 `这是最终答案。`；**首下发 1 次模板失败 → 自愈重建 → 重试成功**（自愈链真机行使） |
| gemma-4-E2B-it（CPU） | ModelStopped ✅ | 回复 `OK` |
| Qwen2.5-1.5B-Instruct | ModelStopped ✅ | round0 原生**工具调用**（TOOL_CALLS）→ round1 收尾（**原生工具通道行使**） |
| LFM2.5-VL-450M_int8 | ModelStopped ✅ | 回复 `OK` |
| Phi-4-mini-instruct | ModelStopped ✅ | 回复 `记忆已更新，任务停止。` |
| Qwen2-VL-2B | ModelStopped ✅ | 回复 `OK` |
| LFM2.5-VL-3B_int4_fixB | ModelStopped ✅ | 回复 `OK` |
| SmolVLM2-500M | ModelStopped ⚠️ | round0 陷重复循环 `finish=LENGTH` → **`StreamRepetitionDetector` 注入系统提醒**「你刚才的输出陷入重复循环，已被截断」→ round1 换答案（**重复自愈链行使**）；文本能力弱为预设已申报特性 |
| DeepSeek-R1-Distill-Qwen-1.5B | ModelStopped ⚠️ | 2 次重复提醒后收尾；输出为推理腔（与 README 挂账「`inferFamily` 误判 thinking=false」同源，未新增） |
| gemma-4-E2B-it-gpu | **Failed** ❌ | `Failed to create conversation config: INVALID_ARGUMENT: Unsupported model type`（预设已申报：GPU 特化变体在 0.17.1 不可用、CPU 后端 NOT_FOUND） |
| gemma-4-E4B-it-gpu | **Failed** ❌ | 同上（同源风险，预设已申报「未验证前谨慎」） |
| MiniCPM-V-4-int8 | **Failed** ❌ | `Unsupported model type`（既有已知限制） |

⇒ **13 模型零 FATAL**；3 个 ❌ 全为**已知/已申报**的容器↔运行时错配，**无新增缺陷**（连续第八波正确性零新增 P0/P1）。

### 3.3 ✅ 台账 #46：剩 2 容器 L3 图像端到端（W60 挂账项关闭）

测试图 `w57_test_card.png`（红底 + 顶部黄条 + 白字 "37"）经 SAF 选图器挂附件（附件缩略图出现，能力位菜单显示「当前模型不支持音频输入，只能添加图片」）：

| 容器 | 模型回复 | 判据 | 态 |
|---|---|---|---|
| **Qwen2-VL 2B** | `The colors in the image are red and yellow. The number in the image is 37.` | 红 ✓ + 黄 ✓ + 数字 **37** ✓（in 844/out 20，首字 44.9s） | ✅ |
| **LFM2.5-VL 3B_int4_fixB** | `红色背景，黄色顶部，数字37。` | 红 ✓ + 黄 ✓ + 数字 **37** ✓（中文回答；in 844/out 11，首字 26.6s） | ✅ |

共同判据：`多元素 content 下发：折叠前 2 → 折叠后 2；元素类型 Text=1/Image=1/Audio=0/ToolResponse=0`、`Failed to apply template`=**0**、`settled=ModelStopped`、FATAL=0。⇒ **W56 遗留「其余视觉容器端到端待验」全部关闭**（同族 fixB 1.6B W57 + 3B W60 + 异族 SmolVLM2 W57 + Qwen2-VL W60）。

---

## 四、审查对账

- 审查席报告（`code-quality-reviewer`，`_plans/w60-review.md`）：**结论 = ✅ 通过（0 P0 / 0 P1 / 0 P2 / 3 P3）**。
- 关键核验：A4 纯函数**逐字等价**（含初始 `-1` 边界）+ 门控体逐字未变；两调用点未动；4 例新增 `@Test` **非 vacuous**；守卫 #32/#33/#30 判据**精确、fail-closed、selftest 三面齐全**且 scaffold 已同步（positive 绿 ⇒ 正例未失守）；baseline 与 @Test 同 commit 双向同步；数字独立复算全吻合（守卫 33 / @Test 649 / baseline 649 / 引擎 1990 / Loader 704 / AgentRunner 2802）；**selftest 定向复跑 W60 触及面 12/12 PASS**。
- 主理人独立复跑：`arch-guard.sh` **33 项 / 0 error / 「架构守卫全部通过。」**；`arch-guard-selftest.sh` **PASS=92 / FAIL=0 / 「arch-guard 自测全部通过。」**（本机 ~52min）。
- **P3×3（不阻断）**：
  1. **README 未追加 W60 波次段**（方案 §五 commit5 提到，任务书 6 刀切分仅列 docs/12）⇒ **本收官刀补齐**（见 §二 tip 刀）。
  2. 引擎 +20 vs 方案预估 +13（KDoc 密度）；无闸门影响。
  3. **守卫 #33 判据① 无注释位排除**（#24/#28/#30 均有）：当前引擎 KDoc 只用 `[shouldDropStaleDisposal]`（无 `(`）⇒ 不命中，实测 g=1 安全；若未来 KDoc 写入调用形态 `shouldDropStaleDisposal(token, current)` ⇒ g 变 2 ⇒ **fail-closed 假红**（非静默放行）。**处置：本波不动守卫（保「已验证产物 == 推送产物」属性），记入 W61 挂账**。

---

## 五、挂账（W61 起；权威版见本节 + README）

1. 🔴 **AgentRunner 拆分前置告警（升级）**：真正更紧的是**方法级** `executeBodyUnchecked` **540/550（守卫 #12，余 10）**，非文件级余 98（#31）。**重评触发条件**（满足任一即启动拆分评审）：`executeBodyUnchecked` ≥ 545 行｜`AgentRunner.kt` ≥ 2880 行｜任一 W61+ 挂账明确落 AgentRunner｜真机文本 run 回归批通过。**首刀选型**：优先「近纯函数簇外提」（`buildSystemSections`/`toolCallSignature`/`canonicalizeJson`，零行为风险）打底 → 再 emit 簇；**方法级压力**用 W29-A1 式「`executeBodyUnchecked` 内再外提」，**禁止**改 #12/#31 阈值。
2. **守卫 #33 判据① 补注释位排除**（P3-3）：加 `\| grep -vE "^[0-9]+:[[:space:]]*[*/]"` + 1 个 selftest 绿面 case（KDoc 注入调用形态 ⇒ 仍绿）。
3. **bump 重启监视**：上游 tag 季扫（前置④）+ 前置⑥清单（`docs/12` 十二项）逐项复核后才动 toml（#29 红输出已加指向）。
4. **多模态 GPU 组合待验**：视觉容器 GPU 后端真机未实测（预设统一禁 GPU 待验证）；`gemma-4-E4B-it-gpu` / `gemma-4-E2B-it-gpu` 待上游修复或 bump。
5. 沿袭：K 回填（等 bump 或剧本灌 N 次）｜`DeepSeek-R1` 误判解除阻塞后 AUTO 验证｜会话删除 UI 立项（激活 delete 卫生位）｜上游 issue 提交｜selftest 快慢网拆分评估（**PASS=92 本机 ~52min，继续走陡**）｜N-W3 `evidenceLevel`｜`TokenUsage.estimated`｜法务 `termsVersion`｜0 tags｜main 快照｜fulltest 接 CI。

---

## 六、下次接手须知

1. **静态闸门基线（本波后）**：`arch-guard.sh` **33 项**；`arch-guard-selftest.sh` **PASS=92 FAIL=0**（本机 ~52min）；`fulltest.sh` = **tests=649 failures=1**（唯一失败 = core-data `SandboxFileScannerTest` Windows 符号链接，既有）；引擎 **1990/2400（余 410）**；`LiteRtLmEngineLoader.kt` 704（#32 钉 ≤900）；`AgentRunner` 2802（#31 钉 ≤2900，**方法级 #12 余 10 才是真压力**）。
2. 🔴 gradle 本机环境：`JAVA_HOME=_j2env/jdk/jdk-21.0.12.1+1` + `GRADLE_USER_HOME=_j2env/gradle-home` + `--offline`；`_ci-tools/localbuild.sh` 在**工作区根**不在仓内。
3. 🔴 **真机（有线 adb）**：`MSYS_NO_PATHCONV=1`；OPPO A13 走 `adb push` + `pm install -r`（直接 install 报 -99）；`push` 本地路径必须写 `D:/…`（MSYS 下不转换）；设备侧 logcat 落盘 `nohup logcat -b all -f /data/local/tmp/x.log &` 事后 `exec-out cat` 拉取（`adb shell <cmd> > file` 会 LF→CRLF 污染二进制）。
4. 🔴 **模型切换脚本**（本波新建，可复用）：`_ci-tools/_w60_set_active.sh <uuid>`（改 datastore `active_model_id` + 重启）；`_ci-tools/_w60_test_model.sh <uuid> <label>`（换模型 + 发 prompt + 收 journal/logcat）；`_ci-tools/_w60_push_models.sh`（批量 push）。模型下载源：ModelScope（`https://www.modelscope.cn/litert-community/<repo>/resolve/master/<file>`，HF 直连被沙箱阻断）。
5. 🔴 **翻转复位必须走 UI 开关**：`lastSeenSwitchOn` 是引擎实例字段，外部改 datastore 不被运行中的 app 感知；中途 Activity 重建（back 退出 + monkey 重启）会清 null ⇒ 失效。**路径 = 对话页 → 底部导航「设置」（勿按 back，用底部 tab）→ 滚动 → 开关 tap → 底部 tab 回对话页**。

---

## 七、CI run id

> **推送后回填**（W52 教训：立即回填）。

| workflow | run id | 结论 | 关键 job |
|---|---|---|---|
| **Build** | `（待回填）` | — | Lint / Assemble Debug / Unit tests |
| **Release** | `（待回填）` | — | — |
