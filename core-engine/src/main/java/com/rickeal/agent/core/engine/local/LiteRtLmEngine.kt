package com.rickeal.agent.core.engine.local

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation as LiteRtConversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.rickeal.agent.core.engine.EngineCapabilities
import com.rickeal.agent.core.engine.EngineException
import com.rickeal.agent.core.engine.EngineLoadConfig
import com.rickeal.agent.core.engine.GenerationRequest
import com.rickeal.agent.core.engine.LlmEngine
import com.rickeal.agent.core.model.AgentLogStore
import com.rickeal.agent.core.model.Attachment
import com.rickeal.agent.core.model.DeltaTracker
import com.rickeal.agent.core.model.EngineKind
import com.rickeal.agent.core.model.FinishReason
import com.rickeal.agent.core.model.GenerationChunk
import com.rickeal.agent.core.model.InferenceBackend
import com.rickeal.agent.core.model.Role
import com.rickeal.agent.core.model.SamplingParams
import com.rickeal.agent.core.model.ThinkingMode
import com.rickeal.agent.core.model.TokenEstimator
import com.rickeal.agent.core.model.TokenUsage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 简报 §3.1：思考文本走 channels["thought"]。 */
internal const val THOUGHT_CHANNEL = "thought"

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
 *  4. ConversationConfig 的 systemInstruction/tools/initialMessages 全部传空 ——
 *     这三个参数在 0.11.0 的确切构造方式无法核对，传空最保险。
 *     系统提示词改由 messages[0]（role=SYSTEM）承载；工具走 Agent 层文本协议。
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
     * 投机解码能力的**真实探测结果**（null = 未探测/探测失败）。
     * 来源：官方 `Capabilities(modelPath).hasSpeculativeDecodingSupport()`。
     * 以此替代按文件名猜测，避免「能力位猜错」导致开了不支持的加速反而出错。
     */
    @Volatile
    private var probedSpeculativeDecoding: Boolean? = null

    /** 已发送消息的 id 水印（见 buildContents 注释）。会话重建时必须清空。 */
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
    private var loadedMaxTokens: Int = -1
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
    private var loadConfig: EngineLoadConfig? = null

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
                val resolvedVisionBackend = if (wantsVision) {
                    config.config.visionBackend ?: InferenceBackend.GPU
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
                val sameEngine = loaded &&
                    engine != null &&
                    loadedModelPath == modelPath &&
                    loadedMaxTokens == config.config.maxTokens &&
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
                    }
                    loadedSampling = config.config.sampling
                    return@withLock
                }
                releaseInternal()

                val backend = toBackend(config.config.backend, config.nativeLibraryDir)

                val engineConfig = EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    // 复用上面算好的解析值：默认值只在这一处落地，判据与引擎不会各写一份而走偏。
                    visionBackend = resolvedVisionBackend?.let {
                        toBackend(it, config.nativeLibraryDir)
                    },
                    audioBackend = resolvedAudioBackend?.let {
                        toBackend(it, config.nativeLibraryDir)
                    },
                    maxNumTokens = config.config.maxTokens,
                    cacheDir = config.externalFilesDir ?: config.cacheDir,
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
                    loadedMaxTokens = config.config.maxTokens
                    loadedBackend = config.config.backend
                    loadedSampling = config.config.sampling
                    // 记**解析后的值**，与 sameEngine 判据同源；记原始配置会让
                    // 「能力位从 false 改 true」时两侧都是同一个原始值而误判为可复用。
                    loadedVisionBackend = resolvedVisionBackend
                    loadedAudioBackend = resolvedAudioBackend
                    loadConfig = config
                    loaded = true
                } catch (t: Throwable) {
                    // 任何失败路径都必须彻底复位（engine 置空 / loaded 置 false / 参数记忆与水印清空）。
                    // 否则下一次 load() 会拿残留状态误判为「可复用」，引擎就永久卡在坏状态里。
                    releaseInternal()
                    if (t is EngineException) throw t
                    throw EngineException("LiteRT-LM: 创建 Engine 失败 (${t.message})", t)
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

    private fun ensureConversation(request: GenerationRequest): LiteRtConversation {
        val currentEngine = engine
            ?: throw EngineException("LiteRT-LM: 引擎未加载，请先 load()")
        // 重建判据（外部审查报告2 §2）：conversationId 或 contextVersion 任一变化。
        // cid 变化 = 换了会话；contextVersion 变化 = 应用侧上下文发生了引擎无法增量
        // 表达的变化（典型：上下文压缩真的裁掉了历史）—— 两条路都必须关旧会话、
        // 清水印、让上层全量重放 messages，否则 KV cache 与应用侧窗口脱节。
        if (request.conversationId != currentConversationId ||
            request.contextVersion != currentContextVersion
        ) {
            // 可观测重建频率（核验建议）：重建 = 一次全量 re-prefill（4B 模型秒级开销），
            // 频率异常升高说明上层压缩/会话切换策略需要关注。
            AgentLogStore.info(
                "LiteRT-LM 会话重建：cid=${request.conversationId ?: "null"} v${request.contextVersion}" +
                    "（旧 cid=${currentConversationId ?: "null"} v$currentContextVersion）"
            )
            runCatching { conversation?.close() }
            conversation = null
            // 换了会话/版本 = 换了 KV cache，水印必须一起清零，否则历史不会被重发 → 新会话丢上下文
            sentMessageIds.clear()
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
        }
        val existing = conversation
        if (existing != null) return existing

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
        val created = currentEngine.createConversation(
            ConversationConfig(
                samplerConfig = samplerConfig,
                systemInstruction = null,
                tools = emptyList(),
                initialMessages = emptyList(),
            )
        )
        conversation = created
        currentConversationId = request.conversationId
        currentContextVersion = request.contextVersion
        return created
    }

    // -------------------------------------------------------- generate

    override fun generateStream(request: GenerationRequest): Flow<GenerationChunk> = flow {
        val conv = ensureConversation(request)
        activeGenerations.incrementAndGet()
        val thinkingOn = when (request.config.thinking) {
            ThinkingMode.ON -> true
            ThinkingMode.OFF -> false
            ThinkingMode.AUTO -> request.model?.capabilities?.thinking == true
        }
        val extraContext: Map<String, Any> =
            if (thinkingOn) mapOf("enable_thinking" to true) else emptyMap()

        // UNLIMITED：LLM 流式决不能丢 token，宁可堆积内存（chunk 只有几十字节）
        val channel = Channel<GenerationChunk>(Channel.UNLIMITED)
        val textTracker = DeltaTracker()
        val thoughtTracker = DeltaTracker()
        val startNs = System.nanoTime()
        var finished = false
        var firstTokenNs = 0L
        var chunkCount = 0

        val callback = object : MessageCallback {
            override fun onMessage(message: Message) {
                if (firstTokenNs == 0L) firstTokenNs = System.nanoTime()
                val textDelta = textTracker.next(message.toString())
                val thoughtDelta = thoughtTracker.next(message.channels[THOUGHT_CHANNEL] ?: "")
                if (textDelta.isEmpty() && thoughtDelta.isEmpty()) return
                chunkCount++
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
                val tps = if (decodeMs > 0L) chunkCount * 1000f / decodeMs else 0f
                channel.trySend(
                    GenerationChunk(
                        finishReason = FinishReason.STOP,
                        usage = TokenUsage(
                            promptTokens = TokenEstimator.estimate(request.messages),
                            completionTokens = chunkCount,
                            totalTokens = TokenEstimator.estimate(request.messages) + chunkCount,
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
                channel.close(EngineException("LiteRT-LM: 生成失败 (${throwable.message})", throwable))
            }
        }

        val contents = buildContents(request)
        conv.sendMessageAsync(Contents.of(contents), callback, extraContext)

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
     * 构造本次要发送给 LiteRT-LM 的内容。
     *
     * 关键点：**只发「还没发过」的消息**（用 message.id 做水印）。Conversation 内部自带 KV cache 历史，
     * 若每轮都把全量历史重发，会出现重复；而若只发最后一条用户消息（最初的实现），
     * 系统提示词与工具执行结果就永远进不了上下文，Agent 循环会退化成「单轮瞎猜」。
     */
    private fun buildContents(request: GenerationRequest): List<Content> {
        val fresh = request.messages.filter { message -> sentMessageIds.add(message.id) }
        if (fresh.isEmpty()) return listOf(Content.Text(""))

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
                    if (message.text.isNotBlank()) out.add(Content.Text(message.text))
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

    // -------------------------------------------------------- misc

    override suspend fun capabilities(): EngineCapabilities {
        return withContext(engineDispatcher) {
            val model = loadConfig?.model
            val caps = model?.capabilities
            EngineCapabilities(
                supportsText = caps?.text ?: true,
                supportsImage = caps?.image ?: false,
                supportsAudio = caps?.audio ?: false,
                supportsTools = caps?.toolCalling ?: false,
                supportsThinking = caps?.thinking ?: false,
                supportedBackends = caps?.preferredBackends ?: setOf(InferenceBackend.CPU),
                maxContextTokens = model?.contextLength ?: 4096,
                nativeToolChannel = false,
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
                // （2~3GB 权重）以及 loadedModelPath / loadedMaxTokens / loadedBackend /
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
        loaded = false
        // 「复用判据」的记忆必须和 engine 一起清掉：只清 engine 而留着这几个参数，
        // 会让下一次 load() 拿着残留参数误判成「同一个引擎」而跳过重建。
        loadedModelPath = null
        loadedMaxTokens = -1
        loadedBackend = null
        loadedSampling = null
        loadedVisionBackend = null
        loadedAudioBackend = null
        // 水印代表「已经送进 Conversation 的历史」。引擎重建 = 上下文从零开始，
        // 水印若残留，重建后的第一轮会把整段历史当成「已发送」而不再重发 —— 模型直接失忆。
        sentMessageIds.clear()
    }
}
