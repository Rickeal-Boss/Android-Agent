package com.rickeal.agent.feature.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.SpringSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.GlassIconButton
import com.rickeal.agent.core.design.GlassMaterial
import com.rickeal.agent.core.design.LocalBottomBarOverlay
import com.rickeal.agent.core.design.LocalGlassColors
import com.rickeal.agent.core.design.LocalGlassConfig
import com.rickeal.agent.core.design.LocalGlassTokens
import com.rickeal.agent.core.design.LocalLiquidMotion
import com.rickeal.agent.core.design.LiquidMotion
import com.rickeal.agent.core.design.OVERLAY_TEXT_MIN_SCRIM
import com.rickeal.agent.core.design.WindowHeightClass
import com.rickeal.agent.core.design.asOverlayColors
import com.rickeal.agent.core.design.liquidGlass
import com.rickeal.agent.core.design.rememberWindowSizeClass
import com.rickeal.agent.core.model.InferenceConfig
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 「推理参数」覆盖层的位移进度句柄（W88 议题 3）。
 *
 * 存在的唯一理由：让**位移**与**背景模糊 / scrim 透明度**共用一个进度源。
 * 改前两者是两条时间线（位移走 `AnimatedVisibility` 的 tween、模糊走
 * `rememberOverlayBlurProgress` 的独立弹簧）⇒ 拖动期必然脱节，出现
 * 「面板已经拖下去了、背景还糊着」。现在模糊只读这一个值，物理上不可能再错拍。
 *
 * 读写纪律（与 `OverlayBackdropBlur.kt:165-171` 同款）：
 *  - **写**只在 sheet 的 `Modifier.offset { }` 布局 lambda 里（每帧一次，与真实位移同源）；
 *  - **读**只在 draw 阶段（`overlayBackdropBlur` 的 progress lambda、scrim 的 `drawBehind`）；
 *  - 写者不读、读者不在组合期读 ⇒ 拖动逐帧变化**不触发任何重组**（只失效绘制）。
 *
 * ⚠️ 默认值是 **0f（已关闭）**，不是 1f。`ChatScreen` 把这个句柄喂给常驻挂载的
 * `overlayBackdropBlur`，默认给 1f 会让「从未打开过参数面板」的对话页整屏发糊。
 */
@Stable
class SheetDragHandle {
    /** 1f = 完全展开；0f = 完全退出。只在拖拽/动画回调里写、只在 draw 阶段读。 */
    var progress by mutableFloatStateOf(0f)
}

/**
 * 松手判据：位移过「屏高的 28%」或向下甩速超阈 ⇒ 判退出。
 *
 * 为什么是纯函数而不是 `AnchoredDraggableState`（方案 A1）：判定在 foundation 内部
 * ⇒ JVM 不可测；而这条判据的两个分支（空命中 / 上甩否决）都属「写错了会静默错」的类型，
 * 必须有单测钉住（见 `SheetDismissPredicateTest`）。
 *
 * 阈值取 0.28 而不是 M3 的 0.5：本 sheet 只占屏高 0.78，0.5 要拖 ≈140dp 才退，
 * 手感"拖不动"；0.28 × 0.78 ≈ 屏高的 22%（≈78dp）即退，与 iOS sheet 的灵敏度接近。
 *
 * @param dragPx 向下为正，拖动累计位移（px）
 * @param velocityPxPerSec 向下为正，松手瞬时速度（px/s）
 * @param heightPx sheet 的**位移量程**（实测高度，px）
 */
internal fun shouldDismissSheet(
    dragPx: Float,
    velocityPxPerSec: Float,
    heightPx: Float,
): Boolean {
    // 空命中语义：尚未测量（heightPx = 0）⇒ 永不判退出。若省掉这条，下面
    // `dragPx > 0 * 0.28` 会让任何一次极小的拖动都直接关掉面板。
    if (heightPx <= 0f) return false
    // 速度优先于位移：快速下甩即便只拖了 20dp 也该退出（用户意图明确）。
    if (velocityPxPerSec > SHEET_FLING_PX_PER_SEC) return true
    // 上甩否决：位移再大，只要是"往回甩"就回弹（否则拖动中途改主意会被判退出）。
    if (velocityPxPerSec < -SHEET_FLING_PX_PER_SEC) return false
    return dragPx > heightPx * SHEET_DISMISS_FRACTION
}

/** 位移判据的比例阈值（见 [shouldDismissSheet] 的 KDoc）。 */
internal const val SHEET_DISMISS_FRACTION = 0.28f

/** 甩速阈值（px/s，≈300dp/s @3x）。 */
internal const val SHEET_FLING_PX_PER_SEC = 900f

/**
 * COMPACT / MEDIUM 下的参数底部抽屉。
 * 刻意不用 material3 的 ModalBottomSheet：签名跨版本漂移风险高，
 * 这里自绘玻璃，观感也更接近 iOS 27 的 sheet。
 *
 * **位移由本组件自己拥有**（W88 议题 3）：`Animatable` 存 px 位移 +
 * `Modifier.offset { }` 落地，进/出场、跟手拖拽、松手回弹全走同一个量。
 * 此前是 `AnimatedVisibility(slideIn/outVertically)` 独占位移 —— 手势无处插入，
 * 且松手判定退出后只能从满高重新滑（表现为「先跳回顶部再滑下去」）。
 *
 * 关闭路径共**四条**（scrim 点按 / 关闭键 / 硬件 BACK / 拖拽过阈），全部走同一个
 * [requestDismiss] ⇒ [onDismiss]；后者内含 W83-02 边界② 的 `onParamCommit()`，
 * 任何一条自己改可见性都会让系统提示词等预览值静默丢失。
 */
@Composable
fun ChatParamsSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    config: InferenceConfig,
    onParamPreview: ((InferenceConfig) -> InferenceConfig) -> Unit,
    onParamCommit: () -> Unit,
    onParamChange: ((InferenceConfig) -> InferenceConfig) -> Unit,
    modifier: Modifier = Modifier,
    // 位移进度句柄（W88）：由 ChatScreen 创建并下传，让背景模糊/scrim 与位移同源。
    // 默认值只作「孤立调用」兜底（那种场景没有读者）；生产路径必须传同一个实例。
    dragHandle: SheetDragHandle = remember { SheetDragHandle() },
) {
    // 覆盖层根取色（UI-1019 Batch 1）：sheet 表面恒深色（THICK + scrim + 平台
    // dim，实测亮度 #59~#7F），深色 onGlass 对比度仅约 3.9:1 ⇒ 直采的 colors
    // 取 overlay 白族。只换 onGlass 三件套：背后页面的 scrim（glassShadow）、
    // accent 不动。嵌套的 ChatParamsContent 在自身组合期读 LocalGlassColors，
    // 由下面 content 里的 CompositionLocalProvider 继承 overlay 版。
    val colors = LocalGlassColors.current.asOverlayColors()
    val tokens = LocalGlassTokens.current
    // Wave 51 F1：键盘弹出时底部导航栏已被键盘完全遮住 ⇒ 不再需要 overlay 占位（否则
    // sheet 被 imePadding 顶到键盘上方后又多垫一份）。derivedStateOf 收敛重组（直接读
    // WindowInsets.ime 会随键盘动画逐帧重组）。
    // ⚠️ `WindowInsets.ime` 是 @Composable @ReadOnlyComposable 取值 ⇒ 必须在组合作用域内
    // 先取出实例，再在 derivedStateOf 的普通 lambda 里读 getBottom。
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val imeVisible by remember(imeInsets, density) {
        derivedStateOf { imeInsets.getBottom(density) > 0 }
    }

    // ── 高度分档（W88 议题 4 · R4-2）────────────────────────────────────────
    // 横屏手机（窗口高 < 480dp = COMPACT）下 0.78 × 360 ≈ 281dp，扣头部（14×2 padding
    // + 48dp 关闭键 ≈ 76dp）只剩 ≈205dp 可视内容；**再点输入框弹键盘**，imePadding 又吃掉
    // ≈200dp ⇒ 可视内容 ≈5dp，最后一个参数项永远够不到（真机上等于"改不了温度"）。
    // 因此横屏放宽到 0.94（≈338dp），键盘态直接通顶（1f）—— 键盘弹起后屏上只剩输入框
    // 上沿那一条，此时任何留白都是纯损失。竖屏仍 0.78，逐字节不变。
    val windowSize = rememberWindowSizeClass()
    val heightFraction = when {
        imeVisible -> 1f
        windowSize.height == WindowHeightClass.COMPACT -> 0.94f
        else -> 0.78f
    }

    val motion = LocalLiquidMotion.current
    val reduceMotion = LocalGlassConfig.current.reduceMotion
    val springSpec = remember(motion) { LiquidMotion.floatSpring(motion) }
    val scope = rememberCoroutineScope()

    // sheet 实测高度（px）。位移量程、拖拽钳制、松手判据**共用这一个量** ——
    // 三处各算一份迟早会漂移（比如一处用 dp 一处用 px），而漂移的表现是
    // 「拖到底还剩一条缝」或「判据阈值和手感不一致」这类难查的小病。
    var sheetHeightPx by remember { mutableFloatStateOf(0f) }
    // 位移（px）：0 = 完全展开，closedOffsetPx = 完全退出。只改它，不直接改可见性。
    val offsetPx = remember { Animatable(0f) }
    // 首次入场时 sheet 还没被测量（keepComposed=false ⇒ 节点根本不在树上）⇒ 屏外起点
    // 只能估：窗口高 × 当前档位比例。**宁可多走不可少走**（窗口高 ≥ sheet 高），
    // 否则进场第一帧会在屏幕内闪一下完整面板。
    val windowHeightPx = LocalWindowInfo.current.containerSize.height.toFloat()
    val closedOffsetPx = if (sheetHeightPx > 0f) sheetHeightPx else windowHeightPx * heightFraction

    // 组合生命周期：visible=false 时先让出场动画跑完再撤节点（否则面板会"啪"地消失）。
    var keepComposed by remember { mutableStateOf(false) }
    // 拖拽松手判定退出时把速度记在这里，交给下面 LaunchedEffect(visible) 的出场动画
    // 当 initialVelocity ⇒ 甩出去的面板带着甩速继续滑，而不是自己匀速爬下去。
    var pendingExitVelocity by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(visible) {
        if (visible) {
            pendingExitVelocity = 0f
            // 首次入场（offset 还是初值 0）需要先对齐屏外起点；上一轮退出被**中途**
            // 重新打开（offset 已在半路）则直接反向滑回 —— 此时若再 snap 到屏外，
            // 用户会看到"面板先跳下去再升上来"。
            if (offsetPx.value == 0f) offsetPx.snapTo(closedOffsetPx)
            keepComposed = true
            offsetPx.settleSheetOffset(0f, 0f, springSpec, reduceMotion)
        } else if (keepComposed) {
            // 出场：从**当前 offset** 继续动画到屏外。拖拽退出尤其关键 —— 若先归零
            // 再滑，就是方案 B 的「先跳回顶部再滑下去」。
            offsetPx.settleSheetOffset(closedOffsetPx, pendingExitVelocity, springSpec, reduceMotion)
            pendingExitVelocity = 0f
            // 节点撤掉后不再有人写 progress：显式归 0，否则背景模糊会残留上一次的强度
            // （模糊修饰符是常驻挂载的，没人替它复位）。
            dragHandle.progress = 0f
            keepComposed = false
        }
    }
    // 1 帧不绘制：visible=true 那一帧 keepComposed 仍为 false（LaunchedEffect 在组合之后
    // 才跑），此时 offset 已对齐屏外 ⇒ 无可见伪影。这是「用 Animatable 自己管生命周期」
    // 的固有取舍，比留一个 AnimatedVisibility 抢位移便宜得多。
    if (!keepComposed) return

    // 四条关闭路径的唯一出口。onDismiss 内含 W83-02 边界② 的 commit，
    // 任何一条自己置 visible=false 都会让预览值静默丢失。
    val requestDismiss: () -> Unit = remember(onDismiss) { { onDismiss() } }

    // W83-07 真机补测抓到的残余侧门：本 sheet 是自绘覆盖层（刻意不用 material3
    // ModalBottomSheet，见 KDoc），硬件 BACK 键**不经过 onDismiss** —— scrim 点击与
    // 关闭键都走 onDismiss（内含 W83-02 的 commit 边界②），唯独 BACK 直接穿透到页面。
    // 补 BackHandler：可见时拦截 BACK 并走同一条 requestDismiss。
    if (visible) {
        BackHandler { requestDismiss() }
    }

    // 拖拽只改位移，不判退出 —— 判定留给松手时的纯函数（可 JVM 测）。
    // snapTo 走 Animatable 的 MutatorMutex：新的拖动会自动取消正在跑的回弹弹簧，
    // 于是"回弹途中再抓一把"能无缝接管，不会两股动画打架。
    val dragState = rememberDraggableState { delta ->
        val next = (offsetPx.value + delta).coerceIn(0f, closedOffsetPx)
        // ⚠️ 必须 UNDISPATCHED：拖拽事件是 ~120Hz 的连发，若每次 launch 都排队等派发，
        // 后一个 snapTo 会取消前一个（Animatable 的 MutatorMutex 语义）⇒ 大量位移被丢掉，
        // 表现为"手指走 100px、面板只跟 60px"的橡皮筋手感。UNDISPATCHED 让 snapTo
        // 在当帧同步生效（与 InteractiveHighlight.kt:304 的拖动跟手同一写法）。
        scope.launch(start = CoroutineStart.UNDISPATCHED) { offsetPx.snapTo(next) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // scrim：随拖动同步变淡（W88）。改前是固定 0.45 —— 面板拖到一半时背景仍然
        // 全黑压着，位移与压暗不同源。这里用 drawBehind 读同一个 progress：
        // 每帧只失效绘制，不重组（与 WorkspaceOverlay 同款写法）。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    // 0.45（UI-1019 Batch 2）：sheet 改为通底后，它自身的玻璃采样的是
                    // **更亮的页面区域**，压暗不足会让白字对比度掉到 AA 以下。
                    // 该 dim 压的是 sheet 背后的页面，与 sheet 自身 scrim 是两回事。
                    drawRect(colors.glassShadow.copy(alpha = 0.45f * dragHandle.progress))
                }
                .pointerInput(requestDismiss) { detectTapGestures { requestDismiss() } },
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // 通底（UI-1019 Batch 2，用户需求「覆盖层应当延伸至底部」）：
                // 玻璃底边直达屏幕底（含系统导航栏区域），底角保持直角
                // （shapeOverride 只圆上两角）。**不再**用 navigationBarsPadding /
                // 页签占位把整块 sheet 抬起来 —— 那是旧形态（sheet 悬在页签上沿，
                // 底部漏一条页面底色，看起来"没铺满"）。
                .fillMaxWidth()
                .fillMaxHeight(heightFraction)
                // 实测高度：位移量程 / 拖拽钳制 / 松手判据的唯一来源。
                .onSizeChanged { sheetHeightPx = it.height.toFloat() }
                .offset {
                    // 位移落地 + **在这里**把实际位移折算成进度写回句柄 ⇒ 模糊/scrim
                    // 与位移同源（同一个 offset、同一帧）。写法纪律见 SheetDragHandle KDoc：
                    // 写在布局阶段（所有读者都在 draw 阶段）⇒ 零重组。
                    val px = offsetPx.value.coerceIn(0f, closedOffsetPx)
                    val p = if (closedOffsetPx > 0f) 1f - px / closedOffsetPx else 1f
                    if (dragHandle.progress != p) dragHandle.progress = p
                    IntOffset(0, px.roundToInt())
                }
                .clip(RoundedCornerShape(topStart = tokens.radiusXl, topEnd = tokens.radiusXl))
                .liquidGlass(
                    material = GlassMaterial.THICK,
                    cornerRadius = tokens.radiusXl,
                    // 底部 sheet 只需上方两角圆。若只传 cornerRadius，
                    // liquidGlass 内部会按对称圆角裁剪，底角也会被圆 —— 浮在半屏很违和。
                    shapeOverride = RoundedCornerShape(
                        topStart = tokens.radiusXl,
                        topEnd = tokens.radiusXl,
                    ),
                    // 半屏级大面积容器，色散 7 次采样在这里纯属白烧 —— 显式关掉。
                    // 写死而不是依赖默认值：以后默认值被翻回去时这里不会跟着打开。
                    dispersion = false,
                    // 覆盖层面板：柔化白描边与高光，消除深色面板上的"白框"隔开感。
                    overlayPanel = true,
                ),
        ) {
            // 覆盖层自身压实 scrim：THICK 材质在壁纸/聊天内容上偏"透"，
            // 参数行文字对比度不足（与上面全屏 dim Box 是两回事 —— 那个
            // 压暗的是 sheet 背后的页面，这里压的是 sheet 自己，后者保留）。
            // 取 max(用户滑条, OVERLAY_TEXT_MIN_SCRIM)：白族文字的可读性下限，
            // 实测 0.45 时 sheet 面板 #8B909A 仅 3.2:1（判据见该常量 KDoc）。
            // matchParentSize 是 BoxScope 成员：玻璃 Box 就是 Box，直接可用，
            // 且不像 fillMaxSize 会反过来撑大父容器。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        colors.glassShadow.copy(
                            alpha = maxOf(
                                LocalGlassConfig.current.overlayOpacity,
                                OVERLAY_TEXT_MIN_SCRIM,
                            ),
                        ),
                    ),
            )
            // 覆盖层文字恒白（UI-1019 Batch 1）：包在玻璃表面绘制之后、内容之前
            // （不碰 liquidGlass 修饰符）。让在自身组合期读 LocalGlassColors 的
            // 嵌套组件（GlassIconButton / ChatParamsContent）继承白族文本色；
            // 直采的 colors 上面已取 overlay 版，对入参幂等。
            CompositionLocalProvider(
                LocalGlassColors provides colors.asOverlayColors(),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // 头部块 = 视觉抓手 + 标题行。**draggable 只挂在这里**：
                    // 下面的内容区有 verticalScroll，两者会抢同一批纵向手势 —— 只让
                    // 「非滚动区」接管拖拽，手指落在参数控件/列表上时永远滚内容。
                    // （GlassSlider 同类竞争的历史坑见 InteractiveHighlight.kt:97-111。）
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .draggable(
                                state = dragState,
                                orientation = Orientation.Vertical,
                                onDragStopped = { velocity ->
                                    // 生产路径**必须**真的调用这个判据（不许只做单测影子实现）。
                                    // heightPx 传位移量程而非 sheet 像素高：两者在有实测值时
                                    // 相等，未实测时同源退化 ⇒ 判据阈值与拖动手感永远一致。
                                    val dragged = offsetPx.value
                                    val dismiss = shouldDismissSheet(
                                        dragPx = dragged,
                                        velocityPxPerSec = velocity,
                                        heightPx = closedOffsetPx,
                                    )
                                    if (dismiss) {
                                        // 先记速度再关：出场动画由 LaunchedEffect(visible)
                                        // 用当前 offset 接续 ⇒ 无跳变，且 commit 边界不变。
                                        pendingExitVelocity = velocity
                                        requestDismiss()
                                    } else {
                                        // 未过阈：回弹。带松手速度 ⇒ 轻轻一拨也能"荡"回去。
                                        scope.launch {
                                            offsetPx.settleSheetOffset(
                                                target = 0f,
                                                velocity = velocity,
                                                spec = springSpec,
                                                reduceMotion = reduceMotion,
                                            )
                                        }
                                    }
                                },
                            ),
                    ) {
                        // 视觉抓手（36×4dp 圆角胶囊）：提示"这块可以下滑"。
                        // 刻意用纯色而非玻璃控件 —— 4dp 高的形状套玻璃只会糊成一团，
                        // 且玻璃的采样成本在拖动期是纯浪费。
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(top = 8.dp)
                                .size(width = 36.dp, height = 4.dp)
                                .clip(CircleShape)
                                .background(colors.onGlassSubtle.copy(alpha = 0.2f)),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "推理参数",
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.onGlass,
                                modifier = Modifier.weight(1f),
                            )
                            // 抽屉头部的关闭键：触摸区 48dp 由组件保证，按下有跟手形变。
                            // 图标保持原来的 24dp（此前 Icon 没写显式尺寸，取默认 24dp）。
                            GlassIconButton(
                                icon = Icons.Filled.Close,
                                contentDescription = "关闭",
                                onClick = requestDismiss,
                                contentColor = colors.onGlassMuted,
                                iconSize = 24.dp,
                            )
                        }
                    }
                    ChatParamsContent(
                        config = config,
                        onParamPreview = onParamPreview,
                        onParamCommit = onParamCommit,
                        onParamChange = onParamChange,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            // 项目走了 setDecorFitsSystemWindows(false)（edge-to-edge），
                            // sheet 通底后内容必须自己避让两层 inset：
                            //  - imePadding：键盘弹出时把内容顶起来；
                            //  - navigationBarsPadding：手势条 / 三键导航区。
                            // 顺序「先 ime 后 nav」：API 30+ 的 Type.ime() 只报键盘
                            // 自身高度、不含导航栏，两者相加才对（与 ChatInputBar 一致）。
                            //
                            // 末尾再垫「悬浮页签占位 + 12dp」——这是用户要求的
                            // **与底部 Tab 栏隔断**：页签是 MainShell 的悬浮玻璃，
                            // 绘制序在 NavHost 之后、天然浮在 sheet 之上；12dp 间隙
                            // 让最后一行控件与页签之间有可见的分界，而不是贴死/被压。
                            // 键盘弹出时页签被键盘完全遮住 ⇒ 该占位归零（Wave 51 F1）。
                            .imePadding()
                            .navigationBarsPadding()
                            .padding(
                                bottom = if (imeVisible) {
                                    0.dp
                                } else {
                                    LocalBottomBarOverlay.current + 12.dp
                                },
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * 把 sheet 位移收到 [target]。
 *
 * `reduceMotion` 时直接 `snapTo`（同 `OverlayBackdropBlur.kt:200-202` 的口径：减少动效
 * 的用户要的是「立刻到位」，不是「慢慢滑」）；否则走 `LocalLiquidMotion` 弹簧 ——
 * 与抽屉 / 对话框 / 背景模糊**同源**，所以面板落位与背景变清晰的步调一致。
 *
 * [velocity] 必须透传：拖拽甩出的速度丢了，松手判定退出后看起来就是"自己慢慢滑下去"
 * 而不是"被甩出去"。
 */
private suspend fun Animatable<Float, AnimationVector1D>.settleSheetOffset(
    target: Float,
    velocity: Float,
    spec: SpringSpec<Float>,
    reduceMotion: Boolean,
) {
    if (reduceMotion) snapTo(target) else animateTo(target, spec, initialVelocity = velocity)
}
