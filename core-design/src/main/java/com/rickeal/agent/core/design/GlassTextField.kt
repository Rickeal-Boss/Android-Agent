package com.rickeal.agent.core.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

/**
 * 玻璃输入框。用 BasicTextField + decorationBox，避免 material3 TextField 的实色下划线。
 *
 * ## 「液态」反馈点是聚焦
 *
 * 输入框没有按压态（按下去就是聚焦），所以它的液态反馈挂在**聚焦**上：
 * 聚焦时玻璃「变实」—— 模糊减弱、折射增强、高光变亮（复用 liquidGlass 的
 * pressProgress 通道）。这是输入框唯一会被用户摸到的状态变化。
 *
 * ## 形状：36dp 大圆角，刻意不是胶囊
 *
 * 输入框支持多行（maxLines 默认 6）。做成胶囊（两端半圆）会把文字挤到圆角里，
 * 多行时尤其明显 —— 首行和末行会被裁掉大半。所以取 `radiusXl`（36dp）：
 * 比原来的 `radiusMd`（20dp）明显更「玻璃」，又不吃内容。
 */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else 6,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current
    val interactionSource = remember { MutableInteractionSource() }
    val textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onGlass)
    val focused by interactionSource.collectIsFocusedAsState()
    val focusProgress by animateFloatAsState(
        targetValue = if (focused && enabled) 1f else 0f,
        animationSpec = LiquidMotion.floatSpring(LocalLiquidMotion.current),
        label = "glassTextFieldFocus",
    )

    // 刻意不走 LiquidGlassSurface：它的 pressProgress 只由 onClick 驱动，
    // 而输入框没有 onClick —— 液态反馈必须走「聚焦」这条，所以直接调 liquidGlass。
    Box(
        modifier = modifier.liquidGlass(
            material = GlassMaterial.ULTRA_THIN,
            cornerRadius = tokens.radiusXl,
            // 逐组件折射：输入框是中等面积，给 12/20（比 THIN 的 10/18 略强一点），
            // 让聚焦时「变实」的变化看得出来。
            refractionHeight = 12.dp,
            refractionAmount = 20.dp,
            // 关色散：输入框获得焦点后会逐帧重绘（光标闪烁 + 输入），
            // 色散 7 次采样在逐帧路径上太贵；而且边缘彩虹会啃掉文字清晰度。
            dispersion = false,
            pressProgress = focusProgress,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PaddingValues(horizontal = 12.dp, vertical = 8.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                Box(modifier = Modifier.padding(end = 8.dp)) { leadingIcon() }
            }
            Box(modifier = Modifier.weight(1f)) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    textStyle = textStyle,
                    keyboardOptions = keyboardOptions,
                    singleLine = singleLine,
                    maxLines = maxLines,
                    interactionSource = interactionSource,
                    cursorBrush = SolidColor(colors.accent),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.TopStart) {
                            if (value.isEmpty() && placeholder != null) {
                                Text(
                                    text = placeholder,
                                    style = textStyle.copy(color = colors.onGlassSubtle),
                                    maxLines = 1,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
            }
            if (trailingIcon != null) {
                Box(modifier = Modifier.padding(start = 8.dp)) { trailingIcon() }
            }
        }
    }
}
