package com.rickeal.agent.core.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** 思考中的三点呼吸指示器（错相位 + 缩放，不用线性位移）。 */
@Composable
fun GlassThinkingIndicator(
    modifier: Modifier = Modifier,
    label: String? = "思考中",
) {
    val colors = LocalGlassColors.current
    val transition = rememberInfiniteTransition(label = "thinking")
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (index in 0..2) {
            val scale by transition.animateFloat(
                initialValue = 0.55f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 620, delayMillis = index * 130, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .background(color = colors.onGlassMuted, shape = CircleShape),
            )
        }
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onGlassSubtle,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
fun GlassDivider(modifier: Modifier = Modifier, alpha: Float = 0.35f) {
    val colors = LocalGlassColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(colors.onGlass.copy(alpha = alpha.coerceIn(0f, 1f))),
    )
}

/**
 * 空态按钮排的单个动作。由调用方把导航/业务回调映射进来
 * （`:core-design` 不依赖导航库，见架构 §1.4）。
 */
data class GlassEmptyStateAction(
    val label: String,
    val onClick: () -> Unit,
)

@Composable
fun GlassEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    /**
     * 按钮排：非空时在 subtitle 下方渲染一行 [GlassButton]。
     * 空态不该只"告知"还得"给出口"——用户卡在空对话页时最需要的就是
     * 三条进入模型的路径，而不是一句干巴巴的提示。
     */
    actions: List<GlassEmptyStateAction> = emptyList(),
    action: (@Composable () -> Unit)? = null,
) {
    val colors = LocalGlassColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.onGlassMuted,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onGlassSubtle,
            )
        }
        if (actions.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (item in actions) {
                    GlassButton(text = item.label, onClick = item.onClick)
                }
            }
        }
        if (action != null) {
            Box(modifier = Modifier.padding(top = 10.dp)) { action() }
        }
    }
}
