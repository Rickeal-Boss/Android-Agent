# Wave 51 收官交接（improve 分支）

> **唯一权威交接文档**。逐波细节看 `handoff-*.md`；README 台账是特性/路线图的权威版。
> 本文件回答三件事：**W51 做了什么 / 留下了什么判据 / 下一个人从哪接手**。
>
> 基线：W50 收官 `e003509`（已推，CI 双绿）｜本波 tip 见 §六

---

## 一、W51 总账

### 一句话
**P1「模板渲染失败」从归因到修法全部落地（本仓唯一的真代码 P1）+ 生成期自愈 + 热降档换手段（外部复审点名的「唯一活的代码缺陷」）+ UI 批三条 P2 + CI 守卫 #23 与用例数 soft-check；真机复验 P1 四件套通过。**

### 7 个 commit（`e003509` → tip）

| commit | 内容 | 性质 |
|---|---|---|
| `e45915f` | **P1**：出口收口折叠相邻 `Content.Text`（4 处下发点）+ 生成期模板失败自愈 + `TextFoldTest` 17 例 | 行为修复 |
| `6db8eaa` | **热降档改走轮次**（maxTokens 档被采样档案顶回 ⇒ 降档失效 + 日志说谎）+ 4 例单测 | 行为修复 |
| `fce3576` | 订正 `RunTokenLedger` 残留的作废口径「估算≈ / 实测」 | 纯注释 |
| `ed128ce` | **UI 批**：`GlassSegmented` 两处 Text 补 `overflow` + 玻璃→Material 色角色桥接（单一事实源） | UI |
| `f4a7f1d` | 热降档接线 + `renameTo` 返回值检查 + 键盘态 overlay 条件化 + 上下文条截断 | 行为修复 + UI |
| `bce6a4a` | **CI**：守卫 #23（`--summary-only` 退出码契约）+ 用例数 soft-check + 注解回显 + selftest 5 例 | 工具链/CI |
| `c3fa9a2` | 第二批审查的 4 项 P3 收口（浅色 `error` 对比度 / 档位快照同源 / 文案精度） | 质量收口 |

⇒ **arch-guard 22 → 23 项、selftest 47 → 52 例**。
⇒ **全量单测 598 → 619**（+21：`TextFoldTest` 17 + `ThermalGovernorTest` 4）；唯一失败仍是
`SandboxFileScannerTest.kt:184`（Windows 符号链接，**既有基线**）。

---

## 二、🔴 本波最重的技术内容：P1 模板渲染失败

### 2.1 归因（本波完成闭环，W50 只做到一半）

**现象**：Qwen2.5-1.5B 在**工具结果回灌那一轮**的生成期报
`INTERNAL: Failed to apply template: invalid operation: tried to use + operator on unsupported types string and sequence (in template:23 / :27)`；`createConversation` **从不失败**。

**本波新增的一手取证（决定性）**：
1. **抽出设备容器内嵌的真实 chat_template**：`adb exec-out dd` 取
   `…Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm` 前 80MB 内偏移 **16423** 起的窗口，
   得到 HF 原版 Qwen2.5-Instruct 模板。其 **`:23`** = `'<|im_start|>' + message.role + '\n' + message.content + '<|im_end|>'`（user / system / assistant-无-tool_calls 支）、
   **`:27`** = `'\n' + message.content`（assistant 带 tool_calls 支）—— **与真机报错行号逐字对上**。
2. **用真实 `minijinja` 离线复现**（PyPI `minijinja`，隔离 venv）：`content` = 字符串 → 正常渲染；
   `content` = **JSON 数组（哪怕只有 1 个元素）** → 必炸，报错文案与行号与真机**逐字一致**。
3. **`javap` 反编译 0.17.1 AAR**：`Contents.toJson()` **恒返回 `JsonArray`**（`of(String)`/`of(vararg)`/`of(List)` 三重重载殊途同归）；`Message.toJson()` 恒 `add("content", contents.toJson())`。
4. ⚠️ **推翻 W50 的说法**：W50 A/B 报告线索 #4 写「litertlm 的 `Contents` 在单元素时为字符串」——
   **该说法不成立**（第 27 处「描述不成立」）。

**⇒ 推断（H-A 工作假设，未离线证实）**：native 侧（prebuilt `liblitertlm_jni.so`，符号已 strip）
**只在 content 恰好 1 个元素时把它收敛为 string**。
旁证：① 真实模板 + minijinja 实测「content 为数组必炸」；② Java 侧 `Message.toJson()` 恒下发数组；
③ 真机 W50 数据「单结果回灌多数不炸 / 3 结果回灌确定性炸」。
⚠️ **决定性实证仍需真机 A/B**（本波已跑，见 §三）——**在此之前不得声称「P1 已修」**。

### 2.2 修法（离线可改，已落地）

- **`foldAdjacentText(contents)`**（`LiteRtLmEngine.kt` 文件级 `internal` 纯函数）：
  只折叠**相邻连续** `Content.Text`（`"\n\n"` 连接）；非 `Text` 的一切子类
  （`ImageBytes`/`ImageFile`/`AudioBytes`/`AudioFile`/`ToolResponse`）**原样透传、相对顺序不变**；
  `size < 2` 原样返回**入参同一实例**（默认路径零回归）；只要出现过 `Text` 就保证输出**非空**
  （避免空列表 ⇒ `Message.toJson` 不加 `content` 键 ⇒ 语义漂移）。
  🔴 **反例纪律**：**不得**把整个 List 压成文本 —— 那会静默打碎多模态。
- **4 处 user-List 下发点收口**：`buildContents` 出口 / 附件消息构造 / 文本协议 TOOL 回灌 /
  `mergeAdjacentNativeUsers` 拼接。
  判定**不改**的：`Message.tool(Contents.of(ToolResponse…))`（模板 `role=tool` 支是
  `{{ message.content }}`，**无 `+`**，数组安全）、单元素与空列表下发点。
- **生成期自愈**（`onError`）：识别 `Failed to apply template` ⇒ 置 `conversationDirty`，
  且**仅当 `nativeToolChannelActive()` 为真时**证伪 `nativeToolsRejected`。
  理由：根因是 content 数组化、**与通道无关**（文本协议下同样炸）；对纯文本用户证伪只会带来
  无意义的「工具清单写回提示词」副作用。
  **防循环**：`nativeToolsRejected` 置位后 `nativeToolChannelActive()` 恒 false ⇒ 不会反复证伪。
  ⚠️ `conversationDirty` 的补置与生成流 `finally` **冗余**（`!finished ⇒ dirty` 本已覆盖），
  显式补置只为让意图在错误路径可见。
- **落地顺序纪律**：**先主因后自愈**（自愈先行会形成「回退文本协议 → 仍炸 → 每轮重建再炸」的无效循环）。

### 2.3 ⚠️ 明确**不覆盖**（诚实申报）
只要消息含 `ImageBytes`/`AudioBytes`，`content` 就必然 ≥2 个元素 ⇒ **仍可能触发同一模板错误**。
Qwen2.5-1.5B 是纯文本模型，app 侧按 `supportsImages`/`supportsAudio` 本就不向它下发多模态，故该场景不在本波覆盖内。

### 2.4 上游残留（本仓改不了）
native 用 `parser_utils.cc ParseTextAndToolCalls()` 解析模型输出构造 assistant 消息时，
`content` 可能 push **0 / 1 / 2+** 个 text（模型「正文 + 代码块 + 正文」即 2 个）⇒ 那类 `:27`
报错属上游，只能靠 §2.2 的自愈兜住。

---

## 三、真机验证结果（OPPO PDRM00 / Android 13 / serial `13309cc8`）

> 取证档 `/data/local/tmp/_w51p1.log`（**不过滤**全量档）。**先验**：`grep -c 'W/native'` = **169**
> ⇒「0 命中」有意义（观测通道未被削弱）。

### 3.1 P1 复验（复现触发器 = W50 造成**确定性崩溃**的原始题面）
题面（**逐字同题**，仅文件名换 `w51.txt`；全 ASCII）：
```
Do these three steps in order and report each one. Step 1 tell me the current time.
Step 2 create a file named w51.txt in the sandbox with content hello. Step 3 tell me the current time again.
```

| 组 | 工具通道 | `Failed to apply template` | 终态 | 回灌形态 | 工具执行 |
|---|---|---|---|---|---|
| **主组** | **文本协议（OFF）** | **0 命中** | `ModelStopped`（rounds=1） | **3 条结果同批回灌** | `current_time` 真实时间戳 ×2；`file_write` `ok=true`（沙箱文件内容 = `hello`） |
| 对照 | 原生 ON | **0 命中** | `ModelStopped`（rounds=3） | 3 条但**逐轮分解**（1 call/轮） | `current_time` ×2 `ok=true`；`file_write` `ok=false`（模型给绝对路径 `/w51.txt`，沙箱策略正确拒绝） |

- **判据四件套（主组）全部命中、非 vacuous**：模型在 round 0 **一次发出 3 个 `tool_call`**
  （journal `run_1791312454005.jsonl` seq4）⇒ 文本协议下经 `buildContents` 变 **3 个 `Content.Text`**，
  再经 `foldAdjacentText` 折成 1 段 ⇒ `Message.user` 单元素。**W50 无此折叠时即 3 元素 JSON 数组 → 模板 `+` 报错。**
- ⚠️ **对照组判据③ = vacuous / 未行使**：native 下模型把任务**逐轮分解**（未触发「多结果单批回灌」）
  ⇒ 该组**只**对「不炸 + 不 Failed + 通道确激活」有效（通道激活证据原文：
  `原生工具通道：探针通过`、`已注册 13 个工具`、`模型下发 1 个 tool_call（current_time）`×2 + `（file_write）`×1）。
  **不得**把该项记为通过。
- **单文本基线**：`What is 17 times 23?` → `17 times 23 is 391.` ⇒ 默认路径未被破坏。

### 3.2 UI 批真机结果

| 项 | 结果 | 证据 |
|---|---|---|
| **F1** 键盘态 84dp | ✅ | 键盘态输入框—键盘空隙 ≈ **25–36dp**（旧 ≈94dp）；收起态输入框底 2040 < 底栏顶 ≈2165 ⇒ **不压底栏**。截图 `_w51_F1_kbup.png` / `_w51_F1_kbdown.png` |
| **F2** `GlassSegmented` 截断 | ✅（判据达成） | 分段控件渲染 **`C…`**（1 个全字 + 省略号，**无半字截断**）。⚠️ **派单里我写的预期 `CP…` 是错的**（卡片可用宽度只容 1 字）—— **预期值不成立 ≠ 修复不成立**，判据本身（无半个字截断 + 有省略号）成立。截图 `_w51_F2_seg.png` / `_w51_F2_zoom.png` |
| **F3** Snackbar 深色浅色块 | ✅ | 深色态 Snackbar 块色实测 **`(34,36,46)`**（深色块，不再是 M3 默认 `#E6E0E9`）；浅色态为浅薰衣草块 = **已申报的取舍**（浅色 `glassTintElevated` 与米白壁纸对比度低，靠 `onGlass` 保证文字可读）。截图 `_w51_F3_snack_dark.png` / `_w51_F3_snack_light.png` |

### 3.3 还原状态（收尾回读）
原生工具通道 = **开**；`backend=CPU`；`thinking=OFF`；系统夜间模式 = `auto`；
`run-as com.rickeal.agent.debug find files -name '*w51*'` ⇒ **0 命中**；appops 通知 = default/allow。

### 3.4 ✅ 最终产物一致性复验（「被测产物 = 出货代码」闭环，W51 补齐）
⚠️ §3.1/§3.2 的首轮真机验证跑的是 **P3 之前**的包（`10-07 02:38`）。虽然 P3 三处改动
（`LiquidAgentTheme` 去 `error`/`onError`、`ThermalGovernor` 加 `tier` 形参、`ChatRunCoordinator`
档位快照 + 文案）**均不触及 `LiteRtLmEngine` 的 fold/自愈路径**，但按本仓纪律**被测产物必须等于出货代码**，
故用最终 APK 重跑了一遍。

- **被测产物**：`121,281,034 B / 2026-10-08 14:25`（`adb push` + `pm install -r` = Success；
  装机回读 `lastUpdateTime = 2026-10-08 14:37:15` ⇒ **确已换包**）
- **取证档** `/data/local/tmp/_w51f.log`（不过滤）：先验含 native 行（`grep -c 'W/native'` = **19**，
  样本 `… litert_lm_loader.h:158] TFLite model type: TF_LITE_VISION_ENCODER not found …`）
- **判据四件套（全部命中、非 vacuous）**：
  | 判据 | 结果 |
  |---|---|
  | ① `Failed to apply template` | **0 命中**（全量档 `grep -c` = 0） |
  | ② `settled` 非 Failed | `ModelStopped`（rounds=7） |
  | ③ 3 条结果同批回灌 | round0 MODEL `finishReason=TOOL_CALLS`、`toolCalls` 长度 = **3** ⇒ seq4/5/6 **3 条 TOOL 同批**（round1 亦 3→3） |
  | ④ 工具真执行 | `current_time` 真时间戳 ×4；`file_write` round0 `ok=false`（模型给绝对路径，沙箱**正确拒绝**）→ round1 改相对路径 `ok=true`，**沙箱文件真存在且内容逐字 = `hello`** |
- **单文本基线**：`17 times 23 is 391.` ✅；无 `FATAL EXCEPTION` / `ANR`（=0）
- **F3 用最终包重测**（与 P3-1 同源）：深色 Snackbar 背景实测 **RGB(34,36,46)**、文字 (244,245,250)
  ⇒ **不再浅色块**；浅色 (242,243,248)/(16,18,26)。F1/F2 沿用首轮（最终包仅差 P3，不触及键盘态/分段控件布局）
- ⚠️ **诚实排除一个污染 run**：首次复跑 settled=`Failed`，根因**不是模板**（模板失败 = 0），而是
  **`WallClockBudget` 300s 硬预算耗尽**（授权对话框出现时回合被系统暂停 ~5min，run 内 227s > 180s 软预算）
  ⇒ 判为**污染 run，不作 P1 结论**（留档 `_w51f_run_polluted.jsonl`）；干净重跑改用**自动授权脚本**
  `_ci-tools/_w51f_autoauth.sh` 消除人工延迟后顺利收敛。
- ⚠️ **模型输出退化（如实申报，非模板 bug）**：round2–7 反复同参调 `current_time`/`memory_read`，
  触发 `SameParamDeadlock` 提醒后模型自行 STOP ⇒ `ModelStopped`。端侧小模型行为；**P1 只主张
  「不炸 + 通道回灌 + 工具真执行」，三项均命中**。

---

## 四、坑复盘（本波最值得继承的 6 条）

### 1. 🔴 全量单测的「假全量」：`fulltest.sh` 在 gradle 未能执行时**仍汇总残留 XML**
本波实测：本地漏设 `JAVA_HOME` ⇒ gradle 未跑，但脚本仍汇总了**上一批残留的 35 个 XML**，
输出 `tests=389`、**只列 5 个模块**，看起来像一份权威汇总。注入环境后才是真实的
`tests=619`、**9 个模块**。
✅ **判据**：跑全量单测**必须注入 `JAVA_HOME`/`ANDROID_HOME`/`GRADLE_USER_HOME`**
（`_ci-tools/localbuild.sh --shell` 或 export），并**核对模块数 = 9**（`app` / `core-agent` /
`core-data` / `core-design` / `core-engine` / `core-model` / `feature-chat` / `feature-models` /
`feature-settings`）。⚠️ 这是「汇总口径可能假绿」的同族新实例，已列 W52 挂账。

### 2. 🔴 修法的有效性可能依赖**未离线证实**的运行时行为 ⇒ 注释不得写「实测」
P1 修法成立的前提是「native 侧把单元素 content 收敛为 string」——**该点在全部可得制品里都找不到证据**
（Java 侧 toJson 恒数组、C++ `NormalizeContent` 反而 string→数组、JNI 桥接层不在快照内）。
✅ **判据**：这类假设必须写成「**推断（H-x 工作假设，未离线证实）+ 旁证 + 待真机 A/B**」，
**不得**写「实测」；且**同一 diff 内两处注释不得自相矛盾**（本波审查席正是靠
「KDoc 写『实测』 vs 测试文件写『必须真机验收』」抓到该问题）。

### 3. 🔴 「预期值」也是判据的一部分，写错会污染结论
F2 我在派单里写「预期显示 `CP…`」，实测是 `C…`。**若只看「是否符合预期」会误判为未修复**；
正确做法是**把判据与预期值分开**：判据 =「无半个字截断 + 有省略号」（成立），
预期值 = 具体字符数（由控件可用宽度决定，**不该预设**）。
⇒ **写判据时不要预设具体字面量，除非它本身是契约。**

### 4. 🔴 `::warning::` 被 `$(…)` 命令替换捕获后**不会**成为注解
`build.yml` 汇总步用 `out="$(bash scripts/fulltest.sh --summary-only 2>&1 || true)"` 捕获输出 ⇒
`fulltest.sh` 里 echo 的 `::warning::` 只以**字面文本**落进 Summary，**不进 Annotations 面板**
⇒ W50 加它的原意（「人眼可见、比守卫更快堵住静默」）**未兑现**。
✅ **修法**：把注解**回显到 stdout**（`printf '%s\n' "$out" | grep -E '^::(warning|error)::' || true`）。
✅ **判据**：任何「靠 CI 注解暴露」的判据，都要确认它**真的成了注解**，而不是被捕获后变成文本。

### 5. 🔴 设计/派单的清单本身可能不完备 —— 审查席要独立 grep，不要照单核
设计 §B7 只点名订正 `:1947` 与 `:1611-1613` 两处陈旧断言；审查席按「grep 同文件『自愈/降级/兜底』」
**独立发现第三处 `:1417`**（本波新增自愈后，「这条路径不会自愈」不再无条件成立）。
⇒ **改机制时必须全文件 grep 同机制关键词**，且**审查席不得只核派单列出的点**。

### 6. ⚠️ 引用他人报告的行号/事实必须回原文核验（本波第 28 处「描述不成立」）
W50 收官交接写「`ChatParamsSheet.kt:149`（sheet 内容同样底部锚定 + overlay）」——
本波复核：该文件的 overlay padding 实际在 **`:79`**，`:149` 是 `.imePadding().padding(...)`。
⇒ 沿用 W50 立的三步判据：① grep 原文是否真出现；② `wc -l` 反查修订；③ 结论显式标注行号出处。

---

## 五、挂账台账（W52 起）

> 权威版仍是 README 台账 + 本文。W51 起给每条加「**最早可启动波次 + 重启前提**」，
> 超期未动的降级或销账（外部复审建议的 SLA 化，本波先落地在本文）。

### 🔴 优先（W52 首批）

1. **P1 的 H-A 定案**：本波真机复验已**强支持** H-A（文本协议 3 结果同批回灌不再炸、单文本不炸、
   多结果在 W50 必炸），但**未**做「单元素是否也炸」的正面二分（无观测面：app 侧已无法构造
   多元素 content）。**重启前提**：若未来出现「折叠后仍炸」的实例，立即升级为上游缺陷并评估
   「整包提示词文本化」退路。
2. **`fulltest.sh` 假全量**（§四.1）：gradle 未执行时仍汇总残留 XML ⇒ 建议在 gradle 非 0 退出时，
   汇总段**前置**一行显式标注「结果可能来自残留 XML」，或在 `--summary-only` 打印 XML 的时间戳范围。
   **量级**：1~5 行。
3. **层 3 判据补日志 `useNativeTools`**（W50 挂账 #2 未动）：不落则层 3 永远 `⛔`。
4. **`DeepSeek-R1-Distill-Qwen-1.5B` 被 `inferFamily` 误判 `thinking=false`**（W48 让
   `enable_thinking` 恒发 ⇒ 可能关掉其推理）。**重启前提：拿到该容器 + 真机验 AUTO。不得无容器盲改。**

> **🔴 W52 外部审查补入（独立条目，与上面 #1 不是同一条）**：
> **多模态消息的 P1 未覆盖** —— 含 `ImageBytes`/`AudioBytes` 的消息 `content` 恒 ≥2 元素 ⇒ 仍可能触发同一模板 `+` 报错；
> 而生成期自愈只置 `conversationDirty`（重建会话 + 清水印），**不改变 content 结构** ⇒ 每次重发同一多模态消息都可能再炸
> （文档原写的「防循环」只防「反复**证伪**」，**不防「反复炸」**）。
> **用户可达**：`feature-models/.../ModelPresets.kt` 有 **6 个**视觉预设（label 含「· 视觉」，全 `image=true`），用户可见可加载。
> **与 #1（H-A 定案）分列**：#1 的前提是「折叠后仍炸」（H-A 被证伪），本条是**已知未覆盖**（多模态根本没折成单元素），
> 触发条件明确、与 H-A 成立与否无关。
> **重启前提**：① 视觉容器 `chat_template` 取证（`adb dd` + `minijinja` 离线复现，复用 W51 方法，**无需真机窗口**）
> —— 若模板不用 `+` 拼 content ⇒ 销账；若用 ⇒ 升级 P1，评估「预设标注 / 独立 content 结构 / 上游缺陷」。
> ② 已做**零代码保护**：6 个视觉预设的 `backendBasis` 加「⚠️ 图片输入尚未验证」标注（与既有「视觉 GPU 未实测 → 禁 GPU」同纪律）。

### 🟡 常规

5. **真机验收积压**：Wave 33 起累计 **35** 条，台账 35 条（**28** `✅回收` / **5** `⚠️部分` / **2** `⛔不适用`）。
   W50/W51 未覆盖项：通知档B（A13 不可构造）｜Gemma 两档｜层5「压缩触发重建」子路径｜
   记忆磁盘满·只读｜W37 UI 手感｜lint gate｜W38 行为变更｜W40 验收面。**W51 新增回收 4 项**（P1 主组 + 单文本基线 + F1/F3；F2 见 §3.2）。
6. **F4 三级文字 token「同角色不同色」6 处**（P3，本波**有意未做**）：应先立「语义角色→颜色」
   单一映射表再全仓对齐，一次性大改回归面过大。⚠️ 深色 `onGlassSubtle`(0x80) 是**脆弱达标**
   （余量 <10%）⇒ **不要降 alpha**。
7. **数据回填 / 阈值回填**：口径见 W49 交接；阈值需 ≥2 个坏容器样本。
8. **`TokenUsage.estimated` 治根字段**（唯一能长期不撒谎的方案）。
9. **通知文案「渠道」误导**（`GenerationNotifier.kt` + 枚举 `CHANNEL_DISABLED`；判据只查应用级）。
10. **法务** `TODO(legal)`×4 / `termsVersion`（**必须先于任何法务文本替换落地**）。
11. **上游/已知限制**：`gemma-4-E2B-it-gpu`（GPU 输出乱码；CPU init `NOT_FOUND`）｜
    `MiniCPM-V-4-int8`（`Unsupported model type`，建不了会话）｜MiniCPM5 OFF 态规划外溢
    （2B int4 固有，**别动 `thoughtChannelDefsFor`**）。
12. **`main` 分支快照声明过时**（仍写「活跃开发在 harness」，实际 improve 领先 main 480 提交）。
13. **README 台账聚合计数漂移**（W52 已订正）：原聚合句「台账已记 31 条 / 27 ✅ / 4 ⛔或⚠️部分」**三项全错**；逐条实数（`docs/10-device-acceptance.md` §11.0.1，`awk -F'|'` 计结论列）= **35 行 = 28 ✅ / 5 ⚠️ / 2 ⛔**。`README.md:336` 已按实数订正。
14. **打 tag**：用户已裁定 W50 不打；建议顺序 = 法务清零 → P1 复验 → UI 批 → tag。
    可先用**内部锚点分支**（非 tag、不触发 workflow）解决回滚锚点。

---

## 六、外部报告对账

| 报告 | 审的修订 | W51 处置 |
|---|---|---|
| 复审 13（三线融合） | `e003509`（W50） | P1 涉及面修正（2 → **4 处**）已采纳并落地；顺序纪律（先主因后自愈）已遵守；UI 批三条已做；守卫 #23 + soft-check 已做；**未采纳**：`--summary-only` 守卫防不住 `files==0`（改由 `::warning::` + 回显注解负责） |
| v13 深审 | `e003509` | P1 归因的「单元素取证」已补（本波 §2.1 第 3 条）并**推翻了 W50 的单元素假设** |
| 每日简报（2026-10-07） | `e003509` | 其两大建议（实施 P1 修法、落实 UI 三条 P2）**正是本波内容**；「引擎 0.11.0 过时」在仓内**不成立**（README 早已是 0.17.1）；采纳其「统一对外口径」建议 ⇒ README「纯端侧零网络依赖」改为精确表述 |

---

## 七、下次接手须知

1. **推送纪律**：`GIT_TERMINAL_PROMPT=0` + `-c credential.helper=`（空）**两者都要**（否则 GCM 无凭据
   **静默挂起**）；`-c http.sslVerify=false`（绕 MITM 的 `CRYPT_E_NO_REVOCATION_CHECK`）；
   **PAT 只以 URL inline 一次性使用、绝不落盘**，用完 `git remote set-url` 复位；推前 `git push --dry-run` 核
   （`git ls-remote` 匿名读 public 库会**假绿**）。⚠️ 本地 `origin/improve` 跟踪引用可能**陈旧**
   （本波实测它落后 29 个 commit），以 `git ls-remote` 为准。
2. **静态闸门基线**（改动前）：`arch-guard` **23 项全 OK**、`arch-guard-selftest` **PASS=52 FAIL=0**
   （本机 ~21 min ⇒ 给 ≥600s 或 `run_in_background`）、`scripts/fulltest.sh` = `tests=619 failures=1`
   （唯一失败 = `SandboxFileScannerTest.kt:184` Windows 符号链接，**既有基线**）。
3. **跑全量单测必须注入环境**（否则假全量，见 §四.1）。
4. **纯文档提交 ⇒ 两条 workflow 都 0 run 且不报错** ⇒ 需出包必须 `workflow_dispatch`。
5. **设备**（OPPO PDRM00 / A13，serial `13309cc8`）：adb = `_j2env/sdk/platform-tools/adb.exe`
   （**不在 PATH**），Git Bash 需 `MSYS_NO_PATHCONV=1`。**装机必须** `adb push` + `pm install -r`
   （直接 `install -r` 报 `Failure [-99]`）。
6. **UI 自动化**：`_ci-tools/_w49_ui.py`（`dump` / `find` / `tap` / `scrollcard` / `swipe`）。
   ⚠️ `uiautomator dump` **对 Compose 的 bounds 上报不可靠**（导航栏 `Text` 报 8px 实际 ≈38px）
   且会返回被遮挡节点 ⇒ 判「尺寸/可见性/覆盖范围」**必须截图目视**。
7. **中文输入不可行**（`input text` 非 ASCII 在设备侧 NPE）⇒ ASCII 题面。
8. **logcat 双档**：涉 native 判据必须落**不过滤**全量档，且**先验该档确含 native 行**才可说「0 命中」有意义。
9. **`_plans/` 在 git 仓库外** ⇒ 真机报告不 commit；关键结论**必须折进仓库文档**才算落档。
10. **判定三态纪律**：`✅ 通过` / `⚠️ 部分`（不得整体记 ✅）/ `⛔ 不适用`（**不是通过**）。
    **凡「目检」判据，报告里必须逐条抄出被目检的原文**；**没触发判据写「vacuous/未行使」**。

---

## 八、CI run id

推送 `e003509..157d7ff`（**8 commit**，fast-forward）后**两条 workflow 自动触发**（本波含
`scripts/**` / `.github/workflows/**` / `core-*/**` / `feature-*/**` 等非文档改动，无需 `workflow_dispatch`）：

| workflow | run id | 结论 | 关键 job |
|---|---|---|---|
| **Build** | **`37737716605`** | ✅ **success** | `Lint (baseline gate)` / `Assemble Debug (JDK 21)`（含 **Architecture guard** + **Architecture guard self-test**）/ `Unit tests` **三 job 全绿** |
| **Release** | **`37737716611`** | ✅ **success** | `Build & (optionally) sign release` 全步骤 success；`Gate tag release on signing secrets` / `Publish GitHub Release` = skipped（分支推送非 tag，符合预期） |

### 8.1 守卫网在 CI 上的实跑（从 job 日志正文取，非只看 job 结论）
- **Build `Assemble Debug` job**：`OK  ` 行 = **23 条**（首条 `litertlm 仅存在于 core-engine`、末条
  `fulltest.sh 的 --summary-only 分支恒 exit 0 且不经 exit 2`）；末行 `架构守卫全部通过。`；
  `自测结果：PASS=52 FAIL=0`。
- **Release job**（16KB 对齐步亦跑守卫自测网）：`OK  ` 行 = **23 条**；`自测结果：PASS=52 FAIL=0`
  ⇒ **Build/Release 对称，两条都取到字面层**。
- 两条的**行首 `::` 真注解计数均 = 0**（日志里的 `::error::` 都是**脚本源码行被回显**，不是注解）；无 `FAIL [`。
- 自测新增用例在列：`PASS [case26 exit 2 未被 SUMMARY_ONLY==0 包住 (第 23 条)]`、
  `PASS [case27 --summary-only 早退载体缺失 (第 23 条)]`。
- 注解级旁证：Build / Release / Lint **全部 job 均无 error 级注解**。

### 8.2 产物
| 来源 | 产物 | 大小 |
|---|---|---|
| Release | `liquidagent-release-apk-improve`（签名 release APK + AAB 打包） | **73,692,200 B** |
| Release | `liquidagent-debug-improve` | 41,309,397 B |
| Release | `liquidagent-release-mapping-improve`（R8 mapping） | 4,579,629 B |
| Build | `liquidagent-debug-157d7ff…` | 41,309,478 B |
| Build | `lint-reports-157d7ff…` | 24,201 B |

对比 W50：release **73,691,893 → 73,692,200**（+307 B）／debug **41,303,807 → 41,309,478**（+5,671 B）／
mapping **4,576,968 → 4,579,629**（+2,661 B）—— 增幅与「`foldAdjacentText` + 生成期自愈 + 主题桥接 +
21 个新单测」相符，**无异常膨胀**。

### 8.3 lint gate **真实通过**（下 artifact 解析，非只看 job 结论）
`lint-reports-157d7ff…` 解析结果：issue 总数 = **1**，唯一一条为 `LintBaseline`（Hint，lint 指向
baseline 文件自身，原文 `3 errors and 1 hint were filtered out because they are listed in the baseline file`）
⇒ **真实新问题 = 0**，冻结 **4 条**全被 baseline 吸收（与 W49/W50 一致）。

### 8.4 ⚠️ 本波 CI 侧的「观测面缺口」（如实标注，**不记通过**）
- **用例数数值（期望 619）与「未触发低于基线 ⚠️」= `⛔ 观测面不可达`**：汇总步把 `fulltest.sh --summary-only`
  的输出与 soft-check 结果都写进 `$GITHUB_STEP_SUMMARY`，而 **GitHub 不提供 step summary 的 API**，
  job 日志正文里**没有** `tests=` 数值。可判的只有「`Run unit tests` 步 = success」+「汇总步 = success」
  这一层 ⇒ **步绿 ✅ / 数值 ⛔**，两者**不得**合并记成「通过」。
- **`::warning::` 回显通道本波未被行使（vacuous）**：本波无 warning 可回显 ⇒ 该修复**未取得行使证据**，
  只能靠 W51 开发期的逐字复刻实测（空目录跑汇总步 ⇒ `::warning::` 出现在 stdout、`EXIT=0`）。
- 同理：`fulltest.sh` 的 `files==0` 分支 `::warning::` 本波**未触发**（测试跑全了）⇒ 亦属未行使。

### 8.5 check-run 注解（`/commits/157d7ff…/check-runs`）
共 12 条（Lint 4 / Assemble 6 / Unit tests 2），**逐条为平台级**（`Android SDK root` / `ubuntu-latest
将迁移` / `Node.js 20 deprecated` / `Built APK:` 等），**无一条来自本仓脚本** ⇒ 无「用例数低于基线 ⚠️」
注解、无 `::warning::未找到任何 TEST-*.xml`、无 `::error::`。
⚠️ 平台限制：soft-check 的 ⚠️ 与 `fulltest.sh` 的 `::warning::` 都只写 Summary，**若触发注解面板也不显示**
⇒ 不得据「注解面板没有」推断「没触发」。

### 8.6 本波 CI 侧 **vacuous / 未行使** 清单（均为「条件未触发」，**不是失败**）
| 项 | 状态 | 依据 |
|---|---|---|
| `::warning::` 回显通道 | **未行使（vacuous）** | 两 run 无任何 warning/error 可回显；机制已由开发期离线复验证实（空目录跑汇总步 ⇒ `::warning::` 出现在 stdout、`EXIT=0`） |
| `fulltest.sh` 的 `files==0` 分支 | **未行使（vacuous）** | 本波测试正常产出 `TEST-*.xml` ⇒ 未触发 |
| soft-check「低于基线」⚠️ 分支 | **未行使（vacuous）** | 总数 ≥ 598（新增 21 例）⇒ 未触发 |
| arch-guard #23 的**红面**（生产侧） | **未行使** | 本波 `fulltest.sh` 合规；但 selftest 的 **case26/27 红面已在 CI 行使** —— `Architecture guard self-test` 步绿即证明它们已跑并断言通过 |

### 8.7 CI 侧**观测面缺口**清单（如实记录，供后续波次参考）
1. **job 日志正文鉴权专属**：未鉴权 `GET /actions/jobs/{id}/logs` = **403**；本仓 helper 内 PAT 已失效（401）
   ⇒ **无有效凭据时无法离线取日志正文**（本波由主理人以新 PAT inline 取回）。
2. **job Summary 面板 API 不可达**：`check-run.output.summary` 为空 + 公开 job HTML 不含 Summary 文本
   ⇒ 逐模块用例数 / soft-check ⚠️ **只能肉眼读**。
3. **artifact 下载鉴权专属**：未鉴权 = **401** ⇒ lint-reports / unit-test-reports 离线不可取。
4. **可用降级通道（public 仓库）**：未鉴权可读 `runs` / `runs/{id}/jobs`（含 step conclusion）/
   `check-runs/{id}/annotations` ⇒ 足以做「**步级 + 注解级**」判定，**不足以**做「日志正文 / Summary 数值」判定。
   ⇒ **凡结论依赖后两者，一律标 `⛔`，不得降级成「通过」。**
5. **运维项（独立于本波）**：helper 内 PAT 过期 ⇒ 影响所有依赖它的离线取数脚本（已记 W52 挂账）。

### 8.8 lint 复核的**独立复算限制**（CI 席如实申报）
CI 席因鉴权所限**无法独立复算** lint artifact 的内部计数（未鉴权下载 = 401）⇒ 该层标 `⛔`，
但给出**三重旁证**：① `Run Android Lint (baseline gate)` 步 = success（硬门禁 `abortOnError=true` +
`warningsAsErrors=true` ⇒ 0 新问题）；② Lint job 注解 = 3 notice + 1 warning（Node.js 20，infra，非本仓）、
**无 error**；③ artifact 大小 **24,201 B 与 W48/W50 的 lint-reports zip 完全一致** ⇒ 报告内容未变，
与「0 新问题 + 1 Hint」自洽。**方法合规性**（下 artifact → 解析 issue 集 → 对比 baseline）已判定 ✔。
