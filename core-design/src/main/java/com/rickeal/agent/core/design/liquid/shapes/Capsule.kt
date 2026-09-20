package com.rickeal.agent.core.design.liquid.shapes

import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/*
   Copyright 2025 Kyant

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

/**
 * **胶囊形状**：圆角半径 = `min(width, height) / 2`，两端是正半圆。
 *
 * Kyant0 的所有交互组件（Button / Slider / Toggle / BottomTabs）一律用胶囊 ——
 * 这是 iOS Liquid Glass 的标志性轮廓，缺了它再怎么调折射都"不像"。
 *
 * ## ⚠️ 为什么不能用 `RoundedCornerShape(percent = 50)`
 *
 * `CornerSize(percent)` 是**按所在角的两条边各自取百分比**：
 *  - 圆角 X 半径 = width * percent
 *  - 圆角 Y 半径 = height * percent
 * 在 64×28 这种非正方形容器上会得到 32×14 的**半椭圆角**（两端被压扁的跑道形），
 * 不是胶囊。必须显式取 `min(w, h) / 2` 才能让两端是**正半圆**。
 *
 * ## ⚠️ 与折射（lens）的关系
 *
 * `effects.lens()` 需要从形状里解析四角半径。它只认 `CornerBasedShape`，
 * 对 [Capsule] 有专门分支（返回 `min(w,h)/2`）——见
 * `effects/Lens.kt` 的 `cornerRadii`。
 * 如果这里换成任意自定义 `Shape`，lens 会因为取不到角半径而**静默跳过**，
 * 玻璃直接退回"纯模糊"，没有任何报错。改这个文件时务必同步 Lens.kt。
 *
 * 对齐 Kyant0 `com.kyant.shapes.Capsule`（Apache-2.0）。
 */
object Capsule : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val radius = capsuleRadius(size)
        return Outline.Rounded(
            RoundRect(
                left = 0f,
                top = 0f,
                right = size.width,
                bottom = size.height,
                radiusX = radius,
                radiusY = radius
            )
        )
    }

    override fun toString(): String = "Capsule"
}

/**
 * 胶囊半径 = 短边的一半。
 *
 * 单独抽出来：[Capsule] 自己和 `effects/Lens.kt` 的角半径解析都要用，保证两处算法一致。
 */
fun capsuleRadius(size: Size): Float {
    val shortSide = minOf(size.width, size.height)
    return if (shortSide <= 0f) 0f else shortSide / 2f
}
