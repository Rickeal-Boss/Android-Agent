package com.rickeal.agent.core.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.liquid.interactive.InteractiveHighlight

/**
 * 玻璃标签：胶囊 + 跟手形变。
 *
 * 原来用的是 `LiquidGlassSurface`（圆角矩形 + 等比缩放）。胶囊是 iOS Liquid Glass
 * 的标志性轮廓，chip 又是高频点击件，所以这里和 [GlassButton] 走同一套
 * （见 [pressLayerBlock]）。
 *
 * 触摸目标仍是完整 48dp：chip 默认只有约 30dp（7dp 内边距 + labelMedium），
 * 可点击与纯展示的都一补齐，免得同一行里高矮不齐。
 */
@Composable
fun GlassChip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
) {
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) { InteractiveHighlight(animationScope) }

    Row(
        modifier = modifier
            .then(
                if (onClick != null) {
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
                blurRadius = 2.dp,
                refractionHeight = 12.dp,
                refractionAmount = 24.dp,
                dispersion = false,
                pressProgress = interactiveHighlight.pressProgress,
                layerBlock = if (onClick != null) {
                    pressLayerBlock(interactiveHighlight = interactiveHighlight, maxScale = 4.dp)
                } else {
                    null
                },
            )
            .then(
                // 顺序不能交换：clickable 在前、gestureModifier 在后（Kyant0 原序）。
                if (onClick != null) {
                    Modifier
                        .then(interactiveHighlight.modifier)
                        .then(interactiveHighlight.gestureModifier)
                } else {
                    Modifier
                },
            )
            .heightIn(min = tokens.minTouchTarget)
            .padding(PaddingValues(horizontal = 12.dp, vertical = 7.dp)),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box { icon() }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) colors.onGlass else colors.onGlassMuted,
        )
    }
}
