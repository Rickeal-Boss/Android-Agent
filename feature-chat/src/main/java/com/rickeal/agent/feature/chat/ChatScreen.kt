package com.rickeal.agent.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.GlassButton
import com.rickeal.agent.core.design.GlassCard
import com.rickeal.agent.core.design.GlassMaterial
import com.rickeal.agent.core.design.GlassScaffold
import com.rickeal.agent.core.design.GlassThinkingIndicator
import com.rickeal.agent.core.design.GlassTopBar
import com.rickeal.agent.core.design.LocalGlassColors
import com.rickeal.agent.core.design.LocalGlassTokens
import com.rickeal.agent.core.design.rememberWindowSizeClass
import com.rickeal.agent.core.model.EngineKind
import com.rickeal.agent.core.model.Role

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current
    val windowSize = rememberWindowSizeClass()
    val listState = rememberLazyListState()
    var paramsOpen by remember { mutableStateOf(false) }

    val pickImage = rememberImagePicker { uri, name -> viewModel.onAttachImage(uri, name) }
    val pickAudio = rememberAudioPicker { uri, name -> viewModel.onAttachAudio(uri, name) }

    val isRemote = state.config.engineKind == EngineKind.REMOTE
    val supportsImages = if (isRemote) {
        true
    } else {
        state.activeModel?.capabilities?.image ?: true
    }
    val subtitle = when {
        isRemote && state.activeEndpoint != null -> "远程 · ${state.activeEndpoint?.name.orEmpty()}"
        isRemote -> "远程 · 未选择端点"
        state.activeModel != null -> "本地 · ${state.activeModel?.displayName.orEmpty()}"
        else -> "未选择模型"
    }

    GlassScaffold(
        modifier = modifier,
        topBar = {
            GlassTopBar(
                title = state.title,
                subtitle = subtitle,
                modifier = Modifier.statusBarsPadding(),
                navigationIcon = {
                    Box(
                        modifier = Modifier
                            .size(tokens.minTouchTarget)
                            .clickable(onClick = onOpenModels),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Storage,
                            contentDescription = "模型",
                            tint = colors.onGlassMuted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                actions = {
                    if (state.isGenerating) {
                        GlassThinkingIndicator(label = "${state.agentRound}/${state.agentMaxRounds} 轮")
                    }
                    if (!windowSize.useThreePane) {
                        Box(
                            modifier = Modifier
                                .size(tokens.minTouchTarget)
                                .clickable { paramsOpen = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Tune,
                                contentDescription = "参数",
                                tint = colors.onGlassMuted,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(tokens.minTouchTarget)
                            .clickable(onClick = viewModel::onNewConversation),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = "新对话",
                            tint = colors.onGlassMuted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
            )
        },
        bottomBar = {
            ChatInputBar(
                draft = state.draftInput,
                onDraftChange = viewModel::onInputChange,
                attachments = state.attachments,
                onRemoveAttachment = viewModel::onRemoveAttachment,
                onSend = viewModel::onSend,
                onStop = viewModel::onStop,
                isGenerating = state.isGenerating,
                onPickImage = pickImage,
                onPickAudio = pickAudio,
                supportsImages = supportsImages,
                modifier = Modifier
                    .navigationBarsPadding()
                    .imePadding(),
            )
        },
        snackbarHost = {
            val error = state.error
            if (error != null) {
                // 失败后必须给用户一条出路。只显示错误文本 + 一个「关闭」的话，用户除了把问题
                // 重打一遍别无选择 —— 而重打会在历史里留下「连续两条 USER 消息」（见
                // ChatViewModel.onRetry 的注释），对 4B 模型是实打实的质量隐患。
                // 这里**复用** ViewModel 里已有的 onRetry()：它会先把历史剪到最后一条用户消息
                // 为止再重发，同时顺手把这条错误清掉。不要在这里另写一套重试。
                // 没有任何用户消息时（理论上进不来）不显示按钮，避免点了没反应。
                val canRetry = state.messages.any { it.role == Role.USER }
                GlassCard(
                    material = GlassMaterial.THICK,
                    cornerRadius = tokens.radiusMd,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier.padding(horizontal = 24.dp),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 不设 maxLines：引擎侧的失败原因可能很长（含原始异常），
                            // 截断成一行会让用户彻底看不懂。
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.danger,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "关闭",
                                tint = colors.onGlassSubtle,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .size(16.dp)
                                    .clickable(onClick = viewModel::onDismissError),
                            )
                        }
                        if (canRetry) {
                            Spacer(modifier = Modifier.height(tokens.gapSm))
                            GlassButton(
                                text = "重试",
                                onClick = viewModel::onRetry,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        },
    ) { _ ->
        if (windowSize.useThreePane) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    ChatMessageList(
                        messages = state.messages,
                        streamingText = state.streamingText,
                        streamingThinking = state.streamingThinking,
                        isStreaming = state.isStreaming,
                        thinkingExpanded = state.thinkingExpanded,
                        onToggleThinking = { viewModel.toggleThinking() },
                        expandedThinkingIds = state.expandedThinkingIds,
                        onToggleMessageThinking = { id -> viewModel.toggleThinking(id) },
                        toolTraces = state.toolTraces,
                        listState = listState,
                    )
                }
                Box(modifier = Modifier.width(340.dp).fillMaxSize()) {
                    ChatParamsPanel(
                        config = state.config,
                        onParamPreview = viewModel::onParamPreview,
                        onParamCommit = viewModel::onParamCommit,
                        onParamChange = viewModel::onParamChangeWith,
                    )
                }
            }
        } else {
            ChatMessageList(
                messages = state.messages,
                streamingText = state.streamingText,
                streamingThinking = state.streamingThinking,
                isStreaming = state.isStreaming,
                thinkingExpanded = state.thinkingExpanded,
                onToggleThinking = viewModel::toggleThinking,
                expandedThinkingIds = state.expandedThinkingIds,
                onToggleMessageThinking = viewModel::toggleThinking,
                toolTraces = state.toolTraces,
                listState = listState,
            )
        }
    }

    if (!windowSize.useThreePane) {
        ChatParamsSheet(
            visible = paramsOpen,
            onDismiss = { paramsOpen = false },
            config = state.config,
            onParamPreview = viewModel::onParamPreview,
            onParamCommit = viewModel::onParamCommit,
            onParamChange = viewModel::onParamChangeWith,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
