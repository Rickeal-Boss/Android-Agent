package com.rickeal.agent.feature.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.GlassButton
import com.rickeal.agent.core.design.GlassCard
import com.rickeal.agent.core.design.GlassChip
import com.rickeal.agent.core.design.GlassMaterial
import com.rickeal.agent.core.design.GlassSegmented
import com.rickeal.agent.core.design.LiquidDialog
import com.rickeal.agent.core.design.LocalGlassColors
import com.rickeal.agent.core.design.LocalGlassTokens
import com.rickeal.agent.core.data.DeviceCapability
import com.rickeal.agent.core.model.InferenceBackend
import com.rickeal.agent.core.model.ModelDescriptor

// FlowRow 在 foundation 1.10.3 仍是 @ExperimentalLayoutApi（BOM 2026.02.00），显式 OptIn。
@OptIn(ExperimentalLayoutApi::class)
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
    // 同 ModelsScreen：卡片在 Lazy 布局里会被回收，且旋转会重建 Activity ——
    // 用 remember 的话确认框会凭空消失。
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        material = if (isActive) GlassMaterial.THICK else GlassMaterial.REGULAR,
        cornerRadius = tokens.radiusMd,
        onClick = if (isActive) null else onSelect,
    ) {
        // 高度上限 420dp：常规内容约 300dp 永不触发滚动，只兜住大字号 / 长文件名 /
        // NPU 警告等极端内容 —— 超高时在卡内滚动，而不是把网格撑出一整屏高的行
        // （行高超过视口时 LazyVerticalGrid 按行滚，卡片下半截永远看不到）。
        // 卡内 verticalScroll（父）与玻璃控件（子）的手势共存已由轴向锁定处理：
        // DampedDragAnimation / InteractiveHighlight 判定纵向意图就让位给父级滚动。
        Column(
            modifier = Modifier
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
        ) {
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

            // 裸 Row 在窄屏（长文件名 / 全能力位开启）会横向溢出被裁（三线审查 Wave10）。
            // chips 是信息展示不是操作入口，自动换行才是正确 affordance —— 不要学
            // ModelsScreen 预设 chips 的 horizontalScroll（那是「主动横滑」语义）。
            FlowRow(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GlassChip(text = model.sizeText())
                GlassChip(text = model.quantization.name)
                GlassChip(text = model.family.name)
                if (!model.exists()) {
                    GlassChip(text = "文件缺失", selected = true)
                }
            }

            FlowRow(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
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
        LiquidDialog(
            onDismissRequest = { confirmDelete = false },
            title = "删除模型",
            actions = { dismiss ->
                GlassButton(text = "取消", onClick = dismiss, material = GlassMaterial.THIN)
                GlassButton(text = "删除", onClick = {
                    // 业务动作先行，再走动画式关闭（dismiss → 出场动画 → onDismissRequest 清状态）。
                    onDelete(deleteFileToo)
                    dismiss()
                })
            },
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
