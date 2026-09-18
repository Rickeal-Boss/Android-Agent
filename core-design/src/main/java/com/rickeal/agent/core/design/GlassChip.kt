package com.rickeal.agent.core.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
        modifier = modifier,
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
