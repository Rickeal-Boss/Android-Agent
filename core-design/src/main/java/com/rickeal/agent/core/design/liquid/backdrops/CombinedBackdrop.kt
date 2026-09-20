package com.rickeal.agent.core.design.liquid.backdrops

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import com.rickeal.agent.core.design.liquid.Backdrop

/**
 * 把多个 [Backdrop] 叠加（先画 backdrop1，再画 backdrop2，依此类推）。
 *
 * 用途：
 *  - LiquidToggle 的 thumb：背景 = 「整屏 LayerBackdrop」 + 「当前 toggle 轨道」组合。
 *  - LiquidBottomTabs 的选中指示器：背景 = 整屏 LayerBackdrop + 父容器。
 *
 * 端口自 Kyant0 backdrop 库（Apache-2.0）。
 */
@Composable
fun rememberCombinedBackdrop(
    backdrop1: Backdrop,
    backdrop2: Backdrop
): Backdrop = remember(backdrop1, backdrop2) {
    Combined2Backdrops(backdrop1, backdrop2)
}

@Composable
fun rememberCombinedBackdrop(
    backdrop1: Backdrop,
    backdrop2: Backdrop,
    backdrop3: Backdrop
): Backdrop = remember(backdrop1, backdrop2, backdrop3) {
    Combined3Backdrops(backdrop1, backdrop2, backdrop3)
}

@Composable
fun rememberCombinedBackdrop(vararg backdrops: Backdrop): Backdrop =
    remember(*backdrops) {
        CombinedVarargBackdrops(*backdrops)
    }

private class Combined2Backdrops(
    val backdrop1: Backdrop,
    val backdrop2: Backdrop
) : Backdrop {
    override val isCoordinatesDependent: Boolean =
        backdrop1.isCoordinatesDependent || backdrop2.isCoordinatesDependent

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        with(backdrop1) { drawBackdrop(density, coordinates, layerBlock) }
        with(backdrop2) { drawBackdrop(density, coordinates, layerBlock) }
    }
}

private class Combined3Backdrops(
    val backdrop1: Backdrop,
    val backdrop2: Backdrop,
    val backdrop3: Backdrop
) : Backdrop {
    override val isCoordinatesDependent: Boolean =
        backdrop1.isCoordinatesDependent ||
            backdrop2.isCoordinatesDependent ||
            backdrop3.isCoordinatesDependent

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        with(backdrop1) { drawBackdrop(density, coordinates, layerBlock) }
        with(backdrop2) { drawBackdrop(density, coordinates, layerBlock) }
        with(backdrop3) { drawBackdrop(density, coordinates, layerBlock) }
    }
}

private class CombinedVarargBackdrops(
    vararg val backdrops: Backdrop
) : Backdrop {
    override val isCoordinatesDependent: Boolean =
        backdrops.any { it.isCoordinatesDependent }

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        backdrops.forEach { backdrop ->
            with(backdrop) { drawBackdrop(density, coordinates, layerBlock) }
        }
    }
}
