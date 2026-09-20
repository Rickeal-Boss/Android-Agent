package com.rickeal.agent.core.design.liquid.backdrops

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import com.rickeal.agent.core.design.liquid.Backdrop
import com.rickeal.agent.core.design.liquid.internal.InverseLayerScope
import androidx.compose.ui.unit.Density

private val DefaultOnDraw: ContentDrawScope.() -> Unit = { drawContent() }

/**
 * LayerBackdrop：把屏幕某块区域录制到 GraphicsLayer，玻璃节点据此做真实背景模糊/折射。
 *
 * 用法（典型）：
 * ```
 * val backdrop = rememberLayerBackdrop()
 * Box(Modifier.layerBackdrop(backdrop)) { wallpaper() }   // 录制壁纸
 *
 * Box(Modifier.drawBackdrop(backdrop, ...)) { content }  // 消费背景
 * ```
 *
 * 端口自 Kyant0 backdrop 库（Apache-2.0）。
 */
@Composable
fun rememberLayerBackdrop(
    graphicsLayer: GraphicsLayer = rememberGraphicsLayer(),
    onDraw: ContentDrawScope.() -> Unit = DefaultOnDraw
): LayerBackdrop {
    return remember(graphicsLayer, onDraw) {
        LayerBackdrop(graphicsLayer, onDraw)
    }
}

@Stable
class LayerBackdrop internal constructor(
    val graphicsLayer: GraphicsLayer,
    internal val onDraw: ContentDrawScope.() -> Unit
) : Backdrop {

    override val isCoordinatesDependent: Boolean = true

    internal var layerCoordinates: LayoutCoordinates? by mutableStateOf(null)

    private var inverseLayerScope: InverseLayerScope? = null

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        val coordinates = coordinates ?: return
        val layerCoordinates = layerCoordinates ?: return
        withTransform({
            if (layerBlock != null) {
                with(obtainInverseLayerScope()) { inverseTransform(density, layerBlock) }
            }
            val offset =
                try {
                    layerCoordinates.localPositionOf(coordinates)
                } catch (_: Exception) {
                    // 父级有外层 transform 时 localPositionOf 可能抛异常，回退到 positionInWindow 减法。
                    coordinates.positionInWindow() - layerCoordinates.positionInWindow()
                }
            translate(-offset.x, -offset.y)
        }) {
            drawLayer(graphicsLayer)
        }
    }

    private fun obtainInverseLayerScope(): InverseLayerScope {
        return inverseLayerScope?.apply { reset() }
            ?: InverseLayerScope().also { inverseLayerScope = it }
    }
}

/**
 * 兼容旧名：部分旧实现里也用 rememberBackdrop，签名同 rememberLayerBackdrop。
 */
@Composable
fun rememberBackdrop(
    backdrop: Backdrop,
    onDraw: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit
): Backdrop {
    return remember(backdrop, onDraw) {
        object : Backdrop {
            override val isCoordinatesDependent: Boolean = backdrop.isCoordinatesDependent
            override fun DrawScope.drawBackdrop(
                density: Density,
                coordinates: LayoutCoordinates?,
                layerBlock: (GraphicsLayerScope.() -> Unit)?
            ) {
                onDraw {
                    with(backdrop) { drawBackdrop(density, coordinates, layerBlock) }
                }
            }
        }
    }
}
