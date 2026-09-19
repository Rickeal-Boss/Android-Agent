package com.rickeal.agent.feature.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.GlassButton
import com.rickeal.agent.core.design.GlassCard
import com.rickeal.agent.core.design.GlassChip
import com.rickeal.agent.core.design.GlassDialog
import com.rickeal.agent.core.design.GlassMaterial
import com.rickeal.agent.core.design.GlassSegmented
import com.rickeal.agent.core.design.LocalGlassColors
import com.rickeal.agent.core.design.LocalGlassTokens
import com.rickeal.agent.core.data.DeviceCapability
import com.rickeal.agent.core.model.InferenceBackend
import com.rickeal.agent.core.model.ModelDescriptor

@Composable
fun ModelCard(
    model: ModelDescriptor,
    isActive: Boolean,
    isLoading: Boolean,
    isLoaded: Boolean,
    backend: InferenceBackend,
    onSelect: () -> Unit,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onProbe: () -> Unit,
    onDelete: (deleteFile: Boolean) -> Unit,
    onBackendChange: (InferenceBackend) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current
    var confirmDelete by remember { mutableStateOf(false) }

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        material = if (isActive) GlassMaterial.THICK else GlassMaterial.REGULAR,
        cornerRadius = tokens.radiusMd,
        onClick = if (isActive) null else onSelect,
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.displayName.ifBlank { model.fileName.ifBlank { "未命名模型" } },
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onGlass,
                    )
                    Text(
                        text = model.fileName.ifBlank { model.path },
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onGlassSubtle,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (isActive) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "当前模型",
                        tint = colors.accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GlassChip(text = model.sizeText())
                GlassChip(text = model.quantization.name)
                GlassChip(text = model.family.name)
                if (!model.exists()) {
                    GlassChip(text = "文件缺失", selected = true)
                }
            }

            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (model.capabilities.image) GlassChip(text = "图片")
                if (model.capabilities.audio) GlassChip(text = "音频")
                if (model.capabilities.toolCalling) GlassChip(text = "工具")
                if (model.capabilities.thinking) GlassChip(text = "思考")
                if (model.capabilities.speculativeDecoding) GlassChip(text = "投机解码")
            }

            Text(
                text = "计算后端",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onGlassSubtle,
                modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
            )
            GlassSegmented(
                items = listOf("CPU", "GPU", "NPU"),
                selectedIndex = when (backend) {
                    InferenceBackend.CPU -> 0
                    InferenceBackend.GPU -> 1
                    InferenceBackend.NPU -> 2
                },
                onSelected = { index ->
                    onBackendChange(
                        when (index) {
                            1 -> InferenceBackend.GPU
                            2 -> InferenceBackend.NPU
                            else -> InferenceBackend.CPU
                        },
                    )
                },
            )
            // NPU 在不支持的设备上是在 native 层崩（用户只看到闪退），所以提前给警告。
            // 注意是"警告"不是"禁用"：8650 这个门槛是估计值，硬拦会误伤能跑的设备。
            if (backend == InferenceBackend.NPU && !DeviceCapability.supportsNpu()) {
                Text(
                    text = "此设备可能不支持 NPU（建议骁龙 8 Gen 3 及以上）；若加载失败，请改用 GPU 或 CPU",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.warning,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isLoaded) {
                    GlassButton(
                        text = "卸载",
                        onClick = onUnload,
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.Stop,
                                contentDescription = null,
                                tint = colors.onGlassMuted,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    )
                } else {
                    GlassButton(
                        text = if (isLoading) "加载中" else "加载",
                        onClick = onLoad,
                        enabled = !isLoading,
                        loading = isLoading,
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = colors.onGlassMuted,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                GlassButton(
                    text = "探测",
                    onClick = onProbe,
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            tint = colors.onGlassMuted,
                            modifier = Modifier.size(14.dp),
                        )
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                )
                GlassButton(
                    text = "删除",
                    onClick = { confirmDelete = true },
                    material = GlassMaterial.THIN,
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = null,
                            tint = colors.danger,
                            modifier = Modifier.size(14.dp),
                        )
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }

    if (confirmDelete) {
        var deleteFileToo by remember { mutableStateOf(false) }
        GlassDialog(
            onDismissRequest = { confirmDelete = false },
            title = "删除模型",
            confirmLabel = "删除",
            onConfirm = {
                confirmDelete = false
                onDelete(deleteFileToo)
            },
            dismissLabel = "取消",
        ) {
            Column {
                Text(
                    text = model.fileName.ifBlank { model.path },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onGlass,
                )
                Text(
                    text = if (deleteFileToo) {
                        "将从清单移除并删除磁盘文件（不可恢复）"
                    } else {
                        "仅从清单移除，磁盘文件保留"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onGlassSubtle,
                    modifier = Modifier.padding(top = 6.dp),
                )
                GlassChip(
                    text = if (deleteFileToo) "同时删除文件 ✓" else "同时删除文件",
                    selected = deleteFileToo,
                    onClick = { deleteFileToo = !deleteFileToo },
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}
