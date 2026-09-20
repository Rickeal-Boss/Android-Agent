package com.rickeal.agent.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.liquid.Backdrop
import com.rickeal.agent.core.design.liquid.EmptyBackdrop
import com.rickeal.agent.core.design.liquid.LocalBackdrop
import com.rickeal.agent.core.design.liquid.backdrops.layerBackdrop
import com.rickeal.agent.core.design.liquid.backdrops.rememberBackdrop
import com.rickeal.agent.core.design.liquid.backdrops.rememberCombinedBackdrop
import com.rickeal.agent.core.design.liquid.backdrops.rememberLayerBackdrop
import com.rickeal.agent.core.design.liquid.drawBackdrop
import com.rickeal.agent.core.design.liquid.effects.blur
import com.rickeal.agent.core.design.liquid.effects.lens
import com.rickeal.agent.core.design.liquid.highlight.Highlight
import com.rickeal.agent.core.design.liquid.interactive.DampedDragAnimation
import com.rickeal.agent.core.design.liquid.shadow.InnerShadow
import com.rickeal.agent.core.design.liquid.shadow.Shadow
import com.rickeal.agent.core.design.liquid.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

/**
 * 参数滑块 —— 整体对齐 Kyant0 `LiquidSlider`。
 *
 * ## 必须完全重写的原因
 *
 * 原来这里是 `androidx.compose.material3.Slider`（用户点名的"原生按钮"之一就是它）：
 * 无论壁纸/折射调多准，观感永远是"原生 M3 控件贴在玻璃纸上"。
 * 现在整个组件基于玻璃引擎自绘：
 *
 *  - 轨道 / 已填充段 / thumb 全部是**胶囊**；
 *  - thumb 是真玻璃：`blur(8dp * (1-progress))` + `lens(10dp, 14dp, 色散开)`；
 *  - 拖动时 thumb 沿拖动方向**拉长**、垂直方向**压扁**（各向异性），松手回弹；
 *  - 点轨道任意位置会跳过去（带弹簧），不是只在 thumb 上能拖。
 *
 * ⚠️ 本文件**不得**再出现 `androidx.compose.material3.Slider` / `SliderDefaults`。
 */
@Composable
fun GlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    valueText: String? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val colors = LocalGlassColors.current
    val backdrop = if (LocalGlassConfig.current.enableBackdropBlur) {
        LocalBackdrop.current
    } else {
        EmptyBackdrop
    }
    Column(modifier = modifier.fillMaxWidth()) {
        if (label != null || valueText != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (label != null) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (enabled) colors.onGlass else colors.onGlassSubtle,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (valueText != null) {
                    Text(
                        text = valueText,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (enabled) colors.onGlassMuted else colors.onGlassSubtle,
                    )
                }
            }
        }
        LiquidSliderTrack(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            backdrop = backdrop,
            accentColor = if (enabled) colors.accent else colors.onGlassSubtle,
            trackColor = colors.accentMuted,
        )
    }
}

@Composable
private fun LiquidSliderTrack(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)?,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    backdrop: Backdrop,
    accentColor: Color,
    trackColor: Color,
) {
    val trackBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(
        Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart,
    ) {
        val trackWidth = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        var didDrag by remember { mutableStateOf(false) }
        // 这些值在 remember 出来的回调里被读取，必须用 rememberUpdatedState 拿最新值，
        // 否则回调会闭包住第一次组合时的旧引用（滑块在列表里复用时尤其明显）。
        val currentValue by rememberUpdatedState(value)
        val currentRange by rememberUpdatedState(valueRange)
        val currentSteps by rememberUpdatedState(steps)
        val currentOnChange by rememberUpdatedState(onValueChange)
        val currentOnFinished by rememberUpdatedState(onValueChangeFinished)

        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = currentValue.coerceIn(currentRange),
                valueRange = currentRange,
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 1.5f,
                onDragStarted = {},
                onDragStopped = {
                    if (didDrag) {
                        currentOnFinished?.invoke()
                        didDrag = false
                    }
                },
                onDrag = { _, dragAmount ->
                    if (!didDrag) {
                        didDrag = dragAmount.x != 0f
                    }
                    val range = currentRange
                    val delta = (range.endInclusive - range.start) * (dragAmount.x / trackWidth)
                    val raw = if (isLtr) targetValue + delta else targetValue - delta
                    currentOnChange(snapToStep(raw.coerceIn(range), range, currentSteps))
                }
            )
        }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { currentValue }
                .collectLatest { v ->
                    if (dampedDragAnimation.targetValue != v) {
                        dampedDragAnimation.updateValue(v)
                    }
                }
        }

        // 轨道本体：录进 trackBackdrop，供 thumb 做"透过玻璃看轨道"的折射。
        Box(Modifier.layerBackdrop(trackBackdrop)) {
            Box(
                Modifier
                    .clip(Capsule)
                    .background(trackColor)
                    .pointerInput(animationScope) {
                        detectTapGestures { position ->
                            if (!enabled) return@detectTapGestures
                            val range = currentRange
                            val delta = (range.endInclusive - range.start) * (position.x / trackWidth)
                            val target =
                                if (isLtr) range.start + delta
                                else range.endInclusive - delta
                            val snapped = snapToStep(target.coerceIn(range), range, currentSteps)
                            dampedDragAnimation.animateToValue(snapped)
                            currentOnChange(snapped)
                            currentOnFinished?.invoke()
                        }
                    }
                    .height(6f.dp)
                    .fillMaxWidth(),
            )
            Box(
                Modifier
                    .clip(Capsule)
                    .background(accentColor)
                    .height(6f.dp)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val width = (constraints.maxWidth * dampedDragAnimation.progress).roundToInt()
                        layout(width, placeable.height) {
                            placeable.place(0, 0)
                        }
                    },
            )
        }

        // Thumb：真玻璃 + 跟手各向异性拉伸。
        Box(
            Modifier
                .graphicsLayer {
                    translationX =
                        (-size.width / 2f + trackWidth * dampedDragAnimation.progress)
                            .coerceIn(-size.width / 4f, trackWidth - size.width * 3f / 4f) *
                            if (isLtr) 1f else -1f
                }
                .then(if (enabled) dampedDragAnimation.modifier else Modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(
                        backdrop,
                        rememberBackdrop(trackBackdrop) { drawBackdrop ->
                            val progress = dampedDragAnimation.pressProgress
                            val scaleX = 2f / 3f + (1f - 2f / 3f) * progress
                            val scaleY = progress
                            scale(scaleX, scaleY) {
                                drawBackdrop()
                            }
                        }
                    ),
                    shape = { Capsule },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        blur(8f.dp.toPx() * (1f - progress))
                        lens(
                            10f.dp.toPx() * progress,
                            14f.dp.toPx() * progress,
                            chromaticAberration = true
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        Highlight.Ambient.copy(
                            width = Highlight.Ambient.width / 1.5f,
                            blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                            alpha = progress
                        )
                    },
                    shadow = {
                        Shadow(
                            radius = 4f.dp,
                            color = Color.Black.copy(alpha = 0.05f)
                        )
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(
                            radius = 4f.dp * progress,
                            alpha = progress
                        )
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        // 快速拖动时沿拖动方向拉长、垂直方向压扁 —— 液态感最强的一处。
                        val velocity = dampedDragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).coerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).coerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(Color.White.copy(alpha = 1f - progress))
                    }
                )
                .size(40f.dp, 24f.dp),
        )
    }
}

/**
 * 按 [steps] 吸附。
 *
 * [steps] 是 min..max 之间的**档数**（与 M3 语义一致）：steps = 30 表示
 * 区间被等分成 31 份。steps <= 0 表示连续。
 */
private fun snapToStep(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int
): Float {
    if (steps <= 0) return value
    val span = range.endInclusive - range.start
    if (span <= 0f) return range.start
    val stepSize = span / (steps + 1)
    return (range.start + ((value - range.start) / stepSize).roundToInt() * stepSize)
        .coerceIn(range)
}
