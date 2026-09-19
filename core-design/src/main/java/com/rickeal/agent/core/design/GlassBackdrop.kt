package com.rickeal.agent.core.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize

/**
 * 背景光斑场中的一个光斑。归一化坐标（0~1）。
 *
 * 这是「壁纸长什么样」的声明式描述（见 [GlassWallpaper]），**不是**玻璃的模糊来源。
 * 玻璃的真实背景模糊走 [GlassBackdropState] / [glassBackdropSource] / [glassBackdropBlur]。
 */
@Immutable
data class GlassBlob(
    val x: Float,
    val y: Float,
    val radiusFraction: Float,
    val color: Color,
)

/**
 * 壁纸光斑场：`GlassScaffold` 铺底时按屏幕尺寸缩放绘制。
 * 玻璃节点通过 [glassBackdropSource] 把这块壁纸录制成图层，再做真实模糊。
 */
@Immutable
data class GlassBackdrop(
    val blobs: List<GlassBlob> = GlassWallpaperDefaults.blobs,
)

object GlassWallpaperDefaults {
    val blobs: List<GlassBlob> = listOf(
        GlassBlob(0.18f, 0.12f, 0.55f, Color(0xFF6E8BFF)),
        GlassBlob(0.82f, 0.28f, 0.48f, Color(0xFFB87BFF)),
        GlassBlob(0.32f, 0.78f, 0.62f, Color(0xFF5AD6C8)),
        GlassBlob(0.72f, 0.86f, 0.44f, Color(0xFFFF9BC2)),
    )
}

/* ------------------------------------------------------------------ 真实背景模糊 */

/**
 * 背景录制层的共享状态。由 [glassBackdropSource]（壁纸层）写入，由 [glassBackdropBlur]（玻璃节点）读取。
 *
 * 只持有「录制层 + 背景源在根坐标系里的位置/尺寸」，本身不做任何绘制。
 */
@Stable
class GlassBackdropState internal constructor(
    /** 背景源内容被录制进的图层。 */
    internal val layer: GraphicsLayer,
) {
    /** 背景源（壁纸）在根坐标系中的位置。 */
    var positionInRoot: Offset by mutableStateOf(Offset.Zero)
        internal set

    /** 背景源的像素尺寸；尚未完成布局时为 0。 */
    var size: IntSize by mutableStateOf(IntSize.Zero)
        internal set

    /** 背景源是否已经完成过一次布局。 */
    val isReady: Boolean
        get() = size.width > 0 && size.height > 0
}

/**
 * 创建并 remember 一个背景录制状态。必须在背景源与玻璃节点的共同祖先上调用一次
 * （见 `GlassScaffold`），再通过 `LocalGlassBackdropState` 下发。
 *
 * 图层由 `rememberGraphicsLayer()` 缓存，重组不会新建图层。
 */
@Composable
fun rememberGlassBackdropState(): GlassBackdropState {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdropState(layer) }
}

/**
 * 【背景源】把本节点绘制的内容录制进 [state] 的图层，供玻璃节点做真实背景模糊。
 *
 * 用法：贴在壁纸上（`GlassScaffold`），`wallpaper()` 的尺寸就是模糊可采样的范围。
 * 录制发生在绘制阶段，和玻璃节点处于同一帧 —— 玻璃绘制时图层已就绪。
 */
fun Modifier.glassBackdropSource(state: GlassBackdropState): Modifier = this
    .onGloballyPositioned { coordinates ->
        val position = coordinates.positionInRoot()
        if (state.positionInRoot != position) state.positionInRoot = position
        val sizePx = coordinates.size
        if (state.size != sizePx) state.size = sizePx
    }
    .drawWithContent {
        val widthPx = size.width.toInt()
        val heightPx = size.height.toInt()
        if (widthPx > 0 && heightPx > 0) {
            state.layer.record(
                density = this,
                layoutDirection = layoutDirection,
                size = IntSize(widthPx, heightPx),
            ) {
                this@drawWithContent.drawContent()
            }
            // 录完立刻按原样画回去，背景源本身的观感不变。
            drawLayer(state.layer)
        } else {
            drawContent()
        }
    }

/**
 * 【玻璃的模糊背景节点】把 [state] 里录制的背景，按本节点相对背景源的位置偏移后模糊绘制。
 *
 * 关键约束：
 *  - 必须 **先于** `drawContent()` 生效（本 Modifier 内部已保证），这样玻璃上的内容不会被模糊；
 *  - 必须应用在节点 `clip(shape)` **之内**，圆角裁剪由外层 clip 负责，本 Modifier 不再建形状；
 *  - 模糊半径静态（`remember(radiusPx)`），不参与动画 —— 逐帧改半径会逐帧重建 RenderEffect。
 *
 * 覆盖范围就是本节点的尺寸（不会全屏建层），离屏开销与玻璃面积成正比。
 */
@Composable
fun Modifier.glassBackdropBlur(
    state: GlassBackdropState,
    blurRadius: Dp,
): Modifier {
    val radiusPx = with(LocalDensity.current) { blurRadius.toPx() }
    // 玻璃自己的模糊层：remember 缓存，重组不新建。
    val blurLayer = rememberGraphicsLayer()
    val blurEffect = remember(radiusPx) { BlurEffect(radiusPx, radiusPx, TileMode.Clamp) }
    // 半径变化时写一次 renderEffect（SideEffect 只在重组后跑，不会逐帧写）。
    SideEffect { blurLayer.renderEffect = blurEffect }

    // 本节点在根坐标系里的位置。偏移量在绘制期用「本节点位置 - 背景源位置」实时算，
    // 这样即使背景源的位置比本节点晚一步确定，绘制时读到的也是最新值。
    var glassPositionInRoot by remember { mutableStateOf(Offset.Zero) }
    return this
        .onGloballyPositioned { coordinates ->
            val position = coordinates.positionInRoot()
            if (glassPositionInRoot != position) glassPositionInRoot = position
        }
        .drawWithContent {
            val widthPx = size.width.toInt()
            val heightPx = size.height.toInt()
            if (widthPx > 0 && heightPx > 0 && state.isReady) {
                val delta = Offset(
                    glassPositionInRoot.x - state.positionInRoot.x,
                    glassPositionInRoot.y - state.positionInRoot.y,
                )
                blurLayer.record(
                    density = this,
                    layoutDirection = layoutDirection,
                    size = IntSize(widthPx, heightPx),
                ) {
                    // 把背景源平移过来，让「本节点正下方的那一块」落在 (0,0)。
                    withTransform({ translate(-delta.x, -delta.y) }) {
                        drawLayer(state.layer)
                    }
                }
                // 超出本节点范围的部分由图层自身边界裁掉，圆角由外层 clip 裁掉。
                drawLayer(blurLayer)
            }
            drawContent()
        }
}
