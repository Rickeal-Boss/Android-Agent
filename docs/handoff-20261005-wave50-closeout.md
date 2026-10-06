# Wave 50 收官交接（improve 分支）

> **唯一权威交接文档**。逐波细节看 `handoff-*.md`；README 台账是特性/路线图的权威版。
> 本文件回答三件事：**W50 做了什么 / 留下了什么判据 / 下一个人从哪接手**。
>
> 基线：W49 收官 `12a0a3f`（已推，CI 双绿）｜本波 tip `0f6506e`（**未推**）

---

## 一、W50 总账

### 一句话
**§11 真机回收（W49 遗留的最大敞口）+ 三件外部报告 P3 收口 + CI 可观测性补齐；本波 0 个真代码缺陷修复，1 个 P2 级自愈缺口登记挂账。**

### 9 个 commit（`12a0a3f` → `a56d5f6`）

| commit | 内容 | 性质 | 闸门 |
|---|---|---|---|
| `312ab98` | C1 `commit()` 销毁竞态 KDoc 声明 | 纯注释（+20/−0） | arch-guard OK / compile OK |
| `6c9d4d7` | C3 `conversationId` 所有权声明 | 纯注释（+8/−0） | 同上 |
| `1efb8eb` | C6 `dismissRecovery` 失败出口补诊断日志 | 仅观测 | 同上 |
| `1d8a397` | Step 0 删死字段 `ChatUiState.conversationId` | 零行为（+4/−5） | `:feature-chat` 38 tests 0 fail |
| `7214b5b` | `scripts/fulltest.sh --summary-only` + 守卫 #22 + selftest case24/24b/25 | 工具链 | arch-guard 22 项 / selftest 47 |
| `932f804` | `build.yml` 非阻断用例数汇总步骤（job summary） | CI 可观测性 | YAML 解析通过 |
| `0f6506e` | P3-2：`--summary-only` 无 XML 时不再误印 gradle 退出码 | 工具链 | `bash -n` rc=0 / 22 项 / 47 |
| `0b2b5cb` | 上下文口径标签 B 落地 + 清掉「引擎回报」错误措辞 | 文案/KDoc（零行为） | 4 files +46/−20 |
| `a56d5f6` | P3-1：C6 诊断日志 `info` → `warn` | 仅观测（零行为） | 1 file +7/−3 |

⇒ **arch-guard 21 → 22 项、selftest 44 → 47**（W49 收官时为 **21 项 / 44 例**，W50 增 1 项 +3 例）。
⚠️ 上表前 6 个已过严质衡第一轮审查（**通过，无 P0/P1/P2**）；`0f6506e` 由主理人代做 + 他复核；**`0b2b5cb` / `a56d5f6` 经主理人独立核验为「零行为改动、质量高」**。

### §11 真机回收：9 项 ✅ / 4 项 ⛔
详见 `_plans/wave50-device-verification.md`（仓库外）与 `docs/10-device-acceptance.md` §11.0.1 台账。

**✅ 回收**：R1 层1 探测通过｜R1 层2 注册 13 工具｜**审批卡红线（`file_write`，截图实证）**｜沙箱子目录下钻 + 面包屑 + 返回上溯｜符号链接剔除沙箱外｜记忆 >2000 字符保持打开｜通知档A（缺权限）｜返回键两段式｜回显四关键字（Qwen 档）

**⛔ 不适用/无法验证**：R1「≥3 轮长任务」子前提（模型侧限制，见 §三 P1）｜通知档B（ROM + A13 双重限制，该状态不可构造）｜顺位 8 的 Gemma 两档（时间未覆盖）｜P1 native template 渲染失败（待 A/B 定性）

---

## 二、坑复盘（本波最值得继承的 9 条）

### 1. 🔴 「判据被行使」≠「判据被验证通过」——**vacuous 判定**
§11 层 3 的判据是 `原生工具通道判据不一致`（`AgentRunner.kt:811`，**每轮末尾**比对 `sessionDiagnostics.nativeToolChannel != useNativeTools`，`:1024-1027`）。
设备日志里该 WARN **0 命中**。看起来是「通过」，实际是：**判据依赖「引擎侧工具通道真的失效」这一特定失效模式**，而该场景本波**没发生** ⇒ **0 命中 = 判据未被行使（vacuous）**，不是被验证。
**四行三态口径**（收进台账判据）：
| 情形 | 判定 |
|---|---|
| 跑完 + 出现该 WARN | `✅回收` |
| 跑完 + 无 WARN + **能证明**引擎侧真失效 | `❌不符`（漏报，真缺陷） |
| 跑完 + 无 WARN + 无法证明漂移 | `⛔无法验证` |
| **没跑完**（被上游缺陷中断） | `⛔无法验证`，注明「连比对都没发生」 |

### 2. 🔴 引用外部报告/他人文档的行号，必须回**原文** grep + 按行数反查修订
**我本人踩了这个坑**（第 23 处描述不成立）。deepdive 报告自述审 `fe6044f`（`ChatRunCoordinator.kt` 1152 行），其**自引锚点全部落在 `fe6044f`** —— 报告**自洽**。而那组看似「报告给出」的行号（`commit():1074 / conversationId:67 / dismissRecovery:306`）**在报告原文 grep = 0 命中**，真实出处是 `wave50-design.md` 表格里标题为「报告口径」的一列 —— **方案席自己 grep `12a0a3f` 得到的值被回填进了那一列**。
我未回原文 grep 就采信，还写下「报告自称基线与引用行号不符」⇒ **差点把一处不存在的问题记成第 24 处描述不成立** —— **把不存在的错记下来，比漏记更坏**。
✅ **三步判据（机械可执行）**：① `grep` 该行号在**原文**是否真实出现（零命中 ⇒ 不可信）；② `wc -l` 对比原文自述文件行数，反查它说的是哪个修订；③ **结论里显式标注行号出处（原文 / 中转）**。
🔴 **同族总判据**：任何「X 为 0 / X 与 Y 对齐」的结论，**都须先证明 X 的观测通道未被削弱、Y 的来源可回溯**。
- 例 1：`logcat -s <TAG>` ⇒ 「0 命中」只是「native 行被过滤掉了」；
- 例 2：`design §0.3` 表格 ⇒ 「行号与源码对齐」只是「对齐了方案席自己填的值」。

### 3. 🔴 同一文件内两处注释可能自相矛盾 —— 改机制时要 grep 全文
- `LiteRtLmEngine.kt:1354-1356`（`54a80cf` **09-30** 引入）：「这条路径**不会自愈**：失败点在 `sendMessageAsync` 而不是 `createConversation`，`nativeToolsRejected` 不会被置位 ⇒ 每遇一次炸一次」
- `LiteRtLmEngine.kt:1947`（`04dcb77` **09-29** 引入）：「半套配对是必炸的，**而炸了能自愈**」

前者是**后者的次日推翻版**，但改前者时**没回头改后者** ⇒ 第 25 处描述不成立。
✅ **判据**：在同文件 grep 同一机制的关键词（「自愈」/「降级」/「兜底」），若两处断言相反 ⇒ **至少一处已过期**；修机制时必须 grep 全文相关注释，别只改自己新加的那处。`git log -L` 可定成因。

### 4. 🔴 自愈分层要核「覆盖哪个失败点」，不是「有没有自愈」
本仓对「原生工具通道」有自愈，但**只覆盖 `createConversation` 失败**（`retriedWithoutTools`，`LiteRtLmEngine.kt:1256-1274`）。而设备侧暴露的失败在 **`nativeSendMessageAsync`（生成期）** ⇒ **自愈盲区**。
✅ **判据**：读自愈代码时问「它包住的是哪个函数调用」—— 建会话与生成是**两个不同的失败面**；只覆盖一个时，另一个就是盲区。

### 5. 🔴 审批类判据要先确认「该工具在当前档位是否真的需要审批」
§11 要求验「审批卡弹出」，最初打算用 `current_time` ⇒ **不弹卡**。回源码发现 `DateTimeTool.kt:16` 无 `dangerous`/`requiresConfirmation` ⇒ `WORKSPACE_WRITE` 档下 `needsApproval=false`（`AgentRunner.kt:1693-1697`）⇒ **不弹卡是正确行为**。
✅ **判据**：验「审批/确认/拦截」类 UI 前，先 grep 该动作的**触发条件**（是否有 `dangerous`/`requiresConfirmation`/档位要求）；否则会把**正确行为误判成缺陷**。红线须用**真正需要审批**的动作验（本波用 `file_write`）。

### 6. 🔴 守卫只钉「结构/存在性」，不钉「措辞」
P3-2 改的是 `fulltest.sh` 同一文件内的输出措辞，而守卫 #22 钉的是「`--summary-only` 分派臂存在」⇒ **不同轴，不加 selftest case**。
✅ **判据**：新守卫只保护「会被后续改动悄悄删掉的结构」；为一个措辞断言造 case 会让自测网只增不减（本仓已 32 → 47）。
⚠️ **反面**：`scripts/fulltest.sh --summary-only` 若未来被误改成「找不到 gradle 也 exit 0」，`build.yml` 那个非阻断步骤会**静默输出 0 个用例数却显示成功** ⇒ 这类**退出码契约**才值得一条守卫（已排 W51）。

### 7. 🔴 「未触发的论断」冒充「已验证的排除」—— 强度等同假绿（第 26 处）
我要求严质衡改 P1 措辞时，他意识到**自己的排除性论断本身是无效推理**：
> 初版写「本仓下发结构 = 官方 SDK schema ⇒ **本仓无结构偏差**」。但**文本协议下本仓根本不构造 `tool_calls`** ⇒ 报错发生在**不含 `tool_calls`** 的路径上 ⇒ **该论据未被触发，不能用来排除。**

✅ **判据**：写「X 不是原因」前，必须先证明**「X 所在路径在本次失败中确实被走过」**；否则那是**未触发的论断冒充已验证的排除**，**强度等同于假绿**。
🔴 与本仓既有纪律**同族**：守卫 #19/#22「只认代码位」（不许被注释骗过）｜存证「回原文 grep 确认行号真出现」—— **都是「别拿没被验证的东西当验证通过」**。本仓「描述不成立」累计 **26 处**，而这条是**方法论级**，比任何单点修复都值钱。

### 8. 🔴 `balance_check.py` 是 Kotlin 括号配平器 —— 对 bash/YAML/README 会误报
W50 改 bash/YAML 时它报 3 处 FAIL（`build.yml:205` / `fulltest.sh:47` / `arch-guard-selftest.sh:173`），全是 bash `case` 臂 `)` 的**误报**。
✅ **判据**：只对 `.kt` 文件跑它；bash 用 `bash -n`；**report 里要写明「本次无 Kotlin 改动，未跑 balance_check」**而不是让读者以为漏跑了。

### 9. 🔴 `uiautomator dump` 的 bounds 对 Compose **不可靠** —— UI 取证必须截图目视
本波 UI 审查中，主理人**基于 `dump` bounds 报了 2 处不存在的问题**（「导航栏文字被压成 8px」「底栏覆盖设置页正文」），截图放大后**双双证伪**。
- 实测：导航栏 `Text` 报 **8px**、实际 ≈**38px**（同容器 icon 报 66px=22dp **准确**）；`dump` 还会**返回被遮挡节点**（键盘态导航栏被键盘遮住仍返回）。
- ⇒ 判「文字是否被裁 / 节点尺寸 / 覆盖范围 / 可见性」**必须截图目视或像素分析**。
- ⚠️ **例证要精确**（审查席证伪了主理人另两条例证）：`ScrollView w=384 vs 480` 实为漏算 `GlassDefaults.ContentPadding`；`h=459 vs 820` 是量纲错配（视口高 vs 内容高）⇒ 正确表述 =「对**被裁剪/被缩放容器内**的节点不可靠」。
- ⚠️ **滑动起点必须在目标节点 bounds 内**（起点在外 ⇒ 滑动无效，极易误判「控件不可达」）。
- 🔴 **同族**：`logcat -s <TAG>` 过滤掉 native 日志（「0 命中」假绿）—— **都是「观测通道被削弱却当成结论」**。本波 UI 审查共纠正 **6 处假阳性**，其中 **4 处源于此**。

---

## 三、挂账台账（W51 起）

### 🔴 P1（本波最高优先，**本仓输入侧为主 + 自愈缺口**）
**现象**：Qwen2.5-1.5B（`multi-prefill-seq_q8_ekv4096`）在**工具结果回灌那一轮**的生成期模板渲染失败：
```
INTERNAL: Failed to apply template: invalid operation:
tried to use + operator on unsupported types string and sequence (in template:27 / :23)
```
`createConversation` **从不失败**（两组第 0 轮都建会话成功）⇒ 失败点固定在 `nativeSendMessageAsync`。

**真机 A/B（决定性，四组对照）**：
| 组 | 通道 | 回灌形态 | template 错误 | 终态 |
|---|---|---|---|---|
| 20:06–20:07 | 原生 ON | `Message.tool` 1 条 | **0** | ✅ |
| 20:15 / 20:17 | 原生 ON | `Message.tool` 1 条 | 2 次（`:27`+`:23`） | ✅ / ❌ |
| 22:14 组A | **文本 OFF** | `Message.user` **3 条压成 1 条** | 2 次（`:23`），重试也炸 | ❌ `Failed rounds=1` |
| 22:22 组B | **文本 OFF** | `Message.user` 1 条 ×6 | **1 次**（重试救回） | ✅ `ModelStopped rounds=6` |

🔴 **归因 = 本仓输入侧（主因，已定案闭环）**，主线 = **`Contents` 多元素序列化成 JSON 数组**。
✅ **决定性取证（严质衡反编译 + 主理人独立复核）**：
```
javap -classpath _litert_forensics/litertlm_0171_classes.jar \
      com.google.ai.edge.litertlm.Contents
→ public final com.google.gson.JsonArray toJson$third_party_...();
```
⇒ **`Contents.of(多元素)` 必定序列化为 JSON 数组，不是 string**（主理人已用工作区 JDK 21 独立复跑，签名一致）。
✅ **跨 C++ 边界亦未展平**（主理人实测 `prompt_template.cc:112-120`）：
```cpp
nlohmann::ordered_json minijinja_inputs;
minijinja_inputs["messages"] = input.messages;   // ← 整包赋值，未展平成 string
...
auto result = minijinja_template_->apply(minijinja_inputs.dump());
```
⇒ **归因完全闭环**：模板侧对 `content` 期望 string、实际收到**数组** ⇒ `string + sequence` 报错。

✅ **本仓修法（离线可改，主因修法不需真机窗口）= 合并多元素为单个 `Content.Text`**，两处下发点：
```
LiteRtLmEngine.kt:1958   Message.user(Contents.of(texts.map { Content.Text(it) }))   ← 文本协议 TOOL 回灌，texts 通常 >1
LiteRtLmEngine.kt:1834   out.add(Content.Text(payload))   :1839   ← buildContents 逐块累加，out 可含多个 Content.Text
```

**为什么不能归给上游**（这条曾被误判，已被 A/B 推翻）：A/B 证明**文本协议下同样炸** ⇒ 上游模板的 `tool_calls` 假设被排除。**两条完全不同的回灌形态（`Message.tool` / `Message.user`）都能触发同一错误 ⇒ 共同的输入侧变量不是 `tool_calls`，是数组化的 `content`。**
⚠️ 上游 Qwen2.5 模板**本身也有缺陷**（`:27`/`:23` 行号不同 ⇒ 渲染的不是同一份上下文；模板烧录在 `.litertlm` 内，本仓改不了），且上游 `prompt_template_test.cc:44-72` **零个 `tool_calls` 用例** ⇒ 该路径缺测试覆盖。

**本仓侧自愈缺口（次因，P2，但这是真正该修的那半）**：生成期**无自愈** —— `onError`（`LiteRtLmEngine.kt:1607-1621`）只做超容文案映射 → `channel.close(EngineException(...))`，**不降级、不证伪 `nativeToolsRejected`、不重建** ⇒ 每遇必走引擎重建，重试再炸就整轮放弃（组A 即此形态）。
✅ **建议修法（≈10 行、零新增架构）**：`onError` 识别 `Failed to apply template` ⇒ 置 `nativeToolsRejected = true` + `AgentLogStore.warn` + 置 `conversationDirty`，下一 run 自动回退文本协议路径。**这正是把 `:1947`「炸了能自愈」那句真正兑现。**
⚠️ **需 +1 次真机窗口**验证：同模型同题重跑，确认第 2 次不再炸。

🔴 **「上一轮失败会污染下一轮」**：组B 炸之前有 `引擎重建：LOCAL 加载失败（LiteRT-LM：上一次生成仍在继续，请稍候重试）` ⇒ 组B 那次「单结果也炸」很可能是**残留 + 历史体积**叠加，**不是**「单元素也能炸」。不写清会让人误得「假设覆盖不了全部 ⇒ 归因不明」。
🔴 **Q3 = P2**：触发条件不是「第 3 次 tool_call 后必炸」，而是「**回灌内容形态 / 历史状态相关**」（组B 6 轮只炸 1 次且被重试救回 ⇒ `ModelStopped`）。

### 🔴 W51 挂账
1. **P1（上面这条）** —— 需 `Contents.toJson()` 取证定案 + **+1 真机窗口**。
2. **层 3 判据补日志 `useNativeTools`** —— ⚠️ **这正是本波层3 只能给 `⛔` 的直接原因**（不落日志 ⇒ 离线永远判不了「引擎侧是否真失效」）。
3. **守卫 #23**：`--summary-only` 分支必须恒 `exit 0`，且**不得**因「找不到 gradle」走 `exit 2` 路径 —— 否则 `build.yml` 非阻断步骤会**静默输出 0 个用例数却显示成功**。
4. **`:1947` 注释订正**：「半套配对是必炸的，而炸了能自愈」已被真机证伪（与 P1 修法同批做）。
5. **通知文案「渠道」误导订正**：`GenerationNotifier.kt:101/:110` + 枚举 `CHANNEL_DISABLED` 都写「渠道」，但判据只查应用级。
6. **数据回填**（独立波次）：口径见 W49 交接（仅 `ModelStopped` run 的最后一条 MODEL、按 USER 交错插入、`role|text` 幂等）。
7. **阈值回填**（P2）：需 ≥2 个坏容器样本；⚠️ E1 已修，现在才具备采集干净判据分布的前提。
8. **`DeepSeek-R1-Distill-Qwen-1.5B`** 被 `inferFamily` 误判 `thinking=false`（含 `qwen` 不含 `qwen3` ⇒ 落 `OTHER`）⇒ W48 让 `enable_thinking` 恒发后可能关掉其推理。**重启前提**：拿到该容器 + 真机验 AUTO。**不得无容器盲改。**
9. **UI 标签治根项 D**：`TokenUsage` 加 `estimated: Boolean`，LiteRT-LM 路径置 true，UI 据此在真引擎上显示「实测」、估算时显示「≈」（改 3 个模块，唯一能长期不撒谎）。
10. **上游/已知限制**：`gemma-4-E2B-it-gpu`（GPU 输出乱码 —— 即便 GPU 真生效也**不能用于验证 GPU 段切换以外的功能**；CPU engine init `NOT_FOUND`）｜`MiniCPM-V-4-int8`（`Unsupported model type`，无法建会话）｜MiniCPM5 **OFF 态规划外溢**（2B int4 固有能力限制，**非 W48 引入**；治它走提示词面或换模型，**别动 `thoughtChannelDefsFor`**）。
11. **法务** `TODO(legal)`×4 / `termsVersion`（**必须先于任何法务文本替换落地**）。
12. **打首个 tag**：用户已裁定 **本波不打**，等正式版本号口径。
13. **本波未覆盖**：通知档B（ROM+A13 双重限制，状态不可构造）｜顺位 8 的 Gemma 两档｜层5 的「压缩触发重建」子路径（源码 `:790-791` 自陈该口径**尚未接入压缩门控**）。

### 🔴 W51 · UI 审查批（W50 真机审查发现，**均未修**，修法已明确）
> 来源：`_plans/wave50-ui-audit-{evidence,plan,review}.md`（仓库外）。**三条 P2 改动量都很小**（1~3 行/处），但用户未裁定本波修 ⇒ 挂 W51。

14. **P2 · 键盘态输入框离键盘多 84dp** —— `ChatScreen.kt:245-249` 的 `bottomBar` 里 `Column.padding(bottom = LocalBottomBarOverlay.current)`（84dp）**无条件生效**，而 `ChatInputBar`（`:271-274`）自带 `.navigationBarsPadding().imePadding()` ⇒ 输入框已被顶到键盘上方，84dp 再叠加。**真机实测空隙 ≈94dp**（density 3.0；`_ci-tools/_ui4_kb.png`）。**判据**：键盘态下底部导航栏被键盘完全遮住（截图证实）⇒ 此时不需要 overlay padding。
    **修法**：`val imeVisible by remember { derivedStateOf { WindowInsets.ime.getBottom(density) > 0 } }` + `padding(bottom = if (imeVisible) 0.dp else LocalBottomBarOverlay.current)`。⚠️ **必须用 `derivedStateOf`**（直接读会随键盘动画逐帧重组；包后收敛到 2 次）。⚠️ **不违反 `ChatSpeedIndicator` 的 R5 红线**（那条针对订阅 `streaming`，与此无关）。
    **同款**：`ChatParamsSheet.kt:149`（sheet 内容同样底部锚定 + overlay）。**良性不必改**：`SettingsScreen.kt:119` / `ToolsScreen.kt:112`（在 `verticalScroll` 内，只多滚到底余量，非可见空隙）。
    🟢 **强旁证**：开发者已在 `ChatParamsSheet.kt:150-152` 处理过同款（删 `navigationBarsPadding`，注释写「内容层再垫一次是双倍空隙」）—— ChatScreen 漏了。

15. **P2 · `GlassSegmented` 文字缺 `overflow` ⇒ 裁切** —— `core-design/.../GlassSegmented.kt:370-378` 的 item `Text` 有 `maxLines = 1` 但**无 `overflow`** ⇒ 默认 `Clip`。**真机证据**：模型卡「计算后端」分段控件显示 **「CP」「GP」「NP」**（第三字符被裁、无省略号）。
    🟢 **本仓已有同款纪律**：`LiquidBottomTabs.kt:1088`（`LiquidBottomTab`）**有** `overflow = TextOverflow.Ellipsis`，其 KDoc 明写「**默认 Clip 在中文下是"切半个字"，比省略号观感差得多**（六路审查 B-P1-3）」⇒ **同一设计系统内纪律未覆盖**。**修法**：补 `overflow = TextOverflow.Ellipsis`（1 行）。

16. **P2 · Snackbar 深色下渲染浅色块** —— `core-design/.../LiquidAgentTheme.kt:98` 的 `darkColorScheme(...)` **只映射 5 个色角色**（primary/onPrimary/surface/background/onBackground），**未映射 `inverseSurface`/`inverseOnSurface`** ⇒ 走 Material3 默认（深色下 `inverseSurface = #E6E0E9` 浅色）。触发点 `ChatScreen.kt:601` 的 `SnackbarHost(hostState = ...)` **未传自定义 `snackbar`**（**全仓唯一** SnackbarHost）。
    **修法**：优先 **映射 `inverseSurface`/`inverseOnSurface`**（深浅两套各 2 行，根因修复、改动最小）；若要玻璃质感则叠加自定义 `snackbar` lambda。

17. **P3 · 三级文字 token「同角色不同色」6 处** —— 对比度**全部达标**（深色 `onGlassSubtle` 最紧 **4.66:1**、浅色 6.68~6.94），问题不是对比度而是**层级不一致**。最典型：分组标题三处两色（`SettingsScreen.kt:745`(onGlass) vs `StorageScreen.kt:327`/`ChatParamsPanel.kt:274`(onGlassMuted) —— 同 App 两个「标题」亮度差一倍）；`GlassSettingRow.kt:89/95` 禁用态**标题=副标题同色**（层级塌陷）；`DiagnosticsScreen.kt:530 vs :555`、`GlassSlider.kt:148/157`、`GlassIndicators.kt:108`、`GlassChip.kt:96 vs GlassSegmented.kt:524`。
    ⚠️ **深色 `onGlassSubtle`(0x80) 是脆弱达标**（余量 <10%）⇒ **不要降 alpha**；若要提升可考虑 0x99（需评估浅色面）。

18. **P3 · `heightIn(max=…) + verticalScroll` 反模式 ≥4 处** —— `ModelCard` 420 / `ModelsScreen` 220·440 / `SandboxFilesScreen` 360。其中**仅 `ModelCard` 藏操作控件**（「加载/探测/删除」按钮随卡内滚动）⇒ **维持既有 B-4（P3）**。
    ⚠️ **一条被证伪的过度推断**：本波曾判「模型卡按钮**默认态**即不可见」⇒ **不成立**（审查席证伪 + 主理人重做取证确认按钮可见）；正确表述 = **只在内容足够高时**（能力位多 / 长文件名 / NPU 警告 / 大字号）才裁掉。

19. **UI 取证方法论（新增跨波判据）** —— 🔴 **`uiautomator dump` 对 Compose 的 bounds 上报不可靠**：导航栏 `Text` 报 **8px**、实际 ≈**38px**（同容器 icon 报 66px=22dp 准确）；且 **`dump` 会返回被遮挡节点**（键盘态导航栏被键盘遮住，dump 仍返回）⇒ **判「文字是否被裁 / 节点尺寸 / 覆盖范围 / 可见性」必须截图目视**。
    ⚠️ **例证要精确**（审查席证伪了我的两条例证）：`ScrollView w=384 vs 480` 实为漏算 `GlassDefaults.ContentPadding`(16dp×2×3.0=96)；`h=459 vs 820` 是**量纲错配**（视口高 vs 内容高）。⇒ 正确表述 =「对**被裁剪/被缩放容器内**的节点不可靠」。
    ⚠️ 附带：**滑动起点必须在目标节点 bounds 内**（起点在外 ⇒ 滑动无效，极易误判「控件不可达」）。

---

## 四、本波外部审查的对账结果

两份外部报告（复审 11 / deepdive-coordinator）审的是 **W48（`fe6044f`）**，W49 已消化大部分；W50 只剩 P3 三件，全部收口。

| 报告开放项 | 处置 |
|---|---|
| D4 selftest 裸 `python` | ✅ W49 已修 `b7704d1` |
| E1 探针采样口径 | ✅ W49 已修 `070389d`（**且实现席推翻了报告的因果描述**） |
| P2「通道 def 判据依赖文件名」 | ✅ W49 的 R-A 从机制上消除 |
| C1 `commit()` 销毁竞态 KDoc | ✅ W50 `312ab98` |
| C3 `conversationId` 所有权 | ✅ W50 `6c9d4d7`（并发现 + 删死字段 `1d8a397`） |
| C6 `dismissRecovery` 失败出口 | ✅ W50 `1efb8eb`（级别 P3-1 待改 `warn`） |

### 新挖出的「描述不成立」清单（第 20–26 处）
| # | 不成立处 | 事实 |
|---|---|---|
| 20 | §11.4「单关通知渠道出另一条 WARN」 | 判据是**应用级** `areNotificationsEnabled()`（`GenerationNotifier.kt:109`），全仓无 per-channel 检查 |
| 21 | §11.1 关键字 `已以 CPU 后端完成加载` | 全仓 0 命中；真串 = `已以降级配置完成加载`（`LiteRtLmEngine.kt:907`） |
| 22 | §11.5「分段控件逐段有反应」 | 非白名单 GPU 是**设计性无反应**（`ModelCard.kt:172 return@GlassSegmented`） |
| 23 | 审批卡红线用 `current_time` | 只读工具 `needsApproval=false` ⇒ **不弹卡是正确行为**；须用 `file_write` |
| 24 | 能力档在参数面板 | 在**设置页**「AI 能对设备做到哪一步」 |
| 25 | `:1947`「炸了能自愈」 | 被同文件 `:1354-1356` 的次日推翻版证伪（生成期失败无自愈） |
| **26** | **「本仓结构 = 官方 SDK schema ⇒ 无结构偏差」** | **审查席自己的排除性论断** —— A/B 的文本协议路径**根本没走到 `tool_calls`** ⇒ 该论据**未被触发**，不能用来排除。**未触发的论断冒充已验证的排除，强度等同假绿**（详见坑复盘 #7） |

---

## 五、下次接手须知

1. **推送纪律**：`GIT_TERMINAL_PROMPT=0` + `-c credential.helper=`（空）**两者都要**，否则 GCM 无凭据**静默挂起**；`-c http.sslVerify=false`（绕 MITM 的 `CRYPT_E_NO_REVOCATION_CHECK`）；**PAT 只以 URL inline 一次性使用、绝不落盘**，用完 `git remote set-url` 复位；推前 `git push --dry-run` 核（`git ls-remote` 匿名读 public 库会**假绿**）。
2. **本波 tip `0f6506e` 尚未推送** ⇒ 推送后 `build.yml` 属 `.github/workflows/**` ⇒ **两条 workflow 都会触发**。
3. **静态闸门基线**（改动前）：`arch-guard` **22 项全 OK**、`arch-guard-selftest` **PASS=47 FAIL=0**（本机 16m44s ⇒ 给 ≥600s 或 `run_in_background`）、`scripts/fulltest.sh` = `tests=598 failures=1`（唯一失败 = `SandboxFileScannerTest.kt:184` Windows 符号链接，**既有基线**）、`:app:lintDebug` 冻结 **4** 条。
4. **纯文档提交 ⇒ 两条 workflow 都 0 run 且不报错**（W49 实测确认：`12a0a3f` 推送后 run 总数不变）⇒ 需出包必须 `workflow_dispatch`。
5. **设备**（OPPO PDRM00 / A13，serial `13309cc8`）：adb = `_j2env/sdk/platform-tools/adb.exe`（**不在 PATH**；`_ci-tools/adb.exe` 不存在），Git Bash 需 `MSYS_NO_PATHCONV=1`。**装机必须** `adb push` + `pm install -r`（直接 `install -r` 报 `Failure [-99]`）。W50 收尾时设备已还原：appops `Default mode: allow`、沙箱无 `w50*` 残留、`backend=CPU / thinking=OFF`。
6. **UI 自动化**：`_ci-tools/_w49_ui.py`（`dump` / `find "文本" --scroll=x,y,x2,n` / `tap` / `scrollcard` / `swipe`）。`uiautomator` **能读到 Compose 语义树**；但「找不到」≠ 不存在（卡内嵌套滚动 / 越界节点）。
7. **中文输入不可行**（`input text` 非 ASCII 在设备侧 NPE）⇒ ASCII prompt + 同题 ON/OFF A/B。
8. **logcat 双档**：涉 native 判据必须落**不过滤**全量档，且**先验该档确含 native 行**（`W/native …`）才可说「0 命中」有意义。`logcat -f` 会**回放既有环形缓冲** ⇒ 统计必须按 PID/时间戳切分。
9. **写守卫前先 grep 实测全仓真实调用形态**（`f(` 与 `f {` 两种）⇒ 双形态判据 + 把易漏形态做进 selftest fixture；**每新增守卫必须同步新增 selftest case**；判据只认**代码位**（`^[^#]*--flag` 之类），否则会被 fixture 注释骗过（R-B 首版即如此，被自己的 selftest 网抓出）。
10. **`_plans/` 在 git 仓库外** ⇒ 真机报告不 commit；关键结论**必须折进仓库文档**（`docs/10-device-acceptance.md` §11.0.1 + 本文件）才算落档。
11. **`fulltest.sh` 未接进 CI 运行步**（定位是「`--continue` 纪律载体 + 本地入口」）；CI 已用聚合 `test --continue`，运行口径本就一致。

---

## 六、CI run id

推送 `12a0a3f..07576eb`（**10 commit**，fast-forward）后**两条 workflow 自动触发**（本波含 `scripts/**` / `.github/workflows/**` / `core-agent/**` / `feature-chat/**` 等非文档改动，无需 `workflow_dispatch`）：

| workflow | run id | 结论 | 关键 job |
|---|---|---|---|
| **Build** | **`37499664308`** | ✅ **success** | `Assemble Debug (JDK 21)` / `Unit tests` / `Lint (baseline gate)` **三 job 全绿** |
| **Release** | **`37499664339`** | ✅ **success** | `Build & (optionally) sign release` 全步骤 success；`Publish GitHub Release` = skipped（分支推送非 tag，符合预期） |

**Release 产物 3 个**：`liquidagent-release-apk-improve`（73,691,893 B，签名 release APK + AAB 打包）｜`liquidagent-debug-improve`（41,303,807 B）｜`liquidagent-release-mapping-improve`（4,576,968 B，R8 mapping）。

**lint gate 真实通过**（下 `lint-reports-07576eb…` artifact 解析，**不是只看 job 结论**）：issue 总数 = **1**，唯一一条为 `LintBaseline`（Hint，lint 指向 baseline 文件自身的标准提示）⇒ **真实新问题 = 0**，冻结 4 条全被 baseline 吸收（与 W49 一致）。

> 判据留档：`uiautomator`/`logcat` 之外，**lint 门禁也必须下 artifact 复核** —— job 结论绿只说明「不超 baseline」，不等于「零问题」。