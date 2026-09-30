package com.rickeal.agent.feature.settings.tools

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.rickeal.agent.core.data.SandboxFileInfo
import com.rickeal.agent.core.design.GlassButton
import com.rickeal.agent.core.design.GlassEmptyState
import com.rickeal.agent.core.design.GlassIconButton
import com.rickeal.agent.core.design.GlassIconButtonShape
import com.rickeal.agent.core.design.GlassMaterial
import com.rickeal.agent.core.design.GlassSettingRow
import com.rickeal.agent.core.design.GlassScaffold
import com.rickeal.agent.core.design.GlassTopBar
import com.rickeal.agent.core.design.LiquidDialog
import com.rickeal.agent.core.design.LocalBottomBarOverlay
import com.rickeal.agent.core.design.LocalGlassColors
import java.io.File

/**
 * 沙箱工作区文件子页（Wave 33；Wave 36 起支持逐层下钻）：Agent 工具产出文件的列表。
 *
 * 能力边界（刻意收窄，职责不混）：
 *  - 只读浏览 + 应用内文本预览（限长截断）+ 经 [FileProvider] 跳转系统「打开」；
 *  - 目录**逐层下钻**（点目录进入、顶栏返回钮上溯一层）；不做递归扫描，也不提供删除 /
 *    重命名 —— 清理走存储页「沙箱工作区」分桶。
 *
 * 「打开」的 Intent 在本层组装（需要 Context 与 FileProvider），VM 只产
 * [SandboxFileInfo]；MIME 决策逻辑抽在 [resolveMimeType] 纯函数里可单测。
 */
@Composable
fun SandboxFilesScreen(
    viewModel: SandboxFilesViewModel,
    sandboxRoot: File,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val colors = LocalGlassColors.current
    val context = LocalContext.current

    GlassScaffold(
        modifier = modifier,
        topBar = {
            GlassTopBar(
                title = "沙箱工作区",
                subtitle = if (state.loading) {
                    "读取中…"
                } else {
                    // 计数口径必须用 totalEntries（entries 已被上限截断）：否则超限时
                    // 这里显示「共 200 项」而工具页入口卡显示「200+ 项」，同一时刻
                    // 两个数字打架（两处共用 sandboxEntryCountText，文案也同源）。
                    // 位置后缀按当前目录呈现：根层保留「（根层）」字样（与工具页入口卡
                    // 同口径），下钻后显示当前目录名（完整路径由下方面包屑承担）。
                    val location = if (state.currentDirPath.isEmpty()) {
                        "（根层）"
                    } else {
                        "（${state.currentDirPath.substringAfterLast('/')}）"
                    }
                    "共 ${sandboxEntryCountText(state.totalEntries, state.truncated)}$location"
                },
                modifier = Modifier.statusBarsPadding(),
                titleAlignment = Alignment.CenterHorizontally,
                navigationIcon = {
                    // pressOnly：顶栏图标位于 GlassTopBar 自己的玻璃之上（见 GlassIconButton KDoc）。
                    GlassIconButton(
                        // 上下文返回：根层时退出本页，下钻后先上溯一层（在子目录里按「返回」
                        // 应当回到上一层，而不是直接跳出整个子页）。
                        onClick = {
                            if (state.currentDirPath.isEmpty()) onBack() else viewModel.navigateUp()
                        },
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
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp)
                // 悬浮页签占位（同 StorageScreen）：加在滚动内容之内，末尾条目能滚出页签区。
                .padding(bottom = LocalBottomBarOverlay.current),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 面包屑（朴素版）：一行文本显示当前 root-relative 路径；根层不渲染。
            // 刻意不做可点分段（第一版避免引入新的可点组件 / 布局 API）—— 上溯走顶栏返回钮。
            if (state.currentDirPath.isNotEmpty()) {
                Text(
                    text = state.currentDirPath,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onGlassSubtle,
                )
            }
            when {
                state.error != null -> {
                    GlassEmptyState(
                        title = "读取失败",
                        subtitle = state.error,
                    )
                    GlassButton(
                        text = "重试",
                        onClick = viewModel::refresh,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
                state.loading && state.entries.isEmpty() -> {
                    GlassEmptyState(title = "读取中…", subtitle = "正在读取沙箱目录")
                }
                state.entries.isEmpty() -> {
                    // 空目录复用同一空态组件：文案按「根层 / 子目录」区分 —— 在空子目录里
                    // 显示「沙箱暂无文件」会被读成「沙箱被清空了」。
                    GlassEmptyState(
                        title = if (state.currentDirPath.isEmpty()) "沙箱暂无文件" else "此目录为空",
                        subtitle = if (state.currentDirPath.isEmpty()) {
                            "Agent 写入的文件会出现在这里"
                        } else {
                            "点左上角返回上一层"
                        },
                    )
                }
                else -> {
                    for (entry in state.entries) {
                        SandboxFileRow(
                            info = entry,
                            onPreview = viewModel::onPreview,
                            onOpen = { openSandboxFile(context, sandboxRoot, entry) },
                            // 目录行点击 = 逐层下钻（Wave 36）：换当前目录再扫一层。
                            onDirectoryClick = { viewModel.navigateInto(entry) },
                        )
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "清理沙箱请到「设置 → 存储空间」的沙箱工作区分桶",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onGlassSubtle,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }

    // 应用内预览弹层：文本读前 N 字符（N 与 AgentPolicy().maxToolOutputChars 同源，
    // 见 SANDBOX_PREVIEW_LIMIT_CHARS）；二进制只给「打开」出口。
    val preview = state.selectedPreview
    if (preview != null) {
        LiquidDialog(
            onDismissRequest = viewModel::onDismissPreview,
            title = preview.info.name,
            subtitle = "${formatSandboxBytes(preview.info.sizeBytes)} · " +
                formatSandboxTime(preview.info.lastModifiedMillis),
            actions = { dismiss ->
                GlassButton(
                    text = "打开",
                    onClick = {
                        openSandboxFile(context, sandboxRoot, preview.info)
                        dismiss()
                    },
                )
                GlassButton(text = "关闭", onClick = dismiss, material = GlassMaterial.THIN)
            },
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    when {
                        preview.loading -> {
                            Text(
                                text = "读取中…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onGlassSubtle,
                            )
                        }
                        preview.text == null -> {
                            Text(
                                text = "二进制文件，不支持预览",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onGlassSubtle,
                            )
                        }
                        else -> {
                            Text(
                                text = preview.text.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onGlass,
                                // 长文本限高滚动：弹窗不是阅读器，限量字符撑破屏幕
                                // 会把动作区顶出视野。
                                modifier = Modifier
                                    .heightIn(max = 360.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                            if (preview.truncated) {
                                Text(
                                    text = "仅预览前 $SANDBOX_PREVIEW_LIMIT_CHARS 字符",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.onGlassSubtle,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                    }
                }
            },
        )
    }
}

/**
 * 一个文件 / 目录行：类型图标 + 名称与摘要 + 行尾动作。
 *
 *  - 文本文件：整行点击 → 应用内预览；行尾 chevron。
 *  - 二进制文件：整行点击 → 系统「打开」；行尾 OpenInNew（预览对它无意义，
 *    但「打开」必须可达 —— 只藏在预览弹层里会变成死路）。
 *  - 目录：行尾给出直接子项计数；整行点击 → [onDirectoryClick]（逐层下钻）。
 */
@Composable
private fun SandboxFileRow(
    info: SandboxFileInfo,
    onPreview: (SandboxFileInfo) -> Unit,
    onOpen: () -> Unit,
    onDirectoryClick: () -> Unit,
) {
    val colors = LocalGlassColors.current
    val eligible = sandboxTextPreviewEligible(info.extension)
    GlassSettingRow(
        title = info.name,
        subtitle = if (info.isDirectory) {
            "目录"
        } else {
            "${formatSandboxBytes(info.sizeBytes)} · ${formatSandboxTime(info.lastModifiedMillis)}"
        },
        onClick = when {
            info.isDirectory -> onDirectoryClick
            eligible -> { -> onPreview(info) }
            else -> onOpen
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = sandboxFileIcon(info),
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(18.dp),
                )
                when {
                    info.isDirectory -> {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${info.childCount} 项",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onGlassSubtle,
                        )
                    }
                    eligible -> {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = colors.onGlassSubtle,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    else -> {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Filled.OpenInNew,
                            contentDescription = "打开",
                            tint = colors.onGlassSubtle,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        },
    )
}

/** 行尾类型图标的粗分组表（几个简单 icon，不做更细的 MIME 表）。 */
private val ICON_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg")
private val ICON_AUDIO_EXTENSIONS = setOf("mp3", "wav", "ogg", "flac", "m4a", "aac")
private val ICON_VIDEO_EXTENSIONS = setOf("mp4", "mov", "webm", "mkv", "avi")

/** 文件类型图标的粗分组（几个简单 icon，不做更细的 MIME 表）。 */
private fun sandboxFileIcon(info: SandboxFileInfo) = when {
    info.isDirectory -> Icons.Filled.Folder
    info.extension in ICON_IMAGE_EXTENSIONS -> Icons.Filled.Image
    info.extension in ICON_AUDIO_EXTENSIONS -> Icons.Filled.Audiotrack
    info.extension in ICON_VIDEO_EXTENSIONS -> Icons.Filled.Movie
    else -> Icons.Filled.Description
}

/**
 * 经 FileProvider 以 `content://` URI 跳转系统「打开」。
 *
 * MIME 查不到时兜底 `application/octet-stream`（见 [resolveMimeType]）——
 * 不带 MIME 的 ACTION_VIEW 在部分 ROM 上会被文件管理器以外的组件拒绝。
 *
 * ⚠️ `getUriForFile` 必须和 `startActivity` 在**同一个** runCatching 里：前者对
 * 「不在 FileProvider 路径表内」的文件直接抛 `IllegalArgumentException`（沙箱外的
 * 残留 / 越权路径会走到这里），放到防护外就成了"承诺了不崩、却仍会崩"的不对称
 * 防护。两者对用户的可见结果一样（打不开），因此共用同一条 Toast。
 */
private fun openSandboxFile(context: Context, sandboxRoot: File, info: SandboxFileInfo) {
    runCatching {
        val file = File(sandboxRoot, info.relativePath)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val mime = resolveMimeType(info.extension, platformMimeLookup())
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(context, "无法打开此文件", Toast.LENGTH_SHORT).show()
    }
}
