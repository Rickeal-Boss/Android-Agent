package com.rickeal.agent.core.design.liquid.internal

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toIntSize

/**
 * 在 DrawScope 上把内容录制到 GraphicsLayer，并把图层的 density 设为 [density]。
 *
 * 为什么需要显式传 density：
 *  `layer.record()` 内部会新建一个 DrawScope，它的 density 默认取自当前 canvas，
 *  与节点实际的 density 可能不一致（尤其有缩放/字体缩放时），
 *  会导致录制出来的背景尺寸不对。这里强制用节点的 density 覆盖。
 *
 * 注：Kyant0 原版用 Kotlin 上下文接收者 `context(node: DelegatableNode)`
 *  （需要 `-Xcontext-receivers` 编译器开关）。本项目改为普通参数传递，
 *  避免依赖编译器实验开关 —— 这是本移植的刻意偏离。
 *
 * 端口自 Kyant0 backdrop 库（Apache-2.0）。
 */
internal fun DrawScope.recordLayer(
    density: Density,
    layer: GraphicsLayer,
    size: IntSize = this.size.toIntSize(),
    block: DrawScope.() -> Unit
) {
    layer.record(size) {
        val prevDensity = drawContext.density
        drawContext.density = density
        try {
            this.block()
        } finally {
            drawContext.density = prevDensity
        }
    }
}
