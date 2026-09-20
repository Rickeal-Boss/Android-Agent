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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.rickeal.agent.core.design.liquid.EmptyBackdrop
import com.rickeal.agent.core.design.liquid.LocalBackdrop

/**
 * 玻璃对话框。THICK 材质 + radiusXl，浮在壁纸之上。
 *
 * Dialog 跑在独立窗口里，主窗口录制的背景层在这里既对不齐也用不上，
 * 因此显式关掉背景模糊（提供 null 背景源），退化为纯玻璃。
 */
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
        // Dialog 在独立窗口，主窗口的 LayerBackdrop 在这里既对不齐也用不上，
        // 显式降级为 EmptyBackdrop（纯玻璃：只有底色 + 高光，不采样背景）。
        // 旧的 LocalGlassBackdropState 一并置 null，保持两代引擎行为一致。
        CompositionLocalProvider(
            LocalGlassBackdropState provides null,
            LocalBackdrop provides EmptyBackdrop,
        ) {
            LiquidGlassSurface(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                material = GlassMaterial.THICK,
                // radiusXl = 36dp：对话框是最"重"的玻璃，圆角要给足才有厚度感。
                cornerRadius = tokens.radiusXl,
                // 关色散：对话框是全屏级大面积，色散 7 次采样扛不住。折射仍开。
                dispersion = false,
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
}
