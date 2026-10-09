# Wave 55 收官交接（improve 分支）

> **唯一权威交接文档**。逐波细节看 `handoff-*.md`；README 台账是特性/路线图的权威版。
> 本文件回答三件事：**W55 做了什么 / 留下了什么判据 / 下一个人从哪接手**。
>
> 基线：W54 收官 `19c1dab`（W54 共 12 commit，其 CI 触发推送 = `ac5a362..04efed3`）｜本波 tip = `improve` 分支最新（W55 共 10 commit，**CI 触发推送 = `19c1dab..6efc0fb`**；`60b86f4` 起为 docs-only 回填/订正，实测 0 run）
> 前置文档：`docs/handoff-20261009-wave54-closeout.md`（W54 逐项记录）

---

## 一、W55 总账

### 一句话
**H-A 毒化定案（离线源码级决定性 + 真机同构建 A/B 双向印证）+ 同步路径模板失败自愈（治本）+ 多模态 L1 剩 5 容器取证（全部数组安全）**：H-A（native 把「恰 1 个 `text` 元素」的 content 数组**收敛为 string**）在 **litertlm v0.17.1** `generic_data_processor.cc:105-112` 找到**决定性源码收敛点**，且 **tip 已删除该收敛** ⇒ **bump 硬风险**（新增第四条前置）；真机证实模板失败在 `sendMessageAsync` **同步抛出**、绕过 `onError` 自愈 ⇒ 抽出 `handleTemplateRenderFailure` 两路径共用；5 个视觉容器模板经 `minijinja` 离线复现**全部数组安全**，**推翻 W53「Qwen 系同源 ⇒ 风险最高」假设**。**无新增 `@Test`** ⇒ `build.yml` 基线维持 **624**。**真机另回收 B1 长审批停表**（审批挂起 ≈334s 不被 HARD 熔断，**自 W52 落地以来首次行使**，台账 #38）。

### 关键现实（如实申报）
- 本波**有 2 处行为修复**：
  ① **同步路径模板失败自愈**（模板失败首次即置 `conversationDirty` + 计数 + 原生通道证伪）；
  ② 🔴 **`activeGenerations` 同步抛出漏减**（`sendMessageAsync` 同步抛出会跳过 `consumeAsFlow` 的 `finally` ⇒ 实例永久 busy、`unload()` 抛「上一次生成仍在继续」——**W50 真机日志已见该症状**）。
  ⚠️ **修复 ① 的范围申报（审查 P2）**：`nativeToolsRejected` / `conversationDirty` / `templateRebuildCount` 均为**引擎实例级**，而 `AgentRunner` 在**首次**失败后即 evict + 新建实例 ⇒ 这些置位**随旧实例被 `close()→releaseInternal` 清零**。故「不再反复炸而不降级」**仅在存活实例的生命周期内成立**；**跨实例持久化 = W56「反复炸防护」项**（进程级 `cid` 键控 store，见 §五 常规 3）。
- **真机验证 = H-A 毒化 A/B 双向**（§三）+ **B1 长审批停表**（§三.2），是本波旗舰判据；其余为离线源码级 / 模板级取证。
- ⚠️ **Arm B（fold OFF）为临时测试补丁**（fold 全局 identity），**测后已完全还原**（`git status` 干净）。
- ⚠️ 修复后的**同步路径自愈真机回归未行使**（真机 A/B 用的是修前构建）⇒ 留 W56（§3.1）。

### 改动清单（10 commit；5 文件；含 2 个纯 docs 订正/回填 commit）

| 文件 | 性质 | 说明 |
|---|---|---|
| `core-engine/.../local/LiteRtLmEngine.kt` | 引擎 | **+** `private fun handleTemplateRenderFailure(raw, role, source)`（模板失败统一处置）；`onError` 分支改调它（**行为等价**）；`conv.sendMessageAsync(...)` 包 `try/catch`（**rethrow**）；两处 `warn` 带**来源标识**（`同步下发` / `异步回调`）；`templateRebuildCount` KDoc 订正（两条路径统一 `++`）；🔴 **审查 P2 收口**：`catch` 内补与 `finally` 同源的收尾（`activeGenerations.decrementAndGet()` + dirty + cancel —— 治「同步抛出漏减致实例永久 busy」）；**H-A 定案口径落档**（`foldAdjacentText` KDoc + 调用点注释 + `summarizeContentTypes` KDoc 同步为已定案） |
| `feature-models/.../ModelPresets.kt` | 预设 | **+** 5 视觉容器（`Qwen2-VL-2B` / `SmolVLM2-500M` / `LFM2.5-VL-450M` / `LFM2.5-VL-1.6B` / `LFM2.5-VL-3B`）`backendBasis` 各补「W55 L1 模板已离线取证数组安全；端到端图片输入仍待真机验证」；`Qwen2-VL-2B` 额外注明**推翻 W53 假设** |
| `docs/10-device-acceptance.md` | 文档 | **+** 台账 **#37**（H-A 毒化 A/B）+ **#38**（B1 长审批停表）；§11.0.1 聚合句 36 → **38**（29 → **31 ✅**） |
| `README.md` | 文档 | 聚合句同步（**38 = 31 ✅ / 5 ⚠️ / 2 ⛔**）+ **新增 W55 波次段** |
| `docs/handoff-20261009-wave55-closeout.md` | 文档 | 本文件（新建） |

⇒ **@Test 实数 = 624**（本波**无新增 / 无删除** ⇒ `build.yml` baseline 维持 624，#26 双绿）。

---

## 二、🔴 本波最重的技术内容

### 2.1 H-A 定案（离线源码级决定性）
`v0.17.1:runtime/conversation/model_data_processor/generic_data_processor.cc:105-112`：

```cpp
} else if (message["content"].is_array() && message["content"].size() == 1 &&
           message["content"][0]["type"] == "text" &&
           !capabilities_.requires_typed_content) {
    // ...always convert the content to a string.
    return ... {"content", message["content"][0]["text"]} ...   // ★ 收敛点
}
```

⇒ native 把「**恰 1 个 `text` 元素**」的 content 数组**收敛为 string**；≥2 元素保数组 ⇒ Qwen2.5 模板 `'…' + message.content + '…'`（`:23`/`:27`）必炸。

**为什么 W53「未找到」**：W53 读的是仓库 **tip** 源码。**tip 已删除该方法**（tip `generic_data_processor.cc` 仅 **92 行**、无 `MessageToTemplateInput` / `requires_typed_content`；改为 `data_utils.cc` 的 `NormalizeContent()`，**方向相反**：string → 数组展开、数组原样透传）⇒ **版本边界陷阱**，「未找到」≠「不存在」。

### 2.2 离线三路径（互相印证，H-A 不再依赖真机）
- **① tag diff**：`NormalizeContent` 为 post-0.17.1 新增；v0.17.1 无；tip 删收敛。
- **② minijinja 版本**：v0.17.1 与 tip 均**精确钉 `=2.14.0`**（与 PyPI 同版）⇒ W53 离线复现忠实。
- **③ JVM 0.17.1 AAR**（`Probe.java` 实跑）：`Contents.of("x").toJson()` = **1 元素数组**；Java 侧恒发数组。

### 2.3 真机同构建 A/B（决定性对照）
见 §三。

### 2.4 同步路径模板失败自愈（治本）
- **新发现**：`Failed to start nativeSendMessageAsync: … Failed to apply template …` 是 `conv.sendMessageAsync(...)` **同步抛出**，**不经** `onError` 回调 ⇒ 引擎 `onError` 的模板自愈分支（W51 置 `conversationDirty` / 证伪 `nativeToolsRejected`；W54 `templateRebuildCount`）对真实路径**失效**（真机日志无 `生成期` / `模板渲染失败`，只有 `AgentRunner.kt:1630/1580` 的引擎重建 warn）。
- **修法**：抽 `handleTemplateRenderFailure(raw, role, source)` 统一处置；`onError` 改调它（**行为等价**）；同步 `sendMessageAsync` 包 `try/catch`（**必须 rethrow**，不吞异常）；两处 `warn` 加来源后缀（`同步下发` / `异步回调`）便于真机区分路径。
- ⚠️ **同一 `catch` 另修一处既有缺口（审查 P2）**：`sendMessageAsync` 同步抛出会**跳过**下方 `channel.consumeAsFlow()` 的 `finally` ⇒ `activeGenerations`（`load()` 侧 `:1280` 已 `incrementAndGet`）**只增不减** ⇒ 实例永久 `isBusy=true`、后续 `unload()` 抛「上一次生成仍在继续，请稍候重试」。已在 `catch` 内补**与 `finally` 逐条同源**的收尾（二者互斥，不重复执行）。
- ⚠️ **抽取时的偏差申报**：`outboundRoleForDiag` 是 flow 内**局部量**（非字段），故函数签名由规范设想的 `(raw: String)` 扩为 `(raw: String, role: String, source: String)`（`role` 随参传入、`source` 区分两路径）。**处置行为与规范逐条一致。**

### 2.5 多模态 L1 剩 5 容器取证（全部数组安全）
方法：HF 直链 `curl -r 0-52428799`（50MB head）→ 定位 `chat_template` → PyPI `minijinja 2.14.0` 按 3 种 content 形状（string / 单元素数组 / 多元素数组）复现。

| 容器 | 模板形态 | 判定 |
|---|---|---|
| `Qwen2-VL-2B` | `is string` + `else` + `for item in content` | ✅ 安全 |
| `SmolVLM2-500M` | 同上 | ✅ 安全 |
| `LFM2.5-VL-450M` / `-1.6B` / `-3B` | `is string` / `is mapping` / `for item in content` | ✅ 安全 |

🔴 **推翻 W53 假设**「`Qwen2-VL-2B` 风险最高（Qwen 系同源）」——其模板用 `is string` + `for`（安全），与 `Qwen2.5-1.5B` 的 `+`（不安全）**不同**。**同家族 ≠ 同模板**。
⚠️ **范围申报**：L1 只验模板对数组的处理；**端到端图片输入（L3）仍未经真机验证**。

---

## 三、真机验证结果（OPPO PDRM00 / Android 13 / debug 包）

> 题面（沿用 W50 组A 原文，ASCII）：`Do these three steps in order and report each one. Step 1 tell me the current time. Step 2 create a file named w55b2.txt in the sandbox with content hello. Step 3 tell me the current time again.`
> 前置：**文本协议**（`nativeToolChannel:false`，settings pb 复核）、模型 `Qwen2.5-1.5B`。

| 臂 | fold | 日志（不过滤全量档） | 结果 |
|---|---|---|---|
| **A**（fold ON） | ON | `多元素 content 下发：折叠前 2 → 折叠后 1` | ✅ **无 `Failed to apply template`**；settle `MaxRounds(rounds=8)`；含 51 条 native 行 |
| **B**（同代码 + fold 全局 identity） | OFF | `折叠前 3 → 折叠后 3；元素类型 Text=3` | ❌ `引擎重建：LOCAL 生成失败（… Failed to apply template: invalid operation: tried to use + operator on unsupported types string and sequence (in template:23)）` + `生成失败：LOCAL 重试后仍失败（… :23）` |

⇒ **元素数（1 vs ≥2）是唯一区分维度** ⇒ H-A 成立。崩溃经引擎重建重试后放弃，**无 `FATAL EXCEPTION`**（优雅降级）。
⚠️ **Arm B 为临时测试补丁**（fold 全局 identity），**测后已完全还原**（`git status` 干净）。
⚠️ **Arm B 隔离难点**：fold 是**多路径承重**（不仅文本协议 TOOL 回灌，系统提示词 + 首条 user 合并也走同一 fold）⇒ 只关两处调用点**未隔离**（仍在第三处折叠），改为**全局 identity** 才构成「同一消息、只切 fold」的最干净对照。
🔴 **本波新发现（由 Arm B 日志暴露）**：模板失败**不经 `onError`**（见 §2.4）⇒ W54 `templateRebuildCount` 在毒化测试中 **⛔ vacuous**（未被行使）——本波已修（§2.4）。

### 3.1 B1 真机长审批停表（首次行使 ✅，台账 #38）

> B1 自 W52 落地以来**一直 vacuous**（审批 ~7s 即过，构造不出长等待）。本波用「**审批对话框本身即暂停开关**」构造成功（**零产品代码改动**）。

构造：`file_write`（需授权）触发审批对话框后**故意不点**、保持 ≈334s（周期性 `input keyevent 224` 保屏）再授权。
题面（ASCII）：`Create a file named b1.txt inside the sandbox with content hello. Use the file_write tool.`

journal `run_1791538166073.jsonl` 时间线：

| 相对时刻 | 事件 |
|---|---|
| 0.0s | `run_started` |
| 0.5s | `user_input` + `round_started(round=0)` |
| 40.3s | `message MODEL TOOL_CALLS toolCalls=1`（`file_write`）⇒ **审批对话框弹出** |
| **374.5s** | `message TOOL`（回灌）—— 审批挂起 **≈334.2s** |
| 377.7s | **`settled = ModelStopped(rounds=1)`** ✅ |

**四判据全命中**：① 审批弹出（截图 `_ci-tools/_w55_b1d.png`）；② run 继续（`ModelStopped` 非 `Failed`）；③ `WallClockBudget` 硬熔断 = 0（日志无硬预算熔断）；④ `pausedNanos` 正确计入（**仅当 334.2s 挂起被剔除**，run 才可能以 **377.7s 墙钟 > 300s 硬预算**存活；有效时长 ≈43.5s）。
⇒ **B1 停表算术真机成立**；`WallClockBudget` 耗时口径 = 有效执行时长（扣审批挂起）**已真机证实**（此前只有 JVM 纯函数单测）。
⚠️ 本轮 `file_write` 因**绝对路径**被沙箱正确拒绝，不影响 B1 判据。

### 3.2 ⛔ 未行使 / vacuous（如实申报，不记通过）
- **修复后同步路径自愈的真机读数**：本波修法已落地，但**未在新构建上重跑毒化以读 `来源=同步下发`**（真机 A/B 用的是修前构建）⇒ 该修复的**真机回归留 W56**。
- **多模态 L3**：未发图 ⇒ 端到端图片输入**未验证**。

---

## 四、坑复盘（本波最值得继承的 4 条）

### 1. 🔴 「读错版本」= 证据链静默失效（版本边界陷阱）
W53 的「三层源码未找到 collapse」**成立但无效**——读的是 **tip**，而真机跑的是 **0.17.1 prebuilt**，tip 已删收敛点。⇒ **源码级取证必须先钉「读的版本 == 真机版本」**；否则「未找到」会被误当「不存在」（本仓「描述不成立」的又一形态）。

### 2. 🔴 「同步抛异常」会绕过异步 `onError` 自愈
W51 / W54 的自愈全部挂在 `onError`，但真实失败点在 `sendMessageAsync` **同步抛出** ⇒ 自愈与计数**对真实路径 vacuous**。⇒ 设计自愈时**必须确证「失败在哪条路径抛出」**，不能假设「失败都会走 `onError`」。

### 3. ⚠️ 承重函数的「局部关闭」不等于「隔离」
fold 被 3 处共用（TOOL 回灌 / merge / 主折叠点）；只关 2 处**未隔离**（仍在第 3 处折叠）。⇒ A/B 隔离必须**穷举所有调用点**，否则得出错误的「未生效」结论。

### 4. ⚠️ 规范设想的函数签名可能与实现约束冲突
规范写 `handleTemplateRenderFailure(raw: String)`，但 `outboundRoleForDiag` 是 flow 内局部量 ⇒ 必须随参传入（+ `source` 区分路径）。⇒ **落地时以代码约束为准并显式申报偏差**，不硬套规范字面。

### 5. ⚠️ 自愈置位若落在「即将被 evict 的实例」上会静默失效（审查 P2）
`nativeToolsRejected` / `conversationDirty` / `templateRebuildCount` 全是**引擎实例级**；而 `AgentRunner` 在**首次**失败后即 evict + 新建实例 ⇒ 这些置位随旧实例被 `close()→releaseInternal` **清零**。⇒ **凡「置位以改变下次行为」的自愈，必须先确证「承载置位的对象会活到下次」**；跨实例语义需另找载体（本波如实申报局限，持久化留 W56 §五 常规 3）。

### 6. ⚠️ 同步抛出会绕过 `finally`（审查 P2，既有缺口）
把 `sendMessageAsync` 与 `consumeAsFlow` 分成两个 try 块时，**前者的同步抛出会跳过后者 `finally`** ⇒ `activeGenerations` 只增不减（实例永久 busy）。W50 真机日志「上一次生成仍在继续，请稍候重试」即此症状。⇒ 资源计数必须**在同一个 try/finally 里配对**，或给每条抛出路径补等量收尾。

---

## 五、挂账台账（W56 起）

> 权威版仍是 README 台账 + 本文。每条给「最早可启动波次 + 重启前提」。

### 🔴 优先（W56 首批）

1. ✅ **B1 真机长审批剧本（W55 已交付）**：审批挂起 **≈334s > 300s 硬预算**仍存活（四判据全命中，台账 #38，见 §3.1）。**自 W52 起一直 vacuous，本波首次行使。**
2. **多模态 L3 真机 A/B**：需一个已取证安全的视觉模型（L1 五选一）+ 发图 UI 路径；协议须同时带 **role + 元素数**（附件消息走多元素 `Contents`）。
3. **反复炸防护**（顺序：毒化 N 分布 → 定阈值 → 落防护）：**本波已补「同步路径自愈」**（模板失败首次即置 `conversationDirty` + 计数），但**限次软熔断**仍待 N 分布。落点沿用复审15 裁决（进程级 `cid` 键控 store）。**别现在拍阈值。**
4. **litertlm bump 评估立项**：⚠️ 硬前置（W53 / W54 三条 + **W55 新增第四条**）——① H-A 定案（**W55 已达成**）；② 新版本重跑 `TextFoldTest` 全套；③ 一次真机 fold 复验（折叠前 3 → 折叠后 1）；④ **确认目标版本仍保留「1 元素 text 数组 → string」的收敛**（v0.17.1 `GenericDataProcessor::MessageToTemplateInput` 语义）——**tip 已删除该收敛，bump 过删除点 ⇒ fold 失效 ⇒ Qwen2.5 P1 静默回归**（`#24` 只钉 fold 调用点存在性，拦不住行为级漂移）。**bump 不得先于上述前置合入。**（**W56 已核**：v0.18.0 tag commit `b2f686e2e` 源码树级 grep 零命中 `MessageToTemplateInput` / `requires_typed_content` = 收敛已删且无等价替代 ⇒ 前置④不通过，**bump 挂起**；重启条件见 `LiteRtLmEngine.kt` fold 出口注释区。）

### 🟡 常规（顺延，未动项）

5. **N-W3 预设 `evidenceLevel` 数据化 + 守卫**（`recommended=true ⇒ 非 unverified`；`image=true ⇒ ≥ family-extrapolated`）：并入「预设扩容波」。
6. **`DeepSeek-R1-Distill-Qwen-1.5B` 被 `inferFamily` 误判 `thinking=false`**（W48 让 `enable_thinking` 恒发 ⇒ 可能关掉其推理）。**重启前提：拿到该容器 + 真机验 AUTO。不得无容器盲改。**
7. **真机验收积压**：台账 38 条（31 ✅ / 5 ⚠️ / 2 ⛔）。未覆盖项：通知档B｜Gemma 两档｜层5「压缩触发重建」子路径｜记忆磁盘满·只读｜W37 UI 手感｜lint gate｜W38 行为变更｜W40 验收面｜F4 三级文字 token 统一｜**多模态 L3**。
8. **F4 三级文字 token「同角色不同色」6 处**：先立「语义角色→颜色」单一映射表再全仓对齐。⚠️ 深色 `onGlassSubtle`(0x80) 是**脆弱达标**（余量 <10%）⇒ **不要降 alpha**。
9. **数据回填 / 阈值回填**：口径见 W49 交接；阈值需 ≥2 个坏容器样本。
10. **`TokenUsage.estimated` 治根字段**（唯一能长期不撒谎的口径方案）。
11. **通知文案「渠道」误导**（`GenerationNotifier.kt` + 枚举 `CHANNEL_DISABLED`）。
12. **法务** `TODO(legal)`×4 / `termsVersion`（**必须先于任何法务文本替换落地**）。
13. **上游 / 已知限制**：`gemma-4-E2B-it-gpu`（GPU 输出乱码；CPU init `NOT_FOUND`）｜`MiniCPM-V-4-int8`（`Unsupported model type`）｜MiniCPM5 OFF 态规划外溢（2B int4 固有，**别动 `thoughtChannelDefsFor`**）。
14. **`main` 分支快照声明过时** ⇒ 绑定「打首个 tag 前」执行。**别挂到 tag 之后。**
15. **`ChatRunCoordinator.kt` 余量**（1278/1300）：若有功能落此文件须**先拆分后回灌**。
16. **`fulltest.sh` 接进 CI 运行步**（W50 挂账顺延）。
17. **引擎 Stage-2/3 拆分预案**：触顶（≤2400）即启动；拆分须保守卫 #24 不变式。

---

## 六、外部报告对账

本波**无新外部审查报告**（W54 的 4 份已收口）。W55 的驱动来自**内部旗舰任务**（H-A 毒化定案）+ W53 / W54 挂账（多模态 L1、反复炸防护、bump 前置）。

---

## 七、下次接手须知

1. **推送纪律**：`GIT_TERMINAL_PROMPT=0` + `-c credential.helper=`（空）**两者都要**（否则 GCM 无凭据**静默挂起**）；`-c http.sslVerify=false`（绕 MITM 的 `CRYPT_E_NO_REVOCATION_CHECK`）；**PAT 只以 URL inline 一次性使用、绝不落盘**。推前 **`git push --dry-run`** 核（`git ls-remote` 匿名读 public 库**假绿**；本地 `origin/<branch>` 跟踪引用可能**陈旧**）。
2. **静态闸门基线**（改动前）：`arch-guard` **27 项全 OK**、`arch-guard-selftest` **PASS=65 FAIL=0**（本机 ~5 min；W53 记的 ~22 min 已随环境变化，仍建议 `run_in_background`）、`scripts/fulltest.sh` = `tests=624 failures=1`（唯一失败 = `core-data` 的 `SandboxFileScannerTest.kt:184` Windows 符号链接）。
   ⚠️ **W55 实测修正**：本波干净重跑得 `tests=624 **failures=0** errors=0`（9/9 模块，XML mtime 全为本轮）⇒ 该「既有失败」**不是常量基线**，而是**环境相关/不稳定**（符号链接创建权限或前次残留所致）。**判据以「无新增失败 + 模块数 = 9」为准，不预设 failures 值**；gradle 若因它 exit 1，**不是**任务校验错误。
3. **跑全量单测必须注入 `JAVA_HOME`/`ANDROID_HOME`/`GRADLE_USER_HOME`**，并**核对模块数 = 9**。⚠️ **`GRADLE_OPTS` 的代理端口取 `${https_proxy}`**（本机实测 **4708**，非脚本注释里的 1799 兜底值）。gradle 的 `N tests completed` 是**残缺口径**，不可信。
4. **纯文档提交 ⇒ 两条 workflow 都 0 run** ⇒ 需出包必须 `workflow_dispatch`。
5. **设备**（OPPO PDRM00 / A13，serial `13309cc8`）：adb = `_j2env/sdk/platform-tools/adb.exe`（**不在 PATH**），Git Bash 需 `MSYS_NO_PATHCONV=1`；**`adb push` 的本地路径要写 Windows 形式**（`D:/...`）。**装机必须** `adb push` + `pm install -r`。⚠️ **并发 adb 会让设备瞬时 `not found`** ⇒ **串行执行**；⚠️ **沙箱会拦「多命令链式」（`;` / `&&`）** ⇒ **拆成单条**。
6. **UI 自动化**：`_ci-tools/_w49_ui.py`（`dump` / `nodes` / `tap`）。⚠️ `uiautomator dump` **对 Compose bounds 不可靠** ⇒ 判尺寸 / 可见性**必须截图目视**；⚠️ **uiautomator 进程自身会抛 `UiAutomationService already registered` FATAL**（PID ≠ app）⇒ 判 app 崩溃前**必须核 PID**。
7. **中文输入不可行** ⇒ ASCII 题面，空格用 `%s`。
8. **logcat 双档**：涉 native 判据必须落**不过滤**全量档，且**先验该档确含 native 行**才可说「0 命中」有意义。
9. **`_plans/` / `_litert_forensics/` 在 git 仓库外** ⇒ 设计 / 审查 / 取证报告不 commit；关键结论**必须折进仓库文档**才算落档（本波 H-A 定案已折进 `LiteRtLmEngine.kt` + 本文件）。
10. **判定四态纪律**：`✅ 通过` / `⚠️ 部分`（不得整体记 ✅） / `⛔ 不适用`（**不是通过**） / `⛔ vacuous`（判据未被行使）。**凡「目检」判据必须逐条抄原文**；**没触发判据写「vacuous / 未行使」**。
11. **关闭原生工具通道**（行使文本协议 fold 路径用）：设置页 →「原生工具通道」开关（`SettingsScreen.kt:705`）；**测完必须还原为开**（settings pb 复核 `nativeToolChannel:true`）。
12. **多模态取证复用 W51 / W55 方法**：`adb exec-out dd` 抽 `chat_template` + `minijinja` 离线复现；或 HF 直链 `curl -r 0-52428799` 取 50MB head 定位模板。
13. **模板失败取证看来源后缀**：`handleTemplateRenderFailure` 的两处 `warn` 带 `（来源=同步下发）` / `（来源=异步回调）` ⇒ 真机可区分路径。

---

## 八、CI run id

推送 `19c1dab..6efc0fb`（**8 commit**，fast-forward）后**两条 workflow 自动触发**（本波含 `core-engine/**` 与 `feature-models/**` 两个 `.kt` 改动，无需 `workflow_dispatch`）：

| workflow | run id | 结论 | 关键 job |
|---|---|---|---|
| **Build** | **`37914317306`** | ✅ **success** | `Lint (baseline gate)` ✅ / `Assemble Debug (JDK 21)` ✅ / `Unit tests` ✅（**三 job 全绿**） |
| **Release** | **`37914317420`** | ✅ **success** | `Build & (optionally) sign release` 全步骤 success |

### 8.1 守卫网在 CI 上的实跑（PAT 有 `actions:read` ⇒ 可读 job log）
- `Assemble Debug (JDK 21)` job 内：`架构守卫全部通过。`（27 条）+ `自测结果：PASS=65 FAIL=0` + `arch-guard 自测全部通过。`
- ⇒ CI 内守卫与自测**均非 vacuous**，与本地读数（27 / 65）一致。
- ⚠️ 本波**无** `scripts/**` / `.github/workflows/**` 改动 ⇒ 无守卫逻辑变更；`release.yml` 仍被 `core-*/**` / `feature-*/**` 白名单命中（**已实测触发**）。

> 本节已在推送后**立即回填**（W52 教训：run-id 回填迟到两次）。

---

## 九、commit / push 纪律回填

- 提交身份：`-c user.name="Rickeal-Boss" -c user.email="15992567646@139.com"`（**绝不用 noreply**）。
- PAT 仅以 URL inline 一次性使用，**绝不落盘**；推前 `git push --dry-run` 核。
- 推送后回填 §八 run-id，并复核双 workflow 结论。
