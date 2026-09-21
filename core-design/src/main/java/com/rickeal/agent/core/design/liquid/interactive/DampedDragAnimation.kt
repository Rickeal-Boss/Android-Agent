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

    /**
     * 本次手势结束的原因：`true` = 手指还按着但事件被父级拿走（releasedCancel），
     * `false` = 用户真的抬手了（released）。
     *
     * 两者在 `awaitPointerEvent` 里都表现为 `!change.pressed`，必须靠 `isConsumed`
     * 区分：被消费即 cancel。一律按"正常抬手"处理会让按压缩放错误弹回。
     */
    val endWasCanceled: Boolean get() = releaseCancelState

    private var releaseCancelState = false

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
            // ⚠️ 每轮手势**最开头**就清掉让位标志（在 awaitFirstDown 之前）。
            // awaitEachGesture 的语义是一次手势 = down 到 up，up 之后才进下一轮；
            // 放在这里能保证"上一次让位过"绝不会残留到下一次手势。
            //
            // 反过来，**不要在 finally 里清**：onDragStopped 是在 finally 里执行的，
            // 它必须读到 true 才能跳过提交（否则让位后仍会双重提交，状态翻两次）。
            // 也就是说这个标志的生命周期是"本次手势"，由下一轮的开头负责收尾。
            yieldedToParentState = false
            releaseCancelState = false
            val down = awaitFirstDown(requireUnconsumed = false)
            var previous = down.position
            // 累积位移：consume 与否按**累积量**判定，不按单帧 delta。
            // 真机点击必然带亚像素抖动，逐帧判定会把外层的 clickable / toggleable
            // 一并取消掉 —— 表现就是"点了没反应"。
            var accumulatedX = 0f
            var accumulatedY = 0f
            // 轴向**只判定一次**（latch）。不能用 running 净位移逐帧重判：
            // 来回拖动会让净位移归零、方向来回翻转，手势会在中途莫名其妙让位。
            var axisDecided = false
            // ⚠️ slop 必须在**循环外**取一次快照。
            //
            // 为什么这行是安全的：`awaitFirstDown` / `awaitEachGesture` 的 lambda 与
            // `pointerInput` 的协程在同一个 continuation 里，进入 `awaitEachGesture`
            // 那一刻仍在 composition 内，所以这里读 CompositionLocal 合法。
            //
            // 但**之后就不行了**：`awaitFirstDown` 返回后，当前 continuation 已经离开
            // composition；此后每次 `awaitPointerEvent()` 返回都会跑到下一个
            // `awaitPointerEvent()`，这中间不在 composition 内 —— 那时再读
            // `viewConfiguration` 会抛 IllegalStateException。
            // 所以循环内一律用这里的 `slop`，不许再出现 viewConfiguration。
            val slop = viewConfiguration.touchSlop
            yieldedToParentState = false
            setPressed(true)
            onDragStarted()
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        // ⚠️ `!change.pressed` 有**两种**截然不同的含义，不能一律当抬手：
                        //   released()        —— 用户真的抬手了
                        //   releasedCancel()  —— 手指还按着，但事件被父级消费（我们去滚列表了）
                        // 后者如果按"正常抬手"处理，会让按压缩放弹回、状态机误判。
                        // 判据：被消费 = cancel。
                        releaseCancelState = change.isConsumed
                        break
                    }
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
                        // ⚠️ 方向判定**必须先过幅度门限**，不能在第一帧就判。
                        // 起手那一两帧 accumulatedX/Y 还是亚像素噪声：
                        // dx=0.3px、dy=0.6px → 0.6 > 0.3*0.577 → 立刻 break，
                        // 整个手势作废 → 滑块/开关"有时拖不动"（随机，极难查）。
                        // Compose 也是在越过 slop 那一刻才算 gestureAngle，不是第一帧。
                        val reach = Offset(abs(accumulatedX), abs(accumulatedY)).getDistance()
                        // 轴向**只判定一次**（axisDecided latch）。
                        // 不能用 running 净位移逐帧重判：有符号累加下，来回拖两帧净位移
                        // 就归零，方向会来回翻转 —— 手势中途莫名其妙让位。
                        if (!axisDecided && reach >= slop) {
                            axisDecided = true
                            if (abs(accumulatedY) > abs(accumulatedX) * VERTICAL_INTENT_TAN30) {
                                // 让位给父级滚动。置位后本手势"吞掉"后续事件。
                                yieldedToParentState = true
                            }
                        }

                        // ⚠️ 让位后**不能 break**，要继续留在循环里把这个手势走完。
                        //
                        // 源码（foundation 1.10.3 AwaitPointerEventScope）：
                        //   awaitEachGesture { ... } 的结构是
                        //     while (true) { awaitFirstDown(); ...; awaitAllEventsUp() }
                        //   且 awaitFirstDown(requireUnconsumed = false) 会立刻接受
                        //   **当前仍按着**的手指。所以这里一旦 break，
                        //   下一轮 awaitFirstDown 会马上拿到同一根手指，
                        //   awaitPointerEvent() 直接返回 up → !change.pressed → 立刻 break
                        //   → 又走一次 onDragStopped（didDrag 仍为 true → 落盘）
                        //   → 反复直到抬手，落盘 N 次。
                        //
                        // 正确做法：置位后既不消费也不 onDrag，但**继续等 up**，
                        // 让 onDragStopped 只在真正结束时被调用一次。
                        if (!yieldedToParentState) {
                            // 横向：越过 [consumeSlopPx] 才消费。
                            // 严格大于：consumeSlopPx = 0f 时 `0 > 0` 为假，纯抖动不消费。
                            if (abs(accumulatedX) > consumeSlopPx) {
                                change.consume()
                            }
                            // ⚠️ onDrag **必须每帧无条件回调**，不能塞进上面的门控里。
                            // 累加是有符号净位移，来回拖两帧它就归零；若 onDrag 受
                            // `abs(accumulatedX) > consumeSlopPx` 门控，来回拖到中途
                            // onDrag 会彻底不再触发 → 滑块值卡住、开关拖到一半松手失效。
                            // 改动前它就是每个 move 无条件回调的，这里保持该语义。
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
                // ⚠️ 顺序不能换：onDragStopped 必须**先**执行、且能读到 true，
                // 它才能据此跳过提交（让位场景）。清标志必须放在它之后。
                onDragStopped(this@DampedDragAnimation)
                // 让位标志的生命周期 = 本次手势。这里清掉，避免跨手势残留。
                // （awaitEachGesture 开头那次复位是双保险，两者不冲突。）
                yieldedToParentState = false
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
