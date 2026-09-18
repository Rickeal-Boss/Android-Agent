package com.rickeal.agent.core.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.layout.matchParentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * 玻璃骨架。壁纸铺底 → 顶栏 → 内容 → 底栏 → FAB / Snackbar 浮层。
 * 刻意不用 material3 的 Scaffold：我们需要壁纸贯穿整个层级，且 inset 由调用方决定。
 */
@Composable
fun GlassScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    wallpaper: @Composable () -> Unit = { GlassWallpaper(modifier = Modifier.matchParentSize()) },
    contentWindowInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
    content: @Composable (PaddingValues) -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        wallpaper()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(contentWindowInsets),
        ) {
            topBar()
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                content(PaddingValues(0.dp))
            }
            bottomBar()
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 20.dp)
                .windowInsetsPadding(contentWindowInsets),
        ) {
            floatingActionButton()
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 104.dp),
        ) {
            snackbarHost()
        }
    }
}

/**
 * 程序化壁纸：三色渐变 + 光斑场。必须铺满 Scaffold 底层。
 * 这就是「背景内容联动」的源头：玻璃里的折射光斑与这里是同一组坐标。
 */
@Composable
fun GlassWallpaper(
    modifier: Modifier = Modifier,
    colors: GlassColorScheme = LocalGlassColors.current,
    backdrop: GlassBackdrop = LocalGlassBackdrop.current,
    intensity: Float = LocalGlassConfig.current.intensity,
) {
    val blobAlpha = if (colors.isDark) 0.52f else 0.40f
    Box(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    colors = listOf(colors.wallpaperTop, colors.wallpaperMid, colors.wallpaperBottom),
                ),
            )
            .drawWithCache {
                val blobs = backdrop.blobs.map { blob ->
                    WallpaperBlob(
                        center = Offset(blob.x * size.width, blob.y * size.height),
                        radius = blob.radiusFraction * max(size.width, size.height) * 0.62f,
                        brush = Brush.radialGradient(
                            colors = listOf(
                                blob.color.copy(alpha = (blobAlpha * intensity).coerceIn(0f, 1f)),
                                Color.Transparent,
                            ),
                        ),
                    )
                }
                onDrawBehind {
                    for (blob in blobs) {
                        drawCircle(brush = blob.brush, center = blob.center, radius = blob.radius)
                    }
                }
            },
    )
}

private class WallpaperBlob(
    val center: Offset,
    val radius: Float,
    val brush: Brush,
)
