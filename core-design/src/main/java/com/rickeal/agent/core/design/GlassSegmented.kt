package com.rickeal.agent.core.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.liquid.interactive.InteractiveHighlight

/**
 * 分段控件：容器与选中项都是**胶囊**，选中项是带跟手形变的玻璃。
 *
 * 折射参数取 Kyant0 `LiquidBottomTabs` 的 `lens(24, 24)` —— 分段控件的容器
 * 比按钮大，折射带要给足才看得出厚度。
 *
 * 每个分段项是独立的 composable（[SegmentItem]），各自持有自己的
 * [InteractiveHighlight]：否则多个项共用一个对象会互相打架（按 A 时 B 也形变）。
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
        // 大面积容器：色散 7 次采样扛不住，必须关。
        dispersion = false,
        contentPadding = PaddingValues(3.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, item ->
                SegmentItem(
                    text = item,
                    selected = index == safeIndex,
                    enabled = enabled,
                    onClick = { onSelected(index) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

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
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) { InteractiveHighlight(animationScope) }

    val pressScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.94f,
        animationSpec = LiquidMotion.floatSpring(motion),
        label = "segmentScale",
    )
    val textColor = if (selected) colors.onGlass else colors.onGlassSubtle

    Box(
        modifier = modifier
            // 选中/取消用弹簧缩放，不用位移（省掉一次测量，且天然居中）。
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
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
            .liquidGlass(
                material = if (selected) GlassMaterial.REGULAR else GlassMaterial.ULTRA_THIN,
                capsule = true,
                blurRadius = 8.dp,
                refractionHeight = 24.dp,
                refractionAmount = 24.dp,
                dispersion = false,
                pressProgress = { interactiveHighlight.pressProgress },
                layerBlock = if (enabled) {
                    pressLayerBlock(interactiveHighlight = interactiveHighlight, maxScale = 16.dp)
                } else {
                    null
                },
            )
            .then(
                // 顺序不能交换：clickable 在前、gestureModifier 在后（Kyant0 原序）。
                if (enabled) {
                    Modifier
                        .then(interactiveHighlight.modifier)
                        .then(interactiveHighlight.gestureModifier)
                } else {
                    Modifier
                },
            )
            // 触摸目标必须是完整 48dp（原来是 minTouchTarget - 12.dp = 36dp）。
            // 分段控件是主要操作入口，36dp 在高 DPI 屏上误触率明显。
            .heightIn(min = tokens.minTouchTarget)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = textColor,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}
