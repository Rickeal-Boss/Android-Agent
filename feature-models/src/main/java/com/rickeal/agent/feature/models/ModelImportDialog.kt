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
import com.rickeal.agent.core.design.GlassButton
import com.rickeal.agent.core.design.GlassCard
import com.rickeal.agent.core.design.GlassChip
import com.rickeal.agent.core.design.GlassMaterial
import com.rickeal.agent.core.design.LiquidDialog
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
    LiquidDialog(
        onDismissRequest = onDismiss,
        title = "导入模型",
        actions = { dismiss ->
            GlassButton(text = "取消", onClick = dismiss, material = GlassMaterial.THIN)
            GlassButton(text = "导入", onClick = {
                onConfirm()
                dismiss()
            })
        },
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

/**
 * 能力位编辑弹窗（Wave 43）：已入库模型（预设下载 / 已导入）随时校正能力位。
 *
 * 为什么要有它：能力位来自文件名启发式（[ModelHeuristics]）的「初值」，而启发式
 * 只看文件名不探测容器 —— 真机实锤案例：Gemma-4 E2B **GPU 变体**容器没有音频
 * 编码器子图（section 表只有 text_decoder），但按 Google 模型卡推断 audio=true，
 * 引擎因此请求音频后端 → 会话创建 NOT_FOUND 硬失败（litert-lm 源码
 * litert_lm_lib.cc：audio_backend 有值即无条件读 AUDIO_ENCODER_HW section）。
 * 用户手动关掉「音频」位即可解锁 —— 但此前 onEditCapabilities 是死代码，
 * 预设模型根本没有编辑入口（只有导入弹窗一次性校正）。
 *
 * 与 [ModelImportDialog] 共用 ToggleChip 组；onConfirm 由调用方接
 * `viewModel.onEditCapabilities(id, capabilities)`（持久化到 modelRepository）。
 */
@Composable
fun ModelCapabilitiesDialog(
    modelName: String,
    capabilities: ModelCapabilities,
    onCapabilitiesChange: (ModelCapabilities) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = LocalGlassColors.current
    LiquidDialog(
        onDismissRequest = onDismiss,
        title = "编辑能力位",
        actions = { dismiss ->
            GlassButton(text = "取消", onClick = dismiss, material = GlassMaterial.THIN)
            GlassButton(text = "保存", onClick = {
                onConfirm()
                dismiss()
            })
        },
    ) {
        Column {
            Text(
                text = modelName,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onGlass,
            )
            Text(
                text = "能力位决定引擎行为（如「音频」会请求音频后端）。若模型容器实际" +
                    "不含某模态的编码器子图，开启对应能力位会导致加载失败：",
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
