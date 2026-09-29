# Wave 33 交接：preface 第三态闸门 + 引擎诊断基建 + 沙箱文件浏览器

> 写于 2026-09-28 晚。基线 `05e358c`（Wave 32 终态，Build #265 / Release 双绿）→ 本波 tip 见 git log（7 commit）。
> 团队 SOP：沈思远（三题方案）→ 柯码成 ×2 并行（engine 流 / feature 流，文件面互斥）→ 严质衡（全量审查：需修改，3 P1）→ 柯码成修复 → 主理人（裁决 / 复核 / 集成 7 commit / 推 CI）。
> 外部输入：用户真机反馈「对话依旧只输出提示词及工具调用语言然后胡言乱语」「Gemma GPU 版本运行有问题」+ 新需求「沙箱会话工作空间文件可视、可跳转打开」。**adb 调试本轮用户明确推迟**——题 B 走可观测性/防御性路线。

---

## 1. 本波最重要的方案结论（沈思远源码级核验）

**「回显命中后 KV 污染」在 Wave 31 tip 已被切断，无需新增跨模块通道**：判定命中 → `StreamLoopException` → collect 中断取消 → `LiteRtLmEngine.generateStream` finally `if (!finished) conversationDirty = true` → 下轮 ensureConversation 关旧会话+清水印+全量重放。且 Wave 29 的 `StreamReset` 事件会清掉上屏乱文——「回显必然已上屏」的旧说法过时。原计划的「回显→重建会话」接口**不需要做**。

**回显残留的真缺口是「角色通道第三态」**：`createConversation` 成功但 chat template 渲染丢失/错位 systemInstruction——此前只有 `renderPrefaceIntoString` 诊断日志、无任何后续动作。Gemma 系模板原生无 system role（官方做法并入第一轮 user turn），是最高危对象。Wave 24 的 legacy 回退（createConversation 抛错时）只覆盖第一态。

## 2. Wave 33 本体

| commit | 内容 |
|---|---|
| `b9a45c1` | **preface 第三态闸门 + 中档回退**：`prefaceContainsSystem` 归一化（lowercase+isLetterOrDigit，与判定⑨同口径）**前 64 字窗口子串**判定（模型无关，不维护 Gemma/Qwen 模板标记表）。命中 → 关会话以 `systemInstruction=null` 重建 + `systemMergedPending` 置位，系统提示词并入首条**未发过**的 USER 载荷（一次生效）；`roleChannelActive` 保持 true（MODEL 回灌门控保留——与 legacy 回退的本质区别）。渲染失败维持 Wave 28 静默（诊断零失败面）。同 commit：**EngineSessionDiagnostics 基建**（三态快照 + `LlmEngine.sessionDiagnostics` 接口默认流）、**actualBackend**（实际生效后端，loadedBackend 仍记请求值防误重建）、GPU 失败文案特征串映射、GPU 大上下文 warn |
| `8f0e90a` | PrefaceGateTest 12 例（窗口语义写死在断言消息：40 字符截断判 false / 补足 64 判 true 对照组） |
| `6d50cfb` | ChatScreen 一行诊断小字（优先级 legacy > 中档 > 后端降级，正常路径零渲染）+ ChatViewModel 重订阅 |
| `c3988c7`+`d36512e` | **沙箱文件浏览器**：工具页入口卡 + `tools/sandbox` 子路由 + SandboxFileScanner（根层/降序/隐藏与 .tmp_ 排除/truncated 语义，6 单测）+ LiquidDialog 文本预览（限 AgentPolicy().maxToolOutputChars 同源 4000 字符）+ FileProvider ACTION_VIEW 打开 |
| `80eb60d` | FileProvider Manifest 声明 + file_paths.xml（**仅** files-path agent_sandbox/ 一条，隐私目录物理不可泄露） |

## 3. 严质衡审查裁决记录（3 P1 全收编）

1. **P1-1 诊断流失联**：静态 collect 绑定 init 时刻引擎实例，`rebuildEngine`（AgentRunner.kt:2039 加载失败自愈）evict+create 换实例后新引擎诊断永久到不了 UI。修法：`engineInitStatus.map { create(LOCAL) }.distinctUntilChanged().flatMapLatest { it.sessionDiagnostics }`（AppContainer.kt:210 已公开 status；rebuild 必经 load → status 必转变）。
2. **P1-2 GPU 提示误伤**：特征串含 INTERNAL/CompiledModel 泛化词，纯 CPU 失败也会拼「请改用 CPU」。修法：`hadGpuAttempt = attempts.size > 1` 门控（映射表存在性判据与内容判据分离——本波方法论）。
3. **P1-3 中档回退承诺缺口**：fresh 空（尾部 MODEL 全量播种）/全 TOOL 轮不消费 pending——**pending 不丢实为顺延**，注释如实化 + 顺延打 info 日志。
4. P2 采纳：GPU 大上下文 warn 改实际生效后端归因；接口默认 getter 改文件级单例空流。P2 不动：scanner `.tmp_` 子串匹配（误伤面留档）、诊断小字透传技术串。

## 4. 关键裁决与偏差记录

- **FE 流未改 LiquidAgentApp.kt 是正确偏差**（主理人复核）：`routeTop` 最长前缀匹配（`startsWith("${it.route}/")` + `maxByOrNull`）使 `tools/sandbox` 自动归 TOOLS 页签；composable 注册在 SettingsRoute graph（tools/memory 先例），在顶层 NavHost 重复注册会 duplicate-destination 崩溃。
- **系统提示词瘦身不做**：echoCorpus 逐字节变化连带 FileReadTool 申报链与单测快照，回归面大且 CI 无法验证提示词效果；留档待真机语料回收（原生工具通道才是根治解，独立波次）。
- **题 B 明确不做**：GPU 白名单/上下文封顶/OpenCL 预检/vision 跟随逻辑全不动——症状未知下一切行为改动都是猜测；B1/B2/B3 是「让下一轮真机排查必有所获」的观测基建。
- A3「回显→conversationDirty 契约测试」挂账：mock 成本 > 收益，契约已有 AgentRunner:1112 注释申报。

## 5. 真机验收清单（CI 查不出，下波 adb 解禁后执行）

1. **回显残留**：用 Gemma CPU/GPU 两变体 + 任一小模型各跑长任务，看「只输出提示词及工具调用语言」是否复现；复现时抓日志关键字：`preface 渲染诊断`（看渲染长度与开头是否含系统提示词正文）、`preface 校验失败…并入首条用户消息`（中档回退生效标记）、`角色通道播种失败…legacy`（第一态回退）、`会话重建原因`。
2. **诊断小字**：故意触发一次降级/回退后 ChatScreen 底部是否出一行说明；正常会话零变化。
3. **Gemma GPU**：GPU 变体加载是否成功；若降级，ChatScreen 是否出现「请求 GPU 已降级 CPU 运行」；日志 `GPU 后端不可用` 行的错误原文（定位真因的关键证据）。
4. **沙箱浏览器**：工具页入口卡数字与存储页分桶口径（根层 vs 递归）；文本预览 4000 字符截断提示；二进制文件直开；空态文案。
5. 顺延日志：工具密集会话中 grep `合并顺延` 出现频率（出现 1-2 次属正常，每轮必现说明 USER 轮被吃——异常）。

## 6. ⚠️ 待解决（用户真机反馈，2026-09-28 晚；adb 解禁后处理）

**Gemma GPU 变体加载出现引擎错误**（用户实锤，症状细节未回传）。本波已把该路径的
可观测性铺满，下轮 adb 拿到日志即可定点：

1. 抓诊断页日志关键字 `GPU 后端不可用`（LiteRtLmEngine load 二段降级触发点）——
   后面跟的错误原文就是真因（OpenCL dlopen / CompiledModel::Create INTERNAL / 缓存目录不可写）。
2. 抓 `已以 CPU 后端完成加载` —— 出现即说明已自动降级成功，属「GPU 不可用」而非「GPU 跑错」。
3. 若 UI 出「请求 GPU 已降级 CPU 运行」小字（本波新增），则本波观测基建已生效，
   问题定性为「设备 GPU 委托不可用」，处置选项：换 CPU 变体 / 反馈机型驱动信息。
4. ⚠️ 区分两条路径：GPU **加载失败**（走降级，本波已覆盖）vs GPU **加载成功但输出异常**
   （后者不在本波观测面内，需用户反馈是否伴随回显——若伴随，则疑为 GPU 变体 chat
   template 元数据缺失，本波 preface 第三态闸门应会捕获，抓 `preface 校验失败` 日志）。

## 7. 挂账（Wave 34+）

- 原生工具通道（litertlm tools 参数）独立波次——回显的根治解。
- 系统提示词瘦身（工具清单紧凑化）——待真机语料。
- 诊断小字文案的关键词映射（透传技术串 → 可操作文案）。
- 沙箱浏览器递归浏览/目录下钻（首版仅根层）。
- Lint baseline 清障翻转历史遗留项。
- **Wave 35 判死：摘要检查点不再需要（原因：history_v2 已判死 + 压缩走现有窗口），`SummarizingContextCompressor` 删除。** 全仓零引用（含测试源集逐文件核验）、从未实例化；Wave 30 挂账后从 Wave 31/33 挂账清单里消失（无完成记录、无判死记录），本波补齐判死留痕。恢复路径 = git 历史，不留死代码占位。
- **Wave 35 新增挂账：`AgentMemory.upsert` 返回 false 现在有两种互不隶属的原因**（文件损坏 / 单条正文超 `MAX_CONTENT_CHARS`）。`feature-settings` 的 `MemoryViewModel.upsert` 仍把 `false` 一律解释成「文件损坏」（`corrupted = true`），人在设置页粘贴超长正文会看到误报「文件已损坏」。修它要动 UI 文案（feature-settings 模块），与 Wave 35 core 侧改动解耦，单独挂账。模型侧不受影响——`MemoryWriteTool` 的前置校验先拦下并回精确文案。
- **Wave 35 新增挂账：原生工具通道「同 run 内恢复」未做。** 本波只补齐了「判据漂移 → 落一条 warn」的留痕（消灭静默），未做同 run 内退回文本协议：那要把 `capabilities()` 的取值下沉到每一轮并让提示词工具面随轮切换，而提示词面变化会触发引擎会话重建（4B 端侧秒级 re-prefill），代价远超机械保守范围。
