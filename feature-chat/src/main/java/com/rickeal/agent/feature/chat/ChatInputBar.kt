package com.rickeal.agent.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.GlassBottomBar
import com.rickeal.agent.core.design.GlassChip
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
                        // 图标只有 16dp，但**点击区**必须 48dp：外面套一层 Box 撑开，
                        // 图标本身尺寸不变（直接给 Icon 加 sizeIn 会因为外层 size(16)
                        // 把 min 又压回 16，撑不开）。
                        Box(
                            modifier = Modifier
                                .size(tokens.minTouchTarget)
                                .clickable { onRemoveAttachment(attachment.key()) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "移除附件",
                                tint = colors.onGlassSubtle,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
        GlassBottomBar(modifier = Modifier.fillMaxWidth()) {
            if (supportsImages) {
                Icon(
                    imageVector = Icons.Filled.Image,
                    contentDescription = "添加图片",
                    tint = colors.onGlassMuted,
                    modifier = Modifier
                        .size(tokens.minTouchTarget)
                        .clip(CircleShape)
                        .clickable(onClick = onPickImage)
                        .padding(12.dp),
                )
            }
            Icon(
                imageVector = Icons.Filled.Audiotrack,
                contentDescription = "添加音频",
                tint = colors.onGlassMuted,
                modifier = Modifier
                    .size(tokens.minTouchTarget)
                    .clip(CircleShape)
                    .clickable(onClick = onPickAudio)
                    .padding(12.dp),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .padding(horizontal = 6.dp)
                    .liquidGlass(
                        material = GlassMaterial.ULTRA_THIN,
                        cornerRadius = tokens.radiusMd,
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
    val tokens = LocalGlassTokens.current
    val active = if (isGenerating) true else enabled
    Box(
        modifier = Modifier
            // 发送/停止是最高频操作，44dp 差 4dp —— 补到 minTouchTarget，
            // 圆角半径同步取一半（保持正圆）。
            .size(tokens.minTouchTarget)
            .clip(RoundedCornerShape(tokens.minTouchTarget / 2))
            .background(if (active) colors.accent else colors.accentMuted)
            .then(
                if (active) {
                    Modifier.clickable { if (isGenerating) onStop() else onSend() }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isGenerating) Icons.Filled.Stop else Icons.Filled.ArrowUpward,
            contentDescription = if (isGenerating) "停止" else "发送",
            tint = colors.onAccent,
            modifier = Modifier.size(20.dp),
        )
    }
}
