package com.rickeal.agent.core.design.liquid.backdrops

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.GraphicsLayerScope
import com.rickeal.agent.core.design.liquid.Backdrop

/**
 * CanvasBackdrop：让调用方在 Canvas 上任意绘制，作为玻璃的背景源。
 *
 * 用途：在背景层"加东西"（如动态光斑、动画图标），但不参与实际壁纸渲染。
 *
 * 端口自 Kyant0 backdrop 库（Apache-2.0）。
 */
@Composable
fun rememberCanvasBackdrop(
    onDraw: DrawScope.() -> Unit
): CanvasBackdrop = remember(onDraw) { CanvasBackdrop(onDraw) }

class CanvasBackdrop internal constructor(
    val onDraw: DrawScope.() -> Unit
) : Backdrop {

    override val isCoordinatesDependent: Boolean = false

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        onDraw()
    }
}

/**
 * 便捷 Composable：把 CanvasBackdrop 渲染到屏幕上（如果不需要挂在 Modifier 链上）。
 */
@Composable
fun CanvasBackdropCanvas(backdrop: CanvasBackdrop) {
    Canvas(modifier = androidx.compose.ui.Modifier) {
        with(backdrop) { onDraw() }
    }
}
