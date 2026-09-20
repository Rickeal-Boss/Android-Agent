package com.rickeal.agent.core.design.liquid

import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density

/**
 * 背景源接口：玻璃节点据此取"自己背后画了什么"，再做真实模糊 / 折射。
 *
 * 关键设计（与「只模糊自己内容」的 `Modifier.blur` 的本质区别）：
 *  - `Modifier.blur` 只模糊**节点自身**绘制的内容；
 *  - 本接口让节点拿到**父级/背景层**已绘制的内容（通常是壁纸），
 *    再把它平移到本节点正下方后模糊 / 折射 —— 这才是"毛玻璃"的正确语义。
 *
 * 端口自 Kyant0 backdrop 库（Apache-2.0，见 [platform.LiquidGlassCapabilities]）。
 */
interface Backdrop {

    /**
     * 背景是否依赖布局坐标。
     * - `true`（如 [backdrops.LayerBackdrop]）：需要 [LayoutCoordinates] 做位置换算，
     *   DrawBackdropNode 会在 `onGloballyPositioned` 里记录坐标。
     * - `false`（如 [backdrops.CanvasBackdrop]）：坐标无关，绘制时不需要坐标。
     */
    val isCoordinatesDependent: Boolean

    /**
     * 把背景画进当前 DrawScope。
     *
     * @param density 密度（用于 dp → px 与 shader uniform）
     * @param coordinates 本节点的布局坐标（仅当 [isCoordinatesDependent] 为 true 时非空）
     * @param layerBlock 玻璃节点自身的图层变换（scale/rotation/translation），
     *                   背景源需要**反向应用**它才能保证背景与节点对齐
     */
    fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)? = null
    )
}
