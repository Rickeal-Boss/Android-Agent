package com.rickeal.agent.core.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.liquid.interactive.InteractiveHighlight

/**
 * 设置页的通用「标题 + 说明 + 右侧控件」行。
 *
 * 接 [InteractiveHighlight]：按下/拖动整行时玻璃「变实」并**跟手位移 + 各向异性拉伸**，
 * 与 [GlassButton] / [GlassChip] 同一套（见 [pressLayerBlock]）。
 *
 * ⚠️ 只挂 `gestureModifier`、**不挂** `InteractiveHighlight.modifier`：后者会在触摸点
 * 画一圈半径 = min(w,h)*0.9 的镜面高光。整行是"全宽 + 48dp 高"，那个半径（≈43dp）
 * 比行高的一半还大，高光会从上下边缘溢出去。按钮/Chip 是胶囊（高度≈宽度的一截）
 * 所以能挂，整行的比例不行。
 */
@Composable
fun GlassSettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) { InteractiveHighlight(animationScope) }
    val interactive = onClick != null && enabled

    LiquidGlassSurface(
        // 无副标题时整行只有 ≈44dp；整行都是点击区，补到 48dp。
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = tokens.minTouchTarget)
            .then(if (interactive) interactiveHighlight.gestureModifier else Modifier),
        material = GlassMaterial.THIN,
        cornerRadius = tokens.radiusMd,
        enabled = enabled,
        onClick = onClick,
        // 设置页是 LazyColumn，一屏十几行 —— 色散 7 次采样在滚动路径上必掉帧。
        // 折射保留（THIN 材质自带 10/18），厚度感靠它。
        dispersion = false,
        layerBlock = if (interactive) {
            pressLayerBlock(interactiveHighlight = interactiveHighlight, maxScale = 4.dp)
        } else {
            null
        },
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) colors.onGlass else colors.onGlassSubtle,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onGlassSubtle,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (trailing != null) {
                Box(modifier = Modifier.padding(start = 12.dp)) { trailing() }
            } else if (onClick != null) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.onGlassSubtle,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(16.dp),
                )
            }
        }
    }
}
