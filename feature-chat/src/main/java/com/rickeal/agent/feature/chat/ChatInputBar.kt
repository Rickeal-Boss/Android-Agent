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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.GlassBottomBar
import com.rickeal.agent.core.design.GlassButton
import com.rickeal.agent.core.design.GlassChip
import com.rickeal.agent.core.design.GlassIconButton
import com.rickeal.agent.core.design.GlassMaterial
import com.rickeal.agent.core.design.LiquidDialog
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
    // 附件面板显隐。用 rememberSaveable：旋转 / 折叠展开重建 Activity 时面板不该自己关掉
    //（与 ModelsScreen 的 showPresetDialog 同一处置）。
    var showAttachmentPanel by rememberSaveable { mutableStateOf(false) }

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
            // 附件入口收编（Wave 10 Phase 2b）：原来的「图片 / 音频」两个按钮合并成一个「+」，
            // 点开附件面板再选类型 —— 与参考设计一致，也给输入框让出更多宽度。
            // 回调仍是现成的 onPickImage / onPickAudio ⇒ ChatScreen / ChatViewModel 零改动。
            // 尺寸口径沿用原图标：24dp 图标 + 默认 48dp 触摸区（不因换组件而偷偷改大小）。
            GlassIconButton(
                icon = Icons.Filled.Add,
                contentDescription = "添加附件",
                onClick = { showAttachmentPanel = true },
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
        // 附件面板：模态选择「图片 / 音频」。两个选项放在 LiquidDialog 的**动作区** ——
        // 动作区回调拿到的 `dismiss` 会先播完出场动画再真正关闭（见 LiquidDialog KDoc）；
        // 若在内容区直接翻转 showAttachmentPanel，弹窗会"瞬间消失"。
        // supportsImages 为 false（当前模型不吃图片）时不列出图片项；音频不受此门控。
        if (showAttachmentPanel) {
            LiquidDialog(
                onDismissRequest = { showAttachmentPanel = false },
                title = "添加附件",
                subtitle = "选择要添加的内容类型",
                actions = { dismiss ->
                    if (supportsImages) {
                        GlassButton(
                            text = "图片",
                            onClick = { onPickImage(); dismiss() },
                            material = GlassMaterial.THIN,
                        )
                    }
                    GlassButton(
                        text = "音频",
                        onClick = { onPickAudio(); dismiss() },
                    )
                },
                content = {
                    Text(
                        text = if (supportsImages) {
                            "图片和音频会随下一条消息一起发送。"
                        } else {
                            "当前模型不支持图片输入，只能添加音频。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onGlassMuted,
                    )
                },
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
