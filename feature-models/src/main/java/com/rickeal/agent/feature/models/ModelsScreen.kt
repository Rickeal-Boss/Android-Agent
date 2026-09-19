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
import com.rickeal.agent.core.design.GlassDialog
import com.rickeal.agent.core.design.GlassCard
import com.rickeal.agent.core.design.GlassTextField
import com.rickeal.agent.core.design.GlassFab
import com.rickeal.agent.core.design.GlassSettingRow
import com.rickeal.agent.core.design.GlassSwitch
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
    var showPresetDialog by remember { mutableStateOf(false) }

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
                    downloadSpeedBytesPerSecond = state.downloadSpeedBytesPerSecond,
                    downloadEtaSeconds = state.downloadEtaSeconds,
                    allowMeteredDownload = state.allowMeteredDownload,
                    onAllowMeteredChange = viewModel::setAllowMeteredDownload,
                    onDownload = viewModel::onDownloadFromUrl,
                    onCancel = viewModel::onCancelDownload,
                    onPickRecommended = { showPresetDialog = true },
                )
            }
            if (state.models.isEmpty()) {
                item {
                    BeginnerImportCard(
                        onGetModel = { showPresetDialog = true },
                        onPickFile = { picker.launch(arrayOf("*/*")) },
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

    // 移动数据保护：GB 级模型用流量下载代价太高，先问一次
    val meteredUrl = state.meteredConfirmUrl
    if (meteredUrl != null) {
        val meteredPreset = ModelPresets.findByUrl(meteredUrl)
        GlassDialog(
            onDismissRequest = viewModel::dismissMeteredConfirm,
            title = "正在使用移动数据",
            confirmLabel = "仍然下载",
            onConfirm = viewModel::confirmMeteredDownload,
            dismissLabel = "先用 Wi-Fi",
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "即将下载 " + (meteredPreset?.sizeText ?: "数 GB") + " 的模型，当前网络可能是按流量计费的。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onGlass,
                    )
                    Text(
                        text = "建议连接 Wi-Fi 后再下载，以免产生大额流量费用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onGlassMuted,
                        modifier = Modifier.padding(top = tokens.gapSm),
                    )
                }
            },
        )
    }

    if (showPresetDialog) {
        RecommendedModelDialog(
            downloadingName = state.downloadName,
            downloadPercent = state.downloadPercent,
            onPick = { preset ->
                showPresetDialog = false
                viewModel.onDownloadFromUrl(preset.url)
            },
            onCancelDownload = viewModel::onCancelDownload,
            onDismiss = { showPresetDialog = false },
        )
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
    downloadSpeedBytesPerSecond: Long?,
    downloadEtaSeconds: Long?,
    allowMeteredDownload: Boolean,
    onAllowMeteredChange: (Boolean) -> Unit,
    onDownload: (String) -> Unit,
    onCancel: () -> Unit,
    onPickRecommended: () -> Unit,
) {
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current
    var url by remember { mutableStateOf("") }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "用链接下载模型（进阶）",
                style = MaterialTheme.typography.titleSmall,
                color = colors.onGlass,
            )
            Text(
                text = "已经知道下载地址就填在这里，系统会在后台下载，下好自动加到模型库。" +
                    "文件约 1~4GB，建议连 Wi-Fi。不知道选哪个，用下面的「一键获取模型」。",
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
                        text = "${preset.label} · ${preset.sizeText} · ${preset.ramText}",
                        selected = url == preset.url,
                        onClick = { url = preset.url },
                    )
                }
            }
            if (url.isNotBlank()) {
                Text(
                    text = ModelPresets.all.firstOrNull { it.url == url }?.let { preset ->
                        "${preset.note} · 体积 ${preset.sizeText} · 建议可用内存 ${preset.ramText}"
                    } ?: "自定义链接（请确认直链可直接下载）",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onGlassSubtle,
                    modifier = Modifier.padding(top = tokens.gapSm),
                )
            }
            Spacer(modifier = Modifier.height(tokens.gapMd))
            if (downloadName != null) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        text = downloadStatusText(
                            name = downloadName,
                            percent = downloadPercent,
                            bytesPerSecond = downloadSpeedBytesPerSecond,
                            etaSeconds = downloadEtaSeconds,
                        ),
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
                Spacer(modifier = Modifier.height(tokens.gapSm))
                GlassButton(
                    text = "不知道选哪个？一键获取模型",
                    onClick = onPickRecommended,
                    material = GlassMaterial.THIN,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(modifier = Modifier.height(tokens.gapMd))
            GlassSettingRow(
                title = "允许使用移动数据下载",
                subtitle = if (allowMeteredDownload) "已允许：下载前不再询问" else "关闭时，检测到移动数据会先问一次",
                trailing = {
                    GlassSwitch(
                        checked = allowMeteredDownload,
                        onCheckedChange = onAllowMeteredChange,
                    )
                },
            )
        }
    }
}

/**
 * 没有任何模型时的引导卡 —— 面向小白，不说术语、替用户做决定。
 *
 * 主路径是「一键下载」（用户此刻几乎肯定没有模型文件），
 * 次路径才是「我已有模型文件」（给已经下好的进阶用户）。
 */
@Composable
private fun BeginnerImportCard(
    onGetModel: () -> Unit,
    onPickFile: () -> Unit,
) {
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "还没有模型",
                style = MaterialTheme.typography.titleMedium,
                color = colors.onGlass,
            )
            Text(
                text = "可以直接下载一个（约 1~4GB，建议连 Wi-Fi），也可以选择你已经下载好的文件。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onGlassMuted,
                modifier = Modifier.padding(top = tokens.gapSm),
            )
            Spacer(modifier = Modifier.height(tokens.gapMd))
            GlassButton(
                text = "一键获取模型（推荐）",
                onClick = onGetModel,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(tokens.gapSm))
            GlassButton(
                text = "我已有模型文件",
                onClick = onPickFile,
                material = GlassMaterial.THIN,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 下载中的一行状态文案，例如 `gemma-4-E2B-it-gpu.litertlm 42% · 3.2 MB/s · 剩余 2 分 15 秒`。
 *
 * 速度 / 剩余时间拿不到时（刚起步、总大小未知、速率为 0）就只显示百分比，
 * 不留 "0 B/s" / "剩余 --" 这类没意义的占位。
 */
private fun downloadStatusText(
    name: String,
    percent: Int?,
    bytesPerSecond: Long?,
    etaSeconds: Long?,
): String {
    val builder = StringBuilder(name).append(' ').append(percent ?: 0).append('%')
    if (bytesPerSecond != null && bytesPerSecond > 0L) {
        builder.append(" · ").append(formatSpeed(bytesPerSecond))
    }
    if (etaSeconds != null && etaSeconds >= 0L) {
        builder.append(" · 剩余 ").append(formatDuration(etaSeconds))
    }
    return builder.toString()
}

/** 速率文案：≥1MB/s 用 MB/s，≥1KB/s 用 KB/s，否则用 B/s（1024 进制，与系统下载通知口径一致）。 */
private fun formatSpeed(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1_048_576L -> "%.1f MB/s".format(bytesPerSecond / 1_048_576.0)
    bytesPerSecond >= 1024L -> "%.0f KB/s".format(bytesPerSecond / 1024.0)
    else -> "$bytesPerSecond B/s"
}

/** 剩余时间文案：≥1 小时显示「x 时 y 分」，≥1 分钟显示「x 分 y 秒」，否则「x 秒」。 */
private fun formatDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    return when {
        safe >= 3600L -> "${safe / 3600L} 时 ${(safe % 3600L) / 60L} 分"
        safe >= 60L -> "${safe / 60L} 分 ${safe % 60L} 秒"
        else -> "$safe 秒"
    }
}
