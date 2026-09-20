package com.rickeal.agent.core.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 玻璃标签。selected 时换 REGULAR 材质 + 主文本色强调。 */
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
    LiquidGlassSurface(
        // 默认只有 ≈30dp（7dp 内边距 + labelMedium），够不上 minTouchTarget。
        // 可点击的 chip 必须保证 48dp 高；不可点击的（纯展示标签）也一起补齐，
        // 免得同一行里高矮不齐。
        modifier = modifier.heightIn(min = tokens.minTouchTarget),
        material = if (selected) GlassMaterial.REGULAR else GlassMaterial.ULTRA_THIN,
        cornerRadius = tokens.radiusFull,
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Box(modifier = Modifier.padding(end = 6.dp)) { icon() }
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) colors.onGlass else colors.onGlassMuted,
            )
        }
    }
}
