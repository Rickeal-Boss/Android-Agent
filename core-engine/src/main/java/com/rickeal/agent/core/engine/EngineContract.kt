package com.rickeal.agent.core.engine

import com.rickeal.agent.core.model.ChatMessage
import com.rickeal.agent.core.model.EngineKind
import com.rickeal.agent.core.model.GenerationChunk
import com.rickeal.agent.core.model.InferenceBackend
import com.rickeal.agent.core.model.InferenceConfig
import com.rickeal.agent.core.model.ModelDescriptor
import com.rickeal.agent.core.model.ToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 引擎加载所需的一切。刻意不传 Context —— 只传字符串，便于测试与隔离。 */
data class EngineLoadConfig(
    val model: ModelDescriptor? = null,
    val config: InferenceConfig = InferenceConfig(),
    /** context.cacheDir —— LiteRT-LM 的权重缓存目录 */
    val cacheDir: String? = null,
    /** context.applicationInfo.nativeLibraryDir —— NPU 后端必需 */
    val nativeLibraryDir: String? = null,
    /** context.getExternalFilesDir(null) —— gallery 用它作 cacheDir */
    val externalFilesDir: String? = null,
    /** 附件（图片/音频/文件）字节读取的根目录白名单 */
    val sandboxDir: String? = null,
)

data class GenerationRequest(
    val messages: List<ChatMessage>,
    val config: InferenceConfig = InferenceConfig(),
    val model: ModelDescriptor? = null,
    val tools: List<ToolSpec> = emptyList(),
    /**
     * 会话标识。LiteRT-LM 的 Conversation 自带历史，本引擎的策略是：
     * conversationId 变化 => 关闭旧 Conversation 并重建（不回放历史）。
     */
    val conversationId: String? = null,
    /**
     * 应用侧上下文版本（外部审查报告2 §2，B1 压缩语义失效的根治）。
     *
     * 语义契约：**contextVersion 变化时，引擎必须关闭旧 Conversation 并全量接收
     * [messages]**（即重建 KV cache、清增量水印、整包重放）。
     * 存在理由：引擎的 Conversation 是「只增不减」的 —— 应用侧发生引擎无法用增量
     * 方式表达的变化（典型：上下文压缩真的裁掉了历史消息）时，唯一的正确动作就是
     * 重建。没有这个契约，压缩只存在于应用侧的窗口里，引擎的 KV cache 仍持有全部
     * 旧历史 —— 压缩语义整体失效，模型「记得」所有本该被裁掉的内容。
     */
    val contextVersion: Long = 0,
)

/** 探测出来的引擎能力。驱动 UI 的开关可用性与 Agent 的工具通道选择。 */
data class EngineCapabilities(
    val supportsText: Boolean = true,
    val supportsImage: Boolean = false,
    val supportsAudio: Boolean = false,
    val supportsTools: Boolean = false,
    val supportsThinking: Boolean = false,
    val supportedBackends: Set<InferenceBackend> = setOf(InferenceBackend.CPU),
    val maxContextTokens: Int = 4096,
    /** 是否支持「模型原生 tool 通道」。false 时 Agent 必须走文本协议。 */
    val nativeToolChannel: Boolean = false,
    val nativeThinkingChannel: Boolean = false,
    /**
     * 是否支持投机解码（speculative decoding）。
     * 真值来自官方 `Capabilities(modelPath).hasSpeculativeDecodingSupport()` 的**真实探测**，
     * 探测不可用时回退到按文件名猜测的 `ModelCapabilities.speculativeDecoding`。
     */
    val supportsSpeculativeDecoding: Boolean = false,
    val engineLabel: String = "",
)

class EngineException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * 引擎会话级诊断快照（Wave 33）。
 *
 * 存在理由：用户真机反复出现的「只输出提示词然后胡言乱语」在 Wave 24 修了主根因
 * （角色通道），但仍存在两类**静默降级**没有用户可见的出口：
 *  1. 「第三态」—— `createConversation` 成功、但模型 chat template 渲染时**丢失**
 *     systemInstruction（Gemma 系模板原生无 system role），此前只有诊断日志没有动作；
 *     ⚠️ 口径收窄（Wave 39）：本诊断只证明「**丢失**」，**证明不了「错位」** ——
 *     判据是归一化**子串**匹配（Wave 39 时位于
 *     `core-engine/src/main/java/com/rickeal/agent/core/engine/local/LiteRtLmEngine.kt`
 *     的 `normalizeForPrefaceCheck(preface).contains(window)`），它只对「窗口串有没有
 *     **出现**」敏感，对「出现在**什么位置 / 哪个 role 段**」完全不敏感 —— 系统提示词
 *     被渲染到用户消息之后、或被塞进别的 role 段里，`contains` 照样返回 true。
 *     所以它能抓「渲染丢了 / 截断到窗口拼不出来」，抓不了「渲染到了错的位置」；
 *     后者本仓**没有**代码侧观测点（详见 LiteRtLmEngine 同口径注释）。
 *  2. GPU 降级实际生效后端无记录 —— 用户以为在跑 GPU，实际在 CPU。
 *
 * 快照在**会话建成**与**降级/回退事件**时整体发布（不逐字段更新），`null` = 当前没有
 * 已建会话（未加载 / 已释放）。字段全部带默认值：数据面只增不改，消费方按需读取。
 *
 * 例外（复审 A3）：[systemMergedIntoUser] 反映**运行态**，会在合并标记被消费时单独
 * 改回 false —— 否则会话不重建期间该字段会与引擎实际状态永久漂移。
 */
data class EngineSessionDiagnostics(
    /**
     * 「角色通道」是否激活（`ConversationConfig.systemInstruction` / `initialMessages`
     * 播种路径生效中）。false = 已回退 legacy 纯文本压平（此时 [legacyFallbackReason]
     * 应非空）。
     */
    val roleChannelActive: Boolean = false,
    /** legacy 回退原因（引擎侧异常消息截断）。非 roleChannelActive 时才可能有值。 */
    val legacyFallbackReason: String? = null,
    /**
     * 中档回退生效中（Wave 33）：模板渲染丢失系统提示词（第三态），系统提示词已
     * 改为并入首条用户消息发送。此时 [roleChannelActive] 保持 true（MODEL 回灌
     * 门控必须保留，与 legacy 回退的本质区别）。
     *
     * ⚠️ 本字段是**运行态**、不是会话出生时的快照（复审 A3）：会话建成时置 true，
     * 待合并标记在引擎侧被真正消费（拼进首条 USER）后**立即**改回 false。消费方
     * （UI 小字）必须按「当前是否仍处于合并态」读取，不能缓存首次读到的值。
     */
    val systemMergedIntoUser: Boolean = false,
    /** 用户**请求**的后端（by design 记请求值：降级是运行时事实、不是新配置）。 */
    val requestedBackend: InferenceBackend? = null,
    /** **实际生效**的后端（GPU 降级后 = CPU）。与 [requestedBackend] 不等即降级。 */
    val actualBackend: InferenceBackend? = null,
    /** 加载时锁定的 KV cache 预算（token 数）。 */
    val contextLength: Int = 0,
    /**
     * 「原生工具通道」是否**实际生效**（Wave 34 题 A，UI 流消费）：true = 工具经引擎
     * 原生 tool 通道注册与回传（提示词里已不含工具清单段）；false = 走文本协议
     * （默认，含探针失败 / 用户未开启 / 模型不具备工具能力三种情形）。
     *
     * ⚠️ 与 [LlmEngine.capabilities] 报的**同名** `nativeToolChannel` **不是同一个表达式**，
     * 只是名字相同、层级不同 —— 且在已知路径下会分叉（Wave 36 E2 如实化，勿再按「同一判据」理解）：
     *  - 本字段是**会话级**事实，赋值表达式为
     *    `nativeToolChannel = registeredToolsSignature != null`
     *    （`LiteRtLmEngine.ensureConversation` 的会话建成收尾处；Wave 36 时位于
     *    `LiteRtLmEngine.kt:998`）—— 判据是「**本会话真的把工具签名注册进了 native**」。
     *  - `capabilities()` 的同名字段是**引擎级**能力，赋值表达式为
     *    `nativeToolChannel = nativeToolChannelActive()`
     *    （Wave 36 时位于 `LiteRtLmEngine.kt:1574`），判据是四条件合取
     *    `!nativeToolsRejected && probedNativeTools == true &&
     *    loadConfig?.config?.nativeToolChannel == true &&
     *    loadConfig?.model?.capabilities?.toolCalling == true`
     *    （判据函数在 `LiteRtLmEngine.kt:1487-1491`）。
     *
     * **分叉场景**（Wave 36 复审 P2-1 修正）：先排除一个易被误认的候选 ——
     * 「注册工具后会话创建失败 → 不带工具重试」**不构成**分叉：该路径在 `nativeTools.isNotEmpty()`
     * 时会显式置 `nativeToolsRejected = true`（`LiteRtLmEngine.kt:862-863`），而
     * `nativeToolChannelActive()` 的首个合取项正是 `!nativeToolsRejected`
     * （`LiteRtLmEngine.kt:1487-1491`）⇒ 引擎级随之报 `false`，与本字段一致。legacy 回退路径同理
     * （`LiteRtLmEngine.kt:914-915` 的置位同样带 `nativeTools.isNotEmpty()` 前置）—— 只要本会话
     * 真的注册过工具，任何失败都会证伪通道，两侧一起翻 `false`。
     *
     * **唯一真实的分叉**发生在：`nativeToolChannelActive() == true`（探针通过 ∧ 用户开关开 ∧
     * 模型能力位 ok ∧ 未被证伪）**但本轮 `request.tools` 为空**（上层未启用任何工具）。此时 E4 的
     * 合取使 `nativeToolsActive == false` ⇒ 本会话不注册任何工具 ⇒ 会话级
     * `registeredToolsSignature == null` ⇒ 本字段 `false`；而两处置 `nativeToolsRejected = true`
     * 都以 `nativeTools.isNotEmpty()` 为前置，空工具集下**永不置位** ⇒ 引擎级四条件仍全真 ⇒
     * 引擎级报 `true`。这条分叉**与 `createConversation` 是否成功无关**：无论会话正常建成、走中档
     * 「系统提示词并入首条 USER」重建、还是失败回退 legacy，会话级都因「没注册工具」而为 `false`，
     * 引擎级都为 `true`。
     *
     * 结论（不变）：两者**不是同一个表达式**，消费方**不得**假设恒等 —— 本字段描述「**这一条
     * 会话**的实际形态」，引擎级描述「**这台引擎**当前是否具备原生通道能力」。要判断「本会话到底
     * 走哪条通道」，只能读本字段。
     */
    val nativeToolChannel: Boolean = false,
)

/**
 * [LlmEngine.sessionDiagnostics] 的**兜底空流单例**（复审 P2-2）：仅供测试 fake /
 * 未来实现类兜底，生产实现必须覆写为真实状态流。文件级单例避免接口默认 getter
 * 每次访问都新建 MutableStateFlow 的无谓分配。
 */
private val EMPTY_SESSION_DIAGNOSTICS_FLOW: StateFlow<EngineSessionDiagnostics?> =
    MutableStateFlow(null)

interface LlmEngine {
    val kind: EngineKind

    /** 当前是否已可生成。 */
    val isLoaded: Boolean

    /**
     * 当前是否有在途生成。UI 用它做前置判断（例如「正在生成时禁用卸载按钮」）。
     *
     * **它只能减少误触，不能替代引擎侧的硬闸门**：这是普通 Boolean，不是 StateFlow，
     * 「读到 false」与「真正调用 load()/unload()」之间天然存在一个窗口，
     * 期间另一条流完全可以起来。所以 load() / unload() 里的
     * `waitForGenerationsToFinish()` 闸门必须保留，不可因为加了本字段而移除。
     */
    val isBusy: Boolean
        get() = false

    /**
     * 会话级诊断快照（Wave 33）。见 [EngineSessionDiagnostics] 的语义说明。
     *
     * 接口默认实现返回恒为 null 的空流（文件级单例 [EMPTY_SESSION_DIAGNOSTICS_FLOW]）
     * —— 仅供测试 fake / 未来实现类兜底，**生产实现必须覆写**：唯一生产实现
     * [com.rickeal.agent.core.engine.local.LiteRtLmEngine] 已用真实状态流覆写。
     * 装饰器（EngineLoadCoordinator 的 LoadObservedEngine）经接口委托自动透传
     * 真实实现的状态流。
     */
    val sessionDiagnostics: StateFlow<EngineSessionDiagnostics?>
        get() = EMPTY_SESSION_DIAGNOSTICS_FLOW

    /** 幂等加载。同 model/endpoint + 同 config 时直接返回。 */
    suspend fun load(config: EngineLoadConfig)

    /** 释放会话/连接，保留引擎对象。 */
    suspend fun unload()

    suspend fun capabilities(): EngineCapabilities

    /** 冷流：collect 时才真正开始生成。取消 collect 即取消生成。 */
    fun generateStream(request: GenerationRequest): Flow<GenerationChunk>

    /** 主动停止（等价取消）。 */
    suspend fun stop()

    /** 估算 token 数。LiteRT-LM 无 tokenizer，走启发式。 */
    suspend fun tokenCount(text: String): Int

    /** 彻底释放，之后必须重新 load。 */
    fun close()
}

interface EngineFactory {
    fun create(kind: EngineKind): LlmEngine

    /**
     * 丢弃并关闭某个 kind 的缓存实例，使下一次 create() 返回一个**全新**实例。
     *
     * 为什么必须有这个入口：引擎（尤其本地 LiteRT-LM）一旦在 load()/initialize()/生成
     * 过程中失败，缓存里那个对象可能停在「半死」状态且无法自愈 —— 再调一次 load() 也不会
     * 恢复。上层唯一的恢复手段就是「换一个新对象重新加载」。没有 evict 时，用户遇到一次
     * 加载失败后必须杀掉 App 重启才能重试，等同于「这个功能坏了」。
     */
    fun evict(kind: EngineKind)

    /**
     * 关闭并丢弃所有缓存引擎。
     *
     * ⚠️ **本方法是同步的，无法等待在途生成收敛**（`LlmEngine.close()` 不是 suspend）。
     * 若调用时仍存在在途生成，等同于 native use-after-free —— 表现为 SIGSEGV：
     * `runCatching` **抓不到**、崩溃日志**记不下来**（进程被内核直接杀掉），
     * 只会表现为「偶发闪退、什么都没留下」。
     *
     * 因此调用点必须自行确保已无在途生成；需要优雅停止请走 `LlmEngine.stop()` 并显式等待。
     * 可以先读 `LlmEngine.isBusy` 做前置判断，但那只是 Boolean、读与调之间有窗口，
     * **不能**作为唯一防线。
     *
     * 当前唯一调用点是 `AppContainer.close()` ← `LiquidAgentApplication.onTerminate()`，
     * 而 `onTerminate()` 在真机上**从不触发**（官方口径：仅供模拟进程环境），所以今天这里是安全的。
     * **但请不要「顺手改进」成挂在 `Activity.onDestroy()` / `ViewModel.onCleared()` 上** ——
     * 那会让上面这个窗口立刻变成真窗口，且只在「生成中 + 退出页面」时偶发，排查成本极高。
     */
    fun closeAll()
}
