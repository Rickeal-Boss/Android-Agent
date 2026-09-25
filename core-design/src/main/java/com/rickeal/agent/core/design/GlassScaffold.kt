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
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.liquid.LocalBackdrop
import com.rickeal.agent.core.design.liquid.backdrops.layerBackdrop
import com.rickeal.agent.core.design.liquid.backdrops.rememberLayerBackdrop
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 自定义壁纸图层（Wave 9 需求 3b）：app 层（LiquidAgentApp）按 DataStore 路径解码后
 * 在根组合 provide，7 个业务屏的 [GlassScaffold] 经默认参数取用 —— feature 屏的
 * 签名与调用点零改动。null = 使用程序化壁纸（三色渐变 + 光斑场）。
 *
 * 用 [staticCompositionLocalOf]：换壁纸整个生命周期只发生两三次（选图 / 恢复默认 /
 * 冷启动），「值变化 → 整棵子树重组」的代价可以接受，换来的是读取处零开销。
 * 解码缓存刻意放在 app 根组合的 `remember` 里，不做进程级单例 —— 组合销毁后
 * Bitmap 交给 GC，泄漏面更小。
 */
val LocalWallpaperImage: ProvidableCompositionLocal<ImageBitmap?> =
    staticCompositionLocalOf { null }

/**
 * 玻璃骨架。壁纸铺底 → 顶栏 → 内容 → 底栏 → FAB / Snackbar 浮层。
 * 刻意不用 material3 的 Scaffold：我们需要壁纸贯穿整个层级，且 inset 由调用方决定。
 *
 * 壁纸那一层经 [layerBackdrop] 录制进 LayerBackdrop 的 GraphicsLayer 并通过
 * `LocalBackdrop` 下发 —— 这是所有玻璃节点（`liquidGlass`）「真实背景模糊 /
 * 折射」的唯一来源（旧的玻璃背景录制实现已随死代码清理移除）。
 *
 * [wallpaperImage] 是自定义壁纸的显式覆盖点：默认取 [LocalWallpaperImage]（app 根
 * 组合下发），透传给默认的 [GlassWallpaper]。传 null 强制用程序化壁纸（预览场景）。
 * 注意默认 wallpaper lambda 引用了前置参数 —— Kotlin 允许默认值引用排在它之前的参数，
 * 这正是「参数透传」与「调用点零改动」能同时成立的原因。
 */
@Composable
fun GlassScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    wallpaperImage: ImageBitmap? = LocalWallpaperImage.current,
    wallpaper: @Composable () -> Unit = {
        GlassWallpaper(modifier = Modifier.fillMaxSize(), backgroundImage = wallpaperImage)
    },
    contentWindowInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
    content: @Composable (PaddingValues) -> Unit,
) {
    // 新液态玻璃引擎的背景源：壁纸被录进 LayerBackdrop 的 GraphicsLayer，
    // 所有 drawBackdrop 节点（即 liquidGlass）从这里取背景做模糊/折射。
    // 不下发它玻璃就拿不到壁纸，会退化成「无背景的纯色半透明面板」。
    val layerBackdrop = rememberLayerBackdrop()
    CompositionLocalProvider(
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
 *
 * [backgroundImage] 非空时改为「自定义壁纸」三层叠加（Wave 9 需求 3b）：
 *
 *  1. **照片层**：`drawImage` 铺满整个尺寸（`dstSize` 显式拉伸到画布 —— 不裁剪，
 *     变形交给 ContentScale 语义之外的手工拉伸；导入时已降采样到屏幕最长边，
 *     `FilterQuality.Medium`（双线性 + mipmap）是缩放质量与逐帧开销的平衡点）；
 *  2. **光斑层**：alpha 压到 0.31 —— 照片本身已有丰富内容可供折射，光斑只做
 *     品牌色调的点缀，压在照片上太浓会把用户选的图盖成调色盘；
 *  3. **scrim 层**：壁纸渐变色整体罩一层（深色 0.55 / 浅色 0.35）—— 保证玻璃
 *     节点下面的文字可读性不依赖用户恰好选了张浅色照片，同时让任意照片与
 *     玻璃配色系统保持同一色温。
 *
 * drawImage 是 [androidx.compose.ui.graphics.drawscope.DrawScope] 的**成员函数**
 * （ui-graphics 1.10.3 源码核实），在 DrawWithContent 的接收者作用域内直接可用，
 * 无需 import。
 */
@Composable
fun GlassWallpaper(
    modifier: Modifier = Modifier,
    backgroundImage: ImageBitmap? = null,
    colors: GlassColorScheme = LocalGlassColors.current,
    backdrop: GlassBackdrop = LocalGlassBackdrop.current,
    intensity: Float = LocalGlassConfig.current.intensity,
) {
    // 光斑 alpha 从 0.40/0.52 提到 0.62/0.78。
    // 原因：折射与色散是"移动/分离背景像素"，背景本身若是一片柔和浅色渐变，
    // 移动了也看不出来 —— 这是液态玻璃效果出不来最容易被忽略的前提。
    // 提高光斑浓度与饱和度，让背景真的有"内容"可供折射。
    // 自定义壁纸时压到 0.31：照片已提供折射所需的"内容"，光斑退为点缀（见 KDoc）。
    val blobAlpha = if (backgroundImage != null) {
        0.31f
    } else if (colors.isDark) 0.78f else 0.62f
    Box(
        modifier = modifier
            // 照片层直接铺满画布，底下的渐变底色被完全遮住 —— 不画就是省一次全屏 fill。
            .then(
                if (backgroundImage == null) {
                    Modifier.background(
                        Brush.verticalGradient(
                            colors = listOf(colors.wallpaperTop, colors.wallpaperMid, colors.wallpaperBottom),
                        ),
                    )
                } else {
                    Modifier
                },
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
                // scrim：任意照片 × 玻璃配色之间的"色温调和层"，同时兜底文字可读性。
                val scrim = backgroundImage?.let {
                    val scrimAlpha = if (colors.isDark) 0.55f else 0.35f
                    Brush.verticalGradient(
                        colors = listOf(
                            colors.wallpaperTop.copy(alpha = scrimAlpha),
                            colors.wallpaperMid.copy(alpha = scrimAlpha),
                            colors.wallpaperBottom.copy(alpha = scrimAlpha),
                        ),
                    )
                }
                onDrawBehind {
                    if (backgroundImage != null) {
                        drawImage(
                            image = backgroundImage,
                            dstOffset = IntOffset.Zero,
                            // 拉伸铺满画布（不裁剪）：导入时降采样已按屏幕最长边归一，
                            // 长宽比偏差由 scrim 与玻璃层吸收，裁剪会破坏用户对"整张图"的预期。
                            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                            filterQuality = FilterQuality.Medium,
                        )
                    }
                    for (blob in blobs) {
                        drawCircle(brush = blob.brush, center = blob.center, radius = blob.radius)
                    }
                    if (scrim != null) {
                        drawRect(brush = scrim)
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
