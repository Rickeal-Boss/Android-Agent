package com.rickeal.agent.feature.models

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.GlassButton
import com.rickeal.agent.core.design.GlassCard
import com.rickeal.agent.core.design.GlassDialog
import com.rickeal.agent.core.design.GlassMaterial
import com.rickeal.agent.core.design.LocalGlassColors
import com.rickeal.agent.core.design.LocalGlassTokens

/**
 * 「一键获取模型」对话框 —— 面向完全不懂技术的小白用户。
 *
 * 设计原则：
 *  1. **不要出现任何术语**：不提 .litertlm / .task / 量化格式 / 后端。
 *  2. **替用户做决定**：推荐项置顶并标「新手推荐」，而不是丢 8 个名字让他选。
 *  3. **把代价说在前面**：体积、建议内存、是否需要 Wi-Fi，全部在点击前可见。
 *  4. **下载中给确定性**：百分比 + 可取消，避免「卡住了吗」的焦虑。
 */
@Composable
fun RecommendedModelDialog(
    downloadingName: String?,
    downloadPercent: Int?,
    onPick: (ModelPreset) -> Unit,
    onCancelDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current

    GlassDialog(
        onDismissRequest = onDismiss,
        title = "选择要下载的模型",
        dismissLabel = "关闭",
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "模型大小约 1~4GB，建议连接 Wi-Fi 后下载。下载完成会自动设为当前模型，之后就可以直接聊天了。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onGlassMuted,
                )
                Spacer(modifier = Modifier.height(tokens.gapMd))

                if (downloadingName != null) {
                    // 下载中：给进度与取消，禁止重复点击
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "正在下载：$downloadingName",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onGlass,
                            )
                            Text(
                                text = "已完成 ${downloadPercent ?: 0}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onGlassMuted,
                            )
                        }
                        Spacer(modifier = Modifier.width(tokens.gapSm))
                        GlassButton(
                            text = "取消",
                            onClick = onCancelDownload,
                            material = GlassMaterial.THIN,
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        for (preset in ModelPresets.recommendedFirst) {
                            PresetRow(
                                preset = preset,
                                onClick = { onPick(preset) },
                            )
                            Spacer(modifier = Modifier.height(tokens.gapSm))
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun PresetRow(
    preset: ModelPreset,
    onClick: () -> Unit,
) {
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        material = if (preset.recommended) GlassMaterial.THICK else GlassMaterial.THIN,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(tokens.gapMd),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = preset.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onGlass,
                    modifier = Modifier.weight(1f),
                )
                if (preset.recommended) {
                    Spacer(modifier = Modifier.width(tokens.gapSm))
                    Text(
                        text = "新手推荐",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.accent,
                    )
                }
            }
            Text(
                text = "体积 ${preset.sizeText} · 建议可用内存 ${preset.ramText}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onGlassMuted,
                modifier = Modifier.padding(top = tokens.gapSm),
            )
            Text(
                text = preset.note,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onGlassSubtle,
                modifier = Modifier.padding(top = tokens.gapSm),
            )
        }
    }
}
