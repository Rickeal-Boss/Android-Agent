package com.rickeal.agent.core.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 玻璃按钮。loading 时用指示器替换文本，宽度不跳变由调用方给定 modifier 决定。 */
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
    LiquidGlassSurface(
        // 默认 12dp 内边距 + labelLarge ≈ 44dp，差一点点；补到 48dp 达标。
        modifier = modifier.heightIn(min = tokens.minTouchTarget),
        material = material,
        cornerRadius = cornerRadius,
        enabled = enabled,
        onClick = if (enabled && !loading) onClick else null,
        contentPadding = contentPadding,
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = colors.onGlassMuted,
                    strokeWidth = 2.dp,
                )
                Text(
                    text = "处理中",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onGlassMuted,
                    modifier = Modifier.padding(start = 8.dp),
                )
            } else {
                if (icon != null) {
                    Box(modifier = Modifier.padding(end = 8.dp)) { icon() }
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (enabled) colors.onGlass else colors.onGlassSubtle,
                )
            }
        }
    }
}
