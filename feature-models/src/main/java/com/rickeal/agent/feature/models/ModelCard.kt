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
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import com.rickeal.agent.core.design.GlassButton
import com.rickeal.agent.core.design.GlassCard
import com.rickeal.agent.core.design.GlassChip
import com.rickeal.agent.core.design.GlassMaterial
import com.rickeal.agent.core.design.GlassSegmented
import com.rickeal.agent.core.design.LiquidDialog
import com.rickeal.agent.core.design.LocalGlassColors
import com.rickeal.agent.core.design.LocalGlassTokens
import com.rickeal.agent.core.data.DeviceCapability
import com.rickeal.agent.core.model.AgentLogStore
import com.rickeal.agent.core.model.InferenceBackend
import com.rickeal.agent.core.model.ModelDescriptor
import com.rickeal.agent.core.model.ModelGpuSupport
import com.rickeal.agent.core.model.ModelPresets

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
    /** 打开能力位编辑弹窗（Wave 43；对话框状态由调用方 ModelsScreen 管理）。 */
    onEditCapabilities: () -> Unit,
    // Modifier 必须排在第一个带默认值的参数位（lint ModifierParameter）：Compose 约定
    // modifier 是「可选修饰」参数中的第一个，否则调用方按位置传参会踩坑。
    modifier: Modifier = Modifier,
    /**
     * 是否放行 GPU（2026-09-26 GPU 白名单）。由调用方按**白名单口径**算好传入
     * （`ModelGpuSupport.isGpuVerified(model.fileName)`；非白名单 / 无预设 = false 从严）。
     * GPU 路径不支持时是 native 崩溃（闪退），Kotlin 层 catch 不住 —— 这里直接
     * 拦下 GPU 选项并给出依据；加载侧 ModelsViewModel 还有第二道兜底。
     */
    gpuAllowed: Boolean = true,
    /** GPU 禁用/放行的依据（来自预设 backendBasis），展示在选择器下方。 */
    gpuBasis: String = "",
    /**
     * 该容器在当前运行时的已知失败签名（来自预设 knownBrokenOn，W63 刀C）。
     * null = 未见已知失败。非空时：① 卡上常驻一行红字；② 点「加载」先弹确认框，
     * 确认后才走原加载路径（失败语义不变，只加提示 —— 用户可能想亲自复验上游修复）。
     *
     * ## 与诊断页的语义分工（W72 M16 交叉引用，⛔ 两页不要互相推断）
     *
     * **本页只有「已登记的坏签名」这一个视角**：它来自 `ModelPreset.knownBrokenOn`
     * （人工登记的静态结论），本页**不运行探针、不显示任何探针结果** —— 卡片上看到
     * 「已知不可用」不代表「刚测过它坏」，反过来卡片上没红字也不代表「刚测过它好」。
     *
     * 自检面（PASS / DEGRADED / BAD + 「PASS = 未检出已知失败面，不构成输出质量保证」
     * 免责行 + `NotRun` 各态）只在**设置 → 诊断信息**页（`DiagnosticsScreen` 的
     * `probePresentation`）出现，那是**本次运行**的动态结论。
     *
     * 两页结论可以并存且**互相不蕴含**：本页红字 + 诊断页 PASS（自检面）是合法组合
     * （登记的是加载期失败面，探针跑的是输出质量面）；本页无红字 + 诊断页 DEGRADED
     * 同样合法（未登记 ≠ 健康）。⛔ 不要据一页的结论去推断另一页的状态，也不要把
     * 探针结论搬进卡片（会让人以为登记簿被自动更新了）。
     */
    knownBrokenOn: String? = null,
) {
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current
    // 同 ModelsScreen：卡片在 Lazy 布局里会被回收，且旋转会重建 Activity ——
    // 用 remember 的话确认框会凭空消失。
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    // 已知坏容器的加载确认框（W63 刀C）：与 confirmDelete 同款 saveable 纪律。
    var confirmBrokenLoad by rememberSaveable { mutableStateOf(false) }

    GlassCard(
        // 横向长条（UI-1019 Batch 3）：宽度由调用方给（`fillParentMaxWidth(0.94f)`，
        // 露出下一张的一条边作为可横滑的 affordance）。卡内**不再**自滚 ——
        // 嵌套 verticalScroll 会与外层 LazyRow 抢手势，而横条给了整屏宽，
        // 长模型名两行内就能读完（双列半宽卡曾把名字截成 6 行）。
        // min 高度保证轮播里各条等高；内容超长时由文字 maxLines 收敛，不撑高。
        modifier = modifier.heightIn(min = 248.dp),
        material = if (isActive) GlassMaterial.THICK else GlassMaterial.REGULAR,
        cornerRadius = tokens.radiusMd,
        onClick = if (isActive) null else onSelect,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
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
                // 能力位编辑入口（Wave 43）：启发式只是文件名初值，容器实际子图可能
                // 缺失（Gemma-4 GPU 变体无音频编码器）—— 用户需要随时校正。
                GlassChip(text = "编辑能力位", onClick = onEditCapabilities)
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
                    // GPU 白名单拦截（2026-09-26）：未放行 GPU 的模型点 GPU 直接忽略 +
                    // 展示依据 —— 不回调 onBackendChange，配置值不会被写坏。
                    if (index == 1 && !gpuAllowed) return@GlassSegmented
                    onBackendChange(
                        when (index) {
                            1 -> InferenceBackend.GPU
                            2 -> InferenceBackend.NPU
                            else -> InferenceBackend.CPU
                        },
                    )
                },
            )
            // GPU 白名单警示：永远可见（不是只在出错时），让「为什么点 GPU 没反应」
            // 在选择的那一刻就有答案 —— 依据来自官方 README 的逐仓核实（backendBasis）。
            if (!gpuAllowed) {
                Text(
                    text = "GPU 已禁用：${gpuBasis.ifEmpty { "该模型未列入 GPU 白名单（无预设元数据），GPU 路径未经验证，为避免闪退已禁用" }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.warning,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            // 已知坏容器硬提示（W63 刀C）：与 GPU 警示同款「常驻展示」取舍 —— 让
            // 「为什么加载失败」在选择的那一刻就有答案，而不是等加载报错才发现。
            if (knownBrokenOn != null) {
                Text(
                    text = "已知不可用：$knownBrokenOn",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.danger,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
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
                        // 已知坏容器（W63 刀C）：先弹确认框，确认后才走原加载路径
                        // （失败语义不变，只加提示 —— 不拦死，留复验上游修复的入口）。
                        onClick = {
                            if (knownBrokenOn != null) confirmBrokenLoad = true else onLoad()
                        },
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

    // 已知坏容器的加载确认（W63 刀C）：与 confirmDelete 同构的「拦下 → 讲清 → 用户
    // 决定」，但有「仍要尝试」出口 —— 这里拦的是「已知的重复失败」，不是授权/安全闸。
    if (confirmBrokenLoad && knownBrokenOn != null) {
        LiquidDialog(
            onDismissRequest = { confirmBrokenLoad = false },
            title = "已知不可用的模型",
            actions = { dismiss ->
                GlassButton(text = "取消", onClick = dismiss, material = GlassMaterial.THIN)
                GlassButton(text = "仍要尝试", onClick = {
                    // W64 L-4：用户主动复验已知坏容器的留痕 —— 先落日志再触发加载。
                    // 顺序刻意排在 onLoad() 之前：① 语义上「我即将尝试」是前置事实；
                    // ② 兜底 —— 若该容器走 native 路径直接闪退（Kotlin catch 不住），
                    //    onLoad() 之后的日志永远写不出来，这条标记会随进程一起丢。
                    // 容器标识用 fileName 而非 displayName：与 knownBrokenOn 的登记键一致
                    // （ModelPresets.findByFileName(model.fileName)），便于日后 grep 对照预设；
                    // 且 displayName 可空（卡上多处 ifBlank 回退），fileName 稳定。
                    AgentLogStore.warn(
                        "用户主动复验已知坏容器（仍要尝试）：${model.fileName}" +
                            "｜已知失败签名：$knownBrokenOn",
                    )
                    // 原加载路径原样触发（onLoad 即 ModelsViewModel.onLoad）：
                    // 失败语义完全不变，本对话框只加提示。
                    onLoad()
                    dismiss()
                })
            },
        ) {
            Text(
                text = "该容器在当前运行时已知不可用：$knownBrokenOn。仍要尝试？",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onGlass,
            )
        }
    }
}

/**
 * 模型库的**横向长条轮播**（UI-1019 Batch 3）。
 *
 * 用户需求：「各个模型的文本较长，模型列表应该更改为横向长条 Liquid scroll」。
 * 原双列 `LazyVerticalGrid` 的卡片只有半屏宽，`Qwen2.5-1.5B-Instruct_multi-prefill-
 * seq_q8_ekv4096` 这类名字会被截成 6 行、布局破碎（真机截图实锤）。
 *
 * 形态：
 *  - `LazyRow` + **吸附**（[rememberSnapFlingBehavior]）：一次滑一条、松手归位，
 *    这是"Liquid scroll"的骨架；
 *  - 每条宽 `fillParentMaxWidth(0.94f)` —— 右侧露出下一条的一小条边，可横滑是
 *    看得见的（不是靠猜）；
 *  - 跟手缩放/淡出在 `graphicsLayer` 的 lambda 里读 [LazyListState.layoutInfo]
 *    （**绘制期**读取，滚动时每帧只失效绘制、不重组整列）；
 *  - 卡片内容仍是 [ModelCard]（已改为横条布局），全部既有能力（GPU 白名单、
 *    knownBroken 确认、探测、删除、能力位编辑）原样保留。
 *
 * 回调带 `ModelDescriptor` 参数：轮播内部按条目分发，调用方（ModelsScreen）
 * 只提供「拿到模型后做什么」，不必为每个条目另建 lambda 表。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelStripCarousel(
    models: List<ModelDescriptor>,
    activeModelId: String?,
    loadingModelId: String?,
    loadedModelId: String?,
    backend: InferenceBackend,
    onSelect: (ModelDescriptor) -> Unit,
    onLoad: (ModelDescriptor) -> Unit,
    onUnload: () -> Unit,
    onProbe: (ModelDescriptor) -> Unit,
    onDelete: (ModelDescriptor, Boolean) -> Unit,
    onBackendChange: (InferenceBackend) -> Unit,
    onEditCapabilities: (ModelDescriptor) -> Unit,
) {
    val listState = rememberLazyListState()
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    LazyRow(
        state = listState,
        flingBehavior = flingBehavior,
        // 与 ModelsScreen 的网格边距对齐（14dp），条目间距 12dp 与网格同口径。
        contentPadding = PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        itemsIndexed(items = models, key = { _, model -> model.id }) { itemIndex, model ->
            // 下标由 itemsIndexed 提供（LazyItemScope 没有 index 属性）。
            val preset = ModelPresets.findByFileName(model.fileName)
            ModelCard(
                model = model,
                isActive = model.id == activeModelId,
                isLoading = model.id == loadingModelId,
                isLoaded = model.id == loadedModelId,
                backend = backend,
                // GPU 白名单口径与 ModelsScreen 原实现逐字一致（W69 C5 同源化）：
                // 白名单放行/禁用，无预设的导入模型从严。
                gpuAllowed = ModelGpuSupport.isGpuVerified(model.fileName),
                gpuBasis = preset?.backendBasis.orEmpty(),
                knownBrokenOn = preset?.knownBrokenOn,
                onSelect = { onSelect(model) },
                onLoad = { onLoad(model) },
                onUnload = onUnload,
                onProbe = { onProbe(model) },
                onDelete = { deleteFile -> onDelete(model, deleteFile) },
                onBackendChange = onBackendChange,
                onEditCapabilities = { onEditCapabilities(model) },
                modifier = Modifier
                    .fillParentMaxWidth(0.94f)
                    // 绘制期读取滚动位置：离视口中心越远 → 略缩小、略淡。
                    // 幅度刻意很小（4% / 8%），只做"聚焦感"，不做花哨转场。
                    .graphicsLayer {
                        val info = listState.layoutInfo
                        val item = info.visibleItemsInfo.firstOrNull { it.index == itemIndex }
                        val viewport = (info.viewportEndOffset - info.viewportStartOffset)
                            .toFloat()
                            .coerceAtLeast(1f)
                        val distance = if (item == null) {
                            1f
                        } else {
                            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                            val itemCenter = item.offset + item.size / 2f
                            (abs(itemCenter - center) / viewport).coerceIn(0f, 1f)
                        }
                        val shrink = 1f - distance * 0.04f
                        scaleX = shrink
                        scaleY = shrink
                        alpha = 1f - distance * 0.08f
                    },
            )
        }
    }
}
