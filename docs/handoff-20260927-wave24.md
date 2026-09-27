# Handoff: Wave 24 —— 根治「只输出提示词然后胡言乱语」（引擎角色通道）+ 复审遗留项收口

> 生成于 2026-09-27。仓库 `D:\WBfil\2026-09-18-18-27-37\Android-Agent`，分支 **`harness-improve`**。
> 远端已推送并 CI 双绿的 tip = **`70195d8`**；本文档为 docs-only 提交（`-` 见文末），**随下波代码推送一起上远端**。
> 新会话开工前先读本文件 + `../HANDOVER-harness.md` + `.workbuddy-ai/memory/2026-09-27.md`。

## 0. 本波结论一句话

用户反复反馈的「对话**只输出提示词然后胡言乱语**」，前三波（Wave 19/21/22）一直在**输出侧**加检测器（8 条判定 + 提示词硬约束 + 同参工具护栏），**全部治标**。本波在**输入侧**找到真根因并修复：**引擎把角色通道丢光了**。修复后 CI 双绿（一次通过、零返工）。

## 1. 根因（两位专家独立在 litertlm 0.17.1 源码级证实）

`core-engine/.../local/LiteRtLmEngine.kt`（修复前）：

1. `ensureConversation()` 传 `systemInstruction = null`、`initialMessages = emptyList()`；
2. `buildContents()` 把 `Role.SYSTEM / USER / MODEL / TOOL` **全部只取 `Content.Text`** 压进一个 `ArrayList<Content>`，再由 `Contents.of(contents)` 作为**一次** `sendMessageAsync` 的载荷。

证据链（决定性，JNI 之前即可断言）：
- `litertlm171_Conversation.kt:352-373` —— `sendMessageAsync(Contents, …)` 内部**无条件** `Message.user(contents)`；
- `litertlm171_Message.kt:44-64 / 81` —— `Message.toJson()` 只序列化 `{role, content}`，`Content.Text` 里没有任何角色标记解析；
- ⇒ **每次发送恒为「一条 role=user 的无角色纯文本」**。系统提示词 + 历史 + 本轮输入被拼成一段纯文本，小模型最自然的续写行为就是继续写这段文本 ⇒ 先复述提示词、再退化刷屏。

**第二重 bug**：`AgentRunner` 把模型自己的回复写回 `working`（:740/:804/:866/:881），下一轮水印过后被当新内容**回灌成 user 文本**；而 native Conversation 本就持有这条回复 ⇒ 模型读自己的旧输出当用户输入，**自我强化复读**。首轮最致命（新会话水印清空，全部历史含 MODEL 轮压平成一条 user）。

**为什么前三波都没修好**：`StreamRepetitionDetector` 的判定⑥（回显指纹）要求**句界与标点逐字对齐**，模型改一下标点/换行就永不命中；且它是**事后拦截** —— 拦截发生时提示词片段必然已经上屏。**「先输出提示词」在原理上无法被事后拦截**。

## 2. 修复（`bbfa3c9`，仅 `LiteRtLmEngine.kt`，+188/-17）

| 项 | 做法 |
|---|---|
| 系统提示词 | 改走 `ConversationConfig.systemInstruction`（0.17.1 起类型是 **`Contents?`**，旧版 `String?` —— 传裸 String 编译不过）；`systemText` **纳入会话重建判据**（提示词只在建会话时注入一次，不重建就永不生效），并单独记一行重建原因日志 |
| 历史 | 按 role 播种进 `initialMessages`：新增 `ChatMessage.toNativeMessage()`，USER→`Message.user`（附件在文本之前）、MODEL→`Message.model`（只带可见正文）、TOOL→`Message.user`；**播种的 id 预登记进水印**，避免下一轮重复发送 |
| 增量发送 | `buildContents` 用 `roleChannelActive` 门控跳过 SYSTEM/MODEL —— **杜绝 MODEL 回灌**（第二重 bug 一并修掉） |
| 回退 | `createConversation` 抛错时：`sentMessageIds.clear()` + `roleChannelActive=false` + 用全空 legacy 配置重建。**没有这条会「两边都不发」，模型彻底失去提示词与历史，比原 bug 更糟** |
| 生命周期 | `releaseInternal()` 里 `currentSystemText` / `roleChannelActive` 一并复位（复用同一清理函数，不另抄字段清单） |

**TOOL 用 `Message.user` 而非 `Message.tool`**：本项目工具走 Agent 层**文本协议**，native 侧没有配对的 tool_call 记录，`role=tool` 会让多数 chat template 判非法（已在 KDoc 申报）。

## 3. 其余三项（`569964b` / `ae1bc1c` / `70195d8`）

- **判定⑨ 字符级回显指纹**（纯增量，既有 8 条判定零改动）：把系统提示词归一化成连续字符流（`lowercase(Locale.ROOT).filter{isLetterOrDigit}`），在输出流上做**去标点/去空白/抗换行**的连续逐字匹配。`WINDOW=32 / STRIDE=16 / STREAK=3` ⇒ 至少 64 个归一化字符连续逐字复述才判循环。实现 = Rabin-Karp 滚动哈希（**指纹集必须步长 1**，回显起始偏移任意；流侧环形缓冲，每字符 O(1)，守住 O(delta) 纪律）。
  - ⚠️ **哈希幂次是 `base^(WINDOW-1)`**（按「先减最老、再乘 base 加新」的滑动顺序）。任务书里写的 `base^WINDOW` 是错的；指纹集与流侧**必须同公式同顺序**，口径分叉会让检测静默失效。
- **E2 单测安全网（连续三轮 P0 未动，本波清账）**：`core-model` 首次有测试源集，24 个用例；`core-model/build.gradle.kts` 补 `testImplementation(libs.junit)` + `libs.kotlin.test`（catalog 已有，**零新依赖**）。**零流水线改动** —— CI 的 `build.yml` 早有 `unit-tests` job 跑聚合 `gradle test --continue`，`:core-model:testDebugUnitTest` 自动从 `NO-SOURCE` 变真跑。
- **topP 接入钳制**（复审 P3）：`appliedTo` 补 `topP = minOf(s.topP, profile.recommendedTopP)`（上限语义、只降不升，与 `maxTopK` 同口径），日志区分「钳制 / 透传」。
- **2b 折射诊断**：诊断页新增「玻璃渲染能力」卡（`SDK_INT` / `isRuntimeShaderSupported()` / `isRenderEffectSupported()` + 可行动文案）。**必须 API 直读而不是查日志** —— `Lens.kt` 的降级日志在 release 包被 R8 `-assumenosideeffects` 整条删除。
- **U2 顶栏标题居中**：`GlassTopBar` 新增 `titleAlignment: Alignment.Horizontal = Alignment.Start`（默认 Start = 零回归），6 个 nav-only/空屏传 `CenterHorizontally`；**ChatScreen / ModelsScreen 有意不居中**（右侧 actions 更宽，KDoc 已留档量化理由）。

## 4. CI 终态（`70195d8`）

- Build `36300294246` success 224s（Assemble Debug 221s / **Unit tests 92s**）
- Release `36300294262` success 500s
- 产物：`liquidagent-release-apk-harness-improve` **69.99 MB** / `-mapping-` **4.22 MB** / `-debug-` **39.20 MB**（90/90/30 天）+ Build 轨 `liquidagent-debug-70195d8…` 39.20 MB
- **安全网生效证据**：`unit-tests` job 日志出现 `> Task :core-model:testDebugUnitTest` + `BUILD SUCCESSFUL`（上一轮 `5851978` 该行为 `NO-SOURCE`）；`core-data` 12 个壁纸用例同时通过
- Release 侧 16KB ELF 对齐断言、APK/AAB 验签、arch-guard 全部 success

## 5. 真机验收清单（本波新增，CI 查不出）

1. **P0 复现场景**：用 SmolVLM2-500M / Qwen2.5-1.5B 发同一批会触发复述的问题；预期**不再**逐字复述系统提示词。日志关键字：`LiteRT-LM 会话重建原因：系统提示词变化`、`角色通道播种失败…已回退 legacy`。
   - ⚠️ 若真机日志出现「角色通道播种失败」，说明该模型的 chat template 不接受 `systemInstruction`/`initialMessages`，**本波修复对它失效**（已自动回退到旧行为，不会更糟）。这条日志是本波最重要的真机信号。
2. **重建频率**（P1）：`systemText` 含记忆段 ⇒ 某 run 写了记忆后，下个 run 首次生成会重建会话（一次全量 re-prefill，4B 秒级）。观察该日志是否**每 run 一次**（可接受）还是**每轮一次**（异常，需加迟滞或把记忆段移出 systemInstruction）。
3. **相邻 user 轮次**（P1）：TOOL→`Message.user` 在「MODEL 正文为空被过滤」时可能产生相邻两条 user。这类问题**静默渲染错乱而非抛异常**，回退保险接不住。触发需连续两个异常轮次，后果是模型多看到一条 user turn。若真机出现输出错乱且日志无异常 → 优先怀疑这里，修法 = 播种时合并相邻 USER run。
4. 判定⑨：让模型复述系统提示词（≥64 归一化字符）应被 `prompt_echo_chars` 截断；正常长回答不应被误截。
5. 诊断页「玻璃渲染能力」卡：Android 13+ 显示「完整折射」，Android 12/12L 显示「仅模糊」。
6. 6 个页面顶栏标题居中观感；ChatScreen / ModelsScreen 保持左对齐。
7. 回归：工具调用轮次、`ask_actor` 子代理、上下文压缩重建、授权卡。

## 6. 留档 / 未做

- **A1 材质亮度自适应（复审报告标「最高设计价值」）** —— 本波判定**独立波次**，技术理由（柯码成核验）：① AGSL RuntimeShader 只作 RenderEffect（写图层），**无法把标量回读 CPU**，报告设想的「shader 内算均值」不可行；回读只能 `GraphicsLayer.toImageBitmap()`（GPU→CPU 同步，逐帧卡死）；② 唯一可行路径是「壁纸变更时算一次平均亮度」——而**默认壁纸是程序化纯色米白 ⇒ A1 默认零效果**，仅自定义照片壁纸有价值；③ 与「THIN 0.21 底色裁决」（报告明确留给用户）强耦合，须先裁决。
- **U3/A5 无障碍联动** —— Android **不存在** iOS 那种 Reduce Transparency 系统设置/API（不能编）。最接近的是 `AccessibilityManager.isHighTextContrastEnabled()`（API 21+），但**变更回调 `addHighContrastTextStateChangeListener` 是 API 36+** ⇒ minSdk 31 只能在 onStart/onResume 重读。可做但收益受 API 限制。
- **已知的既有误伤面**（本波未改，阈值是真机调优结果）：连续 ≥6 行裸 `---\n` 会触发判定④ `short_sig_run`。
- **原生工具通道（根治回显的终解）**：`Capabilities.supportsFunctionCalling()` 现成，但 `LiteRtLmEngine.kt` 硬编码 `nativeToolChannel = false`。启用后工具描述不必进系统提示词。需评估 `ToolSpec→ToolProvider` 映射 + 真机未验证风险 ⇒ 独立波次、按模型能力位门控。
- **`build.yml` 无 paths 过滤**（每次 push 都跑完整 Build）；`release.yml:38-39` 重复写了两次 `harness-improve`（GitHub 去重，无害）。
- 环境：`_ci-tools/ghapi.sh` 本轮修复（相对路径自动补 `https://api.github.com/`，此前必挂）+ PAT 换新；`_ci-tools/gitfetch.sh` 新建（fetch/push/lsremote）。

## 7. 相关文件

- 常驻交接：`../HANDOVER-harness.md`（§0.10 = 本波摘要）
- 会话记忆：`.workbuddy-ai/memory/2026-09-27.md`（本波条目）；旧路径 `.workbuddy/memory/2026-09-26.md`（Wave 15-21）
- litertlm 0.17.1 API 源码快照：`../_research/models/litertlm171_*.kt`（**改引擎必读**：`ConversationConfig.systemInstruction` 是 `Contents?`、`Message` 有 role 工厂）
- 审查报告：`F:\下载\LiquidAgent-审查总纲-终版.md`
- 本波未推送的 docs-only 提交：见 `git log`（`docs: Wave 24 交接文档`），随下波代码推送一起上远端
