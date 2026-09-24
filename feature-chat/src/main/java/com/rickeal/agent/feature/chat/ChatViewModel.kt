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
import com.rickeal.agent.core.agent.history.TurnFold
import com.rickeal.agent.core.agent.history.TurnState
import com.rickeal.agent.core.agent.plan.PlanStep
import java.io.File
import com.rickeal.agent.core.data.AppContainer
import com.rickeal.agent.core.model.Attachment
import com.rickeal.agent.core.model.ChatMessage
import com.rickeal.agent.core.model.InferenceConfig
import com.rickeal.agent.core.model.ModelDescriptor
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
private const val STREAM_FLUSH_INTERVAL_MS = 120L

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
)

class ChatViewModel(
    private val container: AppContainer,
    initialConversationId: String?,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /** 流式通道（A 项节流）：高频字段独立流，token 变化不打挂大 StateFlow 的订阅者。 */
    private val _streaming = MutableStateFlow(StreamingState())
    val streaming: StateFlow<StreamingState> = _streaming.asStateFlow()

    /** 字符缓冲：delta 进缓冲，flush 循环 120ms 收敛一次才真正进 [StreamingState]。 */
    private val textBuffer = StringBuilder()
    private val thinkingBuffer = StringBuilder()
    private var flushJob: Job? = null

    /** 实时指标时间戳：run 启动 / 首个可见 token（TTFT 计算的两端）。 */
    private var runStartedAtMillis: Long? = null
    private var firstTokenAtMillis: Long? = null

    /** token 缓冲收敛进状态流（120ms 一次）。缓冲排空循环自然退出，不常驻。 */
    private fun ensureFlushLoop() {
        if (flushJob?.isActive == true) return
        flushJob = viewModelScope.launch {
            try {
                while (textBuffer.isNotEmpty() || thinkingBuffer.isNotEmpty()) {
                    kotlinx.coroutines.delay(STREAM_FLUSH_INTERVAL_MS)
                    flushNow()
                    updateStreamingUsage()
                }
            } finally {
                flushJob = null
            }
        }
    }

    /** 同步排空缓冲到状态流（终态收尾用：保证「半截回答不凭空消失」）。 */
    private fun flushNow() {
        val t = textBuffer.toString()
        val th = thinkingBuffer.toString()
        textBuffer.setLength(0)
        thinkingBuffer.setLength(0)
        if (t.isNotEmpty() || th.isNotEmpty()) {
            _streaming.update { it.copy(text = it.text + t, thinking = it.thinking + th) }
        }
    }

    /** 流式实时指标（E 项）：TTFT 精确；tps 用字符数粗估（中英混合按 2 字符/token）。 */
    private fun updateStreamingUsage() {
        val started = runStartedAtMillis ?: return
        val first = firstTokenAtMillis ?: return
        val decodeMs = (System.currentTimeMillis() - first).coerceAtLeast(1)
        val estimatedTokens = _streaming.value.text.length / 2
        val tps = estimatedTokens * 1000f / decodeMs
        _streaming.update { it.copy(usage = StreamingUsage(ttftMillis = first - started, tokensPerSecond = tps)) }
    }

    /** 清空流式文本与缓冲（Retrying/Failed：失败尝试不落库，重新来）。 */
    private fun resetStreamingText() {
        textBuffer.setLength(0)
        thinkingBuffer.setLength(0)
        _streaming.update { it.copy(text = "", thinking = "", usage = null) }
    }

    /** 完全复位流式通道（run 启动/终态收尾）。 */
    private fun resetStreaming(role: Role? = Role.MODEL, isStreaming: Boolean = false) {
        textBuffer.setLength(0)
        thinkingBuffer.setLength(0)
        runStartedAtMillis = null
        firstTokenAtMillis = null
        _streaming.value = StreamingState(role = role, isStreaming = isStreaming)
    }

    private var runJob: Job? = null
    private var conversationId: String? = initialConversationId

    /** 当前 run 的 journal（D 项回合归档用）：run 启动时置，onNewConversation 清。 */
    private var currentJournal: AgentRunJournal? = null

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
            // 身份校验（r1 审查遗留）：取消瞬间可能已有新审批入位，旧协程的
            // finally 不能误清新卡 —— 只清自己的那张（同 deferred 引用比对）。
            _uiState.update {
                if (it.pendingApproval?.decision === deferred) {
                    it.copy(pendingApproval = null)
                } else {
                    it
                }
            }
        }
    }

    /** 授权卡「相同调用不再询问」：写入审批缓存（TTL 30min，同参免再弹卡）。 */
    fun onApprovalRememberForSession() {
        val pending = _uiState.value.pendingApproval ?: return
        container.toolApprovalCache.grant(
            toolName = pending.toolName,
            argsDigest = com.rickeal.agent.core.agent.approval.ToolApprovalCache.argsDigest(pending.arguments),
            conversationId = conversationId,
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

    /**
     * 回合归档（D 项 history_v2）：journal 折叠成 TurnRecord → 正文进内容寻址池 →
     * 回合记录落 segments.jsonl → journal 改名 .archived 退出恢复扫描。
     * 硬顺序：**先 commitTurn 后归档**（反序窗口 = 回合记录与 journal 双双丢失）。
     * 全链 best-effort：任何失败只记日志，绝不影响终态处理。
     * archive 参数：onStop 路径传 false —— cancel 时 journal 的 NonCancellable 收尾
     * 可能还没写完，此时 rename 会产生幽灵文件；只记 TurnRecord，归档留给终态路径。
     */
    private fun archiveTurnNow(
        state: TurnState,
        termination: String?,
        cid: String?,
        archive: Boolean,
    ) {
        val journal = currentJournal ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                // 走容器级按会话池（外部审查报告2 §4.1）：此前每次 open() 新实例，
                // 实例级 commitMutex 在四个并发归档入口之间互不相干 —— 锁失效。
                val store = container.historyStore(cid ?: return@runCatching)
                val record = TurnFold.fromJournal(
                    pool = store.pool,
                    journal = journal,
                    state = state,
                    termination = termination,
                ) ?: return@runCatching
                store.commitTurn(record)
                if (archive) journal.archiveAsSettled()
            }
        }
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
     * 把目录里除 [keepRunId] 之外的所有「未 settled」journal 归档。
     *
     * findUnsettled 只返回最近的一个，其余未完成 run 会永远滞留 —— 每次进会话都
     * 再弹一张恢复卡，用户处置完最新的又来一张。归档（改名）而非删除：过程记录
     * 可能还有排查价值；已 settled 的正常 run 与空文件不动。
     */
    private suspend fun archiveOtherUnsettled(runDir: File, keepRunId: String) = withContext(Dispatchers.IO) {
        runCatching {
            val files = runDir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") } ?: return@runCatching
            for (file in files) {
                val name = file.name
                if (name == "$keepRunId.jsonl") continue
                if (name.endsWith(".dismissed.jsonl") || name.endsWith(".jsonl.dismissed")) continue
                val lines = runCatching {
                    AgentRunJournal.open(runDir, name.removeSuffix(".jsonl")).readLines()
                }.getOrDefault(emptyList())
                if (lines.isEmpty() || lines.last().kind == AgentRunJournal.KIND_SETTLED) continue
                runCatching {
                    file.renameTo(File(file.parentFile, file.nameWithoutExtension + ".dismissed.jsonl"))
                }
            }
        }.getOrDefault(Unit)
    }

    /**
     * 处置当前恢复卡（丢弃或被新 run 顶替）：归档目标 journal + 其余未完成 run。
     *
     * 「被新 run 顶替」是 Wave3 补的口子：恢复卡挂着时用户直接发了新消息 = 用行动
     * 表示「不恢复」，卡必须归档 —— 不然它留在状态里，下次进会话又弹出来，而且
     * 那份 journal 的 user_input 已经过时（上下文会拼出新 run 之前的状态）。
     */
    private fun dismissRecovery(offer: RecoveryOffer, cid: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val dir = File(container.journalRoot, cid)
                AgentRunJournal.open(dir, offer.runId).markDismissed()
                archiveOtherUnsettled(dir, offer.runId)
            }
        }
    }

    /**
     * 从中断处继续（恢复卡「继续」按钮）。
     *
     * Wave3 修复的完整重建：journal 现在记录三类行 —— user_input（任务输入）、
     * message（过程消息：工具调用/结果/中间输出）、settled（终态）。恢复上下文 =
     * 会话可见历史（USER/MODEL 往来，去掉与本 run 任务输入重复的那条）+ journal
     * 过程消息（工具调用与结果**只有 journal 有**，会话文件里没有）按序拼接，任务
     * 输入本身作为 userInput 传入。Wave2 只拼 journal 过程消息：任务描述、此前
     * 会话轮次全部丢失，4B 模型是对着工具残骸盲猜。
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
            currentJournal = journal
            val savedInput = withContext(Dispatchers.IO) { journal.readUserInputSync() }
            val committed = withContext(Dispatchers.IO) { journal.committedMessagesSync() }
            if (committed.isEmpty() && savedInput == null) {
                withContext(Dispatchers.IO) { journal.markDismissed() }
                archiveOtherUnsettled(File(container.journalRoot, cid), offer.runId)
                return@launch
            }
            val userMessage: ChatMessage
            val history: List<ChatMessage>
            if (savedInput != null) {
                userMessage = savedInput
                // 会话可见历史里去掉与本 run 任务输入同 id 的那条（它由 userInput 参数
                // 承担，避免上下文双份）。恢复 run 续写场景（同 journal 文件）里，上一次
                // 合成的「继续」消息也是同一条（同 id），同样被去掉 —— 它会作为 userInput
                // 重新出现在正确位置（所有过程消息之后）。
                val visible = state.messages.filterNot { it.id == savedInput.id }
                // 过程消息去重：恢复 run 曾走完过一次的话，其最终回答同时落在会话文件
                // （commitAssistant 落库）与 journal（message 行）里。判据与 commitAssistant
                // 一致用 role+text —— journal decode 出来的 id 与 UI 侧重新生成的不同，
                // 按 id 去重在这里永远命中不了。只剔 MODEL：TOOL / 中间 toolCall 消息
                // 永远不会出现在会话文件里。
                val visibleKeys = visible.mapTo(HashSet()) { it.role.name + "|" + it.text }
                val proc = committed.filterNot { m ->
                    m.role == Role.MODEL && (m.role.name + "|" + m.text) in visibleKeys
                }
                history = visible + proc
            } else {
                // 升级前的旧 journal（没有 user_input 行）：退化到合成「继续」输入，
                // 尽力而为 —— 至少过程消息（工具调用/结果）还在。
                userMessage = ChatMessage(
                    role = Role.USER,
                    text = "请从上次中断的地方继续未完成的任务，不要重复已完成的工作。",
                )
                history = committed
            }
            _uiState.update {
                it.copy(
                    messages = if (it.messages.none { m -> m.id == userMessage.id }) {
                        it.messages + userMessage
                    } else {
                        it.messages
                    },
                    // 既有计划回填（计划已持久化）：恢复 run 不重放历史版本水印，
                    // 时间线必须在这里主动接上，否则磁盘上有计划而 UI 空白。
                    planSteps = container.agentPlanStore.stepsFor(cid),
                    isStreaming = true,
                    isGenerating = true,
                    agentRound = 0,
                )
            }
            resetStreaming(role = Role.MODEL, isStreaming = true)
            runStartedAtMillis = System.currentTimeMillis()
            // 只有「合成的继续输入」（旧 journal 退化路径）才需要落库；savedInput
            // 那条本来就在会话文件里（onSend 落库过 / 上次恢复已落库），不重复写。
            if (savedInput == null) {
                container.conversationRepository.appendMessage(cid, userMessage)
            }
            val config = _uiState.value.config
            val request = AgentRequest(
                conversationId = cid,
                history = history,
                userInput = userMessage,
                config = config,
                model = _uiState.value.activeModel,
                policy = AgentPolicy(maxRounds = config.maxAgentRounds.coerceAtLeast(1)),
                journal = journal,
                memoryText = runCatching { container.agentMemory.renderForPrompt() }.getOrNull(),
                planStore = container.agentPlanStore,
                approvalHandler = approvalHandler,
                approvalCache = container.toolApprovalCache,
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
        dismissRecovery(offer, cid)
    }

    fun onNewConversation() {
        runJob?.cancel()
        runJob = null
        conversationId = null
        // 会话销毁 = 授权作用域消失：审批缓存全清（key 含 cid 本就隔离，这里保超额清）。
        container.toolApprovalCache.revokeAll(null)
        currentJournal = null
        resetStreaming(role = null, isStreaming = false)
        val keep = _uiState.value
        _uiState.value = ChatUiState(
            config = keep.config,
            availableModels = keep.availableModels,
            activeModel = keep.activeModel,
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
        // 保序（A 项节流改造后仍然关键）：先 flushNow 同步排空缓冲 → 取快照 →
        // 再取消 → 再清流式状态。flush 协程独立于 runJob，缓冲排空后自然退出，
        // 终态清空不会与残留 delta 竞争。
        flushNow()
        val partial = _streaming.value.text
        runJob?.cancel()
        runJob = null
        _uiState.update {
            it.copy(
                isStreaming = false,
                isGenerating = false,
                notice = null,
            )
        }
        // 回合归档（D 项）：用户主动停止 = Interrupted。**只记 TurnRecord 不归档** ——
        // cancel 时 journal 的 NonCancellable settled 收尾可能还没写完，此刻 rename
        // 会产生幽灵文件（归档留待该 journal 在下次终态路径/扫描时处理）。
        archiveTurnNow(
            com.rickeal.agent.core.agent.history.TurnState.INTERRUPTED,
            "Cancelled",
            conversationId,
            archive = false,
        )
        resetStreaming(role = null, isStreaming = false)
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
        // 恢复卡挂着时直接发新消息 = 用行动表示「不恢复」：归档那份 journal，
        // 否则卡会在下次进会话时复活（Wave3 补的口子）。
        state.recovery?.let { offer -> conversationId?.let { cid -> dismissRecovery(offer, cid) } }
        _uiState.update {
            it.copy(
                messages = history,
                draftInput = "",
                attachments = emptyList(),
                isStreaming = true,
                isGenerating = true,
                agentRound = 0,
                toolTraces = emptyList(),
                thinkingExpanded = false,
                error = null,
                notice = null,
                recovery = null,
            )
        }
        resetStreaming(role = Role.MODEL, isStreaming = true)
        runStartedAtMillis = System.currentTimeMillis()
        // 覆盖 runJob 之前必须先取消旧的：core-agent 侧已有 runMutex 根治并发，
        // 这里是第二层 —— 少这一行就是「两个 run 抢同一个引擎实例」的入口。
        runJob?.cancel()
        runJob = viewModelScope.launch {
            val cid = ensureConversation(firstUserText(history))
            container.conversationRepository.appendMessage(cid, userMessage)
            val config = _uiState.value.config
            // 每次 run 一个 journal 文件：进程被杀后可从「已完成轮次」继续
            // （core-agent/journal；写入 best-effort，失败不影响 run 本身）。
            val journal = AgentRunJournal.open(
                runDir = File(container.journalRoot, cid),
                runId = "run_" + System.currentTimeMillis(),
            )
            currentJournal = journal
            val request = AgentRequest(
                conversationId = cid,
                history = history,
                userInput = userMessage,
                config = config,
                model = _uiState.value.activeModel,
                policy = AgentPolicy(maxRounds = config.maxAgentRounds.coerceAtLeast(1)),
                journal = journal,
                // 长期记忆片段（harness-memory 移植）：读失败按无记忆处理，绝不挡发送
                memoryText = runCatching { container.agentMemory.renderForPrompt() }.getOrNull(),
                planStore = container.agentPlanStore,
                approvalHandler = approvalHandler,
                approvalCache = container.toolApprovalCache,
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
        // 重试也顶替恢复卡（同 onSend：用户行动优先于过时的恢复提示）。
        _uiState.value.recovery?.let { offer -> conversationId?.let { cid -> dismissRecovery(offer, cid) } }
        _uiState.update {
            it.copy(
                messages = history,
                isStreaming = true,
                isGenerating = true,
                agentRound = 0,
                toolTraces = emptyList(),
                error = null,
                notice = null,
                recovery = null,
            )
        }
        // 同上：覆盖 runJob 之前先取消旧的，绝不让两个 run 同时活着。
        runJob?.cancel()
        resetStreaming(role = Role.MODEL, isStreaming = true)
        runStartedAtMillis = System.currentTimeMillis()
        runJob = viewModelScope.launch {
            val cid = ensureConversation(firstUserText(history))
            val config = _uiState.value.config
            // 每次 run 一个 journal 文件：进程被杀后可从「已完成轮次」继续
            // （core-agent/journal；写入 best-effort，失败不影响 run 本身）。
            val journal = AgentRunJournal.open(
                runDir = File(container.journalRoot, cid),
                runId = "run_" + System.currentTimeMillis(),
            )
            currentJournal = journal
            val request = AgentRequest(
                conversationId = cid,
                history = history,
                userInput = userMessage,
                config = config,
                model = _uiState.value.activeModel,
                policy = AgentPolicy(maxRounds = config.maxAgentRounds.coerceAtLeast(1)),
                journal = journal,
                // 长期记忆片段（harness-memory 移植）：读失败按无记忆处理，绝不挡发送
                memoryText = runCatching { container.agentMemory.renderForPrompt() }.getOrNull(),
                planStore = container.agentPlanStore,
                approvalHandler = approvalHandler,
                approvalCache = container.toolApprovalCache,
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

            // 引擎损坏后重建并重试本轮：清空流式缓冲（两轮输出不得叠在一起），
            // 并用 notice 告知用户「已自动恢复」（gallery 自愈链可见化，G 项）——
            // 以前这里只清文本，用户唯一感知是内容突然清零重来，零解释。
            // 注意：清缓冲**不落库**（s3 审查修正）——重试=丢弃半截输出重新生成，
            // flush 落库反而会把失败尝试的半截文本存进会话。
            is AgentEvent.Retrying -> {
                resetStreamingText()
                _uiState.update { it.copy(notice = "引擎异常，已自动重建并重试") }
            }

            // 工具审批请求：立一条 RUNNING 轨迹占位（授权卡另在 snackbarHost 区渲染）。
            // Wave2 接了真审批 UI 之后，这里还残留着 Wave1 的「未接审批 UI 已按拒绝处理」
            // 文案 + SKIPPED 状态 —— 用户会同时看到矛盾的错误轨迹和待授权卡片，且授权后
            // ToolCallStarted 会再 append 一条同 id 轨迹（见下）。
            is AgentEvent.ApprovalRequested -> _uiState.update { state ->
                state.copy(
                    toolTraces = state.toolTraces + ToolTrace(
                        id = event.call.id,
                        name = event.spec.name,
                        arguments = event.call.argumentsJson,
                        status = ToolTraceStatus.RUNNING,
                        result = "等待用户授权…",
                    ),
                )
            }

            // 计划变化（plan_set / plan_update）：UI 渲染时间线。同一轮内逐次更新。
            is AgentEvent.PlanUpdated -> _uiState.update {
                it.copy(planSteps = event.steps)
            }

            is AgentEvent.TextDelta -> {
                if (firstTokenAtMillis == null) firstTokenAtMillis = System.currentTimeMillis()
                textBuffer.append(event.text)
                ensureFlushLoop()
            }

            is AgentEvent.ThinkingDelta -> {
                thinkingBuffer.append(event.text)
                ensureFlushLoop()
            }

            is AgentEvent.ToolCallStarted -> {
                val call = event.call
                _uiState.update { state ->
                    // 授权路径已由 ApprovalRequested 立过同 id 轨迹（RUNNING 占位），
                    // 这里只补「没有先例」的普通工具调用，否则同一调用出现两条轨迹。
                    val exists = state.toolTraces.any { it.id == call.id }
                    if (exists) {
                        state
                    } else {
                        state.copy(
                            toolTraces = state.toolTraces + ToolTrace(
                                id = call.id,
                                name = call.name,
                                arguments = call.argumentsJson,
                            ),
                        )
                    }
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
                // 终态收尾（A 项节流改造）：先同步排空缓冲再取快照 —— 保证
                // 「半截回答不凭空消失」的既有承诺在节流后仍然成立。
                flushNow()
                val text = event.text.ifBlank { _streaming.value.text }
                val thinking = _streaming.value.thinking.ifBlank { null }
                if (text.isNotBlank() || thinking != null) {
                    commitAssistant(text, thinking, event.usage, conversationId)
                }
                // 本次请求的 prompt 规模 = 当前上下文占用。只有拿到正数才覆盖：
                // 0 / null 表示「这次引擎没给这个字段」，用它覆盖会把上一次的真实占用抹成 0，
                // 状态条会莫名其妙消失 —— 保留旧值比显示 0 更接近事实。
                val promptTokens = event.usage?.promptTokens ?: 0
                _uiState.update {
                    it.copy(
                        isStreaming = false,
                        isGenerating = false,
                        toolTraces = emptyList(),
                        contextTokens = if (promptTokens > 0) promptTokens else it.contextTokens,
                        notice = null,
                    )
                }
                resetStreaming(role = null, isStreaming = false)
                // 回合归档（D 项）：settled 已落 journal，先 commitTurn 再归档。
                archiveTurnNow(
                    com.rickeal.agent.core.agent.history.TurnState.COMPLETE,
                    event.terminatedBy.name,
                    conversationId,
                    archive = true,
                )
            }

            is AgentEvent.Failed -> {
                // 失败尝试的半截输出不落库（与 Retrying 同理），但缓冲必须清。
                resetStreamingText()
                _uiState.update {
                    // AgentEvent.Failed 的 message 可能带着远程端点的 URL / 请求头。
                    it.copy(
                        isStreaming = false,
                        isGenerating = false,
                        error = AgentLogStore.sanitizeUserFacing(event.message),
                        notice = null,
                    )
                }
                _streaming.update { it.copy(isStreaming = false) }
                // 回合归档（D 项）：失败也是终态（settled("Failed") 已落 journal）。
                archiveTurnNow(
                    com.rickeal.agent.core.agent.history.TurnState.FAILED,
                    null,
                    conversationId,
                    archive = true,
                )
            }

            is AgentEvent.Cancelled -> {
                flushNow()
                val partial = event.partialText.ifBlank { _streaming.value.text }
                if (partial.isNotBlank()) {
                    commitAssistant(partial, null, null, conversationId)
                }
                _uiState.update {
                    it.copy(
                        isStreaming = false,
                        isGenerating = false,
                        notice = null,
                    )
                }
                resetStreaming(role = null, isStreaming = false)
                // 引擎主动 CANCELLED 终帧（可达路径）同归档；协程取消路径走 onStop。
                archiveTurnNow(
                    com.rickeal.agent.core.agent.history.TurnState.INTERRUPTED,
                    "Cancelled",
                    conversationId,
                    archive = true,
                )
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
