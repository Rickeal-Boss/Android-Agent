package com.rickeal.agent.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.GlassCard
import com.rickeal.agent.core.design.GlassChip
import com.rickeal.agent.core.design.GlassDialog
import com.rickeal.agent.core.design.GlassEmptyState
import com.rickeal.agent.core.design.GlassFab
import com.rickeal.agent.core.design.GlassScaffold
import com.rickeal.agent.core.design.GlassSlider
import com.rickeal.agent.core.design.GlassSwitch
import com.rickeal.agent.core.design.GlassTextField
import com.rickeal.agent.core.design.GlassTopBar
import com.rickeal.agent.core.design.LocalGlassColors
import com.rickeal.agent.core.design.LocalGlassTokens
import com.rickeal.agent.core.model.AgentLogStore
import com.rickeal.agent.core.model.RemoteEndpoint
import com.rickeal.agent.core.model.RemotePreset
import com.rickeal.agent.core.model.ThinkingParamStyle
import kotlin.math.roundToInt

@Composable
fun EndpointsScreen(
    viewModel: SettingsViewModel,
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
                title = "远程端点",
                subtitle = "OpenAI 兼容接口",
                modifier = Modifier.statusBarsPadding(),
                navigationIcon = {
                    Box(
                        modifier = Modifier
                            .size(tokens.minTouchTarget)
                            .clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
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
        floatingActionButton = {
            GlassFab(
                onClick = { viewModel.onEditEndpoint(RemoteEndpoint(preset = RemotePreset.CUSTOM)) },
                expanded = true,
                label = "新增",
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = colors.onGlass,
                    modifier = Modifier.size(22.dp),
                )
            }
        },
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.endpoints.isEmpty()) {
                GlassEmptyState(
                    title = "还没有端点",
                    subtitle = "点右下角新增一个 OpenAI 兼容端点",
                )
            }
            for (endpoint in state.endpoints) {
                EndpointCard(
                    endpoint = endpoint,
                    isActive = endpoint.id == state.activeEndpointId,
                    onSelect = { viewModel.onSelectEndpoint(endpoint.id) },
                    onEdit = { viewModel.onEditEndpoint(endpoint) },
                    onDelete = { viewModel.onDeleteEndpoint(endpoint.id) },
                )
            }
            BottomSpacer(modifier = Modifier.size(tokens.bottomBarHeight))
        }
    }

    val editing = state.editing
    if (editing != null) {
        EndpointEditDialog(
            endpoint = editing,
            onDismiss = { viewModel.onEditEndpoint(null) },
            onConfirm = { viewModel.onSaveEndpoint(it) },
        )
    }
}

@Composable
private fun BottomSpacer(modifier: Modifier) {
    Box(modifier = modifier)
}

@Composable
private fun EndpointCard(
    endpoint: RemoteEndpoint,
    isActive: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalGlassColors.current
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onSelect,
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    // 上屏前必须过一遍脱敏：name / baseUrl 都是用户自由输入，
                    // 自建反代把 key 塞进 query（`…/v1?key=sk-xxx`）很常见，
                    // 原文渲染就是把凭据打到屏幕上。sanitizeUserFacing 只替换值、
                    // 保留参数名，脱敏后仍看得出是哪个参数漏了。
                    Text(
                        text = AgentLogStore.sanitizeUserFacing(
                            endpoint.name.ifBlank { endpoint.preset.name },
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onGlass,
                    )
                    Text(
                        text = AgentLogStore.sanitizeUserFacing(endpoint.chatCompletionsUrl()),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onGlassSubtle,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Text(
                        text = "model=${endpoint.modelId.ifBlank { "未设置" }} · ctx=${endpoint.contextLength}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onGlassSubtle,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (isActive) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "当前端点",
                        tint = colors.accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (endpoint.supportsTools) GlassChip(text = "工具")
                if (endpoint.supportsVision) GlassChip(text = "视觉")
                if (endpoint.supportsThinking) GlassChip(text = "思考")
                if (endpoint.requiresApiKey) GlassChip(text = "需要 Key")
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 「编辑 / 删除」是 Text + clickable，天然只有字高（≈24dp）。
                // 用 sizeIn 把点击区撑到 48dp（sizeIn 放在 clickable 之前，
                // 先确定最小尺寸再挂点击，语义也更清楚）。
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .clickable(onClick = onEdit),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "编辑",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.accent,
                    )
                }
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .clickable(onClick = onDelete),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "删除",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.danger,
                    )
                }
            }
        }
    }
}

@Composable
private fun EndpointEditDialog(
    endpoint: RemoteEndpoint,
    onDismiss: () -> Unit,
    onConfirm: (RemoteEndpoint) -> Unit,
) {
    val colors = LocalGlassColors.current
    // 刻意**仍是 remember**：RemoteEndpoint 是 kotlinx.serialization 的 @Serializable，
    // 但不是 Parcelable / java.io.Serializable —— rememberSaveable 的 autoSaver 存不了它，
    // 硬改会在旋转时抛 IllegalArgumentException。真要保住编辑草稿，得单独写一个
    // mapSaver 把 id/baseUrl/modelId… 逐个字段存下来，本轮先留着并标注（见回报）。
    var draft by remember(endpoint.id) { mutableStateOf(endpoint) }

    GlassDialog(
        onDismissRequest = onDismiss,
        title = if (endpoint.modelId.isBlank() && endpoint.baseUrl.isBlank()) "新增端点" else "编辑端点",
        confirmLabel = "保存",
        onConfirm = { onConfirm(draft) },
        dismissLabel = "取消",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 限高必须保留：Dialog 高度约束无界时 verticalScroll 会抛
                // IllegalStateException —— 这里只是再叠加 IME / 导航栏避让。
                // Dialog 跑在独立窗口里，420.dp 高 + 键盘几乎必然重叠；
                // 顺序「先 ime 后 nav」（Type.ime() 不含导航栏高度，两者相加）。
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding(),
        ) {
            Field("名称") {
                GlassTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it) },
                    placeholder = "例如 DeepSeek",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Field("Base URL") {
                GlassTextField(
                    value = draft.baseUrl,
                    onValueChange = { draft = draft.copy(baseUrl = it) },
                    placeholder = "https://api.openai.com/v1",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Field("API Key") {
                GlassTextField(
                    value = draft.apiKey,
                    onValueChange = { draft = draft.copy(apiKey = it) },
                    placeholder = "sk-…（明文存于应用私有目录）",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Field("Model ID") {
                GlassTextField(
                    value = draft.modelId,
                    onValueChange = { draft = draft.copy(modelId = it) },
                    placeholder = "gpt-4o-mini",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Field("上下文长度 ${draft.contextLength}") {
                GlassSlider(
                    value = draft.contextLength.toFloat(),
                    onValueChange = { draft = draft.copy(contextLength = it.roundToInt()) },
                    valueRange = 1024f..200000f,
                )
            }
            ToggleLine("支持工具调用", draft.supportsTools) {
                draft = draft.copy(supportsTools = it)
            }
            ToggleLine("支持视觉输入", draft.supportsVision) {
                draft = draft.copy(supportsVision = it)
            }
            ToggleLine("支持思考输出", draft.supportsThinking) {
                draft = draft.copy(supportsThinking = it)
            }
            if (draft.supportsThinking) {
                Text(
                    text = "思考参数风格",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onGlassMuted,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    GlassChip(
                        text = "enable_thinking",
                        selected = draft.thinkingParam == ThinkingParamStyle.ENABLE_THINKING_BOOL,
                        onClick = {
                            draft = draft.copy(thinkingParam = ThinkingParamStyle.ENABLE_THINKING_BOOL)
                        },
                    )
                    GlassChip(
                        text = "reasoning_effort",
                        selected = draft.thinkingParam == ThinkingParamStyle.REASONING_EFFORT,
                        onClick = {
                            draft = draft.copy(thinkingParam = ThinkingParamStyle.REASONING_EFFORT)
                        },
                    )
                    GlassChip(
                        text = "chat_template_kwargs",
                        selected = draft.thinkingParam == ThinkingParamStyle.CHAT_TEMPLATE_KWARGS,
                        onClick = {
                            draft = draft.copy(thinkingParam = ThinkingParamStyle.CHAT_TEMPLATE_KWARGS)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    val colors = LocalGlassColors.current
    Column(modifier = Modifier.padding(top = 10.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = colors.onGlassSubtle,
        )
        content()
    }
}

@Composable
private fun ToggleLine(text: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    val colors = LocalGlassColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onGlass,
            modifier = Modifier.weight(1f),
        )
        GlassSwitch(checked = checked, onCheckedChange = onToggle)
    }
}
