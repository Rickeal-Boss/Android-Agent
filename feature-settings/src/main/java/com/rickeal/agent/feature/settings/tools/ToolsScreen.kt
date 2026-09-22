package com.rickeal.agent.feature.settings.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.GlassButton
import com.rickeal.agent.core.design.GlassCard
import com.rickeal.agent.core.design.GlassChip
import com.rickeal.agent.core.design.GlassEmptyState
import com.rickeal.agent.core.design.GlassScaffold
import com.rickeal.agent.core.design.GlassSettingRow
import com.rickeal.agent.core.design.GlassSwitch
import com.rickeal.agent.core.design.GlassTextField
import com.rickeal.agent.core.design.GlassTopBar
import com.rickeal.agent.core.design.LocalGlassColors
import com.rickeal.agent.core.design.LocalGlassTokens

@Composable
fun ToolsScreen(
    viewModel: ToolsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current

    GlassScaffold(
        modifier = modifier,
        topBar = {
            GlassTopBar(
                title = "Agent 工具",
                subtitle = "共 ${state.tools.size} 个 · 已启用 ${state.tools.count { it.enabled }} 个",
                modifier = Modifier.statusBarsPadding(),
                navigationIcon = {
                    // pressOnly：顶栏图标位于 GlassTopBar 自己的玻璃之上（见 GlassIconButton KDoc）。
                    GlassIconButton(
                        onClick = onBack,
                        shape = GlassIconButtonShape.Capsule,
                        pressOnly = true,
                    ) {
                        Text(
                            text = "返回",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.accent,
                        )
                    }
                },
            )
        },
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 「参数 JSON」输入框在卡片最下方，键盘升起时会把它盖住。
                // 顺序「先 ime 后 nav」：Type.ime() 不含导航栏高度，两者相加才对。
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.tools.isEmpty()) {
                GlassEmptyState(
                    title = "没有已注册的工具",
                    subtitle = "内置工具在 AppContainer 启动时装配",
                )
            }
            for (tool in state.tools) {
                GlassSettingRow(
                    title = tool.name,
                    subtitle = tool.description,
                    trailing = {
                        GlassSwitch(
                            checked = tool.enabled,
                            onCheckedChange = { viewModel.onToggle(tool.name, it) },
                        )
                    },
                    onClick = { viewModel.onSelectTest(tool.name) },
                )
            }

            val selected = state.testName
            if (selected != null) {
                GlassCard(contentPadding = PaddingValues(14.dp)) {
                    Column {
                        Text(
                            text = "试跑：$selected",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onGlass,
                        )
                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val spec = state.tools.firstOrNull { it.name == selected }
                            if (spec != null) {
                                GlassChip(text = spec.category)
                                if (spec.dangerous) GlassChip(text = "危险", selected = true)
                                if (spec.requiresConfirmation) GlassChip(text = "需确认")
                            }
                        }
                        GlassTextField(
                            value = state.testArgs,
                            onValueChange = viewModel::onTestArgsChange,
                            placeholder = "参数 JSON",
                            maxLines = 6,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                        )
                        GlassButton(
                            text = if (state.running) "运行中" else "运行",
                            onClick = viewModel::onRunTest,
                            enabled = !state.running,
                            loading = state.running,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        if (state.testResult != null) {
                            Text(
                                text = state.testResult.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state.testOk) colors.onGlassMuted else colors.danger,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                        if (state.error != null) {
                            Text(
                                text = state.error.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.danger,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                    }
                }
            }
            Box(modifier = Modifier.size(tokens.bottomBarHeight))
        }
    }
}
