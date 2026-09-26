package com.rickeal.agent.feature.settings.storage

import android.content.Intent
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.rickeal.agent.core.data.StorageBucket
import com.rickeal.agent.core.design.GlassButton
import com.rickeal.agent.core.design.GlassCard
import com.rickeal.agent.core.design.GlassIconButton
import com.rickeal.agent.core.design.GlassIconButtonShape
import com.rickeal.agent.core.design.GlassMaterial
import com.rickeal.agent.core.design.GlassScaffold
import com.rickeal.agent.core.design.GlassTopBar
import com.rickeal.agent.core.design.LiquidDialog
import com.rickeal.agent.core.design.LocalBottomBarOverlay
import com.rickeal.agent.core.design.LocalGlassColors
import com.rickeal.agent.core.design.LocalGlassTokens
import java.io.File
import java.util.Locale

/**
 * 存储空间页（Wave 10 Phase 2b C-2）。
 *
 * 分两档呈现（语义不可照抄 WorkBuddy —— 端侧无云端副本）：
 *  - **可清除**：缓存 / 运行日志 / 回合归档 / 执行计划 / 子代理会话 / 诊断日志 / 沙箱，
 *    每项带「清除」+ `LiquidDialog` 二次确认；
 *  - **用户数据**：模型 / 索引 / 会话 / 附件 / 记忆 / 壁纸 / 设置，只显示大小 + 「查看」详情，
 *    **没有一键清**（删除入口留在各自管理页）。
 */
@Composable
fun StorageScreen(
    viewModel: StorageViewModel,
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current

    GlassScaffold(
        modifier = modifier,
        topBar = {
            GlassTopBar(
                title = "存储空间",
                subtitle = if (state.loading) "统计中…" else "共占用 ${formatBytes(state.totalBytes)}",
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
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp)
                // 悬浮页签占位（2026-09-26）：加在滚动内容**之内**，末尾条目能滚出
                // 页签区；内容本体仍从玻璃页签底下穿过（见 LocalBottomBarOverlay KDoc）。
                .padding(bottom = LocalBottomBarOverlay.current),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 总览：先把「一共占了多少」讲清楚，再列分桶。
            GlassCard(contentPadding = PaddingValues(14.dp)) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "本机占用",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onGlass,
                    )
                    Text(
                        text = formatBytes(state.totalBytes),
                        style = MaterialTheme.typography.headlineMedium,
                        color = colors.onGlass,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        text = "所有数据都保存在本机。「可清除」项删除后会自动重建；" +
                            "「用户数据」不会被一键清除，请到对应页面里管理。",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onGlassSubtle,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            if (state.clearableBuckets.isNotEmpty()) {
                SectionTitle("可清除")
                // R1：引擎忙时禁用清除并给一句可行动提示（权威闸门在 ViewModel，这里只是便利层）。
                if (state.engineBusy) {
                    Text(
                        text = "正在生成，暂不可清理",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onGlassSubtle,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                }
                for (bucket in state.clearableBuckets) {
                    StorageBucketCard(
                        bucket = bucket,
                        actionText = "清除",
                        onAction = { viewModel.onRequestClear(bucket) },
                        actionEnabled = !state.engineBusy,
                    )
                }
            }

            if (state.assetBuckets.isNotEmpty()) {
                SectionTitle("用户数据")
                for (bucket in state.assetBuckets) {
                    StorageBucketCard(
                        bucket = bucket,
                        actionText = "查看",
                        onAction = { viewModel.onShowDetail(bucket) },
                    )
                }
            }

            val notice = state.message
            if (notice != null) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onGlassMuted,
                    )
                }
            }

            Box(modifier = Modifier.size(tokens.bottomBarHeight))
        }
    }

    // 清除的二次确认。用 LiquidDialog 的动作区（回调里的 dismiss 会先播完出场动画）。
    val pending = state.pendingClear
    if (pending != null) {
        LiquidDialog(
            onDismissRequest = viewModel::onDismissClear,
            title = "清除「${pending.title}」",
            actions = { dismiss ->
                GlassButton(text = "取消", onClick = dismiss, material = GlassMaterial.THIN)
                GlassButton(
                    text = "清除",
                    onClick = {
                        viewModel.onConfirmClear()
                        dismiss()
                    },
                )
            },
            content = {
                Text(
                    // 用**桶自身的 description** 而不是通用文案：各桶后果不同
                    //（缓存会重建；而沙箱里的工具产出文件**不可恢复**），
                    // 一句笼统的「可以再生成」对沙箱桶是错的（见 AppContainer 的源列表）。
                    text = "将删除其中的 ${formatBytes(pending.bytes)} 数据。${pending.description}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onGlass,
                )
            },
        )
    }

    // 用户资产详情：说明「这是你的数据」，并给出管理入口（模型桶直达模型库）。
    val detail = state.detail
    if (detail != null) {
        LiquidDialog(
            onDismissRequest = viewModel::onDismissDetail,
            title = detail.title,
            actions = { dismiss ->
                if (detail.id == MODELS_BUCKET_ID) {
                    GlassButton(
                        text = "打开模型库",
                        onClick = {
                            onOpenModels()
                            dismiss()
                        },
                    )
                }
                GlassButton(text = "关闭", onClick = dismiss, material = GlassMaterial.THIN)
            },
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = detail.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onGlass,
                    )
                    Text(
                        text = "占用 ${formatBytes(detail.bytes)}",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onGlass,
                        modifier = Modifier.padding(top = tokens.gapSm),
                    )
                    // ⚠️ `path` 是 **core-data 模块的 public API 属性** —— 跨模块的 `val` 可能被
                    // 自定义 getter 覆盖，Kotlin **不允许**对它做 smart cast
                    // （编译错误：Smart cast to 'String' is impossible, because 'path' is a
                    // public API property declared in different module）。
                    // 必须先取到**局部** val 再判空 —— 局部 val 才可 smart cast。
                    val detailPath = detail.path
                    if (detailPath != null) {
                        Text(
                            text = detailPath,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onGlassSubtle,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Text(
                        text = "这是你的数据，不会被「清除」删除；请在对应的页面里管理。",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onGlassMuted,
                        modifier = Modifier.padding(top = tokens.gapSm),
                    )
                    // 「打开系统文件夹」（2026-09-26 用户需求）：SAF 打开 app **外部专属
                    // 目录**（Android/data/<pkg>/files），初始定位由 EXTRA_INITIAL_URI
                    // 给定（minSdk 31 的 DocumentsUI 均支持；对 app 自己的专属目录，
                    // SAF 树不受 Android/data 浏览限制）。直链下载的模型落在其下
                    // Download/（ModelDownloader 的 DownloadManager 落盘处），adb push
                    // 的模型在其下 models/ —— 换行列出的三个路径里，这两个都能在这里
                    // 看到；runCatching 兜极少数无文件选择器的 ROM（静默，不打断）。
                    // ⚠️ SAF 导入的模型复制在**内部** filesDir（/data/user/0/...），
                    // 任何外部文件管理器都无法访问 —— 该位置不提供此入口，删除走模型库。
                    if (detail.id == MODELS_BUCKET_ID) {
                        val context = LocalContext.current
                        GlassButton(
                            text = "打开系统文件夹",
                            onClick = {
                                // 智能定位（2026-09-26 用户反馈：固定跳 files 只看到空的
                                // Download——模型可能不在里面）。按「哪里有大文件（≥64MB）
                                // 就跳哪里」：Download（直链下载落盘处）→ models（adb push
                                // 的扫描目录）→ files 根；都没有（模型全在内部私有目录）
                                // 时跳 files 并提示。
                                fun hasModelFile(dir: File?): Boolean =
                                    dir?.listFiles()?.any { it.length() >= 64L * 1024L * 1024L } == true
                                val externalRoot = context.getExternalFilesDir(null)
                                val sub = when {
                                    hasModelFile(File(externalRoot, Environment.DIRECTORY_DOWNLOADS)) ->
                                        "files/Download"
                                    hasModelFile(File(externalRoot, "models")) -> "files/models"
                                    else -> "files"
                                }
                                // ⚠️ SAF 导入的模型复制在**内部** filesDir（/data/user/0/...），
                                // 任何外部文件管理器都无法访问 —— 检测到时明确告知，别让
                                // 用户在外部目录里翻找不存在的东西。
                                if (hasModelFile(File(context.filesDir, "models"))) {
                                    Toast.makeText(
                                        context,
                                        "部分模型位于应用私有目录（内部存储），系统文件管理器无法访问；已打开外部专属目录",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                                val initial = DocumentsContract.buildDocumentUri(
                                    "com.android.externalstorage.documents",
                                    "primary:Android/data/${context.packageName}/$sub",
                                )
                                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runCatching { context.startActivity(intent) }
                            },
                            material = GlassMaterial.THIN,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = tokens.gapSm),
                        )
                    }
                }
            },
        )
    }
}

/** 资产桶里唯一有独立管理页的：模型（跳模型库）。id 与 AppContainer 的源列表一致。 */
private const val MODELS_BUCKET_ID = "models"

@Composable
private fun SectionTitle(text: String) {
    val colors = LocalGlassColors.current
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = colors.onGlassMuted,
        modifier = Modifier.padding(top = 4.dp, start = 2.dp),
    )
}

/**
 * 一个分桶卡：标题 + 说明在左，**大号数值**在右；下方一枚动作按钮
 *（可清除桶是「清除」，资产桶是「查看」）。
 *
 * [actionEnabled] 只用于可清除桶的「清除」——引擎忙时置灰（R1 的 UI 便利层）。
 */
@Composable
private fun StorageBucketCard(
    bucket: StorageBucket,
    actionText: String,
    onAction: () -> Unit,
    actionEnabled: Boolean = true,
) {
    val colors = LocalGlassColors.current
    GlassCard(contentPadding = PaddingValues(14.dp)) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = bucket.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onGlass,
                    )
                    Text(
                        text = bucket.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onGlassSubtle,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = formatBytes(bucket.bytes),
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onGlass,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                GlassButton(
                    text = actionText,
                    onClick = onAction,
                    enabled = actionEnabled,
                    material = GlassMaterial.THIN,
                )
            }
        }
    }
}

/**
 * 字节数的人类可读文本。
 *
 * **必须显式给 [Locale.US]**：默认 Locale 在部分欧洲语区把小数点输出成逗号（`2,40 GB`），
 * 阿拉伯语区还会输出阿拉伯数字 —— 与同仓 ModelsViewModel / ChatContextMeter 口径一致。
 */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.2f GB".format(Locale.US, bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.1f MB".format(Locale.US, bytes / 1_048_576.0)
    bytes >= 1024L -> "%.0f KB".format(Locale.US, bytes / 1024.0)
    else -> "$bytes B"
}
