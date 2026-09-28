package com.rickeal.agent.feature.chat

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.agent.breaker.render
import com.rickeal.agent.core.agent.TerminationReason
import com.rickeal.agent.core.agent.plan.PlanStepStatus
import com.rickeal.agent.core.design.LocalBottomBarOverlay
import com.rickeal.agent.core.engine.EngineSessionDiagnostics
import com.rickeal.agent.core.design.GlassButton
import com.rickeal.agent.core.design.GlassCard
import com.rickeal.agent.core.design.GlassIconButton
import com.rickeal.agent.core.design.GlassMaterial
import com.rickeal.agent.core.design.GlassScaffold
import com.rickeal.agent.core.design.GlassThinkingIndicator
import com.rickeal.agent.core.design.GlassTopBar
import com.rickeal.agent.core.design.LocalGlassColors
import com.rickeal.agent.core.design.LocalGlassConfig
import com.rickeal.agent.core.design.LocalGlassTokens
import com.rickeal.agent.core.design.LocalOverlayBlurState
import com.rickeal.agent.core.design.OverlayBlurScope
import com.rickeal.agent.core.design.overlayBackdropBlur
import com.rickeal.agent.core.design.rememberGlassHaptics
import com.rickeal.agent.core.design.rememberOverlayBlurProgress
import com.rickeal.agent.core.design.rememberWindowSizeClass
import com.rickeal.agent.core.model.AgentLogStore
import com.rickeal.agent.core.model.Role
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDrawer: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current
    val windowSize = rememberWindowSizeClass()
    // 工具授权是"放行 / 拦下"两个相反决策，用 Confirm / Reject 两种触感区分。
    val haptics = rememberGlassHaptics()
    // 两者都要能扛住配置变更（旋转 / 折叠展开）：
    //  - paramsOpen：抽屉开着时转屏就自己关掉，用户输入一半的参数面板凭空消失；
    //  - listState：滚动位置丢失会直接跳回列表底部/顶部，用户正在看的那条就没了。
    // LazyListState 必须用 LazyListState.Saver（它内部是普通可变状态，不能用 autoSaver）。
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    var paramsOpen by rememberSaveable { mutableStateOf(false) }

    // 「新对话」操作反馈（2026-09-26）：原 Edit 图标语义（编辑）与行为（新对话）不符
    // 导致误读 —— 图标换成 Add 之后，用户按下去也该知道"发生了什么"：
    // 点新对话会瞬间清空列表，没有反馈看起来就像"坏掉了"。showSnackbar 挂
    // rememberCoroutineScope（uiState 无此事件通道，ViewModel 属并行成员改动范围），
    // 宿主槽复用 GlassScaffold 的 snackbarHost 卡片 Column 末尾，M3 默认样式即可。
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // ── 参数面板的背景深度模糊（2026-09-27 用户需求：「覆盖层打开时整个背景深度模糊」）──
    // 这是三类覆盖层里唯一「外壳罩不住」的一个：ChatParamsSheet 是下面 GlassScaffold 的
    // **兄弟**节点、两者都在 NavHost 之内，外壳那层 body 级模糊会连面板一起糊掉
    // （Compose 没有「反模糊」，父节点挂了 RenderEffect，子树全跟着糊）。所以由本屏
    // 自己糊自己的 scaffold（面板绘制序在后 ⇒ 不被糊），同时经登记表让外壳把**悬浮页签**
    // 也糊上 —— 页签是 NavHost 的兄弟、不在本屏管辖内，不补这一条就会剩一条清晰玻璃条
    // 浮在糊背景上，与「整个背景」矛盾。完整层级推导见 OverlayBackdropBlur.kt 的类 KDoc。
    //
    // 用 panelOpen 而不是 paramsOpen：宽屏（三栏）下参数是常驻的 ChatParamsPanel、根本不是
    // 覆盖层（Tune 入口也不渲染），但 rememberSaveable 可能把 paramsOpen 还原成 true，
    // 那时不该平白糊一屏。
    val panelOpen = paramsOpen && !windowSize.useThreePane
    // 与设置页「背景模糊」总闸 + GlassConfig.overlayBlurRadius 同源（外壳那条也一样）。
    val glassCfgForBlur = LocalGlassConfig.current
    val panelBlurEnabled = glassCfgForBlur.enableBackdropBlur
    val overlayBlurState = LocalOverlayBlurState.current
    val panelBlurToken = remember { Any() }
    // onDispose 只在**登记过的**分支里挂：不依赖「DisposableEffect 换 key 时先释放旧的
    // 再执行新的」这条顺序保证（虽然 Compose 确实是这个顺序），将来谁改了实现也不出错。
    DisposableEffect(overlayBlurState, panelBlurToken, panelOpen) {
        if (panelOpen) {
            overlayBlurState.acquire(panelBlurToken, OverlayBlurScope.PANEL)
            onDispose { overlayBlurState.release(panelBlurToken) }
        } else {
            onDispose { }
        }
    }
    val panelBlurProgress = rememberOverlayBlurProgress(
        active = { panelOpen },
        enabled = panelBlurEnabled,
    )

    val pickImage = rememberImagePicker { uri, name -> viewModel.onAttachImage(uri, name) }
    val pickAudio = rememberAudioPicker { uri, name -> viewModel.onAttachAudio(uri, name) }

    val supportsImages = state.activeModel?.capabilities?.image ?: true
    val subtitle = when {
        state.activeModel != null -> "端侧 · ${state.activeModel?.displayName.orEmpty()}"
        else -> "未选择模型"
    }

    GlassScaffold(
        modifier = modifier
            // 参数面板打开时的背景深度模糊（只糊本屏，面板是它的兄弟、绘制序在后）。
            // 进度在 draw 阶段读 ⇒ 逐帧变化只失效绘制，不重组本屏（消息列表不会被牵连）。
            .overlayBackdropBlur(
                progress = panelBlurProgress,
                radius = glassCfgForBlur.overlayBlurRadius.dp,
                enabled = glassCfgForBlur.enableBackdropBlur,
            ),
        topBar = {
            GlassTopBar(
                title = state.title,
                subtitle = subtitle,
                modifier = Modifier.statusBarsPadding(),
                navigationIcon = {
                    // pressOnly：顶栏图标位于 GlassTopBar 自己的玻璃之上，
                    // 再叠一层玻璃会浑浊，也会复现高光溢出盖住标题的问题。
                    //
                    // 两态**互斥**（任一时刻只渲染一个）⇒ 本槽宽度恒为 48dp，与「加汉堡之前」
                    // 完全一致 —— 标题可用宽度不受影响（GlassTopBar 的标题在 weight(1f) 里，
                    // 槽一宽就挤标题）。用户设备宽 ≈320dp，属最窄档，这点余量不能丢。
                    //
                    // ⚠️ 反面记录：曾把两个图标在 Row 里**并排**（槽宽 96dp），窄屏 + 生成中时
                    // 把标题挤到 ≈0（右侧 actions 已带「轮次 + 参数 + 新对话」）。**不要改回并排。**
                    if (onOpenDrawer != null) {
                        // COMPACT：有抽屉 ⇒ 汉堡占位。抽屉在这一档是**唯一**的工作区 / 会话入口；
                        // 而「模型」在本档是**重复入口** —— 底栏 5 个页签里就有「模型」，窄屏常驻可见。
                        GlassIconButton(
                            icon = Icons.Filled.Menu,
                            contentDescription = "打开抽屉",
                            onClick = onOpenDrawer,
                            contentColor = colors.onGlassMuted,
                            pressOnly = true,
                        )
                    } else {
                        // 宽屏（MEDIUM/EXPANDED）：没有抽屉（GlassNavRail 就是入口）⇒
                        // 保留原来的「模型」快捷入口，零改动。
                        GlassIconButton(
                            icon = Icons.Filled.Storage,
                            contentDescription = "模型",
                            onClick = onOpenModels,
                            contentColor = colors.onGlassMuted,
                            pressOnly = true,
                        )
                    }
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
                        // 图标语义修复：Edit（编辑）长成了"编辑这条消息/草稿"的样子，
                        // 实际行为却是开新对话 —— 换 Add（新建），contentDescription 不变。
                        // 点击回调带 snackbar 反馈：清空列表是瞬时强变更，无反馈即"像坏了"。
                        icon = Icons.Filled.Add,
                        contentDescription = "新对话",
                        onClick = {
                            viewModel.onNewConversation()
                            scope.launch { snackbarHostState.showSnackbar("已开启新对话") }
                        },
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
            // + 悬浮页签占位（2026-09-26）：输入区整体抬到玻璃页签之上 —— 消息列表
            // 已经被 bottomBar 挡在页签上方，这里若不抬，输入框会整个压进页签区。
            Column(modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = LocalBottomBarOverlay.current)
            ) {
                ChatContextMeter(
                    usedTokens = state.contextTokens,
                    limitTokens = state.config.contextLength,
                    // 发送侧估算（Wave 31 流2）：与引擎实测并列，UI 用「估算≈/实测」区分。
                    sentTokensEstimate = state.sentTokensEstimate,
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
            // 宿主级状态信息区（自上而下）：工具授权 > 自愈提示 > 崩溃恢复 > 执行计划 > 错误。
            // 都是非模态卡片：run 不被弹窗打断，但「现在发生了什么 / 需要你做什么」永远可见。
            // 间距统一走 spacedBy —— 之前是每张卡后面手插一个 Spacer，增删卡片容易漏。
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(tokens.gapSm),
            ) {

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
                            color = colors.onGlass,
                        )
                        Spacer(modifier = Modifier.height(tokens.gapSm))
                        // 参数是模型给的 JSON 原文：等宽字体对齐结构，扫一眼就能看出要写哪个路径。
                        // maxLines 截断 + 不做内嵌滚动 —— snackbar 区内嵌 scroll 会抢走外层手势。
                        Text(
                            text = pendingApproval.arguments,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = colors.onGlassSubtle,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(tokens.gapSm))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            GlassButton(
                                text = "拒绝",
                                onClick = {
                                    haptics.reject()
                                    viewModel.onApprovalResult(false)
                                },
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(modifier = Modifier.width(tokens.gapSm))
                            GlassButton(
                                text = "授权",
                                onClick = {
                                    haptics.confirm()
                                    viewModel.onApprovalResult(true)
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(modifier = Modifier.height(tokens.gapSm))
                        // 「相同调用不再询问」（Wave3 计划级授权轻量降级）：写入审批
                        // 缓存（工具名 × 参数摘要，TTL 30min，会话隔离）；同参重试免弹卡，
                        // 参数变了照样再问 —— 对齐 ZCode「输入每次不同的工具不能记住决策」。
                        GlassButton(
                            text = "相同调用不再询问",
                            onClick = {
                                // 与同卡「授权」同类：一次明确的**肯定性提交**（写入
                                // 审批缓存 TTL 30min）。卡里另两枚都震、唯独它不震
                                // 会显得像没按到，补 confirm()。
                                haptics.confirm()
                                viewModel.onApprovalRememberForSession()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // ── 自愈提示卡（G 项）：引擎重建/重试「已自动恢复」的非阻塞告知。────
            // 与 error 的区别：无需用户处置，终态事件自动清除，也可手动关。
            // 排版位置紧跟授权卡：运行中的瞬时信息放最上，历史类卡片（恢复/计划）在下。
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
                                text = "上次任务被中断 · 已保留 ${recovery.messageCount} 条进度",
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.onGlass,
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "执行计划",
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.onGlass,
                                modifier = Modifier.weight(1f),
                            )
                            // 完成度徽标：不用逐行找勾就知道进度。
                            val doneCount = state.planSteps.count { it.status == PlanStepStatus.COMPLETED }
                            Text(
                                text = "$doneCount/${state.planSteps.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onGlassSubtle,
                            )
                        }
                        Spacer(modifier = Modifier.height(tokens.gapSm))
                        state.planSteps.forEachIndexed { index, step ->
                            val mark = when (step.status) {
                                PlanStepStatus.COMPLETED -> "✓"
                                PlanStepStatus.IN_PROGRESS -> "▶"
                                PlanStepStatus.PENDING -> "·"
                            }
                            // 层级：进行中最亮 > 待办次之 > 已完成划线弱化 —— 视线应落在「正在做什么」。
                            Text(
                                text = "$mark ${index + 1}. ${step.description}",
                                style = MaterialTheme.typography.bodySmall,
                                color = when (step.status) {
                                    PlanStepStatus.COMPLETED -> colors.onGlassSubtle
                                    PlanStepStatus.IN_PROGRESS -> colors.onGlass
                                    PlanStepStatus.PENDING -> colors.onGlassMuted
                                },
                                textDecoration = if (step.status == PlanStepStatus.COMPLETED) {
                                    TextDecoration.LineThrough
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
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
            }

            // ── 任务诊断卡（Wave 30 §2.4/§2.5 熔断归因）────────────────────
            // 与错误卡同款玻璃卡，挂在错误卡下方：错误卡只说「出错了」，这里补
            // 「卡在哪 + 下一步」。本波按评审口径「文本渲染即达标」—— 不做折叠、
            // 不加交互、不新造组件，直接用报告自带的 render() 分节文本（任务 /
            // 轮次 / 尝试过的工具 / 熔断记录 / 卡点 / 建议）。
            // 独立成一个 if 而不是塞进错误卡：轮次耗尽走的是 Finished 不是 Failed，
            // 那条路径没有 error，诊断卡必须能单独出现。
            val report = state.lastReport
            if (report != null) {
                GlassCard(
                    material = GlassMaterial.THICK,
                    cornerRadius = tokens.radiusMd,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier.padding(horizontal = 24.dp),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // 标题带卡点：一眼看到归因结论，细节在正文里逐条展开。
                        Text(
                            text = "任务诊断 · ${report.blocker.title}",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.onGlass,
                        )
                        Spacer(modifier = Modifier.height(tokens.gapSm))
                        // sanitize 与 error 走同一条出口：报告正文里带工具报错原文
                        // 与熔断证据（外部自由文本），口径必须和 AgentEvent.Failed
                        // 的 message 一致 —— 脱敏统一在渲染出口做，数据层不脱。
                        // trimEnd：render() 逐行 appendLine，末行会多带一个换行，
                        // 卡片底部凭空空一截。
                        Text(
                            text = AgentLogStore.sanitizeUserFacing(report.render()).trimEnd(),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = colors.onGlassMuted,
                        )
                    }
                }
            }

            // ── 终止原因小字（Wave 31 流2：Failed.terminatedBy 接 UI）────────
            // 极轻量：仅对「非正常终止」里语义明确的两档渲染一行小字；其余值 / null
            // 不渲染任何东西 —— 正常结束路径（ModelStopped）UI 零变化。独立于诊断卡：
            // 诊断卡只在有 report 时出现，而这行小字对「无 report 的终止」也能给出提示。
            val terminationHint = terminationHintOf(state.lastTermination)
            if (terminationHint != null) {
                Text(
                    text = terminationHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onGlassSubtle,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }

            // ── 引擎会话诊断小字（Wave 33：sessionDiagnostics 接 UI）────────
            // 极轻量：仅对三类「引擎已自动降级但用户不可见」的状态渲染一行小字
            // （legacy 回退 / 系统提示词并入用户消息 / GPU 降级 CPU）；诊断正常
            // （角色通道 active 且后端一致）不渲染任何东西 —— 正常路径零 UI 变化。
            // 与终止原因小字同款样式（labelSmall + onGlassSubtle），复用 Wave 31 先例形态。
            val diagnosticsHint = sessionDiagnosticsHintOf(state.sessionDiagnostics)
            if (diagnosticsHint != null) {
                Text(
                    text = diagnosticsHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onGlassSubtle,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }

            // ── 轻量操作反馈（Snackbar）────────────────────────────────
            // 挂在状态卡片列的末尾：M3 默认样式，不另做玻璃 Snackbar（宿主级
            // 状态信息已有完整玻璃卡体系，瞬时 toast 级反馈不值得再造一层）。
            // 当前唯一调用点：「新对话」完成提示。
            SnackbarHost(hostState = snackbarHostState)
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

/**
 * 终止原因 → 一行小字（Wave 31 流2）。
 *
 * 只对「用户需要知道、且当前有表达力」的两档渲染；其余值（ModelStopped /
 * Interrupted）与 null 返回 null = **不渲染任何东西**（正常结束路径零 UI 变化）。
 * `when` 带 `else`：`TerminationReason` 枚举将来加值时不崩、不渲染。
 */
private fun terminationHintOf(reason: TerminationReason?): String? = when (reason) {
    TerminationReason.BreakerTripped -> "（已被安全熔断，详见任务诊断）"
    TerminationReason.MaxRounds -> "（达到轮次上限）"
    else -> null
}

/**
 * 引擎会话诊断 → 一行小字（Wave 33）。
 *
 * 只对三类**静默降级**渲染（引擎已自动处理、但用户此前完全不可见）：
 *  1. legacy 回退（角色通道未激活）：「引擎已回退纯文本模式（<原因截断 80 字>）」；
 *  2. 中档回退（系统提示词并入用户消息，Gemma 系模板兼容模式）；
 *  3. 后端降级：「请求 GPU 已降级 CPU 运行」。
 *
 * 其余情况（诊断 null / 角色通道 active 且后端一致）返回 null = **不渲染任何东西**，
 * 正常路径零 UI 变化。优先级：legacy > 中档 > 后端降级（降级链上越靠前的信息
 * 越本质 —— legacy 回退时后端信息照常可用，不必同屏两条）。
 */
private fun sessionDiagnosticsHintOf(diagnostics: EngineSessionDiagnostics?): String? {
    if (diagnostics == null) return null
    if (!diagnostics.roleChannelActive) {
        val reason = diagnostics.legacyFallbackReason
        return if (reason.isNullOrBlank()) {
            "引擎已回退纯文本模式"
        } else {
            "引擎已回退纯文本模式（${reason.take(80)}）"
        }
    }
    if (diagnostics.systemMergedIntoUser) {
        return "系统提示词已并入用户消息（模型模板兼容模式）"
    }
    val requested = diagnostics.requestedBackend
    val actual = diagnostics.actualBackend
    if (requested != null && actual != null && requested != actual) {
        return "请求 $requested 已降级 $actual 运行"
    }
    return null
}
