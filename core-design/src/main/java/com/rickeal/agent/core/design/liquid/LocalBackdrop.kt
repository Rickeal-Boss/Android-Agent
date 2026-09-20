package com.rickeal.agent.core.design.liquid

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density

/**
 * 全局背景源下发点。由 `GlassScaffold` 在最外层通过
 * `CompositionLocalProvider(LocalBackdrop provides rememberLayerBackdrop())` 提供，
 * 所有玻璃节点默认从它取背景。
 *
 * 默认是 [EmptyBackdrop] —— 没有可用背景源时，玻璃退化为
 * 「纯玻璃底色 + 描边 + 高光」，不会崩溃也不会空窗。
 */
val LocalBackdrop = staticCompositionLocalOf<Backdrop> { EmptyBackdrop }

/**
 * 空背景源：什么都不画。
 *
 * 用于 Dialog / Snackbar 等**独立窗口** —— 它们拿不到主窗口的壁纸层，
 * 此时玻璃只保留底色与高光（观感降级，但不崩）。
 */
object EmptyBackdrop : Backdrop {

    override val isCoordinatesDependent: Boolean = false

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        // 故意空：没有可用背景源时，玻璃走「纯玻璃」分支
    }
}
