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
import com.rickeal.agent.core.engine.EngineCapabilities
import com.rickeal.agent.core.engine.EngineException
import com.rickeal.agent.core.engine.EngineLoadConfig
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
 * 模型文件预检的体积下限（64MB）。
 *
 * 低于它视为「下载中断的残片」：内置最小的预设（450M 视觉模型）也有 ~0.25GB，
 * 而 DownloadManager 中断/被清理后常留下几 MB 的半截文件、或下载源返回的错误页
 * （几 KB~几十 KB）。取 64MB 既不会误伤任何真实模型，也能拦住绝大多数残片。
 */
private const val MODEL_MIN_BYTES: Long = 64L * 1024L * 1024L

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
 *     bug，但**不会丢上下文**（比"两边都不发"安全）。工具仍走 Agent 层文本协议（tools
 *     恒传空）。
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
     * P0-A（2026-09-27）：系统提示词此前被 [buildContents] 压进一条无角色 user 文本发送，
     * 模型看到的是一段"纯文本"，于是先复述提示词再退化。现改走
     * `ConversationConfig.systemInstruction`（native 侧 `Message.system`），本字段用于
     * **重建判据**：提示词变了必须重建会话，否则 native 侧还挂着旧的 system。
     * 与 currentConversationId / currentContextVersion 同生命周期，[releaseInternal] 清零。
     */
    private var currentSystemText: String? = null
    /**
     * 「角色通道」是否已**成功**启用（ConversationConfig 的 systemInstruction /
     * initialMessages 播种成功）。
     *
     * 只有它为 true 时，[buildContents] 才可以跳过 SYSTEM / MODEL（这两类已由
     * systemInstruction 与 initialMessages 承载）。一旦创建失败回退 legacy 配置，本标记
     * **必须**为 false —— 否则系统提示词与 MODEL 轮会被"两边都不发"，模型直接失去系统
     * 提示词与历史，比原 bug 更糟。
     */
    private var roleChannelActive: Boolean = false
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
                val sameEngine = loaded &&
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

                // ── 创建引擎：GPU 不可用自动降级 CPU（2026-09-26）────────────────────
                // 真机实锤：Manifest 未声明 libOpenCL.so（Android 12+ 访问厂商非 NDK 库
                // 必须 <uses-native-library>）时，GPU 委托 dlopen 失败 → CompiledModel::
                // Create 抛 INTERNAL（llm_litert_compiled_model_executor.cc:1928）。
                // 上游 issue #1860 的结论就是「SDK 没有预检 API，调用方自己降级重试 CPU」。
                // 这里做成同一次 load() 内的二段尝试：主配置失败且涉及 GPU → 直接换
                // CPU/CPU 再试一次，用户无感。复用判据仍记**用户请求的**解析值 ——
                // 降级是运行时事实、不是新配置，否则「请求 GPU 实际 CPU」会在下次
                // load() 被判成配置变化而整引擎重建（重新加载权重，纯浪费；降级结果
                // 在进程生命周期内是稳定的）。
                val attempts = buildList {
                    add(config.config.backend to resolvedVisionBackend)
                    if (config.config.backend == InferenceBackend.GPU ||
                        resolvedVisionBackend == InferenceBackend.GPU
                    ) {
                        add(InferenceBackend.CPU to if (wantsVision) InferenceBackend.CPU else null)
                    }
                }

                var lastError: Throwable? = null
                for ((index, attempt) in attempts.withIndex()) {
                    if (index > 0) {
                        AgentLogStore.warn(
                            "LiteRT-LM GPU 后端不可用（${lastError?.message?.take(160) ?: "未知错误"}），" +
                                "自动降级 CPU 重试"
                        )
                    }
                    val engineConfig = EngineConfig(
                        modelPath = modelPath,
                        backend = toBackend(attempt.first, config.nativeLibraryDir),
                        visionBackend = attempt.second?.let { toBackend(it, config.nativeLibraryDir) },
                        audioBackend = resolvedAudioBackend?.let { toBackend(it, config.nativeLibraryDir) },
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
                        loadedContextLength = config.config.contextLength
                        loadedBackend = config.config.backend
                        loadedSampling = config.config.sampling
                        // 记**解析后的值**，与 sameEngine 判据同源；记原始配置会让
                        // 「能力位从 false 改 true」时两侧都是同一个原始值而误判为可复用。
                        loadedVisionBackend = resolvedVisionBackend
                        loadedAudioBackend = resolvedAudioBackend
                        loadConfig = config
                        loaded = true
                        if (index > 0) {
                            AgentLogStore.warn(
                                "LiteRT-LM 已以 CPU 后端完成加载（本次会话 GPU 不可用，" +
                                    "请求的后端：${config.config.backend}）"
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
                    }
                }
                lastError?.let { t ->
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

    /** ExperimentalApi：renderPrefaceIntoString 渲染诊断（Wave 28，仅日志用途，失败静默）。 */
    @OptIn(ExperimentalApi::class)
    private fun ensureConversation(request: GenerationRequest): LiteRtConversation {
        val currentEngine = engine
            ?: throw EngineException("LiteRT-LM: 引擎未加载，请先 load()")
        // P0-A：系统提示词改由 ConversationConfig.systemInstruction 承载（native Message.system），
        // 取第一条非空 SYSTEM 正文。
        val systemText = request.messages
            .firstOrNull { it.role == Role.SYSTEM }?.text?.takeIf { it.isNotBlank() }
        // 重建判据（外部审查报告2 §2）：conversationId / contextVersion / systemText 任一变化。
        // cid 变化 = 换了会话；contextVersion 变化 = 应用侧上下文发生了引擎无法增量表达的
        // 变化（典型：上下文压缩真的裁掉了历史）；systemText 变化 = 系统提示词改了，而它只在
        // 建会话时注入一次，不重建就永远不生效。三条路都必须关旧会话、清水印、让上层全量
        // 重放 messages，否则 KV cache / native system 与应用侧脱节。
        if (request.conversationId != currentConversationId ||
            request.contextVersion != currentContextVersion ||
            systemText != currentSystemText
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

        val roleConfig = ConversationConfig(
            samplerConfig = samplerConfig,
            // ⚠️ systemInstruction 的类型是 **Contents?**（litertlm 0.17.1 起，旧版是 String?）
            // —— 必须包一层 Contents.of(...)。传裸 String 编译不过。
            systemInstruction = systemText?.let { Contents.of(it) },
            tools = emptyList(),
            initialMessages = seedMessages,
        )
        val created = try {
            val conv = currentEngine.createConversation(roleConfig)
            roleChannelActive = true
            // preface 渲染诊断（Wave 28，@OptIn ExperimentalApi）：preface = systemInstruction +
            // initialMessages 在 native chat template 下的**实际渲染结果**。若某转换件对
            // system role 渲染不当（createConversation 成功但渲染错位/丢失 —— 「角色通道
            // 静默忽略」第三态，不抛异常故回退门控抓不住），这里是唯一的代码侧观测点：
            // 真机日志比对 preface 长度与开头片段即可定位「模型根本没看到系统提示词」类问题。
            // 渲染失败（模型/版本不支持）静默跳过 —— 诊断绝不成为失败面。
            runCatching {
                val preface = conv.renderPrefaceIntoString()
                AgentLogStore.info(
                    "LiteRT-LM preface 渲染诊断：${preface.length} chars，" +
                        "开头「${preface.take(120).replace('\n', ' ')}」"
                )
            }
            conv
        } catch (t: Throwable) {
            // 真机保命：角色通道播种失败（旧版 litertlm / 模型 chat template 不接受 system
            // 或 initialMessages）时，回退到 **legacy 纯文本配置**，让系统提示词与 MODEL 轮
            // 重新走 buildContents 的文本压平路径。
            // 不置 roleChannelActive=false 会导致两条路都不发（系统提示词 + 历史全丢），
            // 比原 bug 更糟；不回退则直接抛错，整个引擎不可用。
            AgentLogStore.warn(
                "LiteRT-LM 角色通道播种失败（systemInstruction/initialMessages），" +
                    "已回退 legacy 纯文本配置：${t.message?.take(160) ?: "未知错误"}"
            )
            // 清掉刚登记的播种水印：legacy 路径必须靠 buildContents 把全量历史重新发一遍。
            sentMessageIds.clear()
            roleChannelActive = false
            currentEngine.createConversation(
                ConversationConfig(
                    samplerConfig = samplerConfig,
                    systemInstruction = null,
                    tools = emptyList(),
                    initialMessages = emptyList(),
                )
            )
        }
        conversation = created
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
        // 内容 chunk 数（排除纯思考 chunk）—— 复审 U7（2026-09-26）：chunk 里含
        // thinkingDelta 时 tok/s 与 completionTokens 把推理输出也算进"生成"，
        // 通知栏指标虚高（DeepSeek-R1 类推理模型尤甚，思考可能占大半时长）。
        // 口径统一为**用户可见正文**：thinking chunk 不进这两个指标。
        var contentChunkCount = 0
        val callback = object : MessageCallback {
            override fun onMessage(message: Message) {
                if (firstTokenNs == 0L) firstTokenNs = System.nanoTime()
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
                // 裸透传用户读不懂。映射成可操作指引；命中后 conversationDirty 已由
                // finally 置位，下一轮自动重建会话。
                val tooLong = raw.contains("too long", ignoreCase = true) ||
                    (raw.contains("max", ignoreCase = true) && raw.contains("token", ignoreCase = true))
                val hint = if (tooLong) {
                    " —— 上下文超出模型容量。请清空/精简当前会话，或调小「上下文长度」设置后重试"
                } else {
                    ""
                }
                channel.close(EngineException("LiteRT-LM: 生成失败 (${raw})$hint", throwable))
            }
        }

        val contents = buildContents(request)
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
        conv.sendMessageAsync(
            Contents.of(contents),
            callback,
            extraContext = extraContext,
            repetitionPenaltyConfig = repetitionPenaltyConfig,
            // 输出上限逐消息生效（Wave 28）：KV 预算已由 EngineConfig.maxNumTokens =
            // contextLength 承载（输入+输出总和），maxTokens 在这里的语义回归本位 ——
            // 「单次生成的输出 token 上限」（含思考输出，litertlm 口径）。逐消息参数
            // 不进 Conversation 状态：用户改输出上限既不重建引擎也不重建会话，立即生效。
            // NPU 后端无此约束（约束的是 samplerConfig，见上），照常传递。
            maxOutputToken = request.config.maxTokens,
        )

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
        val fresh = request.messages.filter { message ->
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
     * ⚠️ TOOL 用 `Message.user` 而非 `Message.tool` 的取舍：本项目工具走的是 **Agent 层
     * 文本协议** —— 工具调用是模型以正文 JSON 形式输出的（Agent 层解析后已从 MODEL 文本里
     * 剥掉），native 侧**没有**与之配对的 tool_call 记录。在缺少前置 tool_call 的情况下塞
     * `role=tool` 消息，多数 chat template 会判为非法（tool 消息必须紧跟 tool_call）。工具
     * 结果本就以 user 文本回传（legacy 路径即如此），故这里保持同一语义。
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
            if (contents.isEmpty()) null else Message.user(Contents.of(contents))
        }

        // 只发可见正文，不带 thinking（与 buildContents 的 MODEL 分支同口径）：
        // 工具调用的原始 JSON 由 Agent 层解析，不该污染上下文。
        Role.MODEL -> text.takeIf { it.isNotBlank() }?.let { Message.model(it) }

        Role.TOOL -> {
            // 遍历**全部**结果：`ContextCompressor.sanitizeForProvider()` 会把一批工具结果
            // 合成一条含 N 个结果的 TOOL 消息（与 buildContents 的 TOOL 分支同一理由）。
            val texts = toolResults.mapNotNull { result ->
                (result.output.takeIf { it.isNotBlank() } ?: result.errorMessage ?: "")
                    .takeIf { it.isNotBlank() }
            }
            if (texts.isEmpty()) null else Message.user(Contents.of(texts.map { Content.Text(it) }))
        }
    }

    // -------------------------------------------------------- misc

    /**
     * 合并相邻的同角色 USER native 消息（Contents 拼接）。
     *
     * 只处理 USER：MODEL 相邻在部分模板下同样非法，但应用侧 MODEL 轮之间恒有
     * TOOL/USER 隔开（AgentRunner 循环不变量），无需处理；TOOL 不映射为 native
     * tool（文本协议），天然在 USER 合并范围内。
     */
    private fun mergeAdjacentNativeUsers(messages: List<Message>): List<Message> {
        if (messages.size < 2) return messages
        val out = ArrayList<Message>(messages.size)
        for (message in messages) {
            val last = out.lastOrNull()
            if (last != null && last.role == NativeRole.USER && message.role == NativeRole.USER) {
                out[out.size - 1] = Message.user(Contents.of(last.contents.contents + message.contents.contents))
            } else {
                out.add(message)
            }
        }
        return out
    }

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
                // Wave 28 对齐：maxContextTokens 报**引擎实际持有的 KV 预算**
                // （loadConfig 的 contextLength，即 EngineConfig.maxNumTokens 实参），
                // 不再是模型描述符的启发式默认 —— 上层据此对齐压缩预算才有意义。
                maxContextTokens = loadConfig?.config?.contextLength
                    ?: model?.contextLength ?: 4096,
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
        loaded = false
        // 「复用判据」的记忆必须和 engine 一起清掉：只清 engine 而留着这几个参数，
        // 会让下一次 load() 拿着残留参数误判成「同一个引擎」而跳过重建。
        loadedModelPath = null
        loadedContextLength = -1
        loadedBackend = null
        loadedSampling = null
        loadedVisionBackend = null
        loadedAudioBackend = null
        // 水印代表「已经送进 Conversation 的历史」。引擎重建 = 上下文从零开始，
        // 水印若残留，重建后的第一轮会把整段历史当成「已发送」而不再重发 —— 模型直接失忆。
        sentMessageIds.clear()
    }
}
