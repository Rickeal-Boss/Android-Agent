package com.rickeal.agent.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.GlassCard
import com.rickeal.agent.core.design.GlassScaffold
import com.rickeal.agent.core.design.GlassSegmented
import com.rickeal.agent.core.design.GlassSettingRow
import com.rickeal.agent.core.design.GlassSlider
import com.rickeal.agent.core.design.GlassSwitch
import com.rickeal.agent.core.design.GlassTextField
import com.rickeal.agent.core.design.GlassTopBar
import com.rickeal.agent.core.data.DarkMode
import com.rickeal.agent.core.design.LocalGlassColors
import com.rickeal.agent.core.design.LocalGlassTokens
import com.rickeal.agent.core.model.ThinkingMode

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenEndpoints: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenLegal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current

    GlassScaffold(
        modifier = modifier,
        topBar = {
            GlassTopBar(
                title = "设置",
                subtitle = "主题 · 推理参数 · 端点 · 工具",
                modifier = Modifier.statusBarsPadding(),
            )
        },
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            /* ---------------------------------------------------- 外观 */
            GlassCard(contentPadding = PaddingValues(14.dp)) {
                Column {
                    GroupTitle("外观")
                    GlassSegmented(
                        items = listOf("浅色", "深色", "跟随系统"),
                        selectedIndex = when (state.theme.darkMode) {
                            DarkMode.LIGHT -> 0
                            DarkMode.DARK -> 1
                            DarkMode.SYSTEM -> 2
                        },
                        onSelected = { index ->
                            val mode = when (index) {
                                0 -> DarkMode.LIGHT
                                1 -> DarkMode.DARK
                                else -> DarkMode.SYSTEM
                            }
                            viewModel.onThemeChange(state.theme.copy(darkMode = mode))
                        },
                    )
                    GlassSlider(
                        value = state.theme.glassIntensity,
                        // 拖动期间只改内存（onThemePreview）—— 这条会触发全 App 重组 +
                        // 每个玻璃节点重画，再叠加写盘必然掉帧；松手才落盘一次。
                        onValueChange = { viewModel.onThemePreview(state.theme.copy(glassIntensity = it)) },
                        onValueChangeFinished = { viewModel.onThemeCommit() },
                        label = "玻璃质感强度",
                        valueText = "%.2f".format(state.theme.glassIntensity),
                        valueRange = 0.5f..1.5f,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "噪点微纹理",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onGlass,
                            )
                            Text(
                                text = "关闭后玻璃更干净，但大面积纯色会略显塑料感",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onGlassSubtle,
                            )
                        }
                        GlassSwitch(
                            checked = state.theme.enableNoise,
                            onCheckedChange = {
                                viewModel.onThemeChange(state.theme.copy(enableNoise = it))
                            },
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "减弱动效",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onGlass,
                            )
                            Text(
                                text = "弹簧变柔和，材质降到 THIN，低端机更稳",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onGlassSubtle,
                            )
                        }
                        GlassSwitch(
                            checked = state.theme.reduceMotion,
                            onCheckedChange = {
                                viewModel.onThemeChange(state.theme.copy(reduceMotion = it))
                            },
                        )
                    }
                }
            }

            /* ---------------------------------------------------- 推理参数 */
            GlassCard(contentPadding = PaddingValues(14.dp)) {
                Column {
                    GroupTitle("默认推理参数")
                    GlassSlider(
                        value = state.config.sampling.temperature,
                        // 与 ChatParamsPanel 同约定：拖动只 preview，松手才 commit，
                        // 否则 onValueChange 每帧一次 DataStore 事务（约 60 次/秒）。
                        onValueChange = { v ->
                            viewModel.onConfigPreview {
                                it.copy(sampling = it.sampling.copy(temperature = v))
                            }
                        },
                        onValueChangeFinished = { viewModel.onConfigCommit() },
                        label = "Temperature",
                        valueText = "%.2f".format(state.config.sampling.temperature),
                        valueRange = 0f..2f,
                    )
                    GlassSlider(
                        value = state.config.sampling.topP,
                        onValueChange = { v ->
                            viewModel.onConfigPreview {
                                it.copy(sampling = it.sampling.copy(topP = v))
                            }
                        },
                        onValueChangeFinished = { viewModel.onConfigCommit() },
                        label = "Top-P",
                        valueText = "%.2f".format(state.config.sampling.topP),
                        valueRange = 0f..1f,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    GlassSlider(
                        value = state.config.maxTokens.toFloat(),
                        onValueChange = { v ->
                            viewModel.onConfigPreview { it.copy(maxTokens = v.toInt()) }
                        },
                        onValueChangeFinished = { viewModel.onConfigCommit() },
                        label = "最大输出 Token",
                        valueText = "${state.config.maxTokens}",
                        valueRange = 64f..8192f,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    GlassSlider(
                        value = state.config.contextLength.toFloat(),
                        onValueChange = { v ->
                            viewModel.onConfigPreview { it.copy(contextLength = v.toInt()) }
                        },
                        onValueChangeFinished = { viewModel.onConfigCommit() },
                        label = "上下文长度",
                        valueText = "${state.config.contextLength}",
                        valueRange = 512f..32768f,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        text = "思考模式",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onGlassMuted,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    GlassSegmented(
                        items = listOf("关闭", "开启", "自动"),
                        selectedIndex = when (state.config.thinking) {
                            ThinkingMode.OFF -> 0
                            ThinkingMode.ON -> 1
                            ThinkingMode.AUTO -> 2
                        },
                        onSelected = { index ->
                            viewModel.onConfigChange {
                                it.copy(
                                    thinking = when (index) {
                                        0 -> ThinkingMode.OFF
                                        1 -> ThinkingMode.ON
                                        else -> ThinkingMode.AUTO
                                    },
                                )
                            }
                        },
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        text = "系统提示词",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onGlassMuted,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    GlassTextField(
                        value = state.config.systemInstruction,
                        onValueChange = { v ->
                            viewModel.onConfigChange { it.copy(systemInstruction = v) }
                        },
                        placeholder = "留空则使用模型默认行为",
                        maxLines = 5,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    )
                }
            }

            /* ---------------------------------------------------- 入口 */
            GlassCard(contentPadding = PaddingValues(0.dp)) {
                Column {
                    GlassSettingRow(
                        title = "远程端点",
                        subtitle = "OpenAI 兼容：baseUrl / apiKey / modelId",
                        onClick = onOpenEndpoints,
                        trailing = {
                            Icon(
                                imageVector = Icons.Filled.Cloud,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                    GlassSettingRow(
                        title = "Agent 工具",
                        subtitle = "开关与单工具试跑",
                        onClick = onOpenTools,
                        trailing = {
                            Icon(
                                imageVector = Icons.Filled.Build,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                    GlassSettingRow(
                        title = "诊断信息",
                        subtitle = "最近的运行日志：异常与决策点（仅内存，最多 200 条）",
                        onClick = onOpenDiagnostics,
                        trailing = {
                            Icon(
                                imageVector = Icons.Filled.Info,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                    GlassSettingRow(
                        title = "条款与授权",
                        subtitle = "回看应用服务条款与 Gemma 授权，含官方原文入口",
                        onClick = onOpenLegal,
                        trailing = {
                            Icon(
                                imageVector = Icons.Filled.Description,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                }
            }

            val notice = state.error ?: state.message
            if (notice != null) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.error != null) colors.danger else colors.onGlassMuted,
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(
                    text = "LiquidAgent · 端侧 Agent 运行时 · Apache-2.0",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onGlassSubtle,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .size(tokens.bottomBarHeight),
            )
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    val colors = LocalGlassColors.current
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = colors.onGlass,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}
