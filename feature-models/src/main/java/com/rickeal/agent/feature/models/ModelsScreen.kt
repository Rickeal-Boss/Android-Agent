package com.rickeal.agent.feature.models

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import com.rickeal.agent.core.design.GlassEmptyState
import com.rickeal.agent.core.design.GlassFab
import com.rickeal.agent.core.design.GlassScaffold
import com.rickeal.agent.core.design.GlassTopBar
import com.rickeal.agent.core.design.LocalGlassColors
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
