package com.rickeal.agent.core.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.liquid.EmptyBackdrop
import com.rickeal.agent.core.design.liquid.LocalBackdrop
import com.rickeal.agent.core.design.liquid.backdrops.layerBackdrop
import com.rickeal.agent.core.design.liquid.backdrops.rememberCombinedBackdrop
import com.rickeal.agent.core.design.liquid.backdrops.rememberLayerBackdrop
import com.rickeal.agent.core.design.liquid.drawBackdrop
import com.rickeal.agent.core.design.liquid.effects.lens
import com.rickeal.agent.core.design.liquid.highlight.Highlight
import com.rickeal.agent.core.design.liquid.interactive.DampedDragAnimation
import com.rickeal.agent.core.design.liquid.shadow.InnerShadow
import com.rickeal.agent.core.design.liquid.shadow.Shadow
import com.rickeal.agent.core.design.liquid.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlin.math.roundToInt

/**
 * 分段控件：容器是**胶囊**玻璃条，选中项由一枚**滑动指示胶囊**表达——
 * 单击某项或拖动胶囊，胶囊就在项与项之间滑动过渡（LiquidBottomTabs 同款机制）。
 *
 * ## 结构（与 [LiquidBottomTabs] 同一套三层骨架，缩规模复刻）
 *
 *  1. **可见行** —— 各项纯文字 + `clickable`（无自身玻璃、无手势）。
 *     "选中"不再靠 item 自带玻璃厚薄表达（旧实现 REGULAR vs ULTRA_THIN），
 *     而是由顶层胶囊承担 —— 胶囊滑到谁身上谁就是选中项。
 *  2. **隐形回显行** —— 同一份文字再渲染一遍，录进 [rememberLayerBackdrop]
 *     图层并整层 tint 成强调色：胶囊折射看到的"发光文字"就是这层染色的内容。
 *  3. **滑动指示胶囊** —— `fillMaxWidth(1f/count)` 的 Box，
 *     `translationX = value * itemWidth`；手势全在这层
 *     （[DampedDragAnimation.modifier]），
 *     背景折射 `combined(壁纸, 回显行)`。
 *
 * ## 手势分流（为什么胶囊的 consumeSlopPx 必须 8dp）
 *
 * 胶囊叠在**选中项**正上方：一次点击同时命中胶囊手势与下层 clickable。
 * 8dp 内不 consume → 抬手时事件未被消费 → 下层 clickable 正常触发（"点了没反应"
 * 的保险丝，与 [GlassSwitch] / [LiquidBottomTabs] 同一口井）；超 8dp 才消费、
 * 进入拖动换页。点**未选中**项不经过胶囊（胶囊不在那），直达 clickable。
 *
 * 折射参数沿用 LiquidBottomTabs 指示胶囊的实测值（lens 10,14 + 色散）：胶囊面积
 * 约 1/count 栏宽 × 48dp（整屏约 2~3%），与 P1-3"大面积卡片关色散"的决策不冲突，
 * 且参数乘 pressProgress（静止时折射趋 0）、受 `enableBackdropBlur` 总闸。
 */
@Composable
fun GlassSegmented(
    items: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val safeIndex = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))

    LiquidGlassSurface(
        modifier = modifier.fillMaxWidth(),
        material = GlassMaterial.THIN,
        capsule = true,
        // 大面积容器：色散 7 次采样扛不住，必须关（P1-3）。
        dispersion = false,
        contentPadding = PaddingValues(3.dp),
    ) {
        // 空列表：`fillMaxWidth(1f/0)` 会算出 NaN 约束，直接占位返回。
        if (items.isEmpty()) {
            Box(Modifier.heightIn(min = 24.dp))
            return@LiquidGlassSurface
        }
        SegmentedIndicator(
            items = items,
            selectedIndex = safeIndex,
            onSelected = onSelected,
            enabled = enabled,
        )
    }
}

@Composable
private fun SegmentedIndicator(
    items: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    enabled: Boolean,
) {
    val itemsCount = items.size
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current
    val config = LocalGlassConfig.current
    // 胶囊底色（仅退化路径用）：THICK 的 backgroundAlpha，"透明度不要太高"。
    val thick = GlassMaterials.of(GlassMaterial.THICK)

    // 全屏壁纸背景源（GlassScaffold 最外层提供）——与 LiquidBottomTabs 同源。
    val wallpaperBackdrop = if (config.enableBackdropBlur) LocalBackdrop.current else EmptyBackdrop
    // 回显行的录制层。
    val tabsBackdrop = rememberLayerBackdrop()

    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()

    // 布局量用 State 承载而不是闭包捕获：分屏/旋转后宽度变化，旧闭包会失真
    //（与 LiquidBottomTabs 的 tabWidthState 同一决定）。
    val itemWidthState = remember { mutableStateOf(0f) }

    // 内部选中态：单击项与拖动胶囊都只改它，由它统一驱动动画与回调。
    var currentIndex by remember { mutableStateOf(selectedIndex) }

    // ⚠️ consumeSlopPx 必须 8dp —— 胶囊叠在选中项上，"点击选中项没反应"的保险丝。
    val consumeSlopPx = with(density) { 8.dp.toPx() }

    // 拖动期的绝对映射基准（按下快照 / 手势内累积）。与 LiquidBottomTabs 同一套修法：
    // 拖动的事实来源是**手势本身**，不是 `targetValue` 的异步回显链 —— 旧实现把
    //「当前目标值 + 本帧位移增量」交给 `updateValue`，而 `updateValue` 内部是
    // `animateTo(spring)`（收敛动画）：拖动期每帧重启弹簧 → 追不上每帧前移的目标 →
    // 胶囊不跟手 / 越远越偏差 / 抽搐。
    // 绝对映射 + snapValue（瞬时到位）解耦。
    //
    // ⚠️ `onDragStarted` 是 `() -> Unit`（**无 receiver**），读不到 targetValue ——
    // 与 GlassSlider 一样，在按下瞬间快照可访问的 `currentIndex`：静止时它即胶囊的
    // targetValue（onDragStopped 里二者同步赋值），等价且无需额外标志位。
    var dragAccumPx by remember { mutableStateOf(0f) }
    var dragStartValue by remember { mutableStateOf(0f) }

    val dampedDragAnimation = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = selectedIndex.toFloat(),
            valueRange = 0f..(itemsCount - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            // 48dp 的胶囊按下时放大到 60dp 高。
            pressedScale = 60f / 48f,
            consumeSlopPx = consumeSlopPx,
            onDragStarted = {
                // 按下瞬间快照：本次手势的一切增量都从它出发（绝对映射）。
                dragAccumPx = 0f
                dragStartValue = currentIndex.toFloat()
            },
            onDragStopped = {
                // 松手：四舍五入到最近项，内部态收敛。
                val targetIndex = targetValue.roundToInt().coerceIn(0, itemsCount - 1)
                currentIndex = targetIndex
                animateToValue(targetIndex.toFloat())
            },
            onDrag = { _, dragAmount ->
                // 拖动 = 手势内累积位移 → 绝对映射到"项坐标"（每移动一个 itemWidth 前进
                // 一项），与异步回显完全解耦。
                dragAccumPx += dragAmount.x
                val itemWidth = itemWidthState.value
                if (itemWidth > 0f) {
                    val raw =
                        dragStartValue + dragAccumPx / itemWidth * (if (isLtr) 1f else -1f)
                    val coerced = raw.coerceIn(0f, (itemsCount - 1).toFloat())
                    // 值没变不重复 snapValue：掐掉亚像素抖动造成的无意义协程启动。
                    if (coerced != targetValue) snapValue(coerced)
                }
            },
        )
    }

    // 外部选中态变化（父层状态回流）→ 写回内部态。
    // 回流时 currentIndex 已经是同值，snapshotFlow 不再发射，不成环。
    LaunchedEffect(selectedIndex) {
        currentIndex = selectedIndex
    }
    // 内部态变化 → 弹簧动画到位 + 通知调用方。drop(1)：初始组合不回调。
    // onSelected 走 rememberUpdatedState：调用方每次重组传新 lambda 时
    // 不重启本 effect（LaunchedEffect key 只留 dampedDragAnimation）。
    val onSelectedCallback by rememberUpdatedState(onSelected)
    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { currentIndex }
            .drop(1)
            .collectLatest { index ->
                // 点击切换（及外部选中态回流）走分段/页签共用的 TabSwitch 规格 ——
                // 2026-09-24 真机修正后与上游一致（临界阻尼快弹簧，无过冲），
                // 演化依据见 LiquidMotion.TabSwitch 的 KDoc。
                // ⚠️ 拖动松手收敛（onDragStopped 里第 180 行的 animateToValue）**不传**
                // spec，保持默认快收敛 —— 两条路径现在同速。
                dampedDragAnimation.animateToValue(
                    index.toFloat(),
                    LiquidMotion.floatSpring(LiquidMotion.TabSwitch),
                )
                onSelectedCallback(index)
            }
    }

    // 注：不像 LiquidBottomTabs 那样配 InteractiveHighlight(position)——
    // 那边的高光画在面板层（第 1 层 drawBackdrop 的 highlight），position 才有意义；
    // 这边胶囊手势区是 matchParentSize 整个指示胶囊，无需 position 定制，
    // dampedDragAnimation 自带的按压缩放已覆盖按压反馈。

    Box(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { size ->
                // 可见行与回显行都是 fillMaxWidth，Box 宽度 / count = 单项宽。
                val itemWidth = size.width.toFloat() / itemsCount
                if (itemWidthState.value != itemWidth) itemWidthState.value = itemWidth
            },
    ) {
        /* ── 1. 可见行：纯文字 + clickable（无自身玻璃、无手势）──────────── */
        Row(modifier = Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, item ->
                SegmentItem(
                    text = item,
                    selected = index == currentIndex,
                    enabled = enabled,
                    onClick = { currentIndex = index },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        /* ── 2. 隐形回显行（录进 tabsBackdrop，供胶囊折射"发光文字"）──────── */
        Row(
            Modifier
                .fillMaxWidth()
                // 回显行不参与无障碍 / 触摸（真实交互在可见行与胶囊）。
                .clearAndSetSemantics {}
                // alpha(0f) 挂在 layerBackdrop **之前**：录制时拿到不透明内容，
                // 上屏时整层透明 —— "屏幕上看不见、玻璃里看得见"。
                .alpha(0f)
                .layerBackdrop(tabsBackdrop)
                .heightIn(min = tokens.minTouchTarget)
                // 整层 tint 成强调色：胶囊折射看到的"发光文字"就是这层染色的内容。
                .graphicsLayer(colorFilter = ColorFilter.tint(colors.accent)),
        ) {
            items.forEach { item ->
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = tokens.minTouchTarget)
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = item,
                        style = MaterialTheme.typography.labelLarge,
                        // 颜色会被整层 tint 覆盖，但必须不透明（录制依赖不透明内容）。
                        color = Color.White,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        /* ── 3. 滑动指示胶囊（手势 + 折射都在这层）────────────────────────── */
        Box(modifier = Modifier.matchParentSize()) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(1f / itemsCount)
                    .graphicsLayer {
                        translationX =
                            if (isLtr) {
                                dampedDragAnimation.value * itemWidthState.value
                            } else {
                                size.width - (dampedDragAnimation.value + 1f) * itemWidthState.value
                            }
                    }
                    .then(
                        // 禁用时不挂手势：胶囊静态显示在选中位置。
                        if (enabled) {
                            Modifier.then(dampedDragAnimation.modifier)
                        } else {
                            Modifier
                        },
                    )
                    .drawBackdrop(
                        // 背景 = 壁纸 + 回显行：折射同时弯折壁纸与染色的文字。
                        backdrop = rememberCombinedBackdrop(wallpaperBackdrop, tabsBackdrop),
                        shape = { Capsule },
                        effects = {
                            if (config.enableBackdropBlur) {
                                val progress = dampedDragAnimation.pressProgress
                                // 胶囊小（1/count 栏宽），7 次采样扛得住 —— 色散开
                                //（与 LiquidBottomTabs 指示胶囊同一判定，受总闸）。
                                lens(
                                    refractionHeight = 10f.dp.toPx() * progress,
                                    refractionAmount = 14f.dp.toPx() * progress,
                                    chromaticAberration = true,
                                )
                            }
                        },
                        highlight = {
                            if (config.enableBackdropBlur) {
                                Highlight.Default.copy(alpha = dampedDragAnimation.pressProgress)
                            } else {
                                // 退化路径：高光常驻，不随按压。
                                Highlight.Default
                            }
                        },
                        shadow = {
                            Shadow(alpha = dampedDragAnimation.pressProgress)
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
                            if (config.enableBackdropBlur) {
                                // 正常路径只压一层薄对比色把胶囊衬出来（Kyant0 原配方），
                                // 按下时淡出、换成阴影表达"被按住"。
                                val progress = dampedDragAnimation.pressProgress
                                drawRect(
                                    if (colors.isDark) {
                                        Color.White.copy(alpha = 0.10f)
                                    } else {
                                        Color.Black.copy(alpha = 0.10f)
                                    },
                                    alpha = 1f - progress,
                                )
                                drawRect(
                                    Color.Black
                                        .copy(alpha = 0.03f * progress),
                                )
                            } else {
                                // 退化路径：折射没了，"选中"信号改由常驻底色 + accent 描边
                                // 承担（描边即选中，不依赖折射链，也不读 pressProgress ——
                                // 常驻层不引入逐帧重绘）。
                                // 描边用 drawRoundRect + Stroke（同 LiquidBottomTabs fd74a1c）：
                                // 半径 = min(w,h)/2，与 Capsule.createOutline 公式一致，
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
                    ),
            )
        }
    }
}

/**
 * 单个分段项：纯文字 + 单击改内部选中态。
 *
 * "选中"的玻璃表达由顶层滑动胶囊承担，item 自身不再挂 liquidGlass
 * （旧实现 REGULAR vs ULTRA_THIN 的厚薄区分会让胶囊滑到时出现双层玻璃）。
 *
 * `indication = null`：液态玻璃的高光 / 折射反馈已足够，再叠 M3 ripple 就是
 * "原生按钮贴玻璃纸"。触摸目标必须是完整 48dp。
 */
@Composable
private fun SegmentItem(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current
    val motion = LocalLiquidMotion.current

    val pressScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.94f,
        animationSpec = LiquidMotion.floatSpring(motion),
        label = "segmentScale",
    )
    val textColor = if (selected) colors.onGlass else colors.onGlassSubtle

    Box(
        modifier = modifier
            .then(
                if (enabled) {
                    Modifier.clickable(
                        interactionSource = null,
                        indication = null,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            // 触摸目标必须是完整 48dp（分段控件是主要操作入口）。
            .heightIn(min = tokens.minTouchTarget)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = textColor,
            maxLines = 1,
            // 选中/取消的弹簧缩放挂在 **Text**（链最内层）而不是 Box 外层：
            // graphicsLayer 缩放的是其后的绘制，若挂外层会把 48dp 命中区也缩掉
            //（0.94 → 实际命中 45dp，违反触摸目标标准）；挂内层只缩视觉不缩命中。
            modifier = Modifier.graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            },
            textAlign = TextAlign.Center,
        )
    }
}
