package com.rickeal.agent.core.engine

import com.rickeal.agent.core.model.ChatMessage
import com.rickeal.agent.core.model.EngineKind
import com.rickeal.agent.core.model.GenerationChunk
import com.rickeal.agent.core.model.InferenceBackend
import com.rickeal.agent.core.model.InferenceConfig
import com.rickeal.agent.core.model.ModelDescriptor
import com.rickeal.agent.core.model.ToolSpec
import kotlinx.coroutines.flow.Flow

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
