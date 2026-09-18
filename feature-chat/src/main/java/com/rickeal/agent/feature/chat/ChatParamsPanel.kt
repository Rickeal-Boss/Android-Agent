package com.rickeal.agent.feature.chat
import androidx.compose.foundation.layout.weight

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.GlassCard
import com.rickeal.agent.core.design.GlassSegmented
import com.rickeal.agent.core.design.GlassSlider
import com.rickeal.agent.core.design.GlassSwitch
import com.rickeal.agent.core.design.GlassTextField
import com.rickeal.agent.core.design.LocalGlassColors
import com.rickeal.agent.core.model.EngineKind
import com.rickeal.agent.core.model.InferenceBackend
import com.rickeal.agent.core.model.InferenceConfig
import com.rickeal.agent.core.model.ThinkingMode
import kotlin.math.roundToInt

/** EXPANDED（平板/折叠屏）下的常驻参数面板。 */
@Composable
fun ChatParamsPanel(
    config: InferenceConfig,
    onParamPreview: ((InferenceConfig) -> InferenceConfig) -> Unit,
    onParamCommit: () -> Unit,
    onParamChange: ((InferenceConfig) -> InferenceConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    ChatParamsContent(
        config = config,
        onParamPreview = onParamPreview,
        onParamCommit = onParamCommit,
        onParamChange = onParamChange,
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    )
}

/**
 * 参数面板内容（底部抽屉与常驻面板共用）。
 * 降级规则见架构 §5.2：NPU 后端禁用采样三项；远程引擎隐藏后端、禁用 topK。
 */
@Composable
fun ChatParamsContent(
    config: InferenceConfig,
    onParamPreview: ((InferenceConfig) -> InferenceConfig) -> Unit,
    onParamCommit: () -> Unit,
    onParamChange: ((InferenceConfig) -> InferenceConfig) -> Unit,
    modifier: Modifier = Modifier,
    showEngineSwitch: Boolean = true,
) {
    val colors = LocalGlassColors.current
    val isLocal = config.engineKind == EngineKind.LOCAL
    val samplingEnabled = !(isLocal && config.backend == InferenceBackend.NPU)
    val topKEnabled = samplingEnabled && isLocal

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showEngineSwitch) {
            GlassCard(contentPadding = PaddingValues(14.dp)) {
                Column {
                    SectionTitle("推理位置")
                    GlassSegmented(
                        items = listOf("本地", "远程"),
                        selectedIndex = if (isLocal) 0 else 1,
                        onSelected = { index ->
                            onParamChange {
                                it.copy(engineKind = if (index == 0) EngineKind.LOCAL else EngineKind.REMOTE)
                            }
                        },
                    )
                }
            }
        }

        if (isLocal) {
            GlassCard(contentPadding = PaddingValues(14.dp)) {
                Column {
                    SectionTitle("计算后端")
                    GlassSegmented(
                        items = listOf("CPU", "GPU", "NPU"),
                        selectedIndex = when (config.backend) {
                            InferenceBackend.CPU -> 0
                            InferenceBackend.GPU -> 1
                            InferenceBackend.NPU -> 2
                        },
                        onSelected = { index ->
                            onParamChange {
                                it.copy(
                                    backend = when (index) {
                                        1 -> InferenceBackend.GPU
                                        2 -> InferenceBackend.NPU
                                        else -> InferenceBackend.CPU
                                    },
                                )
                            }
                        },
                    )
                    if (config.backend == InferenceBackend.NPU) {
                        Text(
                            text = "NPU 后端不支持自定义采样，temperature / topP / topK 已置灰（实验性）",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.warning,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }

        GlassCard(contentPadding = PaddingValues(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("采样")
                GlassSlider(
                    value = config.sampling.temperature,
                    onValueChange = { v ->
                        onParamPreview { it.copy(sampling = it.sampling.copy(temperature = v)) }
                    },
                    onValueChangeFinished = onParamCommit,
                    label = "Temperature",
                    valueText = "%.2f".format(config.sampling.temperature),
                    valueRange = 0f..2f,
                    enabled = samplingEnabled,
                )
                GlassSlider(
                    value = config.sampling.topP,
                    onValueChange = { v ->
                        onParamPreview { it.copy(sampling = it.sampling.copy(topP = v)) }
                    },
                    onValueChangeFinished = onParamCommit,
                    label = "Top-P",
                    valueText = "%.2f".format(config.sampling.topP),
                    valueRange = 0f..1f,
                    enabled = samplingEnabled,
                )
                GlassSlider(
                    value = config.sampling.topK.toFloat(),
                    onValueChange = { v ->
                        onParamPreview { it.copy(sampling = it.sampling.copy(topK = v.roundToInt())) }
                    },
                    onValueChangeFinished = onParamCommit,
                    label = "Top-K",
                    valueText = if (topKEnabled) "${config.sampling.topK}" else "仅本地引擎",
                    valueRange = 1f..200f,
                    enabled = topKEnabled,
                )
                GlassSlider(
                    value = config.sampling.repetitionPenalty,
                    onValueChange = { v ->
                        onParamPreview { it.copy(sampling = it.sampling.copy(repetitionPenalty = v)) }
                    },
                    onValueChangeFinished = onParamCommit,
                    label = "重复惩罚",
                    valueText = "%.2f".format(config.sampling.repetitionPenalty),
                    valueRange = 1f..2f,
                    enabled = samplingEnabled,
                )
            }
        }

        GlassCard(contentPadding = PaddingValues(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("长度与轮次")
                GlassSlider(
                    value = config.maxTokens.toFloat(),
                    onValueChange = { v ->
                        onParamPreview { it.copy(maxTokens = v.roundToInt()) }
                    },
                    onValueChangeFinished = onParamCommit,
                    label = "最大输出 Token",
                    valueText = "${config.maxTokens}",
                    valueRange = 64f..8192f,
                )
                GlassSlider(
                    value = config.contextLength.toFloat(),
                    onValueChange = { v ->
                        onParamPreview { it.copy(contextLength = v.roundToInt()) }
                    },
                    onValueChangeFinished = onParamCommit,
                    label = "上下文长度（裁剪预算）",
                    valueText = "${config.contextLength}",
                    valueRange = 512f..32768f,
                )
                GlassSlider(
                    value = config.maxAgentRounds.toFloat(),
                    onValueChange = { v ->
                        onParamPreview { it.copy(maxAgentRounds = v.roundToInt()) }
                    },
                    onValueChangeFinished = onParamCommit,
                    label = "Agent 最大轮次",
                    valueText = "${config.maxAgentRounds}",
                    valueRange = 1f..32f,
                    steps = 30,
                )
            }
        }

        GlassCard(contentPadding = PaddingValues(14.dp)) {
            Column {
                SectionTitle("思考模式")
                GlassSegmented(
                    items = listOf("关闭", "开启", "自动"),
                    selectedIndex = when (config.thinking) {
                        ThinkingMode.OFF -> 0
                        ThinkingMode.ON -> 1
                        ThinkingMode.AUTO -> 2
                    },
                    onSelected = { index ->
                        onParamChange {
                            it.copy(
                                thinking = when (index) {
                                    0 -> ThinkingMode.OFF
                                    1 -> ThinkingMode.ON
                                    else -> ThinkingMode.AUTO
                                },
                            )
                        }
                    },
                )
            }
        }

        GlassCard(contentPadding = PaddingValues(14.dp)) {
            Column {
                SectionTitle("系统提示词")
                GlassTextField(
                    value = config.systemInstruction,
                    onValueChange = { v -> onParamPreview { it.copy(systemInstruction = v) } },
                    placeholder = "留空则使用模型默认行为",
                    maxLines = 5,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
            }
        }

        GlassCard(contentPadding = PaddingValues(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "启用工具调用",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onGlass,
                    )
                    Text(
                        text = "关闭后 Agent 只做纯对话，不执行任何工具",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onGlassSubtle,
                    )
                }
                GlassSwitch(
                    checked = config.enableTools,
                    onCheckedChange = { checked -> onParamChange { it.copy(enableTools = checked) } },
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    val colors = LocalGlassColors.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = colors.onGlassMuted,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}
