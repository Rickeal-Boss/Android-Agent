package com.rickeal.agent.feature.chat

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rickeal.agent.core.agent.AgentEvent
import com.rickeal.agent.core.agent.AgentPolicy
import com.rickeal.agent.core.agent.AgentRequest
import com.rickeal.agent.core.data.AppContainer
import com.rickeal.agent.core.model.Attachment
import com.rickeal.agent.core.model.ChatMessage
import com.rickeal.agent.core.model.EngineKind
import com.rickeal.agent.core.model.InferenceConfig
import com.rickeal.agent.core.model.ModelDescriptor
import com.rickeal.agent.core.model.RemoteEndpoint
import com.rickeal.agent.core.model.Role
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

class ChatViewModel(
    private val container: AppContainer,
    initialConversationId: String?,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var runJob: Job? = null
    private var conversationId: String? = initialConversationId

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
            viewModelScope.launch {
                val conversation = container.conversationRepository.load(initialConversationId)
                if (conversation != null) {
                    _uiState.update {
                        it.copy(
                            conversationId = conversation.id,
                            title = conversation.title,
                            messages = conversation.messages,
                            config = conversation.config,
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
        _uiState.update { state ->
            state.copy(
                attachments = state.attachments + Attachment.Image(
                    uri = uriString,
                    name = name.ifBlank { "图片" },
                ),
            )
        }
    }

    fun onAttachAudio(uriString: String, name: String) {
        _uiState.update { state ->
            state.copy(
                attachments = state.attachments + Attachment.Audio(
                    uri = uriString,
                    name = name.ifBlank { "音频" },
                ),
            )
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

    fun onStop() {
        runJob?.cancel()
        runJob = null
        _uiState.update { it.copy(isStreaming = false, isGenerating = false) }
    }

    fun onSend() {
        val state = _uiState.value
        if (state.isStreaming) return
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
            val request = AgentRequest(
                conversationId = cid,
                history = history,
                userInput = userMessage,
                config = config,
                model = _uiState.value.activeModel,
                endpoint = endpoint,
                policy = AgentPolicy(maxRounds = config.maxAgentRounds.coerceAtLeast(1)),
            )
            runCatching {
                container.agentRunner.run(request).collect { event -> handleEvent(event, cid) }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isStreaming = false,
                        isGenerating = false,
                        error = throwable.message ?: "生成失败",
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
        runJob = viewModelScope.launch {
            val cid = ensureConversation(firstUserText(history))
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
            )
            runCatching {
                container.agentRunner.run(request).collect { event -> handleEvent(event, cid) }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isStreaming = false,
                        isGenerating = false,
                        error = throwable.message ?: "生成失败",
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
                commit(event.message, conversationId)
            }

            is AgentEvent.Finished -> {
                val assistant = ChatMessage(
                    role = Role.MODEL,
                    text = event.text.ifBlank { _uiState.value.streamingText },
                    thinking = _uiState.value.streamingThinking.ifBlank { null },
                    usage = event.usage,
                )
                if (assistant.text.isNotBlank() || assistant.thinking != null) {
                    commit(assistant, conversationId)
                }
                _uiState.update {
                    it.copy(
                        streamingText = "",
                        streamingThinking = "",
                        isStreaming = false,
                        isGenerating = false,
                        toolTraces = emptyList(),
                    )
                }
            }

            is AgentEvent.Failed -> _uiState.update {
                it.copy(isStreaming = false, isGenerating = false, error = event.message)
            }

            is AgentEvent.Cancelled -> {
                val partial = event.partialText.ifBlank { _uiState.value.streamingText }
                if (partial.isNotBlank()) {
                    commit(
                        ChatMessage(role = Role.MODEL, text = partial),
                        conversationId,
                    )
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
