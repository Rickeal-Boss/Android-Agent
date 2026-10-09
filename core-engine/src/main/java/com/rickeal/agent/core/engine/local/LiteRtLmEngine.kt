package com.rickeal.agent.core.engine.local

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation as LiteRtConversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.RepetitionPenaltyConfig
import com.google.ai.edge.litertlm.Role as NativeRole
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolCall as NativeToolCall
import com.google.ai.edge.litertlm.ToolProvider
import com.rickeal.agent.core.engine.EngineCapabilities
import com.rickeal.agent.core.engine.EngineException
import com.rickeal.agent.core.engine.EngineLoadConfig
import com.rickeal.agent.core.engine.EngineSessionDiagnostics
import com.rickeal.agent.core.engine.GenerationRequest
import com.rickeal.agent.core.engine.LlmEngine
import com.rickeal.agent.core.model.AgentLogStore
import com.rickeal.agent.core.model.Attachment
import com.rickeal.agent.core.model.ChatMessage
import com.rickeal.agent.core.model.DeltaTracker
import com.rickeal.agent.core.model.EngineKind
import com.rickeal.agent.core.model.FinishReason
import com.rickeal.agent.core.model.GenerationChunk
import com.rickeal.agent.core.model.InferenceBackend
import com.rickeal.agent.core.model.ModelDescriptor
import com.rickeal.agent.core.model.ModelModality
import com.rickeal.agent.core.model.newId
import com.rickeal.agent.core.model.Role
import com.rickeal.agent.core.model.SamplingParams
import com.rickeal.agent.core.model.ThinkingMode
import com.rickeal.agent.core.model.TokenEstimator
import com.rickeal.agent.core.model.TokenUsage
import com.rickeal.agent.core.model.ToolCallDelta
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 模型文件预检的体积下限（64MB）。
 *
 * 低于它视为「下载中断的残片」：内置最小的预设（450M 视觉模型）也有 ~0.25GB，
 * 而 DownloadManager 中断/被清理后常留下几 MB 的半截文件、或下载源返回的错误页
 * （几 KB~几十 KB）。取 64MB 既不会误伤任何真实模型，也能拦住绝大多数残片。
 */
private const val MODEL_MIN_BYTES: Long = 64L * 1024L * 1024L

/**
 * 中档回退（第三态）把系统提示词并进首条 USER 时，提示词块用的**开定界符**（复审 A4）。
 *
 * 存在理由：此前合并是 `systemText + 空行 + body` 的纯文本拼接。真机上若仍出现「模型
 * 复述提示词」，从输出上**无法区分**两种完全不同的成因 —— (a) preface 闸门没兜住的
 * 新形态，(b) 中档回退已正常生效、但小模型照旧复述。只能回头翻日志反推，而真机日志
 * 往往拿不到。加成对定界后，两者在输出里一眼可辨（复述内容里带不带这对标记）。
 *
 * ⚠️ 已知取舍（申报）：定界符本身会进模型，属于**提示词面变化**；极少数小模型可能把
 * `[系统设定]` 标记一起复述出来。仍然取它，是因为换来的可观测性更高 —— 这一步的
 * 归因目前只有「输出」与「日志」两个证据源，缺了定界就只能盲猜。
 *
 * ⚠️ 零回归边界：只有第三态命中（中档回退生效）时才走这条拼接，默认路径与 Wave 33
 * 逐字节一致。
 */
private const val SYSTEM_MERGE_OPEN = "[系统设定]"
/** [SYSTEM_MERGE_OPEN] 的成对闭定界符（存在理由与取舍见其 KDoc）。 */
private const val SYSTEM_MERGE_CLOSE = "[/系统设定]"

/**
 * 折叠 [contents] 中**相邻连续的 [Content.Text]** 为单个 `Content.Text`（`"\n\n"` 连接）；
 * 非 `Text` 的**一切子类**（`ImageBytes` / `ImageFile` / `AudioBytes` / `AudioFile` /
 * `ToolResponse`）**原样保留、相对顺序不变**。
 *
 * 动机（Wave 51 P1）：Qwen2.5 容器的 chat_template 用 `'…' + message.content + '…'` 拼接
 * content（模板 `:23` / `:27`）。content 为 **JSON 数组** 时 minijinja 报
 * `Failed to apply template: … tried to use + operator on unsupported types string and sequence`。
 * 推断（**H-A 工作假设，未离线证实**）：native 侧很可能只在 content 恰好 1 个元素时收敛为
 * string ⇒ 折成单段文本即可下发为 string。旁证：① 真实模板 + minijinja 实测「content 为
 * 数组必炸」；② Java 侧 `Message.toJson()` 恒下发数组；③ 真机 W50 数据显示「单结果回灌
 * 多数不炸 / 3 结果回灌确定性炸」。**决定性实证需真机 A/B（W51 §1.4）**。
 *
 * 🔴 反例纪律：**不得**把整个 List 压成文本 —— 那会把 ImageBytes/AudioBytes 一并变成字符串，
 * **打碎多模态**。只折叠**相邻 Text**，非 Text 是硬边界（遇到即 flush）。
 *
 * 分隔符取空行 `"\n\n"`：用空行分隔相邻工具输出，避免粘连破坏模型解析 —— 这是本函数
 * 自身的合理取舍，**不依赖任何外部「合批口径」**。（原文曾写「与 ContextCompressor 合批
 * 口径对齐」，Wave 52 核验该背书不成立：`ContextCompressor.sanitizeForProvider` 零处
 * `"\n\n"`，故删除假背书，保留分隔符本身。）
 *
 * ⚠️ 行为边界（如实申报，生产不可达）：空 `Text` 参与折叠时会照常写入分隔符 ⇒
 * `[Text("a"), Text("")]` → `[Text("a\n\n")]`（**尾随**空行）、`[Text("a"), Text(""),
 * Text("b")]` → `[Text("a\n\n\n\nb")]`（**双**分隔符）。本函数只保证**无前导**空行
 * （缓冲为空时不加分隔符）。生产路径不可达：`buildContents` / `toNativeMessage` 的附件与
 * 正文均经 `isNotBlank()` 过滤，空 `Text` 不会进入；若将来上游放开，需在此显式处理。
 *
 * ⚠️ 不覆盖范围（诚实申报）：只要消息含 `ImageBytes`/`AudioBytes` 就必然 ≥2 个元素 ⇒ 仍可能
 * 触发同一模板错误。Qwen2.5-1.5B 是纯文本模型，app 侧按 `supportsImages`/`supportsAudio` 本就
 * 不向它下发多模态，故该场景不在本波覆盖内。
 *
 * 纯函数（文件级 internal，可被同模块 JVM 单测直接调）：不构造引擎、不触 native。
 */
internal fun foldAdjacentText(contents: List<Content>): List<Content> {
    // 零分配短路：单元素 / 空列表原样返回**入参同一个实例** ⇒ 默认路径逐字节不变。
    if (contents.size < 2) return contents
    val out = ArrayList<Content>(contents.size)
    val buffer = StringBuilder()
    // 本段是否**出现过** Text（哪怕内容为空）—— 用来区分「从未有 Text」与「有 Text 但都是空串」。
    // 🔴 只要出现过，flush 时即使 buffer 为空也要产出 `Content.Text("")`
    //（否则 `[Text(""), Text("")]` 会折成空列表 ⇒ `Contents` 为空 ⇒ `Message.toJson` 不加
    //  `content` 键 ⇒ 语义漂移）。
    var sawText = false
    for (content in contents) {
        if (content is Content.Text) {
            // 缓冲区为空时不加分隔符（避免 `Text("")` 打头产生前导空行）。
            if (sawText && buffer.isNotEmpty()) buffer.append("\n\n")
            buffer.append(content.text)
            sawText = true
        } else {
            // 非 Text 是硬边界：先把本段相邻 Text flush 出去，再原样加入本元素。
            if (sawText) out.add(Content.Text(buffer.toString()))
            buffer.setLength(0)
            sawText = false
            out.add(content)
        }
    }
    if (sawText) out.add(Content.Text(buffer.toString()))
    return out
}

/**
 * 折叠前 content 的**元素类型摘要**（Wave 53 V-2）：按 [Content] 具体子类计数，供
 * 「多元素 content 下发」日志判断该批次能否被 [foldAdjacentText] 收口。
 *
 * 为什么需要它：单看「折叠前 N → 折叠后 M」无法区分两种形态 —— ① `M == 1`（纯 `Text`，
 * 已折成单段）；② `M == N ≥ 2`（含 `ImageBytes`/`AudioBytes`/`ToolResponse` 等
 * [foldAdjacentText] 的硬边界，未收口）。附上计数后二者在日志侧一眼可分。
 *
 * ⚠️ 只做**观测**、**不参与**折叠决策（边界语义见 [foldAdjacentText]）；且「折成 1 个 `Text`」
 * **不等于**下发为 string（`Contents.toJson()` 恒数组，Wave 53 L1/L2 定案，详见调用点注释）。
 *
 * 纯函数（文件级 internal，可被同模块 JVM 单测直接调）：不构造引擎、不触 native。
 */
internal fun summarizeContentTypes(contents: List<Content>): String {
    var text = 0
    var image = 0
    var audio = 0
    var tool = 0
    for (content in contents) {
        // `Content` 是 sealed（子类恰 6 个）⇒ 本 when 穷尽，无需 else（编译器亦提示 else 冗余）。
        when (content) {
            is Content.Text -> text++
            is Content.ImageBytes, is Content.ImageFile -> image++
            is Content.AudioBytes, is Content.AudioFile -> audio++
            is Content.ToolResponse -> tool++
        }
    }
    return "Text=$text/Image=$image/Audio=$audio/ToolResponse=$tool"
}

/**
 * LiteRT-LM 本地引擎。
 *
 * 桥接要点（架构文档 §3.4 说明 1~4）：
 *  1. 不用 callbackFlow —— 其内部 channel 容量只有 BUFFERED(64)，缓冲满时 trySend 会失败丢帧，
 *     LLM 流式丢一个 token 就是丢字。显式 Channel(UNLIMITED) 是唯一「绝不丢」的方案。
 *  2. 用 flow{} 而非 callbackFlow{awaitClose{}} —— 可以同步拿到 conv 引用，
 *     并在 finally 里精确 cancelProcess()，语义更直白。
 *  3. flowOn(engineDispatcher) —— ensureConversation / sendMessageAsync 是阻塞调用，
 *     整个 flow 体跑在单一 IO 线程；LiteRT 回调线程只做 trySend（线程安全）。
 *  4. ConversationConfig 的 systemInstruction / initialMessages **启用「角色通道」**
 *     （P0-A，2026-09-27）：系统提示词走 systemInstruction（native 侧 Message.system），
 *     历史按 role 播种进 initialMessages，增量只发 USER / TOOL。
 *
 *     为什么必须这么做（旧实现是 bug 的根）：`sendMessageAsync(Contents, …)` 内部恒为
 *     `Message.user(contents)`（litertlm 0.17.1 Conversation.kt），`Message.toJson()` 只
 *     序列化 `{role, content}` —— 旧实现把 SYSTEM/USER/MODEL/TOOL 全部压成一条无角色的
 *     user 纯文本发送，角色信息在进引擎前就丢了。小模型「续写」这段文本时先复述系统提示词
 *     再退化，即用户反复反馈的「只输出提示词然后胡言乱语」。
 *
 *     ⚠️ 回退：`createConversation` 失败（旧版 litertlm / 模型 chat template 不接受
 *     system 或 initialMessages）时自动回退 **legacy 配置**（三者传空 + roleChannelActive
 *     =false），系统提示词与历史重新走 [buildContents] 的文本压平路径 —— 代价是退回原
 *     bug，但**不会丢上下文**（比"两边都不发"安全）。该路径下工具仍走 Agent 层文本协议
 *     （tools 恒传空）。
 *
 *  5. 工具通道（Wave 34 题 A）：`ConversationConfig.tools` 注册工具 → 模型以原生
 *     tool_calls 回传 → 本引擎翻译成 `GenerationChunk.toolCallDelta` → 工具结果以
 *     `Message.tool(ToolResponse)` 回灌。启用后系统提示词里不再需要工具清单段（由
 *     Agent 层删除），「只输出提示词及工具调用语言然后胡言乱语」的回显面消失。
 *     未启用（三条件缺一，见 [nativeToolChannelActive]）时全程走 Agent 层文本协议，
 *     与本次改动前**逐字节一致**。
 *
 *     ⚠️ 红线：`ConversationConfig.automaticToolCalling` **默认 true**，本文件所有构造点
 *     都必须显式写 false —— 否则 native 会自行执行工具，绕过 AgentRunner 的审批 / 沙箱 /
 *     熔断管线（无报错、无日志）。
 */
class LiteRtLmEngine(
    private val engineDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) : LlmEngine {

    override val kind: EngineKind = EngineKind.LOCAL

    /** 在途生成数量。卸载/关闭引擎前必须等它归零，否则会从脚底下抽掉 native 对象。 */
    private val activeGenerations = java.util.concurrent.atomic.AtomicInteger(0)

    /** 上一条流是否被「非正常结束」（用户停止 / 取消 / onError）。 */
    @Volatile
    private var conversationDirty = false

    /**
     * 会话级**累计重建计数**：本引擎实例自加载以来，因「生成期模板渲染失败」
     *（[isTemplateRenderFailure]）而置会话重建的次数。**两条路径**——同步 `sendMessageAsync`
     * 的 catch / 异步 `onError` 回调——经 [handleTemplateRenderFailure] **统一 `++`**
     *（Wave 55：真机证实失败在 `sendMessageAsync` **同步抛出**、不经 `onError`，故此前
     * 只有 `onError` 一条入口时该计数对真实路径失效）。
     *
     * 为什么是**实例级**而非 run 级：语义是**会话级**（同一引擎实例 = 同一会话生命周期，
     * 随 [releaseInternal] 复位），不是单次 run 级；放 run 态会随 run 结束丢失累计。
     *
     * 用途：① 为**毒化测试判据**（关 fold 多发结果 ⇒ 必炸 / 开 fold ⇒ 不炸，直接钉「元素数」
     * 维度，见 W53 交接 §五 优先条 3）提供可观测的失败次数；② 为将来「重建限次软熔断」
     *（连续 N 次重建即停自愈，避免反复炸）预留数据面。
     *
     * ⚠️ 阈值**待真机 N 分布确定，勿现在拍** —— 本字段只做观测，不做判据（软熔断另立）。
     */
    @Volatile
    private var templateRebuildCount = 0

    /**
     * 投机解码能力的**真实探测结果**（null = 未探测/探测失败）。
     * 来源：官方 `Capabilities(modelPath).hasSpeculativeDecodingSupport()`。
     * 以此替代按文件名猜测，避免「能力位猜错」导致开了不支持的加速反而出错。
     */
    @Volatile
    private var probedSpeculativeDecoding: Boolean? = null

    /**
     * 原生工具通道的**真实探测结果**（null = 未探测，true/false = 探测通过/失败）。
     *
     * 探测方式（Wave 34 题 A）：注册一个哑工具建一次 Conversation，成功即视为当前模型 /
     * 转换件接受原生工具注册。为什么必须探针而不是按模型名猜：工具 schema 的解析发生在
     * `createConversation` **内部**（`ToolManager`），形状不被接受时整段抛错 —— 而这完全
     * 取决于转换件的 chat template，**无法离线验证**。猜错的代价是会话创建失败（连文本
     * 协议一起没了），所以探测结果必须真实。
     *
     * **按需 + 缓存**：只在「用户开关已打开且上层第一次问能力」时探（见 [probeNativeTools]
     * 的 KDoc），默认关闭时 load() 不做任何额外动作。结果绑定**这个引擎实例**，随
     * [releaseInternal] 复位为 null（换模型后重新给一次机会）。
     *
     * ⚠️ 时序：探测点在任何生成**之前**（`capabilities()` 返回前）—— 提示词内容由它决定，
     * 晚于它就纠正不了（Wave 33 的中档回退补不回工具清单段）。
     */
    @Volatile
    private var probedNativeTools: Boolean? = null

    /**
     * 原生工具通道**已在本引擎实例上被证伪**（注册真实工具导致会话创建失败）。
     *
     * 存在理由：探针只能验证「哑工具的形状被接受」，覆盖不到每个真实工具的 schema。真机上
     * 一旦某个工具被拒，若不证伪，`capabilities()` 会继续报 true —— 上层据此一直把提示词
     * 里的工具清单段删掉，而引擎又注册不上工具 ⇒ **工具能力永久消失**（进程重启才恢复）。
     * 置位后 [nativeToolChannelActive] 恒 false，上层下一轮自动把工具清单段写回提示词，
     * 整体自愈回已验证的文本协议路径。随引擎释放（[releaseInternal]）复位。
     */
    @Volatile
    private var nativeToolsRejected: Boolean = false

    /** 已发送消息的 id 水印（登记由 [freshMessages] 负责，见其 KDoc）。会话重建时必须清空。 */
    /**
     * 已送入 native Conversation 的消息 id 集合（增量发送水印）。
     *
     * 用 [ConcurrentHashMap.newKeySet] 而不是 `Collections.synchronizedSet(mutableSetOf())`：
     * 后者只保证**单个方法调用**原子，`add()` / `contains()` 之外的复合操作（以及外部
     * `for (id in sentMessageIds)` 迭代）仍需在外部加锁 —— 而这里的读写分散在
     * `Dispatchers.IO.limitedParallelism(1)` 的引擎线程与 `generateStream` 的调用方线程上，
     * 没有统一的外部锁。并发哈希集让迭代也是弱一致的、**不抛 ConcurrentModificationException**。
     */
    private val sentMessageIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private val mutex = Mutex()
    private var engine: Engine? = null
    private var conversation: LiteRtConversation? = null
    private var loadedModelPath: String? = null

    /**
     * 加载时锁定的 **KV cache 预算**（token 数）。
     *
     * Wave 28 语义修正（「只输出提示词然后胡言乱语」残留的第二条根因链）：litertlm 的
     * `EngineConfig.maxNumTokens` 是「输入+输出总和 = KV cache 总容量」，**不是输出上限**。
     * 旧实现把输出上限 `InferenceConfig.maxTokens`（默认 1024）传了进去 —— 系统提示词
     * （5 段 + 工具清单，中文按 ~1 token/字符即 1500+ token）单独就超出 1024 的 KV，
     * 首次 prefill 即触发 litertlm 硬报错（"Input token ids are too long"）或提前收尾。
     * 现改为传 `InferenceConfig.contextLength`（按模型采样档案钳制后 = 转换件 metadata
     * 的 max_num_tokens，预设内存闸门正是按 KV@4096 预算的）。
     *
     * 复用判据随之换轴：KV 预算（contextLength）变化 → 整引擎重建（native 按它分配
     * KV）；而 maxTokens 只是**逐消息输出上限**（走 `sendMessageAsync(maxOutputToken=)`），
     * 变更既不重建引擎也不重建会话 —— 用户在参数面板改输出上限立即生效。
     */
    private var loadedContextLength: Int = -1
    private var loadedBackend: InferenceBackend? = null
    /**
     * 加载时锁定的采样参数。
     *
     * `SamplerConfig` 是在 `ensureConversation()` 里按「会话创建那一刻」的
     * `request.config.sampling` 构造并随 Conversation 缓存的 —— 会话不重建，
     * 新的 temperature / topP / topK 永远进不了引擎。所以 load() 的复用判据必须带上它，
     * 否则用户反复调参、输出毫无变化且无任何报错。
     */
    private var loadedSampling: SamplingParams? = null
    private var loadedVisionBackend: InferenceBackend? = null
    private var loadedAudioBackend: InferenceBackend? = null
    private var currentConversationId: String? = null
    /**
     * 当前 Conversation 对应的应用侧上下文版本（[GenerationRequest.contextVersion]）。
     * 与 currentConversationId 同生命周期：会话创建时记录、重建判据参与比对、
     * releaseInternal 清零（外部审查报告2 §2：版本变化 = 必须重建 Conversation）。
     */
    private var currentContextVersion: Long = 0
    /**
     * 当前 Conversation 绑定的**系统提示词正文**（role=SYSTEM 消息的 text，null = 无）。
     *
     * P0-A（2026-09-27）：系统提示词此前被压进一条无角色 user 文本发送（当时由
     * `buildContents` 全权负责），模型看到的是一段"纯文本"，于是先复述提示词再退化。现改走
     * `ConversationConfig.systemInstruction`（native 侧 `Message.system`），本字段用于
     * **重建判据**：提示词变了必须重建会话，否则 native 侧还挂着旧的 system。
     * 与 currentConversationId / currentContextVersion 同生命周期，[releaseInternal] 清零。
     */
    private var currentSystemText: String? = null
    /**
     * 「角色通道」是否已**成功**启用（ConversationConfig 的 systemInstruction /
     * initialMessages 播种成功）。
     *
     * 只有它为 true 时，[freshMessages] 才会跳过 SYSTEM / MODEL（这两类已由
     * systemInstruction 与 initialMessages 承载），[buildContents] 因此也看不到它们。一旦创建失败回退 legacy 配置，本标记
     * **必须**为 false —— 否则系统提示词与 MODEL 轮会被"两边都不发"，模型直接失去系统
     * 提示词与历史，比原 bug 更糟。
     */
    private var roleChannelActive: Boolean = false
    /**
     * 中档回退挂起标记（Wave 33）：「角色通道第三态」命中后置 true —— 系统提示词
     * 改由 [buildContents] 前置拼进**本轮第一条未发过的 USER 消息**（未发过集合由
     * [freshMessages] 挑出）文本（只生效一次，拼完即复位）。roleChannelActive **保持 true**（MODEL 回灌门控必须保留，这是
     * 与 legacy 回退的本质区别）。复位点：生效后 / [releaseInternal] / 会话重建
     * （cid/version/systemText 变化、conversationDirty、采样参数变化）—— 重建后
     * 第三态判定会重新执行，不能带着旧标记进新会话。
     */
    private var systemMergedPending: Boolean = false

    /**
     * 当前会话里 native **刚刚下发过** tool_calls、正在等工具结果回灌（Wave 34 题 A）。
     *
     * 存在理由：工具结果回灌要发 `Message.tool`（native 侧要求 tool 消息带工具名且紧跟
     * tool_call），而**只有**「上一轮真的是 native 下发的 tool_call」时这个前提才成立。
     * 反例：通道激活但模型这轮走的是文本协议（Agent 层从正文里解析出的 JSON 工具调用）
     * —— 此时 native 侧根本没有配对的 tool_call，贸然发 `role=tool` 会让多数 chat
     * template 判非法。所以这里是「配对闸门」：没配过对就退回文本压平（即今日行为）。
     *
     * 生命周期（Wave 34 审查 P1-3）：**每轮都必须在两条出口之一复位** —— 真走了工具回灌
     * 时配完即清；走文本压平分支同样要清（本轮没发 `role=tool`，native 侧就没有在等的
     * tool_call）。只在其中一条路复位会留下 stale true，授权后面某一轮误发 `role=tool`。
     * 另外随会话重建 / [releaseInternal] 复位。
     */
    @Volatile
    private var awaitingNativeToolResponse: Boolean = false

    /**
     * 当前 Conversation **实际注册**的原生工具集签名（工具名按序拼接，null = 未注册工具）。
     *
     * 参与会话重建判据，见 `ensureConversation()` 内的同名局部变量说明：native 侧的工具
     * 注册是一次性的、没有增量更新入口，工具面变了只能重建会话。
     */
    private var registeredToolsSignature: String? = null

    /**
     * 会话诊断快照状态流（Wave 33）：会话建成 / 降级回退事件时整体发布。
     *
     * ⚠️ 字段语义（复审 A3）：快照**默认**是事件驱动的整体发布，但
     * [EngineSessionDiagnostics.systemMergedIntoUser] 是**运行态**而非出生时快照 ——
     * 中档回退的待合并标记在 [buildContents] 被真正消费时会**就地**把该字段改回 false
     * （`MutableStateFlow.update` 线程安全，此处在引擎 IO 线程调用）。不这么做的话，
     * 只要会话不重建，UI 小字就会一直显示「系统提示词已并入用户消息」，与引擎实际状态
     * 永久漂移。其余字段仍按「建成/降级事件」整体发布。
     */
    private val _sessionDiagnostics = MutableStateFlow<EngineSessionDiagnostics?>(null)
    override val sessionDiagnostics: StateFlow<EngineSessionDiagnostics?> =
        _sessionDiagnostics.asStateFlow()

    /**
     * 本次加载**实际生效**的后端（Wave 33）：GPU 降级成功后为 CPU。与
     * [loadedBackend]（恒记用户请求值，防误重建，by design）成对 —— 两者不等即
     * 「请求 GPU 实际 CPU」的运行时事实，经 [EngineSessionDiagnostics] 暴露给 UI。
     */
    @Volatile
    private var actualBackend: InferenceBackend? = null
    private var loadConfig: EngineLoadConfig? = null

    /**
     * 加载期 / 会话创建期因容器缺 section 而被去掉的模态（Wave 44 P0-2；Wave 45 起会话
     * 创建期亦可触发，空集 = 未降级）。
     *
     * **另立字段的理由（不许并进复用判据）**：[EngineAttempt.degraded] 是**运行时事实**，
     * 与 [loadedVisionBackend] / [loadedAudioBackend]（记**用户请求的解析值**，进 `sameEngine`
     * 判据）**正交**。若把降级写进 `loaded*Backend`，则「请求 GPU 实际去 audio」会在下次
     * `load()` 被判成配置变化而整引擎重建（重新加载权重，纯浪费；降级结果在进程生命周期内
     * 稳定）—— 与 `actualBackend` 分离 [loadedBackend] 是同一纪律。
     *
     * ⚠️ **必须加进 [releaseInternal] 复位**（唯一复位点纪律）：历史上漏抄字段清单出过事故
     * （`sentMessageIds` 漏复位 ⇒ 模型失忆）。
     */
    @Volatile
    private var degradedModality: Set<ModelModality> = emptySet()

    @Volatile
    private var loaded: Boolean = false

    override val isLoaded: Boolean
        get() = loaded

    override val isBusy: Boolean
        get() = activeGenerations.get() > 0

    // ---------------------------------------------------------------- load

    override suspend fun load(config: EngineLoadConfig) {
        // 换模型时若上一轮解码还在跑，直接换引擎会踩空 —— 先等它结束。
        // 等不到就**放弃本次 load**：往下走就是 native use-after-free，
        // 那是 SIGSEGV，runCatching 抓不住、日志也记不下来，整个进程直接没。
        if (!waitForGenerationsToFinish()) {
            throw EngineException("LiteRT-LM：上一次生成仍在继续，请稍候重试")
        }
        loadLocked(config, seedDegrade = emptySet(), forceRebuild = false)
    }

    /**
     * 加锁加载段（Wave 45）：[load] 与会话期模态降级重建 [reloadForDegrade] **共用**。
     *
     * **闸门由各调用方在入口施加**（本函数不重复 [waitForGenerationsToFinish]）：
     *  - [load]：闸门 → `loadLocked(∅, false)`；
     *  - [reloadForDegrade]：闸门 → `loadLocked(degradedModality + modality, true)`。
     *
     * 为什么共用而非另写一份（Wave 45 R1，规避「两份实现各自演化 ⇒ 静默失效」的历史坑）：
     * Engine 构造 + GPU 二段降级 + 诊断 + 日志是一整块，复制必然分叉。
     *
     * @param seedDegrade 会话期降级重建时**预置**的「已去模态」集合（load 路径恒 ∅）；
     *   作为 [EngineAttempt.degraded] 的种子，并据它把对应模态后端置 null。
     * @param forceRebuild 为 true 时**强制**整引擎重建（`sameEngine` 复用判据失效）——
     *   会话期降级重建必须换掉 native Engine（audio / vision 后端是 EngineConfig 级参数，
     *   不重建改不了）。
     */
    private suspend fun loadLocked(
        config: EngineLoadConfig,
        seedDegrade: Set<ModelModality>,
        forceRebuild: Boolean,
    ) {
        withContext(engineDispatcher) {
            mutex.withLock {
                val modelPath = config.model?.path
                if (modelPath.isNullOrBlank()) {
                    throw EngineException("LiteRT-LM: modelPath 为空")
                }
                // 复用条件必须带 loaded：只有「已经成功加载过、且参数没变」才允许短路复用。
                // 少了 loaded，一次失败的加载会留下 engine != null 的半死状态，下次 load()
                // 直接短路并把 loaded 置 true，上层就以为引擎可用 —— 实际底层是坏的，
                // 用户只能杀掉 App 才能重试。
                val wantsVision = config.model?.capabilities?.image == true
                val wantsAudio = config.model?.capabilities?.audio == true
                // 「引擎实际会拿到的后端」，而不是用户配置里的原始值。
                // 两者必须同源（`EngineConfig` 也用这两个值），否则判据与事实脱节：
                // 模型不支持视觉时原始配置可能是 null 也可能是用户随手设的 GPU，
                // 但引擎实际拿到的一定是 null —— 拿原始值去比会得出「没变」的错误结论。
                // 视觉后端**跟随主后端**（2026-09-26 真机实锤根修）：旧默认 GPU 是从
                // gallery 样例抄来的（Gemma 3n 要求 GPU 视觉），无差别套用后，主后端选
                // CPU 的设备视觉仍走 GPU —— 真机表现：CPU 模式 LLM executor 创建成功、
                // vision executor 的 CompiledModel::Create 失败（报错定位
                // vision_litert_compiled_model_executor.cc:273）。GPU 不可用的设备上
                // 这等于「CPU 模式也永远加载失败」。NPU 不支持视觉编码器（上游 vision
                // executor 对非 CPU/GPU 后端直接 InvalidArgument），强制落回 CPU。
                val resolvedVisionBackend = if (wantsVision) {
                    config.config.visionBackend ?: when (config.config.backend) {
                        InferenceBackend.NPU -> InferenceBackend.CPU
                        else -> config.config.backend
                    }
                } else {
                    null
                }
                val resolvedAudioBackend = if (wantsAudio) {
                    config.config.audioBackend ?: InferenceBackend.CPU
                } else {
                    null
                }
                // 复用判据**分两组，别混**：
                //  - sampling（temperature / topP / topK）：随 Conversation 一起固化，
                //    所以变了只需**重建会话**（重建 4B 引擎要几十秒，能省就省）；
                //  - visionBackend / audioBackend：是 **EngineConfig 级别**的参数，
                //    只在 `Engine(engineConfig)` 构造时传入，`createConversation()` 根本拿不到。
                //    把它们放进「重建会话」那一组是静默失效 —— 用户改了视觉后端，
                //    会话重建完了但引擎里的 visionBackend 还是旧的，改了等于没改
                //    （与 ENG-2 原本「调参不生效」是同一类症状）。所以它们变了必须**整机重建**。
                //
                // 比的是**解析后的值**，因此也自动覆盖了「模态从无到有」：
                // 用户在模型页把能力位 image 从 false 改成 true（onEditCapabilities / probe 补齐），
                // 解析值就从 null 变成 GPU —— 判据为 false，引擎重建，视觉后端才会真正存在。
                // 若改成拿原始配置比并加 `!wantsVision ||` 前缀，这条路径会判成「可复用」，
                // 于是模型被标成支持视觉、UI 允许发图，而底层 Engine 根本没有视觉后端 —— 静默失效。
                val sameEngine = !forceRebuild &&
                    loaded &&
                    engine != null &&
                    loadedModelPath == modelPath &&
                    loadedContextLength == config.config.contextLength &&
                    loadedBackend == config.config.backend &&
                    loadedVisionBackend == resolvedVisionBackend &&
                    loadedAudioBackend == resolvedAudioBackend
                if (sameEngine) {
                    loadConfig = config
                    // 采样参数是随 Conversation 一起固化的，只改这些参数**不必**重建引擎
                    // （重建 4B 引擎要几十秒），但必须重建会话，否则新参数永远不生效。
                    val samplingChanged = loadedSampling != config.config.sampling
                    if (samplingChanged) {
                        runCatching { conversation?.close() }
                        conversation = null
                        currentConversationId = null
                        currentContextVersion = 0
                        // 会话重建 = 上下文从零开始，水印必须一起清：
                        // 留着的话新会话会把整段历史当成「已发送」而不再重发 —— 模型直接失忆。
                        sentMessageIds.clear()
                        // 中档回退标记随会话作废（Wave 33，与其他重建点同一纪律）。
                        systemMergedPending = false
                    }
                    loadedSampling = config.config.sampling
                    return@withLock
                }
                releaseInternal()

                // ── 模型文件预检（2026-09-26）──────────────────────────────────────
                // 「initialize 失败」里最常见的一类真因是文件本身坏了/没了（DownloadManager
                // 中断留下的半截文件、被系统清理、下载源返回了 HTML 错误页），这类问题到
                // native 层才炸出来时用户完全读不懂。用 Kotlin 侧就能查的三件事先拦，
                // 把「引擎加载失败」换成可操作的文案：
                //  1、不存在 → 明说「重新下载」；
                //  2、体积 < [MODEL_MIN_BYTES] → 下载几乎必然中断（最小的预设也有 ~0.25GB）；
                //  3、按扩展名校验容器魔数（见下方 when 的 KDoc）→ 魔数不对 = 下到的不是模型。
                val modelFile = java.io.File(modelPath)
                // 文件名只取前 48 字符进文案（复审 E1b）：用户自命名/adb push 的文件
                // 名可能很长或含特殊字符，原样拼进错误提示会撑爆弹窗与诊断日志。
                val displayName = modelFile.name.take(48)
                if (!modelFile.exists()) {
                    throw EngineException(
                        "模型文件不存在：$displayName —— 可能已被系统清理，请在模型页重新下载"
                    )
                }
                if (modelFile.length() < MODEL_MIN_BYTES) {
                    throw EngineException(
                        "模型文件不完整（仅 ${modelFile.length() / (1024L * 1024L)}MB）—— " +
                            "下载很可能已中断，请删除后重新下载"
                    )
                }
                // 两种模型容器、两套魔数（2026-09-26 修正）：
                //  - .litertlm = LiteRT-LM **自研容器**：头部 8 字节 ASCII "LITERTLM" +
                //    版本 u32 + section 数 u32…（Range 请求实测 SmolVLM2-500M 与
                //    Qwen2.5-1.5B 两个官方直链的头部，均为 "LITERTLM" 开头，**不是 zip**）。
                //    ⚠️ 曾想当然按「zip（PK）」校验，把所有正常模型全部拦截 —— 真机
                //    「模型文件完全没问题却报格式异常」的根因，引以为戒：魔数必须实测。
                //  - .task = TFLite Task Library 模型，是真正的 zip 容器（PK）。
                val modelExt = modelFile.extension.lowercase()
                val expectedMagic: ByteArray? = when (modelExt) {
                    "litertlm" -> "LITERTLM".toByteArray(Charsets.US_ASCII)
                    "task" -> byteArrayOf('P'.code.toByte(), 'K'.code.toByte())
                    else -> null
                }
                if (expectedMagic != null) {
                    val magic = ByteArray(expectedMagic.size)
                    java.io.FileInputStream(modelFile).use { ins ->
                        val read = ins.read(magic)
                        if (read != expectedMagic.size || !magic.contentEquals(expectedMagic)) {
                            throw EngineException(
                                "模型文件格式异常（不是 .$modelExt 容器）—— " +
                                    "下载源可能返回了错误页，请换源后重新下载"
                            )
                        }
                    }
                }
                // 缓存目录必须真实存在：GPU 权重/编译缓存写不进去时，CompiledModel::Create
                // 会以同一种 INTERNAL 报错炸掉（vision executor 的 GetGpuModelCacheData /
                // SetGpuCacheOptions 就在编译前取缓存文件路径）。getExternalFilesDir 返回的
                // 目录通常已存在，但「存储未挂载时返回 null → 回退 cacheDir」的路径不保证。
                val effectiveCacheDir = (config.externalFilesDir ?: config.cacheDir)?.let { path ->
                    java.io.File(path).apply { runCatching { mkdirs() } }.absolutePath
                }

                // ── 创建引擎：错误驱动降级链（Wave 44 P0-2）──────────────────────────
                // 两条**正交、可叠加**的降级链，决策逻辑全在 [EngineLoadDegrade]（纯函数、
                // 可单测）；这里只做「建引擎 → 失败 → 问 next() → 建下一个」的驱动循环。
                //  1. 模态降级（只认 NOT_FOUND）：容器缺 VISION_ENCODER / AUDIO_ENCODER_HW
                //     子图时，去掉对应模态重建（先 AUDIO 后 VISION）。Wave 43 真机根因：
                //     Gemma-4 E2B 启发式 audio=true 但容器无 audio section ⇒ 旧实现直接失败。
                //     ⚠️ Wave 45 起模态降级**亦可发生于会话创建期**（NOT_FOUND 实际由
                //     createConversation 抛出）：见 ensureConversation 的 catch 与 reloadForDegrade。
                //  2. 后端降级（既有，2026-09-26）：Manifest 未声明 libOpenCL.so（Android 12+
                //     访问厂商非 NDK 库必须 <uses-native-library>）时 GPU 委托 dlopen 失败 →
                //     CompiledModel::Create 抛 INTERNAL（llm_litert_compiled_model_executor.cc:1928）。
                //     上游 issue #1860：SDK 无预检 API，调用方自己降级重试 CPU。
                // 复用判据仍记**用户请求的**解析值 —— 降级是运行时事实、不是新配置，否则
                // 「请求 GPU 实际 CPU」会在下次 load() 被判成配置变化而整引擎重建。
                //
                // GPU 文案门控（复审 P1-2）：只有用户请求真的涉及 GPU（主后端或视觉后端）
                // 才允许说「GPU 委托不可用」——与旧 `attempts.size > 1` 逐字等价。
                val gpuInvolved = EngineLoadDegrade.gpuInvolved(
                    config.config.backend,
                    resolvedVisionBackend,
                )
                val hadGpuAttempt = gpuInvolved

                var current = EngineLoadDegrade.initial(
                    backend = config.config.backend,
                    // 会话期降级重建（Wave 45）：seedDegrade 里的模态把对应后端置 null，
                    // 于是本轮 EngineConfig 不再带该模态 —— 这是「audio 真降得掉」的关键
                    // （EngineConfig 读的是 current.*，不是 resolved*）。
                    visionBackend = if (ModelModality.VISION in seedDegrade) null else resolvedVisionBackend,
                    audioBackend = if (ModelModality.AUDIO in seedDegrade) null else resolvedAudioBackend,
                    degraded = seedDegrade,
                )
                // visited 去重 + 上限 4（防循环）：任何重复状态不再入队。
                val visited = mutableSetOf(current)
                var attemptIndex = 0
                var lastError: Throwable? = null
                while (true) {
                    val engineConfig = EngineConfig(
                        modelPath = modelPath,
                        backend = toBackend(current.backend, config.nativeLibraryDir),
                        visionBackend = current.visionBackend?.let { toBackend(it, config.nativeLibraryDir) },
                        audioBackend = current.audioBackend?.let { toBackend(it, config.nativeLibraryDir) },
                        maxNumTokens = config.config.contextLength,
                        cacheDir = effectiveCacheDir,
                    )
                    try {
                        val created = Engine(engineConfig)
                        try {
                            created.initialize()
                        } catch (t: Throwable) {
                            runCatching { created.close() }
                            throw EngineException("LiteRT-LM: initialize 失败 (${t.message})", t)
                        }

                        engine = created
                        loadedModelPath = modelPath
                        // 真实能力探测：官方 API 直接读模型文件，比按文件名猜可靠得多。
                        // 失败不影响加载（getOrNull 回退到启发式）。
                        probedSpeculativeDecoding = runCatching {
                            Capabilities(modelPath).use { it.hasSpeculativeDecodingSupport() }
                        }.getOrNull()
                        // 原生工具通道探针不在 load() 里跑 —— 见 [probeNativeTools] 的 KDoc：
                        // 它只在**上层真的开了这个开关、且第一次问能力时**才探一次并缓存，
                        // 默认关闭时 load() 与 Wave 33 完全一致（零额外 Conversation）。
                        loadedContextLength = config.config.contextLength
                        loadedBackend = config.config.backend
                        // 实际生效后端（Wave 33）：attemptIndex==0 = 请求值原样生效；
                        // attemptIndex>0 = 至少降过一次，实际是 current.backend。
                        actualBackend = current.backend
                        // 降级事实另立出口（Wave 44 P0-2）：与 loaded*Backend（复用判据）正交。
                        degradedModality = current.degraded
                        loadedSampling = config.config.sampling
                        // 记**解析后的值**，与 sameEngine 判据同源；记原始配置会让
                        // 「能力位从 false 改 true」时两侧都是同一个原始值而误判为可复用。
                        loadedVisionBackend = resolvedVisionBackend
                        loadedAudioBackend = resolvedAudioBackend
                        loadConfig = config
                        loaded = true
                        if (attemptIndex > 0) {
                            val degradedLabel = if (current.degraded.isEmpty()) {
                                "无"
                            } else {
                                current.degraded.joinToString("/")
                            }
                            AgentLogStore.warn(
                                "LiteRT-LM 已以降级配置完成加载（请求后端：${config.config.backend}，" +
                                    "实际后端：${current.backend}，已去模态：$degradedLabel）"
                            )
                        }
                        // GPU 大上下文风险留档（Wave 33，log-only）：GPU 变体的 OpenCL
                        // buffer + 编译缓存与 KV cache 双占内存，contextLength 拉大后低端机
                        // 初始化失败风险显著升高。证据留档便于事后归因，不改任何行为。
                        // 判据用**实际生效**后端（复审 P2-1）：actualBackend 已在上方赋值，
                        // 请求 GPU 但降级成功跑 CPU 的场景下风险不存在，不应误报。
                        if (
                            actualBackend == InferenceBackend.GPU &&
                            config.config.contextLength > 4096
                        ) {
                            AgentLogStore.warn(
                                "GPU 后端上下文 ${config.config.contextLength} tok：" +
                                    "GPU 变体 OpenCL buffer+编译缓存与 KV 双占内存，" +
                                    "低端机初始化失败风险高（证据留档，不改行为）"
                            )
                        }
                        lastError = null
                        break
                    } catch (t: Throwable) {
                        // 任何失败路径都必须彻底复位（engine 置空 / loaded 置 false /
                        // 参数记忆与水印清空），否则下一次 load() 会拿残留状态误判为
                        // 「可复用」，引擎就永久卡在坏状态里。
                        releaseInternal()
                        lastError = t
                        val next = EngineLoadDegrade.next(current, t, gpuInvolved)
                        attemptIndex++
                        if (next == null ||
                            attemptIndex >= EngineLoadDegrade.MAX_LOAD_ATTEMPTS ||
                            !visited.add(next)
                        ) {
                            break
                        }
                        // 只做留档：区分「去模态」与「后端降级」两条链，便于真机归因。
                        if (next.degraded.size > current.degraded.size) {
                            val removed = (next.degraded - current.degraded).joinToString("/")
                            AgentLogStore.warn(
                                "LiteRT-LM 容器缺少 $removed 编码器 section" +
                                    "（NOT_FOUND：${t.message?.take(160) ?: "未知错误"}），自动去模态重试"
                            )
                        } else {
                            AgentLogStore.warn(
                                "LiteRT-LM GPU 后端不可用（${t.message?.take(160) ?: "未知错误"}），" +
                                    "自动降级 CPU 重试"
                            )
                        }
                        current = next
                    }
                }
                lastError?.let { t ->
                    // 可操作文案（Wave 33）：两段尝试都失败且错误消息命中 GPU 特征串时，
                    // 附加「驱动/OpenCL 缺失」的归因与下一步指引。纯字符串映射，不改行为。
                    // 门控（复审 P1-2）：只在真的做过 GPU 尝试时才拼 GPU 文案。
                    val gpuHint = if (
                        hadGpuAttempt &&
                        GPU_FAILURE_FEATURES.any { t.message.orEmpty().contains(it, ignoreCase = true) }
                    ) {
                        GPU_FAILURE_HINT
                    } else {
                        ""
                    }
                    if (t is EngineException) {
                        // initialize 失败包装路径：保留原文案结构，特征命中则补指引。
                        throw EngineException(t.message + gpuHint, t.cause)
                    }
                    throw EngineException("LiteRT-LM: 创建 Engine 失败 (${t.message})$gpuHint", t)
                }
            }
        }
    }

    private fun toBackend(backend: InferenceBackend, nativeLibraryDir: String?): Backend = when (backend) {
        InferenceBackend.CPU -> Backend.CPU()
        InferenceBackend.GPU -> Backend.GPU()
        // 简报 §3.1：NPU 需要 nativeLibraryDir，且 samplerConfig 必须为 null
        InferenceBackend.NPU -> Backend.NPU(nativeLibraryDir = nativeLibraryDir ?: "")
    }

    // -------------------------------------------------------- conversation

    /** ExperimentalApi：renderPrefaceIntoString 渲染诊断（Wave 28，仅日志用途，失败静默）。 */
    @OptIn(ExperimentalApi::class)
    private fun ensureConversation(request: GenerationRequest): LiteRtConversation {
        val currentEngine = engine
            ?: throw EngineException("LiteRT-LM: 引擎未加载，请先 load()")
        // P0-A：系统提示词改由 ConversationConfig.systemInstruction 承载（native Message.system），
        // 取第一条非空 SYSTEM 正文。
        val systemText = request.messages
            .firstOrNull { it.role == Role.SYSTEM }?.text?.takeIf { it.isNotBlank() }
        // 原生工具通道（Wave 34 题 A）：只有通道激活时才注册工具。文本协议模式恒传空 ——
        // 工具清单此时由系统提示词承载，注册了却不删提示词段等于双份。
        // ⚠️ 必须在重建判据**之前**算出来：工具集本身也是判据之一（见下）。
        // 原生工具通道是否本次生效（Wave 36 E4：把 provider 构造下沉到真正建会话的分支）。
        // ⚠️ 必须带 `&& request.tools.isNotEmpty()` 合取：开关开但上层未启用任何工具时
        // request.tools 为空，此时签名必须是 null 而不是空串 ""（否则 toolsChanged 每轮误判）。
        val nativeToolsActive = nativeToolChannelActive() && request.tools.isNotEmpty()
        // 已注册工具集的签名（null = 本次不注册任何工具）。工具是在 createConversation 时
        // 一次性注册进 native 的，**之后没有增量更新入口**（没有 removeTool 之类），所以
        // 工具面变了必须重建会话，否则模型拿到的仍是旧清单 —— 表现是「刚关掉的工具还在被
        // 调、刚打开的模型看不见」，且完全无报错。
        // Wave 36 E4：判据从 `nativeTools.isEmpty()` 换成 `!nativeToolsActive`（等价 —— 见
        // nativeToolsActive 处的注释；且不再需要在这里构造 provider）。
        val toolsSignature = if (!nativeToolsActive) null else request.tools.joinToString(",") { it.name }
        // 重建判据（外部审查报告2 §2 + Wave 34）：conversationId / contextVersion /
        // systemText / 工具集变化（[toolsChanged]）任一命中。
        // cid 变化 = 换了会话；contextVersion 变化 = 应用侧上下文发生了引擎无法增量表达的
        // 变化（典型：上下文压缩真的裁掉了历史）；systemText 变化 = 系统提示词改了，而它只在
        // 建会话时注入一次，不重建就永远不生效。
        //
        // ⚠️ **工具集判据必须带 `&& roleChannelActive` 门控**（审查 P0-1）：legacy 回退会话
        // 从未注册工具（registeredToolsSignature=null），而下一轮 nativeToolChannelActive()
        // 可能仍为真 ⇒ toolsSignature("a,b") ≠ null ⇒ 裸判据**每轮命中** ⇒ 每轮一次全量
        // re-prefill，且下一轮必然再次 legacy 回退 —— 稳态化的性能事故。带上 roleChannelActive
        // 后（roleChannelActive 是**上一轮会话的实际形态**，legacy 会话为 false）：
        //   开→关：重建（要摘掉已注册进 native 的工具）✅
        //   关→开：重建（要补注册）✅
        //   legacy 下：不再因为工具集重建 ✅
        //   legacy→通道可建：由 systemText / roleChannelActive 变化驱动，工具形态在建会话
        //   时按当时的 nativeTools 决定（此时 roleChannelActive=false ⇒ 仍不重建 ℹ️ 见下）
        // ℹ️ 唯一残留：从 legacy 会话切回可建 sessions 时若仅工具集变化，本判据不触发重建；
        //    但那要求 model/systemText 全不变而 role 通道又能建了 —— 不存在这条路径
        //    （roleChannelActive 从 false 翻 true 只可能发生在新建会话成功之后）。
        val toolsChanged = toolsSignature != registeredToolsSignature && roleChannelActive
        if (request.conversationId != currentConversationId ||
            request.contextVersion != currentContextVersion ||
            systemText != currentSystemText ||
            toolsChanged
        ) {
            // 可观测重建频率（核验建议）：重建 = 一次全量 re-prefill（4B 模型秒级开销），
            // 频率异常升高说明上层压缩/会话切换策略需要关注。
            AgentLogStore.info(
                "LiteRT-LM 会话重建：cid=${request.conversationId ?: "null"} v${request.contextVersion}" +
                    "（旧 cid=${currentConversationId ?: "null"} v$currentContextVersion）"
            )
            // 单独一行说明「这次重建是不是因为系统提示词变了」，便于与上下文压缩区分。
            if (systemText != currentSystemText) {
                AgentLogStore.info(
                    "LiteRT-LM 会话重建原因：系统提示词变化（旧 ${currentSystemText?.length ?: 0} 字 " +
                        "→ 新 ${systemText?.length ?: 0} 字）"
                )
            }
            // 同理：工具集变化引发的重建单独留痕（真机上「工具开关不生效」的唯一抓手）。
            // 判据必须与上面的重建条件**逐字同源**：否则会出现「日志说工具集变化、实际没
            // 重建」的口径分叉（legacy 会话下 toolsSignature ≠ null 是常态）。
            if (toolsChanged) {
                AgentLogStore.info(
                    "LiteRT-LM 会话重建原因：原生工具集变化（旧 ${registeredToolsSignature ?: "无"} " +
                        "→ 新 ${toolsSignature ?: "无"}）"
                )
            }
            runCatching { conversation?.close() }
            conversation = null
            // 换了会话/版本 = 换了 KV cache，水印必须一起清零，否则历史不会被重发 → 新会话丢上下文
            sentMessageIds.clear()
            // 中档回退标记随会话一起作废（Wave 33）：重建后第三态判定会重新执行，
            // 不能把旧会话的「待合并」状态带进新会话。
            systemMergedPending = false
        }
        // 关键：上一条流若被取消或出错（cancelProcess / onError），Conversation 可能停留在
        // 半截状态（prefill 完成一半、KV cache 状态不完整）。带着这种状态继续 sendMessageAsync
        // 不会报错，只会让后续每轮「静默变傻」—— 必须强制重建。
        if (conversationDirty) {
            runCatching { conversation?.close() }
            conversation = null
            conversationDirty = false
            // 同上：重建后的会话是空的，水印不清零会让「已发过」的历史永远不再发送
            sentMessageIds.clear()
            // 中档回退标记同步作废（Wave 33，理由同上：新会话重新走完整判定）。
            systemMergedPending = false
        }
        val existing = conversation
        if (existing != null) return existing

        // Wave 36 E4：provider 构造**下沉到这里** —— 上面 `existing != null` 的早退路径
        // 每轮都会走到（AgentRunner 主循环每轮 generateStream → ensureConversation），
        // 而会话早已建好时原来仍会白造一整批 ToolProvider 再丢弃。`toToolProviders()` 是纯
        // map（空入空出、无副作用），下沉后对下方所有消费点语义等价。
        val nativeTools: List<ToolProvider> =
            if (nativeToolsActive) request.tools.toToolProviders() else emptyList()

        val cfg = request.config
        val isNpu = cfg.backend == InferenceBackend.NPU
        // 简报：NPU 后端 samplerConfig 必须为 null
        val samplerConfig = if (isNpu) {
            null
        } else {
            SamplerConfig(
                topK = cfg.sampling.topK,
                topP = cfg.sampling.topP.toDouble(),
                // 与 OpenAI 语义一致用 temperature=0 表示贪心；LiteRT SamplerConfig 底层会做除法，
                // 0 可能触发除零/NaN，这里钳到一个极小值（等价于贪心）。
                temperature = cfg.sampling.temperature.toDouble().coerceAtLeast(0.01),
            )
        }
        // P0-A：历史按 role 播种进 initialMessages（native 侧按 role 组装 chat template），
        // 只剩最后一条留给 buildContents 作为本次 sendMessageAsync 的载荷。
        // ⚠️ 最后一条**不一定是 USER**：工具轮之后最后一条是 TOOL（AgentRunner 的工具结果
        // 回灌路径）。所以这里只按「末条留给发送、其余全部播种」处理，**不假设角色**。
        // 唯一例外见下方 tailIsModel：末条是 MODEL 时它会被角色门控拦下不发送，必须一起播种。
        val nonSystem = request.messages.filter { it.role != Role.SYSTEM }
        // ⚠️ 尾部 MODEL 的静默丢失（复审3 §2.4 P2）：`dropLast(1)` 把尾条排除在播种之外，
        // 而 buildContents 的角色门控（roleChannelActive 时跳过 MODEL）又不会发送它 ⇒ 该条
        // 回复**既不在 native 上下文里、也不在本次载荷里**，无声消失。它不抛异常、不报错，
        // 只是模型少看了一句自己的话 —— 完全不可观测。
        // 现状不可触发（AgentRunner 循环尾恒 TOOL，UI 层尾恒 USER），但任何「尾部是 MODEL」
        // 的新入口（后台批量续写 / 编辑后重生成 / subagent 复用会话）都会踩中。
        // 处置：尾部是 MODEL 时**全量播种**（含该条），本次载荷退化为空文本 —— native 侧
        // 已持有完整历史，空载荷语义与既有 `fresh.isEmpty()` 兜底一致。
        val tailIsModel = nonSystem.lastOrNull()?.role == Role.MODEL
        val seed = if (tailIsModel) nonSystem else nonSystem.dropLast(1)
        // 相邻 USER 合并（Wave 28，复审 P1-2）：空文本 MODEL（纯思考无正文/strip 后为空）
        // 被 toNativeMessage 过滤后，USER 与 TOOL 都映射成 Message.user，在 initialMessages
        // 里形成两条连续 user —— 部分 chat template 判非法 → createConversation 抛异常 →
        // 静默 legacy 回退（仅一条 warn），等于在长工具会话里复活原始 P0。合并后
        // initialMessages 恢复严格交替。水印登记不受影响（按 seed 的消息 id 登记，
        // 与合并后的 Message 对象一一对应无关）。
        val seedMessages = mergeAdjacentNativeUsers(seed.mapNotNull { it.toNativeMessage() })
        // 播种进 native 的历史必须**预登记进水印**：否则下一轮 buildContents 会把它们当成
        // 「未发过」再发一遍，native 侧出现重复历史。
        for (message in seed) sentMessageIds.add(message.id)

        if (nativeTools.isNotEmpty()) {
            // 真机抓手：工具 schema 的形状**无法离线验证**（取决于转换件的 chat template），
            // 这里把首个工具的完整 JSON 打出来 —— 真机「工具注册失败 / 模型不认工具」时
            // 是唯一定位点。会话建成时打一次，等价该会话首轮生成前。
            AgentLogStore.info(
                "原生工具通道：已注册 ${nativeTools.size} 个工具，首个 schema = " +
                    request.tools.firstOrNull()?.toOpenApiSchemaJson().orEmpty()
            )
        }

        // thought 通道输出解析声明（Wave 48 按模型选 def → Wave 49 R-A 数据驱动，见
        // [thoughtChannelDefsFor] KDoc）。**可空**：null = 不下发 channels、信任容器元数据。
        // 本会话内 4 个构造点（roleConfig / 第三态重建 / 不带工具重试 / legacy 回退）共用同一份，
        // 保证非空时 front() 恒为该模型自己的思考通道（W47 thinking 预算只认 front()）。
        val thoughtDefs = thoughtChannelDefsFor(request.model)
        val roleConfig = ConversationConfig(
            samplerConfig = samplerConfig,
            // ⚠️ systemInstruction 的类型是 **Contents?**（litertlm 0.17.1 起，旧版是 String?）
            // —— 必须包一层 Contents.of(...)。传裸 String 编译不过。
            systemInstruction = systemText?.let { Contents.of(it) },
            tools = nativeTools,
            initialMessages = seedMessages,
            // thought 通道输出解析声明（[thoughtChannelDefsFor] KDoc：Wave 43 真机实锤）。
            channels = thoughtDefs,
            // 红线：automaticToolCalling **默认 true** —— 一旦为 true，native 会自己去调
            // OpenApiTool.execute() 执行工具，完全绕过 AgentRunner 的审批/沙箱/熔断管线。
            // 这里必须**显式**写 false（漏写即静默绕过审批，无报错、无日志）。
            automaticToolCalling = false,
        )
        // legacy 回退原因 / 工具是否被重试路径丢弃（try 体内赋值、体外的诊断发布与签名登记
        // 读取 —— Kotlin 的 try 是表达式但 try 内局部量 catch 看不见，故声明提到 try 外）。
        var legacyFallbackReason: String? = null
        var toolsDroppedOnRetry = false
        val created = try {
            val conv = currentEngine.createConversation(roleConfig)
            roleChannelActive = true
            // Wave 43 真机验收关键字：thought 通道声明是否随会话下发（Gemma-4 思维链
            // 泄漏治理）。channel 切分是运行时行为，创建成功 ≠ 运行时一定切分
            // （元数据/模板差异），生效与否以生成期正文无泄漏为准。
            // ⚠️ 文案**动态打印实际下发的 start/end**（Wave 48）：否则 MiniCPM5 场景日志
            // 恒说「剥离 <|channel>thought」，与真实下发（<think>）自相矛盾、误导排查。
            // ⚠️ Wave 49 R-A：`thoughtDefs` 可空 ⇒ **两态**（`null` = 不下发、信任容器元数据）。
            // 这条日志是**验收关键字**（出现「未下发（信任容器元数据）」即证明走 null 分支），
            // 保留可观测性；改了文案要同步验收脚本。可空后不可直接 `.first()`（会 NPE）。
            if (thoughtDefs != null) {
                AgentLogStore.info(
                    "thought 通道解析声明已随会话下发（正文剥离 " +
                        "${thoughtDefs.first().start}…${thoughtDefs.first().end}，" +
                        "思维链入 channels[$THOUGHT_CHANNEL]）"
                )
            } else {
                AgentLogStore.info(
                    "thought 通道解析声明未下发（信任容器元数据；模型=" +
                        "${request.model?.fileName.orEmpty()}）"
                )
            }
            var thirdState = false
            // preface 渲染诊断（Wave 28，@OptIn ExperimentalApi）：preface = systemInstruction +
            // initialMessages 在 native chat template 下的**实际渲染结果**。若某转换件对
            // system role 渲染不当（createConversation 成功但渲染**丢失** —— 「角色通道
            // 静默忽略」第三态，不抛异常故回退门控抓不住），这里是唯一的代码侧观测点。
            // ⚠️ 口径收窄（Wave 39，与 EngineContract.EngineSessionDiagnostics 的 KDoc
            // 同读）：这里只证「**丢失**」，**证不了「错位」** —— 判据是归一化**子串**
            // 匹配（`normalizeForPrefaceCheck(preface).contains(window)`），它只对
            // 「窗口串有没有出现」敏感，对「出现在哪个位置 / 哪个 role 段」不敏感：
            // 系统提示词被渲染到用户消息之后、或被塞进别的 role 里，contains 依然 true。
            // 渲染失败（模型/版本不支持）静默跳过 —— 诊断绝不成为失败面。
            runCatching {
                val preface = conv.renderPrefaceIntoString()
                AgentLogStore.info(
                    "LiteRT-LM preface 渲染诊断：${preface.length} chars，" +
                        "开头「${preface.take(120).replace('\n', ' ')}」"
                )
                // 第三态闸门（Wave 33）：渲染成功但系统提示词正文没有出现在渲染结果里
                // → 模板丢了 system。渲染抛错时不做任何判定（维持 Wave 28 的静默语义）。
                if (systemText != null && !prefaceContainsSystem(preface, systemText)) {
                    thirdState = true
                }
            }
            if (thirdState) {
                // 中档回退（Wave 33，区别于 legacy）：关掉刚建的会话，以
                // systemInstruction = null 重建（initialMessages 播种与水印登记照旧），
                // roleChannelActive **保持 true** —— MODEL 回灌门控必须保留，系统提示词
                // 改由 buildContents 前置拼进首条未发过的 USER 消息（systemMergedPending）。
                AgentLogStore.warn(
                    "角色通道 preface 校验失败（模板渲染疑似丢失系统提示词），" +
                        "已降级为系统提示词并入首条用户消息"
                )
                runCatching { conv.close() }
                systemMergedPending = true
                currentEngine.createConversation(
                    ConversationConfig(
                        samplerConfig = samplerConfig,
                        systemInstruction = null,
                        tools = nativeTools,
                        initialMessages = seedMessages,
                        // thought 通道输出解析声明（同 roleConfig）。
                        channels = thoughtDefs,
                        // 红线：automaticToolCalling 默认 true，必须显式 false（同 roleConfig）。
                        automaticToolCalling = false,
                    )
                )
            } else {
                conv
            }
        } catch (t: Throwable) {
            // ① 模态降级（最高优先级，Wave 45）：确定性缺编码器子图（NOT_FOUND）⇒ 抛信号，
            //    由 flow 层（ensureConversationWithDegrade）去该模态重建后重试建会话。
            //    次序纪律（Wave 45 §4-3）：模态 → ② 工具重试 → ③ legacy 回退。
            //    NOT_FOUND 来自 audio/vision 子图，**与工具无关** —— 若先走「不带工具重试」会
            //    必然同样失败，且会永久置 nativeToolsRejected=true（一次假证伪）。
            val degrade = EngineLoadDegrade.modalityToDegradeOnSessionError(
                error = t,
                currentModalities = currentEngineModalities(),
                degradedModality = degradedModality,
            )
            if (degrade != null) throw ModalityDegradeNeeded(degrade)
            val reason = t.message?.take(160) ?: "未知错误"
            // 原生工具通道的**自愈**（Wave 34 题 A）：会话创建失败的原因可能**就是**注册工具
            // （schema 形状被这个转换件拒绝 —— 探针只能用哑工具验证形状，无法覆盖每个真实
            // 工具）。此时先「证伪本通道 + 不带工具重试一次」：
            //  - 保住 systemInstruction / initialMessages（不退回 legacy 文本压平，那等于
            //    复活 P0 的「提示词复述」）；
            //  - 证伪后 `nativeToolChannelActive()` 恒 false → capabilities() 立刻改报 false
            //    → 上层下一轮把工具清单段重新写回系统提示词，整体自愈回「文本协议 + 全量
            //    工具清单」这条已验证路径（而不是「工具能力永久消失」）。
            val retriedWithoutTools: LiteRtConversation? = if (nativeTools.isNotEmpty()) {
                nativeToolsRejected = true
                AgentLogStore.warn(
                    "原生工具通道：注册工具后会话创建失败（$reason），" +
                        "已证伪本通道并改为不注册工具重试"
                )
                runCatching {
                    currentEngine.createConversation(
                        ConversationConfig(
                            samplerConfig = samplerConfig,
                            systemInstruction = systemText?.let { Contents.of(it) },
                            tools = emptyList(),
                            initialMessages = seedMessages,
                            // thought 通道输出解析声明（同 roleConfig）。
                            channels = thoughtDefs,
                            // 红线：automaticToolCalling 默认 true，必须显式 false（同 roleConfig）。
                            automaticToolCalling = false,
                        )
                    )
                }.getOrNull()
            } else {
                null
            }
            if (retriedWithoutTools != null) {
                // 重试用的是**带 systemInstruction** 的配置，所以中档回退的「待合并」标记必须
                // 清掉：若在第三态重建那一步炸出来，该标记已置 true，带着它进新会话会让
                // 系统提示词双份（一次 systemInstruction、一次并入首条 USER）。
                systemMergedPending = false
                // 这次会话**没有**注册工具，签名必须记 null（见下方登记处）。
                toolsDroppedOnRetry = true
                // 角色通道保住了：水印（seed 已登记）与 roleChannelActive 与正常路径一致。
                roleChannelActive = true
                retriedWithoutTools
            } else {
                // 真机保命：角色通道播种失败（旧版 litertlm / 模型 chat template 不接受 system
                // 或 initialMessages）时，回退到 **legacy 纯文本配置**，让系统提示词与 MODEL 轮
                // 重新走 buildContents 的文本压平路径。
                // 不置 roleChannelActive=false 会导致两条路都不发（系统提示词 + 历史全丢），
                // 比原 bug 更糟；不回退则直接抛错，整个引擎不可用。
                // 中档回退的重建若也在此炸出，合并标记必须一起清（legacy 路径 SYSTEM 走
                // 文本压平，再合并就是双份）。
                systemMergedPending = false
                legacyFallbackReason = reason
                AgentLogStore.warn(
                    "LiteRT-LM 角色通道播种失败（systemInstruction/initialMessages），" +
                        "已回退 legacy 纯文本配置：$reason"
                )
                // 清掉刚登记的播种水印：legacy 路径必须靠 buildContents 把全量历史重新发一遍。
                sentMessageIds.clear()
                roleChannelActive = false
                // 走到 legacy 就不可能再注册工具 ⇒ 证伪本通道（理由见下方 ConversationConfig
                // 里的注释：不证伪 = 上层继续删提示词工具段、引擎却不注册工具 = 工具能力静默
                // 归零且零报错）。带 `!nativeToolsRejected` 是为了避免重复告警：若上面那条
                // 「不带工具重试」路径已经证伪过，这里就是同一次事故的第二次落点。
                if (nativeTools.isNotEmpty() && !nativeToolsRejected) {
                    nativeToolsRejected = true
                    AgentLogStore.warn(
                        "原生工具通道：本引擎已回退 legacy 且不注册工具，已证伪本通道" +
                            "（下一 run 起工具清单段会写回提示词，走回文本协议）"
                    )
                }
                currentEngine.createConversation(
                    ConversationConfig(
                        samplerConfig = samplerConfig,
                        systemInstruction = null,
                        // legacy 回退**不注册工具**：这条路的存在前提就是「模型/版本不接受
                        // 高级会话配置」，工具仍走 Agent 层文本协议（与回退前逐字节一致）。
                        //
                        // ⚠️ 既然这条路不注册工具，就必须把通道**证伪**（审查 P0-1）：
                        // 一旦走到这里而 nativeTools 非空，说明「探针通过」没能覆盖真实的
                        // roleConfig（探针只验哑工具 + systemInstruction=null +
                        // initialMessages=空，盖不住 systemInstruction/initialMessages
                        // 参与后的失败 —— Gemma 系模板无 system role 正是 Wave 24 legacy
                        // 回退的成因）。不证伪的后果是**工具能力静默归零且无报错**：
                        // capabilities() 继续报 true ⇒ AgentRunner 在 run 开头已按它把提示词
                        // 里的工具清单段删掉，而引擎侧根本没注册工具 ⇒ 模型看不见任何工具。
                        // 置位后 capabilities() 立刻改报 false ⇒ 下一个 run 自动把工具清单段
                        // 写回提示词，整体自愈回已验证的文本协议路径。
                        tools = emptyList(),
                        initialMessages = emptyList(),
                        // thought 通道输出解析声明（同 roleConfig；纯输出侧解析配置，
                        // 不参与模板渲染，legacy 路径下发无风险）。
                        channels = thoughtDefs,
                        // 红线：automaticToolCalling 默认 true，必须显式 false（同 roleConfig）。
                        automaticToolCalling = false,
                    )
                )
            }
        }
        conversation = created
        // ⚠️ 配对闸门**不能**无条件复位（收口复审 P1-2）：这次重建虽然换了 native 会话，
        // 但 `seedMessages` 里可能播种了一条**带 tool_calls 的 MODEL** —— 那 native 侧的
        // 状态就是「有一个 tool_call 在等结果」，闸门必须相应保持 true。
        // 若照旧无条件置 false，重建当轮的尾部 TOOL 结果就会走文本压平分支，最终形态是
        // `model(tool_calls) → user(文本)` —— 正是 `toNativeMessage()` 注释里判定「多数
        // chat template 判非法」的半套配对，而它命中的恰好是那条注释想救的
        // 「长工具会话 + 压缩/重建后全量重放」场景。
        // 更糟的是这条路径在 **Wave 51 之前不会自愈**：失败点在 `sendMessageAsync` 而不是
        // `createConversation`，彼时 `nativeToolsRejected` 不会被置位 ⇒ 每遇一次炸一次。
        // ⚠️ Wave 51 起该失败面已**部分**兜底：若失败表现为 `Failed to apply template`，
        // `onError` 会置 `conversationDirty` 并在原生工具通道下证伪本通道（见其注释）；
        // **非模板类**的 `sendMessageAsync` 失败仍不被该自愈覆盖。故闸门仍要按
        // **播种历史**（最后一条播种是不是带 tool_calls 的 MODEL）初始化，而不是按
        // 「会话是新的」假设重置。
        awaitingNativeToolResponse = nativeTools.isNotEmpty() &&
            seed.lastOrNull()?.let { it.role == Role.MODEL && it.toolCalls.isNotEmpty() } == true
        // 记录**实际注册进去**的工具集：legacy 回退（roleChannelActive=false）与「证伪重试」
        // 两条路都没注册工具，此时签名必须记 null —— 否则下一轮会因「签名一致」而不重建，
        // 工具永远注册不上。
        registeredToolsSignature = if (roleChannelActive && !toolsDroppedOnRetry) toolsSignature else null
        currentConversationId = request.conversationId
        currentContextVersion = request.contextVersion
        currentSystemText = systemText
        // KV 占用可观测（Wave 28）：「只输出提示词然后胡言乱语」残留的第二条根因链是
        // KV 超卖（旧实现把输出上限当 KV 容量）。这行日志让真机一眼可查「KV 预算多大、
        // 本轮 prompt 估算多大、角色通道是否激活」，并与超容 warn（generateStream 的
        // onError 文案映射）互为印证。
        run {
            val kvBudget = request.config.contextLength
            val estPrompt = TokenEstimator.estimate(request.messages)
            AgentLogStore.info(
                "LiteRT-LM 会话已建：cid=${request.conversationId ?: "null"} " +
                    "kv=$kvBudget tok role=${if (roleChannelActive) "on" else "legacy"} " +
                    "estPrompt=$estPrompt tok sys=${systemText?.length ?: 0} chars"
            )
            if (estPrompt > kvBudget * 85 / 100) {
                AgentLogStore.warn(
                    "上下文占用偏高：估算 $estPrompt / KV $kvBudget tok（>85%）—— " +
                        "生成可能失败或被截断，请精简会话/记忆或调大上下文长度"
                )
            }
        }
        // 会话诊断快照（Wave 33）：会话建成时整体发布，覆盖正常路径 / 中档回退 /
        // legacy 回退 / 原生通道证伪重试四种终态 —— 消费方（UI 小字）据此区分
        // 「一切正常不渲染」与「某类静默降级需要告知」。releaseInternal 复位为 null。
        _sessionDiagnostics.value = EngineSessionDiagnostics(
            roleChannelActive = roleChannelActive,
            legacyFallbackReason = legacyFallbackReason,
            systemMergedIntoUser = systemMergedPending,
            requestedBackend = loadedBackend,
            actualBackend = actualBackend,
            contextLength = loadedContextLength,
            // 用**实际登记**的工具集签名判定（legacy 回退与「证伪重试」两条路都没注册工具，
            // 而 nativeTools 此时仍非空 —— 拿它判断会报出「注册了但没注册」的假事实）。
            nativeToolChannel = registeredToolsSignature != null,
            // 加载期 / 会话创建期模态降级事实（Wave 44 P0-2；Wave 45 起会话创建期亦可触发）：
            // 与 requested/actualBackend 正交。
            degradedModality = degradedModality,
        )
        return created
    }

    // ---------------------------------------------- 会话期模态降级（Wave 45）

    /**
     * 会话创建遇「容器缺编码器子图」的内部信号（非终态失败，需去模态重建后重试）。
     *
     * 为什么用「抛信号 + flow 层重试」而非把 [ensureConversation] 改成 suspend 内部重建
     * （Wave 45 §4-2）：[ensureConversation] 在开头捕获 `currentEngine = engine` 局部引用后
     * mutate ~15 个字段，若在其内部 suspend 并重建，`currentEngine` 会变陈旧（重建后 engine
     * 是新对象）⇒ 极易踩 native use-after-free。信号方案让 [generateStream] 重建后**重新调用**
     * [ensureConversation]（拿到全新 `currentEngine`），规避该陷阱。
     *
     * 可见性 `private`：仅本文件内抛 / 捕（[ensureConversation] 抛、[ensureConversationWithDegrade]
     * 捕），不跨模块观测（Wave 45 裁决 §4）。
     */
    private class ModalityDegradeNeeded(val modality: ModelModality) :
        Exception("会话创建缺 $modality 编码器子图，需去模态重建后重试")

    /**
     * 当前 Engine **实际启用**的模态 = 用户请求解析值（`loaded*Backend` 非空）− 已降级模态。
     *
     * ⚠️ `loadedVisionBackend` / `loadedAudioBackend` 记的是**用户请求的解析值**（Wave 45 §4-6，
     * 有意错位：进 `sameEngine` 复用判据，防误重建），**不等于** Engine 实际启用的模态 ——
     * 所以必须再减去 [degradedModality]，才是「本次会话还能拿它去降的模态」。
     */
    private fun currentEngineModalities(): Set<ModelModality> = buildSet {
        if (loadedVisionBackend != null) add(ModelModality.VISION)
        if (loadedAudioBackend != null) add(ModelModality.AUDIO)
    } - degradedModality

    /**
     * 会话期模态降级重建：与 [load] 共用 [loadLocked]，差异 = `forceRebuild=true` + `seedDegrade`。
     *
     * 时序纪律（Wave 45 §4-5，**单点赋值**）：不在调用本函数**之前**写 [degradedModality] ——
     * [loadLocked] 内部先 [releaseInternal]（会把 degradedModality 清空，V19/L1917），成功后再
     * 由 L749 统一赋值 `degradedModality = current.degraded`。故「要去掉的模态」作为 `seedDegrade`
     * **传参**进 [loadLocked]，最终值 = `degradedModality + modality`。
     */
    private suspend fun reloadForDegrade(modality: ModelModality) {
        // 闸门（Wave 45 §4-1）：沿用既有安全契约 —— releaseInternal 会 engine?.close()，
        // 对**并发**在途生成是 native use-after-free（SIGSEGV，runCatching 抓不住）。
        if (!waitForGenerationsToFinish()) {
            throw EngineException("LiteRT-LM：有在途生成，模态降级重建被跳过，请稍候重试")
        }
        val config = loadConfig
            ?: throw EngineException("LiteRT-LM：会话期降级重建缺少 loadConfig")
        AgentLogStore.warn(
            "LiteRT-LM 会话创建遇容器缺 $modality 编码器 section（NOT_FOUND），" +
                "已去该模态重建引擎后重试（请求后端：${config.config.backend}）"
        )
        loadLocked(
            config = config,
            seedDegrade = degradedModality + modality,
            forceRebuild = true,
        )
    }

    /**
     * 建会话 + 会话期模态降级的有限重试（每模态一次，上限 [EngineLoadDegrade.MAX_SESSION_DEGRADE_ATTEMPTS]）。
     *
     * 🔴 **不得把 [activeGenerations] 的 `incrementAndGet()` 上提到本函数之前**（Wave 45 R12）：
     * 会话期重建发生在自增**之前** ⇒ 本生成尚未计数 ⇒ [reloadForDegrade] 的闸门读到 0、零等待
     * 返回。若把自增上提，[waitForGenerationsToFinish] 会**自等自**（等自己归零）⇒ 真死锁。
     */
    private suspend fun ensureConversationWithDegrade(request: GenerationRequest): LiteRtConversation {
        var attempts = 0
        while (true) {
            try {
                return ensureConversation(request)
            } catch (signal: ModalityDegradeNeeded) {
                attempts++
                if (attempts > EngineLoadDegrade.MAX_SESSION_DEGRADE_ATTEMPTS) throw signal
                reloadForDegrade(signal.modality)
            }
        }
    }

    // -------------------------------------------------------- generate

    override fun generateStream(request: GenerationRequest): Flow<GenerationChunk> = flow {
        // 会话期模态降级（Wave 45）：`NOT_FOUND: TF_LITE_AUDIO_ENCODER_HW` 由 createConversation
        // 抛出（非 load），故降级链必须挂在这里。🔴 本调用**先于**下方 activeGenerations
        // 自增 —— 不得调换顺序，否则重建闸门自等自死锁（见 ensureConversationWithDegrade KDoc）。
        val conv = ensureConversationWithDegrade(request)
        activeGenerations.incrementAndGet()
        val thinkingOn = when (request.config.thinking) {
            ThinkingMode.ON -> true
            ThinkingMode.OFF -> false
            ThinkingMode.AUTO -> request.model?.capabilities?.thinking == true
        }
        // thinking 开关的**显式**下发（Wave 48 1-B 修法）：absent ≠ off。
        // 上游 litert-lm `conversation.cc:241-244` 只在「extraContext 未显式含该键」时才用
        // `ThinkingConfig` 兜底写入 ⇒ 关思考若走「不发 enable_thinking」（absent），
        // MiniCPM5 **int4** 模板（默认思考开）会按默认自决继续思考，关不掉（真机 bbd8db82：
        // 关闭思考仍有思考区）。故 thinkingOn == false 时必须**显式**下发
        // `enable_thinking = false`（RM_MiniCPM5-2B.md:107「false switches to direct answers」）。
        // ⚠️ W47 曾**刻意**不写 false（「避开模板对 absent/false 处理不同的未知风险」）——
        // 现证据表明 absent 确实 ≠ off，该规避不再成立；本条影响**所有** thinking 模型的
        // 关思考路径，须真机逐模型回归（见 wave48 设计 §1.4 真机必验项）。
        val extraContext: Map<String, Any> = mapOf("enable_thinking" to thinkingOn)
        // thinking 独立 token 预算（Wave 47 项1）：上游 litertlm 0.17.1 的 `ThinkingConfig`
        // 是 native **硬约束** —— 到预算即强制吐出 thinking 结束符、转入正文，从根上消灭
        // 「thinking 烧光 maxTokens ⇒ 无可见输出 ⇒ 空转 ⇒ 撞墙钟熔断」（真机 222s 空转）。
        // `thinkingOn == false` 时恒 null ⇒ 不设预算；关思考的**显式 false** 已由上方
        // extraContext 下发（二者同值、互补不冲突，extraContext 优先）。
        // 预算**计入** maxOutputToken（thinking + 正文共享）⇒ 必须 < maxTokens，见
        // [resolveThinkingBudget]（纯函数，可 JVM 单测）。
        // ⚠️ `ThinkingConfig` 必须**全限定名 + 位置实参**：本文件已 import 协程
        // `Channel`；AAR 未带 `-java-parameters`（与 thoughtChannelDefsFor 同因，见其 KDoc）。
        val thinkingConfig = if (thinkingOn) {
            com.google.ai.edge.litertlm.ThinkingConfig(
                true,
                resolveThinkingBudget(request.config.thinkingTokenBudget, request.config.maxTokens),
            )
        } else {
            null
        }

        // UNLIMITED：LLM 流式决不能丢 token，宁可堆积内存（chunk 只有几十字节）
        val channel = Channel<GenerationChunk>(Channel.UNLIMITED)
        val textTracker = DeltaTracker()
        val thoughtTracker = DeltaTracker()
        val startNs = System.nanoTime()
        var finished = false
        var firstTokenNs = 0L
        // 内容 chunk 数（排除纯思考 chunk）—— 复审 U7（2026-09-26）：chunk 里含
        // thinkingDelta 时 tok/s 与 completionTokens 把推理输出也算进"生成"，
        // 通知栏指标虚高（DeepSeek-R1 类推理模型尤甚，思考可能占大半时长）。
        // 口径统一为**用户可见正文**：thinking chunk 不进这两个指标。
        var contentChunkCount = 0
        // 原生工具通道的**单次闩锁**：native 可能把同一批 tool_calls 分多帧下发，
        // 重复下发会让上层把一次调用执行两遍（工具是写操作时会真的做两遍）。
        var toolCallsEmitted = false
        // H-A 定案观测（Wave 53）：模板渲染失败时记录「本次下发消息的 role」，供真机 A/B 判别
        // 炸点属于 user/tool 哪条路径（离线 minijinja 复现与真机行为存在分歧，见 docs）。
        // ⚠️ 语义澄清（Wave53 深审 F-2）：此处的 "role" 是**内容来源语义**——`toolResponses`
        // 非空 → "tool"、否则 "user"——**非** native 侧 role；文本协议下 TOOL 回灌也走 `Message.user`
        // 而标 "tool" 为内容语义。两组对照（W50 3 元素炸 / W51-W52 折后 1 元素不炸）role 同为 user，
        // 真正能区分的维度是**元素数**（V-2 的 `summarizeContentTypes` 已记录），本观测仅作辅助。
        var outboundRoleForDiag = "unknown"
        val callback = object : MessageCallback {
            override fun onMessage(message: Message) {
                if (firstTokenNs == 0L) firstTokenNs = System.nanoTime()
                // 原生工具通道（Wave 34 题 A）：automaticToolCalling=false 时 native 把
                // tool_calls 经本回调回传。⚠️ 必须放在下方「空增量即 return」**之前** ——
                // Message.toString() 只拼 contents、不含 tool_calls，所以 tool_calls 帧的
                // 正文增量恒为空，会被那道 return 吃掉。
                if (nativeToolChannelActive() && !toolCallsEmitted && message.toolCalls.isNotEmpty()) {
                    toolCallsEmitted = true
                    // 配对闸门置位：下一轮的工具结果才有资格走 Message.tool 回灌。
                    awaitingNativeToolResponse = true
                    message.toolCalls.forEachIndexed { index, call ->
                        channel.trySend(
                            GenerationChunk(
                                toolCallDelta = ToolCallDelta(
                                    index = index,
                                    id = newId(),
                                    name = call.name,
                                    argumentsFragment = argsMapToJson(call.arguments),
                                )
                            )
                        )
                    }
                    AgentLogStore.info(
                        "原生工具通道：模型下发 ${message.toolCalls.size} 个 tool_call（" +
                            message.toolCalls.joinToString(",") { it.name } + "）"
                    )
                }
                val textDelta = textTracker.next(message.toString())
                val thoughtDelta = thoughtTracker.next(message.channels[THOUGHT_CHANNEL] ?: "")
                if (textDelta.isEmpty() && thoughtDelta.isEmpty()) return
                if (textDelta.isNotEmpty()) contentChunkCount++
                channel.trySend(
                    GenerationChunk(textDelta = textDelta, thinkingDelta = thoughtDelta)
                )
            }

            override fun onDone() {
                finished = true
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                // 首 token 延迟 = prefill 耗时（把 prompt 喂进 KV cache 的那一段）。
                val firstTokenLatencyMs = if (firstTokenNs == 0L) {
                    0L
                } else {
                    (firstTokenNs - startNs) / 1_000_000L
                }
                // tok/s 口径对齐官方 gallery：分母是**纯 decode 时间**（总耗时 − prefill），
                // 分子是 decode 阶段产出的 chunk 数。若拿总耗时当分母，prefill 会被算进 decode，
                // 首 token 延迟越长指标越难看，与官方数字也不可比（长 prompt 下差距可达数倍）。
                // decodeMs <= 0（首 token 与结束同一毫秒、或时间戳异常）时不做除法，直接给 0。
                val decodeMs = (elapsedMs - firstTokenLatencyMs).coerceAtLeast(0L)
                // tok/s 与 completionTokens 同口径：都是**内容 chunk**（复审 U7）。
                val tps = if (decodeMs > 0L) contentChunkCount * 1000f / decodeMs else 0f
                channel.trySend(
                    GenerationChunk(
                        finishReason = FinishReason.STOP,
                        usage = TokenUsage(
                            promptTokens = TokenEstimator.estimate(request.messages),
                            completionTokens = contentChunkCount,
                            totalTokens = TokenEstimator.estimate(request.messages) + contentChunkCount,
                            tokensPerSecond = tps,
                            firstTokenLatencyMillis = firstTokenLatencyMs,
                            // 与 tokensPerSecond 的分母保持同一口径：都是纯 decode 时长。
                            decodeMillis = decodeMs,
                        ),
                    )
                )
                channel.close()
            }

            override fun onError(throwable: Throwable) {
                val raw = throwable.message.orEmpty()
                // 超容硬报错的可行动化映射（Wave 28）：litertlm 对「输入超 KV 容量」报
                // "Input token ids are too long"（或 kMaxNumTokensReached 提前收尾），
                // 裸透传用户读不懂。映射成可操作指引；conversationDirty 由 finally 兜底
                // （下方 `!finished ⇒ conversationDirty = true`），本处显式补置为冗余声明（Wave 51）。
                val tooLong = raw.contains("too long", ignoreCase = true) ||
                    (raw.contains("max", ignoreCase = true) && raw.contains("token", ignoreCase = true))
                val hint = if (tooLong) {
                    " —— 上下文超出模型容量。请清空/精简当前会话，或调小「上下文长度」设置后重试"
                } else {
                    ""
                }
                // 生成期模板渲染失败的自愈（Wave 51 P1）：Qwen2.5 容器模板用
                // `'…' + message.content + '…'` 拼接 content，content 为 JSON 数组时必炸
                //（详见 [foldAdjacentText] 的 KDoc）。出口收口后仍可能命中（多模态 / 其它模型）。
                // ⚠️ Wave 55：真机证实模板失败实际在 `sendMessageAsync` **同步抛出**、**不经**
                // 本回调 ⇒ 本分支对真实路径不可达（仅为「native 在回调期才报」的兜底）。
                // 处置逻辑已抽到 [handleTemplateRenderFailure]，与同步 catch 共用一份、避免分叉。
                if (isTemplateRenderFailure(raw)) {
                    handleTemplateRenderFailure(raw, outboundRoleForDiag, source = "异步回调")
                }
                channel.close(EngineException("LiteRT-LM: 生成失败 (${raw})$hint", throwable))
            }
        }

        // 本轮「未发过」的消息。**必须先于 buildContents 取一次**：水印在这里标记，
        // 取完之后「未发过」集合即为空，工具结果回灌与文本压平两条路共用同一判据。
        val fresh = freshMessages(request)
        // 原生工具通道的**工具结果回灌**（Wave 34 题 A）：native 侧要求 tool 消息带工具名
        // 且紧跟 tool_call，而文本压平（Message.user + 纯文本）携带不了这两项 —— 长工具
        // 会话里会让模板判非法。本轮载荷全为 TOOL 结果时改发 Message.tool(ToolResponse)。
        val toolResponses: List<Content.ToolResponse> = if (
            nativeToolChannelActive() &&
            awaitingNativeToolResponse &&
            fresh.isNotEmpty() &&
            fresh.all { it.role == Role.TOOL }
        ) {
            fresh.flatMap { message ->
                message.toolResults.map { result ->
                    Content.ToolResponse(
                        result.name,
                        result.output.ifBlank { result.errorMessage ?: "" },
                    )
                }
            }
        } else {
            emptyList()
        }
        // 短路求值：有工具结果回灌时不走 buildContents（不消费中档回退的待合并标记 ——
        // 本轮载荷没有 USER，按既有语义它本就该顺延到下一个含 USER 的轮次）。
        val outbound: Message = if (toolResponses.isNotEmpty()) {
            // 一轮 tool_call 只配一轮 tool 结果，配完即复位。
            awaitingNativeToolResponse = false
            // 与 buildContents 的「顺延可观测」同一口径：走工具回灌时不经过 buildContents，
            // 待合并的系统提示词同样顺延（pending 不丢），这一点必须可见。
            if (systemMergedPending) {
                AgentLogStore.info("系统提示词合并顺延至下一用户消息轮（本轮为原生工具结果回灌）")
            }
            Message.tool(Contents.of(toolResponses))
        } else {
            // ⚠️ 文本压平分支**也必须复位配对闸门**（审查 P1-3）：本轮没发 `role=tool`，
            // 就意味着 native 侧此刻没有「等待回灌的 tool_call」。不复位的话闸门会 stale
            // 为真，授权后面某一轮贸然发 `role=tool`（典型：legacy 会话 + 通道仍激活），
            // 届时 native 侧无前置 tool_call ⇒ chat template 判非法。
            awaitingNativeToolResponse = false
            // 出口收口（Wave 51 P1）：把相邻 Text 折成单段。⚠️ **订正（Wave 53 L1/L2）**：
            // 折叠**不会**使 content「下发为 string」—— litertlm 0.17.1 的 `Contents.toJson()`
            // 恒返回 JSON 数组（`Contents.of(String)` 亦然；真机 litertlm 代码实测
            // `Contents.of("x").toJson()` = `[{"type":"text","text":"x"}]`），native 侧
            // `NormalizeContent` 亦原样保留数组。故本折叠**不能**断言已规避 Qwen2.5 模板
            // `'…' + content + '…'` 的 `+` 报错。**不得**据此反向断言 H-A「native 只在恰 1 元素时
            // 收敛为 string」**已证伪**：三层源码（Kotlin/JNI/C++）**均未找到** collapse 实现，且
            // C++/JNI 读的是仓库 tip 源码（非真机 0.17.1 prebuilt）、JNI 桥接层不在快照内 ⇒ 证据链
            // 不完整，「未找到」≠「不存在」。**真机证据相反**：W50 组A（3 元素）炸 vs W51/W52（折后
            // 1 元素）不炸，该对照只能用「1 元素被 collapse」解释 ⇒ H-A 作为经验规律**仍成立**；折叠是
            // 经真机复验的有效修复，**不得删除**（删则回归 W50 必炸）。冲突取舍：离线复现 vs 真机实证
            // 冲突时**以真机为准**。折叠本身只做「相邻 Text 合并」这一件事。
            val preFoldContents = buildContents(fresh)
            val foldedContents = foldAdjacentText(preFoldContents)
            // H-A 观测面（Wave 52 V-2；Wave 53 补元素类型摘要）：只报「折叠前 ≥2 元素」的实例
            // （单元素是绝大多数正常轮，不落日志以免刷屏）。附上元素类型计数，使「折叠后 =1（纯 Text）」
            // 与「折叠后仍 ≥2（含 ImageBytes/AudioBytes 等非 Text 硬边界）」在日志侧一眼可分。纯观测，不改行为。
            if (preFoldContents.size >= 2) {
                AgentLogStore.info(
                    "多元素 content 下发：折叠前 ${preFoldContents.size} → 折叠后 ${foldedContents.size}；" +
                        "元素类型 ${summarizeContentTypes(preFoldContents)}"
                )
            }
            Message.user(Contents.of(foldedContents))
        }
        outboundRoleForDiag = if (toolResponses.isNotEmpty()) "tool" else "user"
        // 重复惩罚（Wave 20，litertlm 0.17.1 起真实生效）：此前 SamplerConfig 无此参数、
        // SamplingParams.repetitionPenalty 只是「上层模拟或忽略」的死字段，0.17.1 把
        // RepetitionPenaltyConfig 开放为 sendMessage* 的逐消息参数 —— 这里是它在整条
        // 链路上唯一的生效点。默认 1.0 = 不惩罚（与旧版行为一致，零回归风险）；
        // >1.0（用户滑条或 ModelSamplingProfiles 按模型下限）才传。逐消息参数不进
        // Conversation 状态，改动不需要重建会话。NPU 后端保持与 samplerConfig=null
        // 同一道约束（简报 §3.1），不传。
        val repPenalty = request.config.sampling.repetitionPenalty
        val repetitionPenaltyConfig = if (
            repPenalty > 1.0f + 1e-3f &&
            request.config.backend != InferenceBackend.NPU
        ) {
            RepetitionPenaltyConfig(repetitionPenalty = repPenalty)
        } else {
            null
        }
        try {
            conv.sendMessageAsync(
                outbound,
                callback,
                extraContext = extraContext,
                repetitionPenaltyConfig = repetitionPenaltyConfig,
                // 输出上限逐消息生效（Wave 28）：KV 预算已由 EngineConfig.maxNumTokens =
                // contextLength 承载（输入+输出总和），maxTokens 在这里的语义回归本位 ——
                // 「单次生成的输出 token 上限」（含思考输出，litertlm 口径）。逐消息参数
                // 不进 Conversation 状态：用户改输出上限既不重建引擎也不重建会话，立即生效。
                // NPU 后端无此约束（约束的是 samplerConfig，见上），照常传递。
                maxOutputToken = request.config.maxTokens,
                // thinking 独立预算（Wave 47 项1）：非 null 时 native 到预算强制转正文
                // （`ThinkingBudgetConstraint`，0.17.1 AAR `.so` 字节级证实已编入）。
                // 与上方 `extraContext["enable_thinking"]` 互补不冲突（native 侧 `contains`
                // 守卫保证 extraContext 优先）：thinkingOn=true 时二者同值（true）；thinkingOn=false
                // 时本项为 null，关思考由 extraContext 的显式 `enable_thinking=false` 承担（Wave 48 1-B）。
                thinkingConfig = thinkingConfig,
            )
        } catch (t: Throwable) {
            // Wave 55：真机证实「生成期模板渲染失败」是在 `sendMessageAsync` **同步抛出**
            //（`Failed to start nativeSendMessageAsync: … Failed to apply template …`），
            // **不经**异步 `onError` 回调 ⇒ 原先只挂在 `onError` 的自愈（W51 置
            // `conversationDirty` / 证伪 `nativeToolsRejected`；W54 `templateRebuildCount`）
            // 对真实路径**失效**。这里补一道与 `onError` **等价**的处置（共用
            // [handleTemplateRenderFailure]，避免两份逻辑分叉）。
            // ⚠️ **必须 rethrow**：不吞异常 —— 失败仍由上层（`AgentRunner` 的引擎重建重试）
            // 承接；本 catch 只**新增**模板判据分支，非模板类失败原样上抛。
            val raw = t.message ?: ""
            if (isTemplateRenderFailure(raw)) {
                handleTemplateRenderFailure(raw, outboundRoleForDiag, source = "同步下发")
            }
            throw t
        }

        try {
            channel.consumeAsFlow().collect { chunk -> emit(chunk) }
        } finally {
            activeGenerations.decrementAndGet()
            // 只有 onDone 正常收尾才算“健康”；被取消 / 出错 / 被外部 stop 都要重建会话
            if (!finished) conversationDirty = true
            // 流结束（正常 / 取消 / 异常）都确保底层停止，避免 GPU 继续烧电
            runCatching { conv.cancelProcess() }
            runCatching { channel.cancel() }
        }
    }
        .flowOn(engineDispatcher)
        .cancellable()

    /**
     * 「生成期模板渲染失败」的**统一处置**（Wave 55）：置会话重建 + 累计计数 + 原生通道证伪 +
     * 对应 `warn`。抽成单一函数是为了让**两条路径共用一份逻辑**，避免行为分叉：
     * ① 同步 `conv.sendMessageAsync(...)` 的 catch（[source] = `同步下发`）——**真机实测的
     * 真实路径**（失败在 `sendMessageAsync` 同步抛出）；② 异步 `onError` 回调
     *（[source] = `异步回调`）——兜底「native 在回调期才报」的形态。
     *
     * 为什么只在 [nativeToolChannelActive] 为真时证伪通道：失败根因是 content 数组化、
     * **与通道无关**（文本协议下同样炸，W50 组A 实证）；对纯文本协议用户证伪通道只会带来
     * 无意义的「工具清单写回提示词」副作用。native 通道用户被降级后，native 路径特有的
     * `assistant + tool_calls` 的 `:27` 失败面也随之消失。防循环：`nativeToolsRejected` 置位后
     * [nativeToolChannelActive] 恒 false ⇒ 不会反复证伪；重建只在 dirty 置位后下一 run 发生一次。
     *
     * @param raw 失败原文（`Throwable.message`），用于判据与日志。
     * @param role 本次下发消息的内容来源语义（`tool` / `user`），仅用于日志；由调用点传入
     *  （`outboundRoleForDiag` 是 flow 内的局部量，故随参数传入而非读字段）。
     * @param source 失败来源标识（`同步下发` / `异步回调`），仅用于日志区分路径，不影响处置行为。
     */
    private fun handleTemplateRenderFailure(raw: String, role: String, source: String) {
        // 与 finally（`!finished ⇒ conversationDirty = true`）**冗余**：
        // 显式补置是为了让「模板失败 ⇒ 重建会话」这个意图在错误路径上可见，**勿删 finally 那条**。
        conversationDirty = true
        // 会话级累计重建计数（W54）：两条路径经本函数统一 `++`，供毒化测试判据 /
        // 将来重建限次软熔断观测（见字段 KDoc，阈值勿现在拍）。
        templateRebuildCount++
        if (nativeToolChannelActive()) {
            nativeToolsRejected = true
            AgentLogStore.warn(
                "原生工具通道：生成期模板渲染失败（$raw），已证伪本通道；下一 run 回退文本协议（下发 role=$role）（会话重建 #${templateRebuildCount}）（来源=$source）",
            )
        } else {
            AgentLogStore.warn(
                "生成期模板渲染失败（$raw），已置会话重建（下发 role=$role）（会话重建 #${templateRebuildCount}）（来源=$source）",
            )
        }
    }

    /**
     * 挑出本轮「还没发过」的消息，并**登记进水印**（用 message.id）。
     *
     * 提取成独立方法的理由（Wave 34 题 A）：原生工具通道的工具结果回灌需要先看一眼
     * 「本轮未发过的消息是不是全为 TOOL」，而水印一旦标记完就再也问不出来 —— 判据必须
     * 只有一份、且每轮只跑一次，否则两条路（文本压平 / 工具回灌）会各算一套「未发过」。
     */
    private fun freshMessages(request: GenerationRequest): List<ChatMessage> =
        request.messages.filter { message ->
            if (message.id in sentMessageIds) return@filter false
            // P0-A：角色通道生效时，SYSTEM 与 MODEL 已由 ConversationConfig 承载
            // （systemInstruction 与 initialMessages），不能在这里再发一遍 —— 否则 native
            // 侧重复注入；MODEL 若回灌成 user 文本，模型还会读自己的旧输出当用户输入。
            // 回退 legacy 时 roleChannelActive=false，这两类必须重新走文本压平（见 ensureConversation）。
            if (roleChannelActive && (message.role == Role.SYSTEM || message.role == Role.MODEL)) {
                return@filter false
            }
            sentMessageIds.add(message.id)
        }

    /**
     * 构造本次要发送给 LiteRT-LM 的内容（文本压平路径）。
     *
     * 关键点：**只发「还没发过」的消息**（用 message.id 做水印，由 [freshMessages] 挑出）。
     * Conversation 内部自带 KV cache 历史，若每轮都把全量历史重发，会出现重复；而若只发
     * 最后一条用户消息（最初的实现），系统提示词与工具执行结果就永远进不了上下文，
     * Agent 循环会退化成「单轮瞎猜」。
     *
     * ⚠️ 提示词面变化（复审 A4，申报）：中档回退（角色通道第三态）命中时，并入首条 USER
     * 的系统提示词是**带成对显式定界**的（`[系统设定] … [/系统设定]`）—— 进模型的内容
     * 变了，目的是让「闸门没兜住」与「回退生效但模型仍复述」在输出上可区分；已知取舍是
     * 小模型可能连定界符一起复述。详见 [SYSTEM_MERGE_OPEN] 的 KDoc。
     * 零回归边界：只有第三态命中才走这条拼接，默认路径与 Wave 33 逐字节一致。
     */
    private fun buildContents(fresh: List<ChatMessage>): List<Content> {
        if (fresh.isEmpty()) {
            // 边界（复审 P1-3）：尾部 MODEL 全量播种路径（本次载荷退化为空文本）。
            // 此时中档回退的合并标记**不消费也不丢**——顺延到下一个载荷含 USER 的轮次。
            return listOf(Content.Text(""))
        }

        // 顺延可观测（复审 P1-3）：本轮载荷全为 TOOL / 空时 USER 分支不会执行，
        // 待合并的系统提示词顺延到下一个 USER 轮 —— pending 不丢，但**本轮**生成的
        // 上下文暂缺系统提示词，必须留一行日志否则完全不可见。
        if (systemMergedPending && fresh.none { it.role == Role.USER }) {
            AgentLogStore.info("系统提示词合并顺延至下一用户消息轮（本轮载荷无 USER）")
        }

        val out = ArrayList<Content>(8)
        for (message in fresh) {
            when (message.role) {
                Role.SYSTEM -> if (message.text.isNotBlank()) {
                    out.add(Content.Text(message.text))
                }

                Role.USER -> {
                    // 简报 §3.1：图片/音频必须在文本之前，保证自回归 token 顺序正确
                    for (attachment in message.attachments) {
                        when (attachment) {
                            is Attachment.Image -> AttachmentBytesReader.imagePngBytes(attachment.uri)
                                ?.let { out.add(Content.ImageBytes(it)) }

                            is Attachment.Audio -> AttachmentBytesReader.audioBytes(attachment.uri)
                                ?.let { out.add(Content.AudioBytes(it)) }

                            is Attachment.Text -> if (attachment.text.isNotBlank()) {
                                out.add(Content.Text(attachment.text))
                            }

                            is Attachment.File -> Unit
                        }
                    }
                    // 中档回退消费点（Wave 33）：载荷中第一条未发过的 USER 消息前置拼入
                    // 系统提示词正文，拼完复位（只生效一次）。格式 = 定界块 + 空行 + 原文；
                    // 原文为空（纯附件消息）则只发定界块。定界块的形态与存在理由见
                    // [SYSTEM_MERGE_OPEN] 的 KDoc（可观测性，代价是提示词面变化）。
                    // ⚠️ 承诺如实化（复审 P1-3）：系统提示词并入的是下一个**载荷含 USER
                    // 的轮次**的首条 USER；本轮载荷全为 TOOL / 空载荷时顺延（pending 不丢，
                    // 但该轮上下文暂缺系统提示词，见上方顺延日志）。
                    // 其余 USER 消息与后续轮次不受影响；SYSTEM 消息仍由角色门控跳过
                    // （roleChannelActive=true 时已在 filter 处过滤，不会双份）。
                    // ⚠️ 已知次优（方案裁决接受）：若首条 USER 已被播种进 initialMessages
                    // （有历史时），合并落到下一轮 USER —— system 位置偏后但必进上下文。
                    val merged = systemMergedPending && currentSystemText != null
                    if (merged) {
                        val sysText = currentSystemText.orEmpty()
                        val block = SYSTEM_MERGE_OPEN + "\n" + sysText + "\n" + SYSTEM_MERGE_CLOSE
                        val body = message.text
                        out.add(Content.Text(if (body.isBlank()) block else block + "\n\n" + body))
                        // 消费即复位（两处，缺一不可）：
                        //  1. pending 复位 —— 只生效一次；
                        //  2. 诊断快照同步置 false（复审 A3）—— 快照若只在会话建成时发布，
                        //     合并消费后 UI 小字会一直显示「系统提示词已并入用户消息」，
                        //     与引擎实际状态永久漂移（只要会话不重建就再无校正机会）。
                        systemMergedPending = false
                        _sessionDiagnostics.update { it?.copy(systemMergedIntoUser = false) }
                        AgentLogStore.info(
                            "系统提示词合并已生效：首条 USER 前置拼接定界系统设定块" +
                                "（系统设定 ${sysText.length} 字，" +
                                "本轮载荷 ${fresh.size} 条 / 已发水印累计 ${sentMessageIds.size} 条）"
                        )
                    } else if (message.text.isNotBlank()) {
                        out.add(Content.Text(message.text))
                    }
                }

                Role.MODEL -> if (message.text.isNotBlank()) {
                    // 只回灌可见文本：工具调用的原始 JSON 由 Agent 层解析，不该污染上下文
                    out.add(Content.Text(message.text))
                }

                Role.TOOL -> {
                    // 必须遍历**全部**结果：`ContextCompressor.sanitizeForProvider()` 会把一批
                    // 工具结果合成**一条**含 N 个结果的 TOOL 消息。只取 firstOrNull() 的话，
                    // 压缩切掉一半后模型只看到第一个工具的输出，以为其余没执行 → 反复重试。
                    for (result in message.toolResults) {
                        val payload = result.output.takeIf { it.isNotBlank() }
                            ?: result.errorMessage
                            ?: ""
                        if (payload.isNotBlank()) out.add(Content.Text(payload))
                    }
                }
            }
        }
        if (out.isEmpty()) out.add(Content.Text(""))
        return out
    }

    /**
     * 原生工具通道下的 TOOL 载荷：一条 ChatMessage 的**全部**工具结果转成一个
     * `Message.tool`（`Content.ToolResponse` 列表）。
     *
     * 必须遍历**全部**结果（与 [buildContents] 的 TOOL 分支同一理由）：
     * `ContextCompressor.sanitizeForProvider()` 会把一批工具结果合成**一条**含 N 个结果的
     * TOOL 消息，只取第一个的话压缩切掉一半后模型以为其余没执行 → 反复重试。
     *
     * 结果内容取 `output`，为空回落 `errorMessage` —— 失败也必须告诉模型「这个工具报错了」，
     * 静默丢弃会让模型以为工具没被调用而无限重试同一条调用。
     *
     * 返回值可能为 null（无任何结果），调用方按需判空。
     */
    private fun ChatMessage.toNativeToolMessage(): Message? {
        val responses = toolResults.map { result ->
            Content.ToolResponse(
                result.name,
                result.output.ifBlank { result.errorMessage ?: "" },
            )
        }
        if (responses.isEmpty()) return null
        return Message.tool(Contents.of(responses))
    }

    /**
     * 把应用侧 [ChatMessage] 转成 native [Message]，用于 `ConversationConfig.initialMessages`
     * 的**按 role 播种**（P0-A）。
     *
     * 返回 null = 该消息不参与播种（SYSTEM 由 systemInstruction 承载；空文本 / 无内容的
     * USER / MODEL / TOOL 无意义）。
     *
     * ⚠️ 与 [buildContents] 的口径**刻意保持一致**（同一套附件顺序、同样的空值判断）：
     * 两条路径（角色通道 / legacy 回退）给模型喂的上下文必须等价，否则回退瞬间行为突变。
     *
     * ⚠️ TOOL 的形态随工具通道切换（Wave 34 题 A）：文本协议下压成 `Message.user` 文本
     * （工具调用是模型以正文 JSON 输出的、native 侧没有配对的 tool_call，此时塞
     * `role=tool` 会被多数 chat template 判非法）；**原生工具通道下必须**用 `Message.tool`
     * 与 MODEL 分支播种的 toolCalls 完成配对（详见该分支注释）。
     */
    private fun ChatMessage.toNativeMessage(): Message? = when (role) {
        // 系统提示词由 ConversationConfig.systemInstruction 承载，不重复播种成一条 message。
        Role.SYSTEM -> null

        Role.USER -> {
            // 简报 §3.1：图片/音频必须在文本之前（与 buildContents 的 USER 分支逐字同序）。
            val contents = ArrayList<Content>(4)
            for (attachment in attachments) {
                when (attachment) {
                    is Attachment.Image -> AttachmentBytesReader.imagePngBytes(attachment.uri)
                        ?.let { contents.add(Content.ImageBytes(it)) }

                    is Attachment.Audio -> AttachmentBytesReader.audioBytes(attachment.uri)
                        ?.let { contents.add(Content.AudioBytes(it)) }

                    is Attachment.Text -> if (attachment.text.isNotBlank()) {
                        contents.add(Content.Text(attachment.text))
                    }

                    is Attachment.File -> Unit
                }
            }
            if (text.isNotBlank()) contents.add(Content.Text(text))
            // 出口收口（Wave 51 P1）：附件文本与正文相邻时折成单段（非 Text 附件仍是硬边界）。
            if (contents.isEmpty()) null else Message.user(Contents.of(foldAdjacentText(contents)))
        }

        // 只发可见正文，不带 thinking（与 buildContents 的 MODEL 分支同口径）：
        // 工具调用的原始 JSON 由 Agent 层解析，不该污染上下文。
        // ⚠️ 原生工具通道：MODEL 轮**必须连 tool_calls 一起播种**（Wave 34 题 A）。
        // 会话重建（压缩 / 提示词变化 / dirty）会把整段历史重放进 initialMessages，若这里
        // 丢掉 toolCalls，native 侧就出现「tool 消息没有前置 tool_call」—— 多数 chat
        // template 判为非法，表现是长工具会话 + 压缩必炸。
        Role.MODEL -> {
            val nativeCalls: List<NativeToolCall> = if (nativeToolChannelActive()) {
                toolCalls.map { NativeToolCall(it.name, jsonToArgsMap(it.argumentsJson)) }
            } else {
                emptyList()
            }
            val visibleText = text.takeIf { it.isNotBlank() }
            when {
                visibleText != null -> Message.model(
                    Contents.of(visibleText),
                    nativeCalls,
                    emptyMap(),
                )
                // 纯工具轮（正文为空）：也要把 tool_calls 播种回去，否则配对断裂。
                nativeCalls.isNotEmpty() -> Message.model(
                    Contents.of(emptyList<Content>()),
                    nativeCalls,
                    emptyMap(),
                )
                else -> null
            }
        }

        Role.TOOL -> {
            // 原生工具通道（Wave 34 题 A，审查 P1-2）：MODEL 分支已把 toolCalls 一起播种，
            // native 侧**有一条配对的 tool_call**，所以这里必须用 `Message.tool` 完成配对。
            // 若继续压成 `Message.user` 文本，历史形态就是 `model(tool_calls) → user(文本)`
            // —— 正是「多数 chat template 判非法」的那一种，恰好命中本分支想救的
            // 「长工具会话 + 压缩/重建后全量重放」场景。
            //
            // 取舍申报（已知风险边界）：若某个转换件不接受 `Message.tool` 形态，
            // createConversation 会失败 —— 这条错误路径已被覆盖：先证伪本通道并不带工具
            // 重试，再失败才 legacy 兜底（含失败原因日志）。半套配对是必炸的；生成期失败的自愈
            // 见 onError（Wave 51 起），**在此之前不会自愈**。
            if (nativeToolChannelActive()) {
                toNativeToolMessage()
            } else {
                // 文本协议：工具调用是模型以正文 JSON 输出的（Agent 层解析后已从 MODEL 文本
                // 剥掉），native 侧没有配对的 tool_call ⇒ 只能以 user 文本回灌。
                // 遍历**全部**结果：`ContextCompressor.sanitizeForProvider()` 会把一批工具
                // 结果合成一条含 N 个结果的 TOOL 消息（与 buildContents 的 TOOL 分支同理由）。
                val texts = toolResults.mapNotNull { result ->
                    (result.output.takeIf { it.isNotBlank() } ?: result.errorMessage ?: "")
                        .takeIf { it.isNotBlank() }
                }
                // 出口收口（Wave 51 P1）：多结果文本本会成 JSON 数组（多元素）⇒ 折成单段。
                if (texts.isEmpty()) null else Message.user(Contents.of(foldAdjacentText(texts.map { Content.Text(it) })))
            }
        }
    }

    // -------------------------------------------------------- misc

    /**
     * 合并相邻的同角色 USER native 消息（Contents 拼接）。
     *
     * 只处理 USER：MODEL 相邻在部分模板下同样非法，但应用侧 MODEL 轮之间恒有
     * TOOL/USER 隔开（AgentRunner 循环不变量），无需处理。
     *
     * ⚠️ TOOL 的形态随通道切换（Wave 34 题 A）：文本协议下映射成 `Message.user`，因此会
     * 参与这里的相邻合并；**原生工具通道下**是 `Message.tool`（要跟 MODEL 轮播种的
     * tool_calls 配对），不参与合并 —— 两者都不破坏「不出现连续同角色」的目标。
     */
    private fun mergeAdjacentNativeUsers(messages: List<Message>): List<Message> {
        if (messages.size < 2) return messages
        val out = ArrayList<Message>(messages.size)
        for (message in messages) {
            val last = out.lastOrNull()
            if (last != null && last.role == NativeRole.USER && message.role == NativeRole.USER) {
                // 出口收口（Wave 51 P1）：拼接后的 USER 内容里相邻 Text 折成单段（多元素数组会炸模板）。
                out[out.size - 1] = Message.user(
                    Contents.of(foldAdjacentText(last.contents.contents + message.contents.contents))
                )
            } else {
                out.add(message)
            }
        }
        return out
    }

    /**
     * 「原生工具通道」本次是否**实际激活**（引擎侧唯一判据）。
     *
     * 四条件：`未被证伪` ∧ `探针通过` ∧ `用户开关打开` ∧ `模型能力位 toolCalling`。
     *
     * ⚠️ 与 `capabilities()` 报给上层的 `nativeToolChannel` **必须逐字同源**（所以
     * capabilities() 直接调本函数）：上层按它决定「要不要把工具传进 GenerationRequest、
     * 要不要从系统提示词里删掉工具清单段」。两侧判据一旦分叉，就会出现「上层按原生通道
     * 删了提示词工具段、引擎却按文本协议不注册工具」的空窗 —— 工具能力整体消失且无报错。
     */
    private fun nativeToolChannelActive(): Boolean =
        !nativeToolsRejected &&
            probedNativeTools == true &&
            loadConfig?.config?.nativeToolChannel == true &&
            loadConfig?.model?.capabilities?.toolCalling == true

    /**
     * 跑一次原生工具通道探针（哑工具 + 一次性会话），结果写进 [probedNativeTools]。
     *
     * ## 为什么必须真实探针，不能按模型名猜
     *
     * 工具 schema 的解析发生在 `createConversation` **内部**（`ToolManager`），形状不被接受时
     * 整段抛错，而这完全取决于转换件的 chat template，**无法离线验证**。猜错的代价是会话
     * 创建失败（连文本协议一起没了），所以结论必须来自一次真跑。
     *
     * ## 为什么不放进 load() 无条件跑（审查 P1-1）
     *
     * 探针要额外建 + 关一个 native Conversation（一次 KV 分配）。本功能默认关闭，无条件跑
     * 等于给**默认路径**白加一份开销，违反「默认路径与 Wave 33 逐字节一致」。
     *
     * ## 为什么改成「懒探测」而不是「load() 里加开关门控」
     *
     * 只在 load() 里看开关是不够的：`sameEngine` 判据不含本开关，用户**中途**打开开关时
     * load() 直接短路返回（`return@withLock`），探针永远跑不到 ⇒ 开关变成死开关（只能靠
     * 改上下文长度/后端/模型或重启 App 才生效）。改成在上层第一次问能力时按需探一次并
     * 缓存后：默认关闭仍然零开销；打开后**下一次问能力即生效**，不需要昂贵引擎重建。
     *
     * ## 开关门控的**净效果**（审查 P1-1 的验收口径）
     *
     * 唯一调用点在 `capabilities()` 内、且带 `loadConfig?.config?.nativeToolChannel == true`
     * 门控 ⇒ 开关默认 false 时探针**一次都不跑**：`probedNativeTools` 保持 null，
     * [nativeToolChannelActive] 恒 false（通道自然不通），load() 与 Wave 33 相比**零成本**
     * （零额外 Conversation、零额外 KV 分配）。开关打开后最多探一次，结果按引擎实例缓存。
     *
     * ## 时序承诺（仍然严格）
     *
     * 探针跑在 `capabilities()` 内**返回之前** ⇒ 上层拿到的能力位一定已含真实探针结论，
     * 由它决定的「提示词里是否保留工具清单段」在任何生成之前就已确定 —— 与「探针必须在
     * 任何生成之前」的原约束等价。
     *
     * ## ⚠️ 必须传入 model（Wave 48）
     *
     * 通道 def 自 Wave 48 起**按模型选择**（[thoughtChannelDefsFor]）。探针内没有 `request`，
     * 故 model 由调用方（`capabilities()` 的 `loadConfig?.model`，即实际会话所用模型）**显式传入**
     * —— 让探针与生产构造点下发**同一份**通道 def，维持「探针盖住生产构造面」的纪律（若探针
     * 自行读别的模型，探针通过 ≠ 生产可用）。
     */
    private fun probeNativeTools(engine: Engine, model: ModelDescriptor?) {
        probedNativeTools = runCatching {
            val probeConversation = engine.createConversation(
                ConversationConfig(
                    systemInstruction = null,
                    tools = nativeToolProbeProviders(),
                    // thought 通道声明随探针同口径下发（与生产构造点同一份 def）：探针通过即证明
                    // 「channels 配置 + 哑工具」的组合形状被本转换件接受（channels 是纯输出侧解析
                    // 配置，不进模板，风险远低于 tools，但保持「探针盖住生产构造面」的纪律）。
                    channels = thoughtChannelDefsFor(model),
                    // 红线：automaticToolCalling 默认 true，必须显式 false
                    // （探针虽不会真调用，但保持与生产构造点同一口径）。
                    automaticToolCalling = false,
                )
            )
            runCatching { probeConversation.close() }
            true
        }.getOrElse { t ->
            AgentLogStore.warn(
                "原生工具通道：探针失败，退回文本协议（${t.message?.take(160) ?: "未知错误"}）"
            )
            false
        }
        if (probedNativeTools == true) {
            AgentLogStore.info("原生工具通道：探针通过（模型/转换件接受原生工具注册）")
        }
    }

    override suspend fun capabilities(): EngineCapabilities {
        return withContext(engineDispatcher) {
            val model = loadConfig?.model
            // 开关打开时才探，结果按引擎实例缓存（null = 未探测 ⇒ 再问时重探）。
            // 语义详见 probeNativeTools 的 KDoc。model 显式传入：通道 def 按模型选择（Wave 48），
            // 探针必须与生产构造点下发同一份 def。
            if (probedNativeTools == null && loadConfig?.config?.nativeToolChannel == true) {
                val currentEngine = engine
                if (currentEngine != null) probeNativeTools(currentEngine, model)
            }
            val caps = model?.capabilities
            // 模态降级收窄（Wave 44 P0-2）：容器缺 section 时（加载期或 Wave 45 起的会话创建期）
            // 已把该模态去掉，能力位必须同步收窄。⚠️ 本收窄只覆盖「模型卡文案 / 能力查询」；
            // 对话页发图/发音频的门控在 `ChatScreen`（读模型描述符静态位），已在 Wave 44 收口时
            // 叠加本降级事实 ⇒ 两处合起来才杜绝「底层无该后端、UI 仍允许发」的静默失效。
            // 降级事实读 @Volatile 字段，与 loadLocked 同源；空集时行为与 Wave 43 逐字节一致。
            val degraded = degradedModality
            EngineCapabilities(
                supportsText = caps?.text ?: true,
                supportsImage = (caps?.image ?: false) && ModelModality.VISION !in degraded,
                supportsAudio = (caps?.audio ?: false) && ModelModality.AUDIO !in degraded,
                supportsTools = caps?.toolCalling ?: false,
                supportsThinking = caps?.thinking ?: false,
                supportedBackends = caps?.preferredBackends ?: setOf(InferenceBackend.CPU),
                // Wave 28 对齐：maxContextTokens 报**引擎实际持有的 KV 预算**
                // （loadConfig 的 contextLength，即 EngineConfig.maxNumTokens 实参），
                // 不再是模型描述符的启发式默认 —— 上层据此对齐压缩预算才有意义。
                maxContextTokens = loadConfig?.config?.contextLength
                    ?: model?.contextLength ?: 4096,
                // Wave 34 题 A：与 nativeToolChannelActive() 同源（同判据、同三条件）。
                nativeToolChannel = nativeToolChannelActive(),
                nativeThinkingChannel = caps?.thinking ?: false,
                supportsSpeculativeDecoding = probedSpeculativeDecoding
                    ?: caps?.speculativeDecoding
                    ?: false,
                engineLabel = "LiteRT-LM ${model?.displayName.orEmpty()}",
            )
        }
    }

    /**
     * 等待在途生成结束。
     *
     * 返回 `false` = 仍有在途生成，**调用方必须放弃本次操作**。绝不能带着未收敛的生成往下走
     * releaseInternal()：那是 native use-after-free，表现为 SIGSEGV，
     * `runCatching` 抓不住、崩溃日志也记不下来（进程直接被内核杀掉）。
     *
     * 时长口径：4B 模型 1024 token 的生成约 50 秒，原先的 5 秒窗口几乎必然超时。
     * 这里放宽到 10 秒，超时后主动 `cancelProcess()` 再给 3 秒让回调线程收敛。
     */
    private suspend fun waitForGenerationsToFinish(): Boolean {
        withContext(Dispatchers.IO) {
            repeat(100) {
                if (activeGenerations.get() <= 0) return@withContext
                delay(100)
            }
        }
        if (activeGenerations.get() > 0) {
            withContext(engineDispatcher) { runCatching { conversation?.cancelProcess() } }
            withContext(Dispatchers.IO) {
                repeat(30) {
                    if (activeGenerations.get() <= 0) return@withContext
                    delay(100)
                }
            }
        }
        return activeGenerations.get() <= 0
    }

    override suspend fun stop() {
        withContext(engineDispatcher) {
            runCatching { conversation?.cancelProcess() }
            // cancelProcess 之后会话状态不可信，下一次生成必须重建
            conversationDirty = true
        }
    }

    override suspend fun tokenCount(text: String): Int = TokenEstimator.estimate(text)

    override suspend fun unload() {
        // 与 load() **同构**：等不到就抛错，绝不静默 return。
        // 静默 return 会让「已卸载」变成假状态 —— unload() 返回成功而引擎仍 loaded=true、
        // Conversation 还活着，UI 照着返回值显示「已卸载」，用户再点加载却撞上 load() 的
        // 抛错分支，看到「上一次生成仍在继续」，两句话完全对不上。
        // （唯一调用点 ModelsViewModel.onUnload() 外面套着 runCatching，抛出去不会炸到别处。）
        if (!waitForGenerationsToFinish()) {
            throw EngineException("LiteRT-LM：上一次生成仍在继续，请稍候重试")
        }
        withContext(engineDispatcher) {
            mutex.withLock {
                // 必须复用 releaseInternal()，不要在这里另抄一份字段清单：
                // 原来只清了 conversation / currentConversationId / loaded，把 **Engine 本身**
                // （2~3GB 权重）以及 loadedModelPath / loadedContextLength / loadedBackend /
                // loaded*Sampling / sentMessageIds 全留在原地 ——
                // 表现是「UI 显示已卸载，内存一点没降；再去加载别的模型直接 OOM」。
                // 抄一份字段清单迟早会漏（close() 的注释里已经记过一次这个教训）。
                releaseInternal()
                // 会话脏标记属于「上一次加载」，一起清；下次 load() 从干净状态开始。
                conversationDirty = false
            }
        }
    }

    override fun close() {
        // close() 不是 suspend，无法优雅等待；但必须先把在途解码停掉，
        // 否则会在 native 解码仍在跑时释放 Engine —— 表现为 SIGSEGV。
        runCatching { conversation?.cancelProcess() }
        // 复位必须复用 releaseInternal()，不要在这里再抄一遍字段清单。
        // 抄一遍迟早会漏：漏掉 sentMessageIds 一个，就足以让 evict() 后重建的引擎
        // 把整段历史当成「已发送」而不再重发 —— 用户看到的是「模型失忆」，且无任何报错。
        releaseInternal()
        // 这三个不随引擎/会话资源一起走，单独复位
        conversationDirty = false
        loadConfig = null
        probedSpeculativeDecoding = null
    }

    private fun releaseInternal() {
        runCatching { conversation?.close() }
        runCatching { engine?.close() }
        conversation = null
        engine = null
        currentConversationId = null
        currentContextVersion = 0
        // 角色通道绑定随 Conversation 一起销毁：会话没了，native 侧的 systemInstruction /
        // initialMessages 也一并消失。标记必须复位 —— 否则下次建会话前 buildContents 会误以为
        // SYSTEM / MODEL 已在 native 侧而跳过发送，模型直接失去系统提示词与历史。
        currentSystemText = null
        roleChannelActive = false
        // 中档回退标记与诊断快照随会话一起销毁（Wave 33）：快照归 null = UI 端
        // 「没有已建会话」，不渲染任何降级提示。
        systemMergedPending = false
        actualBackend = null
        // 模态降级事实随引擎释放作废（Wave 44 P0-2）：它描述「本次加载」的运行时事实，
        // 引擎没了就没有「本次加载」—— 漏复位会让下次加载的诊断出口报出上一次的降级。
        degradedModality = emptySet()
        _sessionDiagnostics.value = null
        loaded = false
        // 「复用判据」的记忆必须和 engine 一起清掉：只清 engine 而留着这几个参数，
        // 会让下一次 load() 拿着残留参数误判成「同一个引擎」而跳过重建。
        loadedModelPath = null
        loadedContextLength = -1
        loadedBackend = null
        loadedSampling = null
        loadedVisionBackend = null
        loadedAudioBackend = null
        // 原生工具通道探针结果绑定的是**这个引擎实例**（同一个模型文件 + 同一份转换件）。
        // 引擎没了，结果必须一起作废 —— 否则换模型后仍拿旧探针结论去注册工具，而新模型
        // 未必接受同一形状。「证伪」同样绑定本引擎实例（换模型后应重新给一次机会）。
        probedNativeTools = null
        nativeToolsRejected = false
        // 重建计数同样绑定**本引擎实例**（同一模型 + 同一转换件）：引擎没了，计数即作废 ——
        // 否则下次加载会带入上一实例的累计值，让「会话级」语义失真（将来重建限次软熔断会误判）。
        templateRebuildCount = 0
        // tool_call ↔ tool 结果的配对状态与已注册工具集都属于会话，随会话一起作废。
        awaitingNativeToolResponse = false
        registeredToolsSignature = null
        // 水印代表「已经送进 Conversation 的历史」。引擎重建 = 上下文从零开始，
        // 水印若残留，重建后的第一轮会把整段历史当成「已发送」而不再重发 —— 模型直接失忆。
        sentMessageIds.clear()
    }
}
