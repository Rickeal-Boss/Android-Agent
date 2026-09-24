package com.rickeal.agent.core.design

import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.rickeal.agent.core.design.liquid.EmptyBackdrop
import com.rickeal.agent.core.design.liquid.LocalBackdrop
import com.rickeal.agent.core.design.liquid.backdrops.layerBackdrop
import com.rickeal.agent.core.design.liquid.backdrops.rememberCombinedBackdrop
import com.rickeal.agent.core.design.liquid.backdrops.rememberLayerBackdrop
import com.rickeal.agent.core.design.liquid.drawBackdrop
import com.rickeal.agent.core.design.liquid.effects.blur
import com.rickeal.agent.core.design.liquid.effects.lens
import com.rickeal.agent.core.design.liquid.effects.vibrancy
import com.rickeal.agent.core.design.liquid.highlight.Highlight
import com.rickeal.agent.core.design.liquid.interactive.DampedDragAnimation
import com.rickeal.agent.core.design.liquid.interactive.InteractiveHighlight
import com.rickeal.agent.core.design.liquid.shadow.InnerShadow
import com.rickeal.agent.core.design.liquid.shadow.Shadow
import com.rickeal.agent.core.design.liquid.shapes.Capsule
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * vibrancy 的饱和度系数。
 * 与 `LiquidGlassModifier` 的同名常量同一取值：Kyant0 默认 1.5，但我们的壁纸是
 * 浅色渐变 + 光斑（不是照片），1.5 会把浅色推成荧光色。降到 1.22 —— 提鲜但不失真。
 */
private const val VibrancySaturation = 1.22f

/**
 * 单个页签的声明式描述。图标 + 文本，由调用方（app 模块）从自己的导航模型映射而来
 * （`:core-design` 不依赖任何导航库，见架构 §1.4）。
 */
@Immutable
data class TabSpec(
    val label: String,
    val icon: ImageVector,
)

/**
 * 页签内容的按压缩放（1f → 1.2f），由主组件按 [DampedDragAnimation.pressProgress] 下发。
 *
 * ⚠️ 与 Kyant0 一致，这个缩放**只作用于回显行**（第 2 层）：可见行保持原样，
 * "图标被按大"的观感来自选中胶囊（第 3 层）对回显行的折射放大 —— 这正是
 * Liquid Glass "内容在玻璃里" 的关键错觉，把两层都缩放反而会让图标跳两次。
 */
private val LocalLiquidBottomTabScale = staticCompositionLocalOf<() -> Float> { { 1f } }

/**
 * 真·LiquidBottomTabs：**选中指示胶囊随选中项横向滑动**的底部页签。
 *
 * 结构对齐 Kyant0 `catalog/components/LiquidBottomTabs`（Apache-2.0）的四层：
 *
 *  1. **滑动指示面板**（可见玻璃条）—— Row + `graphicsLayer { translationX = panelOffset }`
 *     + Capsule + vibrancy/blur(8)/lens(24,24)。拖动时整条玻璃朝拖动方向轻微拉伸
 *     （panelOffset 最大 4dp，EaseOut），松手弹回。
 *  2. **隐形回显行** —— 同一份页签内容再渲染一遍，`alpha(0f)` 屏幕上不可见，
 *     但被 `layerBackdrop` 录进 [tabsBackdrop] 图层，并整体 tint 成强调色。
 *     它是第 3 层折射的素材：胶囊里看到的"发光页签"就是它。
 *  3. **滑动指示胶囊** —— `fillMaxWidth(1f/tabsCount)` 的 Box，横向平移
 *     `value * tabWidth + panelOffset`；手势（[InteractiveHighlight.gestureModifier]
 *     + [DampedDragAnimation.modifier]）挂在这一层；lens(10,14) + 色散 +
 *     按压缩放 + 速度各向异性拉伸。
 *  4. **选中项图标随按压缩放** —— 由 [LocalLiquidBottomTabScale] 下发（见上）。
 *
 * ## 与 Kyant0 原版的 API 差异（全部是等价替换，结构不变）
 *
 *  - 原版 `backdrop: Backdrop` 入参 → 读 [LocalBackdrop]（GlassScaffold 在最外层
 *    提供的壁纸录制层），并尊重 [GlassConfig.enableBackdropBlur]（UI-07 性能开关：
 *    用户关掉背景模糊后，这里退化为底色 + 高光，与 `liquidGlass` 门面行为一致）。
 *  - 原版 `content: RowScope.() -> Unit` 自由内容 → `tabs: List<TabSpec>` 强类型页签，
 *    内部生成内容（两行共用同一个 lambda，保证逐帧一致）。
 *  - 玻璃底色用 [GlassMaterial.THICK] 的 `backgroundAlpha`（0.34）—— 真机反馈
 *    "透明度不要太高"，导航栏是常驻组件，底色要压得住复杂背景。
 *
 * ## 为什么 8dp consumeSlopPx 是**必须**的
 *
 * 指示胶囊叠在选中的页签上面，一次点击会**同时**命中胶囊的拖动手势和页签的
 * `clickable`。若手势一有位移就 `consume()`，真机点击的亚像素抖动就会取消
 * clickable 的按压 → **点击选中页签没反应**（GlassSwitch 踩过的同一口井）。
 * 越过 8dp slop 才消费，"点击"与"拖动换页"两条路径干净分开。
 *
 * ## 页签切换的数据流（2026-09-24 Wave 6b 重构：**onSelected 只在用户位点发**）
 *
 *  - 单击页签 → `currentIndex = index` + **直接** `onSelected(index)`
 *  - 拖动胶囊 → `onDragStopped` 里 `currentIndex = targetIndex` + **直接** `onSelected(index)`
 *  - 外部 [selectedIndex] 变化（导航返回等）→ [LaunchedEffect] 写回 [currentIndex]
 *    （**拖拽进行中跳过**——见该处门禁注释）→ 收集器把胶囊动画过去
 *  - 收集器只负责动画、**不发 onSelected**：外部回显写也会触达收集器，若在那里
 *    回调再走 navigateTop → selectedIndex 回压 → 再写 currentIndex，反馈环闭合
 *    后任何一次回显都能自激振荡（真机录屏实证的"胶囊两端逐帧横跳"）
 *
 *  拖拽期锚点是 receiver 的实时 `value`（非 currentIndex）——按住动画中的胶囊
 *  不会瞬移；底栏无纵向滚动父级，让位锁关闭（`canYieldToParent = false`）。
 */
@Composable
fun LiquidBottomTabs(
    tabs: List<TabSpec>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) {
        // 空页签：`fillMaxWidth(1f/0)` 会算出 NaN 约束，直接占位返回。
        Box(modifier = modifier)
        return
    }
    val tabsCount = tabs.size
    val safeSelectedIndex = selectedIndex.coerceIn(0, tabsCount - 1)

    val colors = LocalGlassColors.current
    val config = LocalGlassConfig.current
    // 底色材质：THICK（真机反馈"透明度不要太高"）。只取它的 backgroundAlpha 做玻璃
    // 底色；blur / lens 沿用 Kyant0 LiquidBottomTabs 的实测值（8 / 24,24），那是
    // "液态"观感的来源，不随材质走（GlassMaterial.kt 的参数表也如此记录）。
    val thick = GlassMaterials.of(GlassMaterial.THICK)

    // 全屏壁纸背景源（GlassScaffold 最外层提供）—— 等价于 Kyant0 传进来的 backdrop 参数。
    val wallpaperBackdrop = if (config.enableBackdropBlur) LocalBackdrop.current else EmptyBackdrop
    // 回显行的录制层 —— 等价于 Kyant0 的 rememberLayerBackdrop() + .layerBackdrop()。
    val tabsBackdrop = rememberLayerBackdrop()

    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()

    // 手势回调里要读的布局量。用 State 承载而不是闭包捕获 val：
    // DampedDragAnimation 被 remember，闭包捕获的是创建那一刻的值；
    // 分屏 / 旋转后窗口宽度变了，旧闭包仍用旧宽度 → 胶囊跟手比例失真。
    // 刻意用 mutableStateOf + 显式 .value（不用 by 委托），与 DampedDragAnimation
    // 的同一决定一致 —— 少一层隐式依赖。
    val tabWidthState = remember { mutableStateOf(0f) }
    val containerWidthState = remember { mutableStateOf(0f) }

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        // 容器左右各 4dp 内边距（下面三层都带同一 4dp），可用宽度 = maxWidth - 8dp。
        val tabWidth = with(density) {
            (constraints.maxWidth.toFloat() - 8f.dp.toPx()) / tabsCount
        }
        // Wave4 审查（B-P0-3/P1-3）：5 项后窄容器（分屏 / 自由窗口可低至 ~206dp）单项
        // 宽度跌破 48dp 触摸标准且文字被 Clip 成半个字。完整修法（横向滚动 + 宽度同源）
        // 需要联动改 4 处几何，本轮不做；先用「窄容器退化为纯图标」缓解拥挤与截字 ——
        // 触摸目标问题的根治方案连同 4 处联动清单记入蓝图 §4 backlog。
        val compactTabs = with(density) { (tabWidth.toDp()) < 56.dp }
        // 带守卫的写入：值没变不触发失效，不会造成"组合期写状态"的重组循环。
        if (tabWidthState.value != tabWidth) tabWidthState.value = tabWidth
        val maxWidthPx = constraints.maxWidth.toFloat()
        if (containerWidthState.value != maxWidthPx) containerWidthState.value = maxWidthPx

        // 面板拉伸偏移（px）：拖动时整条玻璃朝拖动方向最长拉 4dp，松手弹簧归零。
        //
        // ⚠️ 拖动期**同步累加**到这个 State，不再每帧 `animationScope.launch { … snapTo(…) }`。
        // 旧写法每帧新建一个协程，多个在途协程都先读到**同一个** `offsetAnimation.value`
        // 再加各自的 delta，谁最后写谁生效 → 累加丢帧 → 面板 ±4dp 抽搐（真机反馈）。
        // 同步累加在 onDrag 当帧完成，没有任何在途协程读旧值。
        val panelOffsetPx = remember { mutableStateOf(0f) }
        val panelOffset = remember(density) {
            derivedStateOf {
                val containerWidth = containerWidthState.value
                val fraction = if (containerWidth > 0f) {
                    (panelOffsetPx.value / containerWidth).coerceIn(-1f, 1f)
                } else {
                    0f
                }
                with(density) {
                    4f.dp.toPx() * sign(fraction) * EaseOut.transform(abs(fraction))
                }
            }
        }
        // 松手回弹的在途协程。新一次拖动开始要先取消它 —— 否则回弹一边归零、拖动一边
        // 累加，两者对同一个 State 互相覆盖，面板又抖。
        val panelReboundJob = remember { mutableStateOf<Job?>(null) }

        // 内部选中态：单击页签与拖动胶囊都只改它，由它统一驱动动画与回调。
        var currentIndex by remember { mutableStateOf(safeSelectedIndex) }

        // ⚠️ consumeSlopPx 必须 8dp（见类 KDoc）——"点击选中页签没反应"的保险丝。
        val consumeSlopPx = with(density) { 8.dp.toPx() }

        // 拖动期的绝对映射基准（按下快照 / 手势内累积）。
        //
        // ⚠️ 拖动唯一的事实来源是**手势本身**，不是异步回显链：旧实现把
        //「当前目标值 + 本帧位移增量」交给 `updateValue`，而 `updateValue` 内部是
        // `animateTo(spring)`（**收敛动画，不是瞬时**），拖动期每帧重启弹簧 → 弹簧永远
        // 追不上每帧前移的目标 → 胶囊恒定滞后手指（"不跟手"）；单帧只走"一步"而目标走
        // "一个手指增量" → 误差随拖动距离单调累积（"越远越偏差"）；速度快时弹簧速度反向
        // 打架 → 抽搐。绝对映射 + snapValue（瞬时到位）彻底解耦，与 GlassSlider 同一套修法。
        //
        // ⚠️ 锚点 = receiver 的实时 `value`（onDragStarted 已带 receiver，见
        // DampedDragAnimation）——不能用 currentIndex：点击动画进行中按住胶囊时
        // 两者可能差出数个页签，旧锚点的第一帧 snapValue 会把胶囊瞬移到 currentIndex
        //（真机录屏"首尾乱飘"的来源之一）。
        var dragAccumPx by remember { mutableStateOf(0f) }
        var dragStartValue by remember { mutableStateOf(0f) }

        // onSelected 的最新引用：onDragStopped / 页签 onClick 两个**用户动作位点**
        // 直接回调（见各处注释——绝不能挂回 snapshotFlow 收集器）。必须声明在
        // dampedDragAnimation 之前：onDragStopped 闭包要捕获它。
        val onSelectedCallback by rememberUpdatedState(onSelected)
        // 触感用同一条纪律：只发在 onDragStopped 与页签 onClick 两个用户动作位点。
        // 绝不进 LaunchedEffect(selectedIndex) 或 snapshotFlow 收集器 ——
        // 导航返回 / 程序化切换都会触达那里，会变成"返回上一页也震"。
        val haptics = rememberGlassHaptics()
        val currentHaptics by rememberUpdatedState(haptics)

        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = safeSelectedIndex.toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                // 56dp 的胶囊按下时放大到 78dp 高 —— Kyant0 BottomTabs 的实测值。
                pressedScale = 78f / 56f,
                consumeSlopPx = consumeSlopPx,
                // ⚠️ 底栏**没有纵向滚动父级**（MainShell 的 Column 上下都无
                // verticalScroll），让位锁在这里是纯害：拖页签时手指的自然弧线会让
                // net-vertical 越过 tan30° 阈值 → 拖拽**中途冻结**（2026-09-24 真机
                // 录屏"拉越远越偏移"的来源之一）。必须关掉。
                canYieldToParent = false,
                onDragStarted = {
                    // 新一次拖动：取消在途回弹，避免它与拖动同时写 panelOffsetPx。
                    panelReboundJob.value?.cancel()
                    panelReboundJob.value = null
                    // 按下瞬间快照：本次手势的一切增量都从它出发（绝对映射）。
                    // ⚠️ 锚点必须用 **receiver 的实时 value**（胶囊此刻的真实位置），
                    // 不能用 currentIndex：点击动画进行中按住胶囊时两者可能差半个
                    // 屏 —— 旧锚点会让第一帧 snapValue 把胶囊**瞬移**到 currentIndex
                    // （真机录屏 6.060s/6.193s 帧"首尾乱飘"的来源之一）。
                    dragAccumPx = 0f
                    dragStartValue = this.value
                },
                onDragStopped = {
                    // 松手：四舍五入到最近页签，内部态收敛，面板拉伸弹回 0。
                    val targetIndex = targetValue.roundToInt().coerceIn(0, tabsCount - 1)
                    currentIndex = targetIndex
                    animateToValue(targetIndex.toFloat())
                    // ⚠️ onSelected 只在**用户完成动作**的两个位点（这里与页签单击）
                    // 直接回调 —— 绝不能挂回 snapshotFlow 收集器：外部回显写
                    // currentIndex 也会触达收集器，若在那里再发 onSelected →
                    // navigateTop → selectedIndex 回压 → 再写 currentIndex，
                    // 就是真机录屏实证的"胶囊两端自激振荡"（2026-09-24 Wave 6b）。
                    onSelectedCallback(targetIndex)
                    // 拖动换页提交 → 一次 tick（页签是离散档位）。
                    //
                    // ⚠️ 只认 `finishedNormally`：onDragStopped 在 `finally` 里执行，
                    // pointerInput 协程被取消（旋转 / 导航 / 页面销毁）时也会走到这里，
                    // 那时并不是一次用户提交。（本控件 `canYieldToParent = false`，
                    // 没有"让位给滚动"这条路径，故不判 yieldedToParent —— 与
                    // GlassSegmented 的门禁口径差异就来自这个开关。）
                    if (finishedNormally) currentHaptics.tick()
                    // 面板拉伸弹回：从当前累加值出发做一次弹簧（**单个**协程，非每帧）。
                    val start = panelOffsetPx.value
                    if (start != 0f) {
                        panelReboundJob.value?.cancel()
                        panelReboundJob.value = animationScope.launch {
                            animate(
                                initialValue = start,
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = 1f,
                                    stiffness = 300f,
                                    visibilityThreshold = 0.5f,
                                ),
                            ) { value, _ -> panelOffsetPx.value = value }
                        }
                    }
                },
                onDrag = { _, dragAmount ->
                    // 面板拉伸：**同步累加**（当帧完成），不再每帧 launch。
                    panelOffsetPx.value += dragAmount.x
                    // 胶囊位置：手势内累积 → 绝对映射（每移动一个 tabWidth 前进一页），
                    // 与异步回显完全解耦。
                    dragAccumPx += dragAmount.x
                    val tabWidth = tabWidthState.value
                    if (tabWidth > 0f) {
                        val raw = dragStartValue + dragAccumPx / tabWidth * if (isLtr) 1f else -1f
                        val coerced = raw.coerceIn(0f, (tabsCount - 1).toFloat())
                        // 值没变不重复 snapValue：掐掉亚像素抖动造成的无意义协程启动。
                        if (coerced != targetValue) snapValue(coerced)
                    }
                },
            )
        }

        // 外部选中态变化（导航返回 / 程序化切换）→ 写回内部态。
        // ⚠️ 2026-09-24 Wave 6b 门禁：**拖拽进行中绝不回写**。拖拽期手势（snapValue）
        // 是胶囊位置的唯一事实来源；导航回压（navigateTop 落地晚于手势开始）此刻写
        // currentIndex，会经收集器触发一次 animateToValue 旧页签，与 snapValue 逐帧
        // 互搏 —— 真机录屏实证的"胶囊两端自激振荡"（手指按住对话、胶囊在对话/设置
        // 间逐帧横跳 1.6 秒）。拖拽结束后 onDragStopped 提交用户的选择，这里错过的
        // 回写由那次提交覆盖（onSelected 已把导航带到用户要的页签）。
        LaunchedEffect(selectedIndex) {
            if (!dampedDragAnimation.isDragging) currentIndex = safeSelectedIndex
        }
        // 内部态变化 → 弹簧动画到位。drop(1)：初始组合不回调。
        // ⚠️ onSelected **不在收集器里发**：收集器也会被外部回显写触达，若在那里
        // 发 onSelected → navigateTop → selectedIndex 回压 → 再写 currentIndex →
        // 再进收集器 —— 反馈环闭合，任何一次回显都能自激振荡。用户动作的两个
        // 位点（onClick / onDragStopped）直接回调，环被切断。
        // key 只留 dampedDragAnimation：调用方的 lambda 字面量每次重组都是新实例，
        // 用 rememberUpdatedState 让闭包始终读最新 lambda（见上方声明处）。
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { currentIndex }
                .drop(1)
                .collectLatest { index ->
                    // 点击切换（及外部选中态回流）走 TabSwitch 规格 —— 2026-09-24
                    // 真机修正后与上游一致（临界阻尼快弹簧，≈120ms 收敛，无过冲）。
                    dampedDragAnimation.animateToValue(
                        index.toFloat(),
                        LiquidMotion.floatSpring(LiquidMotion.TabSwitch),
                    )
                }
        }

        // 高光中心跟随"胶囊当前位置"而不是手指落点 —— 手指在哪不重要，
        // 玻璃胶囊滑到哪，高光就在哪。
        val interactiveHighlight = remember(animationScope) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, _ ->
                    // 与胶囊 translationX 同一套钳制（见下方 renderValue 注释）。
                    val v = dampedDragAnimation.value.coerceIn(0f, (tabsCount - 1).toFloat())
                    Offset(
                        if (isLtr) {
                            (v + 0.5f) * tabWidthState.value + panelOffset.value
                        } else {
                            size.width - (v + 0.5f) * tabWidthState.value +
                                panelOffset.value
                        },
                        size.height / 2f,
                    )
                },
            )
        }

        // 两行共用同一份内容 lambda：保证可见行与回显行逐帧一致。
        val tabsContent: @Composable RowScope.() -> Unit = {
            tabs.forEachIndexed { index, tab ->
                LiquidBottomTab(
                    tab = tab,
                    selected = index == currentIndex,
                    // ⚠️ onSelected 在用户动作位点直发（非收集器）——见收集器处注释。
                    // 点当前页签时 currentIndex 不变、onSelected 照发，由调用方的
                    // "destination != selected" 守卫去重（MainShell 的 onSelect）。
                    onClick = {
                        currentIndex = index
                        onSelectedCallback(index)
                        // 点击换页 → 一次 tick（与拖动换页同规格）。
                        currentHaptics.tick()
                    },
                    showLabel = !compactTabs,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        /* ── 第 1 层：滑动指示面板（可见玻璃条）────────────────────────────── */
        Row(
            Modifier
                .graphicsLayer { translationX = panelOffset.value }
                .drawBackdrop(
                    backdrop = wallpaperBackdrop,
                    shape = { Capsule },
                    effects = {
                        if (config.enableBackdropBlur) {
                            vibrancy(saturation = VibrancySaturation)
                            blur(8f.dp.toPx())
                            // 导航栏比按钮大，折射带要给足（Kyant0 Tabs：24,24）。
                            lens(refractionHeight = 24f.dp.toPx(), refractionAmount = 24f.dp.toPx())
                        }
                    },
                    layerBlock = {
                        // 按压时整条面板微微放大（相对宽度归一化，避免宽屏拉过头）。
                        val progress = dampedDragAnimation.pressProgress
                        val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, progress)
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawSurface = {
                        if (config.enableBackdropBlur) {
                            // 正常路径：THICK 底色上亮下暗垂直渐变，与 liquidGlass 同一配方。
                            val baseAlpha = thick.backgroundAlpha
                            drawRect(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        colors.glassTint.copy(alpha = baseAlpha),
                                        colors.glassTintElevated
                                            .copy(alpha = (baseAlpha * 0.72f).coerceIn(0f, 1f)),
                                    ),
                                    startY = 0f,
                                    endY = size.height,
                                )
                            )
                        } else {
                            // 退化路径（背景模糊关）：常驻底色，不读任何动画状态 ——
                            // 与 liquidGlass 门面「底色 + 高光」的退化承诺对齐。
                            drawRect(colors.glassTint.copy(alpha = thick.backgroundAlpha * 0.8f))
                        }
                    },
                )
                .then(interactiveHighlight.modifier)
                .height(64.dp)
                .fillMaxWidth()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = tabsContent,
        )

        /* ── 第 2 层：隐形回显行（录进 tabsBackdrop，供胶囊折射）───────────── */
        CompositionLocalProvider(
            LocalLiquidBottomTabScale provides {
                lerp(1f, 1.2f, dampedDragAnimation.pressProgress)
            }
        ) {
            Row(
                Modifier
                    // 回显行不参与无障碍 / 触摸（真实交互在第 1 层与第 3 层）。
                    .clearAndSetSemantics {}
                    // alpha(0f) 挂在 layerBackdrop **之前**：录制时拿到的是不透明内容，
                    // 上屏时整层透明 —— "屏幕上看不见、玻璃里看得见"。
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer { translationX = panelOffset.value }
                    .drawBackdrop(
                        backdrop = wallpaperBackdrop,
                        shape = { Capsule },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            if (config.enableBackdropBlur) {
                                vibrancy(saturation = VibrancySaturation)
                                blur(8f.dp.toPx())
                                lens(
                                    refractionHeight = 24f.dp.toPx() * progress,
                                    refractionAmount = 24f.dp.toPx() * progress,
                                )
                            }
                        },
                        highlight = {
                            if (config.enableBackdropBlur) {
                                val progress = dampedDragAnimation.pressProgress
                                Highlight.Default.copy(alpha = progress)
                            } else {
                                // 退化路径：高光是门面退化承诺的一半，不能被 progress=0 关死。
                                Highlight.Default
                            }
                        },
                        onDrawSurface = {
                            if (config.enableBackdropBlur) {
                                val baseAlpha = thick.backgroundAlpha
                                drawRect(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            colors.glassTint.copy(alpha = baseAlpha),
                                            colors.glassTintElevated
                                                .copy(alpha = (baseAlpha * 0.72f).coerceIn(0f, 1f)),
                                        ),
                                        startY = 0f,
                                        endY = size.height,
                                    )
                                )
                            } else {
                                // 退化路径：常驻底色，不读任何动画状态。
                                drawRect(colors.glassTint.copy(alpha = thick.backgroundAlpha * 0.8f))
                            }
                        },
                    )
                    .then(interactiveHighlight.modifier)
                    .height(56.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    // 整层 tint 成强调色：胶囊折射看到的"发光页签"就是这层染色的内容。
                    .graphicsLayer(colorFilter = ColorFilter.tint(colors.accent)),
                verticalAlignment = Alignment.CenterVertically,
                content = tabsContent,
            )
        }

        /* ── 第 3 层：滑动指示胶囊（手势 + 折射都在这层）───────────────────── */
        Box(
            Modifier
                .padding(horizontal = 4.dp)
                .graphicsLayer {
                    // 渲染值钳制（2026-09-24 Wave 6）：把 value 限回页签区间再参与定位。
                    // TabSwitch 改临界阻尼后弹簧本身不再过冲，这是**防御层**——将来若有
                    // 人把规格改回欠阻尼（或引入带初速的重定向），钳制保证胶囊永不画出
                    // 玻璃条两端（真机录屏 6.060s / 6.193s 帧的"漂移越界"）。拖动路径的
                    // snapValue 与点击路径的 animateToValue 目标都已 coerce，钳制在正常
                    // 路径是恒等变换，零开销。
                    val renderValue = dampedDragAnimation.value
                        .coerceIn(0f, (tabsCount - 1).toFloat())
                    translationX =
                        if (isLtr) {
                            renderValue * tabWidthState.value + panelOffset.value
                        } else {
                            size.width - (renderValue + 1f) * tabWidthState.value +
                                panelOffset.value
                        }
                }
                .then(interactiveHighlight.gestureModifier)
                .then(dampedDragAnimation.modifier)
                .drawBackdrop(
                    // 背景 = 壁纸 + 回显行：折射同时弯折壁纸与染色的页签内容。
                    backdrop = rememberCombinedBackdrop(wallpaperBackdrop, tabsBackdrop),
                    shape = { Capsule },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        // 胶囊小（1/tabsCount 宽），7 次采样扛得住 —— 色散开。
                        // 这是 Kyant0 在指示胶囊上唯一开色散的位置。
                        if (config.enableBackdropBlur) {
                            lens(
                                refractionHeight = 10f.dp.toPx() * progress,
                                refractionAmount = 14f.dp.toPx() * progress,
                                chromaticAberration = true,
                            )
                        }
                    },
                    highlight = {
                        if (config.enableBackdropBlur) {
                            val progress = dampedDragAnimation.pressProgress
                            Highlight.Default.copy(alpha = progress)
                        } else {
                            // 退化路径：高光常驻，不随按压（与回显行同一处理）。
                            Highlight.Default
                        }
                    },
                    shadow = {
                        val progress = dampedDragAnimation.pressProgress
                        Shadow(alpha = progress)
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(radius = 8f.dp * progress, alpha = progress)
                    },
                    layerBlock = {
                        // 按压缩放（DampedDragAnimation 的 scaleX/scaleY）
                        // + 速度各向异性：拖得快沿运动方向拉长、垂直方向压扁。
                        //
                        // ⚠️ 已知取舍（本轮 P0 拖动跟手改造引入）：拖动改用 snapValue
                        //（瞬时到位）后，`valueAnimatable` 不再保留"未走完的弹簧速度"，
                        // `velocity` 会偏小 → 这里的各向异性拉伸在**拖动中**会减弱
                        //（松手回弹那一段仍有速度，拉伸还在）。换取的是胶囊**严格跟手**
                        //（真机"不跟手/越远越偏差"的根治）——跟手优先。
                        // 若真机确认拉伸观感缺失，再单独调 velocity 的来源，
                        // **不为此回退绝对映射**。
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).coerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).coerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        // ⚠️ 正常路径刻意**不用** THICK 底色：面板（第 1 层）已经是 THICK，
                        // 胶囊再叠一层厚底色会把回显行的强调色盖掉、"发光"就没了。
                        // 这里只压一层薄对比色把胶囊从面板里衬出来（Kyant0 原配方），
                        // 按下时淡出、换成阴影表达"被按住"。
                        val progress = dampedDragAnimation.pressProgress
                        if (config.enableBackdropBlur) {
                            drawRect(
                                if (colors.isDark) {
                                    Color.White.copy(alpha = 0.10f)
                                } else {
                                    Color.Black.copy(alpha = 0.10f)
                                },
                                alpha = 1f - progress,
                            )
                            drawRect(Color.Black.copy(alpha = 0.03f * progress))
                        } else {
                            // 退化路径：折射没了，"选中"信号改由**常驻底色 + accent 描边**
                            // 承担（描边即选中，不依赖折射链，也不读 pressProgress ——
                            // 常驻层不引入逐帧重绘）。
                            // 描边用 drawRoundRect + Stroke（qa-review 认可的等价方案）：
                            // 半径 = min(w,h)/2，与 Capsule.createOutline 的公式完全一致，
                            // 避免 drawOutline 的引用解析问题（CI 实测 Unresolved）。
                            drawRect(colors.glassTint.copy(alpha = thick.backgroundAlpha * 0.8f))
                            val capsuleRadius = minOf(size.width, size.height) / 2f
                            drawRoundRect(
                                color = colors.accent.copy(alpha = 0.35f),
                                cornerRadius = CornerRadius(capsuleRadius, capsuleRadius),
                                style = Stroke(width = 1.dp.toPx()),
                            )
                        }
                    },
                )
                .height(56.dp)
                .fillMaxWidth(1f / tabsCount),
        )
    }
}

/**
 * 单个页签项：图标 + 文本，单击改内部选中态。
 *
 * `indication = null`：液态玻璃的高光 / 折射反馈已足够，再叠 M3 ripple 就是
 * "原生按钮贴玻璃纸"。触摸目标 56dp（容器高 64 - 上下 4×2），高于 48dp 标准。
 */
@Composable
private fun RowScope.LiquidBottomTab(
    tab: TabSpec,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
) {
    val colors = LocalGlassColors.current
    val scale = LocalLiquidBottomTabScale.current
    Column(
        modifier
            .clip(Capsule)
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val s = scale()
                scaleX = s
                scaleY = s
            },
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = tab.label,
            tint = if (selected) colors.accent else colors.onGlassSubtle,
            modifier = Modifier.size(22.dp),
        )
        // 窄容器（compactTabs）下不渲染文字：4 字标签在 ~48dp 单项宽里会被 Clip 成
        // 半个字，宁可只剩图标（contentDescription 仍保留无障碍语义）。
        if (showLabel) {
            Text(
                text = tab.label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) colors.onGlass else colors.onGlassSubtle,
                maxLines = 1,
                // 必须显式 Ellipsis：默认 Clip 在中文下是"切半个字"，比省略号观感差得多
                //（六路审查 B-P1-3）。
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}
