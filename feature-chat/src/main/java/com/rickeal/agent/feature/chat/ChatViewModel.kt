package com.rickeal.agent.feature.chat

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rickeal.agent.core.agent.AgentEvent
import com.rickeal.agent.core.model.AgentLogStore
import com.rickeal.agent.core.agent.AgentPolicy
import com.rickeal.agent.core.agent.AgentRequest
import com.rickeal.agent.core.agent.approval.ToolApprovalDecision
import com.rickeal.agent.core.agent.approval.ToolApprovalHandler
import com.rickeal.agent.core.agent.journal.AgentRunJournal
import com.rickeal.agent.core.agent.plan.PlanStep
import java.io.File
import com.rickeal.agent.core.data.AppContainer
import com.rickeal.agent.core.model.Attachment
import com.rickeal.agent.core.model.ChatMessage
import com.rickeal.agent.core.model.EngineKind
import com.rickeal.agent.core.model.InferenceConfig
import com.rickeal.agent.core.model.ModelDescriptor
import com.rickeal.agent.core.model.RemoteEndpoint
import com.rickeal.agent.core.model.Role
import com.rickeal.agent.core.model.TokenUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

@Immutable
data class ChatUiState(
    val conversationId: String? = null,
    val title: String = "新对话",
    /** 已完成的消息，稳定不变；流式中的那条单独放 streaming* 字段 */
    val messages: List<ChatMessage> = emptyList(),
    val streamingText: String = "",
    val streamingThinking: String = "",
    val streamingRole: Role? = null,
    val isStreaming: Boolean = false,
    val agentRound: Int = 0,
    val agentMaxRounds: Int = 8,
    val draftInput: String = "",
    val attachments: List<Attachment> = emptyList(),
    val config: InferenceConfig = InferenceConfig(),
    val availableModels: List<ModelDescriptor> = emptyList(),
    val activeModel: ModelDescriptor? = null,
    val activeEndpoint: RemoteEndpoint? = null,
    val isGenerating: Boolean = false,
    val error: String? = null,
    val toolsEnabled: Boolean = true,
    /** 流式气泡里「思考过程」是否展开 */
    val thinkingExpanded: Boolean = false,
    val toolTraces: List<ToolTrace> = emptyList(),
    /** 已提交消息里被展开的「思考过程」 */
    val expandedThinkingIds: Set<String> = emptySet(),
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
)

class ChatViewModel(
    private val container: AppContainer,
    initialConversationId: String?,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var runJob: Job? = null
    private var conversationId: String? = initialConversationId

    /**
     * 审批通道：把「危险/需确认工具的执行前裁决」挂起到用户点击为止。
     * fail-closed 的另一半在这里——run 取消时 `deferred.await()` 随协程取消，
     * 不需要超时兜底；弹窗未响应期间 run 挂起是**设计行为**（人在回路）。
     */
    private val approvalHandler = ToolApprovalHandler { call, spec ->
        val deferred = CompletableDeferred<ToolApprovalDecision>()
        _uiState.update {
            it.copy(
                pendingApproval = PendingApproval(
                    callId = call.id,
                    toolName = spec.name,
                    arguments = call.argumentsJson,
                    decision = deferred,
                ),
            )
        }
        try {
            deferred.await()
        } finally {
            _uiState.update { it.copy(pendingApproval = null) }
        }
    }

    init {
        viewModelScope.launch {
            container.conversationRepository.refresh()
            container.modelRepository.refresh()
            container.endpointRepository.refresh()
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
        viewModelScope.launch {
            container.settingsRepository.activeEndpointId.collect { id ->
                _uiState.update { it.copy(activeEndpoint = container.endpointRepository.find(id)) }
            }
        }
        if (initialConversationId != null) {
            viewModelScope.launch { maybeOfferRecovery(initialConversationId) }
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

    fun onDismissError() {
        _uiState.update { it.copy(error = null) }
    }

    /** 用户对当前授权请求做出裁决（授权卡按钮）。 */
    fun onApprovalResult(approved: Boolean) {
        val pending = _uiState.value.pendingApproval ?: return
        pending.decision.complete(
            if (approved) ToolApprovalDecision.APPROVED else ToolApprovalDecision.DENIED,
        )
    }

    /** 打开会话后扫描 journal：有「没跑完就被进程死亡打断」的 run 就出恢复卡。 */
    private suspend fun maybeOfferRecovery(cid: String) {
        val dir = File(container.journalRoot, cid)
        val unsettled = withContext(Dispatchers.IO) {
            runCatching { AgentRunJournal.findUnsettled(dir) }.getOrNull()
        } ?: return
        _uiState.update {
            it.copy(recovery = RecoveryOffer(runId = unsettled.runId, messageCount = unsettled.messageCount))
        }
    }

    /**
     * 从中断处继续（恢复卡「继续」按钮）。
     *
     * journal 里的已提交消息重建出完整上下文（含工具调用与结果——这些**不在**会话文件的
     * 可见消息里，只有 journal 有），从崩溃点继续推理：
     *  - 末条是 USER → 那条就是被打断的输入，直接作为 userInput（不重复落库）；
     *  - 否则合成一条「继续」输入（对用户可见并落库，与 onSend 行为一致）。
     */
    fun onRecover() {
        val state = _uiState.value
        if (state.isGenerating) return
        val offer = state.recovery ?: return
        val cid = conversationId ?: return
        _uiState.update { it.copy(recovery = null, error = null, toolTraces = emptyList()) }
        runJob?.cancel()
        runJob = viewModelScope.launch {
            val journal = AgentRunJournal.open(File(container.journalRoot, cid), offer.runId)
            val committed = withContext(Dispatchers.IO) { journal.committedMessagesSync() }
            if (committed.isEmpty()) {
                journal.markDismissed()
                return@launch
            }
            val last = committed.last()
            val userMessage: ChatMessage
            val history: List<ChatMessage>
            if (last.role == Role.USER) {
                history = committed.dropLast(1)
                userMessage = last
            } else {
                history = committed
                userMessage = ChatMessage(
                    role = Role.USER,
                    text = "请从上次中断的地方继续未完成的任务，不要重复已完成的工作。",
                )
            }
            _uiState.update {
                it.copy(
                    messages = if (it.messages.none { m -> m.id == userMessage.id }) {
                        it.messages + userMessage
                    } else {
                        it.messages
                    },
                    streamingText = "",
                    streamingThinking = "",
                    streamingRole = Role.MODEL,
                    isStreaming = true,
                    isGenerating = true,
                    agentRound = 0,
                )
            }
            if (last.role != Role.USER) {
                container.conversationRepository.appendMessage(cid, userMessage)
            }
            val config = _uiState.value.config
            val endpoint = if (config.engineKind == EngineKind.REMOTE) {
                container.endpointRepository.find(config.remoteEndpointId)
                    ?: _uiState.value.activeEndpoint
            } else {
                null
            }
            val request = AgentRequest(
                conversationId = cid,
                history = history,
                userInput = userMessage,
                config = config,
                model = _uiState.value.activeModel,
                endpoint = endpoint,
                policy = AgentPolicy(maxRounds = config.maxAgentRounds.coerceAtLeast(1)),
                journal = journal,
                memoryText = runCatching { container.agentMemory.renderForPrompt() }.getOrNull(),
                planStore = container.agentPlanStore,
                approvalHandler = approvalHandler,
            )
            runCatching {
                container.agentRunner.run(request).collect { event -> handleEvent(event, cid) }
            }.onFailure { throwable ->
                // 取消不是错误（与 onSend/onRetry 同一约定）
                if (throwable is CancellationException) return@onFailure
                _uiState.update {
                    it.copy(
                        isStreaming = false,
                        isGenerating = false,
                        error = throwable.message?.let { m -> AgentLogStore.sanitizeUserFacing(m) }
                            ?: "生成失败",
                    )
                }
            }
        }
    }

    /** 恢复卡「丢弃」：journal 改名归档（不删——过程记录里可能有排查需要的东西）。 */
    fun onDiscardRecovery() {
        val offer = _uiState.value.recovery ?: return
        val cid = conversationId ?: return
        _uiState.update { it.copy(recovery = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val journal = AgentRunJournal.open(File(container.journalRoot, cid), offer.runId)
            journal.markDismissed()
        }
    }

    fun onNewConversation() {
        runJob?.cancel()
        runJob = null
        conversationId = null
        val keep = _uiState.value
        _uiState.value = ChatUiState(
            config = keep.config,
            availableModels = keep.availableModels,
            activeModel = keep.activeModel,
            activeEndpoint = keep.activeEndpoint,
            toolsEnabled = keep.toolsEnabled,
            agentMaxRounds = keep.agentMaxRounds,
        )
    }

    /**
     * 用户点「停止」。
     *
     * 顺序很关键：**先取快照，再取消**。`runJob.cancel()` 之后协程进入取消态，流不会再吐
     * 事件、`streamingText` 也不会再更新；先取消再读，用户等了半天的半截回答就凭空消失了。
     *
     * 落库统一走 [commitAssistant]：取消与 `AgentEvent.Cancelled` 谁先到是不确定的竞态，
     * 两条路径都会提交同样的文本，靠它按「最后一条 MODEL 且文本相同」去重，不会写两份。
     * 只提交文本、不带 thinking，与 `Cancelled` 分支保持一致 —— 否则同一个操作会因为竞态
     * 胜负不同而存出不同的东西。
     */
    fun onStop() {
        val partial = _uiState.value.streamingText
        runJob?.cancel()
        runJob = null
        _uiState.update {
            it.copy(
                streamingText = "",
                streamingThinking = "",
                isStreaming = false,
                isGenerating = false,
            )
        }
        if (partial.isNotBlank()) {
            // conversationId 为空说明首轮的用户消息都还没落库（极窄窗口），此时没有可写入的
            // 会话，不提交，避免出现「界面有气泡但历史里没有」的假象。
            conversationId?.let { commitAssistant(partial, null, null, it) }
        }
    }

    fun onSend() {
        val state = _uiState.value
        // 闸门统一用 isGenerating，与 onRetry() 一致（原来这里是 isStreaming）：
        // 工具执行阶段 isStreaming 会回落（没在吐 token）而 isGenerating 仍为 true，
        // 用 isStreaming 当闸门就放行第二次 run —— 两个 run 抢同一个引擎实例，
        // 后一个 close() 掉前一个正在用的 LiteRT Conversation → native SIGSEGV 闪退。
        if (state.isGenerating) return
        val text = state.draftInput
        if (text.isBlank() && state.attachments.isEmpty()) return

        val userMessage = ChatMessage(
            role = Role.USER,
            text = text,
            attachments = state.attachments,
        )
        val history = state.messages + userMessage
        _uiState.update {
            it.copy(
                messages = history,
                draftInput = "",
                attachments = emptyList(),
                streamingText = "",
                streamingThinking = "",
                streamingRole = Role.MODEL,
                isStreaming = true,
                isGenerating = true,
                agentRound = 0,
                toolTraces = emptyList(),
                thinkingExpanded = false,
                error = null,
            )
        }
        // 覆盖 runJob 之前必须先取消旧的：core-agent 侧已有 runMutex 根治并发，
        // 这里是第二层 —— 少这一行就是「两个 run 抢同一个引擎实例」的入口。
        runJob?.cancel()
        runJob = viewModelScope.launch {
            val cid = ensureConversation(firstUserText(history))
            container.conversationRepository.appendMessage(cid, userMessage)
            val config = _uiState.value.config
            val endpoint = if (config.engineKind == EngineKind.REMOTE) {
                container.endpointRepository.find(config.remoteEndpointId)
                    ?: _uiState.value.activeEndpoint
            } else {
                null
            }
            // 每次 run 一个 journal 文件：进程被杀后可从「已完成轮次」继续
            // （core-agent/journal；写入 best-effort，失败不影响 run 本身）。
            val journal = AgentRunJournal.open(
                runDir = File(container.journalRoot, cid),
                runId = "run_" + System.currentTimeMillis(),
            )
            val request = AgentRequest(
                conversationId = cid,
                history = history,
                userInput = userMessage,
                config = config,
                model = _uiState.value.activeModel,
                endpoint = endpoint,
                policy = AgentPolicy(maxRounds = config.maxAgentRounds.coerceAtLeast(1)),
                journal = journal,
                // 长期记忆片段（harness-memory 移植）：读失败按无记忆处理，绝不挡发送
                memoryText = runCatching { container.agentMemory.renderForPrompt() }.getOrNull(),
                planStore = container.agentPlanStore,
                approvalHandler = approvalHandler,
            )
            runCatching {
                container.agentRunner.run(request).collect { event -> handleEvent(event, cid) }
            }.onFailure { throwable ->
                // 取消不是错误：onStop() / onNewConversation() 会 cancel 这个协程，而 runCatching
                // 把 CancellationException 也一起捕获了。不挡掉的话，用户点「停止」或「新对话」
                // 之后会莫名其妙弹出一条「生成失败」—— 明明是他自己主动取消的。
                if (throwable is CancellationException) return@onFailure
                _uiState.update {
                    it.copy(
                        isStreaming = false,
                        isGenerating = false,
                        // 异常消息是自由文本，可能整段带上请求头 / 带凭据的 URL
                        // （远程引擎失败时尤其常见），上屏前必须过一遍脱敏。
                        error = throwable.message?.let { AgentLogStore.sanitizeUserFacing(it) }
                            ?: "生成失败",
                    )
                }
            }
        }
    }

    /** 重跑最后一轮：截掉最后一条用户消息之后的所有内容再生成。 */
    fun onRetry() {
        val state = _uiState.value
        if (state.isGenerating) return
        val lastUserIndex = state.messages.indexOfLast { it.role == Role.USER }
        if (lastUserIndex < 0) return
        val trimmed = state.messages.take(lastUserIndex + 1)
        _uiState.update {
            it.copy(
                messages = trimmed,
                error = null,
                streamingText = "",
                streamingThinking = "",
                toolTraces = emptyList(),
            )
        }
        onSendFrom(trimmed[lastUserIndex], trimmed)
    }

    private fun onSendFrom(userMessage: ChatMessage, history: List<ChatMessage>) {
        // 与 onSend() / onRetry() 同一道闸门。它现在是 private、只被 onRetry() 调，
        // 但 onRetry() 自己也在改状态之后才调过来（中间有 _uiState.update 的间隙），
        // 而这里才是真正起 runJob 的地方 —— 闸门放在真正启动的那一处才拦得住。
        if (_uiState.value.isGenerating) return
        _uiState.update {
            it.copy(
                messages = history,
                streamingText = "",
                streamingThinking = "",
                streamingRole = Role.MODEL,
                isStreaming = true,
                isGenerating = true,
                agentRound = 0,
                toolTraces = emptyList(),
                error = null,
            )
        }
        // 同上：覆盖 runJob 之前先取消旧的，绝不让两个 run 同时活着。
        runJob?.cancel()
        runJob = viewModelScope.launch {
            val cid = ensureConversation(firstUserText(history))
            val config = _uiState.value.config
            val endpoint = if (config.engineKind == EngineKind.REMOTE) {
                container.endpointRepository.find(config.remoteEndpointId)
                    ?: _uiState.value.activeEndpoint
            } else {
                null
            }
            // 每次 run 一个 journal 文件：进程被杀后可从「已完成轮次」继续
            // （core-agent/journal；写入 best-effort，失败不影响 run 本身）。
            val journal = AgentRunJournal.open(
                runDir = File(container.journalRoot, cid),
                runId = "run_" + System.currentTimeMillis(),
            )
            val request = AgentRequest(
                conversationId = cid,
                history = history,
                userInput = userMessage,
                config = config,
                model = _uiState.value.activeModel,
                endpoint = endpoint,
                policy = AgentPolicy(maxRounds = config.maxAgentRounds.coerceAtLeast(1)),
                journal = journal,
                // 长期记忆片段（harness-memory 移植）：读失败按无记忆处理，绝不挡发送
                memoryText = runCatching { container.agentMemory.renderForPrompt() }.getOrNull(),
                planStore = container.agentPlanStore,
                approvalHandler = approvalHandler,
            )
            runCatching {
                container.agentRunner.run(request).collect { event -> handleEvent(event, cid) }
            }.onFailure { throwable ->
                // 取消不是错误：onStop() / onNewConversation() 会 cancel 这个协程，而 runCatching
                // 把 CancellationException 也一起捕获了。不挡掉的话，用户点「停止」或「新对话」
                // 之后会莫名其妙弹出一条「生成失败」—— 明明是他自己主动取消的。
                if (throwable is CancellationException) return@onFailure
                _uiState.update {
                    it.copy(
                        isStreaming = false,
                        isGenerating = false,
                        // 异常消息是自由文本，可能整段带上请求头 / 带凭据的 URL
                        // （远程引擎失败时尤其常见），上屏前必须过一遍脱敏。
                        error = throwable.message?.let { AgentLogStore.sanitizeUserFacing(it) }
                            ?: "生成失败",
                    )
                }
            }
        }
    }

    /* -------------------------------------------------------------- 内部 */

    private fun handleEvent(event: AgentEvent, conversationId: String) {
        when (event) {
            is AgentEvent.RoundStarted -> _uiState.update {
                it.copy(agentRound = event.round, agentMaxRounds = event.maxRounds)
            }

            // 引擎损坏后重建并重试本轮：必须清空流式文本。
            // 否则第二轮的 delta 会追加到第一轮的半截输出之后，用户看到两段拼在一起。
            is AgentEvent.Retrying -> _uiState.update {
                it.copy(streamingText = "", streamingThinking = "")
            }

            // 工具审批请求（Wave 1 无 UI 通道 → 默认拒绝；fail-closed 与主循环行为一致）。
            // 现在落成一条工具轨迹让用户「看得见发生了什么」；弹窗交互属于 Wave 2。
            is AgentEvent.ApprovalRequested -> _uiState.update { state ->
                state.copy(
                    toolTraces = state.toolTraces + ToolTrace(
                        id = event.call.id,
                        name = event.spec.name,
                        arguments = event.call.argumentsJson,
                        status = ToolTraceStatus.SKIPPED,
                        result = "需要授权后才会执行（当前未接审批 UI，已按拒绝处理）",
                    ),
                )
            }

            // 计划变化（plan_set / plan_update）：UI 渲染时间线。同一轮内逐次更新。
            is AgentEvent.PlanUpdated -> _uiState.update {
                it.copy(planSteps = event.steps)
            }

            is AgentEvent.TextDelta -> _uiState.update {
                it.copy(streamingText = it.streamingText + event.text)
            }

            is AgentEvent.ThinkingDelta -> _uiState.update {
                it.copy(streamingThinking = it.streamingThinking + event.text)
            }

            is AgentEvent.ToolCallStarted -> {
                val call = event.call
                _uiState.update { state ->
                    state.copy(
                        toolTraces = state.toolTraces + ToolTrace(
                            id = call.id,
                            name = call.name,
                            arguments = call.argumentsJson,
                        ),
                    )
                }
            }

            is AgentEvent.ToolResultReceived -> {
                val result = event.result
                _uiState.update { state ->
                    state.copy(
                        toolTraces = state.toolTraces.map { trace ->
                            if (trace.id == result.callId || (trace.result == null && trace.name == result.name)) {
                                trace.copy(
                                    status = if (result.ok) ToolTraceStatus.OK else ToolTraceStatus.FAILED,
                                    result = result.output.ifBlank { result.errorMessage.orEmpty() },
                                    elapsedMillis = result.elapsedMillis,
                                )
                            } else {
                                trace
                            }
                        },
                    )
                }
            }

            is AgentEvent.ToolSkipped -> _uiState.update { state ->
                state.copy(
                    toolTraces = state.toolTraces.map { trace ->
                        if (trace.id == event.call.id) {
                            trace.copy(status = ToolTraceStatus.SKIPPED, result = event.reason)
                        } else {
                            trace
                        }
                    },
                )
            }

            is AgentEvent.MessageCommitted -> {
                // 注意：AgentRunner 在终态还会再发一次 Finished，两条路径都会落库，
                // 会导致「同一条回答被提交两次」（UI 双气泡 + 会话文件两份）。
                // 这里只负责把它渲染进消息列表（commit 会去重），落库统一交给 Finished。
                val already = _uiState.value.messages.any { it.id == event.message.id }
                if (!already) {
                    _uiState.update { it.copy(messages = it.messages + event.message) }
                }
            }

            is AgentEvent.Finished -> {
                val text = event.text.ifBlank { _uiState.value.streamingText }
                val thinking = _uiState.value.streamingThinking.ifBlank { null }
                if (text.isNotBlank() || thinking != null) {
                    commitAssistant(text, thinking, event.usage, conversationId)
                }
                // 本次请求的 prompt 规模 = 当前上下文占用。只有拿到正数才覆盖：
                // 0 / null 表示「这次引擎没给这个字段」，用它覆盖会把上一次的真实占用抹成 0，
                // 状态条会莫名其妙消失 —— 保留旧值比显示 0 更接近事实。
                val promptTokens = event.usage?.promptTokens ?: 0
                _uiState.update {
                    it.copy(
                        streamingText = "",
                        streamingThinking = "",
                        isStreaming = false,
                        isGenerating = false,
                        toolTraces = emptyList(),
                        contextTokens = if (promptTokens > 0) promptTokens else it.contextTokens,
                    )
                }
            }

            is AgentEvent.Failed -> _uiState.update {
                // 同上：AgentEvent.Failed 的 message 可能带着远程端点的 URL / 请求头。
                it.copy(
                    isStreaming = false,
                    isGenerating = false,
                    error = AgentLogStore.sanitizeUserFacing(event.message),
                )
            }

            is AgentEvent.Cancelled -> {
                val partial = event.partialText.ifBlank { _uiState.value.streamingText }
                if (partial.isNotBlank()) {
                    commitAssistant(partial, null, null, conversationId)
                }
                _uiState.update {
                    it.copy(
                        streamingText = "",
                        streamingThinking = "",
                        isStreaming = false,
                        isGenerating = false,
                    )
                }
            }
        }
    }

    private fun commit(message: ChatMessage, conversationId: String) {
        _uiState.update { state -> state.copy(messages = state.messages + message) }
        viewModelScope.launch {
            container.conversationRepository.appendMessage(conversationId, message)
        }
    }

    /**
     * 提交一条助手回答，并**保证同一条回答不会被提交两次**。
     *
     * 为什么要去重：`AgentRunner` 在终态先 emit `MessageCommitted`（那条消息带着它自己生成的
     * id），紧接着再 emit `Finished`；而 `Finished` 只给文本，UI 只能新建 `ChatMessage`——它的
     * id 是 `newId()` 随机生成的，所以「按 id 去重」永远命中不了，结果是**两个一模一样的助手
     * 气泡**，会话文件里也写了两条。
     *
     * 判据用「最后一条消息 role == MODEL 且 text 相同」，而不是 id（id 每次都变）。
     * 只看**最后一条**而不是全表：用户完全可能连着两轮拿到同样的回答，全局去重会吞掉第二条。
     *
     * 命中时**不重新落库**：`ConversationRepository.appendMessage` 是无条件追加（不按 id 覆盖），
     * 再 append 一次等于把重复记录写进历史，正好是要修的那个问题。正常路径上也没有需要回填的
     * 字段 —— `MessageCommitted` 那条已经带着 usage 与 finishReason。
     *
     * 同样的判据也服务「点停止」与 `Cancelled` 事件的竞态：两条路径提交的文本相同，先到的那条
     * 生效，后到的那条被挡下。
     *
     * ## ⚠️ 这个判据成立的前提（改动 `AgentRunner` 之前务必先读这段）
     *
     * 「最后一条 MODEL 且 text 相同」之所以够用，是因为**一次 `run()` 内 `MessageCommitted`
     * 只会 emit 一次** —— 只有「模型给出最终答案」那条分支会发（`AgentRunner` 里
     * `working.add(committed)` 之后那一处），所以 `messages.lastOrNull()` 若已是 MODEL 消息，
     * 它必然就是刚被提交的那条，不可能是一条无关的历史回答。
     *
     * **如果将来有人在中间轮也 emit `MessageCommitted`（例如每轮落一条中间消息），这个前提就
     * 没了**：那时「最后一条 MODEL 且 text 相同」有可能撞上一条**合法的、独立的**回答，把它误吞掉
     * —— 表现为「模型答了但界面/历史里没有」，而且不会有任何报错。
     *
     * 届时的正确做法是换一个判据：在 ViewModel 里记住最近一次 `MessageCommitted` 的
     * `event.message.id`（例如 `private var lastCommittedId: String?`），在 `Finished` 里用
     * 「`messages.lastOrNull()?.id == lastCommittedId`」判断是否已经提交过。id 是精确的，
     * 不依赖任何关于「谁排在最后」的假设。
     */
    private fun commitAssistant(
        text: String,
        thinking: String?,
        usage: TokenUsage?,
        conversationId: String,
    ) {
        val last = _uiState.value.messages.lastOrNull()
        if (last != null && last.role == Role.MODEL && last.text == text) return
        commit(
            ChatMessage(role = Role.MODEL, text = text, thinking = thinking, usage = usage),
            conversationId,
        )
    }

    private suspend fun ensureConversation(firstUserText: String): String {
        val existing = conversationId
        if (existing != null) return existing
        val title = firstUserText.trim().take(24).ifBlank { "新对话" }
        val created = container.conversationRepository.create(title = title)
        conversationId = created.id
        _uiState.update { it.copy(conversationId = created.id, title = created.title) }
        return created.id
    }

    private fun firstUserText(history: List<ChatMessage>): String =
        history.firstOrNull { it.role == Role.USER }?.text.orEmpty()

    override fun onCleared() {
        runJob?.cancel()
        super.onCleared()
    }
}
