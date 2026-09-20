package com.rickeal.agent.core.design
import androidx.compose.foundation.background

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.liquid.LocalBackdrop
import com.rickeal.agent.core.design.liquid.backdrops.layerBackdrop
import com.rickeal.agent.core.design.liquid.backdrops.rememberLayerBackdrop
import kotlin.math.max

/**
 * 玻璃骨架。壁纸铺底 → 顶栏 → 内容 → 底栏 → FAB / Snackbar 浮层。
 * 刻意不用 material3 的 Scaffold：我们需要壁纸贯穿整个层级，且 inset 由调用方决定。
 *
 * 壁纸那一层同时被 [glassBackdropSource] 录制进一个 GraphicsLayer，
 * 通过 `LocalGlassBackdropState` 下发 —— 这是所有玻璃节点「真实背景模糊」的来源。
 */
@Composable
fun GlassScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    wallpaper: @Composable () -> Unit = { GlassWallpaper(modifier = Modifier.fillMaxSize()) },
    contentWindowInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
    content: @Composable (PaddingValues) -> Unit,
) {
    val backdropState = rememberGlassBackdropState()
    // 新液态玻璃引擎的背景源：壁纸被录进 LayerBackdrop 的 GraphicsLayer，
    // 所有 drawBackdrop 节点（即 liquidGlass）从这里取背景做模糊/折射。
    // 必须与 LocalGlassBackdropState 一起下发，否则新引擎的玻璃拿不到壁纸，
    // 会退化成「无背景的纯色半透明面板」。
    val layerBackdrop = rememberLayerBackdrop()
    CompositionLocalProvider(
        LocalGlassBackdropState provides backdropState,
        LocalBackdrop provides layerBackdrop,
    ) {
        Box(modifier = modifier.fillMaxSize()) {
            // 壁纸层：内容录制进背景图层，供玻璃节点取用（本身观感不变）。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(layerBackdrop),
            ) {
                wallpaper()
            }
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
                    // 三键导航下导航栏高 48dp，只靠 20.dp 的 bottom padding 会让 FAB
                    // 约 28dp 落进导航栏区域：视觉上被压住，点击也容易被导航栏吃掉。
                    // navigationBarsPadding() 必须在 .padding(...) 之前 —— 先让出系统
                    // 导航区，再在剩余空间里加设计给的 20dp 边距。
                    .navigationBarsPadding()
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
    // 光斑 alpha 从 0.40/0.52 提到 0.62/0.78。
    // 原因：折射与色散是"移动/分离背景像素"，背景本身若是一片柔和浅色渐变，
    // 移动了也看不出来 —— 这是液态玻璃效果出不来最容易被忽略的前提。
    // 提高光斑浓度与饱和度，让背景真的有"内容"可供折射。
    val blobAlpha = if (colors.isDark) 0.78f else 0.62f
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
                        // 半径系数 0.62 → 0.46：光斑收紧、边界更清晰，
                        // 于是玻璃边缘压过去的折射/色散能吃到明显的色相变化。
                        radius = blob.radiusFraction * max(size.width, size.height) * 0.46f,
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
