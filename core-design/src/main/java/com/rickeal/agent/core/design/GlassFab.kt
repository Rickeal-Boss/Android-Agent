package com.rickeal.agent.core.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 悬浮玻璃按钮。expanded=true 时展开成胶囊并露出 label。 */
@Composable
fun GlassFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    label: String? = null,
    material: GlassMaterial = GlassMaterial.THICK,
    content: @Composable () -> Unit,
) {
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current
    val showLabel = expanded && !label.isNullOrBlank()
    LiquidGlassSurface(
        modifier = modifier,
        material = material,
        cornerRadius = tokens.radiusFull,
        onClick = onClick,
        contentPadding = PaddingValues(
            horizontal = if (showLabel) 10.dp else 0.dp,
            vertical = 0.dp,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.heightIn(min = tokens.fabSize),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(tokens.fabSize),
                contentAlignment = Alignment.Center,
            ) {
                content()
            }
            if (showLabel) {
                Text(
                    text = label.orEmpty(),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onGlass,
                    modifier = Modifier.padding(start = 2.dp, end = 10.dp),
                )
            }
        }
    }
}
