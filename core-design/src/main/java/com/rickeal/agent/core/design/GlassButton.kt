package com.rickeal.agent.core.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.liquid.interactive.InteractiveHighlight

/**
 * 玻璃按钮 —— 整体对齐 Kyant0 `LiquidButton`。
 *
 * ## 为什么必须整体重写（而不是继续用 LiquidGlassSurface）
 *
 * 用户真机反馈的"原生按钮"就出在这里：原来它是**圆角矩形 + 等比缩放**，
 * 而 Kyant0 / iOS Liquid Glass 的按钮是：
 *
 *  1. **胶囊**轮廓（`Capsule`，两端正半圆）—— 不是 28dp 圆角矩形；
 *  2. **极轻模糊 + 强折射**（`blur(2dp)` + `lens(12, 24)`）—— 模糊只做柔化，
 *     折射才是主角；两者反过来就是"磨砂塑料"；
 *  3. **跟手形变**（`layerBlock`）：按下时整体放大一点点，拖动时按
 *     **各向异性**拉伸（x/y 比例不同）+ tanh 阻尼位移。
 *
 * 第 3 条静态截图完全看不出来，真机一按就露馅 —— 这也是前两轮只调参数没解决的原因。
 *
 * @param cornerRadius 保留参数但**被胶囊覆盖**（Kyant0 的按钮恒为胶囊）。
 *   保留是为了不破坏既有调用点；要圆角矩形请改用 `LiquidGlassSurface`。
 */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
    material: GlassMaterial = GlassMaterial.REGULAR,
    cornerRadius: Dp = GlassDefaults.RadiusLg,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
) {
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) { InteractiveHighlight(animationScope) }
    val interactive = enabled && !loading

    Row(
        modifier = modifier
            .then(
                if (interactive) {
                    // indication = null：液态玻璃自己有高光/内阴影反馈，
                    // 再叠一层 M3 ripple 就变成"原生按钮贴玻璃纸"了。
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
            .liquidGlass(
                material = material,
                capsule = true,
                cornerRadius = cornerRadius,
                // 对齐 Kyant0 LiquidButton：blur(2.dp) + lens(12.dp, 24.dp)。
                // 模糊必须轻到能看见折射把背景像素"掰弯"，否则就是磨砂。
                blurRadius = 2.dp,
                refractionHeight = 12.dp,
                refractionAmount = 24.dp,
                // Kyant0 的 Button 没开色散：色散 7 次采样，按钮是小面积高频件，
                // 省下来的开销留给真正的大面积容器。
                dispersion = false,
                intensity = if (enabled) 1f else 0.6f,
                // 按下时玻璃"变实"（模糊减弱 / 折射增强 / 高光变亮）。
                pressProgress = { interactiveHighlight.pressProgress },
                layerBlock = if (interactive) {
                    pressLayerBlock(interactiveHighlight = interactiveHighlight, maxScale = 4.dp)
                } else {
                    null
                },
            )
            .then(
                if (interactive) {
                    // 顺序不能交换：clickable 在前、gestureModifier 在后（Kyant0 原序）。
                    // 交换后 clickable 会先吃掉手势，跟手位移就没了。
                    Modifier
                        .then(interactiveHighlight.modifier)
                        .then(interactiveHighlight.gestureModifier)
                } else {
                    Modifier
                },
            )
            .heightIn(min = tokens.minTouchTarget)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            LiquidSpinner(color = colors.onGlassMuted)
            Text(
                text = "处理中",
                style = MaterialTheme.typography.labelLarge,
                color = colors.onGlassMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            if (icon != null) {
                Box { icon() }
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) colors.onGlass else colors.onGlassSubtle,
                // HIG：按钮标签必须单行。中文没有词边界，容器一窄就整段换行成两行，
                // 胶囊按钮高度被顶高、两行文字挤在胶囊里 —— 宁可末尾省略号也不要折行。
                // 已核查全仓 27 个调用点文案最长 13 字，单行不触发省略号。
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 自绘的加载指示器。
 *
 * 刻意不用 Material3 的 `CircularProgressIndicator`：它是 M3 主题色的原生控件，
 * 放在玻璃上就是用户说的"原生按钮"观感。这里用 Canvas 画一段旋转的圆弧，
 * 颜色直接取玻璃配色，只有 16dp、2dp 描边，和胶囊按钮同一套视觉语言。
 */
@Composable
private fun LiquidSpinner(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "liquidSpinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "liquidSpinnerAngle",
    )
    Canvas(modifier = modifier.size(16.dp)) {
        rotate(angle) {
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = 300f,
                useCenter = false,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}
