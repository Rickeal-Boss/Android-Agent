package com.rickeal.agent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.GlassIconButton
import com.rickeal.agent.core.design.GlassMaterial
import com.rickeal.agent.core.design.LiquidGlassSurface
import com.rickeal.agent.core.design.LocalGlassColors
import com.rickeal.agent.core.design.LocalGlassConfig
import com.rickeal.agent.core.design.LocalOverlayBlurState
import com.rickeal.agent.core.design.OverlayBlurScope
import com.rickeal.agent.feature.settings.tools.SandboxFilesContent
import com.rickeal.agent.feature.settings.tools.SandboxFilesPreviewDialog
import com.rickeal.agent.feature.settings.tools.SandboxFilesViewModel
import java.io.File
import kotlin.math.roundToInt

/**
 * 右侧滑出面板的两端锚点。
 *
 * 只在 app 模块内部使用（状态由 `MainShell` 持有、传入 [WorkspaceOverlay]），
 * `internal` 防止泄漏成公共 API。
 */
internal enum class WorkspaceOverlayValue { Closed, Open }

/**
 * 右缘触发区的宽度：手指从屏幕右缘这一条竖带内向左滑即可拖出面板。
 *
 * 24dp 与系统返回手势/抽屉边缘手势的量级同档 —— 太窄滑不到、太宽会吃掉页面
 * 右侧的横向滑动。触发区只响应**横向拖拽**（`anchoredDraggable`），点按会穿透，
 * 不挡底下的内容。
 */
internal val WORKSPACE_EDGE_ZONE_WIDTH = 24.dp

/**
 * 对话页右侧的「工作区」覆盖层（Wave 40 G1）—— **仅 COMPACT 且停在「对话」页**。
 *
 * 形态：贴右缘的滑出面板（宽 `min(360dp, 82%)`），内容 = 沙箱工作区文件浏览
 * （复用 feature-settings 的 [SandboxFilesViewModel] + [SandboxFilesContent]，
 * 零新依赖）。与左侧会话抽屉（`ModalNavigationDrawer`）同层级的**兄弟覆盖层**：
 *
 * ⚠️ **必须是 body `Row` 的兄弟节点，绝不能是它的子节点** —— body 级背景模糊
 * （`MainShell` 里挂在 Row 上的 `overlayBackdropBlur`）会把 Row 的子树一起糊掉
 * （Compose 没有「反模糊」），面板作为 Row 的孩子会被自己触发的模糊糊掉。
 * 完整层级推导见 `OverlayBackdropBlur.kt` 的类 KDoc。
 *
 * 数据面：[viewModel] 由调用方在**面板首次打开时**才创建（VM 的 init 会扫盘，
 * app 启动就建等于每次冷启动都白扫一遍 IO）；null = 尚未打开过，面板内只渲染
 * 头部（正常流里面板打开前 VM 已就位，null 只是一帧的兜底）。
 *
 * 模糊：三路联动 ——
 *  1. 面板自身的锚点位移**逐帧**驱动 body 模糊（`MainShell` 里
 *     `workspaceBlurProgress`，与抽屉的 `drawerBlurProgress` 同思路，完全跟手）；
 *  2. 开启时经 [LocalOverlayBlurState] 登记 [OverlayBlurScope.SHELL]（对话框同一条
 *     通道）：`shellBlurProgress` 弹簧随之抬升，`max` 语义下取两者更强的一条，
 *     同时把「页签条单独补糊」的分支按既有规则关掉（SHELL 生效时 body 级已覆盖页签）；
 *  3. scrim 的压暗也在 draw 阶段读进度（`drawBehind`），**不在组合期读** [state] 的
 *     逐帧状态 —— 这是 `DrawerBackHandler` KDoc 记载过的整屏重组事故的既定防线。
 *
 * @param state 锚点状态（Closed 锚 = +面板宽度、Open 锚 = 0，见调用方构造）。
 *   由调用方持有：打开回调、页签切换关闭、返回键统一处理都要读写它。
 * @param viewModel 沙箱文件 VM；首次打开后才非空（见上）。
 * @param sandboxRoot 沙箱根目录（`container.sandboxDir`，「打开」的 FileProvider 基准）。
 * @param width 面板宽度（`min(360dp, 82%)`，调用方算好传入，锚点位移与宽度必须同源）。
 * @param edgeTriggerEnabled 右缘触发区是否响应拖拽 —— 与左抽屉的 `gesturesEnabled`
 *   同判据（COMPACT + CHAT）。面板本体拖拽不受此限（面板开着必然在对话页）。
 * @param blurProgress 面板开启进度 0..1（draw 阶段读取，见上「模糊」一节）。
 * @param close 关闭面板（调用方负责动画到 Closed 锚）。
 */
@Composable
internal fun WorkspaceOverlay(
    state: AnchoredDraggableState<WorkspaceOverlayValue>,
    viewModel: SandboxFilesViewModel?,
    sandboxRoot: File,
    width: Dp,
    edgeTriggerEnabled: Boolean,
    blurProgress: () -> Float,
    close: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGlassColors.current
    val glassCfg = LocalGlassConfig.current
    // 只读离散值（锚点两侧翻转，一次手势至多两次），不读逐帧的 offset/progress：
    // 本 composable 的重组频率因此与「面板开关」同频，不会每帧重组。
    val open = state.currentValue == WorkspaceOverlayValue.Open

    // ── 覆盖层模糊登记（SHELL 通道，同 LiquidDialog）─────────────────────────
    // 登记**不受**「背景模糊」总闸影响：登记表还参与「页签条补糊」的判据联动
    // （MainShell 的 navBarBlurProgress），总闸关闭时所有进度本来就归零，多登记无副作用。
    // onDispose 只在登记过的分支挂（ChatScreen 参数面板同款写法，见那里的说明）。
    val overlayBlurState = LocalOverlayBlurState.current
    val blurToken = remember { Any() }
    DisposableEffect(overlayBlurState, blurToken, open) {
        if (open) {
            overlayBlurState.acquire(blurToken, OverlayBlurScope.SHELL)
            onDispose { overlayBlurState.release(blurToken) }
        } else {
            onDispose { }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // ── scrim：压暗随进度走（draw 阶段读，零重组）；点按关闭仅在开态挂上 ──
        // 关态时既不画也不拦触摸 —— 覆盖层对页面是「不存在」的。
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawBehind {
                    val p = blurProgress().coerceIn(0f, 1f)
                    if (p > 0f) {
                        drawRect(color = colors.glassShadow, alpha = p * glassCfg.overlayOpacity)
                    }
                }
                .then(
                    if (open) {
                        // 空 indication：玻璃 scrim 上不该有材质涟漪（ConversationDrawerContent
                        // 的抽屉空白区同款写法）。
                        Modifier.clickable(
                            interactionSource = null,
                            indication = null,
                            onClick = close,
                        )
                    } else {
                        Modifier
                    },
                ),
        )

        // ── 右缘触发区：右缘 24dp 竖带，横向拖拽 = 拖出面板 ────────────────────
        // 在面板**之前**组合：面板开启后盖在它上面（绘制序在后），触发区自然失效。
        // 只在对话页启用（edgeTriggerEnabled）——与左抽屉的 gesturesEnabled 同判据。
        if (edgeTriggerEnabled) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(WORKSPACE_EDGE_ZONE_WIDTH)
                    .anchoredDraggable(state, Orientation.Horizontal),
            )
        }

        // ── 面板本体：贴右缘、通顶的液态玻璃（左抽屉同款层做法，轻量 surface，
        //    不嵌套完整 GlassScaffold 壁纸 backdrop —— 面板不是全屏页）────────────
        LiquidGlassSurface(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(width)
                // 锚点位移：Closed 锚 = +面板宽度（屏外右侧）、Open 锚 = 0（贴右缘）。
                // offset lambda 在布局/绘制阶段读，逐帧只失效 Placement，不重组。
                .offset { IntOffset(state.offset.roundToInt(), 0) }
                .anchoredDraggable(state, Orientation.Horizontal),
            material = GlassMaterial.THICK,
            // 贴右缘、通顶到底的面板：直角，不做胶囊（左抽屉同款）。
            cornerRadius = 0.dp,
            contentPadding = PaddingValues(0.dp),
        ) {
            // 覆盖层 scrim（同 ConversationDrawerContent）：压在玻璃上、内容之下。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(colors.glassShadow.copy(alpha = glassCfg.overlayOpacity)),
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // 吃掉面板内空白区的点击，防止穿透到 scrim 误关（左抽屉同款）；
                    // 子项（文件行 / 按钮）先命中，不受影响。
                    .clickable(
                        interactionSource = null,
                        indication = null,
                        onClick = {},
                    ),
            ) {
                // ── 头部：标题 + 关闭钮（inset 用法照抄左抽屉：statusBarsPadding）──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "沙箱工作区",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onGlass,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Agent 工具产出的文件",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onGlassSubtle,
                        )
                    }
                    GlassIconButton(
                        icon = Icons.Filled.Close,
                        contentDescription = "关闭工作区",
                        onClick = close,
                        contentColor = colors.onGlassSubtle,
                        pressOnly = true,
                    )
                }

                // ── 内容：与全屏子页共用同一渲染面（SandboxFilesContent）────────
                // 底部避让在内容块内部（navigationBarsPadding + 悬浮页签 84dp 占位，
                // 末尾条目能滚出页签区 —— 与抽屉/各屏的既有语义一致）。
                val vm = viewModel
                if (vm != null) {
                    val filesState by vm.uiState.collectAsState()
                    SandboxFilesContent(
                        state = filesState,
                        sandboxRoot = sandboxRoot,
                        viewModel = vm,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                    // 预览弹层与全屏子页同一枚组件（VM 状态驱动，关闭归还给 VM）。
                    val preview = filesState.selectedPreview
                    if (preview != null) {
                        SandboxFilesPreviewDialog(
                            preview = preview,
                            sandboxRoot = sandboxRoot,
                            onDismiss = vm::onDismissPreview,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 工作区覆盖层的返回键（Wave 40 G1）。
 *
 * **注册位置必须在 `MainShell` 的 `DrawerBackHandler` 之后**（后注册先派发 LIFO）：
 * 面板开着时返回键先「在子目录则上溯一层、否则关面板」，都不接管时才落回
 * 抽屉关闭 / 两段式返回。enabled 只看 `state.currentValue`（离散值）——
 * 刻意抽成小 composable 而不写在 `MainShell` 里，与 `DrawerBackHandler` 同理由：
 * 把锚点状态的读取隔离在最小失效范围（这里一次手势至多两次重组，且不产出布局节点）。
 *
 * 「能否上溯」读**真源** `viewModel.uiState.value.currentDirPath`（回调执行期读，
 * 非组合期订阅），与 SandboxFilesScreen 的上下文返回判据同源。
 *
 * @param canNavigateUp 面板内是否处于子目录（true = 返回键先上溯一层）。
 * @param onNavigateUp 上溯一层（调 `viewModel.navigateUp()`）。
 * @param close 关闭面板。
 */
@Composable
internal fun WorkspaceOverlayBackHandler(
    state: AnchoredDraggableState<WorkspaceOverlayValue>,
    canNavigateUp: () -> Boolean,
    onNavigateUp: () -> Unit,
    close: () -> Unit,
) {
    val open = state.currentValue == WorkspaceOverlayValue.Open
    BackHandler(enabled = open) {
        if (canNavigateUp()) {
            onNavigateUp()
        } else {
            close()
        }
    }
}
