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
import com.rickeal.agent.core.model.Attachment
import com.rickeal.agent.core.model.DeltaTracker
import com.rickeal.agent.core.model.EngineKind
import com.rickeal.agent.core.model.FinishReason
import com.rickeal.agent.core.model.GenerationChunk
import com.rickeal.agent.core.model.InferenceBackend
import com.rickeal.agent.core.model.Role
import com.rickeal.agent.core.model.ThinkingMode
import com.rickeal.agent.core.model.TokenEstimator
import com.rickeal.agent.core.model.TokenUsage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
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
    private val sentMessageIds: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    private val mutex = Mutex()
    private var engine: Engine? = null
    private var conversation: LiteRtConversation? = null
    private var loadedModelPath: String? = null
    private var loadedMaxTokens: Int = -1
    private var loadedBackend: InferenceBackend? = null
    private var currentConversationId: String? = null
    private var loadConfig: EngineLoadConfig? = null

    @Volatile
    private var loaded: Boolean = false

    override val isLoaded: Boolean
        get() = loaded

    // ---------------------------------------------------------------- load

    override suspend fun load(config: EngineLoadConfig) {
        // 换模型时若上一轮解码还在跑，直接换引擎会踩空 —— 先等它结束
        waitForGenerationsToFinish()
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
                val sameEngine = loaded &&
                    engine != null &&
                    loadedModelPath == modelPath &&
                    loadedMaxTokens == config.config.maxTokens &&
                    loadedBackend == config.config.backend
                if (sameEngine) {
                    loadConfig = config
                    return@withLock
                }
                releaseInternal()

                val backend = toBackend(config.config.backend, config.nativeLibraryDir)
                val wantsVision = config.model?.capabilities?.image == true
                val wantsAudio = config.model?.capabilities?.audio == true

                val engineConfig = EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    visionBackend = if (wantsVision) {
                        toBackend(config.config.visionBackend ?: InferenceBackend.GPU, config.nativeLibraryDir)
                    } else {
                        null
                    },
                    audioBackend = if (wantsAudio) {
                        toBackend(config.config.audioBackend ?: InferenceBackend.CPU, config.nativeLibraryDir)
                    } else {
                        null
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
        if (request.conversationId != currentConversationId) {
            runCatching { conversation?.close() }
            conversation = null
            // 换了会话 = 换了 KV cache，水印必须一起清零，否则历史不会被重发 → 新会话丢上下文
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
                    val result = message.toolResults.firstOrNull()
                    val payload = result?.output?.takeIf { it.isNotBlank() }
                        ?: result?.errorMessage
                        ?: ""
                    if (payload.isNotBlank()) out.add(Content.Text(payload))
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

    /** 等待在途生成结束（最多约 5 秒），避免在生成过程中卸载 native 引擎导致崩溃。 */
    private suspend fun waitForGenerationsToFinish() {
        withContext(Dispatchers.IO) {
            repeat(50) {
                if (activeGenerations.get() <= 0) return@withContext
                kotlinx.coroutines.delay(100)
            }
        }
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
        waitForGenerationsToFinish()
        withContext(engineDispatcher) {
            mutex.withLock {
                runCatching { conversation?.close() }
                conversation = null
                currentConversationId = null
                loaded = false
            }
        }
    }

    override fun close() {
        // close() 不是 suspend，无法优雅等待；但必须先把在途解码停掉，
        // 否则会在 native 解码仍在跑时释放 Engine —— 表现为 SIGSEGV。
        runCatching { conversation?.cancelProcess() }
        runCatching { conversation?.close() }
        runCatching { engine?.close() }
        conversation = null
        engine = null
        loadedModelPath = null
        loaded = false
    }

    private fun releaseInternal() {
        runCatching { conversation?.close() }
        runCatching { engine?.close() }
        conversation = null
        engine = null
        currentConversationId = null
        loaded = false
        // 「复用判据」的记忆必须和 engine 一起清掉：只清 engine 而留着这三个参数，
        // 会让下一次 load() 拿着残留参数误判成「同一个引擎」而跳过重建。
        loadedModelPath = null
        loadedMaxTokens = -1
        loadedBackend = null
        // 水印代表「已经送进 Conversation 的历史」。引擎重建 = 上下文从零开始，
        // 水印若残留，重建后的第一轮会把整段历史当成「已发送」而不再重发 —— 模型直接失忆。
        sentMessageIds.clear()
    }
}
