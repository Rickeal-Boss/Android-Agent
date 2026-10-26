package com.rickeal.agent.feature.chat

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rickeal.agent.core.agent.AgentEvent
import com.rickeal.agent.core.model.AiCapabilityMode
import com.rickeal.agent.core.model.ToolDisclosureMode
import com.rickeal.agent.core.agent.AgentPolicy
import com.rickeal.agent.core.agent.TerminationReason
import com.rickeal.agent.core.agent.approval.ToolApprovalDecision
import com.rickeal.agent.core.agent.breaker.BottleneckReport
import com.rickeal.agent.core.agent.plan.PlanStep
import com.rickeal.agent.core.data.AppContainer
import com.rickeal.agent.core.engine.EngineSessionDiagnostics
import com.rickeal.agent.core.engine.local.EngineLoadDegrade
import com.rickeal.agent.core.model.AgentLogStore
import com.rickeal.agent.core.model.Attachment
import com.rickeal.agent.core.model.ChatMessage
import com.rickeal.agent.core.model.EngineKind
import com.rickeal.agent.core.model.InferenceBackend
import com.rickeal.agent.core.model.InferenceConfig
import com.rickeal.agent.core.model.ModelDescriptor
import com.rickeal.agent.core.model.ModelGpuSupport
import com.rickeal.agent.core.model.ModelSamplingProfiles
import com.rickeal.agent.core.model.Role
import com.rickeal.agent.core.model.SamplingParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 工具调用在 UI 上的一条轨迹（纯 UI，不落库）。 */
@Immutable
data class ToolTrace(
    val id: String,
    val name: String,
    val arguments: String,
    val status: ToolTraceStatus = ToolTraceStatus.RUNNING,
    val result: String? = null,
    val elapsedMillis: Long = 0L,
)

enum class ToolTraceStatus { RUNNING, OK, FAILED, SKIPPED }

/**
 * 一次等待用户裁决的工具调用（Octop tool_guard 的 UI 面）。
 * `decision` 在用户点击授权/拒绝时 complete；run 被取消时随协程一起取消。
 */
@Immutable
data class PendingApproval(
    val callId: String,
    val toolName: String,
    val arguments: String,
    val decision: CompletableDeferred<ToolApprovalDecision>,
)

/** 崩溃恢复 offer：journal 扫描发现「没有 settled 行」的 run。 */
@Immutable
data class RecoveryOffer(
    val runId: String,
    val messageCount: Int,
)

/**
 * 流式通道的独立状态（Wave3 节流改造）：从 [ChatUiState] 拆出高频字段，
 * token 突发只失效读本流的组合点，不再打挂整个 ChatScreen。
 *
 * 关键语义：TextDelta/ThinkingDelta 先进 [ChatViewModel] 的字符缓冲，
 * 由 120ms flush 循环收敛后才进这里 —— 渲染频率与 token 速率解耦
 * （gallery BufferedFadingMarkdownText 的 conflate 参数实测值）。
 */
@Immutable
data class StreamingState(
    val text: String = "",
    val thinking: String = "",
    val role: Role? = null,
    val isStreaming: Boolean = false,
    /** 流式期间的实时指标（TTFT 精确、tps 粗估）；终态后由引擎精确 usage 覆盖。 */
    val usage: StreamingUsage? = null,
)

/** 流式实时指标（E 项）：TTFT = run 启动 → 首个可见 token；tps 按字符数粗估。 */
@Immutable
data class StreamingUsage(
    val ttftMillis: Long = 0L,
    val tokensPerSecond: Float = 0f,
)

/** 流式 flush 间隔（gallery 实测参数）：token 突发收敛为最多每 120ms 一次渲染。 */
internal const val STREAM_FLUSH_INTERVAL_MS = 120L

@Immutable
data class ChatUiState(
    val title: String = "新对话",
    /** 已完成的消息，稳定不变；流式中的那条在 [StreamingState]（独立低频流）。 */
    val messages: List<ChatMessage> = emptyList(),
    val isStreaming: Boolean = false,
    val agentRound: Int = 0,
    val agentMaxRounds: Int = 8,
    val draftInput: String = "",
    val attachments: List<Attachment> = emptyList(),
    val config: InferenceConfig = InferenceConfig(),
    /**
     * AI 能力档位（Wave 26）。由设置页写入 DataStore，本页 collect 后在每次 run 时
     * 传给 AgentRequest —— 档位**必须进入执行路径**，只活在设置页就是「看起来有权限
     * 控制、实际不生效」的假象（Operit2 明令禁止的反模式）。
     */
    val capabilityMode: AiCapabilityMode = AiCapabilityMode.WORKSPACE_WRITE,
    /**
     * 工具披露模式（Wave 27）。与 [capabilityMode] 同一纪律：由设置页写入 DataStore，
     * 本页 collect 后每次 run 下发 —— 模式**必须进入执行路径**，只活在设置页就等于
     * 什么都没做。默认 FULL（工具清单照旧全量进提示词）＝ 零行为回归。
     */
    val disclosureMode: ToolDisclosureMode = ToolDisclosureMode.FULL,
    val availableModels: List<ModelDescriptor> = emptyList(),
    val activeModel: ModelDescriptor? = null,
    /**
     * 当前激活模型的**已知坏签名**（W68 B1 数据面镜像）。
     *
     * 由 `ModelRepository.activeModelKnownBroken`（core-data 派生只读流）收集而来 ——
     * 对话页**只收集不查预设表**，判定键（`ModelPresets.findByFileName` 精确匹配）
     * 收敛在 core-data 一处。`null` = 无已知坏签名 / 无激活模型 / 非 preset 文件。
     * 本字段本身**不驱动 UI**；run 发起时由 [activeModelWarning] 按判据式决定是否提示。
     */
    val activeModelKnownBroken: String? = null,
    /**
     * 对话页「激活态重校验」提示（W68 B2）。非空 = 渲染一张可关闭的警示卡。
     *
     * 判据式（写死）：`(fileName ∈ knownBrokenOn) OR (gpuInvolved(backend, visionBackend) ∧
     * fileName ∉ gpuVerifiedFileNames)`（见 [activationWarningFor]）。**触发时机 = run 发起时**
     * （不是 UI collect 时 —— 避免进会话就红、离开就忘的噪声）；**频控 = 每会话至多一次**
     * （[ActivationWarningGate]），用户关闭后当次会话不再现，换会话复位。
     *
     * 与模型库页的加载前确认框（`ModelCard.confirmBrokenLoad`）**互斥**：那是「加载前拦截」
     * （模型库页、可取消加载），本字段是「激活态告知」（对话页、不拦 run）—— 两语义不得
     * 叠成双弹；同一 run 至多一种提示形态（真机判据见 §五.2 ①）。
     */
    val activeModelWarning: String? = null,
    val isGenerating: Boolean = false,
    val error: String? = null,
    /**
     * 非阻塞告知（G 项，gallery 自愈链可见化）：引擎重建/重试这类「已自动恢复，
     * 但用户应该知道发生了什么」的信息。与 error 的区别 —— error 是失败终态需要
     * 用户处置，notice 是自愈过程提示，下一终态事件自动清除。
     */
    val notice: String? = null,
    val toolsEnabled: Boolean = true,
    /** 流式气泡里「思考过程」是否展开 */
    val thinkingExpanded: Boolean = false,
    val toolTraces: List<ToolTrace> = emptyList(),
    /** 已提交消息里被展开的「思考过程」 */
    val expandedThinkingIds: Set<String> = emptySet(),
    /** 工具过程折叠组（F 项）中被手动展开的组（组 id = 首成员 trace.id；默认全折叠）。 */
    val expandedGroupIds: Set<String> = emptySet(),
    /**
     * 最近一次请求实际送进模型的上下文规模（`TokenUsage.promptTokens`）。
     *
     * 端侧窗口只有 4K 量级，超限会被静默压缩、模型随即「变傻」。这个值就是给用户看的前兆。
     * `null` = 还没有可用数据（此时 UI 不显示任何占用量，而不是显示 0）。
     */
    val contextTokens: Int? = null,
    /** 当前会话的执行计划（plan_set / plan_update 维护；ZCode Phase Graph 降级移植）。 */
    val planSteps: List<PlanStep> = emptyList(),
    /** 等待用户授权的工具调用；非 null 时输入区上方显示授权卡。 */
    val pendingApproval: PendingApproval? = null,
    /** 崩溃恢复 offer：上次 run 被进程死亡打断（journal 无 settled 行）。 */
    val recovery: RecoveryOffer? = null,
    /**
     * 最近一次终态附带的诊断卡（Wave 30 §2.4/§2.5）。非空 = 这一轮是被熔断（热闸 /
     * 墙钟 / 失败连击 / 振荡）或轮次耗尽收掉的，UI 据此渲染「卡在哪 + 建议」。
     *
     * 与 [error] 的分工：error 是「出错了」这一句结论，本字段是「为什么 + 下一步」
     * 的完整归因；两者同屏展示（诊断卡挂在错误卡下方，见 ChatScreen 的渲染纪律）。
     * 起 run 时与 error 同一处清 null —— 上一次任务的诊断不该粘到下一次任务上。
     */
    val lastReport: BottleneckReport? = null,
    /**
     * 本次 run 的**发送侧估算**上下文规模（`RunTokenLedger.sentTokens`，Wave 31 流2 生产接线）。
     *
     * 与 [contextTokens]（生成回报口径）是**并列的第二口径**，两者**不做换算也不做对账**
     * （[com.rickeal.agent.core.agent.token.RunTokenLedger] KDoc 红线）。它由 AgentRunner
     * 发送侧记账块经 `AgentRequest.tokenLedger` 回写，故在**发送前/首轮**即可给出预估 ——
     * 这是 [contextTokens]（要等生成回报）给不出的信息。
     *
     * ⚠️ **Wave 50 订正**：[contextTokens] 并非「引擎实测」——
     * `TokenUsage.promptTokens` 全仓只有 `LiteRtLmEngine.kt:1593` 一个构造点，其值就是
     * `TokenEstimator.estimate(request.messages)`（`:1594`），与本字段**同源同算法**；
     * 真机实测两数完全相等。本字段的**真正差异**只剩「发送前就能给」这一点。
     *
     * `null` / `<= 0` = 还没有可用估算（账本未接 / 新 run 尚未首轮回写），此时 UI 不显示
     * 估算口径。⚠️ 账本按会话池化、跨 run 存活，而 sentTokens 是 run 级（新 run 首轮回写即
     * 覆盖）—— 新 run 起点由 [ChatRunCoordinator.observeTokenLedger] 用账本时间戳做基线、**只
     * 接受本轮的新回写**，避免首轮回写前的窗口里显示上一轮遗留值（账本实例本身无 reset
     * API，见该函数 KDoc）。
     */
    val sentTokensEstimate: Long? = null,
    /**
     * 最近一次终态的**终止原因**（`AgentEvent.Finished.terminatedBy` /
     * `AgentEvent.Failed.terminatedBy`，Wave 31 流2）。
     *
     * 此前该字段零消费者；本波只把「非正常终止」中语义明确的两档渲染为一行小字
     * （见 ChatScreen 的 terminationHintOf）：[TerminationReason.BreakerTripped] /
     * [TerminationReason.MaxRounds]。其余值 / null ⇒ 不渲染（正常结束路径零 UI 变化）。
     * 起 run 时与 error / lastReport 同一处清 null（上一次的终止原因不粘到下一次）。
     */
    val lastTermination: TerminationReason? = null,
    /**
     * 引擎会话级诊断快照（Wave 33，`LlmEngine.sessionDiagnostics` 的 UI 透传）。
     *
     * `null` = 引擎侧尚无已建会话（未加载 / 已释放），UI 不渲染任何东西。
     * 非 null 也仅在三类静默降级时上屏一行小字（legacy 回退 / 系统提示词并入用户
     * 消息 / 后端降级，映射见 ChatScreen 的 sessionDiagnosticsHintOf）：角色通道
     * active 且后端一致时不渲染 —— 正常路径零 UI 变化。快照由引擎在会话建成 /
     * 回退事件时整体发布，这里只做透传不加工。
     */
    val sessionDiagnostics: EngineSessionDiagnostics? = null,
)

class ChatViewModel(
    private val container: AppContainer,
    initialConversationId: String?,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /**
     * run 编排（Wave 48 真外提）：run 生命周期（[onSend] / [onRetry] / [onRecover] / [onStop]、
     * 流式缓冲与复位、终态处理与落库、journal 恢复）整体归 [ChatRunCoordinator]；本类只保留
     * UI 状态与输入事件，对外 API 面以薄转发维持（见文件末尾「run 编排薄转发」）。
     *
     * - scope 传 [viewModelScope]（**不新建 scope**，否则 VM 销毁时 run 不会被取消）；
     * - [_uiState] 共享同一实例（Coordinator 持引用并 `update`，避免 UI 状态分叉）。
     */
    private val run = ChatRunCoordinator(
        container,
        viewModelScope,
        // W77：落库走进程级 scope —— onCleared 之后 viewModelScope 已取消，
        // 半截答案的写入不能被它带走（见 ChatRunCoordinator 构造参数 KDoc）。
        container.processScope,
        _uiState,
        initialConversationId,
    )

    /** 流式通道：转发 Coordinator 的同一 StateFlow（`ChatScreen` 拿到的实例不变）。 */
    val streaming: StateFlow<StreamingState> get() = run.streaming

    /** 授权卡「相同调用不再询问」：写入审批缓存（TTL 30min，同参免再弹卡）。 */
    fun onApprovalRememberForSession() {
        val pending = _uiState.value.pendingApproval ?: return
        container.toolApprovalCache.grant(
            toolName = pending.toolName,
            argsDigest = com.rickeal.agent.core.agent.approval.ToolApprovalCache.argsDigest(pending.arguments),
            conversationId = run.conversationId,
            // 档位入 key（Wave 28 P1-1）：授权跟随授权那一刻的档位，降档后必须重新询问。
            capabilityMode = _uiState.value.capabilityMode.name,
            ttlMillis = 0L,
        )
        pending.decision.complete(ToolApprovalDecision.APPROVED)
    }

    init {
        viewModelScope.launch {
            container.conversationRepository.refresh()
            container.modelRepository.refresh()
        }
        viewModelScope.launch {
            container.settingsRepository.inferenceConfig.collect { config ->
                _uiState.update {
                    it.copy(
                        config = config,
                        agentMaxRounds = config.maxAgentRounds,
                        toolsEnabled = config.enableTools,
                    )
                }
            }
        }
        // AI 能力档位（Wave 26）：设置页改动后立即生效于下一次 run。
        // 与 inferenceConfig 分开 collect（两者是独立设置，合并会让任一变化都触发全量更新）。
        viewModelScope.launch {
            container.settingsRepository.capabilityMode.collect { mode ->
                _uiState.update { it.copy(capabilityMode = mode) }
            }
        }
        // 工具披露模式（Wave 27）：与 capabilityMode 独立 collect（一个管权限面、
        // 一个管提示词可见性，合并会让任一变化都触发全量更新）。
        viewModelScope.launch {
            container.settingsRepository.disclosureMode.collect { mode ->
                _uiState.update { it.copy(disclosureMode = mode) }
            }
        }
        viewModelScope.launch {
            container.modelRepository.models.collect { models ->
                _uiState.update { it.copy(availableModels = models) }
            }
        }
        viewModelScope.launch {
            container.settingsRepository.activeModelId.collect { id ->
                _uiState.update { it.copy(activeModel = container.modelRepository.find(id)) }
            }
        }
        // 激活模型的已知坏签名（W68 B1 数据面）：core-data 派生只读流 —— 对话页**只收集
        // 不查预设表**。判定键（精确文件名匹配）收敛在 core-data；本页只在 run 发起时按
        // 判据式决定是否提示（见 ChatRunCoordinator）。
        viewModelScope.launch {
            container.modelRepository.activeModelKnownBroken.collect { broken ->
                _uiState.update { it.copy(activeModelKnownBroken = broken) }
            }
        }
        // 引擎会话诊断（Wave 33）：观察 LlmEngine.sessionDiagnostics 并透传进 UI 状态。
        // 默认同源：engineFactory.create 按 kind 缓存（DefaultEngineFactory），拿到的就是
        // AgentRunner 实际生成用的同一实例；但 AgentRunner.rebuildEngine 的 evict+create
        // 会**换出全新引擎实例**，init 时刻绑定的旧实例诊断流从此失联。故经
        // engineInitStatus 状态变化重订阅（rebuildEngine 必经 load → status 必转变 →
        // flatMapLatest 切到新缓存实例），保证诊断流始终跟随当前引擎实例。
        // LoadObservedEngine 是接口委托，新实例的状态流原样透传。
        // ⚠️ flatMapLatest 是 ExperimentalCoroutinesApi：@OptIn 不能标在 init 块上
        //（initializer 不是合法标注目标，CI 首轮实锤），标在承载它的局部变量上
        //（作用域最窄，initializer 随声明一起被覆盖）。
        @OptIn(ExperimentalCoroutinesApi::class)
        val engineDiagnosticsFlow = container.engineInitStatus
            .map { container.engineFactory.create(EngineKind.LOCAL) }
            .distinctUntilChanged()
            .flatMapLatest { it.sessionDiagnostics }
        viewModelScope.launch {
            engineDiagnosticsFlow.collect { diag ->
                _uiState.update { it.copy(sessionDiagnostics = diag) }
            }
        }
        if (initialConversationId != null) {
            viewModelScope.launch { run.maybeOfferRecovery(initialConversationId) }
            viewModelScope.launch {
                val conversation = container.conversationRepository.load(initialConversationId)
                if (conversation != null) {
                    // 打开历史会话时把上一次的占用带出来，否则状态条要等用户再发一轮才出现。
                    // 只认正数：0 是「引擎没给」，不是「上下文为空」。
                    val lastContext = conversation.messages
                        .lastOrNull { (it.usage?.promptTokens ?: 0) > 0 }
                        ?.usage
                        ?.promptTokens
                    _uiState.update {
                        it.copy(
                            title = conversation.title,
                            messages = conversation.messages,
                            config = conversation.config,
                            contextTokens = lastContext,
                            // 既有计划回填（计划已持久化）：打开会话就要看到时间线，
                            // 而不是等下一次 plan_update 才出现。
                            planSteps = container.agentPlanStore.stepsFor(conversation.id),
                        )
                    }
                }
            }
        }
    }

    /* ------------------------------------------------------------ 输入事件 */

    fun onInputChange(value: String) {
        _uiState.update { it.copy(draftInput = value) }
    }

    fun onAttachImage(uriString: String, name: String) {
        viewModelScope.launch {
            // content:// 只在选择器授权的短时间内可读，必须先落盘到内部文件，
            // 否则引擎侧按“文件路径”读取时会 100% 失败。
            val stored = container.importAttachment(uriString, name)
            _uiState.update { state ->
                state.copy(
                    attachments = state.attachments + Attachment.Image(
                        uri = stored ?: uriString,
                        name = name.ifBlank { "图片" },
                    ),
                )
            }
        }
    }

    fun onAttachAudio(uriString: String, name: String) {
        viewModelScope.launch {
            val stored = container.importAttachment(uriString, name)
            _uiState.update { state ->
                state.copy(
                    attachments = state.attachments + Attachment.Audio(
                        uri = stored ?: uriString,
                        name = name.ifBlank { "音频" },
                    ),
                )
            }
        }
    }

    /**
     * 文本 / Markdown 文件附件（UI-1019 Batch 4）。
     *
     * 读成 `Attachment.Text` 而不是 `Attachment.File`：引擎对 File 是 no-op
     * （`is Attachment.File -> Unit`），Text 才会被内联进提示词 —— 这是内核
     * 定版冻结下让模型真正读到文件内容的唯一路径（见 AppContainer.readTextAttachment）。
     *
     * 读取失败（provider 拒绝 / 空文件 / 编码异常）时**不添加空附件**，只留一条
     * 日志：给用户一个点不开的占位 chip 比静默不添加更糟。
     */
    fun onAttachFile(uriString: String, name: String) {
        viewModelScope.launch {
            val text = container.readTextAttachment(uriString)
            if (text.isNullOrBlank()) {
                AgentLogStore.warn("文件附件读取失败或为空，已跳过：${name.ifBlank { uriString }}")
                return@launch
            }
            _uiState.update { state ->
                state.copy(
                    attachments = state.attachments + Attachment.Text(
                        text = text,
                        name = name.ifBlank { "文件" },
                    ),
                )
            }
        }
    }

    fun onRemoveAttachment(id: String) {
        _uiState.update { state ->
            state.copy(attachments = state.attachments.filterNot { it.key() == id })
        }
    }

    // W84：onParamChange(config) 已删除（零生产调用点 + 整份快照写回；同族收敛见 mergeChatPanelParams）。

    /**
     * 按 transform 改配置并落盘（分段控件 / 开关这类「点一下就定」的交互）。
     *
     * ⚠️ 落盘闭包同样**只改对话页参数面板编辑的键**（W83-09 + R4）：`next` 是由
     * `_uiState.value.config` 派生出来的**整份快照**，直接整份写回会覆盖掉磁盘上
     * 这期间被设置页 / 模型页改过的其他键。收敛键清单见 [mergeChatPanelParams]。
     *
     * ⚠️ 异常边界与 [commitParams] 同款（W84-03 收尾）：`viewModelScope` 不挂
     * `CoroutineExceptionHandler` ⇒ DataStore IO 异常会打死进程。分段控件 / 开关是高频交互，
     * 暴露面不比滑条小。warn 文案与 [commitParams] **逐字一致**，一处 grep 即可取证两处。
     */
    fun onParamChangeWith(transform: (InferenceConfig) -> InferenceConfig) {
        val next = transform(_uiState.value.config).coerce()
        _uiState.update { it.copy(config = next) }
        viewModelScope.launch {
            // ⚠️ 不能用 runCatching：它会把 CancellationException 一起吞掉，
            // 协程以 normal 完成收尾（父 scope 仍取消，但 Job 的 isCancelled /
            // joinAll 语义会漂移）。取消必须透传，只兜真正的 IO 异常。
            try {
                container.settingsRepository.updateInferenceConfig { current ->
                    current.mergeChatPanelParams(next)
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                // 不记 t.message：可能带路径 / 配置内容。
                AgentLogStore.warn("参数落盘失败（${t.javaClass.simpleName}）")
            }
        }
    }

    /**
     * 只改内存里的配置（拖动滑块 / 输入系统提示词时高频调用，不落盘）。
     *
     * ⚠️ 预览值**必须与某个 commit 边界配对**才会落盘：滑条的
     * `onValueChangeFinished`、系统提示词的失焦、抽屉 dismiss、VM onCleared
     * （三边界 flush 设计见 ChatParamsPanel 系统提示词卡片注释）。没有任何边界
     * 触发的预览 = 进程一死即丢失的静默数据丢失（W82-13 的成因）。
     */
    fun onParamPreview(transform: (InferenceConfig) -> InferenceConfig) {
        _uiState.update { it.copy(config = transform(it.config).coerce()) }
    }

    /**
     * 预览的 commit 边界之一：把内存里的配置快照落盘。
     *
     * 调用点：滑条松手（`onValueChangeFinished`）、系统提示词失焦、抽屉 dismiss
     * —— 均走 [viewModelScope]。[onCleared] 的最后一次 flush 走进程级 scope
     * （viewModelScope 彼时已取消），见 [commitParams]。
     */
    fun onParamCommit() {
        commitParams(viewModelScope)
    }

    /**
     * 落盘当前内存配置快照（[onParamCommit] 与 [onCleared] 的 flush 共用同一实现）。
     *
     * @param scope 落盘所用 scope。UI 事件路径传 [viewModelScope]；[onCleared] 必须传
     *   [AppContainer.processScope] —— viewModelScope 在 onCleared 之后已取消，从它
     *   launch 的协程不会执行（W77 落库同款纪律，见 ChatRunCoordinator 构造参数 KDoc）。
     *
     * ## ① 必须自带异常边界（W84-03 / P2-N1）
     *
     * 本方法的两条落盘路径**都没有上游兜底**：`processScope` 是
     * `SupervisorJob() + Dispatchers.IO` 且**不挂** `CoroutineExceptionHandler`，
     * `viewModelScope` 同样没有 ⇒ 根协程里任何未捕获异常直接走默认
     * `UncaughtExceptionHandler`，**整个进程被打死**。DataStore 的 `edit` 会因磁盘满 /
     * 目录不可写 / 序列化失败抛 IO 异常，而 W83-02 把落盘边界从 1 条扩到 4 条（滑条松手 /
     * 系统提示词失焦 / 抽屉 dismiss / onCleared），暴露面同比放大 4 倍。
     *
     * 形态与 `LiquidAgentApplication.onCreate` 的冷启动预热逐字对齐：
     * `runCatching { ... }.onFailure { AgentLogStore.warn("…（${it.javaClass.simpleName}）") }`。
     * **只记异常类名、不记 message** —— message 里可能带文件路径或配置内容。
     * 落盘失败**不回滚 UI**（内存侧即时生效，磁盘真值由仓库 flow 回灌校准，与全仓同款纪律）。
     *
     * ## ② 落盘闭包只改「对话页参数面板真正编辑的键」（W83-09 + R4）
     *
     * 此前闭包直接传整份 `next` 快照。`updateInferenceConfig` 在 `DataStore.edit` 内是
     * **先 decode 磁盘现值、再 transform** —— 传整份快照等于用「调用那一刻」的旧值覆盖
     * 其他键：这段窗口里若设置页改了 `thinking` / `ModelsViewModel` 切了 `backend`，
     * 那次更新会被**静默抹掉**（内存侧即时生效，磁盘被回灌成旧值，用户看到开关自己弹回）。
     * 收敛逻辑与键清单见 [mergeChatPanelParams]（W82-07 已用同一修法处理 `systemInstruction`，
     * 本处是同族残余）。
     */
    private fun commitParams(scope: CoroutineScope) {
        val next = _uiState.value.config
        scope.launch {
            // ⚠️ 不能用 runCatching（会吞 CancellationException），见 [onParamChangeWith]。
            try {
                container.settingsRepository.updateInferenceConfig { current ->
                    current.mergeChatPanelParams(next)
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                // 不记 t.message：可能带路径 / 配置内容。
                AgentLogStore.warn("参数落盘失败（${t.javaClass.simpleName}）")
            }
        }
    }

    fun toggleThinking() {
        _uiState.update { it.copy(thinkingExpanded = !it.thinkingExpanded) }
    }

    fun toggleThinking(messageId: String) {
        _uiState.update {
            val next = it.expandedThinkingIds.toMutableSet()
            if (!next.add(messageId)) next.remove(messageId)
            it.copy(expandedThinkingIds = next)
        }
    }

    /** 展开/收起一个工具过程折叠组（F 项；组默认折叠）。 */
    fun toggleGroup(groupId: String) {
        _uiState.update {
            val next = it.expandedGroupIds.toMutableSet()
            if (!next.add(groupId)) next.remove(groupId)
            it.copy(expandedGroupIds = next)
        }
    }

    fun onDismissError() {
        _uiState.update { it.copy(error = null) }
    }

    /** 关闭自愈提示（G 项 notice）。终态事件会自动清除，手动关闭是提前处置。 */
    fun onDismissNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    /**
     * 关闭「激活态重校验」提示（W68 B2）。提示**每会话至多一次**（频控在
     * [ChatRunCoordinator.activationWarningGate]），关闭后当次会话不再现；换会话复位。
     */
    fun onDismissActiveModelWarning() {
        _uiState.update { it.copy(activeModelWarning = null) }
    }

    /** 用户对当前授权请求做出裁决（授权卡按钮）。 */
    fun onApprovalResult(approved: Boolean) {
        val pending = _uiState.value.pendingApproval ?: return
        pending.decision.complete(
            if (approved) ToolApprovalDecision.APPROVED else ToolApprovalDecision.DENIED,
        )
    }

    /** 恢复卡「丢弃」：journal 改名归档（不删——过程记录里可能有排查需要的东西）。 */
    fun onDiscardRecovery() {
        val offer = _uiState.value.recovery ?: return
        val cid = run.conversationId ?: return
        _uiState.update { it.copy(recovery = null) }
        run.dismissRecovery(offer, cid)
    }

    fun onNewConversation() {
        // run 生命周期复位（runJob / ledgerJob 取消、conversationId 清空、persistState /
        // salvageText 清零、审批缓存撤销、流式通道复位）整体归 Coordinator（与
        // [ChatRunCoordinator.collectRunWithPerfWindow] 首行同为 persistState / salvageText 的
        // 唯一清零点）。
        run.resetForNewConversation()
        // 状态重建见 [ChatUiState.forNewConversation]（W68 B2-R1：必须一并保留
        // activeModelKnownBroken；W84-03：capabilityMode / disclosureMode 同属「独立冷流
        // 字段」，漏掉它们会让权限档位与披露模式静默回落默认值 —— 安全向变宽）。
        _uiState.value = _uiState.value.forNewConversation()
    }

    /* ------------------------------------------------------- run 编排薄转发 */

    /**
     * 以下 run 编排方法整体外提到 [ChatRunCoordinator]（Wave 48 真外提）；此处保留同名 public
     * 薄转发，保证 `ChatScreen` 的 `viewModel::onSend` / `::onStop` / `::onRetry` / `::onRecover`
     * 调用点零改动。
     */
    fun onSend() = run.onSend()
    fun onRetry() = run.onRetry()
    fun onRecover() = run.onRecover()
    fun onStop() = run.onStop()

    override fun onCleared() {
        // W83-02 边界③：VM 销毁前把内存里未落盘的参数预览值（典型：系统提示词
        // 改完还没失焦 / 没关抽屉就离开对话页）落盘。⚠️ 必须走 processScope：
        // viewModelScope 在 onCleared 之后已取消，从它 launch 的协程不会执行
        // （W77 落库同款纪律，见 ChatRunCoordinator 构造参数 KDoc）。
        commitParams(container.processScope)
        // W77：这里原先只 `run.cancel()` —— 裸取消走不到任何终态收口，
        // 半截回答**既不显示也不落库，界面与日志零报错**（报告 B §六 的真发现）。
        // 端侧生成慢，切一次会话/按一次返回的代价是几十秒算力白烧，必须收尾。
        //
        // 改成 onStop() 后语义与「用户点停止」完全一致：flush 缓冲 → 取 partial/thinking
        // 快照 → cancel → resetStreaming → commitAssistant（Cancelled 语义）。
        // ⚠️ 落库必须走 processScope，否则本修复是假的（见 Coordinator 构造参数 KDoc）。
        run.onStop()
        // VM 销毁 = 界面已不存在，正在显示的「生成中」通知必须撤掉：run 协程的
        // CancellationException 被 onFailure 静默吞掉（取消不是错误），不会走到任何
        // 终态收口 —— 不补这一句，生成中退出 App 会残留一条 ongoing、划不掉的通知，
        // 直到下次进聊天页跑完一轮或进程被杀。stop() 幂等。
        container.generationNotifier.stop()
        super.onCleared()
    }
}

/**
 * 主对话 run 的 [AgentPolicy] 构造单点（Wave 42 P2-4：onRecover / onSend / onSendFrom
 * 三处逐行相同的构造收敛为一）。
 *
 * ## agent 会话采样折衷（Wave 19 P1-1 立，Wave 43 真机校准）
 *
 * enableTools 的主对话用低温 + 收窄 topK —— 对齐 gallery agent 任务 TopK=1 的官方
 * 姿态，但保留少量随机性防 token 级循环；重复惩罚由 ModelSamplingProfiles 按模型
 * 下限生效（Wave 20 起 0.17.1 支持），循环兜底由 AgentRunner 轮内检测器负责。
 *
 * ⚠️ **Wave 43 修正**：折衷值（0.4 / 20）此前是**死值**，不区分模型。真机实锤
 * （OPPO PDRM00，Gemma-4 E2B GPU）：档案 temp 1.0/topK 64 的模型被压到 0.4/20 后
 * 输出分布崩塌 —— 连 `<|channel>thought` 的通道名都生成不准（吐出 `<|channel>तरह`），
 * 正文陷入 `[current` n-gram 死锁，连续 3 轮触发轮内重复检测被终止。
 * 对照：`google-ai-edge/gallery` 的 `Consts.kt` 默认值是
 * `DEFAULT_TEMPERATURE = 1.0f / DEFAULT_TOPK = 64 / DEFAULT_TOPP = 0.95f`
 * —— 即**不覆盖模型官方采样口径**，这是它跑 Gemma 系正常的原因。
 *
 * 现口径：**以模型档案为下界采信**（Gemma-4 → 1.0/64 与 Gallery 一致；
 * MiniCPM5-2B 档案 0.5/20 → 0.5/20，Qwen 0.7/20 → 0.7/20），钳在折衷值之上、
 * 1.0 / 64 之内防过热。topP 不动（沿用会话配置，避免多变量）。
 * 回归风险点：档案值高于 0.4 的模型（MiniCPM5 由 0.4 → 0.5）需真机复查。
 *
 * maxRounds 钳 `coerceAtLeast(1)`：防 0 轮配置直接空转。收敛后采样数值
 * （temperature / topK 字面量）全文件只剩函数体这一处，改动采样口径不再需要
 * 三处同步。
 *
 * @param model 当前激活模型（取采样档案用）；null = 无档案可查，退回折衷死值。
 */
internal fun chatAgentPolicy(config: InferenceConfig, model: ModelDescriptor?): AgentPolicy {
    val profile = model?.fileName?.let { ModelSamplingProfiles.forFileName(it) }
    return AgentPolicy(
        maxRounds = config.maxAgentRounds.coerceAtLeast(1),
        agentSamplingOverride = if (config.enableTools) {
            SamplingParams(
                // 档案值在折衷值之上时采信档案（Gemma 系 1.0；无档案维持 0.4 折衷）
                temperature = if (profile == null) {
                    0.4f
                } else {
                    0.4f.coerceAtLeast(profile.recommendedTemperature.coerceAtMost(1.0f))
                },
                topK = if (profile == null) {
                    20
                } else {
                    20.coerceAtLeast(profile.recommendedTopK.coerceAtMost(64))
                },
            )
        } else {
            null
        },
    )
}

/**
 * 「激活态重校验」提示判据式（W68 B2，第十二审 R-2/R-3，纯函数可 JVM 单测）。
 *
 * 判据（写死）：`(fileName ∈ knownBrokenOn) OR (gpuInvolved(backend, visionBackend) ∧
 * fileName ∉ gpuVerifiedFileNames)`。GPU 子句**复用引擎侧组合子**
 * [EngineLoadDegrade.gpuInvolved]（`backend == GPU || visionBackend == GPU`）—— 与引擎
 * 「GPU 文案门控 / 二段降级」同源，避免 UI 与引擎各写一份 GPU 口径而漂移（W69 C2）。
 * 当前 `visionBackend` 无 UI 设置（`resolveVisionBackend` 使其恒等于 backend / null）⇒
 * 行为与旧 `backend == GPU` 逐字等价（零行为变化）。
 *
 *  - 第一子句：`knownBrokenOn` 由 core-data 的 `activeModelKnownBroken`（`fileName` 经
 *    `ModelPresets.findByFileName` **精确匹配**预设后取 `knownBrokenOn`）**预解析**传入 ⇒
 *    非 null 即等价于「该 fileName 命中已知坏签名」。文案必须**原样携带** `knownBrokenOn`
 *    条件词（不得丢语义 —— 用户据此判断是哪一种已知失败）。
 *  - 第二子句：覆盖「文件维度健康但 GPU backend 不可用」面（W66 E4B-GPU 整机重启教训）——
 *    同一文件在 CPU 路径健康、在 GPU 路径却可能 OOM/崩溃；白名单 `gpuVerifiedFileNames`
 *    现恰 4 件（`ModelGpuSupport.kt`）。
 *
 * 返回 `null` = 无提示（正常路径零 UI 变化）；非 null = 上屏提示文案（含「仍可继续，
 * 但可能失败」类语义，**不拦 run** —— 与加载前确认框的「拦下」语义刻意区分）。
 *
 * @param fileName 激活模型下载文件名（判定键）；null / 空串 = 无有效文件名（第二子句
 *   按 `isNullOrEmpty` 判空 —— 空串 `""` 会「∉ 白名单」恒真而**误报**，故一并挡掉，
 *   与 `ModelGpuSupport.isGpuVerified` 对空串返回 false 的语义**刻意区分**：本处空串 =
 *   不提示，非「未验证」）。
 * @param knownBrokenOn core-data 预解析的已知坏签名；null = 该文件无已知坏签名。
 * @param backend 激活模型本次 run 的计算后端（`InferenceConfig.backend`）。
 * @param visionBackend 激活模型本次 run 的视觉后端（`InferenceConfig.visionBackend`，
 *   可为 null）；与 [backend] 一并喂 [EngineLoadDegrade.gpuInvolved]（W69 C2）。
 */
internal fun activationWarningFor(
    fileName: String?,
    knownBrokenOn: String?,
    backend: InferenceBackend,
    visionBackend: InferenceBackend?,
): String? {
    val knownBrokenClause = knownBrokenOn != null
    val gpuClause = EngineLoadDegrade.gpuInvolved(backend, visionBackend) &&
        !fileName.isNullOrEmpty() &&
        !ModelGpuSupport.isGpuVerified(fileName)
    if (!knownBrokenClause && !gpuClause) return null
    val reason = when {
        knownBrokenClause && gpuClause ->
            "已知不可用：$knownBrokenOn；且该文件未列入 GPU 白名单，GPU 路径未经验证"
        knownBrokenClause -> "已知不可用：$knownBrokenOn"
        else -> "该文件未列入 GPU 白名单，GPU 路径未经验证（可能崩溃）"
    }
    // C6 尾句按 gpuClause 分叉：只有 GPU 相关路径才存在「被自动回落 CPU」（引擎保命道
    // DefaultEngineFactory 的 GPU→CPU 兜底）；knownBroken-only（后端非 GPU）没有该语义。
    val tail = if (gpuClause) "仍可继续，但可能失败或被自动回落 CPU。" else "仍可继续，但可能失败。"
    return "$reason。$tail"
}

/**
 * 「每会话至多一次」频控闸（W68 B2，纯逻辑可 JVM 单测）。
 *
 * 语义：一个会话内提示**至多出现一次**（[consumeOnce] 首次返回 true 后恒 false），
 * 用户是否关闭都不再重复；[reset] 只在**换会话**时调用（新会话可再提示一次）。
 * 这样 knownBroken 激活用户不会被每轮 run 反复骚扰（第十二审 R-2 频控要求）。
 *
 * 生命周期由 [ChatRunCoordinator] 持有：换会话（`resetForNewConversation`）复位。
 */
internal class ActivationWarningGate {
    private var shown = false

    /** 本会话是否还应展示提示：首次返回 true 并锁定「已展示」，此后恒 false。 */
    fun consumeOnce(): Boolean {
        if (shown) return false
        shown = true
        return true
    }

    /** 新会话复位（换会话后可再次提示）。 */
    fun reset() {
        shown = false
    }
}

/**
 * 「终态事件 → 诊断卡 + 终止原因」的纯映射（Wave 31 流2 提取，供 JVM 单测）。
 *
 * 把 [AgentEvent.Finished] / [AgentEvent.Failed] 携带的 [BottleneckReport]（诊断卡）与
 * [TerminationReason]（终止原因）落到 [ChatUiState] 的两个字段上；其余事件与两个字段无关，
 * 原样返回。提取成**纯函数**（不触 Compose / Android API / ViewModel）是为了让 feature-chat
 * 这个此前无测试源集的模块也能对「事件 → 状态」这一层做真正的 JVM 单测 —— 见
 * `ChatUiStateTest`。生产路径（[ChatRunCoordinator.handleEvent] 的 Finished / Failed 分支）实际
 * 调用本函数，故测试覆盖的是真实逻辑而非镜像。
 */
internal fun ChatUiState.applyTerminalEvent(event: AgentEvent): ChatUiState = when (event) {
    is AgentEvent.Finished -> copy(
        lastReport = event.report,
        lastTermination = event.terminatedBy,
    )

    is AgentEvent.Failed -> copy(
        lastReport = event.report,
        lastTermination = event.terminatedBy,
    )

    else -> this
}

/**
 * 「新会话」状态重建（W68 B2-R1，严质衡 B2 复审 Finding 1 修复）。
 *
 * [ChatViewModel.onNewConversation] 需要**整体重建** [ChatUiState]（清消息 / 草稿 / 流式 /
 * 上一次的提示卡），只保留跨会话不变的字段。本函数把这层重建提为**纯函数**
 * （不触 Compose / Android API）以便 JVM 单测（`ChatUiStateTest` / `ActivationWarningTest` /
 * `NewConversationStateTest`）。
 *
 * ## 冷流字段必列清单（**同坑已踩两次，第三次前先读这张表**）
 *
 * 要害是同一条：这些字段都由 `init` 里**一次性订阅**的 flow/StateFlow 写回，而
 * 「点新对话」既不改变任何上游、也不重订阅 ⇒ **流不会重发**，重建时若打回默认值，
 * **没有任何人会把真值恢复回来**（要等下一次上游变化，而那次变化可能永不发生）。
 * 每新增一个来自独立流的状态字段，必须在这张表里登记并决定「保留 / 刻意重置」：
 *
 * | 字段 | 上游（订阅点） | 新会话处置 |
 * |---|---|---|
 * | [ChatUiState.config] / [ChatUiState.agentMaxRounds] / [ChatUiState.toolsEnabled] | `settingsRepository.inferenceConfig` | **保留**（参数面板的预览态就在这里） |
 * | [ChatUiState.capabilityMode] | `settingsRepository.capabilityMode` | **保留**（W84-03：漏了它 = 权限档位静默回落） |
 * | [ChatUiState.disclosureMode] | `settingsRepository.disclosureMode` | **保留**（同上） |
 * | [ChatUiState.availableModels] | `modelRepository.models` | **保留** |
 * | [ChatUiState.activeModel] | `settingsRepository.activeModelId` | **保留** |
 * | [ChatUiState.activeModelKnownBroken] | `modelRepository.activeModelKnownBroken`（派生 combine） | **保留**（W68 B2-R1） |
 * | [ChatUiState.sessionDiagnostics] | `engineFactory…sessionDiagnostics`（引擎 StateFlow） | 刻意**重置**（见下） |
 *
 * `sessionDiagnostics` 之所以是唯一例外：它的值由引擎在**会话建成时整体重发**
 * （`LiteRtLmEngine.ensureConversation` 末尾 `_sessionDiagnostics.value = …`），而
 * [ChatViewModel.onNewConversation] 会清空 conversationId ⇒ 下一次 run 必然重建会话 ⇒
 * 必然重发（自愈）。反过来保留它会把**上一会话**的降级结论粘到新会话上，在「还没发第一句」
 * 的窗口里显示一条与当前会话无关的降级小字。
 *
 * ## W84-03（P1，安全向）：为什么 [ChatUiState.capabilityMode] / [ChatUiState.disclosureMode] 必须保留
 *
 * 两者此前**不在**重建参数里 ⇒ 点「新对话」后静默回落构造默认值：
 * `capabilityMode` 从用户选的 `READ_ONLY` 变成 `WORKSPACE_WRITE`（**权限面被悄悄放宽**，
 * 这是安全向回归），`disclosureMode` 回落 `FULL`（工具清单全量进提示词，披露面同时变宽）。
 * 而设置流此刻不重发 ⇒ 界面显示与实际下发**长期不一致**，直到用户去设置页再动一次开关。
 * 本页每次 run 都把这两个字段下发给 `AgentRequest`，所以它们不是「显示态」而是**执行态**。
 *
 * ## 不保留 [ChatUiState.activeModelWarning]（刻意）
 *
 * 换会话须**清掉**上一次的提示卡（回落默认 `null`）—— 用户已看过/已关闭的提示不跨会话残留。
 */
internal fun ChatUiState.forNewConversation(): ChatUiState = ChatUiState(
    config = config,
    // W84-03：独立冷流（capabilityMode / disclosureMode 各一条），新会话不重发 ⇒ 必须沿用上一值。
    capabilityMode = capabilityMode,
    disclosureMode = disclosureMode,
    availableModels = availableModels,
    activeModel = activeModel,
    toolsEnabled = toolsEnabled,
    agentMaxRounds = agentMaxRounds,
    // 要害：派生流在新会话不重发 ⇒ 必须沿用上一值，否则激活态重校验在新会话静默失效。
    activeModelKnownBroken = activeModelKnownBroken,
)

/**
 * 把**对话页参数面板真正编辑的那些键**从 [next]（对话页内存快照）合并进本对象
 * （= `updateInferenceConfig` 闭包里拿到的**磁盘现值**）。
 *
 * ## 为什么需要它（W83-09 + R4：整份快照覆盖写回丢更新）
 *
 * `SettingsRepository.updateInferenceConfig` 在 `DataStore.edit` 内**先 decode 磁盘现值、
 * 再跑 transform**。若 transform 忽略入参、直接返回对话页的整份内存快照（旧形态），
 * 就等于用「对话页最后一次 collect 时」的旧值覆盖所有其他键 —— 这期间设置页改的
 * `thinking`、模型页切的 `backend` 会被静默抹掉（内存侧已生效、磁盘被回灌成旧值，
 * 表现为开关自己弹回）。W82-07 已用「落盘闭包只改本键」修掉 `systemInstruction` 一处，
 * 本函数是**同族残余**的收敛点（对话页侧）。
 *
 * ## 键清单（唯一依据 = `ChatParamsPanel.kt` / `ChatParamsContent` 的交互面）
 *
 * 面板实际能改的键（11 个）：
 *  1. `backend`（计算后端分段控件）
 *  2. `sampling.temperature`
 *  3. `sampling.topP`
 *  4. `sampling.topK`
 *  5. `sampling.repetitionPenalty`
 *  6. `maxTokens`
 *  7. `contextLength`
 *  8. `maxAgentRounds`
 *  9. `thinking`（思考模式分段控件）
 *  10. `systemInstruction`（三边界 flush）
 *  11. `enableTools`（启用工具调用开关）
 *
 * **刻意不写**（面板无任何入口，一律取磁盘现值 [InferenceConfig] 的 `this`）：
 * `sampling.seed`（无 UI）、`visionBackend`、`audioBackend`、`thinkingTokenBudget`、
 * `nativeToolChannel`（设置页专属开关）、`stream`。
 *
 * ⚠️ `sampling` 是**逐字段** copy 而不是 `sampling = next.sampling`：整体替换会把
 * `seed` 一并按对话页的旧值覆盖 —— 那是「按键收敛」自己制造的新丢更新。
 */
internal fun InferenceConfig.mergeChatPanelParams(next: InferenceConfig): InferenceConfig = copy(
    sampling = sampling.copy(
        temperature = next.sampling.temperature,
        topP = next.sampling.topP,
        topK = next.sampling.topK,
        repetitionPenalty = next.sampling.repetitionPenalty,
    ),
    maxTokens = next.maxTokens,
    contextLength = next.contextLength,
    maxAgentRounds = next.maxAgentRounds,
    backend = next.backend,
    thinking = next.thinking,
    systemInstruction = next.systemInstruction,
    enableTools = next.enableTools,
)
