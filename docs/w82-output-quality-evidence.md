# W82 模型输出质量与延迟证据（真机 186 会话全量扫描 + 现场复测）

> 分支 `core-fix`（W82，基线 `8a2dcba`）。
> 目的：把「模型输出语言质量」从主观印象变成**可机械复核的分布数字**，并区分「容器/模型行为画像」与「本仓可修的输出侧缺陷」。
> 采集方式：有线 adb（OPPO PDRM00 / Android 13 / ColorOS），`adb exec-out run-as com.rickeal.agent.debug tar -c files/conversations` 全量拉取 186 个会话 JSON（856 KB）后离线统计；另做一次现场端到端复测。
> ⚠️ 本档只记**观测事实与判据**，不把「未复现的推断」写成结论。

## 一、样本与方法

| 项 | 值 |
|---|---|
| 会话文件数 | **186**（185 个 envelope 对象 + **1 个裸数组**，见 §五.4） |
| MODEL 消息数 | **171** |
| 时间跨度 | 2026-09 至 2026-10-25（含 W80/W81 全波） |
| 终态模型 | Qwen2.5-1.5B（`dfc68e9e-5540-4959-a4d1-c7373a329192`），占 65/171 条 |
| 判据口径 | 逐条读 `messages[].text`；语言按 Unicode 区块占比（CJK / 拉丁 / 西里尔 / 阿拉伯 / 韩 / 泰 / 天城 / 希伯来）；工具 JSON 按 `[{"tool":` 形态匹配；thinking 按 `<think` / `think>` 匹配 |

复现脚本（工作区 `_w82_dev/analyze.py`，仓外）：
```bash
adb exec-out run-as com.rickeal.agent.debug tar -c files/conversations > conv.tar
tar -xf conv.tar && python analyze.py
```

## 二、结论先行：四类签名，只有两类是本仓可修的

| # | 签名 | 计数 | 定性 | W82 处置 |
|---|---|---|---|---|
| **LQ-1** | **原生工具 JSON 泄漏到正文**（`[{"tool": ...}]` 被当最终答案展示） | **11 / 171（6.4%）** | **本仓输出侧缺陷**（协议降级路径把原文直接给用户） | W83-03 实施（输出侧收口：协议解析层加固，非提示词面） |
| **LQ-2** | **thinking 标记/内容泄漏到正文**（`<think>` 或思考段落混入） | **7 / 171（4.1%）** | **部分本仓可修**（通道未声明的容器无兜底剥离） | W83-03 调研后实施或挂账 |
| **LQ-3** | **幻觉绝对路径 → 沙箱拒绝死胡同** | 见 §四 | **本仓可修（可观测 + 纠偏文案）** | **W82-04 本波实施** |
| **LQ-4** | **首字延迟极端长尾** | p50 12.9s / p99 181s / max 200.0s | **冷启模型加载成本**，非 prefill | 本波取证 + 挂账 |
| — | 中文提问→英文作答 | **0** | 语言跟随正常 | 无需处置 |
| — | 外文碎片（西里尔/阿拉伯/韩/泰/天城/希伯来 >5%） | **0** | 无坏容器特征 | 无需处置 |

> **编号漂移勘误（W83-01）**：原「登记 W82-13/W82-14」与台账真实 W82-13（ChatParamsPanel 落盘）/W82-14（下载 STALLED）**撞号**——LQ-1/LQ-2 的实际处置走 **W83-03**，复审 36 P3-4 同判。

⇒ **「语言质量劣化」在本样本里不成立**（与 W81 结论一致）；真正的问题是**协议降级与思考通道的输出侧收口**，以及**冷启加载延迟**。这三类都不该被笼统写成「模型不行」。

## 三、LQ-1 工具 JSON 泄漏（11 例，逐条）

| 题面 | 泄漏正文（截断） |
|---|---|
| `Say OK` | ` ```json [{"tool": "memory_write", "arguments": {"title": "任务中的重要信息"}}] ``` ` |
| `Say OK` | `[{"tool": "file_read", "arguments": {"path": "/home/user/samples/", "offset": 0, "limit": 10}}]` |
| `What is 17 times 23` | `[{"tool": "calculator", "arguments": {"expression": "17*23"}}]` |
| `Create a.txt with hello` | `[{"tool": "file_write", "arguments": {"path": "/Users/yourusername/Desktop/a.txt", ...}}]` |
| `A bakery sells cookies...` | 正文答案 + ` ```json [{"tool": "math_search", ...}] ``` ` |
| `Think carefully with full...` | ` ```json [{"tool": " clipboard", "arguments": ...}] ``` `（**工具名带前导空格**） |
| `write 1 to a.txt; write ...` | ` ```json [{"tool": "file_write", ...}, {"tool": "file_write", ...}] ``` ` |
| 其余 4 例 | 同类（`ask_actor` ×2、`current_time`、`ask_actor`） |

**根因（源码证据）**：`AgentRunner.kt:966-978` —— 文本协议解析出「有工具形状但不可执行」（工具名未注册 / 参数不合法）时判 `ProtocolResult.FinalAnswer`，注释明写「此处保留原文（不 strip），因为用户可能就是要这段 JSON」，随后原文作为最终答案上屏。

**为什么是缺陷而非设计**：① 11 例里没有一例是用户要 JSON（`Say OK` / 乘法 / 写文件）；② `calculator`、`file_write`、`current_time` 都是**本仓真实工具名**，说明触发原因是「工具面收窄后模型仍调旧工具」或「名字带空格等形态错误」，而不是用户意图；③ 用户侧表现是「发一句话，回一堆 JSON」，无任何解释。

**修法候选（未实施，需评审）**：保留原文（不推翻既有裁决），但在 `FinalAnswer` 且解析出的工具名**已知但当前不可用 / 形态非法**时，追加一行可读说明（「模型尝试调用工具 `calculator`，该工具当前不在可用工具面内；以上为模型原始输出」）+ `AgentLogStore.warn` 留痕。**不 strip**，避免破坏「用户真的要 JSON」的合法场景。

## 四、LQ-3 幻觉绝对路径 → 沙箱拒绝（本波实施）

**现场复现的会话**（`01805165-...json`）：题面 `Create a file named b1.txt inside the sandbox with content hello. Use the file_write tool.`，模型最终正文 = **`拒绝绝对路径：请使用相对沙箱根目录的路径`**（13 token）。

**这是工具错误文案被当成最终答案上屏**：用户看不到「模型做错了什么、应该怎么改」，只看到一句系统拒绝语。同族证据：泄漏的工具参数里出现 `/Users/yourusername/Desktop/a.txt`、`/home/user/samples/` —— 模型在幻觉桌面路径。

**本波修法**：
1. 拒绝路径补 `AgentLogStore.error`（**ERROR 是唯一落盘级别**，见 `AgentLogFileStore.kt:90`）+ 30s 节流；
2. 对模型的错误文案加具体示例（`note.txt` / `docs/a.md`），明确禁止 `/Users/...`、`/home/...`、`/sdcard/...`；
3. 扫描器目录逃逸剔除补 `warn`（UI 浏览面，可能高频）。

> 口径说明：用户原话是「补 `AgentLogStore.warn`」。本波按**分级**落地 —— 越界尝试属安全事件，用 `error` 才能落盘、事后可查；`warn` 只进 200 条内存环，杀进程即丢。浏览面剔除用 `warn`。

## 五、LQ-4 首字延迟：主因是冷启加载，不是 prefill

### 5.1 现场端到端复测（2026-10-25 00:26，冷启后首个 run）

| 阶段 | 时间戳（ms） | 差值 |
|---|---|---|
| `round_started(round=0)` | 1792859188999 | — |
| 首条 MODEL（tool call `file_write`） | 1792859212424 | **20225ms** |
| 工具执行完成（TOOL） | 1792859425493 | 4ms（工具本体） |
| `round_started(round=1)` | 1792859425501 | — |
| 末条 MODEL `DONE` | 1792859426983 | **1471ms**（promptTokens **1521**） |

**判据**：round 0 与 round 1 的 prompt 规模接近（622 → 1521 token，后者更大），但首字延迟从 **20.2s 降到 1.47s**。⇒ 20.2s 里的绝大部分是**引擎/模型加载**，不是 prefill。
按 round 1 反推 prefill 速率 ≈ **1521 tok / 1.47s ≈ 1035 tok/s**。

### 5.2 历史分布（171 条 MODEL 消息）

| 指标 | p50 | p90 | p99 | max |
|---|---|---|---|---|
| `firstTokenLatencyMillis` | **12942** | 34500 | 181242 | **200031** |
| `decodeMillis`（>8s 的长尾） | — | — | — | 202247ms / 1198 token（5.9 tok/s） |

- `max = 200031ms` 高度可疑地贴近 200s 整（需与 `freezeAwareTimeout` / 生成超时口径对账，本波**未取到**该 run 的日志，登记待查）。
- p50 12.9s 与 §5.1 的「冷启加载 ~19s」同量级 ⇒ **历史 TTFT 分布主要度量的是「进程冷启后首个 run 的模型加载」**，不是每轮推理速度。

### 5.3 结论与挂账
- **不是**「端侧 1.5B 太慢」：热态 prefill ~1035 tok/s、生成 ~8-12 tok/s，符合该容器预期。
- **是**「每次进程冷启要付一次 ~19s 模型加载」。用户感知 = 「打开 App 问第一句要等 20 秒」。
- 修法方向（**本波不做，需产品裁决**）：进入对话页且有 active 模型时后台预热引擎。代价 = 常驻内存 + 冷启 CPU 争抢（与 W80-01 的 `reportFullyDrawn` 664ms 目标冲突）。⇒ 登记为 W82-15（active，待裁决）。

## 六、顺带发现的两个观测面缺口

### 6.1 `toolCalls` / `toolResults` 落盘恒空
186 个会话里 **`toolCalls` 非空的消息数 = 0**，但 journal（`run_*.jsonl`）里明明有完整 `toolCalls`。⇒ 会话正文**不记录工具调用**，事后无法从会话文件回答「这轮到底调了什么工具、参数是什么、结果如何」。这直接削弱了 LQ-1 的归因能力（只能从泄漏正文倒推）。
处置：登记 W82-16（低优先，属数据面）。

### 6.2 会话 schema 双形态
185 个文件是 `{id,title,messages,config,...}` envelope，**1 个是裸 `List<ChatMessage>`**。读取侧显然兼容两种，但写入侧已统一为 envelope ⇒ 旧文件长期不迁移。与外审 P2-3（schema 版本）同族，登记 W82-17。

## 七、本档与 W81 结论的关系

W81 §三.5 的结论是「『detailed guide』类题面确认式回复 = 容器长期行为，非回归；语言质量无劣化签名」。**本档独立复算后同意「无语言劣化」**（中文问/英文答 0、外文碎片 0），但补两点 W81 未覆盖的面：
1. **输出侧收口缺陷确实存在**（LQ-1 6.4% / LQ-2 4.1%），只是它们不是「语言」问题而是「协议与通道」问题；
2. **TTFT 长尾的主因被定位为冷启加载**，W81 只记了「首字 20.2s」这一现象，未拆分归因。

---

*证据来源：主理人一手采集（186 会话全量拉取 + 1 次现场端到端复测 + 源码逐行核验）。所有计数可用 §一 的命令复现。*
