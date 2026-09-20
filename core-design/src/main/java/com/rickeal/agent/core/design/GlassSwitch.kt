package com.rickeal.agent.core.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
import kotlin.math.abs

/**
 * 玻璃开关 —— 整体对齐 Kyant0 `LiquidToggle`。
 *
 * ## 必须完全重写的原因
 *
 * 原来这里是 `androidx.compose.material3.Switch`（用户点名的"原生按钮"之一）：
 * 它是 M3 主题色的原生控件，放在玻璃上观感永远是"原生开关贴玻璃纸"。
 * 现在整个开关基于玻璃引擎自绘：
 *
 *  - 轨道与 thumb 都是**胶囊**；
 *  - thumb 是真玻璃：静止时 `blur(8dp)`，按下/拖动时模糊减弱、折射增强
 *    （`lens(5dp, 10dp, 色散开)`）—— 玻璃被"按实"了；
 *  - thumb 透过玻璃看得到**轨道的颜色**（`rememberCombinedBackdrop`，
 *    和 Kyant0 一致），这是"一块有厚度的玻璃"的关键；
 *  - 支持拖动切换（不只是点击），拖动时 thumb 沿拖动方向拉长。
 *
 * ⚠️ 本文件**不得**再出现 `androidx.compose.material3.Switch` / `SwitchDefaults`。
 */
@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalGlassColors.current
    val backdrop = if (LocalGlassConfig.current.enableBackdropBlur) {
        LocalBackdrop.current
    } else {
        EmptyBackdrop
    }
    val accentColor = if (enabled) colors.accent else colors.onGlassSubtle
    val trackColor = colors.accentMuted

    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val dragWidth = with(density) { 20f.dp.toPx() }
    // 判定"拖过 / 纯点击"的阈值。Kyant0 原实现没有这个，真机上任何亚像素抖动都会被
    // 判成拖动 —— 8dp 与系统 ViewConfiguration.touchSlop 同量级。
    val touchSlopPx = with(density) { 8.dp.toPx() }
    val animationScope = rememberCoroutineScope()
    // 累积横向位移（关键：记"位移了多少"，不是记"有没有位移过"）。
    // 真机没有绝对静止的点击，手指抖 1px 也会产生 dragAmount；旧代码只要横向分量
    // 非零就置"已拖动" → fraction 只漂移一点点 → 松手吸附回**原状态**
    // → 点了开关不切换，只抖一下弹回去。这就是本条要修的 bug。
    var draggedX by remember { mutableStateOf(0f) }
    var fraction by remember { mutableStateOf(if (checked) 1f else 0f) }
    // 回调里要读最新值，不能闭包住第一次组合时的旧引用。
    val currentChecked by rememberUpdatedState(checked)
    val currentOnCheckedChange by rememberUpdatedState(onCheckedChange)

    val dampedDragAnimation = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = fraction,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.5f,
            onDragStarted = {},
            onDragStopped = {
                if (abs(draggedX) > touchSlopPx) {
                    // 真的拖过了：按松手时的位置决定最终态
                    fraction = if (targetValue >= 0.5f) 1f else 0f
                } else {
                    // 位移没越过 touch slop → 就是纯点击，直接翻转
                    fraction = if (currentChecked) 0f else 1f
                }
                currentOnCheckedChange?.invoke(fraction == 1f)
                draggedX = 0f
            },
            onDrag = { _, dragAmount ->
                draggedX += dragAmount.x
                val delta = dragAmount.x / dragWidth
                fraction =
                    if (isLtr) (fraction + delta).coerceIn(0f, 1f)
                    else (fraction - delta).coerceIn(0f, 1f)
            }
        )
    }
    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { fraction }
            .collectLatest { f -> dampedDragAnimation.updateValue(f) }
    }
    LaunchedEffect(checked) {
        snapshotFlow { currentChecked }
            .collectLatest { isChecked ->
                val target = if (isChecked) 1f else 0f
                if (target != fraction) {
                    fraction = target
                    dampedDragAnimation.animateToValue(target)
                }
            }
    }

    val interactive = enabled && onCheckedChange != null
    val trackBackdrop = rememberLayerBackdrop()

    Box(modifier, contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier
                .layerBackdrop(trackBackdrop)
                .clip(Capsule)
                .drawBehind {
                    drawRect(
                        lerp(trackColor, accentColor, dampedDragAnimation.value.coerceIn(0f, 1f))
                    )
                }
                .size(64f.dp, 28f.dp),
        )
        Box(
            Modifier
                .graphicsLayer {
                    val f = dampedDragAnimation.value.coerceIn(0f, 1f)
                    val padding = 2f.dp.toPx()
                    translationX =
                        if (isLtr) padding + dragWidth * f
                        else -(padding + dragWidth * f)
                }
                .semantics {
                    role = Role.Switch
                }
                .then(if (interactive) dampedDragAnimation.modifier else Modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(
                        backdrop,
                        rememberBackdrop(trackBackdrop) { drawBackdrop ->
                            val progress = dampedDragAnimation.pressProgress
                            val scaleX = 2f / 3f + (0.75f - 2f / 3f) * progress
                            val scaleY = 0.75f * progress
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
                            5f.dp.toPx() * progress,
                            10f.dp.toPx() * progress,
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
                        val velocity = dampedDragAnimation.velocity / 50f
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
