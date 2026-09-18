package com.rickeal.agent.core.engine.local

import com.google.ai.edge.litertlm.Backend
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
        withContext(engineDispatcher) {
            mutex.withLock {
                val modelPath = config.model?.path
                if (modelPath.isNullOrBlank()) {
                    throw EngineException("LiteRT-LM: modelPath 为空")
                }
                val sameEngine = engine != null &&
                    loadedModelPath == modelPath &&
                    loadedMaxTokens == config.config.maxTokens &&
                    loadedBackend == config.config.backend
                if (sameEngine) {
                    loadConfig = config
                    loaded = true
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

                val created = try {
                    Engine(engineConfig)
                } catch (t: Throwable) {
                    throw EngineException("LiteRT-LM: 创建 Engine 失败 (${t.message})", t)
                }
                try {
                    created.initialize()
                } catch (t: Throwable) {
                    runCatching { created.close() }
                    throw EngineException("LiteRT-LM: initialize 失败 (${t.message})", t)
                }

                engine = created
                loadedModelPath = modelPath
                loadedMaxTokens = config.config.maxTokens
                loadedBackend = config.config.backend
                loadConfig = config
                loaded = true
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
                temperature = cfg.sampling.temperature.toDouble(),
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
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                val tps = if (elapsedMs > 0) chunkCount * 1000f / elapsedMs else 0f
                channel.trySend(
                    GenerationChunk(
                        finishReason = FinishReason.STOP,
                        usage = TokenUsage(
                            promptTokens = TokenEstimator.estimate(request.messages),
                            completionTokens = chunkCount,
                            totalTokens = TokenEstimator.estimate(request.messages) + chunkCount,
                            tokensPerSecond = tps,
                            firstTokenLatencyMillis = if (firstTokenNs == 0L) {
                                0L
                            } else {
                                (firstTokenNs - startNs) / 1_000_000L
                            },
                            decodeMillis = elapsedMs,
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
            // 流结束（正常 / 取消 / 异常）都确保底层停止，避免 GPU 继续烧电
            runCatching { conv.cancelProcess() }
            runCatching { channel.cancel() }
        }
    }
        .flowOn(engineDispatcher)
        .cancellable()

    private fun buildContents(request: GenerationRequest): List<Content> {
        val out = ArrayList<Content>(4)
        val last = request.messages.lastOrNull { it.role == Role.USER }
            ?: request.messages.lastOrNull()
        if (last != null) {
            for (attachment in last.attachments) {
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
        }
        // 简报 §3.1：文本必须放在最后
        out.add(Content.Text(last?.text.orEmpty()))
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
                engineLabel = "LiteRT-LM ${model?.displayName.orEmpty()}",
            )
        }
    }

    override suspend fun stop() {
        withContext(engineDispatcher) {
            runCatching { conversation?.cancelProcess() }
        }
    }

    override suspend fun tokenCount(text: String): Int = TokenEstimator.estimate(text)

    override suspend fun unload() {
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
    }
}
