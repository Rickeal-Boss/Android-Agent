# Wave 34 + Wave 35 交接：原生工具通道（回显根治）+ 记忆 pull 化 + 静态可证批处理

> 写于 2026-09-30 凌晨。基线 `b17303d`（Wave 33 终态，Build #266 / Release 双绿）→ 本波 tip `2f27024`（8 commit）。
> 外部输入：**五份第三方审查报告**（issues-v1/v2、深度挖掘第三轮、环境约束下待修项、复审6 莫斯线）。
> 团队：沈思远（方案）→ 柯码成 ×3 并行（engine 流 / agent+model 流 / data+UI 流，文件面互斥）→ 严质衡（Wave 34 全量审查：2 P0 + 5 P1）→ 柯码成修复 → 主理人（复核 / 集成 8 commit / 推 CI）。
> **adb 真机仍推迟**；用户已把「Gemma GPU 加载引擎错误」标记为待解决项（落档见 Wave 33 交接 §6）。

---

## 1. Wave 34：原生工具通道（回显根治解）

### 1.1 为什么它是根治解

回显的**根子**不是检测不够，而是工具走**文本协议**——系统提示词里必须塞一整段工具清单 + JSON 调用格式，小模型续写时先复述它（Wave 21 真机复现的正是工具指令段被逐字复述）。改成 litertlm 原生工具通道后，该段整体删除，**回显面物理消失**（这是与 Wave 19/21/24 所有「输出侧加检测器」的本质区别）。

### 1.2 关键事实（javap 实证，litertlm 0.17.1）

| API | 结论 |
|---|---|
| `OpenApiTool` | 公开接口（`getToolDescriptionJsonString()` / `execute(String)`），可自由实现 |
| `ToolProvider` | 抽象方法名带模块混淆后缀 `provideTools$third_party_...()`，**不可覆写**，只能走 `tool(OpenApiTool)` 工厂 |
| `automaticToolCalling` | **默认 true**；设为 false 时 tool_calls 经 `JniMessageCallbackImpl.onMessage` → `Message.model(contents, toolCalls)` 回传调用方（实证于 Conversation.kt:605-608） |
| `Message.toString()` | = `contents.toString()`，**不含 tool_calls** ⇒ tool_calls 帧正文恒空，不会与流式正文重复计数 |
| `Message.tool(Contents)` + `Content.ToolResponse(name, response)` | 回灌工具结果的标准形态 |
| `GenerationChunk.toolCallDelta` | **core-model 早就有**（Generation.kt:34），`StreamAccumulator` 已消费，AgentRunner `nativeCalls`（:654-678）执行通路完整 ⇒ **无需扩任何字段**，引擎只需填它 |

### 1.3 交付内容

- **引擎侧**：`NativeToolBridge`（ToolSpec → OpenAI 平铺 schema；`execute()` **抛异常**——红线物理保险，万一 automaticToolCalling 被误设 true，异常可见，而返回错误串会让模型以为工具已执行从而静默绕过审批）；探针（只在开关打开时跑，位置在 `capabilities()` 内——放 `load()` 会被 `sameEngine` 短路吃掉变死开关）；五处 ConversationConfig 显式 `automaticToolCalling=false`；`toolCallDelta` 上抛（单次闩锁，位置在「空增量即 return」之前）；回灌 `Message.tool` vs `Message.user` 双形态；MODEL 播种带 toolCalls + TOOL 播种成 `Message.tool`（**半套配对**会构造出 `model(tool_calls) → user(文本)` 的模板非法形态）。
- **提示词侧**：三重门（引擎能力 ∧ enableTools ∧ `config.nativeToolChannel`）；原生激活时删工具清单段 + DISCLOSURE_GUIDE、`TOOL_GUARDRAILS` 换精简版。
- **执行侧零改动**：审批 fail-closed / 沙箱 / 同参守卫 / 失败连击熔断 / 披露转发全部复用既有管线（已逐处核对，未复制出第二条执行路径）。
- **开关默认 false + Settings 可开**：工具 schema 形状**无法离线验证**（全仓 0 命中样例），误开的形态是「提示词已删工具段 + native 不认工具」= 比现在更糟；但不开用户就无法验证根治效果，故一并落开关。

### 1.4 严质衡 2 个 P0（均已收编）

1. **legacy 回退 + 通道激活 ⇒ 每轮全量 re-prefill + 工具能力静默归零**：legacy 下 `registeredToolsSignature=null` 而通道仍判 active ⇒ 重建判据每轮命中；且 legacy 配置不带工具，而上层已按 run 级缓存的 capabilities 删了提示词工具段。修法：重建判据加 `&& roleChannelActive`；legacy 分支**证伪**通道（让 capabilities 立刻改报 false，下一 run 把工具段写回自愈）。
2. **证伪自愈只作用于下一 run**：本 run 剩余轮次是「提示词无工具清单 + 引擎无工具」的静默空窗。修法：每轮校验 `engine.sessionDiagnostics.nativeToolChannel` 与本地判据，不一致时 warn（**消灭静默**，不是同 run 内恢复）。

---

## 2. Wave 34：记忆 pull 化 + `memory_search`

- 记忆从**全量注入**改为**标题索引注入 + 按需检索**：`AgentMemory.renderIndex(600)` 只注入 `- [标题]`；正文经 `memory_search` 工具结果通道取用。
- ⚠️ 铁律：**检索结果绝不塞进系统提示词**——那会让每个 run 的 `systemText` 都变 ⇒ 引擎重建判据每轮命中 ⇒ 4B 秒级 re-prefill（Wave 24 验收点已标红「每轮一次 = 异常」）。
- `memory_read` **不退役**（退役会让旧 journal / 恢复路径里的 `memory_read` 变未注册名 → 被 TextToolProtocol 降级为最终答案 = 静默行为变化），仅补一句描述。
- 检索复用 `HiddenToolCatalog` 的 CJK 二元组；**命中门维持 `score > 0`**（本仓语料下改阈值会误删正确命中）；空 query 特殊规则 = 返回最近更新 8 条（防模型退化成先空搜一次）。

---

## 3. Wave 35：外部报告驱动的静态可证批处理

判据：**无本地编译 + 无真机 ⇒ 只有「静态可证 + 纯 JVM 可测」的项才值得现在修**，且一次 CI 批量覆盖。

| 项 | 内容 | 性质 |
|---|---|---|
| **D1** | `String.take` 停在半个代理对产生 U+FFFD 乱码 → 新增 `String.truncateSafe(max)`，统一 10 处（工具输出截断是最高频用户可见路径；`file_read` 输出还会回灌模型，原注释「纯修饰性，不处理」是错的） | **用户可见** |
| **D4.2** | 通知补 `POST_NOTIFICATIONS` 检查：无权限 / 通知被关 / `notify` 抛 SecurityException 三出口全部留痕（此前 runCatching 吞掉 = 通知不弹且无日志） | **功能静默失效** |
| **D2** | `.tmp_` 判据收紧为「最后一个 `.tmp_` 之后全为数字」，修 `report.tmp_backup.txt` 被静默隐藏的误伤 | 误伤可穷举 |
| **D6** | 记忆单条正文上限 2000（**纵深防御**——工具层早有前置校验，下沉防换路径写入 + 消双份常量；不是修活 bug） | 防御 |
| **B2** | 子 run 墙钟 evidence 改报「run 总运行 N 秒（本子 run M 秒）」——原文案沿用 run 相对口径而判据是继承的绝对 deadline，出现「已运行 2 秒，达到 300 秒硬预算」的自相矛盾 | **用户可见失真** |
| **B1** | `SummarizingContextCompressor` 全仓零引用（含测试源集）→ 判死删除并留痕（此前从挂账里「蒸发」，无完成也无判死记录） | 债 |
| **A1**（P1） | 沙箱预览弹层失写竞态：`previewJob` cancel + `commitPreview` 对账（关闭后重弹 / 标题 B 内容 A 两类都覆盖） | **用户可见** |
| A2/A5/A6/A7/A8 | getUriForFile 防护 / 扫描失败留痕 / 返回刷新接线 / 计数口径统一 / 目录点击 Toast | 一致性 |
| **D7** | `CancellationException` 一致性：只改真会吞取消的两处（suspend 调用被 runCatching 包住 ⇒ 用户点「停止」后仍在生成）；非 suspend 路径加「为何不加判据」KDoc | 语义错 |
| — | `nativeToolsDrifted` **补定义**（Wave 34 写了调用点但函数未定义，树当时编译不过） | 编译阻断 |

**已裁决不做**：arch-guard 第 15 项「零引用类」守卫（需自测网配套，风险>收益）；lint baseline 168 项分类治理（量大）；`AutoboxingStateCreation` 17 条批量改（无真机验证帧率）。

---

## 4. 主理人的三处裁决（含对外部报告的一处纠偏）

1. **A6 改回朴素写法**：开发者用了 `DisposableEffect + LifecycleEventObserver`（`LocalLifecycleOwner` 是全仓首次 import）。裁决换 `LaunchedEffect(Unit)`——本仓硬约束原文「无本地 JDK，CI 是唯一验证通道；**宁可朴素不要冒险写法**」，一处解析不到就是一整轮 CI 往返。
2. **探针位置采信实况而非处方**：我原本要求「探针包在 `load()` 内」，开发者实测探针在 `capabilities()` 内**且已带开关门控**，并指出放 `load()` 会被 `sameEngine` 短路吃掉变死开关。采纳其实况，只补 KDoc。
3. **D6 定性纠偏**：外部报告称「记忆存储面无界增长」，实况是工具层早有 2000 前置校验，该路径并不可达 → 按**纵深防御**记，不记成「修了无界增长」。

---

## 5. 真机验收（adb 解禁后，按优先级）

**Wave 33 第 1 项仍为全局最高优先级**（莫斯线独立确认：用户反馈包推断不含 `b9a45c1`，第三态假说未被证伪）：

1. **第三态验证**：Gemma CPU/GPU + 任一小模型长任务；四组关键字 `preface 渲染诊断` / `preface 校验失败` / `角色通道播种失败` / `会话重建原因`。命中 → 回显议题收口在望；未命中 → 转向原生工具通道。
2. **原生工具通道（开关打开后）**：grep「原生工具通道」确认注册/是否 fallback；让模型调 `current_time`，**审批卡必须照常弹出**（红线验收项）；连跑 5+ 轮工具任务并触发一次压缩，看是否出现 `角色通道播种失败`；关开关跑一遍必须与今天逐字节一致（回归基线）。
3. **Gemma GPU 加载引擎错误**（用户标记待解决）：抓 `GPU 后端不可用` 后的错误原文。
4. 沙箱：>200 文件时入口卡与子页数字一致且都带「+」；预览大文件时点关闭不重开；快连点两文件标题内容同源；点目录出 Toast；关通知权限后开「生成速度通知」→ 诊断页出现一条权限 WARN。
5. 记忆：≥20 条时问「你还记得我的偏好吗」→ 应先 `memory_search` 再答；`estPrompt` 应比 pull 化前明显下降。

## 6. 挂账（Wave 36+）

- 原生通道「同 run 内恢复」（需把 `capabilities()` 取到轮内，判据翻转时才变一次）。
- `MemoryViewModel` 误报：存储层失败原因未传出，UI 侧靠长度比对（次优，等存储层出原因字段后收敛）。
- 工具 schema 形状的**真机实证**（R1，唯一无法离线验证项，决定题 A 成败）。
- lint baseline 168 项分类治理 + `AutoboxingStateCreation` 17 条 + arch-guard 第 15 项。
- 记忆向量检索（禁引新依赖）/ 自动去重 / 按会话分区。
- THIN 0.21（已跨 4 个波次未裁决）。
