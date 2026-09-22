package com.rickeal.agent.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.GlassBottomBar
import com.rickeal.agent.core.design.GlassChip
import com.rickeal.agent.core.design.GlassIconButton
import com.rickeal.agent.core.design.GlassMaterial
import com.rickeal.agent.core.design.LocalGlassColors
import com.rickeal.agent.core.design.LocalGlassTokens
import com.rickeal.agent.core.design.liquidGlass
import com.rickeal.agent.core.model.Attachment

@Composable
fun ChatInputBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    attachments: List<Attachment>,
    onRemoveAttachment: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isGenerating: Boolean,
    onPickImage: () -> Unit,
    onPickAudio: () -> Unit,
    supportsImages: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current
    val canSend = draft.isNotBlank() || attachments.isNotEmpty()

    Column(modifier = modifier.fillMaxWidth()) {
        if (attachments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (attachment in attachments) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GlassChip(text = attachment.label())
                        // 触摸目标由组件内的 size 撑满 48dp；图标保持原来的 16dp。
                        GlassIconButton(
                            icon = Icons.Filled.Close,
                            contentDescription = "移除附件",
                            onClick = { onRemoveAttachment(attachment.key()) },
                            contentColor = colors.onGlassSubtle,
                            iconSize = 16.dp,
                        )
                    }
                }
            }
        }
        GlassBottomBar(modifier = Modifier.fillMaxWidth()) {
            if (supportsImages) {
                // 输入区图标原本 24dp（48dp 触摸区 + 12dp 内边距），尺寸原样保留。
                GlassIconButton(
                    icon = Icons.Filled.Image,
                    contentDescription = "添加图片",
                    onClick = onPickImage,
                    contentColor = colors.onGlassMuted,
                    iconSize = 24.dp,
                )
            }
            GlassIconButton(
                icon = Icons.Filled.Audiotrack,
                contentDescription = "添加音频",
                onClick = onPickAudio,
                contentColor = colors.onGlassMuted,
                iconSize = 24.dp,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .padding(horizontal = 6.dp)
                    .liquidGlass(
                        material = GlassMaterial.ULTRA_THIN,
                        cornerRadius = tokens.radiusMd,
                        // 输入框聚焦后逐帧重绘，色散（7 次采样）在这里最贵 —— 显式关掉。
                        // 写死而不是依赖默认值：以后默认值被翻回去时这里不会跟着打开。
                        dispersion = false,
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (draft.isEmpty()) {
                    Text(
                        text = "问点什么…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onGlassSubtle,
                    )
                }
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onGlass),
                    cursorBrush = SolidColor(colors.accent),
                    decorationBox = { innerTextField -> innerTextField() },
                )
            }
            SendButton(
                isGenerating = isGenerating,
                enabled = canSend,
                onSend = onSend,
                onStop = onStop,
            )
        }
    }
}

@Composable
private fun SendButton(
    isGenerating: Boolean,
    enabled: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val colors = LocalGlassColors.current
    val active = if (isGenerating) true else enabled
    // containerColor != null 会自动关掉玻璃（强调色不透明，叠了看不见），
    // 但仍保留跟手形变 —— 这正是「液态」的部分。尺寸取默认 48dp。
    GlassIconButton(
        icon = if (isGenerating) Icons.Filled.Stop else Icons.Filled.ArrowUpward,
        contentDescription = if (isGenerating) "停止" else "发送",
        onClick = { if (isGenerating) onStop() else onSend() },
        enabled = active,
        containerColor = if (active) colors.accent else colors.accentMuted,
        contentColor = colors.onAccent,
    )
}
