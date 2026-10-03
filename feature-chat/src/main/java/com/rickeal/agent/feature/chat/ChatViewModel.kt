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
import com.rickeal.agent.core.model.Attachment
import com.rickeal.agent.core.model.ChatMessage
import com.rickeal.agent.core.model.EngineKind
import com.rickeal.agent.core.model.InferenceConfig
import com.rickeal.agent.core.model.ModelDescriptor
import com.rickeal.agent.core.model.ModelSamplingProfiles
import com.rickeal.agent.core.model.Role
import com.rickeal.agent.core.model.SamplingParams
import kotlinx.coroutines.CompletableDeferred
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
    val conversationId: String? = null,
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
     * 与 [contextTokens]（引擎回报实测）是**并列的第二口径**，两者**不做换算也不做对账**
     * （[com.rickeal.agent.core.agent.token.RunTokenLedger] KDoc 红线）。它由 AgentRunner
     * 发送侧记账块经 `AgentRequest.tokenLedger` 回写，故在**发送前/首轮**即可给出预估 ——
     * 这是 [contextTokens]（要等引擎回报）给不出的信息。
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
    private val run = ChatRunCoordinator(container, viewModelScope, _uiState, initialConversationId)

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
                            conversationId = conversation.id,
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

    fun onRemoveAttachment(id: String) {
        _uiState.update { state ->
            state.copy(attachments = state.attachments.filterNot { it.key() == id })
        }
    }

    /** 直接替换整份配置（设置页风格的一次性写入）。 */
    fun onParamChange(config: InferenceConfig) {
        _uiState.update { it.copy(config = config.coerce()) }
        viewModelScope.launch { container.settingsRepository.updateInferenceConfig { config } }
    }

    /** 按 transform 改配置并落盘。 */
    fun onParamChangeWith(transform: (InferenceConfig) -> InferenceConfig) {
        val next = transform(_uiState.value.config).coerce()
        _uiState.update { it.copy(config = next) }
        viewModelScope.launch { container.settingsRepository.updateInferenceConfig { next } }
    }

    /**
     * 只改内存里的配置（拖动滑块时高频调用，不落盘）。
     * 与 `onParamCommit()` 配对使用，避免每帧写一次 DataStore。
     */
    fun onParamPreview(transform: (InferenceConfig) -> InferenceConfig) {
        _uiState.update { it.copy(config = transform(it.config).coerce()) }
    }

    fun onParamCommit() {
        val next = _uiState.value.config
        viewModelScope.launch { container.settingsRepository.updateInferenceConfig { next } }
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
        val keep = _uiState.value
        _uiState.value = ChatUiState(
            config = keep.config,
            availableModels = keep.availableModels,
            activeModel = keep.activeModel,
            toolsEnabled = keep.toolsEnabled,
            agentMaxRounds = keep.agentMaxRounds,
        )
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
        run.cancel()
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
