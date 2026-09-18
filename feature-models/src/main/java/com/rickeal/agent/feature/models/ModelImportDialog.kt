package com.rickeal.agent.feature.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.GlassCard
import com.rickeal.agent.core.design.GlassChip
import com.rickeal.agent.core.design.GlassDialog
import com.rickeal.agent.core.design.LocalGlassColors
import com.rickeal.agent.core.model.ModelCapabilities

/**
 * 导入确认弹窗：文件名启发式只能猜个大概，让用户在导入后立刻校正能力位。
 * 能力位直接决定 UI 上「图片按钮 / 思考开关 / 工具开关」是否可用（架构 §5.2）。
 */
@Composable
fun ModelImportDialog(
    fileName: String,
    capabilities: ModelCapabilities,
    onCapabilitiesChange: (ModelCapabilities) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = LocalGlassColors.current
    GlassDialog(
        onDismissRequest = onDismiss,
        title = "导入模型",
        confirmLabel = "导入",
        onConfirm = onConfirm,
        dismissLabel = "取消",
    ) {
        Column {
            Text(
                text = fileName,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onGlass,
            )
            Text(
                text = "文件会被复制到 App 私有目录（卸载即清理）。能力位按文件名猜测，请按需校正：",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onGlassSubtle,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ToggleChip("图片", capabilities.image) {
                    onCapabilitiesChange(capabilities.copy(image = it))
                }
                ToggleChip("音频", capabilities.audio) {
                    onCapabilitiesChange(capabilities.copy(audio = it))
                }
            }
            Row(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ToggleChip("工具调用", capabilities.toolCalling) {
                    onCapabilitiesChange(capabilities.copy(toolCalling = it))
                }
                ToggleChip("思考模式", capabilities.thinking) {
                    onCapabilitiesChange(capabilities.copy(thinking = it))
                }
            }
        }
    }
}

@Composable
private fun ToggleChip(text: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    GlassChip(
        text = text,
        selected = checked,
        onClick = { onToggle(!checked) },
    )
}

@Composable
fun HowToGetModelsCard(modifier: Modifier = Modifier, importDirPath: String = "") {
    val colors = LocalGlassColors.current
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Column {
            Text(
                text = "如何获取模型",
                style = MaterialTheme.typography.titleMedium,
                color = colors.onGlass,
            )
            Text(
                text = "本项目构建期与安装后都不含任何模型权重，需要你自行下载后导入。" +
                    "支持 .litertlm / .task / .bin / .tflite。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onGlassMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                text = "推荐来源：Hugging Face 上搜索 “litertlm”、“Gemma 3n”、“Qwen3 4B” 的" +
                    " LiteRT-LM / MediaPipe 转换版本（例如 google/gemma-3n-E2B-it-litert-lm）。" +
                    " 4B 级别模型通常 2~4GB，请确保设备有 ≥8GB 内存。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onGlassMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                text = "也可以 adb push 到：$importDirPath，然后点右下角扫描。",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onGlassSubtle,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
