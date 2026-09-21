package com.rickeal.agent.core.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.liquid.interactive.InteractiveHighlight

/**
 * 悬浮玻璃按钮：胶囊 + 跟手形变。
 *
 * 与 [GlassButton] 同一套（见 [pressLayerBlock]）—— FAB 是 App 里最常被按的控件，
 * "按下去会跟着手指形变"这条在这里最容易被感知到。
 *
 * expanded=true 时展开成胶囊并露出 label。
 */
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
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) { InteractiveHighlight(animationScope) }
    val showLabel = expanded && !label.isNullOrBlank()

    Row(
        modifier = modifier
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .liquidGlass(
                material = material,
                capsule = true,
                blurRadius = 2.dp,
                refractionHeight = 12.dp,
                refractionAmount = 24.dp,
                dispersion = false,
                pressProgress = { interactiveHighlight.pressProgress },
                layerBlock = pressLayerBlock(
                    interactiveHighlight = interactiveHighlight,
                    maxScale = 4.dp
                ),
            )
            .then(interactiveHighlight.modifier)
            .then(interactiveHighlight.gestureModifier)
            .padding(
                horizontal = if (showLabel) 10.dp else 0.dp,
                vertical = 0.dp,
            ),
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
