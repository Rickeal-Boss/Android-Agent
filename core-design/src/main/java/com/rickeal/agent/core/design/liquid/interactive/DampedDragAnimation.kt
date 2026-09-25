package com.rickeal.agent.core.design.liquid.interactive

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
    private val onDragStarted: DampedDragAnimation.() -> Unit,
    private val onDragStopped: DampedDragAnimation.() -> Unit,
    private val onDrag: DampedDragAnimation.(value: Float, dragAmount: Offset) -> Unit,
    /**
     * 只有**累积主轴位移**超过它才 `consume()` 事件。
     *
     * ⚠️ 累积的是 `abs(dragAmount.x)`（主轴），**不是**欧氏距离 —— 纵向滑动不会累积，
     * 因此纵向永远不消费，父级 `verticalScroll` 才能正常接管滚动。
     *
     * **当前全仓 4 个调用点，全部显式传正值 8dp**（主轴均为 x，暂无纵向控件）：
     *  - `GlassSegmented`（分段指示器拖动）
     *  - `GlassSlider`（滑块拖动）
     *  - `LiquidBottomTabs`（底栏页签拖动）
     *  - `GlassSwitch`（开关，其局部变量名为 `touchSlopPx`）
     *
     * **为什么现在都必须传正值**：`GlassSegmented` / `GlassSlider` / `LiquidBottomTabs`
     * 三处的手势宿主在 Wave 10 Phase 2d/2e 都从"被平移的元素节点"迁到了**静态**覆盖层
     * （为分离坐标反馈），命中路径随之从"元素自身 bounds"扩成"整条宿主" ⇒ 若仍用默认
     * `0f`（"横向一动就消费"），真机点击 / 点轨道跳转必然带亚像素抖动，1px 就被 consume
     * 取消 ⇒ **"点了没反应"**。`GlassSwitch` 同理（它外层挂了 `toggleable`）。过了 slop
     * 才消费，才能把"点击"与"拖动"这两条路径干净地分开。
     *
     * 默认 `0f` 仅为历史默认值，**当前无任何调用点使用**（保留它是为了不动既有构造签名）。
     * **若将来新增纵向控件（如竖向 slider），这个累加必须参数化**，否则纵向拖动会失效。
     */
    private val consumeSlopPx: Float = 0f,
    /**
     * 是否允许"纵向意图 → 让位给父级滚动"（2026-09-24 新增开关）。
     *
     * 让位锁（tan30° 分轴 + 一次判定终生让位）是为**可滚动容器里的横向控件**
     * （设置页的滑块/开关）设计的；判定为纵向后本手势永久静默。对**没有纵向
     * 滚动父级**的控件（底栏 LiquidBottomTabs——它在 MainShell 的 Column 里，
     * 上下都没有 verticalScroll），让位锁是纯害：用户拖页签时手指的自然弧线
     * 会让 net-vertical 越过阈值 → 拖拽**中途冻结**（"拉越远越偏移"的直接来源
     * 之一）。这类调用点必须传 false。
     */
    private val canYieldToParent: Boolean = true,
    /**
     * 可选起手门禁：**带接收者**的 lambda，入参是**手势宿主节点的局部坐标**按下点，
     * 返回 true 才允许本次手势起手。
     *
     * ⚠️ 为什么带接收者（`DampedDragAnimation.(Offset) -> Boolean`，Wave 10 Phase 2d）：
     * 调用点需要按**被拖元素的实时位置**判定抓取区（页签 = 胶囊实时 `value`、滑块 =
     * thumb 实时 `progress`）。带接收者后门禁里可直接读 `value` / `progress` —— 既
     * **不需要**在 `remember{}` 之后用 holder 回填实例（那是"组合期写 MutableState"的
     * 反模式），也**没有**"首帧实例未就绪"分支（恒可用）。若用无接收者的
     * `(Offset) -> Boolean`，调用方只能退而求其次读"内部选中态"（动画窗口内会偏半格）。
     *
     * ⚠️ 用途：把「手势宿主」从被平移的胶囊 / thumb 节点迁到**静态**覆盖层后，手势区会从
     * "元素自身 bounds"扩成"整条宿主"。为了让行为**零变化**，调用方传入逐轴复刻原抓取区
     * 的门禁（**宿主矩形 ∩ 元素实时矩形**）。
     *
     * 返回 false 时：**不** `setPressed`、**不**置 [isDragging]、**不**走
     * [onDragStarted] / [onDragStopped]，且**不消费**事件。后续分流看 [onTap]：
     *  - [onTap] == null（GlassSlider / GlassSwitch 等既有调用点）：维持原语义，直接
     *    `return@awaitEachGesture`，本次手势到此为止（外层 `clickable` /
     *    `toggleable` 照常生效）；
     *  - [onTap] != null（静态宿主独占命中的调用点）：进**观察循环**，跟踪这根手指
     *    到抬手，按 [onTap] 的语义决定是否回调点按。
     * 门禁在 `awaitFirstDown` 之后、`setPressed(true)` 之前求值，因此被挡下的按下
     * 对控件**完全无副作用**。
     *
     * 默认 `null` = 不设门禁（开关等既有行为完全不变）。
     */
    private val canStartDrag: (DampedDragAnimation.(Offset) -> Boolean)? = null,
    /**
     * 可选点按观察通道（2026-09-26 新增）：**门禁不过**时的补救路径。
     *
     * ⚠️ 背景（为什么需要它）：compose-ui 1.10.3 `InnerNodeCoordinator.hitTestChild`
     * 的兄弟命中语义 —— 兄弟节点按 z 序**逆序**命中，顶部兄弟命中后若不与兄弟共享
     * 指针输入（默认），**下层兄弟全部不再参与命中**。当手势宿主是 `matchParentSize`
     * 覆盖整条的**静态**顶层节点（Wave 10 Phase 2d/2e 的坐标反馈修复）时，宿主下层
     * 的 `clickable`（底栏页签 / 分段项）收不到任何指针事件 —— 真机表现为
     * **"点击死亡"**（点击无响应，只能拖动换页）。这类调用点传 [onTap]，由本类的
     * 手势循环代为观察点按。
     *
     * 语义（**只在门禁不过时**走；门禁通过路径完全不经过这里）：
     *  - 观察手势至抬手：净位移（与主循环同款 reach 公式
     *    `Offset(abs(accumulatedX), abs(accumulatedY)).getDistance()`，有符号净位移
     *    逐轴取 abs 再求欧氏距离 —— 不是路径长度，来回抖动不会顶过阈值）未越过
     *    touchSlop（循环外快照 `slop`，观察循环内**绝不**读 `viewConfiguration`）
     *    且事件流未断（tracked change 一直在）⇒ 以**宿主局部坐标**的按下点
     *    `down.position` 回调；
     *  - 净位移越过 slop ⇒ 判定为拖动意图、tap 取消，但仍**继续观察到 up、不
     *    break、不消费** —— 观察分支只回答"是不是 tap"这一个问题，不改变任何
     *    事件走向；
     *  - **全程零副作用**：不消费任何事件、不 `setPressed`、不置 [isDragging]、
     *    不碰 [yieldedToParent] / [finishedNormally] 任何状态字段 —— [isDragging]
     *    被调用方的回显门禁（`LaunchedEffect(selectedIndex)`）读取，观察分支碰它
     *    会引发回写互搏。
     *
     * 入参与 [canStartDrag] 同一坐标系（宿主局部坐标），调用方按同一套几何常量
     * 换算目标项序号（LiquidBottomTabs 需 TabPad 边界守卫 —— Kotlin `toInt()` 向零
     * 截断，`(-0.5).toInt() == 0`，否则左 pad 带会被静默映射到第 0 格）。
     *
     * 默认 `null` = 既有调用点（GlassSlider / GlassSwitch）零变化。
     */
    private val onTap: (DampedDragAnimation.(Offset) -> Unit)? = null,
) {

    private val valueAnimatable = Animatable(initialValue, visibilityThreshold)
    private val pressAnimatable = Animatable(0f)

    // ── 回弹恢复（2026-09-26，对齐上游 Kyant0 catalog/utils/DampedDragAnimation）────
    // 上游的 scale 是**独立的欠阻尼弹簧**（scaleX dampingRatio=0.6 / scaleY=0.7、
    // stiffness=250），不是 pressProgress 的纯函数 —— 按下微过冲、松手**回弹数拍**
    //（iOS Liquid Glass 的标志性 squash & stretch）。本仓移植时把 scale 写成了
    // `initialScale + (pressedScale - initialScale) * pressProgress`，pressProgress
    // 又是 NoBouncy 临界阻尼 ⇒ 回弹整条丢失（用户以上游 demo 录屏对比实锤）。
    // 规格逐字取自上游（0.6/0.7/250），initialThreshold 上游用 0.001f。
    private val scaleXAnimatable = Animatable(initialScale, 0.001f)
    private val scaleYAnimatable = Animatable(initialScale, 0.001f)

    /**
     * scale 弹簧规格（上游逐字）：欠阻尼 = 松手回弹的来源。
     * X 轴 0.6 / Y 轴 0.7：松手时横向先弹过头再收，纵向略收窄，两轴相位差
     * 产生"果冻"观感。**不要**改成 NoBouncy —— 那正是被丢掉的回弹。
     */
    private val scaleXAnimationSpec =
        spring(dampingRatio = 0.6f, stiffness = 250f, visibilityThreshold = 0.001f)
    private val scaleYAnimationSpec =
        spring(dampingRatio = 0.7f, stiffness = 250f, visibilityThreshold = 0.001f)

    // 上游同款：独立的速度动画（spring(0.5f, 300f)，欠阻尼），由 VelocityTracker
    // 对 **value**（页签序号量纲）逐帧采样驱动，而不是直接读 valueAnimatable.velocity
    // —— 拖动路径是 snapValue（瞬时到位，Animatable.velocity 恒 ≈0），直接读它
    // 等于拖动中没有任何拉伸（Wave 10 已申报的取舍）。逐帧 animateTo 一个
    // 欠阻尼弹簧：拖动中真实跟手拉伸 + 松手后速度带过冲衰减 = 上游的弹性拖尾。
    private val velocityAnimatable = Animatable(0f, 5f)
    private val velocityAnimationSpec =
        spring(dampingRatio = 0.5f, stiffness = 300f, visibilityThreshold = 0.01f)
    private val velocityTracker = VelocityTracker()

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

    /**
     * 当前速度（value 量纲 / s，来自独立速度弹簧——见 [velocityAnimatable] 注释）。
     * 用于各向异性拉伸。
     */
    val velocity: Float get() = velocityAnimatable.value

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
     * 本次手势是否是**完整走完**的：`true` = 手指按下过并抬起（正常结束）；
     * `false` = 事件流断了（change 被移除 / 这根手指从未真正按下），
     * 此时 [onDragStopped] 里不应再走业务提交。
     *
     * 判据是 `down.previousPressed`，不是 `change.isConsumed` —— 见赋值处的说明。
     */
    val finishedNormally: Boolean get() = finishedNormallyState

    private var finishedNormallyState = false

    /**
     * 手势是否进行中（按下 → 抬起/让位/断流）。
     *
     * **可观测**（mutableStateOf）—— 调用方用它做**回显门禁**：拖动期间手势是
     * 数值的唯一事实来源，外部状态（导航回压 / StateFlow 回流）此刻写入只会
     * 与 `snapValue` 打架（2026-09-24 真机录屏实证的"胶囊两端乱飘"振荡）。
     */
    val isDragging: Boolean get() = isDraggingState.value

    private val isDraggingState = mutableStateOf(false)

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

    /**
     * 按下时放大的 X 缩放（[initialScale] → [pressedScale]）。
     * 2026-09-26 起读**独立欠阻尼弹簧**（不再随 pressProgress 线性走）——
     * 按下微过冲、松手回弹，见 [scaleXAnimatable] 处注释。
     */
    val scaleX: Float get() = scaleXAnimatable.value

    /** 同 [scaleX]，Y 轴（阻尼比 0.7，回弹相位与 X 略异 = 果冻感）。 */
    val scaleY: Float get() = scaleYAnimatable.value

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
            finishedNormallyState = false
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
            // ⚠️ 起手门禁：必须在 `yieldedToParentState / finishedNormallyState` 复位**之后**、
            // `setPressed(true)` **之前**求值 ——
            //  · 放在复位之后：被挡下的手势不残留任何本次标志（下一轮 awaitEachGesture 干净）；
            //  · 放在 setPressed 之前：门禁不过 ⇒ 按压态从不置位 ⇒ 胶囊不会在"按非当前页签"
            //    时鼓包（pressProgress 驱动的 56→78dp 弹簧），事件也不消费 ⇒ 页签点击照常。
            // 控制流直接交回 awaitEachGesture（它会在 block 返回后 awaitAllPointersUp，
            // 等全部手指抬起才进下一轮，不会反复触发）。
            //
            // ⚠️ 用 `?.let` + `invoke`：`let` 是 inline ⇒ `return@awaitEachGesture` 非局部
            // 返回合法；`invoke` 显式传接收者，避免"带接收者 lambda"在可空属性上的
            // 智能转换 / receiver 调用歧义。
            canStartDrag?.let { gate ->
                if (!gate.invoke(this@DampedDragAnimation, down.position)) {
                    // 门禁不过：分流看 onTap。
                    //  - onTap == null（既有调用点）：维持原语义，直接交回 awaitEachGesture。
                    //  - onTap != null（静态宿主独占命中的补救）：进**观察循环**，跟踪这根
                    //    手指到抬手，"净位移未过 slop 的完整点按"回调 onTap。
                    // ⚠️ 观察循环**只读事件、不写任何状态**：不 setPressed、不置
                    // isDraggingState、不碰 yieldedToParentState / finishedNormallyState
                    // —— isDragging 被调用方的回显门禁读取，观察分支碰它会引发回写互搏。
                    // ⚠️ 全程不 consume、不改事件走向；slop 用循环外的既有快照
                    //（上面那行 `val slop = viewConfiguration.touchSlop`），
                    // 观察循环内绝不读 viewConfiguration。
                    if (onTap != null) {
                        var tapPrevious = down.position
                        var tapAccumX = 0f
                        var tapAccumY = 0f
                        var tapCandidate = true
                        while (true) {
                            val tapEvent = awaitPointerEvent()
                            val tapChange = tapEvent.changes.firstOrNull { it.id == down.id }
                                ?: break // 事件流断（change 被移除）⇒ 无 tap，静默放弃
                            if (!tapChange.pressed) {
                                // 抬手：全程净位移未越过 slop 才算一次 tap。
                                if (tapCandidate) {
                                    onTap.invoke(this@DampedDragAnimation, down.position)
                                }
                                break
                            }
                            val tapCurrent = tapChange.position
                            val tapDelta = tapCurrent - tapPrevious
                            tapPrevious = tapCurrent
                            if (tapDelta != Offset.Zero) {
                                // 与主循环同款 reach 公式（有符号净位移 → 逐轴 abs → 欧氏距离）。
                                tapAccumX += tapDelta.x
                                tapAccumY += tapDelta.y
                                if (Offset(abs(tapAccumX), abs(tapAccumY)).getDistance() >= slop) {
                                    // 判定为拖动意图：取消 tap 候选，但**继续观察到 up、
                                    // 不 break、不消费** —— 观察分支不改变任何事件走向。
                                    tapCandidate = false
                                }
                            }
                        }
                    }
                    return@awaitEachGesture
                }
            }
            setPressed(true)
            isDraggingState.value = true
            onDragStarted(this@DampedDragAnimation)
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        // ⚠️ 走到这里有两种截然不同的情况，必须区分：
                        //   1. 手指**按下过又抬起** → 一次完整手势正常结束
                        //   2. 这根 change 从未真正按下（事件流断了 / change 被移除）
                        //      → 不是手势结束，不该走业务提交
                        //
                        // ⚠️ 直接置 true，**不要用 down.previousPressed**。
                        //
                        // 早期这里写成 `down.previousPressed`，并注释"恒为 true"——
                        // **两个都是错的**。源码（foundation 1.10.3 PointerEvent.kt）：
                        //   changedToDownIgnoreConsumed() = !previousPressed && pressed
                        // 而 awaitFirstDown 的循环条件正是 isChangedToDown，
                        // 所以返回的 down **必定满足 previousPressed == false**
                        // → finishedNormally 恒为 false → 业务提交被永久关闭
                        // （滑块改完不落盘、开关拨不动，只能点）。
                        //
                        // 能走到这个分支，说明我们确实看到了指针释放，即一次完整手势。
                        // 真正需要拦的是上面 `? : break` 那条路：change 被移除
                        // （事件流断了）时根本走不到这里，标志保持初始的 false。
                        finishedNormallyState = true
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
                            if (canYieldToParent &&
                                abs(accumulatedY) > abs(accumulatedX) * VERTICAL_INTENT_TAN30
                            ) {
                                // 让位给父级滚动。置位后本手势"吞掉"后续事件。
                                yieldedToParentState = true
                                // ⚠️ 父级一接管就要取消按压，**不能等到 finally**（那要等抬手）。
                                // 否则手指按在滑块/开关上纵向滑走之后，pressProgress 会一直
                                // 保持 1：thumb 维持 1.5 倍拉伸、玻璃维持"变实"
                                // （模糊减弱 + 折射增强）贯穿整个滚动过程，直到抬手才归位。
                                // 平台标准行为是"父级接管即取消"，InteractiveHighlight
                                // （走 break 路径）也是这个行为 —— 两边必须一致，
                                // 否则同一个手势语义在两个类里表现相反。
                                setPressed(false)
                                // 让位即拖拽事实来源终止：回显门禁要放行（isDragging 复位），
                                // 否则外层状态在剩余手势里永远无法回写到控件上。
                                isDraggingState.value = false
                            }
                        }

                        // 让位后留在循环里"吞掉"后续事件：既不消费也不 onDrag，但继续等 up。
                        //
                        // 📌 机制澄清（早期这里写过一段**错误**的说明，已更正）：
                        //   曾认为「break 之后下一轮 awaitFirstDown 会立刻拿到同一根手指，
                        //   反复走 onDragStopped → 落盘 N 次」。这不成立 ——
                        //   awaitEachGesture 在 block() 返回后会调用 awaitAllPointersUp()，
                        //   正是用来防止"手势还没结束就重开一轮"的，
                        //   所以 break 之后协程会等到所有手指抬起才进下一轮。
                        //
                        //   那为什么还保留吞事件、不退回 break？因为吞事件有一个
                        //   break 做不到的好处：让 yieldedToParent 标志**活到 up 帧**。
                        //   GlassSlider 的 detectTapGestures 守卫
                        //   （`if (yieldedToParent) return@detectTapGestures`）在 up 帧读它；
                        //   若退回 break，finally 会在 up 之前就把标志清掉，跳值又会发生。
                        //
                        //   代价是按压态要提前归位 —— 由上面那次显式 setPressed(false) 负责。
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

                            // ── 速度采样（2026-09-26 回弹恢复，上游 updateValue 同款）────
                            // 对 value（页签/进度量纲）按时间采样，目标速度 = tracker 速度
                            // ÷ valueRange 跨度（归一化成"每秒走完多少比例的量程"），
                            // 交给欠阻尼速度弹簧跟随 —— 拖动中各向异性拉伸有真实来源，
                            // 松手后速度带过冲衰减（弹性拖尾）。上游逐帧 animateTo 的
                            // 模式照搬；snapValue 让 valueAnimatable.velocity 恒 ≈0，
                            // 那条路已经没有速度可读（Wave 10 申报的取舍由此真正解决）。
                            velocityTracker.addPosition(
                                SystemClock.elapsedRealtime(),
                                Offset(valueAnimatable.value, 0f),
                            )
                            val span = valueRange.endInclusive - valueRange.start
                            val trackedVelocity = velocityTracker.calculateVelocity().x /
                                if (span > 0f) span else 1f
                            animationScope.launch {
                                velocityAnimatable.animateTo(
                                    trackedVelocity,
                                    velocityAnimationSpec,
                                )
                            }
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
                // 速度弹簧终点归零（防御性，覆盖全部手势终点）：正常松手后消费方会在
                // onDragStopped 里 animateToValue 收敛 value，但速度弹簧的目标若不显式
                // 归零，会停在最后一次采样值上 —— layerBlock 的各向异性拉伸将永久卡住。
                // 欠阻尼弹簧从当前速度带过冲衰减到 0 = 松手的弹性拖尾（上游经
                // animateToValue 里的 velocity→0 达成同款观感，这里收口更严）。
                // 让位 / 事件流断路径同样走到这里（finally 语义），不会残留拉伸。
                animationScope.launch {
                    velocityAnimatable.animateTo(0f, velocityAnimationSpec)
                }
                // ⚠️ 顺序不能换：onDragStopped 必须**先**执行、且能读到 true，
                // 它才能据此跳过提交（让位场景）。清标志必须放在它之后。
                onDragStopped(this@DampedDragAnimation)
                // 让位标志的生命周期 = 本次手势。这里清掉，避免跨手势残留。
                // （awaitEachGesture 开头那次复位是双保险，两者不冲突。）
                yieldedToParentState = false
                isDraggingState.value = false
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

    /**
     * 直接把值 animate 过去（点轨道跳转 / 外部选中态变化时用）。
     *
     * [animationSpec] 默认 `spring()` —— 与历史行为一致（同一刚度 / 阻尼），既有调用点
     * 不受影响。页签点击切换显式传入 `LiquidMotion.TabSwitch`（2026-09-24 起与上游
     * 对齐的临界阻尼快弹簧，见其 KDoc 的真机证据）。
     *
     * ⚠️ 拖动松手收敛（`onDragStopped`）刻意**不传** spec，保持默认的快收敛手感。
     */
    fun animateToValue(value: Float, animationSpec: AnimationSpec<Float> = spring()) {
        val coerced = value.coerceIn(valueRange)
        targetValue = coerced
        animationScope.launch { valueAnimatable.animateTo(coerced, animationSpec) }
    }

    /**
     * **立即到位**（`snapTo`，不走弹簧）。拖动期间专用。
     *
     * 为什么拖动不能用弹簧：thumb 位置完全由 [valueAnimatable] 驱动，若拖动期间
     * 每帧都 `animateTo` 重启弹簧，弹簧永远追不上每帧更新的目标 → 永不收敛 →
     * thumb 恒定滞后于手指（"不跟手"）。拖动要求的是**瞬时相等**，不是缓动。
     *
     * `snapTo` 与 `animateTo` 共用同一个 mutation 锁：每次调用会取消在途动画，
     * 所以逐帧调用是安全且预期的（旧目标直接作废）。
     *
     * ⚠️ 派发方式必须是 [CoroutineStart.UNDISPATCHED]，**不是**默认的 `launch`：
     * `animationScope` 来自 `rememberCoroutineScope()`，其 context 是
     * `AndroidUiDispatcher.Main + MonotonicFrameClock`，默认 `launch` 是**派发**的
     * —— 协程体要等下一个 frame 的 dispatch 阶段才跑 ⇒ 拖动期渲染读到的 [value]
     * 恒滞后 ≥1 帧（真机上表现为"胶囊/滑块慢半拍"）。`UNDISPATCHED` 让协程体在
     * **首个挂起点之前同步执行**：`snapTo` 在没有在途动画竞争时会同步把 value 写完，
     * 当帧即生效、严格跟手。
     *
     * 📌 与真机录屏里那次"胶囊在 1.6↔3.2 页签间以 ≈5.9Hz 振荡"**不是一回事** ——
     * **修正（Wave 10 Phase 2d）**：早前曾断言那次振荡是"双写者互搏、已由 `bf2bed4`
     * 的回显门禁切断"，这是**错的**。2026-09-25 15:01 录屏（tip `53656a5`，已含
     * `bf2bed4`）里振荡仍在，真因是**坐标反馈**（手势宿主与被平移节点是同一个 ⇒ 一阶
     * 反馈，斜率 ≈0.489）叠加**本处的派发延迟**（`v_{t+1} = v_{t-1}` 二周期）。坐标反馈
     * 由 `LiquidBottomTabs` 的 R1（手势宿主迁静态节点）修复；本处只负责消掉派发延迟那半边。
     *
     * ⚠️ **影响面申报（Wave 10）**：`snapValue` 是全仓共享 API，改动 `launch` →
     * `UNDISPATCHED` 会同时影响**全部 3 个调用点**，且它们**都在拖动路径**上：
     *  - `GlassSegmented.kt:217`（分段控件拖动换项）
     * `GlassSlider.kt:286`（滑块拖动）
     * `LiquidBottomTabs.kt:366`（底栏页签拖动换页）
     * ⇒ 本次顺带改变了**已验收的滑块 / 分段跟手行为**。方向为**正向**（值同步生效、
     * 更跟手、无回归风险），但属"申报外的行为变更"，真机验收**必须回归滑块与分段**。
     * 📌 `animateToValue` / `updateValue` **未动**（保持默认 `launch`）：它们是非拖动
     * 路径（1 帧启动延迟不可感知），且被 `GlassSwitch` 的弹簧路径消费，改了会波及开关。
     *
     * ⚠️ 只做"值立即到位"这一件事 —— 不碰手势循环 / consume 门控 / 轴向锁定，
     * 那三块是真机验过的稳定性所在，别为手感顺手改它们。
     */
    fun snapValue(value: Float) {
        val coerced = value.coerceIn(valueRange)
        targetValue = coerced
        animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            valueAnimatable.snapTo(coerced)
        }
    }

    /**
     * 按压 / 释放的三路弹簧：pressProgress（临界阻尼，NoBouncy 与上游一致）+
     * scaleX / scaleY（欠阻尼 0.6/0.7 —— 回弹来源）。
     *
     * ⚠️ 影响面（2026-09-26 回弹恢复申报）：scaleX/scaleY 从 pressProgress 的纯函数
     * 改为独立弹簧，消费方 = LiquidBottomTabs 胶囊 layerBlock、GlassSlider thumb、
     * GlassSwitch thumb —— 三者的按压/松手都会获得上游同款的微过冲与回弹；
     * pressProgress 本身（effects 的模糊/折射/阴影）仍走原 NoBouncy 路径，观感不变。
     */
    private fun setPressed(pressed: Boolean) {
        // 按下即重置速度采样（上游 press() 同款）：上一段手势的速度不得污染本次。
        velocityTracker.resetTracking()
        animationScope.launch {
            launch {
                pressAnimatable.animateTo(
                    targetValue = if (pressed) 1f else 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                )
            }
            launch {
                scaleXAnimatable.animateTo(
                    targetValue = if (pressed) pressedScale else initialScale,
                    animationSpec = scaleXAnimationSpec
                )
            }
            launch {
                scaleYAnimatable.animateTo(
                    targetValue = if (pressed) pressedScale else initialScale,
                    animationSpec = scaleYAnimationSpec
                )
            }
        }
    }
}
