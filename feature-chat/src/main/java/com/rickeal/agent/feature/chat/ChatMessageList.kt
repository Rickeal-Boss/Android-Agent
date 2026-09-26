package com.rickeal.agent.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.GlassAttachmentKind
import com.rickeal.agent.core.design.GlassBubble
import com.rickeal.agent.core.design.GlassBubbleAttachment
import com.rickeal.agent.core.design.GlassBubbleUsage
import com.rickeal.agent.core.design.GlassCard
import com.rickeal.agent.core.design.GlassEmptyState
import com.rickeal.agent.core.design.GlassEmptyStateAction
import com.rickeal.agent.core.design.GlassMaterial
import com.rickeal.agent.core.design.LocalGlassColors
import com.rickeal.agent.core.model.Attachment
import com.rickeal.agent.core.model.ChatMessage
import com.rickeal.agent.core.model.Role
import com.rickeal.agent.core.model.TokenUsage
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest

/**
 * 传给 `animateScrollToItem` 的 scrollOffset：一个「足够大」的值，把列表顶到最底部。
 *
 * 默认 0 会把目标 item 的**顶部**对齐视口顶部 —— 流式气泡不断变高后，
 * 最新那几个 token 正好被顶出屏幕外，等于没跟随。
 *
 * 这里不给 `Int.MAX_VALUE`：target 是整数相加算出来的，极端情况下会溢出成负数，
 * 反而可能跳到列表顶部。1e6 px 远超任何真实会话的内容高度，且不会溢出。
 */
private const val SCROLL_TO_TAIL_SLACK = 1_000_000

/** 领域附件 → 设计层 DTO（:core-design 不依赖 :core-model，映射放在 feature 层）。 */
fun Attachment.toGlassAttachment(): GlassBubbleAttachment = when (this) {
    is Attachment.Image -> GlassBubbleAttachment(
        id = key(), label = label(), kind = GlassAttachmentKind.IMAGE, uri = uri,
    )
    is Attachment.Audio -> GlassBubbleAttachment(
        id = key(), label = label(), kind = GlassAttachmentKind.AUDIO, uri = uri,
    )
    is Attachment.Text -> GlassBubbleAttachment(
        id = key(), label = label(), kind = GlassAttachmentKind.TEXT, uri = "",
    )
    is Attachment.File -> GlassBubbleAttachment(
        id = key(), label = label(), kind = GlassAttachmentKind.FILE, uri = uri,
    )
}

fun TokenUsage.toGlassUsage(): GlassBubbleUsage = GlassBubbleUsage(
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    tokensPerSecond = tokensPerSecond,
    firstTokenLatencyMillis = firstTokenLatencyMillis,
)

@Composable
fun ChatMessageList(
    messages: List<ChatMessage>,
    /** 流式通道独立流（A 项节流）：在列表内部 collect，ChatScreen 顶层不再随 token 失效。 */
    streamingFlow: StateFlow<StreamingState>,
    thinkingExpanded: Boolean,
    onToggleThinking: () -> Unit,
    expandedThinkingIds: Set<String>,
    onToggleMessageThinking: (String) -> Unit,
    /** 折叠组展开集合（F 项）与切换回调。 */
    expandedGroupIds: Set<String>,
    onToggleGroup: (String) -> Unit,
    toolTraces: List<ToolTrace>,
    /** 空态按钮排的出口：导入/下载模型页。回调链见 [ChatRoute.chatGraph]。 */
    onOpenModels: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val streaming by streamingFlow.collectAsState()
    // 「用户是否贴着底部」必须用 derivedStateOf 包住：layoutInfo 每次滚动都会更新，
    // 直接在组合里读会让整个列表跟着每一帧滚动重组。
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()
            lastVisible == null || lastVisible.index >= info.totalItemsCount - 2
        }
    }

    // 新消息滚动（条件化，三线审查 Wave10）：仅当用户已贴着底部才跟随 ——
    // 回看历史时收到新消息不再被强行拽到底。判定源与流式分支同一条
    // derivedStateOf(atBottom)（同一个 LazyListState），不引入第二套「是否在底部」。
    // 例外：最新一条是用户自己发的 —— 发消息的人必然想看到它出现，无条件滚。
    // 进入会话 / 空列表时 atBottom 恒 true（lastVisible == null），首次载入照常滚底。
    LaunchedEffect(messages.size) {
        val index = messages.lastIndex
        if (index < 0) return@LaunchedEffect
        if (atBottom || messages[index].role == Role.USER) {
            listState.animateScrollToItem(index, scrollOffset = SCROLL_TO_TAIL_SLACK)
        }
    }

    // 流式跟随（A 项节流改造）：key 在低频的 isStreaming / toolTraces.size 上
    // （原来 key 在 streamingText 上 —— 每 token 重启协程；现在文本经 120ms 收敛，
    // 仍可用但没必要）。协程体内用 snapshotFlow 读文本长度变化驱动滚动，
    // collectLatest 保证滚动动画慢于 flush 节奏时取消上一次，不堆积。
    LaunchedEffect(streaming.isStreaming, toolTraces.size) {
        if (!streaming.isStreaming) return@LaunchedEffect
        snapshotFlow { streaming.text.length }
            .collectLatest {
                if (atBottom) {
                    val lastIndex = listState.layoutInfo.totalItemsCount - 1
                    if (lastIndex >= 0) {
                        listState.animateScrollToItem(lastIndex, scrollOffset = SCROLL_TO_TAIL_SLACK)
                    }
                }
            }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (messages.isEmpty() && !streaming.isStreaming) {
            item(key = "empty") {
                Box(modifier = Modifier.fillParentMaxHeight(0.7f), contentAlignment = Alignment.Center) {
                    // 空态两出口：都去模型页（导入本地文件 / 从推荐列表下载）。
                    // 本应用为纯端侧运行，无远程端点可配。
                    GlassEmptyState(
                        title = "开始一段对话",
                        subtitle = "先在「模型」页导入 .litertlm / .task，或从推荐列表下载",
                        actions = listOf(
                            GlassEmptyStateAction(label = "导入本地模型", onClick = onOpenModels),
                            GlassEmptyStateAction(label = "下载推荐模型", onClick = onOpenModels),
                        ),
                    )
                }
            }
        }
        items(
            items = messages,
            key = { it.id },
            contentType = { if (it.role == Role.USER) 0 else 1 },
        ) { message ->
            MessageRow(
                message = message,
                expanded = expandedThinkingIds.contains(message.id),
                onToggleThinking = onToggleMessageThinking,
            )
        }
        if (streaming.isStreaming) {
            item(key = "streaming", contentType = 1) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 工具过程折叠组（F 项）：连续完结轨迹折叠成组，RUNNING/FAILED
                    // 独立渲染；toolTraces 引用不变时 remember 跳过分组计算。
                    val renderItems = rememberTraceRenderItems(toolTraces)
                    for (renderItem in renderItems) {
                        when (renderItem) {
                            is ToolTraceOrGroup.Group -> {
                                val group = renderItem.group
                                ChatTraceGroupCard(
                                    group = group,
                                    expanded = expandedGroupIds.contains(group.id),
                                    onToggle = { onToggleGroup(group.id) },
                                )
                            }
                            is ToolTraceOrGroup.Single -> {
                                val trace = renderItem.trace
                                ChatToolCard(
                                    name = trace.name,
                                    argumentsJson = trace.arguments,
                                    output = trace.result,
                                    elapsedMillis = trace.elapsedMillis,
                                    ok = trace.status == ToolTraceStatus.OK,
                                    running = trace.status == ToolTraceStatus.RUNNING,
                                )
                            }
                        }
                    }
                    // 展示前净化 + <think> 拆分（见 splitThinkAndClean）：
                    // 流式未闭合时整段算思考，闭合后自动归位正文区。
                    val (streamThink, streamAnswer) = splitThinkAndClean(
                        streaming.text,
                        streaming.thinking.ifBlank { null },
                    )
                    GlassBubble(
                        text = streamAnswer,
                        isUser = false,
                        thinking = streamThink,
                        thinkingExpanded = thinkingExpanded,
                        onToggleThinking = onToggleThinking,
                        isStreaming = true,
                        // 流式实时指标（E 项）：TTFT 精确、tps 粗估；只在有数据时传，
                        // 避免把 "in 0 / out 0 · 0 tok/s" 之类无意义占位画出来。
                        usage = streaming.usage?.takeIf { it.ttftMillis > 0 || it.tokensPerSecond > 0f }
                            ?.let {
                                GlassBubbleUsage(
                                    promptTokens = 0,
                                    completionTokens = 0,
                                    tokensPerSecond = it.tokensPerSecond,
                                    firstTokenLatencyMillis = it.ttftMillis,
                                )
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageRow(
    message: ChatMessage,
    expanded: Boolean,
    onToggleThinking: (String) -> Unit,
) {
    when (message.role) {
        Role.SYSTEM -> {
            val colors = LocalGlassColors.current
            Text(
                text = message.text,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onGlassSubtle,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            )
        }

        Role.USER -> {
            GlassBubble(
                text = message.text,
                isUser = true,
                attachments = message.attachments.map { it.toGlassAttachment() },
            )
        }

        else -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val resultsById = message.toolResults.associateBy { it.callId }
                for (call in message.toolCalls) {
                    val result = resultsById[call.id]
                    ChatToolCard(
                        name = call.name,
                        argumentsJson = call.argumentsJson,
                        output = result?.output,
                        elapsedMillis = result?.elapsedMillis ?: 0L,
                        ok = result?.ok ?: true,
                        running = result == null,
                    )
                }
                if (message.text.isNotBlank() || !message.thinking.isNullOrBlank()) {
                    val (think, answer) = splitThinkAndClean(message.text, message.thinking)
                    val clipboard = LocalClipboardManager.current
                    GlassBubble(
                        text = answer,
                        isUser = false,
                        thinking = think,
                        thinkingExpanded = expanded,
                        onToggleThinking = { onToggleThinking(message.id) },
                        errorMessage = message.errorMessage,
                        usage = message.usage?.toGlassUsage(),
                        // 气泡下方两枚复制键（不进气泡玻璃，省 LazyColumn item 一层 glass）：
                        //  - 「复制」：splitThinkAndClean 的 .second —— 净化后展示文本
                        //    （U+FFFD 已洗、<think> 已拆出），所见即所得；
                        //  - 「复制为 Markdown」：message.text 原文，含原始 markdown 标记 ——
                        //    "原始"语义有意为之，导出到外部编辑器需要标记本身。
                        // 点击无 Toast：按钮自身有按压态反馈，复制是低风险幂等操作。
                        actions = {
                            BubbleCopyAction(
                                label = "复制",
                                icon = Icons.Filled.ContentCopy,
                                onClick = { clipboard.setText(AnnotatedString(answer)) },
                            )
                            BubbleCopyAction(
                                label = "复制为 Markdown",
                                icon = Icons.Filled.ContentCopy,
                                onClick = { clipboard.setText(AnnotatedString(message.text)) },
                            )
                        },
                    )
                }
            }
        }
    }
}

/**
 * 气泡下方的轻量文字操作键（复制类）。
 *
 * 刻意不做玻璃控件：LazyColumn 一屏十几条，每条再叠一层玻璃控件
 * 模糊成本 ×N；小文字 + 按压态足够传达可点击。圆角 8dp + 灰字弱化，
 * 视觉层级明显低于气泡本体 —— 操作是附属能力，不是内容。
 */
@Composable
private fun BubbleCopyAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val colors = LocalGlassColors.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            // padding 在 clickable 之后：热区含内边距，点字边也能命中。
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.onGlassSubtle,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onGlassSubtle,
            modifier = Modifier.padding(start = 3.dp),
        )
    }
}

@Composable
fun ChatToolCard(
    name: String,
    argumentsJson: String,
    output: String?,
    elapsedMillis: Long,
    ok: Boolean,
    running: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGlassColors.current
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        // 正文量：argumentsJson(maxLines=3) + output(maxLines=6) ≈ 最多 12 行。
        // 判据见 GlassMaterial 文件头 KDoc —— 走 THIN 而不是 REGULAR：
        // 本卡是 LazyColumn 的 item，模糊成本会 ×N，下限达标即止。
        material = GlassMaterial.THIN,
        cornerRadius = 18.dp,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Build,
                    contentDescription = null,
                    tint = colors.onGlassMuted,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onGlass,
                    modifier = Modifier
                        .padding(start = 7.dp)
                        .weight(1f),
                )
                if (running) {
                    Text(
                        text = "执行中…",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onGlassSubtle,
                    )
                } else {
                    Icon(
                        imageVector = if (ok) Icons.Filled.Check else Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = if (ok) colors.success else colors.danger,
                        modifier = Modifier.size(14.dp),
                    )
                    if (elapsedMillis > 0L) {
                        Text(
                            text = "${elapsedMillis}ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onGlassSubtle,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
            if (argumentsJson.isNotBlank() && argumentsJson != "{}") {
                Text(
                    text = argumentsJson,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onGlassSubtle,
                    maxLines = 3,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (!output.isNullOrBlank()) {
                Text(
                    text = output,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onGlassMuted,
                    maxLines = 6,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/**
 * 展示层文本净化 + `<think>` 解析（2026-09-26，仅作用于显示，不碰持久化 ——
 * journal / 会话存档保留模型原始输出，排查问题需要原文）。
 *
 * 1. **U+FFFD（�）清洗**：小模型 tokenizer 流式解码常见坏字节，替换字符在中文
 *    正文里非常刺眼（真机截图实锤）。只洗展示层。
 * 2. **`<think>` 拆分**：DeepSeek-R1 等推理模型把思考直接写进正文（不走引擎的
 *    thought channel），拆出来并入思考折叠区；流式未闭合时整段按思考显示，
 *    闭合后自动归位正文区。已有 [existingThinking]（引擎 thought channel 的
 *    思考）时按「channel 在前、正文解析在后」合并。
 *
 * 返回 `(thinking, answer)`。
 */
private fun splitThinkAndClean(text: String, existingThinking: String?): Pair<String?, String> {
    val cleaned = text.replace("\uFFFD", "")
    val openTag = "<think>"
    val closeTag = "</think>"
    val open = cleaned.indexOf(openTag)
    if (open < 0) {
        return existingThinking?.replace("\uFFFD", "") to cleaned
    }
    val close = cleaned.indexOf(closeTag)
    val thinkBody = if (close >= 0) {
        cleaned.substring(open + openTag.length, close)
    } else {
        cleaned.substring(open + openTag.length)
    }
    val answer = if (close >= 0) cleaned.substring(close + closeTag.length).trimStart('\n') else ""
    val merged = listOfNotNull(
        existingThinking?.replace("\uFFFD", "")?.takeIf { it.isNotBlank() },
        thinkBody.trim().takeIf { it.isNotBlank() },
    ).joinToString("\n\n")
    return merged.takeIf { it.isNotBlank() } to answer
}
