package com.rickeal.agent.feature.chat

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.GlassAttachmentKind
import com.rickeal.agent.core.design.GlassBubble
import com.rickeal.agent.core.design.GlassBubbleAttachment
import com.rickeal.agent.core.design.GlassBubbleUsage
import com.rickeal.agent.core.design.GlassCard
import com.rickeal.agent.core.design.GlassEmptyState
import com.rickeal.agent.core.design.GlassMaterial
import com.rickeal.agent.core.design.LocalGlassColors
import com.rickeal.agent.core.model.Attachment
import com.rickeal.agent.core.model.ChatMessage
import com.rickeal.agent.core.model.Role
import com.rickeal.agent.core.model.TokenUsage

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
    streamingText: String,
    streamingThinking: String,
    isStreaming: Boolean,
    thinkingExpanded: Boolean,
    onToggleThinking: () -> Unit,
    expandedThinkingIds: Set<String>,
    onToggleMessageThinking: (String) -> Unit,
    toolTraces: List<ToolTrace>,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    LaunchedEffect(messages.size) {
        val index = messages.lastIndex
        if (index >= 0) listState.animateScrollToItem(index)
    }
    LaunchedEffect(isStreaming) {
        if (isStreaming) listState.animateScrollToItem(messages.size)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (messages.isEmpty() && !isStreaming) {
            item(key = "empty") {
                Box(modifier = Modifier.fillParentMaxHeight(0.7f), contentAlignment = Alignment.Center) {
                    GlassEmptyState(
                        title = "开始一段对话",
                        subtitle = "先在「模型」页导入 .litertlm / .task，或配置一个远程端点",
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
        if (isStreaming) {
            item(key = "streaming", contentType = 1) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (trace in toolTraces) {
                        ChatToolCard(
                            name = trace.name,
                            argumentsJson = trace.arguments,
                            output = trace.result,
                            elapsedMillis = trace.elapsedMillis,
                            ok = trace.status != ToolTraceStatus.FAILED,
                            running = trace.status == ToolTraceStatus.RUNNING,
                        )
                    }
                    GlassBubble(
                        text = streamingText,
                        isUser = false,
                        thinking = streamingThinking.ifBlank { null },
                        thinkingExpanded = thinkingExpanded,
                        onToggleThinking = onToggleThinking,
                        isStreaming = true,
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
                    GlassBubble(
                        text = message.text,
                        isUser = false,
                        thinking = message.thinking,
                        thinkingExpanded = expanded,
                        onToggleThinking = { onToggleThinking(message.id) },
                        errorMessage = message.errorMessage,
                        usage = message.usage?.toGlassUsage(),
                    )
                }
            }
        }
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
        material = GlassMaterial.ULTRA_THIN,
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
