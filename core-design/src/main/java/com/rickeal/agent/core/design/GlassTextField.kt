package com.rickeal.agent.core.design
import androidx.compose.foundation.layout.weight

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.unit.dp

/** 玻璃输入框。用 BasicTextField + decorationBox，避免 material3 TextField 的实色下划线。 */
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

    LiquidGlassSurface(
        modifier = modifier,
        material = GlassMaterial.ULTRA_THIN,
        cornerRadius = tokens.radiusMd,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
