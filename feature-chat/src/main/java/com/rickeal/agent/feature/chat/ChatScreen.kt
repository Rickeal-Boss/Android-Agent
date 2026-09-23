package com.rickeal.agent.feature.chat

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.GlassButton
import com.rickeal.agent.core.design.GlassCard
import com.rickeal.agent.core.design.GlassIconButton
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
    /** 空对话页「配置远程端点」按钮的出口，链路同 [onOpenModels]（chatGraph 下发）。 */
    onOpenEndpoints: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current
    val windowSize = rememberWindowSizeClass()
    // 两者都要能扛住配置变更（旋转 / 折叠展开）：
    //  - paramsOpen：抽屉开着时转屏就自己关掉，用户输入一半的参数面板凭空消失；
    //  - listState：滚动位置丢失会直接跳回列表底部/顶部，用户正在看的那条就没了。
    // LazyListState 必须用 LazyListState.Saver（它内部是普通可变状态，不能用 autoSaver）。
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    var paramsOpen by rememberSaveable { mutableStateOf(false) }

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
                    // pressOnly：顶栏图标位于 GlassTopBar 自己的玻璃之上，
                    // 再叠一层玻璃会浑浊，也会复现高光溢出盖住标题的问题。
                    GlassIconButton(
                        icon = Icons.Filled.Storage,
                        contentDescription = "模型",
                        onClick = onOpenModels,
                        contentColor = colors.onGlassMuted,
                        pressOnly = true,
                    )
                },
                actions = {
                    if (state.isGenerating) {
                        GlassThinkingIndicator(label = "${state.agentRound}/${state.agentMaxRounds} 轮")
                    }
                    if (!windowSize.useThreePane) {
                        GlassIconButton(
                            icon = Icons.Filled.Tune,
                            contentDescription = "参数",
                            onClick = { paramsOpen = true },
                            contentColor = colors.onGlassMuted,
                            pressOnly = true,
                        )
                    }
                    GlassIconButton(
                        icon = Icons.Filled.Edit,
                        contentDescription = "新对话",
                        onClick = viewModel::onNewConversation,
                        contentColor = colors.onGlassMuted,
                        pressOnly = true,
                    )
                },
            )
        },
        bottomBar = {
            // 上下文占用条紧贴输入框上方：它是「模型变傻」的解释，属于输入区的状态信息，
            // 不占正文空间。Column 里的 imePadding/navigationBarsPadding 仍在 ChatInputBar
            // 自己身上，键盘弹出时这一行会一起被顶到键盘上方。
            Column(modifier = Modifier.fillMaxWidth()) {
                ChatContextMeter(
                    usedTokens = state.contextTokens,
                    limitTokens = state.config.contextLength,
                )
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
            }
        },
        snackbarHost = {
            // 宿主级状态信息区（自上而下）：工具授权 > 崩溃恢复 > 执行计划 > 错误。
            // 都是非模态卡片：run 不被弹窗打断，但「现在发生了什么 / 需要你做什么」永远可见。
            Column(modifier = Modifier.fillMaxWidth()) {

            // ── 工具授权卡（人在回路；Octop tool_guard 的 UI 面）──────────────
            val pendingApproval = state.pendingApproval
            if (pendingApproval != null) {
                GlassCard(
                    material = GlassMaterial.THICK,
                    cornerRadius = tokens.radiusMd,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier.padding(horizontal = 24.dp),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "工具「${pendingApproval.toolName}」请求授权",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.onGlassMuted,
                        )
                        Spacer(modifier = Modifier.height(tokens.gapSm))
                        Text(
                            text = pendingApproval.arguments,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onGlassSubtle,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                        )
                        Spacer(modifier = Modifier.height(tokens.gapSm))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            GlassButton(
                                text = "拒绝",
                                onClick = { viewModel.onApprovalResult(false) },
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(modifier = Modifier.width(tokens.gapSm))
                            GlassButton(
                                text = "授权",
                                onClick = { viewModel.onApprovalResult(true) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(modifier = Modifier.height(tokens.gapSm))
                        // 「相同调用不再询问」（Wave3 计划级授权轻量降级）：写入审批
                        // 缓存（工具名 × 参数摘要，TTL 30min，会话隔离）；同参重试免弹卡，
                        // 参数变了照样再问 —— 对齐 ZCode「输入每次不同的工具不能记住决策」。
                        GlassButton(
                            text = "相同调用不再询问",
                            onClick = viewModel::onApprovalRememberForSession,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(tokens.gapSm))
            }

            // ── 崩溃恢复卡（ZCode Journal 语义的可见面）────────────────────
            val recovery = state.recovery
            if (recovery != null && !state.isGenerating) {
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
                            Text(
                                text = "上次任务被中断（已保留 ${recovery.messageCount} 条进度）",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onGlassMuted,
                                modifier = Modifier.weight(1f),
                            )
                            GlassIconButton(
                                icon = Icons.Filled.Close,
                                contentDescription = "忽略",
                                onClick = viewModel::onDiscardRecovery,
                                contentColor = colors.onGlassSubtle,
                                iconSize = 16.dp,
                            )
                        }
                        Spacer(modifier = Modifier.height(tokens.gapSm))
                        GlassButton(
                            text = "从中断处继续",
                            onClick = viewModel::onRecover,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(tokens.gapSm))
            }

            // ── 执行计划时间线（plan_set / plan_update 实时渲染）────────────
            if (state.planSteps.isNotEmpty()) {
                GlassCard(
                    material = GlassMaterial.THICK,
                    cornerRadius = tokens.radiusMd,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier.padding(horizontal = 24.dp),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "执行计划",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.onGlassMuted,
                        )
                        Spacer(modifier = Modifier.height(tokens.gapSm))
                        state.planSteps.forEachIndexed { index, step ->
                            val mark = when (step.status) {
                                com.rickeal.agent.core.agent.plan.PlanStepStatus.COMPLETED -> "✓"
                                com.rickeal.agent.core.agent.plan.PlanStepStatus.IN_PROGRESS -> "▶"
                                com.rickeal.agent.core.agent.plan.PlanStepStatus.PENDING -> "·"
                            }
                            Text(
                                text = "$mark ${index + 1}. ${step.description}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (step.status == com.rickeal.agent.core.agent.plan.PlanStepStatus.COMPLETED) {
                                    colors.onGlassSubtle
                                } else {
                                    colors.onGlassMuted
                                },
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(tokens.gapSm))
            }

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
                            // 触摸目标由组件内的 size 参数撑满 48dp；图标保持原来的 16dp。
                            GlassIconButton(
                                icon = Icons.Filled.Close,
                                contentDescription = "关闭",
                                onClick = viewModel::onDismissError,
                                contentColor = colors.onGlassSubtle,
                                iconSize = 16.dp,
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
                Spacer(modifier = Modifier.height(tokens.gapSm))
            }

            // 自愈提示卡（G 项）：引擎重建/重试「已自动恢复」的非阻塞告知。
            // 与 error 的区别：无需用户处置，终态事件自动清除，也可手动关。
            val notice = state.notice
            if (notice != null) {
                GlassCard(
                    material = GlassMaterial.THICK,
                    cornerRadius = tokens.radiusMd,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier.padding(horizontal = 24.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = notice,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onGlassMuted,
                            modifier = Modifier.weight(1f),
                        )
                        GlassIconButton(
                            icon = Icons.Filled.Close,
                            contentDescription = "关闭提示",
                            onClick = viewModel::onDismissNotice,
                            contentColor = colors.onGlassSubtle,
                            iconSize = 16.dp,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(tokens.gapSm))
            }
            }
        },
    ) { _ ->
        if (windowSize.useThreePane) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    ChatMessageList(
                        messages = state.messages,
                        streamingFlow = viewModel.streaming,
                        thinkingExpanded = state.thinkingExpanded,
                        onToggleThinking = { viewModel.toggleThinking() },
                        expandedThinkingIds = state.expandedThinkingIds,
                        onToggleMessageThinking = { id -> viewModel.toggleThinking(id) },
                        expandedGroupIds = state.expandedGroupIds,
                        onToggleGroup = viewModel::toggleGroup,
                        toolTraces = state.toolTraces,
                        onOpenModels = onOpenModels,
                        onOpenEndpoints = onOpenEndpoints,
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
                streamingFlow = viewModel.streaming,
                thinkingExpanded = state.thinkingExpanded,
                onToggleThinking = viewModel::toggleThinking,
                expandedThinkingIds = state.expandedThinkingIds,
                onToggleMessageThinking = viewModel::toggleThinking,
                expandedGroupIds = state.expandedGroupIds,
                onToggleGroup = viewModel::toggleGroup,
                toolTraces = state.toolTraces,
                onOpenModels = onOpenModels,
                onOpenEndpoints = onOpenEndpoints,
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
