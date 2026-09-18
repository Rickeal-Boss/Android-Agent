package com.rickeal.agent.core.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

/**
 * 壁纸：三色竖向渐变 + 光斑。所有玻璃都透出它，因此它是整个视觉体系的「底色」。
 */
@Composable
fun GlassWallpaper(
    modifier: Modifier = Modifier,
    colors: GlassColorScheme = LocalGlassColors.current,
    backdrop: GlassBackdrop = LocalGlassBackdrop.current,
    intensity: Float = 1f,
) {
    Box(
        modifier = modifier.drawWithCache {
            onDrawBehind {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(colors.wallpaperTop, colors.wallpaperMid, colors.wallpaperBottom),
                        startY = 0f,
                        endY = if (size.height > 0f) size.height else 1f,
                    ),
                )
                drawSoftBlobs(backdrop)
                if (intensity < 1f) {
                    drawRect(color = colors.wallpaperBottom, alpha = (1f - intensity) * 0.15f)
                }
            }
        },
    )
}

/**
 * 玻璃脚手架：壁纸 + 顶栏 + 内容 + 底栏 + 悬浮按钮。
 * 不使用 Scaffold 是为了完全控制玻璃材质的层叠顺序（顶栏要浮在内容之上并半透明）。
 */
@Composable
fun GlassScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    wallpaper: @Composable () -> Unit = { GlassWallpaper(modifier = Modifier.fillMaxSize()) },
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable (PaddingValues) -> Unit,
) {
    val tokens = LocalGlassTokens.current
    Box(modifier = modifier.fillMaxSize()) {
        wallpaper()
        Column(modifier = Modifier.fillMaxSize()) {
            topBar()
            Box(modifier = Modifier.weight(1f, fill = true)) {
                content(contentPadding)
            }
            bottomBar()
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = tokens.paddingLg, bottom = tokens.bottomBarHeight + tokens.paddingMd),
        ) {
            floatingActionButton()
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = tokens.bottomBarHeight + tokens.paddingMd),
        ) {
            snackbarHost()
        }
    }
}

/**
 * 玻璃顶栏。滚动时材质自动加厚（[scrollFraction] 越大越厚重），这是 iOS 27 的标志性行为。
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
    val tokens = LocalGlassTokens.current
    val colors = LocalGlassColors.current
    val material = when {
        scrollFraction > 0.66f -> GlassMaterial.THICK
        scrollFraction > 0.25f -> GlassMaterial.THIN
        else -> GlassMaterial.ULTRA_THIN
    }
    LiquidGlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.paddingSm, vertical = tokens.paddingXs),
        material = material,
        cornerRadius = tokens.radiusLg,
        contentPadding = PaddingValues(horizontal = tokens.paddingMd, vertical = tokens.paddingSm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(tokens.topBarHeight - tokens.paddingMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (navigationIcon != null) {
                navigationIcon()
                Spacer(modifier = Modifier.width(tokens.gapSm))
            }
            Column(modifier = Modifier.weight(1f, fill = true)) {
                Text(
                    text = title,
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    color = colors.onGlass,
                    maxLines = 1,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = colors.onGlassMuted,
                        maxLines = 1,
                    )
                }
            }
            if (actions != null) {
                Row(content = actions)
            }
        }
    }
}

/** 玻璃底栏（导航 / 输入区）。 */
@Composable
fun GlassBottomBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val tokens = LocalGlassTokens.current
    LiquidGlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.paddingSm, vertical = tokens.paddingXs),
        material = GlassMaterial.REGULAR,
        cornerRadius = tokens.radiusXl,
        contentPadding = PaddingValues(horizontal = tokens.paddingMd, vertical = tokens.paddingSm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(tokens.bottomBarHeight - tokens.paddingLg),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
