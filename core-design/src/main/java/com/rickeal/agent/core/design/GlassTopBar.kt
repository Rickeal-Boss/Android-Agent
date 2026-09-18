package com.rickeal.agent.core.design
import androidx.compose.foundation.layout.weight

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 悬浮胶囊式玻璃顶栏（iOS 27 风格：栏是"浮"在内容上的一块玻璃，不是一条实色横条）。
 *
 * @param scrollFraction 0f=完全展开（超薄材质），1f=完全折叠（加厚材质 + 分隔线）
 */
@Composable
fun GlassTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    scrollFraction: Float = 0f,
) {
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current
    val collapsed = scrollFraction.coerceIn(0f, 1f) > 0.5f
    val material = if (collapsed) GlassMaterial.THIN else GlassMaterial.ULTRA_THIN

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .heightIn(min = tokens.topBarHeight)
                .liquidGlass(material = material, cornerRadius = tokens.radiusFull)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (navigationIcon != null) {
                Box(modifier = Modifier.padding(end = 4.dp)) { navigationIcon() }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onGlass,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onGlassSubtle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (actions != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
        if (collapsed) {
            GlassDivider(modifier = Modifier.padding(horizontal = 24.dp))
        }
    }
}

/** 底部玻璃栏（导航 / 输入栏容器）。 */
@Composable
fun GlassBottomBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val tokens = LocalGlassTokens.current
    Row(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .heightIn(min = tokens.bottomBarHeight)
            .liquidGlass(material = GlassMaterial.THICK, cornerRadius = tokens.radiusXl)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
