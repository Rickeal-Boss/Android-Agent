package com.rickeal.agent.feature.models
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import com.rickeal.agent.core.design.GlassChip
import androidx.compose.foundation.lazy.items

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Refresh
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
import com.rickeal.agent.core.design.GlassButton
import com.rickeal.agent.core.design.GlassCard
import com.rickeal.agent.core.design.GlassEmptyState
import com.rickeal.agent.core.design.GlassTextField
import com.rickeal.agent.core.design.GlassFab
import com.rickeal.agent.core.design.GlassScaffold
import com.rickeal.agent.core.design.GlassTopBar
import com.rickeal.agent.core.design.LocalGlassColors
import com.rickeal.agent.core.design.GlassMaterial
import com.rickeal.agent.core.design.LocalGlassTokens
import com.rickeal.agent.core.model.ModelCapabilities

@Composable
fun ModelsScreen(
    viewModel: ModelsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current

    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var pendingName by remember { mutableStateOf("") }
    var pendingCaps by remember { mutableStateOf(ModelCapabilities()) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            pendingUri = uri
            pendingName = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
            pendingCaps = guessCapabilities(pendingName)
        }
    }

    GlassScaffold(
        modifier = modifier,
        topBar = {
            GlassTopBar(
                title = "模型库",
                subtitle = if (state.models.isEmpty()) "还没有模型" else "共 ${state.models.size} 个",
                modifier = Modifier.statusBarsPadding(),
                actions = {
                    Box(
                        modifier = Modifier
                            .size(tokens.minTouchTarget)
                            .clickable { viewModel.onScanDirectories() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "扫描目录",
                            tint = colors.onGlassMuted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            GlassFab(
                onClick = { picker.launch(arrayOf("*/*")) },
                expanded = true,
                label = "导入模型",
            ) {
                Icon(
                    imageVector = Icons.Filled.FileDownload,
                    contentDescription = null,
                    tint = colors.onGlass,
                    modifier = Modifier.size(22.dp),
                )
            }
        },
    ) { _ ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 320.dp),
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ModelDownloadCard(
                    downloadName = state.downloadName,
                    downloadPercent = state.downloadPercent,
                    onDownload = viewModel::onDownloadFromUrl,
                    onCancel = viewModel::onCancelDownload,
                )
            }
            if (state.models.isEmpty()) {
                item {
                    GlassEmptyState(
                        title = "暂无本地模型",
                        subtitle = "点右下角导入 .litertlm / .task，或把文件放到内部 models 目录后扫描",
                    )
                }
            }
            items(items = state.models, key = { it.id }) { model ->
                ModelCard(
                    model = model,
                    isActive = model.id == state.activeModelId,
                    isLoading = model.id == state.loadingModelId,
                    isLoaded = model.id == state.loadedModelId,
                    backend = state.backend,
                    onSelect = { viewModel.onSelect(model.id) },
                    onLoad = { viewModel.onLoad(model.id) },
                    onUnload = viewModel::onUnload,
                    onProbe = { viewModel.onProbe(model.id) },
                    onDelete = { deleteFile -> viewModel.onDelete(model.id, deleteFile) },
                    onBackendChange = viewModel::onBackendChange,
                )
            }
            item {
                HowToGetModelsCard(
                    importDirPath = state.importDirPath,
                    modifier = Modifier.padding(bottom = 96.dp),
                )
            }
            item {
                val notice = state.error ?: state.message ?: state.capabilitiesText
                if (notice != null) {
                    Text(
                        text = notice,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = if (state.error != null) colors.danger else colors.onGlassMuted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 96.dp),
                    )
                }
            }
        }
    }

    val uri = pendingUri
    if (uri != null) {
        ModelImportDialog(
            fileName = pendingName,
            capabilities = pendingCaps,
            onCapabilitiesChange = { pendingCaps = it },
            onDismiss = { pendingUri = null },
            onConfirm = {
                pendingUri = null
                viewModel.onImportUri(uri)
            },
        )
    }
}

/** 导入前按文件名粗猜能力位（真正的启发式在 :core-model 的 ModelHeuristics）。 */
private fun guessCapabilities(fileName: String): ModelCapabilities {
    val lower = fileName.lowercase()
    return ModelCapabilities(
        image = lower.contains("3n") || lower.contains("gemma-3") || lower.contains("vision"),
        audio = lower.contains("3n"),
        toolCalling = lower.contains("3n") || lower.contains("qwen"),
        thinking = lower.contains("qwen3") || lower.contains("thinking") || lower.contains("-r1"),
    )
}

/** 从直链下载模型的卡片：交给系统 DownloadManager，支持后台与断点续传。 */
@Composable
private fun ModelDownloadCard(
    downloadName: String?,
    downloadPercent: Int?,
    onDownload: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current
    var url by remember { mutableStateOf("") }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "从链接下载模型",
                style = MaterialTheme.typography.titleSmall,
                color = colors.onGlass,
            )
            Text(
                text = "填写 .litertlm / .task 直链，由系统下载管理器后台下载，完成后自动登记到模型库。4B 模型约 2~4GB，建议 Wi-Fi。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onGlassMuted,
                modifier = Modifier.padding(top = tokens.gapSm),
            )
            Spacer(modifier = Modifier.height(tokens.gapMd))
            GlassTextField(
                value = url,
                onValueChange = { url = it },
                placeholder = "https://example.com/gemma-3n.litertlm",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(tokens.gapSm))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(tokens.gapSm),
            ) {
                ModelPresets.all.forEach { preset ->
                    GlassChip(
                        text = "${preset.label} · ${preset.sizeText}",
                        selected = url == preset.url,
                        onClick = { url = preset.url },
                    )
                }
            }
            if (url.isNotBlank()) {
                Text(
                    text = ModelPresets.all.firstOrNull { it.url == url }?.note ?: "自定义链接",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onGlassSubtle,
                    modifier = Modifier.padding(top = tokens.gapSm),
                )
            }
            Spacer(modifier = Modifier.height(tokens.gapMd))
            if (downloadName != null) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        text = "$downloadName ${downloadPercent ?: 0}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onGlass,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(tokens.gapSm))
                    GlassButton(text = "取消", onClick = onCancel, material = GlassMaterial.THIN)
                }
            } else {
                GlassButton(
                    text = "开始下载",
                    onClick = { onDownload(url) },
                    enabled = url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
