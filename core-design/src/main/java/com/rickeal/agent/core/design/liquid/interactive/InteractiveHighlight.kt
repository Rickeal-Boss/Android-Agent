package com.rickeal.agent.core.design.liquid.interactive

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import kotlin.math.abs
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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

/** 拖拽时"跟手偏移"的弹簧：偏软，让位移跟手但不生硬。 */
private val DragFollowSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessLow
)

/**
 * 交互高光：把「按压进度」+「跟手拖拽偏移」打包成一个对象，供 `layerBlock` 读。
 *
 * Kyant0 的每个交互组件都在 `layerBlock` 里读它的两个值：
 *  - [pressProgress]：按下 0→1，用来把玻璃"按实"（组件侧再拿它去调 blur / lens）
 *  - [offset]：手指拖动的累计偏移，用 `tanh` 阻尼后做跟手位移 + 各向异性拉伸
 *
 * 两个 Modifier 的分工（**顺序不能调换**）：
 *  - [modifier]：尺寸跟踪 + 触摸点高光。纯视觉。
 *  - [gestureModifier]：`pointerInput` 手势源。
 *
 * ⚠️ 与 `clickable` 组合时，Kyant0 原序是 `.clickable(...)` **在前**、
 * `.then(gestureModifier)` **在后**。调换后 `clickable` 会先吃掉手势，
 * 玻璃的跟手位移就没了（静态截图看不出来，真机一按就露馅）。
 *
 * 对齐 Kyant0 `catalog/utils/InteractiveHighlight`（Apache-2.0）。
 *
 * @param position 高光中心的计算方式。默认跟随手指落点；
 *   需要让高光跟随**别的状态**（例如 BottomTabs 跟随选中项而不是手指）时传入自定义实现。
 */
@Stable
class InteractiveHighlight(
    private val animationScope: CoroutineScope,
    private val position: ((size: Size, offset: Offset) -> Offset)? = null
) {

    private val pressAnimatable = Animatable(0f)
    private val offsetXAnimatable = Animatable(0f)
    private val offsetYAnimatable = Animatable(0f)

    /** 累计的原始拖拽偏移（不受动画影响，抬手后归零）。 */
    private var rawOffsetX = 0f
    private var rawOffsetY = 0f

    private var nodeSize by mutableStateOf(Size.Zero)
    private var touchOffset by mutableStateOf(Offset.Zero)

    /** 按压进度 0~1。 */
    val pressProgress: Float get() = pressAnimatable.value

    /** 阻尼后的拖拽偏移（px）。 */
    val offset: Offset get() = Offset(offsetXAnimatable.value, offsetYAnimatable.value)

    /**
     * 视觉层：跟踪尺寸 + 在触摸点画一圈柔和的镜面高光。
     *
     * 必须放在 `drawBackdrop(...)` **之后**（Kyant0 原序），这样它是玻璃节点的子节点，
     * 高光会被胶囊形状裁剪，不会溢出到玻璃外面。
     */
    val modifier: Modifier = Modifier
        .onSizeChanged { nodeSize = Size(it.width.toFloat(), it.height.toFloat()) }
        .drawWithContent {
            drawContent()
            val progress = pressAnimatable.value
            if (progress > 0.001f) {
                val center = position?.invoke(nodeSize, touchOffset) ?: touchOffset
                val radius = minOf(size.width, size.height) * 0.9f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.16f * progress),
                            Color.Transparent
                        ),
                        center = center,
                        radius = radius
                    ),
                    radius = radius,
                    center = center
                )
            }
        }

    /**
     * 手势层：按下 → 记录按压与落点；拖动 → 累加偏移；抬手 → 归位。
     */
    val gestureModifier: Modifier = Modifier.pointerInput(this) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var previous = down.position
            // 累积位移：是否 consume 按**累积量**判定，不按单帧 delta。
            // 两轴分开记 —— 纵向累积更大时判定为"用户想滚列表"，本手势让位。
            var accumulatedX = 0f
            var accumulatedY = 0f
            touchOffset = down.position
            setPressed(true)
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val current = change.position
                    val dragAmount = current - previous
                    previous = current
                    touchOffset = current
                    if (dragAmount != Offset.Zero) {
                        // ⚠️ 只有**累积**位移越过 touch slop 才 consume，抖 1px 就消费会毁掉点击。
                        //
                        // 本项目所有玻璃控件都是 `clickable` 在外、本手势在内（Kyant0 原序），
                        // 而 Compose 的 ClickableNode 只要看到事件被消费就会取消按压：
                        //   Clickable.kt:940-948  Main  pass —— `it.isConsumed` → Canceled
                        //   Clickable.kt:950-957  Final pass —— 复查一次 → Canceled
                        // 真机点击不可能绝对静止，抖 1px 就消费 → 按压被取消 → onClick 不来
                        // → **所有玻璃按钮 / 分段项 / 页签点了都没反应**。
                        //
                        // 越过 slop 才消费，两条路径就干净分开了：
                        // 点击（含抖动）不消费 → clickable 正常触发；真拖动消费 → 父级滚动抢不走。
                        accumulatedX += abs(dragAmount.x)
                        accumulatedY += abs(dragAmount.y)

                        // 轴向锁定：纵向累积更大 → 判定为"用户想滚列表"，本手势让位。
                        // 既**不消费**（父级 LazyColumn / verticalScroll 才能接管滚动），
                        // 也**不更新跟手位移**（否则页面滚动时按钮还跟着手指跑，很怪）。
                        // pressProgress 仍保持 1 —— 手指确实还按着，该有按压反馈。
                        if (accumulatedY > accumulatedX) {
                            continue
                        }

                        // ⚠️ 用公开的 `touchSlop`。foundation 内部那个
                        // `viewConfiguration.pointerSlop(pointerType)` 是 internal 扩展，
                        // 外部调不到（CI 实测 Unresolved reference）。
                        if (accumulatedX >= viewConfiguration.touchSlop) {
                            change.consume()
                        }
                        // 跟手位移与是否消费无关，照常累加 —— 手感不受影响。
                        rawOffsetX += dragAmount.x
                        rawOffsetY += dragAmount.y
                        animationScope.launch {
                            offsetXAnimatable.animateTo(rawOffsetX, DragFollowSpring)
                            offsetYAnimatable.animateTo(rawOffsetY, DragFollowSpring)
                        }
                    }
                }
            } finally {
                // ⚠️ 归位必须放在 finally 里，不能留在循环体之后。
                // 父级滚动容器抢走手势、或 pointerInput 协程被取消时，
                // awaitPointerEvent() 会抛 CancellationException，
                // 循环体后面的语句根本执行不到 —— pressProgress 会永远卡在 1，
                // 那一行一直显示"按下"高亮，松手也回不来。
                setPressed(false)
                rawOffsetX = 0f
                rawOffsetY = 0f
                animationScope.launch {
                    offsetXAnimatable.animateTo(0f)
                    offsetYAnimatable.animateTo(0f)
                }
            }
        }
    }

    private fun setPressed(pressed: Boolean) {
        animationScope.launch {
            pressAnimatable.animateTo(
                targetValue = if (pressed) 1f else 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
        }
    }
}
