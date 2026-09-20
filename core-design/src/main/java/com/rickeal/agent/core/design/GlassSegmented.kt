package com.rickeal.agent.core.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * iOS 风格分段控件：容器是 THIN 玻璃，选中项是 REGULAR 玻璃"胶囊"，
 * 选中/取消用弹簧缩放，不用位移（省掉一次测量，且天然居中）。
 */
@Composable
fun GlassSegmented(
    items: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current
    val motion = LocalLiquidMotion.current
    val safeIndex = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))

    LiquidGlassSurface(
        modifier = modifier.fillMaxWidth(),
        material = GlassMaterial.THIN,
        cornerRadius = tokens.radiusFull,
        contentPadding = PaddingValues(3.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, item ->
                val selected = index == safeIndex
                val scale by animateFloatAsState(
                    targetValue = if (selected) 1f else 0.94f,
                    animationSpec = LiquidMotion.floatSpring(motion),
                    label = "segmentScale",
                )
                val textColor = if (selected) {
                    colors.onGlass
                } else {
                    colors.onGlassSubtle
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        // 触摸目标必须是完整 48dp（原来是 minTouchTarget - 12.dp = 36dp）。
                        // 分段控件是主要操作入口，36dp 在高 DPI 屏上误触率明显。
                        .heightIn(min = tokens.minTouchTarget)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .then(
                            if (selected) {
                                Modifier.liquidGlass(
                                    material = GlassMaterial.REGULAR,
                                    cornerRadius = tokens.radiusFull,
                                )
                            } else {
                                Modifier
                            },
                        )
                        .then(
                            if (enabled) {
                                Modifier.clickable { onSelected(index) }
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = item,
                        style = MaterialTheme.typography.labelLarge,
                        color = textColor,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
