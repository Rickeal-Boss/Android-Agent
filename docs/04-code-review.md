# LiquidAgent 代码质量审查报告

> 审查人：严质衡（code-quality-reviewer）
> 审查对象：`Rickeal-Boss/Android-Agent` @ `00c1ffe`（CI `:app:assembleDebug` 全绿）
> 审查范围：9 个 Gradle 模块全部 Kotlin 源码（81 个 .kt / 9506 行）+ 构建脚本 + Manifest
> 审查基线：本地无 JDK/SDK，全部为**静态阅读 + 运行时推演**，不依赖编译结果
> 铁律：本报告**不修改任何源码**，所有修复建议均为「可直接照做」的代码级方案

---

## 一、总体结论

# 需修改（不通过）

判定理由：CI 绿只证明「能编译」，不证明「能跑对」。本次审查在**运行时语义层面**发现
**4 个 P0（功能完全不可用 / 数据被写坏）** 与 **9 个 P1**，其中 3 个 P0 会在真机上
**第一次使用就必然触发**，且都不会抛异常、不会崩，而是「静默给出错误结果」——
这类缺陷 CI 永远抓不到，是最危险的那一类。

| 等级 | 条数 | 一句话 |
|---|---|---|
| P0 致命 | 4 | 本地/远程工具调用全链路失效、多模态附件全丢弃、回答被重复落盘两次 |
| P1 严重 | 9 | 上下文重复、轮次耗尽丢答案、并发释放 native 引擎、压缩拆散 tool 配对、主线程文件 IO 等 |
| P2 一般 | 11 | 流式重组风暴、原子写有名无实、卸载不释放显存、无限轮询等 |
| P3 建议 | 8 | 死代码/别名冗余、注释与实现不符、API Key 明文备份等 |

**「能编译」和「能用」之间目前隔着 4 个 P0。**
但请注意：**架构骨架是对的**。分层、依赖方向、模块边界、构建纪律都做到了
「开荒期不该有的干净程度」，P0 全部集中在「引擎桥接的语义细节」与
「事件流的终态语义」两处，属于**可定点修复**的问题，不需要返工架构。

---

## 二、问题清单

### P0-1 远程工具调用：工具执行结果**从未回传给模型**，`tool_call_id` 恒为空

**位置**
- `core-engine/.../remote/OpenAiCompatibleEngine.kt:287-306`（`buildMessages`）
- `core-engine/.../remote/OpenAiCompatibleEngine.kt:308-344`（`buildContent`）
- `core-agent/.../AgentRunner.kt:147-155 / 168 / 180 / 187`（TOOL 消息的构造）

**问题**
`AgentRunner` 构造 TOOL 消息时是 `ChatMessage(role = Role.TOOL, toolResults = listOf(result))`，
**没有给 `text` 赋值**。`buildContent(message)` 只读 `message.text` 与 `message.attachments`，
对 `toolResults` 视而不见：

```kotlin
// OpenAiCompatibleEngine.kt:309-339
val parts = ArrayList<JsonElement>()
if (message.text.isNotEmpty()) parts.add(JsonPrimitive(message.text))   // TOOL 消息 text == ""
for (attachment in message.attachments) { ... }                          // TOOL 消息无附件
if (parts.isEmpty()) return JsonPrimitive("")                            // ← 永远走这里
```

于是发给 OpenAI 的是 `{"role":"tool","tool_call_id":"","content":""}`。

**为什么是问题**
1. **工具输出根本没进请求** —— 模型永远看不到 `calculator`/`file_read`/`current_time` 的结果，
   只能靠幻觉续写，工具调用 100% 无意义。
2. **`toolCallId` 为空**：`ToolResult.callId` 默认 `""`（`ToolSpec.kt:56`），
   而 `FileTools.kt:43/48`、`SystemTools.kt:127/133` 等**没有任何一个内置工具给 `callId` 赋值**，
   `executeWithGuard` 的 `raw.copy(...)`（`AgentRunner.kt:204`）也只是把 `""` 拷过来。
   OpenAI 对 `tool_call_id` 缺失/不匹配会直接 **400**，
   或者判定 assistant 的 `tool_calls` 未被响应而报错。
3. **不会崩溃、不会报错** —— Agent 会一路跑到 `maxRounds`，最后把一串空话当答案。
   这正是「CI 全绿但功能死了」的典型形态。

**修法**（两步，必须都做）

① `AgentRunner` 侧：把 tool 结果写进 `text`，并带上 `callId`

```kotlin
// AgentRunner.kt —— 三处 working.add(ChatMessage(role = Role.TOOL, ...)) 统一改成：
private fun toolResultMessage(result: ToolResult): ChatMessage = ChatMessage(
    role = Role.TOOL,
    text = buildString {
        append("[工具 ${result.name} 返回]\n")
        append(if (result.ok) result.output else "错误：${result.errorMessage.orEmpty()}")
    },
    toolResults = listOf(result),
)

// executeWithGuard 的返回值补上 callId（ToolResult 已有该字段，只是没人填）：
val raw = withTimeout(policy.toolTimeoutMillis) { tool.invoke(call.argumentsJson) }
val filled = raw.copy(callId = raw.callId.ifBlank { call.id })   // ← 新增这一行
```

② `OpenAiCompatibleEngine` 侧：显式处理 `toolResults`

```kotlin
// OpenAiCompatibleEngine.kt:308 起
private fun buildContent(message: ChatMessage): JsonElement {
    // 新增：工具结果优先于 text
    val results = message.toolResults
    if (results.isNotEmpty()) {
        return JsonPrimitive(results.joinToString("\n") { r ->
            if (r.ok) r.output else "错误：${r.errorMessage.orEmpty()}"
        })
    }
    val parts = ArrayList<JsonElement>()
    ...
}

// 并且 buildMessages 里，一条 ChatMessage 若有 N 个 toolResults，要展开成 N 条 MessageDto
// （OpenAI 要求一个 tool_call_id 对应一条 tool message）：
private fun buildMessages(messages: List<ChatMessage>): List<MessageDto> = messages.flatMap { message ->
    if (message.role == Role.TOOL && message.toolResults.isNotEmpty()) {
        message.toolResults.map { r ->
            MessageDto(
                role = "tool",
                content = JsonPrimitive(
                    if (r.ok) r.output else "错误：${r.errorMessage.orEmpty()}"
                ),
                toolCallId = r.callId,            // ← 必须非空
            )
        }
    } else {
        listOf(/* 原逻辑 */)
    }
}
```

---

### P0-2 本地引擎：系统提示词 + 全部历史 + 工具结果**全部被丢弃**，本地 Agent 循环实质失效

**位置**
- `core-engine/.../local/LiteRtLmEngine.kt:259-280`（`buildContents`）
- `core-engine/.../local/LiteRtLmEngine.kt:172-179`（`ConversationConfig(systemInstruction = null, ...)`）

**问题**
`buildContents` 只取**最后一条 USER 消息**的附件和文本，其它一切全部丢弃：

```kotlin
// LiteRtLmEngine.kt:261-279
val last = request.messages.lastOrNull { it.role == Role.USER } ?: request.messages.lastOrNull()
if (last != null) { for (attachment in last.attachments) { ... } }
out.add(Content.Text(last?.text.orEmpty()))     // ← 只发这一条
```

后果有三，且**互相叠加**：

1. **系统提示词永远送不进去。**
   文件头部注释（`:53-55`）写的是「系统提示词改由 `messages[0]`（role=SYSTEM）承载」，
   但 `buildContents` 根本不读 SYSTEM 消息，而 `ConversationConfig` 又显式传了
   `systemInstruction = null`。**注释与实现直接矛盾。** 对于 4B 级模型，
   工具清单只能靠 system prompt 注入（见 `AgentRunner.buildSystemInstruction`，`:226-231`），
   提示词丢了 ⇒ 模型不知道有工具 ⇒ **本地模式下工具调用永远不会发生**。

2. **工具结果永远送不进去。**
   第 2 轮时 `working = [SYSTEM, ...history, USER, MODEL(tool_call), TOOL(result)]`，
   `lastOrNull { it.role == Role.USER }` 拿到的**还是最初那条用户消息**。
   于是第 2 轮把同一个问题又发了一遍给同一个 Conversation ——
   模型看到 `[Q, A(工具调用), Q]`，大概率再吐一次同样的工具调用，
   直到 `maxRounds` 耗尽。本地工具调用**从第一轮起就是死路**。

3. **会话恢复丢历史。** 冷启动后 Conversation 是新建的，
   磁盘里恢复出来的 N 轮历史一条都发不出去，只发最后一句。

**修法**：给引擎加「已发送水位」，每轮只发增量；把 SYSTEM 与 TOOL 显式编码成文本。

```kotlin
// LiteRtLmEngine 新增字段（与 conversation 同生命周期，reset 时归零）
private var sentUpTo: Int = -1          // 已发进当前 Conversation 的最后一条 messages 下标
private var systemSent: Boolean = false

private fun buildContents(request: GenerationRequest): List<Content> {
    val all = request.messages
    val out = ArrayList<Content>(4)

    // 1) 会话首轮：把 SYSTEM 作为一条文本发出（不依赖 ConversationConfig 的构造方式）
    if (!systemSent) {
        val sys = all.filter { it.role == Role.SYSTEM }.joinToString("\n") { it.text }
        if (sys.isNotBlank()) out.add(Content.Text(sys))
        systemSent = true
    }

    // 2) 只发「上一轮之后新增的消息」
    val pending = if (sentUpTo < 0) all else all.drop(sentUpTo + 1)
    sentUpTo = all.lastIndex

    for (m in pending) {
        when (m.role) {
            Role.SYSTEM -> Unit                                  // 已在首轮发过
            Role.USER -> {
                for (a in m.attachments) {                       // 图片/音频在前
                    when (a) {
                        is Attachment.Image -> AttachmentBytesReader.imagePngBytes(a.uri)
                            ?.let { out.add(Content.ImageBytes(it)) }
                        is Attachment.Audio -> AttachmentBytesReader.audioBytes(a.uri)
                            ?.let { out.add(Content.AudioBytes(it)) }
                        is Attachment.Text  -> if (a.text.isNotBlank()) out.add(Content.Text(a.text))
                        is Attachment.File  -> Unit
                    }
                }
                out.add(Content.Text(m.text))                    // 文本在最后
            }
            Role.MODEL -> if (m.text.isNotBlank()) out.add(Content.Text(m.text))
            Role.TOOL -> for (r in m.toolResults) {              // ← 关键：工具结果回灌
                out.add(Content.Text(
                    "[工具 ${r.name} 返回]\n" +
                    (if (r.ok) r.output else "错误：${r.errorMessage.orEmpty()}")
                ))
            }
        }
    }
    if (out.isEmpty()) out.add(Content.Text(all.lastOrNull()?.text.orEmpty()))
    return out
}

// 配套：ensureConversation 里「重建会话」的两个出口都要复位水位
// （:154-156 关旧会话处、:180-182 新建会话处）
conversation = null; sentUpTo = -1; systemSent = false
```

> 备选更省事的方案：若真机能核实 `ConversationConfig(systemInstruction = ...)` 的构造方式，
> 则 SYSTEM 走该字段，上面第 1) 段可删。但在核实之前，**用文本承载是唯一不会错的做法**。

---

### P0-3 每条回答被提交两次：UI 双气泡 + 会话文件里写两份

**位置**
- `core-agent/.../AgentRunner.kt:143`（`emit(MessageCommitted)`）→ `:144 break` → `:193`（`emit(Finished)`）
- `feature-chat/.../ChatViewModel.kt:408-410`（`MessageCommitted → commit()`）
- `feature-chat/.../ChatViewModel.kt:412-431`（`Finished → 再造一条 ChatMessage → commit()`）

**问题**
正常结束路径是：`MessageCommitted(committed)` → `break` → `Finished(finalText)`。
`ChatViewModel` 对这两个事件**各 commit 一次**：

```
MessageCommitted → messages += assistant ; appendMessage(cid, assistant)
Finished         → messages += assistant2; appendMessage(cid, assistant2)   // 同一段文本，第二条
```

`Finished` 分支里 `event.text.ifBlank { streamingText }`，而 `event.text == finalText == cleanText`
（非空），所以**必然**走 `ifBlank` 的 false 分支 ⇒ 必然重复。

**为什么是问题**
1. 屏幕上同一条回答出现两个气泡（严重可见）；
2. `ConversationRepository.appendMessage` 是「全量读 → 追加 → 全量写」（`:297-300`），
   重复写会把**脏数据固化进 JSON 文件**，App 重启后两条都还在，且后续每轮继续翻倍式污染；
3. 第二条还会把上一轮的 `streamingThinking` 一起带上（`:416`），因为 `MessageCommitted`
   分支没有清理 `streamingThinking`。

**修法**（推荐 A，语义最干净）

A. 让终态事件互斥 —— `AgentRunner` 在 `break` 前不 emit `Finished`，改为统一出口：

```kotlin
// AgentRunner.kt：给「已提交最终消息」立一个标志
var committed: ChatMessage? = null
...
if (calls.isEmpty()) {
    ...
    committed = ChatMessage(...)
    working.add(committed)
    emit(AgentEvent.MessageCommitted(committed))
    break                                   // 不再 emit Finished
}
...
// 循环结束后：
emit(
    if (committed != null) AgentEvent.Finished(committed.text, round, lastUsage)
    else AgentEvent.Finished(finalText, round, lastUsage)   // 轮次耗尽的兜底
)
```

```kotlin
// ChatViewModel.kt:412-431 —— Finished 分支只做「收尾」，不再 commit
is AgentEvent.Finished -> _uiState.update {
    it.copy(
        streamingText = "", streamingThinking = "",
        isStreaming = false, isGenerating = false, toolTraces = emptyList(),
        error = if (event.text.isBlank() && it.messages.lastOrNull()?.role != Role.MODEL)
            "已达到最大轮次（${event.round}），未产出最终回答" else null,
    )
}
```

B. （若不想动 AgentRunner）在 ViewModel 里加 `private var committedFinal = false`，
`MessageCommitted` 时置 true，`Finished` 时 `if (!committedFinal) commit(...)`。
但 A 更根本，**推荐 A**。

---

### P0-4 多模态附件 100% 丢失：`content://` Uri 被当成文件路径

**位置**
- `feature-chat/.../ChatAttachmentPicker.kt:452-477`（SAF 返回 `content://`，直接 `uri.toString()` 存进 `Attachment.Image.uri`）
- `core-engine/.../local/AttachmentBytesReader.kt:163-178 / 180-189`（`File(stripScheme(uri))`）
- `core-engine/.../local/AttachmentBytesReader.kt:191-192`（`stripScheme` 只剥 `file://`）
- `core-engine/.../remote/OpenAiCompatibleEngine.kt:346-361`（同样 `File(...)`）

**问题**
SAF（`ActivityResultContracts.OpenDocument`）返回的是 `content://com.android.providers.media.documents/document/image:12345`。
引擎侧做的是：

```kotlin
val file = File(stripScheme(uri))     // File("content://...") —— 这不是路径
if (!file.exists()) return null       // ← 永远 return null
```

`imagePngBytes` 返回 `null` 后，`buildContents` 里 `?.let { out.add(...) }` 静默跳过（`:266-269`）；
`imageToDataUri` 同理返回 `null`，`buildContent` 静默跳过（`OpenAiCompatibleEngine.kt:316`）。
**图片/音频一个字节都发不出去，且没有任何日志、任何提示。**

讽刺的是 UI 侧是能显示缩略图的 —— `decodeThumbnail` 用了 `contentResolver.openInputStream`
（`GlassSurface.kt:413-430`），所以「用户看到图了」但「模型没看到图」，
这个 bug 在真机上极难被归因。

**修法**：在**选图时**就把内容落盘到 App 私有目录，让 `Attachment.uri` 始终是**真实文件路径**。
引擎层不碰 `Context`（分层是对的，不要破坏）。

```kotlin
// ChatViewModel.kt：新增落盘，onAttachImage / onAttachAudio 复用
private suspend fun materialize(uriString: String, dir: String, ext: String): String? =
    withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.parse(uriString)
            val targetDir = File(container.attachmentsDir, dir).apply { mkdirs() }   // 见下方 AppContainer
            val target = File(targetDir, "${System.currentTimeMillis()}.$ext")
            container.appContext.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            } ?: return@runCatching null
            target.absolutePath
        }.getOrNull()
    }

fun onAttachImage(uriString: String, name: String) {
    viewModelScope.launch {
        val path = materialize(uriString, "images", "png") ?: run {
            _uiState.update { it.copy(error = "图片读取失败") }; return@launch
        }
        _uiState.update { s ->
            s.copy(attachments = s.attachments + Attachment.Image(uri = path, name = name.ifBlank { "图片" }))
        }
    }
}
```

```kotlin
// AppContainer.kt：暴露目录与 applicationContext（AppContainer 已持有 context）
val attachmentsDir: File = File(context.filesDir, "attachments").apply { mkdirs() }
val appContext: Context = context.applicationContext
```

> 附带收益：会话落盘后重开 App 仍能读到（不再依赖 `takePersistableUriPermission` 的长期授权，
> 该授权在 App 被杀后并不保证存活）。
> 注意：`Attachment.Text` / `Attachment.File` 在 `buildContents` 里被 `Unit` 吞掉（`:273`），
> 也应一并按上面的 pending 循环处理（文件类至少要把文本抽出来）。

---

### P1-1 用户消息被塞进上下文两次

**位置**：`ChatViewModel.kt:233`（`val history = state.messages + userMessage`）+ `:262`（`history = history`）+ `:263`（`userInput = userMessage`）；`AgentRunner.kt:70-71`（`addAll(history)` 后又 `add(userInput)`）。
同样问题在 `onRetry`（`:289` `trimmed` 已含最后一条 USER，`onSendFrom(trimmed[lastUserIndex], trimmed)` 再传一次，`:299`）。

**为什么是问题**：远程引擎的 payload 里用户消息出现两遍，浪费 token 且会让部分模型复读；
本地引擎虽只看最后一条 USER，但 `TokenEstimator` / 压缩预算被重复计算。

**修法**：`AgentRequest` 的语义应当是「history = 用户输入之前的所有消息」。

```kotlin
// ChatViewModel.kt:233 / :262-263
val history = state.messages                       // 不含 userMessage
val request = AgentRequest(history = history, userInput = userMessage, ...)
// onRetry 同理：onSendFrom(trimmed[lastUserIndex], trimmed.dropLast(1))
```
并在 `AgentRunner` 顶部加一条防御：
```kotlin
working.addAll(request.history.filter { it.id != request.userInput.id })
working.add(request.userInput)
```

---

### P1-2 轮次耗尽后把「上一轮的原始工具 JSON」当答案提交

**位置**：`AgentRunner.kt:77 / 190 / 193`（`while (round < maxRounds)`，`round++` 只在走工具分支时执行；耗尽后 `finalText` 仍为 `""`）；`ChatViewModel.kt:415`（`event.text.ifBlank { streamingText }`）。

**为什么是问题**：`maxRounds` 耗尽退出循环 ⇒ `emit(Finished("", ...))` ⇒
`event.text` 为空 ⇒ 退回 `streamingText`，而 `streamingText` 此时是**最后一轮未 strip 的原始输出**
（对于文本协议模式就是一整块 ```` ```json ````）。用户看到一坨 JSON，且它被**落盘**了。
`AgentEvent` 里也没有任何「因轮次耗尽而终止」的信号，UI 无从区分。

**修法**：

```kotlin
// AgentRunner.kt —— 循环外
if (round >= policy.maxRounds && finalText.isBlank()) {
    emit(AgentEvent.Failed("已达到最大轮次（$round/${policy.maxRounds}）仍未产出最终回答"))
    return@flow
}
emit(AgentEvent.Finished(finalText, round, lastUsage))
```
```kotlin
// ChatViewModel.kt:415 —— 别再用 streamingText 兜底
is AgentEvent.Finished -> {
    if (event.text.isNotBlank()) commit(ChatMessage(role = Role.MODEL, text = event.text, ...), conversationId)
    _uiState.update { it.copy(streamingText = "", ..., error = if (event.text.isBlank()) "本轮未产出回答" else null) }
}
```

---

### P1-3 模型加载/卸载与正在进行的推理无互斥，可能把 native Engine 从脚底下抽掉

**位置**：`LiteRtLmEngine.kt:80-139`（`load` 在 `mutex` 内调用 `releaseInternal()`，`releaseInternal` 会 `engine.close()`）vs `:150-183`（`ensureConversation` **不加锁**）vs `:245-254`（`generateStream` 持有 `conv` 并 `sendMessageAsync`）。

**为什么是问题**：`ModelsViewModel.onLoad`（`:196-240`）和 `ChatViewModel.onSend` 用的是
`DefaultEngineFactory` 缓存的**同一个** `LiteRtLmEngine` 实例。
用户在对话流式输出过程中切到「模型」页点「加载」，就会走到 `releaseInternal()`：
`conversation.close()` + `engine.close()`。而此时 `generateStream` 的 `finally` 还在
`runCatching { conv.cancelProcess() }` —— **对已释放的 native 对象调用方法**。
LiteRT-LM 是 JNI 封装，这种用法的结果不是抛 Java 异常，而是 **SIGSEGV / 进程直接崩**。

**修法**（最小改动）：把生成过程也纳入同一把锁的保护范围，并让 `load` 主动停止在途生成。

```kotlin
// LiteRtLmEngine 新增
private val generationMutex = Mutex()
@Volatile private var inFlight: (() -> Unit)? = null     // 取消钩子

// generateStream 的 flow 体内，ensureConversation 前后：
generationMutex.withLock {
    val conv = ensureConversation(request)
    ...
    try { channel.consumeAsFlow().collect { emit(it) } }
    finally { runCatching { conv.cancelProcess() }; runCatching { channel.cancel() } }
}

// load() 的 releaseInternal() 之前：
inFlight?.invoke()            // 先让在途生成停下来
runCatching { generationMutex.withLock { } }   // 等它真正退出临界区
releaseInternal()
```
更彻底的做法是给 Engine 做引用计数 +「切换模型前由上层先 `onStop()`」，
但上面的方案足以消除崩溃窗口，且不动架构。

---

### P1-4 上下文压缩会拆散 `MODEL(tool_calls)` 与 `TOOL(result)` 的配对

**位置**：`core-agent/.../ContextCompressor.kt:343-367`。

**为什么是问题**：压缩是「按 token 从新到旧贪心装填」，完全不感知
「assistant 带 tool_calls 的消息必须紧跟对应 tool 消息」这条 OpenAI 硬约束。
一旦 MODEL(tool_calls) 被保留而它的 TOOL 结果被丢（或反之），
请求会直接被服务端 **400** 拒绝：`An assistant message with 'tool_calls' must be followed by tool messages responding to each tool_call_id`。
长对话 + 开工具 = 必现。

**修法**：把「MODEL(有 toolCalls) + 紧跟的 TOOL 消息」当成**一个不可分割的组(atom)**参与装填。

```kotlin
// ContextCompressor.kt：compress 内先做分组
private fun atomize(rest: List<ChatMessage>): List<List<ChatMessage>> {
    val atoms = ArrayList<List<ChatMessage>>()
    var i = 0
    while (i < rest.size) {
        val m = rest[i]
        if (m.toolCalls.isNotEmpty()) {
            val group = ArrayList<ChatMessage>().apply { add(m) }
            var j = i + 1
            while (j < rest.size && rest[j].role == Role.TOOL) { group.add(rest[j]); j++ }
            atoms.add(group); i = j
        } else { atoms.add(listOf(m)); i++ }
    }
    return atoms
}
// 然后：used 计算改为整组成本；装不下整组就整组丢弃（而不是丢半个）
```

---

### P1-5 文本协议对「最终答案」也做解析，用户要 JSON 就变死循环

**位置**：`AgentRunner.kt:122`（`TextToolProtocol.parse(accumulator.text)`）；`TextToolProtocol.kt:254-267`。

**为什么是问题**：只要模型输出里出现 ```` ```json ```` 围栏或 `<tool_call>` 标签，
就会被当成工具调用。用户问「给我一段 JSON 配置」时，**正常答案**会被误判：
命中未注册工具 ⇒ 产出「未注册的工具：xxx」⇒ 喂回模型 ⇒ 再吐一遍 JSON ⇒ …
一路烧到 `maxRounds`。本地模式下这个概率不低（`parse` 的裸 JSON 兜底 `:262-264`
还会把任意带 `{...}` 的散文也解析一遍）。

**修法**（三条，建议全上）：

```kotlin
// 1) 只接受「注册过的」工具名，未注册的直接不算工具调用
val parsed = TextToolProtocol.parse(accumulator.text)
val calls = parsed.filter { toolRegistry.get(it.name) != null }

// 2) 最后一轮不再尝试解析（把轮次留作纯输出轮）
if (round >= policy.maxRounds - 1) emptyList() else calls

// 3) 重复调用检测：本轮解析出的 (name, argsJson) 若与已执行过的完全相同，直接终止
if (executedSignatures.contains(call.name to call.argumentsJson)) { /* 视为最终答案，break */ }
```

---

### P1-6 点「停止」后，已生成的半截回答被丢弃

**位置**：`ChatViewModel.kt:216-220`（`onStop` 直接 `runJob?.cancel()`）；`AgentRunner.kt:104-107`（`emit(AgentEvent.Cancelled(...))` 后 `throw t`）；`ChatViewModel.kt:437-453`（`Cancelled` 分支）。

**为什么是问题**：`runJob.cancel()` 取消的是**整个** `viewModelScope.launch`，
`collect` 所在的协程已经处于 cancelled 状态；此时 `AgentRunner` 里那个
`emit(AgentEvent.Cancelled(accumulator.text))` 的 `emit` **必定立即抛 CancellationException**，
事件根本发不到 `handleEvent`。用户点了停止，屏幕上 Streaming 气泡直接消失，半截答案没了。

**修法**：不要在取消态里 emit。用 `NonCancellable` 把「保存现场」这一步摘出来，
或更简单地 —— **让 ViewModel 直接持有 accumulator 的镜像**（它本来就已经有 `streamingText`）：

```kotlin
// ChatViewModel.kt —— 最省事且最可靠的修法
fun onStop() {
    val partial = _uiState.value.streamingText      // ① 先取现场
    runJob?.cancel()
    runJob = null
    if (partial.isNotBlank()) commit(ChatMessage(role = Role.MODEL, text = partial), conversationId)
    _uiState.update { it.copy(isStreaming = false, isGenerating = false, streamingText = "", streamingThinking = "") }
}
```
（若坚持走事件，则在 `AgentRunner` 里用 `withContext(NonCancellable) { emit(AgentEvent.Cancelled(...)) }`。）

---

### P1-7 工具在**主线程**做文件 IO 与剪贴板操作

**位置**：`feature-settings/.../tools/ToolsViewModel.kt:103-109`（`viewModelScope.launch { tool.invoke(args) }`，`viewModelScope` 默认 `Dispatchers.Main`）；`FileTools.kt:45`（`file.readText().take(200_000)`）、`:72`（`writeText`）、`:93`（`listFiles`）。

**为什么是问题**：`file_read` 一次能读 200K 字符并做字符串拷贝，`file_list` 遍历目录 —— 全部跑在 Main。
「工具试跑」页点一下就可能掉帧；极端情况 ANR。
（注：经过 `AgentRunner.executeWithGuard` 的路径是安全的，因为 `run()` 有 `flowOn(Dispatchers.Default)`；
**只有 ToolsScreen 的试跑入口漏了调度器**。）

**修法**：

```kotlin
// ToolsViewModel.kt:109
val outcome = withContext(Dispatchers.IO) { runCatching { tool.invoke(args) } }
```
顺带：ClipboardTool 也应在 IO 上跑（`ClipboardManager` 内部有 Binder 调用）。

---

### P1-8 远程图片原图直传 base64，无降采样 → OOM / HTTP 413

**位置**：`OpenAiCompatibleEngine.kt:346-361`（`file.readBytes()` + `Base64.encodeToString`）。

**为什么是问题**：本地引擎侧做了 `downscale(bitmap, 1MB 像素)`（`AttachmentBytesReader.kt:169/` `:194-202`），
远程侧**完全没有**。一张 4000×3000 的照片 ≈ 3~5MB，base64 后 ≈ 4~7MB，
再加上 `json.encodeToString` 会**再拷一份字符串**、OkHttp 再拷一份 →
单次请求峰值十几 MB。低端机直接 OOM，服务端则大概率 413。
（这是典型的「同一件事两条路径实现不一致」。）

**修法**：把降采样逻辑提到 `core-engine` 的公共位置，两条路径共用。

```kotlin
// 新增 core-engine/.../remote/ImageDownsampler.kt（或把 AttachmentBytesReader.downscale 提为 internal 工具）
private fun imageToDataUri(uri: String): String? {
    val file = File(uri.removePrefix("file://"))
    if (!file.exists()) return null
    // 先用 inJustDecodeBounds 算 inSampleSize，再解码到长边 ≤ 1024
    val bmp = decodeSampled(file, maxDim = 1024) ?: return null
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)   // JPEG 比 PNG 小一个量级
    bmp.recycle()
    return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(out.toByteArray())
}
```

---

### P1-9 `JsonFileStore.write` 的原子写有名无实

**位置**：`core-data/.../JsonFileStore.kt:117-130`。

**为什么是问题**
1. tmp 文件名固定为 `"$fileName.tmp"`。`ChatViewModel.commit()` 每次落盘都
   `viewModelScope.launch { appendMessage(...) }`（`:459-461`），**不等待上一个完成**（见 P2-8），
   两次并发写会互相覆盖 tmp，最终落盘内容错乱；
2. `renameTo` 失败时退化成 `target.writeText(tmp.readText())` —— 整文件进内存且**非原子**；
3. 失败路径下 tmp 残留，且 `delete()` 里虽然清了 tmp，但顺序是先删正式文件。

**修法**

```kotlin
suspend fun <T> write(fileName: String, value: T, strategy: SerializationStrategy<T>) =
    withContext(Dispatchers.IO) {
        if (!baseDir.exists()) baseDir.mkdirs()
        val target = File(baseDir, fileName)
        val tmp = File(baseDir, "$fileName.${System.nanoTime()}.tmp")   // ① 唯一 tmp 名
        try {
            tmp.writeText(json.encodeToString(strategy, value))
            if (!tmp.renameTo(target)) {                                // ② 不再退化成非原子写
                throw java.io.IOException("rename failed: ${tmp.name}")
            }
        } finally {
            tmp.delete()
        }
    }
```
并把仓库的写操作串行化（见 P2-8 的 `writerScope`）。

---

### P2-1 流式输出引发全列表重组（每个 token 重建整份 `ChatUiState`）

**位置**：`ChatViewModel.kt:356-358`；`ChatScreen.kt:50`（`val state by viewModel.uiState.collectAsState()`）；`ChatMessageList.kt:335-345`；`ChatScreen.kt:190-191 / 212-214`（每次重组新建 lambda）。

**为什么是问题**：每来一个 token 就 `copy` 一整份 `ChatUiState`（含 `messages`/`toolTraces`/`config`…），
`ChatScreen` 整个重组 → `ChatMessageList` 因 `streamingText` 变化而重组 →
`LazyColumn` 重组 → **所有可见 item 的 lambda 重跑**。
`MessageRow(onToggleThinking = { id -> viewModel.toggleThinking(id) })` 每次都是新 lambda，
item 无法跳过 ⇒ 每个可见气泡（各自带一层 `liquidGlass` 的 `drawWithCache`）完整重走组合+布局。
长对话 + 高 tok/s 时是最典型的掉帧源。

**修法**（三处，按性价比排序）

```kotlin
// ① 流式文本独立成流，只让「streaming 那一个 item」订阅
// ChatViewModel：
private val _streamingText = MutableStateFlow("")
val streamingText: StateFlow<String> = _streamingText.asStateFlow()
// handleEvent: is AgentEvent.TextDelta -> _streamingText.update { it + event.text }   // O(1) 追加语义
// 并从 ChatUiState 里删掉 streamingText / streamingThinking 两个字段

// ChatMessageList：streaming item 内部单独 collect
item(key = "streaming", contentType = 1) {
    val text by vmStreamingText.collectAsState()
    GlassBubble(text = text, isUser = false, isStreaming = true)
}

// ② ChatScreen：把 lambda 变成稳定引用
val onToggleMessageThinking = remember(viewModel) { { id: String -> viewModel.toggleThinking(id) } }

// ③ ChatMessageList：把 List<ChatMessage> 包一层 @Immutable，或至少让 MessageRow 只吃原始字段
```

---

### P2-2 `ChatUiState` / `ChatMessage` 的 `List<...>` 字段使 Compose 跳过失效

**位置**：`ChatViewModel.kt:38-63`（`messages: List<ChatMessage>`、`toolTraces: List<ToolTrace>`、`expandedThinkingIds: Set<String>`）；`ChatMessageList.kt:305-308`。

**为什么是问题**：Compose 编译器无法证明 `kotlin.collections.List/Set` 不可变，
会把这些参数判为 unstable ⇒ 即便内容没变，`ChatMessageList` / `MessageRow` 也无法 skip。
这是 P2-1 的放大器。
（好消息：`ToolTrace` / `ChatUiState` / `ThemeState` / `ModelsUiState` 都规规矩矩标了 `@Immutable`，习惯是对的。）

**修法**（不引新依赖的前提下）：给跨层传递的集合加一个 `@Immutable` 包装，或把参数收敛为
「`@Immutable data class MessageListState(val items: List<ChatMessage>)`」；
最低成本是升级到 Compose 编译器的 `stable collections` 支持（Compose 1.9+ 可对
`kotlinx.collections.immutable` 生效）——但那要加依赖，**与本工程硬约束冲突**，
故推荐包装法 + 让 `MessageRow` 只接收 `ChatMessage` 单对象。

---

### P2-3 `tokenCount` 的公式写错了（除以上下文长度）

**位置**：`OpenAiCompatibleEngine.kt:263-266`

```kotlin
return (text.length * 1000 / remote.contextLength.coerceAtLeast(1)).coerceAtLeast(1)
```

**为什么是问题**：token 估算不应该随 `contextLength` 反比变化。
`contextLength=32768` 时，一段 1000 字中文估成 30 个 token（真实约 500~700）。
这个数会流进上下文预算与 UI 展示。

**修法**：`(text.length / 3).coerceAtLeast(1)`（中文按 ~3 字符/token 估，与 `TokenEstimator` 保持一致；
或干脆复用 `TokenEstimator.estimate(text)`）。

---

### P2-4 会话落盘是「整文件读-改-写」，且 `autoTitle` 会拿 SYSTEM 文本当标题

**位置**：`ConversationRepository.kt:297-300`（`appendMessage`）；`:322-329`（`autoTitle` 用 `firstOrNull { it.text.isNotBlank() }`，不区分 role）。

**为什么是问题**：每来一条消息就整文件反序列化 + 追加 + 序列化 + 写盘，一轮对话（含工具消息）可能 5~10 次，
复杂度随对话长度平方增长；`autoTitle` 会把第一条 SYSTEM 提示词的前 24 字当成会话标题
（虽然目前 `ensureConversation` 已自带标题、而 `autoTitle` 又没人调用，见 P3 死代码，
但一旦接上就会出错）。

**修法**：`autoTitle` 加 `it.role == Role.USER` 过滤；`appendMessage` 若未来要用，
改为在 `ConversationRepository` 内维护一个 `Mutex` + 内存缓存，批量 flush。

---

### P2-5 「卸载模型」只关会话不关引擎，2~4GB 内存照占，UI 却提示「已卸载」

**位置**：`ModelsViewModel.kt:242-251`（`onUnload` 调用 `engine.unload()`）；`LiteRtLmEngine.kt:311-320`（`unload` 只 `conversation?.close()`，`engine` 保留）。

**为什么是问题**：设计上「保留 engine 以便快速重载」是合理的，
但 UI 文案是「已卸载」，`loadedModelId` 也置空了 —— **用户以为内存释放了**。
在低内存设备上这直接导致后续加载新模型时 OOM（旧 engine 的 native 内存还没还）。

**修法**：语义对齐。要么文案改成「已释放会话（模型权重仍在内存，切换模型时释放）」，
要么提供真正的释放：

```kotlin
// EngineFactory 增加
fun release(kind: EngineKind) { engines.remove(kind)?.let { runCatching { it.close() } } }
// ModelsViewModel.onUnload 调 container.engineFactory.release(EngineKind.LOCAL)
```

---

### P2-6 下载轮询是 `while(true)` 无上限、无取消句柄

**位置**：`ModelsViewModel.kt:120-152`；`:156-161`（`onCancelDownload` 只 `manager.remove(id)`，不取消轮询协程）。

**为什么是问题**：下载暂停/卡住时，这个协程会每秒 `_uiState.update` 一次、永远跑下去
（直到 ViewModel 被清）。`onCancelDownload` 之后轮询仍在跑，
下一轮 `progress()` 因任务已不存在返回 `STATUS_SUCCESSFUL`，
于是弹「下载完成，导入失败」—— 误导用户。

**修法**：保存 `Job` 句柄并在 `onCancelDownload` 里 cancel；加最大轮次/超时；
`progress()` 对「任务不存在」应返回一个可区分的状态而不是 `STATUS_SUCCESSFUL`。

---

### P2-7 系统提示词用 `onParamPreview`，不碰滑块就永不落盘

**位置**：`ChatParamsPanel.kt:239-247`（`GlassTextField(onValueChange = { v -> onParamPreview { ... } })`）。
`onParamPreview`（`ChatViewModel.kt:176-178`）只改内存，落盘靠 `onParamCommit`
（`:180-183`），而 commit 只在滑块的 `onValueChangeFinished` 里触发。

**为什么是问题**：用户只改系统提示词 → 退出页面 → DataStore 里还是旧值；
且一旦别的写操作触发 `inferenceConfig` 回流（`:82-92`），编辑内容会被覆盖丢失。

**修法**：文本框用 `onParamChange`（即时落盘），或加防抖：
```kotlin
// ChatViewModel 增加
private var commitJob: Job? = null
fun onParamPreviewDebounced(t: (InferenceConfig) -> InferenceConfig) {
    onParamPreview(t)
    commitJob?.cancel()
    commitJob = viewModelScope.launch { delay(600); onParamCommit() }
}
```

---

### P2-8 落盘是 fire-and-forget，且 `onRetry` 不清理旧回答

**位置**：`ChatViewModel.kt:457-462`（`commit` 里 `viewModelScope.launch`，不等完成）；`:284-300`（`onRetry` 只改内存 `messages`，不动已落盘的旧 assistant 消息）。

**为什么是问题**：退出页面/进程被杀时最后一条消息可能丢失；
`onRetry` 后会话 JSON 里会同时留下「旧的错误回答」和「新的重试回答」。

**修法**：`AppContainer` 提供一个 `val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + 单线程)`，
`commit` 改为 `writerScope.launch { ... }`（不受 ViewModel 生命周期影响）；
`onRetry` 里顺带 `conversationRepository` 增加 `trimAfter(conversationId, lastUserIndex)`。

---

### P2-9 两个「永远失败」的占位工具默认注册，污染工具清单

**位置**：`DefaultTools.kt:290-299`（`register(WebSearchTool())` / `register(ImageDescribeTool())`）；
`SystemTools.kt:152-180 / 185-212`（fetcher/describer 默认 `{ null }`，必然返回 `ok=false`）。

**为什么是问题**：它们出现在 system prompt 的工具清单里（`AgentRunner.buildSystemInstruction`），
4B 模型看到「web_search」会频繁尝试调用，每次都得到
「网络搜索未配置」——白白消耗轮次与上下文，还可能把 Agent 拖到 `maxRounds`。

**修法**：默认**不注册**；在设置里配置了对应能力后再 `register`；
或至少 `ToolSpec` 增加 `available: Boolean`，`buildSystemInstruction` 过滤掉不可用的。

---

### P2-10 `unload()` 后会话 id 复位为空，下次请求静默重建会话丢历史

**位置**：`LiteRtLmEngine.kt:311-320`（`unload` 把 `currentConversationId = null` 且 `conversation = null`）；`:153-156`（重建判断）。

**为什么是问题**：`unload` 后 `currentConversationId == null`，
下一次 `generateStream` 传入的 `request.conversationId`（非空）必然 `!= null` ⇒ 关掉（已经是 null）
并重建 Conversation ⇒ **LiteRT Conversation 内的历史全部丢失**，
而上层（ChatViewModel）毫不知情，继续按「有历史」来渲染。

**修法**：`unload` 若只是释放会话，应保留 `currentConversationId = null` 但
在 `ensureConversation` 里对「重建」这一事实**上抛**（例如抛一个 `EngineException("会话上下文已丢失，请重新加载")`），
或让上层在 `unload` 后主动清空 UI 的历史（配合 P2-5 的 release 语义）。

---

### P2-11 自动滚动与用户手动上滑打架

**位置**：`ChatMessageList.kt:311-317`（`LaunchedEffect(messages.size)` 无条件 `animateScrollToItem`；`LaunchedEffect(isStreaming)` 再滚一次）。

**为什么是问题**：多轮工具调用时每条消息都触发一次滚动动画；
用户想往上翻看历史时，新消息一到就被强行拽回底部。

**修法**：仅在「用户本来就在底部」时自动滚：
```kotlin
val atBottom by remember { derivedStateOf {
    val li = listState.layoutInfo
    li.visibleItemsInfo.lastOrNull()?.index == li.totalItemsCount - 1
} }
LaunchedEffect(messages.size) { if (atBottom) listState.animateScrollToItem(messages.lastIndex) }
```

---

### P3 建议（8 项，不阻塞）

1. **死代码清理**（均无任何调用方）：`JsonFileStore.ensureDir()` / `list()`、
   `InferenceConfig.stream`（声明后从未读取）、`LlmEngine.isLoaded` / `tokenCount`（无消费方）、
   `ToolRegistry.unregister()` / `registerAll()`、`ToolContributor` 接口、
   `SummarizingContextCompressor`（从未实例化）、`ModelRepository.observeActiveModel()`、
   `ConversationRepository.autoTitle()`、`AppContainer` 的三个别名属性（`:28-31`）。
2. **命名双轨应收敛**：`ModelRepository` 与 `typealias ModelsRepository`、
   `ConversationRepository` 与 `typealias ConversationsRepository`、
   `EndpointRepository` 与 `typealias RemoteEndpointsRepository`（`:22 / :269 / :243`）。
   类型别名是开荒期为了「两边叫错名字也能编译」的权宜之计，
   现在代码已定型，建议**统一成一个规范名并删除别名**。
3. **`AppContainer.kt:77-78` 注释与实现不符**：注释写「构造函数里已用 applicationContext 兜底」，
   实际 `class AppContainer(private val context: Context)` 直接用传入值。
   目前调用方是 `LiquidAgentApplication`（传的是 Application，安全），
   但注释会误导后来人。建议直接 `class AppContainer(context: Context) { private val app = context.applicationContext ... }`。
4. **安全**：`AndroidManifest` `android:allowBackup="true"`，
   而 `RemoteEndpoint.apiKey` 明文存在 `filesDir/endpoints/endpoints.json`
   （`EndpointRepository.kt:29`）。建议 `allowBackup="false"` 或加 `dataExtractionRules` 排除该文件。
5. **`TextToolProtocol.kt:277`** 每次 `strip` 都 `Regex("<tool_call>[\\s\\S]*?</tool_call>")` 新建正则；
   且 `:273` 的 `result.replace("```json", "")` 是**全文替换**，会误删正文里出现的字面量。
   建议把正则提到 `companion object`；替换改为基于 `extractBlocks` 的切片删除。
6. **`DeltaTracker`（`StreamAccumulator.kt:63-72`）**：当 `incoming == last`（后端重复回调同一帧）时，
   `startsWith` 为真但长度相等 ⇒ 走 else 分支 ⇒ **整段文本被重复追加一次**。
   建议加 `if (incoming == last) return ""`。
7. **`ChatAttachmentPicker.kt:488-491`** `displayName` 用 `uri.lastPathSegment`，
   SAF 下常得到 `image:12345`。建议走 `OpenableColumns.DISPLAY_NAME`
   （`ModelRepository.queryDisplayName` 里已有现成实现，可抽出共用）。
8. **`LiquidAgentApplication.onTerminate()`（`:30-33`）在真机不会被调用**，
   `container.close()`（关闭 native Engine）实际永不执行。
   建议改到 `Application.onTrimMemory(TRIM_MEMORY_COMPLETE)` 或
   `ProcessLifecycleOwner` 的 `ON_STOP` 里做兜底释放。
   另：`ChatToolCard` 的 `running = result == null`（`ChatMessageList.kt:412`）
   会让「工具调用已落库但结果未匹配上」的卡片永久显示「执行中…」，建议改为按 `callId` 精确匹配。

---

## 三、本次做对的 3 件事（值得沉淀）

### 1. 流式通道的设计是本项目最有价值的一处正确性决策

`LiteRtLmEngine.kt:187-257`：
- **显式 `Channel(UNLIMITED)` 而不是 `callbackFlow`** —— 注释里把理由写清楚了：
  `callbackFlow` 内部 channel 默认 `BUFFERED(64)`，缓冲满时 `trySend` 失败会**丢帧**，
  而 LLM 流式丢一个 token 就是丢一个字。这是真正读过 API 才会踩到的坑，被提前规避了。
- **`flow {}` 而非 `callbackFlow { awaitClose {} }`** —— 能同步拿到 `conv` 引用，
  从而可以在 `finally` 里**精确** `cancelProcess()`，保证「正常结束 / 取消 / 异常」
  三条路径都能停掉 NPU/GPU 推理，不留「烧电的僵尸推理」。
- **`flowOn(Dispatchers.IO.limitedParallelism(1))`** —— 把 LiteRT 的阻塞调用
  关在单一 IO 线程，回调线程只做 `trySend`。
- **异常不吞**：`onError` 用 `channel.close(EngineException(...))` 让错误沿流传播，
  而不是静默结束。

这四条里任何一条做错，都是「编译得过、跑起来随机丢字/发热/卡死」的级别。
**建议把这段注释原样沉淀进知识库，作为本项目「引擎桥接」的标准范式。**

### 2. 构建与依赖纪律做到了「开荒期不该有的干净程度」

- 全仓 **0 处 `!!`、0 处 `runBlocking`、0 处 `GlobalScope`**（已全量 grep 验证）；
- 硬约束 100% 遵守：**没有任何** KSP / Room / Hilt / Koin / Retrofit / Coil / 注解处理器 / JitPack；
  AGP 9 下**没有**显式应用 `org.jetbrains.kotlin.android`（只用 `kotlin.compose` + `kotlin.serialization`）；
- `settings.gradle.kts` 用 `FAIL_ON_PROJECT_REPOS` 从机制上杜绝模块级仓库；
- `app/build.gradle.kts` 的 release 签名在**四个 signing 属性缺失时优雅退回 debug 签名**，
  保证无密钥也能 `assembleRelease` —— 这是很懂 CI 的写法；
- 所有模块统一 `JvmTarget.JVM_17`、`compileSdk { release(36) }` 块式 DSL，无一处漂移。

### 3. 数据层的防御性与安全护栏底子扎实

- `JsonFileStore`（`core-data/.../JsonFileStore.kt`）的哲学是对的：
  **读失败一律返回 null 而不是抛异常** ——「一个坏掉的会话文件不该让整个 App 起不来」；
  写用 tmp + rename 保证原子性（虽实现有瑕疵，见 P1-9，但方向正确）。
- `SandboxedFileTool.resolveSafe`（`FileTools.kt:17-25`）用 **canonical path + 前缀比对**
  做路径逃逸检查，是所有文件类工具的基类 —— 护栏前置，而不是每个工具各写一遍。
- `ModelRepository.sanitizeFileName`（`:209-212`）过滤 `/ \ : * ? " < > |`，
  `ModelDownloader.sanitizeFileName`（`:156-162`）用白名单字符并拒绝 `.`/`..`，
  两处合起来把「模型导入路径穿越」堵死了（这是任务书点名要求核查的项，**结果是安全的**）。
- `ToolsViewModel` 提供工具独立试跑页面 —— 在没法写 instrumentation 测试的情况下，
  这是验证工具契约最经济的手段。

---

## 四、未覆盖 / 必须真机验证的部分

静态审查无法覆盖 LiteRT-LM 0.11.0 的**实际 API 行为**。以下全部属于
「代码写得像是对的，但只有在真机上跑一次才能确认」：

| # | 待验证项 | 位置 | 不确认会怎样 |
|---|---|---|---|
| 1 | `MessageCallback.onMessage(message)` 给的是**增量**还是**累积全文** | `LiteRtLmEngine.kt:206-215` | `DeltaTracker` 兜底是否命中；若两种都不是（比如 `toString()` 返回 `Message(...)` 调试串），输出直接乱码 |
| 2 | `message.channels["thought"]` 是否真是思考通道 | `:41 / :209` | 思考内容要么全丢、要么混进正文 |
| 3 | `sendMessageAsync(contents, callback, extraContext: Map<String, Any>)` 第三参签名 | `:246` | 编译已过（说明签名存在），但参数是否被接受、键值是否为 `enable_thinking` 需实测 |
| 4 | `ConversationConfig` 的 `systemInstruction` / `tools` / `initialMessages` 确切构造方式 | `:173-178` | 决定了 P0-2 是走「文本承载」还是可以走官方字段 |
| 5 | `Content.ImageBytes` 是否强制要求 PNG、`AudioBytes` 是否强制 16kHz mono WAV | `:266-269` | 格式不符时可能静默失败或 native 崩溃 |
| 6 | `Capabilities(modelPath).use { hasSpeculativeDecodingSupport() }` | `ModelCapabilityProbe.kt:219-226` | 已用 `runCatching` 兜底，最坏是探测恒 false |
| 7 | NPU 后端是否真的要求 `samplerConfig == null`，`nativeLibraryDir` 空串是否可接受 | `:141-146 / :161-171` | 若不接受空串，NPU 选项点了就崩 |
| 8 | GPU/OpenCL 后端在真实设备上的可用性与显存上限 | `:142-143` | 决定 4B 模型能否上 GPU |
| 9 | SSE 字段对三家真实后端的兼容：`reasoning_content` / `reasoning` / `tool_calls` delta / `usage` 位置 | `OpenAiCompatibleEngine.kt:196-245` | DeepSeek / vLLM / Ollama 各自可能字段名不同 |
| 10 | 4B 模型实际 tok/s、首字延迟、内存峰值；`largeHeap=true` 是否够 | 全局 | 决定是否需要加内存分级降级策略 |
| 11 | 折叠屏/平板旋转重建时，流式生成是否存活 | `MainActivity` 未声明 `configChanges`（有意为之） | 需确认重建后 UI 状态与引擎状态的一致性 |
| 12 | 进程被杀 / 来电中断后 native Engine 的释放 | `onTerminate` 不触发（P3-8） | 下次冷启动是否残留 |

**另外，本工程目前 0 个 `src/test` 与 `src/androidTest` 源集（已确认）。**
上面 P0 里有 3 个（工具结果回灌、多模态路径、事件双提交）都属于
**纯 Kotlin 逻辑、不依赖 Android 框架**，完全可以用 JVM 单测钉住。
建议下一轮优先补：

```
core-agent/src/test/java/.../AgentRunnerEventTest.kt      // MessageCommitted 与 Finished 互斥
core-agent/src/test/java/.../TextToolProtocolTest.kt      // 解析 / strip 的边界
core-engine/src/test/java/.../BuildContentsTest.kt        // 需要把 buildContents 提取为 internal + 注入 FakeReader
core-data/src/test/java/.../JsonFileStoreTest.kt          // 并发写 / 损坏文件兜底
```

---

## 五、建议的修复顺序

| 批次 | 内容 | 理由 |
|---|---|---|
| **第 1 批（必修，否则功能不可用）** | P0-1、P0-2、P0-3、P0-4 | 四个都会让「第一次真实使用」就得到错误结果 |
| **第 2 批（稳定性）** | P1-3（并发释放崩溃）、P1-4（压缩拆散配对）、P1-1、P1-2、P1-6 | 崩溃 / 400 / 数据错乱 |
| **第 3 批（质量）** | P1-5、P1-7、P1-8、P1-9、P2-1、P2-2 | 性能与正确性细节 |
| **第 4 批（清理）** | 其余 P2 + 全部 P3 | 不阻塞，可随迭代做 |

> 本人未修改任何源码、未执行任何 git 操作。以上均为建议，落地由主理人安排。


---

## 五、修复进展（主理人回填，2026-09-19）

审查结论为「需修改」。以下为逐条处置结果（✅ 已修 / ⏳ 未修并说明原因）：

| 编号 | 一句话 | 状态 |
|---|---|---|
| P0-1 | 远程 TOOL 消息 content 为空，工具结果从未回传 | ✅ `buildContent()` 对 TOOL 角色特判，取 `output ?: errorMessage` |
| P0-2 | 本地引擎只发最后一条 user 消息，系统提示词与工具结果全丢 | ✅ 改为按 `message.id` 做发送水印，增量装配 system/user/model/tool 全量上下文 |
| P0-3 | 回答被提交两次（双气泡 + 会话文件两份） | ✅ `MessageCommitted` 仅渲染并去重，落库统一由 `Finished` 负责 |
| P0-4 | `content://` 附件被当文件路径，多模态 100% 丢失 | ✅ 附件选中即落盘到内部目录，领域模型存真实文件路径 |
| P1-1 | 用户消息被塞进上下文两次 | ✅ `AgentRunner` 按 message id 去重后再追加 |
| P1-2 | 轮次耗尽把原始工具 JSON 当答案提交 | ✅ 回退到最后一轮可见文本并剥离工具协议片段 |
| P1-3 | 加载/卸载与在途推理无互斥，可能抽掉 native 引擎 | ✅ `close()` 前先 `cancelProcess()`；`unload()` / `load()` 前等待在途生成结束 |
| P1-4 | 上下文压缩拆散 tool_calls / tool 配对导致 400 | ⏳ 未修：需要重写 `ContextCompressor` 的裁剪边界（按「一轮 tool_calls + 其全部 tool 结果」为最小单元），改动面较大，留到真机联调时一并处理 |
| P1-5 | 文本协议对最终答案也解析，用户要 JSON 就死循环 | ⏳ 未修：需要引入「仅在 finishReason=TOOL_CALLS 时解析」的判定，与 P1-4 同一批改动 |
| P1-6 | 点「停止」后半截答案丢失 | ✅ `Cancelled` 事件已携带 `partialText`，`ChatViewModel` 会落库半截答案 |
| P1-7 | 工具在主线程做文件 IO 与剪贴板 | ✅ 工具执行统一切到 `Dispatchers.IO`（AgentRunner 与工具试跑页） |
| P1-8 | 远程图片原图直传 base64 → OOM / 413 | ✅ 统一降采样到最长边 1024px 再压 JPEG(85) |
| P1-9 | 原子写有名无实（tmp 名固定 + 非原子兜底） | ✅ 唯一 tmp 名 + `ATOMIC_MOVE`，删除「读全文再整写」的损坏路径 |

### 审查之外额外修的两处（自查发现）

1. **会话取消后不重建**：`cancelProcess()` 会留下半截 KV 状态，复用不报错但后续每轮静默变傻。
   现在只有 `onDone` 才算健康，其余路径（取消 / `onError` / `stop()`）下次生成前强制重建会话。
2. **`temperature=0` 语义**：LiteRT `SamplerConfig` 底层对 temperature 做除法，0 可能触发除零/NaN，已钳到 `≥0.01`。

### 工程纪律补强

- 新增 `scripts/arch-guard.sh` + CI 的 `Architecture guard` 步骤，用 grep 强制依赖方向
  （思路借鉴 Edge0：编译能过 ≠ 架构没被破坏）。
- 新增「加载前内存闸门」：可用内存不足时提前拦下，避免等几十秒后撞上 native 崩溃。
