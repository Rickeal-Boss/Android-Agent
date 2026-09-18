package com.rickeal.agent.core.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/** 玻璃对话框。THICK 材质 + radiusXl，浮在壁纸之上。 */
@Composable
fun GlassDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    confirmLabel: String? = null,
    onConfirm: (() -> Unit)? = null,
    dismissLabel: String? = null,
    content: @Composable () -> Unit,
) {
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current
    Dialog(onDismissRequest = onDismissRequest) {
        LiquidGlassSurface(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            material = GlassMaterial.THICK,
            cornerRadius = tokens.radiusXl,
            contentPadding = PaddingValues(20.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (title != null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.onGlass,
                    )
                }
                Column(modifier = Modifier.padding(top = if (title != null) 12.dp else 0.dp)) {
                    content()
                }
                if (confirmLabel != null || dismissLabel != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    ) {
                        if (dismissLabel != null) {
                            GlassButton(
                                text = dismissLabel,
                                onClick = onDismissRequest,
                                material = GlassMaterial.THIN,
                            )
                        }
                        if (confirmLabel != null && onConfirm != null) {
                            GlassButton(text = confirmLabel, onClick = onConfirm)
                        }
                    }
                }
            }
        }
    }
}
