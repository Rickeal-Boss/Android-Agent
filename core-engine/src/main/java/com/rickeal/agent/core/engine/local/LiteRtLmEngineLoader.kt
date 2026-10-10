package com.rickeal.agent.core.engine.local

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.Conversation as LiteRtConversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.rickeal.agent.core.engine.EngineException
import com.rickeal.agent.core.engine.EngineLoadConfig
import com.rickeal.agent.core.engine.GenerationRequest
import com.rickeal.agent.core.model.AgentLogStore
import com.rickeal.agent.core.model.InferenceBackend
import com.rickeal.agent.core.model.ModelModality
import com.rickeal.agent.core.model.SamplingParams
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * LiteRT-LM 引擎的**加载态载体**（W59 Stage-2 拆分，自 [LiteRtLmEngine] 外提）。
 *
 * 职责边界（与引擎的分工是**唯一事实**，两边都不得越界另抄）：
 *  - 本类拥有**加载态**：native [Engine] 的构造 / 重建 / 释放、模型文件预检、
 *    GPU 二段降级驱动循环、复用判据（`sameEngine`）、模态降级重建
 *   （[reloadForDegrade] / [ensureConversationWithDegrade]）以及全部 `loaded*` /
 *    `actualBackend` / `degradedModality` / `loadConfig` / 探测结果字段。
 *  - **会话态留在引擎**：`conversation` / `currentConversationId` / 水印
 *    （`sentMessageIds`）/ 角色通道标记 / 韧性计数（`templateRebuildCount` /
 *    `nativeToolsRejected`）等不迁 —— 建会话（`ensureConversation`）与其后整条
 *    生成链仍在引擎侧。会话态需要在本类触达的位置，一律经**构造注入的回调**回到
 *    引擎执行（见构造参数的接缝申报），本类不持有会话态字段。
 *
 * 接缝纪律（评审走查面，改动须逐字申报）：
 *  1. **复位回调**（[onRebuildRelease]）：`loadLocked` 内两处「重建前全量复位」改为
 *     调它（= 引擎的 `releaseInternal`，其内部再下沉到本类 [releaseLoadState]）。
 *     **顺序契约逐字节保持：先会话态（conversation close / 水印清 / 诊断清）后加载态
 *    （engine close / `loaded*` 清）** —— 两个 `close()` 的相对顺序是唯一有副作用的
 *     操作面，绝不可重排（历史坑：复位顺序重排 ⇒ 半死状态 / 模型失忆复发）。
 *  2. **建会话回调**（[ensureConversationWithDegrade] 的 `buildSession` 参数）：
 *     包装的建会话函数留在引擎（内含 adopt/persist/闸门接线），本类经参数回调，
 *     lambda 无早退 / 无额外挂起点 ⇒ 行为等价。
 *  3. **重采样会话复位回调**（[onResampleSessionReset]）：复用判据短路分支里
 *    「仅采样参数变化 ⇒ 重建会话（非重建引擎）」要动的全是**会话态**
 *    （conversation close / cid / 水印 / 合并标记），经注入回调回引擎原样执行。
 *
 * 可见性 `internal`：仅 core-engine 模块内组装（引擎唯一持有者），不对外暴露。
 */
internal class LiteRtLmEngineLoader(
    /**
     * 在途生成闸门（接缝①配套）：重建 / 降级重建前必须等在途生成归零，否则对**并发**
     * 在途生成做 `engine.close()` 是 native use-after-free（SIGSEGV）。实现 = 引擎侧
     * `waitForGenerationsToFinish()`（依赖引擎的 activeGenerations 计数）。
     */
    private val waitForGenerations: suspend () -> Boolean,
    /**
     * 重建前全量复位回调（接缝①本体）：= 引擎的 `releaseInternal()`（会话态清 +
     * 回调进本类 [releaseLoadState] 清加载态）。`loadLocked` 内两处调用点语义与
     * 拆分前调用 `releaseInternal()` 逐字节等价。
     */
    private val onRebuildRelease: () -> Unit,
    /**
     * 重采样会话复位回调（接缝③）：复用判据短路且采样参数变化时，由引擎关闭并重建
     * Conversation（水印 / cid / 合并标记一并复位）。语句与拆分前逐字一致。
     */
    private val onResampleSessionReset: () -> Unit,
    private val engineDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) {

    /**
     * 投机解码能力的**真实探测结果**（null = 未探测/探测失败）。
     * 来源：官方 `Capabilities(modelPath).hasSpeculativeDecodingSupport()`。
     * 以此替代按文件名猜测，避免「能力位猜错」导致开了不支持的加速反而出错。
     */
    @Volatile
    var probedSpeculativeDecoding: Boolean? = null
        private set

    private val mutex = Mutex()
    private var engine: Engine? = null
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
    var loadedContextLength: Int = -1
        private set
    internal var loadedBackend: InferenceBackend? = null
        private set
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

    /**
     * 本次加载**实际生效**的后端（Wave 33）：GPU 降级成功后为 CPU。与
     * [loadedBackend]（恒记用户请求值，防误重建，by design）成对 —— 两者不等即
     * 「请求 GPU 实际 CPU」的运行时事实，经 EngineSessionDiagnostics 暴露给 UI。
     */
    @Volatile
    var actualBackend: InferenceBackend? = null
        private set
    var config: EngineLoadConfig? = null
        private set

    /**
     * 加载期 / 会话创建期因容器缺 section 而被去掉的模态（Wave 44 P0-2；Wave 45 起会话
     * 创建期亦可触发，空集 = 未降级）。
     *
     * **另立字段的理由（不许并进复用判据）**：降级是**运行时事实**，
     * 与 [loadedVisionBackend] / [loadedAudioBackend]（记**用户请求的解析值**，进
     * `sameEngine` 判据）**正交**。若把降级写进 `loaded*Backend`，则「请求 GPU 实际去
     * audio」会在下次 `load()` 被判成配置变化而整引擎重建（重新加载权重，纯浪费；
     * 降级结果在进程生命周期内稳定）—— 与 `actualBackend` 分离 [loadedBackend] 是
     * 同一纪律。
     *
     * ⚠️ **必须加进 [releaseLoadState] 复位**（唯一复位点纪律）：历史上漏抄字段清单
     * 出过事故（水印漏复位 ⇒ 模型失忆）。
     */
    @Volatile
    var degradedModality: Set<ModelModality> = emptySet()
        private set

    @Volatile
    private var loaded: Boolean = false

    val isLoaded: Boolean
        get() = loaded

    /** 当前持有的 native 引擎（null = 未加载）。引擎侧建会话 / 探针经它取用。 */
    val nativeEngine: Engine?
        get() = engine

    /** 复用判据消费的「用户请求的后端解析值」（by design 与 [actualBackend] 错位）。 */
    val requestedBackend: InferenceBackend?
        get() = loadedBackend

    /** 加载时锁定的 KV cache 预算（读用别名，语义见 [loadedContextLength] KDoc）。 */
    val contextLength: Int
        get() = loadedContextLength

    // ---------------------------------------------------------------- load

    /**
     * 加载入口。**在途生成闸门由调用方（引擎的 `load()`）在入口施加** —— 本方法
     * 不重复等待；等不到就放弃本次 load 的行为由闸门抛错承接（往下走就是 native
     * use-after-free，那是 SIGSEGV，runCatching 抓不住）。
     */
    suspend fun load(config: EngineLoadConfig) {
        loadLocked(config, seedDegrade = emptySet(), forceRebuild = false)
    }

    /**
     * 引擎 `close()` 专属清尾（**不进** [releaseLoadState]）：`loadConfig` 记忆与
     * 投机解码探测结果在 `releaseInternal` 复位路径上**刻意**保留（历史语义），只在
     * 进程级关闭时随实例一起作废。拆分前由引擎 `close()` 直接写字段，拆分后经本方法
     * 下沉执行，语句与拆分前一致。
     */
    fun clearCloseOnlyState() {
        config = null
        probedSpeculativeDecoding = null
    }

    /**
     * 释放**加载态**（W59 拆分接缝①的加载态半边）：引擎 `releaseInternal()` 的会话态
     * 清理之后调本方法。语句集合与顺序 = 拆分前 `releaseInternal` 内加载态部分原样搬运
     *（engine close → engine 置空 → actualBackend / degradedModality / loaded /
     * `loaded*` 清零）。
     */
    fun releaseLoadState() {
        runCatching { engine?.close() }
        engine = null
        actualBackend = null
        // 模态降级事实随引擎释放作废（Wave 44 P0-2）：它描述「本次加载」的运行时事实，
        // 引擎没了就没有「本次加载」—— 漏复位会让下次加载的诊断出口报出上一次的降级。
        degradedModality = emptySet()
        loaded = false
        // 「复用判据」的记忆必须和 engine 一起清掉：只清 engine 而留着这几个参数，
        // 会让下一次 load() 拿着残留参数误判成「同一个引擎」而跳过重建。
        loadedModelPath = null
        loadedContextLength = -1
        loadedBackend = null
        loadedSampling = null
        loadedVisionBackend = null
        loadedAudioBackend = null
    }

    /** 引擎 `unload()` 的互斥复位面：加载态互斥锁由本类拥有，经此暴露给引擎包住复位块。 */
    suspend fun <R> withLoadStateLocked(block: suspend () -> R): R = mutex.withLock { block() }

    /**
     * 当前 Engine **实际启用**的模态 = 用户请求解析值（`loaded*Backend` 非空）− 已降级模态。
     *
     * ⚠️ `loadedVisionBackend` / `loadedAudioBackend` 记的是**用户请求的解析值**（Wave 45
     * 有意错位：进 `sameEngine` 复用判据，防误重建），**不等于** Engine 实际启用的模态 ——
     * 所以必须再减去 [degradedModality]，才是「本次会话还能拿它去降的模态」。
     */
    fun currentEngineModalities(): Set<ModelModality> = buildSet {
        if (loadedVisionBackend != null) add(ModelModality.VISION)
        if (loadedAudioBackend != null) add(ModelModality.AUDIO)
    } - degradedModality

    /**
     * 会话期模态降级重建：与 [load] 共用 [loadLocked]，差异 = `forceRebuild=true` + `seedDegrade`。
     *
     * 时序纪律（Wave 45 §4-5，**单点赋值**）：不在调用本函数**之前**写 [degradedModality] ——
     * [loadLocked] 内部先经 [onRebuildRelease] 走全量复位（会把 degradedModality 清空，
     * 见 [releaseLoadState]），成功后再于 loadLocked 成功赋值处统一赋值
     * `degradedModality = current.degraded`。故「要去掉的模态」作为 `seedDegrade`
     * **传参**进 [loadLocked]，最终值 = `degradedModality + modality`。
     */
    suspend fun reloadForDegrade(modality: ModelModality) {
        // 闸门（Wave 45 §4-1）：沿用既有安全契约 —— 全量复位会 engine?.close()，
        // 对**并发**在途生成是 native use-after-free（SIGSEGV，runCatching 抓不住）。
        if (!waitForGenerations()) {
            throw EngineException("LiteRT-LM：有在途生成，模态降级重建被跳过，请稍候重试")
        }
        val config = config
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
     * 🔴 **不得把引擎侧 `activeGenerations` 的 `incrementAndGet()` 上提到调用本函数之前**
     *（Wave 45 R12）：会话期重建发生在自增**之前** ⇒ 本生成尚未计数 ⇒ 闸门读到 0、零等待
     * 返回。若把自增上提，[waitForGenerations] 会**自等自**（等自己归零）⇒ 真死锁。
     *
     * @param buildSession 接缝②：建会话函数（引擎侧 `ensureConversation`，内含 adopt /
     *   persist / 闸门接线）经参数回调 —— 它是会话态操作，留在引擎；本函数只负责
     *   「捕获 [ModalityDegradeNeeded] 信号 → 去模态重建 → 重试建会话」的循环。
     */
    suspend fun ensureConversationWithDegrade(
        request: GenerationRequest,
        buildSession: suspend (GenerationRequest) -> LiteRtConversation,
    ): LiteRtConversation {
        var attempts = 0
        while (true) {
            try {
                return buildSession(request)
            } catch (signal: ModalityDegradeNeeded) {
                attempts++
                if (attempts > EngineLoadDegrade.MAX_SESSION_DEGRADE_ATTEMPTS) throw signal
                reloadForDegrade(signal.modality)
            }
        }
    }

    /**
     * 加锁加载段（Wave 45）：[load] 与会话期模态降级重建 [reloadForDegrade] **共用**。
     *
     * **闸门由各调用方在入口施加**（本函数不重复在途生成等待）：
     *  - [load]：调用方（引擎 `load()`）先过闸门 → `loadLocked(∅, false)`；
     *  - [reloadForDegrade]：自身闸门 → `loadLocked(degradedModality + modality, true)`。
     *
     * 为什么共用而非另写一份（Wave 45 R1，规避「两份实现各自演化 ⇒ 静默失效」的历史坑）：
     * Engine 构造 + GPU 二段降级 + 诊断 + 日志是一整块，复制必然分叉。
     *
     * @param seedDegrade 会话期降级重建时**预置**的「已去模态」集合（load 路径恒 ∅）；
     *   作为降级尝试状态的种子，并据它把对应模态后端置 null。
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
                // vision executor 的 CompiledModel::Create 失败。GPU 不可用的设备上
                // 这等于「CPU 模式也永远加载失败」。NPU 不支持视觉编码器（上游 vision
                // executor 对非 CPU/GPU 后端直接 InvalidArgument），强制落回 CPU。
                // W59 A3 契约固化：解析逻辑外提为纯函数 [resolveVisionBackend]（可 JVM
                // 单测），本处只调用 —— 判据与解析单源化，不再双份。
                val resolvedVisionBackend = resolveVisionBackend(
                    wantsVision = wantsVision,
                    requestedBackend = config.config.backend,
                    requestedVisionBackend = config.config.visionBackend,
                )
                val resolvedAudioBackend = resolveAudioBackend(
                    wantsAudio = wantsAudio,
                    requestedAudioBackend = config.config.audioBackend,
                )
                // 复用判据**分两组，别混**：
                //  - sampling（temperature / topP / topK）：随 Conversation 一起固化，
                //    所以变了只需**重建会话**（重建 4B 引擎要几十秒，能省就省）；
                //  - visionBackend / audioBackend：是 **EngineConfig 级别**的参数，
                //    只在 `Engine(engineConfig)` 构造时传入，`createConversation()` 根本拿不到。
                //    把它们放进「重建会话」那一组是静默失效 —— 用户改了视觉后端，
                //    会话重建完了但引擎里的 visionBackend 还是旧的，改了等于没改。
                //    所以它们变了必须**整机重建**。
                //
                // 比的是**解析后的值**，因此也自动覆盖了「模态从无到有」：
                // 用户在模型页把能力位 image 从 false 改成 true（onEditCapabilities / probe 补齐），
                // 解析值就从 null 变成 GPU —— 判据为 false，引擎重建，视觉后端才会真正存在。
                // 若改成拿原始配置比并加 `!wantsVision ||` 前缀，这条路径会判成「可复用」，
                // 于是模型被标成支持视觉、UI 允许发图，而底层 Engine 根本没有视觉后端 —— 静默失效。
                //
                // W59 A3 契约固化：判据外提为纯函数 [isSameEngine]（单源），**签名里刻意
                // 没有 nativeToolChannel 形参** —— 「开关不进重建判据、走会话级生效路径」
                // 这条契约用签名缺席表达，比注释更硬（EngineSameEngineContractTest 钉死：
                // 若未来有人把开关加进判据，契约测试即红）。
                val sameEngine = isSameEngine(
                    forceRebuild = forceRebuild,
                    loaded = loaded,
                    hasEngine = engine != null,
                    loadedModelPath = loadedModelPath,
                    modelPath = modelPath,
                    loadedContextLength = loadedContextLength,
                    contextLength = config.config.contextLength,
                    loadedBackend = loadedBackend,
                    backend = config.config.backend,
                    loadedVisionBackend = loadedVisionBackend,
                    visionBackend = resolvedVisionBackend,
                    loadedAudioBackend = loadedAudioBackend,
                    audioBackend = resolvedAudioBackend,
                )
                if (sameEngine) {
                    this@LiteRtLmEngineLoader.config = config
                    // 采样参数是随 Conversation 一起固化的，只改这些参数**不必**重建引擎
                    // （重建 4B 引擎要几十秒），但必须重建会话，否则新参数永远不生效。
                    val samplingChanged = loadedSampling != config.config.sampling
                    if (samplingChanged) {
                        // 接缝③：会话态复位（conversation close / cid / 水印 / 合并标记）
                        // 回引擎原样执行 —— 本类不持有会话态字段。
                        onResampleSessionReset()
                    }
                    loadedSampling = config.config.sampling
                    return@withLock
                }
                // 接缝①：重建前全量复位（先会话态后加载态，顺序契约见类 KDoc）。
                onRebuildRelease()

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
                //     createConversation 抛出）：见引擎侧建会话的 catch 与 [reloadForDegrade]。
                //  2. 后端降级（既有，2026-09-26）：Manifest 未声明 libOpenCL.so（Android 12+
                //     访问厂商非 NDK 库必须 <uses-native-library>）时 GPU 委托 dlopen 失败 →
                //     CompiledModel::Create 抛 INTERNAL。上游 issue #1860：SDK 无预检 API，
                //     调用方自己降级重试 CPU。
                // 复用判据仍记**用户请求的**解析值 —— 降级是运行时事实、不是新配置，否则
                // 「请求 GPU 实际 CPU」会在下次 load() 被判成配置变化而整引擎重建。
                //
                // GPU 文案门控（复审 P1-2）：只有用户请求真的涉及 GPU（主后端或视觉后端）
                // 才允许说「GPU 委托不可用」——与旧 attempts 逐字等价。
                val gpuInvolved = EngineLoadDegrade.gpuInvolved(
                    config.config.backend,
                    resolvedVisionBackend,
                )
                val hadGpuAttempt = gpuInvolved

                var current = EngineLoadDegrade.initial(
                    backend = config.config.backend,
                    // 会话期降级重建（Wave 45）：seedDegrade 里的模态把对应后端置 null，
                    // 于是本轮 EngineConfig 不再带该模态 —— 这是「audio 真降得掉」的关键
                    // （EngineConfig 读的是 current 的字段，不是 resolved*）。
                    visionBackend = if (ModelModality.VISION in seedDegrade) null else resolvedVisionBackend,
                    audioBackend = if (ModelModality.AUDIO in seedDegrade) null else resolvedAudioBackend,
                    degraded = seedDegrade,
                )
                // visited 去重 + 上限（防循环）：任何重复状态不再入队。
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
                        // 原生工具通道探针不在 load() 里跑 —— 见引擎侧 probeNativeTools 的
                        // KDoc：它只在**上层真的开了这个开关、且第一次问能力时**才探一次并
                        // 缓存，默认关闭时 load() 与 Wave 33 完全一致（零额外 Conversation）。
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
                        this@LiteRtLmEngineLoader.config = config
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
                        onRebuildRelease()
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
}

/**
 * 会话创建遇「容器缺编码器子图」的内部信号（非终态失败，需去模态重建后重试）。
 *
 * 为什么用「抛信号 + 重试循环」而非把建会话改成 suspend 内部重建（Wave 45 §4-2）：
 * 引擎侧 `ensureConversation` 在开头捕获 native Engine 局部引用后 mutate ~15 个字段，
 * 若在其内部 suspend 并重建，该局部引用会变陈旧（重建后 engine 是新对象）⇒ 极易踩
 * native use-after-free。信号方案让重试循环（[LiteRtLmEngineLoader.ensureConversationWithDegrade]）
 * 在重建后**重新调用**建会话（拿到全新 native Engine），规避该陷阱。
 *
 * 可见性 `internal`（W59 拆分申报：原为引擎文件内 `private`）：建会话（引擎侧）抛、
 * 重试循环（[LiteRtLmEngineLoader.ensureConversationWithDegrade]）捕，跨文件但同模块，
 * 不跨模块观测（Wave 45 裁决 §4 语义不变）。
 */
internal class ModalityDegradeNeeded(val modality: ModelModality) :
    Exception("会话创建缺 $modality 编码器子图，需去模态重建后重试")

/**
 * 模型文件预检的体积下限（64MB）。
 *
 * 低于它视为「下载中断的残片」：内置最小的预设（450M 视觉模型）也有 ~0.25GB，
 * 而 DownloadManager 中断/被清理后常留下几 MB 的半截文件、或下载源返回的错误页
 * （几 KB~几十 KB）。取 64MB 既不会误伤任何真实模型，也能拦住绝大多数残片。
 */
internal const val MODEL_MIN_BYTES: Long = 64L * 1024L * 1024L

/**
 * 视觉后端的**解析规则**（W59 A3 契约固化，自引擎 loadLocked 外提的纯函数）：
 * 模型要视觉时，取用户配置的视觉后端；未配置则**跟随主后端**，且 NPU 强制落回 CPU
 *（上游 vision executor 对非 CPU/GPU 后端直接 InvalidArgument，见 loadLocked 同源注释）。
 * 不需要视觉时恒 null —— `EngineConfig` 不带视觉后端。
 *
 * 纯函数（零 native 依赖，可 JVM 单测）：解析值与 `isSameEngine` 判据**同源**，
 * 改这里 = 改判据事实面。
 */
internal fun resolveVisionBackend(
    wantsVision: Boolean,
    requestedBackend: InferenceBackend?,
    requestedVisionBackend: InferenceBackend?,
): InferenceBackend? =
    if (wantsVision) {
        requestedVisionBackend ?: when (requestedBackend) {
            InferenceBackend.NPU -> InferenceBackend.CPU
            else -> requestedBackend
        }
    } else {
        null
    }

/**
 * 音频后端的**解析规则**（W59 A3 契约固化，自引擎 loadLocked 外提的纯函数）：
 * 模型要音频时，取用户配置的音频后端，未配置落 CPU（音频编码器只支持 CPU 路径）；
 * 不需要音频时恒 null。
 *
 * 纯函数（零 native 依赖，可 JVM 单测）：解析值与 `isSameEngine` 判据**同源**。
 */
internal fun resolveAudioBackend(
    wantsAudio: Boolean,
    requestedAudioBackend: InferenceBackend?,
): InferenceBackend? =
    if (wantsAudio) {
        requestedAudioBackend ?: InferenceBackend.CPU
    } else {
        null
    }

/**
 * `sameEngine` 复用判据（W59 A3 契约固化，自引擎 loadLocked 外提的纯函数）：
 * 全部五项比较**逐项列出**，与拆分前 loadLocked 内联判据逐语义等价
 *（三前置 `!forceRebuild && loaded && hasEngine` + 五项相等）。
 *
 * 🔴 **契约本体：签名里刻意没有 nativeToolChannel 形参** —— 「原生工具通道开关切换
 * 不触发实例更换，开关走会话级生效路径（探针懒探测 + 会话重建判据）」这条契约用
 * **签名缺席**表达，比注释更硬（同模块 JVM 契约测试 EngineSameEngineContractTest
 * 钉死该语义：若未来有人把开关加进判据，契约测试与调用点编译即红）。
 *
 * 纯函数（零 native 依赖，可 JVM 单测）：`engine != null` 参数化为 `hasEngine`，
 * 保持函数纯度；调用点（loadLocked）是它的**唯一**生产消费方，判据单源、无双份。
 */
internal fun isSameEngine(
    forceRebuild: Boolean,
    loaded: Boolean,
    hasEngine: Boolean,
    loadedModelPath: String?,
    modelPath: String?,
    loadedContextLength: Int,
    contextLength: Int,
    loadedBackend: InferenceBackend?,
    backend: InferenceBackend?,
    loadedVisionBackend: InferenceBackend?,
    visionBackend: InferenceBackend?,
    loadedAudioBackend: InferenceBackend?,
    audioBackend: InferenceBackend?,
): Boolean =
    !forceRebuild &&
        loaded &&
        hasEngine &&
        loadedModelPath == modelPath &&
        loadedContextLength == contextLength &&
        loadedBackend == backend &&
        loadedVisionBackend == visionBackend &&
        loadedAudioBackend == audioBackend
