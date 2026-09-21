package com.rickeal.agent.core.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
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
 * ## 页签切换的数据流（单击与拖动在同一个汇合点收敛）
 *
 *  - 单击页签 → `currentIndex = index`
 *  - 拖动胶囊 → `onDragStopped` 里 `currentIndex = targetValue.roundToInt()`
 *  - 两条路都汇入 `snapshotFlow { currentIndex }` → 弹簧动画到位 + [onSelected] 回调
 *  - 外部 [selectedIndex] 变化（导航返回等）→ [LaunchedEffect] 写回 [currentIndex]
 *    → 同样走弹簧，指示胶囊滑回正确页签。回调回流的值不变，不会成环。
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
        // 带守卫的写入：值没变不触发失效，不会造成"组合期写状态"的重组循环。
        if (tabWidthState.value != tabWidth) tabWidthState.value = tabWidth
        val maxWidthPx = constraints.maxWidth.toFloat()
        if (containerWidthState.value != maxWidthPx) containerWidthState.value = maxWidthPx

        // 面板拉伸偏移：拖动时整条玻璃朝拖动方向最长拉 4dp，松手弹簧归零。
        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset = remember(density) {
            derivedStateOf {
                val containerWidth = containerWidthState.value
                val fraction = if (containerWidth > 0f) {
                    (offsetAnimation.value / containerWidth).coerceIn(-1f, 1f)
                } else {
                    0f
                }
                with(density) {
                    4f.dp.toPx() * sign(fraction) * EaseOut.transform(abs(fraction))
                }
            }
        }

        // 内部选中态：单击页签与拖动胶囊都只改它，由它统一驱动动画与回调。
        var currentIndex by remember { mutableStateOf(safeSelectedIndex) }

        // ⚠️ consumeSlopPx 必须 8dp（见类 KDoc）——"点击选中页签没反应"的保险丝。
        val consumeSlopPx = with(density) { 8.dp.toPx() }
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
                onDragStarted = {},
                onDragStopped = {
                    // 松手：四舍五入到最近页签，内部态收敛，面板拉伸弹回 0。
                    val targetIndex = targetValue.roundToInt().coerceIn(0, tabsCount - 1)
                    currentIndex = targetIndex
                    animateToValue(targetIndex.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(
                            0f,
                            spring(dampingRatio = 1f, stiffness = 300f, visibilityThreshold = 0.5f),
                        )
                    }
                },
                onDrag = { _, dragAmount ->
                    // 拖动 = 把累计位移换算成"页签坐标"（每移动一个 tabWidth 前进一页）。
                    updateValue(
                        (targetValue + dragAmount.x / tabWidthState.value * if (isLtr) 1f else -1f)
                            .coerceIn(0f, (tabsCount - 1).toFloat())
                    )
                    // 面板拉伸逐帧跟上（snapTo，不走弹簧 —— 弹簧留给松手回弹）。
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                },
            )
        }

        // 外部选中态变化（导航返回 / 程序化切换）→ 写回内部态。
        // 回流时 currentIndex 已经是同值，snapshotFlow 不再发射，不成环。
        LaunchedEffect(selectedIndex) {
            currentIndex = safeSelectedIndex
        }
        // 内部态变化 → 弹簧动画到位 + 通知调用方。drop(1)：初始组合不回调
        // （外部本来就知道当前选中的是谁）。
        //
        // ⚠️ key 只留 dampedDragAnimation，不含 onSelected：调用方的 lambda 字面量
        // 每次重组都是新实例，拿它当 key 会让 effect 随导航状态反复重启（drop(1)
        // 能保正确性，但纯属浪费）。用 rememberUpdatedState 让闭包始终读最新
        // lambda，effect 生命周期与组件一致，调用方零负担。
        val onSelectedCallback by rememberUpdatedState(onSelected)
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { currentIndex }
                .drop(1)
                .collectLatest { index ->
                    dampedDragAnimation.animateToValue(index.toFloat())
                    onSelectedCallback(index)
                }
        }

        // 高光中心跟随"胶囊当前位置"而不是手指落点 —— 手指在哪不重要，
        // 玻璃胶囊滑到哪，高光就在哪。
        val interactiveHighlight = remember(animationScope) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, _ ->
                    Offset(
                        if (isLtr) {
                            (dampedDragAnimation.value + 0.5f) * tabWidthState.value + panelOffset.value
                        } else {
                            size.width - (dampedDragAnimation.value + 0.5f) * tabWidthState.value +
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
                    onClick = { currentIndex = index },
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
                    translationX =
                        if (isLtr) {
                            dampedDragAnimation.value * tabWidthState.value + panelOffset.value
                        } else {
                            size.width - (dampedDragAnimation.value + 1f) * tabWidthState.value +
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
                            drawRect(colors.glassTint.copy(alpha = thick.backgroundAlpha * 0.8f))
                            drawOutline(
                                outline = Outline.Generic(
                                    Capsule.createOutline(size, layoutDirection, this)
                                ),
                                color = colors.accent.copy(alpha = 0.35f),
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
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) colors.onGlass else colors.onGlassSubtle,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}
