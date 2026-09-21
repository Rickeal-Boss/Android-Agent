package com.rickeal.agent.core.design.liquid.interactive

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
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

/**
 * 带阻尼的拖拽动画：**数值**（滑块进度 / 开关 0~1）+ **按压形变**（缩放）。
 *
 * Kyant0 的 Slider / Toggle / BottomTabs 全部建立在它上面。它同时暴露：
 *  - [value] / [targetValue] / [progress]：给布局用（thumb 位置、轨道填充宽度）
 *  - [pressProgress]：给 `effects {}` 用（按压时模糊减弱、折射增强）
 *  - [scaleX] / [scaleY] / [velocity]：给 `layerBlock` 用（各向异性拉伸 —— 快速拖动时
 *    沿拖动方向被拉长、垂直方向被压扁，这就是"液态"最直观的信号）
 *  - [modifier]：手势入口，挂到需要被拖的节点上
 *
 * **为什么不用 `detectDragGestures`**：它在"按下后没有越过 touch slop 就抬手"时
 * 不保证回调 `onDragStart` / `onDragEnd`，而开关的**纯点击切换**正是走 `onDragStopped`。
 * 这里手写 `awaitEachGesture` 循环，保证按下→抬手一定会走完 onDragStarted/onDragStopped。
 *
 * 对齐 Kyant0 `catalog/utils/DampedDragAnimation`（Apache-2.0，原实现用 `context()`
 * 上下文接收者，本项目按「不开 -Xcontext-receivers」改为普通 lambda 参数）。
 */
@Stable
/**
 * 判定"纵向意图"的阈值：tan(30°) = 0.577。
 *
 * 与 foundation 1.10.3 `DragGestureNode.processAwaitTouchSlop` 的分轴规则对齐
 * （Horizontal: `angle <= 30`；Vertical: `30 < angle <= 90`，角度由
 * `atan2(x = |dx|, y = |dy|)` 得出，从 X 轴量角）。
 *
 * ⚠️ 不要"简化"成 `|dy| > |dx|`（= 45°），否则 30°~45° 斜滑留残余死区。
 */
private const val VERTICAL_INTENT_TAN30 = 0.577f

class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    initialValue: Float,
    private val valueRange: ClosedFloatingPointRange<Float>,
    visibilityThreshold: Float,
    private val initialScale: Float,
    private val pressedScale: Float,
    private val onDragStarted: () -> Unit,
    private val onDragStopped: DampedDragAnimation.() -> Unit,
    private val onDrag: DampedDragAnimation.(value: Float, dragAmount: Offset) -> Unit,
    /**
     * 只有**累积主轴位移**超过它才 `consume()` 事件。
     *
     * ⚠️ 累积的是 `abs(dragAmount.x)`（主轴），**不是**欧氏距离 —— 纵向滑动不会累积，
     * 因此纵向永远不消费，父级 `verticalScroll` 才能正常接管滚动。
     * 当前全仓只有 `GlassSlider` 与 `GlassSwitch` 两个调用点，都是横向控件，主轴即 x；
     * **若将来新增纵向控件（如竖向 slider），这个累加必须参数化**，否则纵向拖动会失效。
     *
     * 默认 0f —— 即"横向一动就消费"，滑块手感与历史行为完全一致。
     *
     * 开关必须传一个正的值（8dp）：它外层挂了 `toggleable`，而真机点击不可能绝对
     * 静止，只要抖 1px 就消费 → `toggleable` 的点击被取消 → **点了没反应**。
     * 过了 slop 才消费，才能把"点击"和"拖动"这两条路径干净地分开。
     */
    private val consumeSlopPx: Float = 0f
) {

    private val valueAnimatable = Animatable(initialValue, visibilityThreshold)
    private val pressAnimatable = Animatable(0f)

    // 刻意不用 `by mutableFloatStateOf(...)` 委托：MutableFloatState 的
    // getValue/setValue 是 androidx.compose.runtime 的扩展运算符，必须显式 import 才生效，
    // 漏了 import 会报 "Type 'MutableFloatState' has no method 'getValue(...)'"（CI 实测踩过）。
    // 直接持有 state 并手写 get/set，少一个隐式依赖。
    private val targetValueState = mutableStateOf(initialValue)

    /** 当前目标值（手指/外部状态想去的地方）。拖拽增量基于它计算，避免累积漂移。 */
    var targetValue: Float
        get() = targetValueState.value
        private set(value) { targetValueState.value = value }

    /** 当前实际值（弹簧跟随 [targetValue]，所以会"慢半拍"——这就是阻尼）。 */
    val value: Float get() = valueAnimatable.value

    /** 当前速度（px/s 量纲的数值速度）。用于各向异性拉伸。 */
    val velocity: Float get() = valueAnimatable.velocity

    /** 按压进度 0~1。 */
    val pressProgress: Float get() = pressAnimatable.value

    /**
     * 本次手势是否已**让位给父级滚动**（判定为纵向意图后 break 退出）。
     *
     * ⚠️ 必须在让位分支里置真，调用方的 `onDragStopped` 要据此**跳过提交**：
     * 让位意味着我们**没有消费**事件，于是外层的 `toggleable` / `clickable` 不会
     * 因"事件被消费"而取消按压 —— 抬手时它仍会走一次 `onValueChange`。
     * 如果此时 `onDragStopped` 也提交（`draggedX≈0` 会落进"纯点击"分支），
     * 就会**提交两次 = 状态翻两次 = 看起来完全没反应**。
     * 这正是 P0「点了没反应」从另一条路复活，务必用这个标志挡住。
     *
     * 典型场景：设置页按在开关上纵向滑（想滚列表）→ 滚完抬手，开关不能突然翻转。
     */
    val yieldedToParent: Boolean get() = yieldedToParentState

    private var yieldedToParentState = false

    /** 归一化进度 0~1（相对 [valueRange]）。轨道填充宽度、thumb 位移都用它。 */
    val progress: Float
        get() {
            val span = valueRange.endInclusive - valueRange.start
            return if (span <= 0f) {
                0f
            } else {
                ((value - valueRange.start) / span).coerceIn(0f, 1f)
            }
        }

    /** 按下时放大的 X 缩放（[initialScale] → [pressedScale]）。 */
    val scaleX: Float get() = initialScale + (pressedScale - initialScale) * pressProgress

    /** 按下时放大的 Y 缩放（[initialScale] → [pressedScale]）。 */
    val scaleY: Float get() = initialScale + (pressedScale - initialScale) * pressProgress

    /**
     * 手势入口。必须挂在**需要被拖的节点**上，且顺序上放在 `drawBackdrop(...)` **之后**
     * （Kyant0 原序）—— 这样它是玻璃节点的子节点，绘制会被玻璃形状裁剪。
     */
    val modifier: Modifier = Modifier.pointerInput(this) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var previous = down.position
            // 累积位移：consume 与否按**累积量**判定，不按单帧 delta。
            // 真机点击必然带亚像素抖动，逐帧判定会把外层的 clickable / toggleable
            // 一并取消掉 —— 表现就是"点了没反应"。
            var accumulatedX = 0f
            var accumulatedY = 0f
            yieldedToParentState = false
            setPressed(true)
            onDragStarted()
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val current = change.position
                    val dragAmount = current - previous
                    previous = current
                    if (dragAmount != Offset.Zero) {
                        // ⚠️ 累加**有符号**的 dx/dy（净位移）。
                        // 不要用 abs / getDistance()：它们恒非负，累加的是"路径长度"，
                        // 来回抖动会单调累加顶过 slop → 误判为拖动 → 误消费。
                        // Compose 自己累加的也是 dragAccumulator += dragAmount（有符号 Offset）。
                        //
                        // 分轴是为了跟父级滚动共存：纵向滑动不该被本手势吃掉，
                        // 否则父级 verticalScroll 的 awaitTouchSlopOrCancellation 会放弃
                        // → **手指按在滑块上时页面滚不动**（13 个滑块全在可滚动容器里）。
                        accumulatedX += dragAmount.x
                        accumulatedY += dragAmount.y

                        // 轴向锁定：判定为纵向意图 → 让位给父级滚动。
                        //
                        // ⚠️ 阈值必须跟 Compose 对齐（同 InteractiveHighlight）：
                        //   |dy| > |dx| * tan30°(0.577)
                        // 写成 |dy| > |dx|（45°）会在 30°~45° 斜滑留残余死区。
                        //
                        // ⚠️ 必须 **break**，不能只"跳过本次 consume/onDrag"（源码依据）：
                        // verticalScroll / LazyColumn 的 startDragImmediately = false，
                        // 父级只在 **Main pass** 消费，而 Main 自下而上 —— 子级永远
                        // 先拿到未消费事件。只跳一次的话，父级开滚后子级仍会在后续帧
                        // 继续走 onDrag，表现为"一边滚页面一边改数值"。
                        // break 后 awaitEachGesture 等抬手才重启，一次解决。
                        // 代价：用户要多滑几 px 才起滚。
                        if (abs(accumulatedY) > abs(accumulatedX) * VERTICAL_INTENT_TAN30) {
                            // 让位给父级滚动。必须置位——见 [yieldedToParent] 的说明：
                            // 不消费意味着外层 toggleable/clickable 不会被取消，
                            // 抬手时它还会提交一次；若 onDragStopped 也提交就是两次。
                            yieldedToParentState = true
                            break
                        }

                        // 横向意图：越过 [consumeSlopPx] 才消费。
                        // 严格大于：consumeSlopPx = 0f 时 `0 > 0` 为假，纯抖动不消费。
                        if (abs(accumulatedX) > consumeSlopPx) {
                            change.consume()
                            onDrag(this@DampedDragAnimation, valueAnimatable.value, dragAmount)
                        }
                    }
                }
            } finally {
                // ⚠️ 与 InteractiveHighlight 同理，这两句必须在 finally 里。
                // 手势被父级抢走 / 协程被取消时，循环体之后的代码不会执行，
                // 结果有两个：pressProgress 永远卡在 1（那一行一直显示按下态）；
                // 更糟的是 onDragStopped 不触发 —— 滑块的 onValueChangeFinished
                // 永远不来，设置页改完温度**不落盘**。
                setPressed(false)
                onDragStopped(this@DampedDragAnimation)
            }
        }
    }

    /**
     * 外部状态变化 → 同步目标值（弹簧跟随）。
     * 拖拽期间每帧都会被调用；`animateTo` 会自动取消上一条未完成的动画，不会打架。
     */
    fun updateValue(value: Float) {
        val coerced = value.coerceIn(valueRange)
        targetValue = coerced
        animationScope.launch {
            valueAnimatable.animateTo(
                targetValue = coerced,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessHigh
                )
            )
        }
    }

    /** 直接把值 animate 过去（点轨道跳转 / 外部选中态变化时用，默认弹簧带一点点回弹）。 */
    fun animateToValue(value: Float) {
        val coerced = value.coerceIn(valueRange)
        targetValue = coerced
        animationScope.launch { valueAnimatable.animateTo(coerced) }
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
