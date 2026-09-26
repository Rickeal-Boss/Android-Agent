package com.rickeal.agent.feature.settings.tools

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
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
import com.rickeal.agent.core.design.GlassIconButton
import com.rickeal.agent.core.design.GlassIconButtonShape
import com.rickeal.agent.core.design.GlassScaffold
import com.rickeal.agent.core.design.GlassSegmented
import com.rickeal.agent.core.design.GlassSettingRow
import com.rickeal.agent.core.design.GlassSwitch
import com.rickeal.agent.core.design.GlassTextField
import com.rickeal.agent.core.design.GlassTopBar
import com.rickeal.agent.core.design.LocalBottomBarOverlay
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
                        // 对齐参考形态（iOS 26 返回钮）：玻璃圆钮 + 深色 chevron，无文字。
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                tint = colors.onGlass,
                                modifier = Modifier.size(18.dp),
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
                .padding(horizontal = 14.dp, vertical = 12.dp)
                // 悬浮页签占位（2026-09-26）：加在滚动内容**之内**，末尾条目能滚出
                // 页签区；内容本体仍从玻璃页签底下穿过（见 LocalBottomBarOverlay KDoc）。
                .padding(bottom = LocalBottomBarOverlay.current),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.tools.isEmpty()) {
                GlassEmptyState(
                    title = "没有已注册的工具",
                    subtitle = "内置工具在 AppContainer 启动时装配",
                )
            } else {
                // 搜索框：匹配工具名 / 描述（大小写不敏感，逻辑在 ViewModel）。
                GlassTextField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    placeholder = "搜索工具",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 顶部分段：全部 / 已启用。分段是二值切换，selectedIndex 由 enabledOnly 决定。
                GlassSegmented(
                    items = listOf("全部", "已启用"),
                    selectedIndex = if (state.enabledOnly) 1 else 0,
                    onSelected = { index -> viewModel.onEnabledOnlyChange(index == 1) },
                )
                // 分类 chip 行：横向滚动 —— 分类多时换行会把工具列表整体顶下去。
                // 「全部」是 `category == null` 的显式入口（点击即清除分类筛选）。
                // 边缘渐隐（复审 U1，2026-09-26）：滚到中途的行如果右边缘被屏幕硬切，
                // 看起来像"内容坏了"—— 在**可滚动方向**的那一侧加 DstIn alpha 渐隐遮罩，
                // 滚到头的一侧不画。scroll 状态在 draw 相位读取（快照自动触发重绘，
                // 不经重组）。graphicsLayer 必须在 drawWithContent **之前**、
                // horizontalScroll **之后**包住视口：Offscreen 层让 DstIn 只作用于本
                // 节点像素，而不是把底下的壁纸一起乘掉。
                val chipsScroll = rememberScrollState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            val edgePx = 24.dp.toPx()
                            val canScrollBack = chipsScroll.value > 0
                            val canScrollForward = chipsScroll.value < chipsScroll.maxValue
                            if (canScrollBack) {
                                drawRect(
                                    brush = Brush.horizontalGradient(
                                        listOf(Color.Transparent, Color.Black),
                                    ),
                                    size = Size(edgePx, size.height),
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                            if (canScrollForward) {
                                drawRect(
                                    brush = Brush.horizontalGradient(
                                        listOf(Color.Black, Color.Transparent),
                                    ),
                                    topLeft = Offset(size.width - edgePx, 0f),
                                    size = Size(edgePx, size.height),
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                        }
                        .horizontalScroll(chipsScroll),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    GlassChip(
                        text = "全部",
                        selected = state.category == null,
                        onClick = { viewModel.onCategoryChange(null) },
                    )
                    for (category in state.categories) {
                        GlassChip(
                            text = toolCategoryLabel(category),
                            selected = state.category == category,
                            onClick = { viewModel.onCategoryChange(category) },
                        )
                    }
                }
            }

            // 有工具但被筛没了：给一句可行动的提示，别让页面看起来"坏了"。
            if (state.tools.isNotEmpty() && state.visibleTools.isEmpty()) {
                GlassEmptyState(
                    title = "没有匹配的工具",
                    subtitle = "换个关键词或分类试试",
                )
            }
            for (tool in state.visibleTools) {
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
        }
    }
}
