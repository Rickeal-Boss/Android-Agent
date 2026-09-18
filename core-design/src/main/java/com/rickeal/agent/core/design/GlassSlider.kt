package com.rickeal.agent.core.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 参数滑块。轨道走强调色，容器材质由外层决定（通常放在 GlassCard 里）。 */
@Composable
fun GlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    valueText: String? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val colors = LocalGlassColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        if (label != null || valueText != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (label != null) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (enabled) colors.onGlass else colors.onGlassSubtle,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (valueText != null) {
                    Text(
                        text = valueText,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (enabled) colors.onGlassMuted else colors.onGlassSubtle,
                    )
                }
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
            colors = SliderDefaults.colors(
                thumbColor = if (enabled) colors.accent else colors.onGlassSubtle,
                activeTrackColor = if (enabled) colors.accent else colors.onGlassSubtle,
                inactiveTrackColor = colors.accentMuted,
            ),
        )
    }
}
