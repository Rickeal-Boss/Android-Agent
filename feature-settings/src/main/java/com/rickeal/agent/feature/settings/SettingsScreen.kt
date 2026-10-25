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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.rickeal.agent.core.design.GlassBackdropBlurOverride
import com.rickeal.agent.core.design.GlassButton
import com.rickeal.agent.core.design.GlassCard
import com.rickeal.agent.core.design.GlassChip
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
import com.rickeal.agent.core.model.AiCapabilityMode
import com.rickeal.agent.core.model.PersonaPreset
import com.rickeal.agent.core.model.ToolDisclosureMode
import com.rickeal.agent.core.model.ThinkingMode
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * 系统提示词草稿的提交防抖窗口（W82-04）。
 *
 * 400ms 是「人还在打字」与「已经打完」的经验分界：比它短会让连续输入仍然逐键落盘，
 * 比它长会让用户感觉「停手了还没保存」。真正的兜底不靠这个数字，而靠失焦 / 离开页面
 * 两个 flush 边界（见 [SettingsScreen] 里的系统提示词块）。
 */
private const val SYSTEM_INSTRUCTION_COMMIT_DEBOUNCE_MS = 400L

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

    // 从系统设置页返回时重读「电池优化豁免」状态（W77）：用户可能刚在系统里改完，
    // 不刷新就会一直显示离开前那一态。读的是系统态、不落盘，代价可忽略。
    LifecycleResumeEffect(Unit) {
        viewModel.refreshBackgroundRestriction()
        onPauseOrDispose { }
    }

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
                titleAlignment = Alignment.CenterHorizontally,
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
            verticalArrangement = Arrangement.spacedBy(tokens.cardGap),
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
                    // 覆盖层不透明度（Wave 21）：驱动 GlassConfig.overlayOpacity，
                    // 三类覆盖层（推理参数面板 / 新增记忆对话框 / 会话抽屉）共用。
                    // preview/commit 模式与上面「玻璃质感强度」完全一致 —— 拖动只改
                    // 内存（这条会触发三类覆盖层重画），松手才落盘。
                    GlassSlider(
                        value = state.theme.overlayOpacity,
                        onValueChange = { viewModel.onThemePreview(state.theme.copy(overlayOpacity = it)) },
                        onValueChangeFinished = { viewModel.onThemeCommit() },
                        label = "覆盖层不透明度",
                        valueText = "%.2f".format(Locale.US, state.theme.overlayOpacity),
                        valueRange = 0f..1f,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    // 覆盖层背景深度模糊的**满量程半径**（dp，2026-09-27）：驱动
                    // GlassConfig.overlayBlurRadius —— 三类覆盖层（抽屉 / 对话框 / 推理
                    // 参数面板）打开时，背景就糊到这个半径。
                    //
                    // 默认 20f 是**起点而非定论**：本机无真机可验证观感（玻璃自身模糊刻意
                    // 压在 3~12dp 是另一回事，那条是"柔化"，这条是"景深隔离"），所以做成
                    // 滑条让真机一档定下来：嫌糊成一坨往下 12~16，嫌不够深往上 24~28。
                    // preview/commit 模式同上：拖动只改内存，松手才落盘。
                    GlassSlider(
                        value = state.theme.overlayBlurRadius,
                        onValueChange = { viewModel.onThemePreview(state.theme.copy(overlayBlurRadius = it)) },
                        onValueChangeFinished = { viewModel.onThemeCommit() },
                        label = "覆盖层背景模糊",
                        valueText = "%.0f dp".format(Locale.US, state.theme.overlayBlurRadius),
                        valueRange = 8f..32f,
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
                    /* ───────────── 系统提示词的局部草稿态（W82-04 写放大治理）─────────────
                     * 缺陷：此前 TextField 的 onValueChange 直连 `viewModel.onConfigChange`，
                     * 而那条路径每次都 `DataStore.edit` **整份 InferenceConfig**
                     * （decode + encode + 磁盘事务）⇒ 打 200 个字 = 200 次完整序列化 +
                     * 200 次事务写（低端机输入掉跟手性 + 无谓闪存写入）。
                     *
                     * 修法：TextField 只绑**纯内存草稿**，静默 [SYSTEM_INSTRUCTION_COMMIT_DEBOUNCE_MS]
                     * 后才提交一次；失焦 / 组合销毁两个边界各自 flush，保证不丢刚输入的字。
                     *
                     * 草稿刻意留在 UI 层、**不进 VM**：它是「输入中的临时态」，不是应用状态。
                     * 搬进 VM 只会多一份与 `SettingsUiState.config` 并行的第二状态源。
                     */
                    var instructionFocused by remember { mutableStateOf(false) }
                    var instructionDraft by remember { mutableStateOf(state.config.systemInstruction) }
                    // 「用户手改过草稿」—— 只用来回答一个问题：外部改写来了要不要采纳。
                    // 不用 `draft != config` 代替它：首次组合时 config 还是默认空串，DataStore
                    // 的真值随后才到，那一刻若拿「不相等」当脏判据，就会把真值当成外部改写
                    // 挡掉（用户已聚焦时尤其致命：输入框空着、失焦一提交就把已存的提示词抹了）。
                    var instructionDirty by remember { mutableStateOf(false) }
                    // 同步可调用（不挂起）：提交 = 改内存 + launch 落盘。草稿与已落盘值相同时
                    // 由 VM 侧短路，故两个 flush 边界可以**无条件**调它而不产生空写。
                    val commitInstruction = {
                        viewModel.onSystemInstructionCommit(instructionDraft)
                        // 提交后草稿即已落盘值（内存态同步生效），脏标记归零。
                        instructionDirty = false
                    }

                    // 外部改写同步（首次加载 / 人格 chip）。⚠️ 用户手改过且**正在输入**时
                    // 不重置 —— 有意取舍：宁可让他把这句话打完，也不在打字途中换掉文本。
                    // 当前屏幕内唯一的外部改写点（人格 chip）已在自己的 onClick 里同步草稿
                    // 并清脏标记，所以这条取舍不会把 chip 的结果吞掉；失焦后自然收敛。
                    LaunchedEffect(state.config.systemInstruction) {
                        if (!instructionDirty || !instructionFocused) {
                            instructionDraft = state.config.systemInstruction
                            instructionDirty = false
                        }
                    }
                    // 防抖：key 变化会取消上一个 delay，连续按键天然合并成一次提交。
                    LaunchedEffect(instructionDraft) {
                        if (instructionDraft == state.config.systemInstruction) return@LaunchedEffect
                        delay(SYSTEM_INSTRUCTION_COMMIT_DEBOUNCE_MS)
                        commitInstruction()
                    }
                    // 边界②：组合销毁（导航离开 / 切页签 / 退出）。提交走
                    // `AppContainer.processScope`，故即便 VM 此刻已被 clear（viewModelScope
                    // 已取消）也不会丢最后一次输入 —— 见 onSystemInstructionCommit 的 KDoc。
                    DisposableEffect(Unit) { onDispose { commitInstruction() } }

                    // 人格档位 chip（W70 B2，W69-07 重启件）：点选即把预设 prompt 逐字
                    // 写入 systemInstruction（既有落盘路径，零第二状态源）；选中态 =
                    // PersonaPreset.matches(当前文本)，手改/留空即「自定义」（无高亮）。
                    // W82：选中态改判**草稿**而不是已落盘值 —— 否则手打第一个字之后高亮
                    // 还要多亮 400ms（防抖窗口），看起来像「点了没生效」。
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    ) {
                        PersonaPreset.entries.forEach { persona ->
                            GlassChip(
                                text = persona.label,
                                selected = PersonaPreset.matches(instructionDraft) == persona,
                                onClick = {
                                    // 一次性写入（点一下就定），直接落盘，不走草稿防抖。
                                    // 必须**同步草稿并清脏标记**：否则上面「输入中不重置草稿」
                                    // 的取舍会把这次外部改写吞掉（草稿仍是用户手打的那句）。
                                    instructionDraft = persona.prompt
                                    instructionDirty = false
                                    viewModel.onConfigChange {
                                        it.copy(systemInstruction = persona.prompt)
                                    }
                                },
                            )
                        }
                    }
                    Text(
                        text = "点选人格即写入对应系统提示词；手动改动文本后为自定义（无高亮）。" +
                            "人格只是一条系统指令，端侧小模型对它的遵循程度不一，效果可能有差异。" +
                            "切换后下一条消息需重新 prefill（一次性数秒）；人格是全局项，影响所有会话。",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onGlassMuted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    GlassTextField(
                        value = instructionDraft,
                        // 逐键只改草稿（纯内存，零落盘）；落盘由上面 400ms 防抖 + 两个 flush 边界负责。
                        onValueChange = {
                            instructionDraft = it
                            instructionDirty = true
                        },
                        placeholder = "留空则使用模型默认行为",
                        maxLines = 5,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            // 边界①：失焦即提交（点空白 / 收起键盘 / 点别的控件）。
                            // 只在 true→false 的跃迁上提交：组合首次挂载也会发一次
                            // hasFocus=false，那里提交纯属空转（VM 侧相等即短路）。
                            //
                            // ⚠️ 用 `hasFocus`（本节点**或其子**有焦点）而不是 `isFocused`
                            // （本节点自身）：这个 modifier 挂在 GlassTextField 外层 Box 上，
                            // 真正的焦点目标在它内部的 BasicTextField 上。
                            .onFocusChanged { focusState ->
                                val nowFocused = focusState.hasFocus
                                if (instructionFocused && !nowFocused) commitInstruction()
                                instructionFocused = nowFocused
                            },
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

            /* ------------------------- 后台运行保障（W77 建卡 / W78 语义解耦） */
            // 这一卡解决的是「模型跑着，用户切后台/锁屏，进程被冻结」。
            // 诚实口径（与 knownBroken 红字同一设计哲学）：
            //   * **AOSP 电池优化豁免 ≠ OEM 后台管控**：前者只是 Android 系统层面的事实，
            //     厂商（ColorOS/HyperOS/EMUI/MagicOS/OriginOS/nubia/One UI/Flyme）另有
            //     一整套独立的后台管控，且**不可程序化检测**（无公开 API）；
            //   * 所以本卡拆成三段，各自只说自己的事实：状态行只报 AOSP 二态、限定行
            //     声明覆盖面、路径行给准确路径 + **证据强度**（本机已实测 / 官方说明 /
            //     社区经验）—— 不假装验证过没验证过的族；
            //   * 白名单**不保证**不被杀 —— 它放宽 Doze，不防 LMK，也管不了 OEM 层；
            //   * 后台生成**确实**更耗电、更热（散热条件比前台差）；
            //   * 真被杀也不是白干：恢复卡会带着已写出的部分继续。
            // 绝不写「设置后永不被杀」这类承诺 —— 那是把系统行为说成我们的能力。
            // W78 删掉的两句旧文案（**故意不在此复述原文**，避免任何按文案做的判据
            // 被自家注释污染，W77 实摔过的形态）：一句把 AOSP 豁免说成「更不容易被
            // 冻结」= **效果承诺**（已被 W77 真机 A/B 在 ColorOS 上证伪：AOSP 白名单
            // 为 true 时 OplusProxyWakeLock 仍在灭屏 ~10s 后被回收）；另一句把「冻结」
            // 与「电池优化开关」绑成因果（冻结是 app-freezer / OEM 层，不是它的直接结果）。
            GlassCard(
                modifier = Modifier.staggeredPageItem(itemIndex = 4),
                contentPadding = PaddingValues(14.dp),
            ) {
                Column {
                    GroupTitle("后台运行保障")
                    // ① 状态行：只报 AOSP 事实，不做效果承诺（W78 D3）。
                    Text(
                        text = if (state.batteryOptimizationIgnored) {
                            "已加入系统电池优化豁免"
                        } else {
                            "未加入系统电池优化豁免"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onGlass,
                    )
                    // ② 限定行：恒定，随「该族有没有厂商层」二选一 —— 与上面的二态
                    // 无关（用户把 AOSP 白名单设完，覆盖面不会因此变大）。
                    Text(
                        text = if (state.oemVendorManaged) {
                            "仅 Android 系统层面；厂商可能另有独立的后台管控（见下方路径），本项不覆盖。"
                        } else {
                            "仅 Android 系统层面；本机为接近原生的系统，无额外厂商管控。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onGlassSubtle,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    // ③ 路径行：族名 + 证据括注 + 路径（W78 D5）。逐厂商路径
                    // （ColorOS / HyperOS / EMUI / MagicOS / OriginOS / nubia /
                    // One UI / Flyme / AOSP 各一套）。证据括注是本波对「未验证的路径
                    // 与已验证的路径口吻相同」这一问题的处置：不假装验证，而是把
                    // 「未在本机实测」这件事本身产品化。
                    Text(
                        text = if (state.oemName.isBlank()) {
                            state.oemGuideText
                        } else {
                            "${state.oemName}（${state.oemEvidenceNote}）：${state.oemGuideText}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onGlassSubtle,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    // ④ 导航兜底行：路径随版本漂移时给一条不依赖具体菜单名的出路。
                    Text(
                        text = "路径可能因系统版本略有差异；找不到时，在设置顶部搜索框输入" +
                            "「电池优化」或「应用启动管理」。",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onGlassSubtle,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(
                        modifier = Modifier.padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GlassButton(
                            text = "去系统设置",
                            onClick = { viewModel.onOpenBatteryOptimizationSettings() },
                        )
                        GlassButton(
                            text = "应用详情",
                            onClick = { viewModel.onOpenAppDetails() },
                            material = GlassMaterial.THIN,
                        )
                    }
                    // ⑤ 通知不可见披露（W78 D10②）：**条件渲染** —— null（通知可见）
                    // 时一行都不出现，绝大多数用户零噪音；只有真的受影响（未授予
                    // POST_NOTIFICATIONS / 「后台运行」渠道被关）才多这 1 行。
                    // 只说事实，不承诺「一定有通知」，也不说「影响保活」（FGS 照跑）。
                    state.backgroundNotificationHint?.let { hint ->
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onGlassSubtle,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                    // ⑥ 尾注：效果免责（耗电 / 不保证不被回收 / 锁屏后数秒内可能被冻结）+
                    // 恢复路径。W78-11（r12）：点名「部分机型锁屏后数秒内冻结本进程」机制，
                    // 只说部分机型、不带秒数、不写必然保证。
                    Text(
                        text = "后台生成会增加耗电与发热；即使完成上述设置，切后台后仍可能被冻结或回收；" +
                            "部分机型会在锁屏后数秒内冻结本进程，此时生成会暂停、解锁后自动继续。中断后可从恢复卡继续。",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onGlassSubtle,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }

            /* ---------------------------------------------------- AI 能力 */
            // AI 能力档位（Wave 26）：Operit2 四层能力模型的第 2 层，裁剪为单设备版本。
            // 它解耦了「工具危不危险」（工具自身的危险标记）与「我今天允许 AI 做到哪」
            // （用户授权范围）—— 在此之前只有前者，用户无法一次性收窄 AI 的行为面。
            // 副文案按档位切换，点明代价与边界；「完整权限」必须说清它不是提权，
            // 否则用户会以为选了它 App 就拿到了系统没给的能力。
            GlassCard(
                modifier = Modifier.staggeredPageItem(itemIndex = 5),
                contentPadding = PaddingValues(14.dp),
            ) {
                Column {
                    GroupTitle("AI 能力")
                    Text(
                        text = "AI 能对设备做到哪一步",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onGlassSubtle,
                    )
                    GlassSegmented(
                        items = listOf("只读", "工作区读写", "完整权限"),
                        selectedIndex = when (state.capabilityMode) {
                            AiCapabilityMode.READ_ONLY -> 0
                            AiCapabilityMode.WORKSPACE_WRITE -> 1
                            AiCapabilityMode.FULL -> 2
                        },
                        onSelected = { index ->
                            viewModel.onCapabilityModeChange(
                                when (index) {
                                    0 -> AiCapabilityMode.READ_ONLY
                                    2 -> AiCapabilityMode.FULL
                                    else -> AiCapabilityMode.WORKSPACE_WRITE
                                },
                            )
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        text = when (state.capabilityMode) {
                            AiCapabilityMode.READ_ONLY ->
                                "写操作（改文件、写记忆、写剪贴板）执行前都会先问你"
                            AiCapabilityMode.WORKSPACE_WRITE ->
                                "默认档：只由工具自身的风险标记决定是否要你确认"
                            AiCapabilityMode.FULL ->
                                "不再额外收窄。注意它不会给 App 任何系统本来没授予的能力"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onGlassSubtle,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            // 工具披露模式（Wave 27 / Operit「CLI 工具模式」裁剪移植）。与上面「AI 能力」
            // 卡刻意分开放：那张卡管**权限面**，这张管**提示词预算与工具可见性**。
            // 二者语义不同 —— 混在一起会让用户以为「按需检索」也是一种权限限制。
            GlassCard(
                modifier = Modifier.staggeredPageItem(itemIndex = 6),
                contentPadding = PaddingValues(14.dp),
            ) {
                Column {
                    GroupTitle("工具清单")
                    Text(
                        text = "可用工具怎么交给模型",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onGlassSubtle,
                    )
                    GlassSegmented(
                        items = listOf("全量列出", "按需检索"),
                        selectedIndex = when (state.disclosureMode) {
                            ToolDisclosureMode.FULL -> 0
                            ToolDisclosureMode.ON_DEMAND -> 1
                        },
                        onSelected = { index ->
                            viewModel.onDisclosureModeChange(
                                if (index == 1) ToolDisclosureMode.ON_DEMAND else ToolDisclosureMode.FULL,
                            )
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        text = when (state.disclosureMode) {
                            ToolDisclosureMode.FULL ->
                                "默认档：全部可用工具的名称与参数一次性写进提示词，模型可直接调用"
                            ToolDisclosureMode.ON_DEMAND ->
                                "只给模型两个元工具（检索 / 转发），真实工具按能力关键词查出来再执行。" +
                                    "提示词里的工具清单不再随工具数量变长，模型逐字复述工具清单的面积也更小；" +
                                    "代价是会多花一轮检索 —— 工具很少时未必更省，工具越多越划算。" +
                                    "危险操作该问的照样问，这不改变任何授权规则"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onGlassSubtle,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            // 原生工具通道（Wave 34 题 A）。与上面「工具清单」卡相邻但分开：那张卡管
            // **提示词里写多少**，这张管**工具信息走哪条通道发给模型**。
            //
            // 「默认关闭」必须在文案里说死：这是未经验证的新通路，开启后提示词里的
            // 工具清单段会整体消失（回显面的根治尝试），若引擎侧静默退回文本协议，
            // 模型就既拿不到清单、又少了回显检测覆盖 —— 那正是本波的已知代价，
            // 不能让用户在不知情的情况下长期开着。
            //
            // ⚠️ **文案与实现是一对契约，改一边必须改另一边**（复审 P1-4）：下面两处
            // 副文案里「对话页会显示一行说明」的实现在 `ChatScreen.sessionDiagnosticsHintOf`
            // 的第 3 分支（判据 = 本开关开启 ∧ 会话诊断 nativeToolChannel=false）。
            // 若哪天改了那边的判据或删掉那一行，本卡的这句承诺就成了假承诺 ——
            // 用户勾了开关却永远等不到那行说明。两侧都留了指向对方的注释。
            GlassCard(
                modifier = Modifier.staggeredPageItem(itemIndex = 7),
                contentPadding = PaddingValues(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "原生工具通道",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onGlass,
                        )
                        Text(
                            text = if (state.config.nativeToolChannel) {
                                "已开启：工具改由模型原生通道下发，提示词不再包含工具清单。" +
                                    "用于验证「只输出提示词及工具调用语言」的回显是否根治；" +
                                    "若当前模型不支持，会自动退回文本协议并在对话页显示一行说明。" +
                                    "若之后要关闭再重开：需在关闭状态下先发送一条消息，再重新开启，" +
                                    "原生通道才会重试（通道状态在下一次发送时才被观察到）"
                            } else {
                                "默认关闭：工具清单与调用格式写在提示词里（文本协议）。" +
                                    "开启后提示词不再含工具清单，用于验证回显是否根治；" +
                                    "若当前模型不支持，会自动退回文本协议并在对话页显示一行说明。" +
                                    "注意：关闭后需先发送一条消息，再重新开启，原生通道才会重试" +
                                    "（通道状态在下一次发送时才被观察到）"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onGlassSubtle,
                        )
                    }
                    GlassSwitch(
                        checked = state.config.nativeToolChannel,
                        onCheckedChange = { viewModel.onNativeToolChannelChange(it) },
                    )
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
