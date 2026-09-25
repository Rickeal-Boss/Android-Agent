package com.rickeal.agent.feature.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.rickeal.agent.core.design.GlassBackdropBlurOverride
import com.rickeal.agent.core.design.GlassButton
import com.rickeal.agent.core.design.GlassCard
import com.rickeal.agent.core.design.GlassMaterial
import com.rickeal.agent.core.design.GlassScaffold
import com.rickeal.agent.core.design.GlassSegmented
import com.rickeal.agent.core.design.GlassSettingRow
import com.rickeal.agent.core.design.GlassSlider
import com.rickeal.agent.core.design.GlassSwitch
import com.rickeal.agent.core.design.GlassTextField
import com.rickeal.agent.core.design.GlassTopBar
import com.rickeal.agent.core.data.DarkMode
import com.rickeal.agent.core.design.LocalBottomBarOverlay
import com.rickeal.agent.core.design.LocalGlassColors
import com.rickeal.agent.core.design.LocalGlassTokens
import com.rickeal.agent.core.design.LocalWallpaperImage
import com.rickeal.agent.core.design.motion.staggeredPageItem
import com.rickeal.agent.core.model.ThinkingMode
import java.util.Locale

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenDiagnostics: () -> Unit,
    onOpenLegal: () -> Unit,
    onOpenStorage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current
    val context = LocalContext.current

    // 「生成速度通知」的运行时权限（API 33+）。
    // 只在用户**显式打开**开关且尚未授权时才请求 —— 反过来（关开关、或已授权）都
    // 不该弹系统权限框。授予后才落盘 true；拒绝时**不落盘**，开关由 state 回弹
    // （state.generationNotification 没变），副文案切到权限提示。
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.onGenerationNotificationChange(true)
        // 拒绝：什么都不做 —— onGenerationNotificationChange 不被调用，
        // DataStore 里仍是 false，开关自然弹回，避免「开着却永远不出通知」。
    }
    val needNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        !NotificationManagerCompat.from(context).areNotificationsEnabled()

    // 自定义壁纸（Wave 9 需求 3b）：Photo Picker（系统进程内运行，零权限、拿不到
    // 照片真实路径，只有一个一次性 content:// Uri）。取消选择返回 null —— 不动现有壁纸。
    val wallpaperPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) viewModel.onWallpaperImport(uri)
    }

    GlassScaffold(
        modifier = modifier,
        topBar = {
            GlassTopBar(
                title = "设置",
                // Wave4：工具与记忆已提升为独立一级页签，本页只留全局偏好与低频入口。
                subtitle = "主题 · 推理参数 · 高级",
                modifier = Modifier.statusBarsPadding(),
            )
        },
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 本页有「系统提示词」输入框，键盘升起时必须把可滚动视口压短，
                // 否则输入框被盖住（项目是 edge-to-edge，系统不会自动顶）。
                // 顺序「先 ime 后 nav」：Type.ime() 不含导航栏高度，两者相加才对。
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp)
                // 悬浮页签占位（2026-09-26）：加在滚动内容**之内**，末尾条目能滚出
                // 页签区；内容本体仍从玻璃页签底下穿过（见 LocalBottomBarOverlay KDoc）。
                .padding(bottom = LocalBottomBarOverlay.current),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            /* ---------------------------------------------------- 外观 */
            // 切到本页时三张卡错峰浮现（staggeredPageItem，graphicsLayer-only）。
            // 挂在固定块（卡片容器）而非列表项上 —— 懒加载项的 index 随滚动回收变化，
            // 错峰序号会错乱（该 modifier 的 KDoc 有明确约束）。
            GlassCard(
                modifier = Modifier.staggeredPageItem(itemIndex = 0),
                contentPadding = PaddingValues(14.dp),
            ) {
                Column {
                    GroupTitle("外观")
                    GlassSegmented(
                        items = listOf("浅色", "深色", "跟随系统"),
                        selectedIndex = when (state.theme.darkMode) {
                            DarkMode.LIGHT -> 0
                            DarkMode.DARK -> 1
                            DarkMode.SYSTEM -> 2
                        },
                        onSelected = { index ->
                            val mode = when (index) {
                                0 -> DarkMode.LIGHT
                                1 -> DarkMode.DARK
                                else -> DarkMode.SYSTEM
                            }
                            viewModel.onThemeChange(state.theme.copy(darkMode = mode))
                        },
                    )
                    GlassSlider(
                        value = state.theme.glassIntensity,
                        // 拖动期间只改内存（onThemePreview）—— 这条会触发全 App 重组 +
                        // 每个玻璃节点重画，再叠加写盘必然掉帧；松手才落盘一次。
                        onValueChange = { viewModel.onThemePreview(state.theme.copy(glassIntensity = it)) },
                        onValueChangeFinished = { viewModel.onThemeCommit() },
                        label = "玻璃质感强度",
                        valueText = "%.2f".format(Locale.US, state.theme.glassIntensity),
                        valueRange = 0.5f..1.5f,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "背景模糊",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onGlass,
                            )
                            Text(
                                text = "关掉后玻璃只保留底色与描边，中低端机明显更流畅",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onGlassSubtle,
                            )
                        }
                        // 一次性事件（不是滑块），直接写，不需要 preview/commit 拆分。
                        // 关掉后观感会降一档（没有折射光斑的柔化），所以文案里说清代价。
                        GlassSwitch(
                            checked = GlassBackdropBlurOverride.enabled,
                            onCheckedChange = { GlassBackdropBlurOverride.setBlurEnabled(it) },
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "噪点微纹理",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onGlass,
                            )
                            Text(
                                text = "关闭后玻璃更干净，但大面积纯色会略显塑料感",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onGlassSubtle,
                            )
                        }
                        GlassSwitch(
                            checked = state.theme.enableNoise,
                            onCheckedChange = {
                                viewModel.onThemeChange(state.theme.copy(enableNoise = it))
                            },
                        )
                    }
                    // 触感反馈强度（Wave 9）：四档分段，写回 ThemeState.hapticLevel（String）。
                    // onThemeChange 既有、零改动；app 层 valueOf 解析失败回退 STANDARD。
                    // 副文案点明「系统开关是总闸」——这里选什么，系统关了都不震，
                    // 免得用户把"App 选了标准却没震"当成 bug 报上来。
                    Text(
                        text = "触感反馈强度",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onGlass,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Text(
                        text = "系统设置里关闭触感反馈时，这里选任何档位都不会震动",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onGlassSubtle,
                    )
                    GlassSegmented(
                        items = listOf("关", "轻", "标准", "强"),
                        selectedIndex = when (state.theme.hapticLevel) {
                            "OFF" -> 0
                            "LIGHT" -> 1
                            "STRONG" -> 3
                            // 脏值（枚举改名 / 老版本残留）归到标准档，与 app 层回退口径一致。
                            else -> 2
                        },
                        onSelected = { index ->
                            val level = when (index) {
                                0 -> "OFF"
                                1 -> "LIGHT"
                                2 -> "STANDARD"
                                else -> "STRONG"
                            }
                            viewModel.onThemeChange(state.theme.copy(hapticLevel = level))
                        },
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "减弱动效",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onGlass,
                            )
                            Text(
                                text = "弹簧变柔和，材质降到 THIN，低端机更稳",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onGlassSubtle,
                            )
                        }
                        GlassSwitch(
                            checked = state.theme.reduceMotion,
                            onCheckedChange = {
                                viewModel.onThemeChange(state.theme.copy(reduceMotion = it))
                            },
                        )
                    }
                    // 自定义壁纸（Wave 9 需求 3b）。缩略图直接复用根组合已解码的
                    // LocalWallpaperImage —— 再单独 decode 一份 40dp 小图纯属浪费，
                    // 缓存位图本来就是 ≤2048px 的成品，一次 Image 零额外成本。
                    val currentWallpaper = LocalWallpaperImage.current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (currentWallpaper != null) {
                            Image(
                                bitmap = currentWallpaper,
                                contentDescription = "当前壁纸预览",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                        } else {
                            // 程序化壁纸的占位块：与预览同尺寸，避免行高跳变。
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.onGlassSubtle.copy(alpha = 0.12f)),
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "背景图片",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onGlass,
                            )
                            Text(
                                text = "选一张照片完全替换背景，玻璃效果会实时跟着它变",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onGlassSubtle,
                            )
                        }
                        GlassButton(
                            text = "更换",
                            onClick = {
                                wallpaperPickerLauncher.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                                    ),
                                )
                            },
                            material = GlassMaterial.THIN,
                        )
                        if (currentWallpaper != null) {
                            Spacer(modifier = Modifier.width(6.dp))
                            GlassButton(
                                text = "恢复默认",
                                onClick = { viewModel.onWallpaperReset() },
                                material = GlassMaterial.THIN,
                            )
                        }
                    }
                }
            }

            /* ---------------------------------------------------- 推理参数 */
            GlassCard(
                modifier = Modifier.staggeredPageItem(itemIndex = 1),
                contentPadding = PaddingValues(14.dp),
            ) {
                Column {
                    GroupTitle("默认推理参数")
                    GlassSlider(
                        value = state.config.sampling.temperature,
                        // 与 ChatParamsPanel 同约定：拖动只 preview，松手才 commit，
                        // 否则 onValueChange 每帧一次 DataStore 事务（约 60 次/秒）。
                        onValueChange = { v ->
                            viewModel.onConfigPreview {
                                it.copy(sampling = it.sampling.copy(temperature = v))
                            }
                        },
                        onValueChangeFinished = { viewModel.onConfigCommit() },
                        label = "Temperature",
                        valueText = "%.2f".format(Locale.US, state.config.sampling.temperature),
                        valueRange = 0f..2f,
                    )
                    GlassSlider(
                        value = state.config.sampling.topP,
                        onValueChange = { v ->
                            viewModel.onConfigPreview {
                                it.copy(sampling = it.sampling.copy(topP = v))
                            }
                        },
                        onValueChangeFinished = { viewModel.onConfigCommit() },
                        label = "Top-P",
                        valueText = "%.2f".format(Locale.US, state.config.sampling.topP),
                        valueRange = 0f..1f,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    GlassSlider(
                        value = state.config.maxTokens.toFloat(),
                        onValueChange = { v ->
                            viewModel.onConfigPreview { it.copy(maxTokens = v.toInt()) }
                        },
                        onValueChangeFinished = { viewModel.onConfigCommit() },
                        label = "最大输出 Token",
                        valueText = "${state.config.maxTokens}",
                        valueRange = 64f..8192f,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    GlassSlider(
                        value = state.config.contextLength.toFloat(),
                        onValueChange = { v ->
                            viewModel.onConfigPreview { it.copy(contextLength = v.toInt()) }
                        },
                        onValueChangeFinished = { viewModel.onConfigCommit() },
                        label = "上下文长度",
                        valueText = "${state.config.contextLength}",
                        valueRange = 512f..32768f,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        text = "思考模式",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onGlassMuted,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    GlassSegmented(
                        items = listOf("关闭", "开启", "自动"),
                        selectedIndex = when (state.config.thinking) {
                            ThinkingMode.OFF -> 0
                            ThinkingMode.ON -> 1
                            ThinkingMode.AUTO -> 2
                        },
                        onSelected = { index ->
                            viewModel.onConfigChange {
                                it.copy(
                                    thinking = when (index) {
                                        0 -> ThinkingMode.OFF
                                        1 -> ThinkingMode.ON
                                        else -> ThinkingMode.AUTO
                                    },
                                )
                            }
                        },
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        text = "系统提示词",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onGlassMuted,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    GlassTextField(
                        value = state.config.systemInstruction,
                        onValueChange = { v ->
                            viewModel.onConfigChange { it.copy(systemInstruction = v) }
                        },
                        placeholder = "留空则使用模型默认行为",
                        maxLines = 5,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    )
                }
            }

            /* ---------------------------------------------------- 入口 */
            GlassCard(
                modifier = Modifier.staggeredPageItem(itemIndex = 2),
                contentPadding = PaddingValues(0.dp),
            ) {
                Column {
                    GlassSettingRow(
                        title = "诊断信息",
                        subtitle = "最近的运行日志：异常与决策点（仅内存，最多 200 条）",
                        onClick = onOpenDiagnostics,
                        trailing = {
                            Icon(
                                imageVector = Icons.Filled.Info,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                    GlassSettingRow(
                        title = "存储空间",
                        subtitle = "查看各类数据的占用，清理可再生成的内容",
                        onClick = onOpenStorage,
                        trailing = {
                            Icon(
                                imageVector = Icons.Filled.Storage,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                    GlassSettingRow(
                        title = "条款与授权",
                        subtitle = "回看应用服务条款与 Gemma 授权，含官方原文入口",
                        onClick = onOpenLegal,
                        trailing = {
                            Icon(
                                imageVector = Icons.Filled.Description,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                }
            }

            /* ---------------------------------------------------- 通知 */
            GlassCard(
                modifier = Modifier.staggeredPageItem(itemIndex = 3),
                contentPadding = PaddingValues(14.dp),
            ) {
                Column {
                    GroupTitle("通知")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "生成速度通知",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onGlass,
                            )
                            Text(
                                // 权限被拒（或尚未授予）时换行内文案，指路系统设置 ——
                                // 此时开关是弹回的，光说「已开启时显示速度」会让人困惑。
                                text = if (needNotificationPermission) {
                                    "需要通知权限，可在系统设置中开启"
                                } else {
                                    "生成时在通知栏实时显示 token/s 与首字延迟"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onGlassSubtle,
                            )
                        }
                        GlassSwitch(
                            checked = state.generationNotification,
                            onCheckedChange = { want ->
                                when {
                                    // 关闭不需要任何权限，直接落盘 false 并撤通知。
                                    !want -> viewModel.onGenerationNotificationChange(false)
                                    // API 33+ 且尚未授权：先请求，授予回调里才落盘 true。
                                    needNotificationPermission ->
                                        notificationPermissionLauncher.launch(
                                            Manifest.permission.POST_NOTIFICATIONS,
                                        )
                                    // API 31/32 无运行时权限，或已授予过：直接开。
                                    else -> viewModel.onGenerationNotificationChange(true)
                                }
                            },
                        )
                    }
                }
            }

            val notice = state.error ?: state.message
            if (notice != null) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.error != null) colors.danger else colors.onGlassMuted,
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(
                    text = "LiquidAgent · 端侧 Agent 运行时 · Apache-2.0",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onGlassSubtle,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .size(tokens.bottomBarHeight),
            )
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    val colors = LocalGlassColors.current
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = colors.onGlass,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}
