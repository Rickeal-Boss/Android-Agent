package com.rickeal.agent.core.design

import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.liquid.interactive.InteractiveHighlight
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/**
 * **跟手形变**：Kyant0 `LiquidButton` 的 `layerBlock`，抽出来给
 * Button / Chip / Fab / 分段项共用。
 *
 * 三件事缺一就不是"液态"：
 *  1. 按下时整体放大一点点（[maxScale]）
 *  2. **tanh 阻尼**的跟手位移 —— 拖多远都不会飞出去，松手回弹
 *  3. **各向异性**拉伸 —— 沿拖动方向拉长、垂直方向压扁
 *
 * ⚠️ [scaleX] 与 [scaleY] **故意不相等**。等比缩放（scaleX == scaleY）就是
 * "原生按钮"的手感：上一版 `liquidPress()` 只做等比缩放，静态截图一模一样，
 * 真机一按就露馅 —— 这是本轮必须补上的缺口。
 *
 * @param maxScale 按压/拖动的形变量级。Kyant0 按钮取 4dp（相对控件高度归一化），
 *   BottomTabs / 分段控件取 16dp（面板更大）。
 *
 * ## 为什么是 public
 *
 * 一开始只给 core-design 内部用（Button / Chip / Fab / 分段项），结果 app 模块的
 * NavBar / NavRail 要同样的手感，只能**复制一份** `navPressLayerBlock()`。
 * 两份实现一旦分叉（比如一边加了阻尼系数、另一边没加），就会出现"同一个 App 里
 * 两处按钮手感不一样"的静默 bug —— 所以这里对外开放，app 侧直接调用、删掉副本。
 */
fun pressLayerBlock(
    interactiveHighlight: InteractiveHighlight,
    maxScale: Dp = 4.dp
): GraphicsLayerScope.() -> Unit = {
    val width = size.width.coerceAtLeast(1f)
    val height = size.height.coerceAtLeast(1f)
    val progress = interactiveHighlight.pressProgress
    val scale = 1f + (maxScale.toPx() / height) * progress

    val maxOffset = size.minDimension.coerceAtLeast(1f)
    val offset = interactiveHighlight.offset
    translationX = maxOffset * tanh(0.05f * offset.x / maxOffset)
    translationY = maxOffset * tanh(0.05f * offset.y / maxOffset)

    val maxDragScale = maxScale.toPx() / height
    val offsetAngle = atan2(offset.y, offset.x)
    scaleX = scale +
        maxDragScale * abs(cos(offsetAngle) * offset.x / size.maxDimension) *
        (width / height).coerceAtMost(1f)
    scaleY = scale +
        maxDragScale * abs(sin(offsetAngle) * offset.y / size.maxDimension) *
        (height / width).coerceAtMost(1f)
}
