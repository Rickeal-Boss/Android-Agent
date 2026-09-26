package com.rickeal.agent.feature.models
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.rickeal.agent.core.design.GlassChip

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.GlassButton
import com.rickeal.agent.core.design.LiquidDialog
import com.rickeal.agent.core.design.GlassCard
import com.rickeal.agent.core.design.GlassEmptyState
import com.rickeal.agent.core.design.GlassTextField
import com.rickeal.agent.core.design.GlassFab
import com.rickeal.agent.core.design.GlassIconButton
import com.rickeal.agent.core.design.GlassIconButtonShape
import com.rickeal.agent.core.design.GlassSettingRow
import com.rickeal.agent.core.design.GlassSwitch
import com.rickeal.agent.core.design.GlassScaffold
import com.rickeal.agent.core.design.GlassTopBar
import com.rickeal.agent.core.data.LegalDocuments
import com.rickeal.agent.core.design.LocalBottomBarOverlay
import com.rickeal.agent.core.design.LocalGlassColors
import com.rickeal.agent.core.design.GlassMaterial
import com.rickeal.agent.core.design.LocalGlassTokens
import com.rickeal.agent.core.design.rememberGlassHaptics
import com.rickeal.agent.core.model.ModelCapabilities
import com.rickeal.agent.core.model.ModelFamily
import java.util.Locale

@Composable
fun ModelsScreen(
    viewModel: ModelsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current

    // 旋转 / 折叠展开会重建 Activity，这几项丢了用户就得重走一遍 SAF 文件选择器，
    // 所以都改用 rememberSaveable。
    //
    // Uri 存 String 而不是直接存 Uri：Uri 虽然是 Parcelable，但走 Bundle 恢复的路径
    // 不如字符串稳，且 nullable 的 autoSaver 更脆；toString()/parse 往返是零风险的。
    var pendingUriText by rememberSaveable { mutableStateOf("") }
    var pendingName by rememberSaveable { mutableStateOf("") }
    // 刻意**仍是 remember**：ModelCapabilities 既不是 Parcelable 也不是
    // java.io.Serializable，rememberSaveable 的 autoSaver 存不了它，硬上会在旋转时抛
    // IllegalArgumentException。真要存得单独给它写 listSaver —— 收益（能力位复选框
    // 不重置）小于引入一个自定义 Saver 的风险，本轮先留着，已在回报里标注。
    var pendingCaps by remember { mutableStateOf(ModelCapabilities()) }
    var showPresetDialog by rememberSaveable { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            pendingUriText = uri.toString()
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
                    GlassIconButton(
                        icon = Icons.Filled.Refresh,
                        contentDescription = "扫描目录",
                        onClick = { viewModel.onScanDirectories() },
                        contentColor = colors.onGlassMuted,
                        pressOnly = true,
                    )
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
            // 双列固定（Wave 10 Phase 2b）：手机上 Adaptive(minSize = 320.dp) 只会排出一列
            // （屏宽普遍 < 640dp），与参考设计的双列卡片形态不符。改成固定 2 列。
            // 整行内容（下载卡 / 引导卡 / 说明卡 / 提示条 / 筛选头）用 span = maxLineSpan
            // 单独占满，见下方各 item —— 否则会被压成半宽。
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            // 底部 104dp = 56(FAB) + 20(FAB 下边距) + 16(THICK 材质 shadowElevation
            // 上溢) + 12(呼吸间隙)，与 GlassScaffold snackbar 槽位同口径。
            // ⚠️ 底部留白收敛在 contentPadding，**不再**由末尾 item 各自补 padding：
            // item 级补偿只在"末尾恰好是这两项"时成立，插入新尾部 item 就会漏。
            // start/end/top/bottom 四参版：PaddingValues 只有 all / horizontal+vertical /
            // start+top+end+bottom 三个重载，horizontal 不能与 top/bottom 混用（R2 CI 红的教训）。
            contentPadding = PaddingValues(
                start = 14.dp, top = 12.dp, end = 14.dp,
                // 104dp = FAB(56) + 20 + 16 + 12（原口径，FAB 槽位已随悬浮页签上移）
                // + 悬浮页签占位（2026-09-26），见 LocalBottomBarOverlay KDoc。
                bottom = 104.dp + LocalBottomBarOverlay.current,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 搜索 + 分类 chip 行：整行占满，双列不影响它。只在有模型时出现 ——
            // 空库时搜索没有意义，避免在引导卡上方压一条没用的搜索框。
            if (state.models.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ModelsFilterHeader(
                        query = state.query,
                        onQueryChange = viewModel::onQueryChange,
                        families = state.families,
                        selectedFamily = state.family,
                        onFamilyChange = viewModel::onFamilyChange,
                    )
                }
            }
            // 以下整行内容必须 span = maxLineSpan：双列会把下载卡的输入框、引导文案、
            // 说明文字全挤成半宽。只有 ModelCard 保持单格（每行 2 张）。
            item(span = { GridItemSpan(maxLineSpan) }) {
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
                item(span = { GridItemSpan(maxLineSpan) }) {
                    BeginnerImportCard(
                        onGetModel = { showPresetDialog = true },
                        onPickFile = { picker.launch(arrayOf("*/*")) },
                    )
                }
            } else if (state.visibleModels.isEmpty()) {
                // 有模型但被搜索 / 分类筛没了：给一句可行动的提示，别让页面看起来"坏了"。
                item(span = { GridItemSpan(maxLineSpan) }) {
                    GlassEmptyState(
                        title = "没有匹配的模型",
                        subtitle = "换个关键词或分类试试",
                    )
                }
            }
            items(items = state.visibleModels, key = { it.id }) { model ->
                // GPU 白名单（2026-09-26）：按预设元数据放行/禁用 GPU —— 官方无 Android
                // GPU 验证证据的一律禁（native 崩溃 catch 不住）；无预设的导入模型从严。
                val preset = ModelPresets.findByFileName(model.fileName)
                ModelCard(
                    model = model,
                    isActive = model.id == state.activeModelId,
                    isLoading = model.id == state.loadingModelId,
                    isLoaded = model.id == state.loadedModelId,
                    backend = state.backend,
                    gpuAllowed = preset?.gpuSupported == true,
                    gpuBasis = preset?.backendBasis.orEmpty(),
                    onSelect = { viewModel.onSelect(model.id) },
                    onLoad = { viewModel.onLoad(model.id) },
                    onUnload = viewModel::onUnload,
                    onProbe = { viewModel.onProbe(model.id) },
                    onDelete = { deleteFile -> viewModel.onDelete(model.id, deleteFile) },
                    onBackendChange = viewModel::onBackendChange,
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                HowToGetModelsCard(
                    importDirPath = state.importDirPath,
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                val notice = state.error ?: state.message ?: state.capabilitiesText
                if (notice != null) {
                    Text(
                        text = notice,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = if (state.error != null) colors.danger else colors.onGlassMuted,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    // Gemma 授权闸门：与下面两个闸门是**同一套「拦下 → 讲清 → 用户决定」结构，
    // 但拦的东西不同** —— 它们拦的是「估算值可能不够」的风险，这个拦的是「授权还没接受」。
    // 所以按钮只能是「同意并继续 / 暂不」，**没有**「仍要继续」那种绕过入口：
    // 用户没有「不接受但还是要用」这个选项。
    //
    // 只对 Gemma 系模型触发（判据在 ModelLicenses），Qwen / Phi 等完全不受影响。
    val gemmaTermsBlock = state.gemmaTermsBlock
    if (gemmaTermsBlock != null) {
        GemmaTermsGateDialog(
            block = gemmaTermsBlock,
            onAccept = viewModel::onGemmaTermsAccepted,
            onDismiss = viewModel::dismissGemmaTerms,
        )
    }

    // 内存闸门：估算值不足时不再硬拦，而是讲清风险、把决定权交回用户。
    // 文案放在数据层（MemoryGateBlock.riskText），就是为了防止 UI 侧把它简化成
    // 一句没有信息量的「内存不足」——用户需要知道继续的代价是闪退和可能的会话丢失。
    val memoryGateBlock = state.memoryGateBlock
    if (memoryGateBlock != null) {
        LiquidDialog(
            onDismissRequest = viewModel::dismissMemoryGate,
            title = "内存可能不足",
            actions = { dismiss ->
                GlassButton(text = "先不加载", onClick = dismiss, material = GlassMaterial.THIN)
                GlassButton(text = "仍要加载", onClick = {
                    viewModel.onLoadIgnoringMemoryGate()
                    dismiss()
                })
            },
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = memoryGateBlock.riskText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onGlass,
                    )
                }
            },
        )
    }

    // 存储闸门：与内存闸门同一套哲学——估算值不足时讲清代价，由用户决定。
    // 代价不是崩溃，而是下载中途失败、白白耗掉几 GB（用移动数据则流量照常扣）。
    val storageGateBlock = state.storageGateBlock
    if (storageGateBlock != null) {
        LiquidDialog(
            onDismissRequest = viewModel::dismissStorageGate,
            title = "存储空间可能不够",
            actions = { dismiss ->
                GlassButton(text = "先清理空间", onClick = dismiss, material = GlassMaterial.THIN)
                GlassButton(text = "仍要下载", onClick = {
                    viewModel.onDownloadIgnoringStorageGate()
                    dismiss()
                })
            },
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = storageGateBlock.riskText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onGlass,
                    )
                }
            },
        )
    }

    // 移动数据保护：GB 级模型用流量下载代价太高，先问一次
    val meteredUrl = state.meteredConfirmUrl
    if (meteredUrl != null) {
        val meteredPreset = ModelPresets.findByUrl(meteredUrl)
        LiquidDialog(
            onDismissRequest = viewModel::dismissMeteredConfirm,
            title = "正在使用移动数据",
            actions = { dismiss ->
                GlassButton(text = "先用 Wi-Fi", onClick = dismiss, material = GlassMaterial.THIN)
                GlassButton(text = "仍然下载", onClick = {
                    viewModel.confirmMeteredDownload()
                    dismiss()
                })
            },
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

    val uri = pendingUriText
        .takeIf { it.isNotBlank() }
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }
    if (uri != null) {
        ModelImportDialog(
            fileName = pendingName,
            capabilities = pendingCaps,
            onCapabilitiesChange = { pendingCaps = it },
            onDismiss = { pendingUriText = "" },
            onConfirm = {
                pendingUriText = ""
                viewModel.onImportUri(uri)
            },
        )
    }
}

/**
 * Gemma 授权闸门的确认框：讲清「为哪个模型同意哪份条款」，并给出官方原文入口。
 *
 * 用 [LiquidDialog] 而不是整屏页面，是为了和本页已有的三个闸门（内存 / 存储 / 流量）
 * 保持同一种交互形态 —— 用户在这里已经习惯了「被拦下 → 看清代价 → 决定」。
 *
 * 正文来自 [LegalDocuments]（`:core-data`），与首启引导、设置页回看用的是**同一份文本**；
 * 这里不复制任何条款措辞，避免三处各改各的。
 */
@Composable
private fun GemmaTermsGateDialog(
    block: GemmaTermsBlock,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current
    val uriHandler = LocalUriHandler.current
    // 与 ConsentScreens 的 Gemma 条款页是**同一件事的两个入口**，语义必须一致：
    // 「同意并继续」= Confirm（接受条款）；「暂不」= 不发（它是把决定推迟，不是拒绝，
    // 与那边「稍后再说」同一口径）。
    val haptics = rememberGlassHaptics()

    LiquidDialog(
        onDismissRequest = onDismiss,
        title = LegalDocuments.GEMMA_TERMS_TITLE,
        actions = { dismiss ->
            // 「暂不」= 推迟决定，不发拒绝触感（与 ConsentScreens「稍后再说」同一口径）。
            GlassButton(text = "暂不", onClick = dismiss, material = GlassMaterial.THIN)
            GlassButton(text = "同意并继续", onClick = {
                haptics.confirm()
                onAccept()
                dismiss()
            })
        },
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "「${block.modelLabel}」属于 Gemma 系列模型，由 Google 提供，" +
                        "需要先接受 Gemma Terms of Use 才能下载或加载。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onGlass,
                )
                // 条款正文较长：限高 + 独立滚动，别把按钮挤出屏幕
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = tokens.gapSm)
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = LegalDocuments.GEMMA_TERMS_BODY,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onGlassMuted,
                    )
                }
                // 条款入口此前是裸 Row + clickable（只有字高 ≈24dp、无任何反馈）。
                // 换成胶囊小按钮：触摸区 48dp、按下有跟手形变；文字即语义。
                GlassIconButton(
                    onClick = {
                        // 没有浏览器时 openUri 会抛异常，条款入口不该让应用崩溃
                        runCatching { uriHandler.openUri(LegalDocuments.GEMMA_TERMS_URL) }
                    },
                    modifier = Modifier.padding(top = tokens.gapSm),
                    shape = GlassIconButtonShape.Capsule,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = LegalDocuments.VIEW_FULL_TERMS_LABEL,
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.accent,
                        )
                        Icon(
                            imageVector = Icons.Filled.OpenInNew,
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(15.dp),
                        )
                    }
                }
            }
        },
    )
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

/**
 * 模型库的搜索 + 分类行（双列网格里整行占满的一条）。
 *
 * 分类维度取 [ModelFamily]（Gemma / Qwen / Llama / Phi …）：它是持久化字段、由文件名
 * 启发式推得，比「能力位」可靠得多（能力位多数模型还没探测过，恒为默认值）。
 * 中文名映射放本模块 —— `core-design` 必须零业务依赖（arch-guard 第 2 条）。
 */
@Composable
private fun ModelsFilterHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    families: List<ModelFamily>,
    selectedFamily: ModelFamily?,
    onFamilyChange: (ModelFamily?) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        GlassTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = "搜索模型",
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // 分类 chip 行：横向滚动 —— 家族多时换行会把网格整体顶下去。
        // 「全部」= `family == null` 的显式入口（点击即清除分类筛选）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            GlassChip(
                text = "全部",
                selected = selectedFamily == null,
                onClick = { onFamilyChange(null) },
            )
            for (family in families) {
                GlassChip(
                    text = modelFamilyLabel(family),
                    selected = selectedFamily == family,
                    onClick = { onFamilyChange(family) },
                )
            }
        }
    }
}

/**
 * [ModelFamily] 的中文展示名。穷举全部 8 个家族（无 `else` 分支）—— 将来新增家族时
 * 这里会**编译报错**，强制补映射，而不是静默漏掉一个分类。
 */
private fun modelFamilyLabel(family: ModelFamily): String = when (family) {
    ModelFamily.GEMMA_3N -> "Gemma 3n"
    ModelFamily.GEMMA_3 -> "Gemma 3"
    ModelFamily.GEMMA_4 -> "Gemma 4"
    ModelFamily.QWEN_3 -> "Qwen 3"
    ModelFamily.LLAMA -> "Llama"
    ModelFamily.PHI -> "Phi"
    ModelFamily.MINICPM -> "MiniCPM"
    ModelFamily.OTHER -> "其他"
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
    // 这张卡在 LazyColumn 里：用 remember 的话滑出屏幕被回收后，用户填了一半的
    // 下载 URL 就清空了。rememberSaveable 在 Lazy 布局里按 item key 保住状态。
    var url by rememberSaveable { mutableStateOf("") }
    // 预设选择弹窗（Wave 12）：随卡片本地化。卡片被 LazyColumn 回收时弹窗连同关闭，
    // 语义合理 —— 用户回滚回来重新打开即可，不产生孤儿 Dialog 窗口。
    var showPresetSelectDialog by rememberSaveable { mutableStateOf(false) }

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
            // 预设选择入口（Wave 12 需求）：此前是全部预设的横向滚动 chips，13 个预设
            // 挤在一行里只能拖动盲扫、无法全览。改为收进 [PresetSelectDialog] 纵向滚动
            // 全览，这里只留一个入口行展示当前选择。选中归回用 findByUrl：镜像直链
            // 也能落到所属预设（Wave 6 的 ownsUrl 兼容口径）。
            GlassSettingRow(
                title = "选择预设模型",
                subtitle = ModelPresets.findByUrl(url)?.let { preset ->
                    "${preset.label} · ${preset.sizeText} · 建议可用内存 ${preset.ramText}"
                } ?: "从 ${ModelPresets.all.size} 个预设中选择，或直接粘贴下载链接",
                onClick = { showPresetSelectDialog = true },
                trailing = {
                    Text(
                        text = "›",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onGlassMuted,
                    )
                },
            )
            if (url.isNotBlank()) {
                val activePreset = ModelPresets.findByUrl(url)
                Text(
                    text = activePreset?.let { preset ->
                        "${preset.note} · 体积 ${preset.sizeText} · 建议可用内存 ${preset.ramText}"
                    } ?: "自定义链接（请确认直链可直接下载）",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onGlassSubtle,
                    modifier = Modifier.padding(top = tokens.gapSm),
                )
                // 换源行：主源 HuggingFace + 国内镜像一键切换。只改 url、不动预设 ——
                // 镜像与主源逐字节同源，所以体积、内存闸门、findByFileName 的文件名
                // 口径全部不变；这是 ModelPreset.mirrors 数据结构换来的不变式。
                if (activePreset != null && activePreset.mirrors.isNotEmpty()) {
                    Text(
                        text = "下载源",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onGlassMuted,
                        modifier = Modifier.padding(top = tokens.gapMd),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(tokens.gapSm),
                    ) {
                        val sources = listOf(
                            ModelPresetMirror("HuggingFace · 官方", activePreset.url),
                        ) + activePreset.mirrors
                        sources.forEach { source ->
                            GlassChip(
                                text = source.label,
                                selected = url == source.url,
                                onClick = { url = source.url },
                            )
                        }
                    }
                }
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

    if (showPresetSelectDialog) {
        PresetSelectDialog(
            selectedUrl = url,
            onSelect = { preset ->
                url = preset.url
                showPresetSelectDialog = false
            },
            onDismiss = { showPresetSelectDialog = false },
        )
    }
}

/**
 * 预设全览选择弹窗（Wave 12 需求）：把下载卡里原来横向滚动的全部预设 chips 收进
 * [LiquidDialog]，纵向滚动一屏全览 —— 每个预设一行：名称 + 体积/内存 + 能力注记，
 * 当前选中行高亮（镜像直链经 ownsUrl 归回所属预设，与入口行同口径）。
 *
 * 选定即关：与 [RecommendedModelDialog] 的 onPick 同一口径 —— 父层翻状态直接关闭，
 * 不走 LiquidDialog actions 的动画收尾（那会"瞬间消失"，见 LiquidDialog KDoc 的申报；
 * 弹窗式选择器统一用最短路径，换源行等轻量选择仍留在卡内 chips）。
 */
@Composable
private fun PresetSelectDialog(
    selectedUrl: String,
    onSelect: (ModelPreset) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current
    val haptics = rememberGlassHaptics()

    LiquidDialog(
        onDismissRequest = onDismiss,
        title = "选择预设模型",
        subtitle = "共 ${ModelPresets.all.size} 个 · 体积与内存需求仅供选型参考",
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(tokens.gapSm),
            ) {
                ModelPresets.all.forEach { preset ->
                    // 选中归回用 ownsUrl：换到镜像源后 url 不再等于 preset.url，
                    // 但仍是同一个预设（与 Wave 6 的预设 chip 选中口径一致）。
                    val selected = preset.ownsUrl(selectedUrl)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = if (selected) colors.accent.copy(alpha = 0.14f) else colors.glassTint.copy(alpha = 0.06f),
                                shape = RoundedCornerShape(tokens.radiusMd),
                            )
                            .clickable {
                                haptics.tick()
                                onSelect(preset)
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = preset.label,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (selected) colors.accent else colors.onGlass,
                            )
                            Text(
                                text = "体积 ${preset.sizeText} · 建议可用内存 ${preset.ramText}",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onGlassMuted,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                            Text(
                                text = preset.note,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onGlassSubtle,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        if (selected) {
                            Text(
                                text = "已选",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.accent,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
        },
    )
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
    bytesPerSecond >= 1_048_576L -> "%.1f MB/s".format(Locale.US, bytesPerSecond / 1_048_576.0)
    bytesPerSecond >= 1024L -> "%.0f KB/s".format(Locale.US, bytesPerSecond / 1024.0)
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
