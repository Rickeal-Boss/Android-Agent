package com.rickeal.agent.core.design.liquid.interactive

import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import kotlin.math.abs
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
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
 * 按压进度弹簧：上游规格 `spring(0.5f, 300f, 0.001f)` —— **欠阻尼**。
 *
 * 2026-09-26 果冻回弹恢复：本仓此前写成 NoBouncy/StiffnessMedium（临界阻尼），
 * "按实"程度是单调到位的，一按下去没有"先过一点再回"的弹动。上游 0.5 阻尼比会
 * 让按压进度在 1 附近小幅过冲收敛 —— 这是果冻手感的第一个来源。
 */
private val PressSpring = spring<Float>(
    dampingRatio = 0.5f,
    stiffness = 300f,
    visibilityThreshold = 0.001f,
)

/**
 * 跟手偏移的**归位**弹簧：上游规格 `spring(0.5f, 300f)` —— **欠阻尼**。
 *
 * 拖动中是瞬时跟手（上游 `snapTo`），松手才走这条弹簧回零 —— 回零路上欠阻尼
 * 过冲 ⇒ 元素"弹回去再稳住"，这是果冻手感的第二个来源。
 *
 * ⚠️ 拖动中**不**用它：本仓旧实现拖动中也走弹簧（且是 NoBouncy + StiffnessLow
 * = 又软又无弹动），结果是"位移迟钝"而不是"果冻"。上游口径：拖动手起手落、松手才弹。
 */
private val OffsetSettleSpring = spring<Float>(
    dampingRatio = 0.5f,
    stiffness = 300f,
    visibilityThreshold = 0.001f,
)

/**
 * 判定"纵向意图"的阈值：tan(30°) = 0.577。
 *
 * 与 foundation 1.10.3 `DragGestureNode.processAwaitTouchSlop` 的分轴规则对齐：
 *   `atan2(x = |dx|, y = |dy|)` → Horizontal: `angle <= 30`；Vertical: `30 < angle <= 90`
 * 换算成可直接比较的形式即 `|dy| > |dx| * tan(30°)`。
 *
 * ⚠️ 不要"简化"成 `|dy| > |dx|`（那等于把分界放到 45°），
 * 否则 30°~45° 的斜滑会出现「Compose 认为父级该滚、我们却判横向并消费」的死区。
 */
private const val VERTICAL_INTENT_TAN30 = 0.577f

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
            val progress = pressAnimatable.value
            if (progress > 0.001f) {
                // 上游口径（2026-09-26 对齐）：先整片轻微提亮（0.08p，Plus 叠加），
                // 再在触摸点叠一圈柔和径向光（0.15p，半径 minDimension × 1.5）。
                // 本仓旧实现只有后半段、且用普通 SourceOver 混合 + 0.9 倍半径 ——
                // 按压时"光"起不来，果冻形变少了光泽那半边。
                drawRect(
                    color = Color.White.copy(alpha = 0.08f * progress),
                    blendMode = BlendMode.Plus,
                )
                val center = position?.invoke(nodeSize, touchOffset) ?: touchOffset
                val radius = minOf(size.width, size.height) * 1.5f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.15f * progress),
                            Color.Transparent
                        ),
                        center = center,
                        radius = radius
                    ),
                    radius = radius,
                    center = center,
                    blendMode = BlendMode.Plus,
                )
            }
            drawContent()
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
            // ⚠️ slop 必须在**循环外**取一次快照（pin）。
            // viewConfiguration 来自 CompositionLocal，手势跑到后面时 composition
            // 可能已经 dispose，此时再取值会抛 IllegalStateException（CompositionLocal 越界）。
            val slop = viewConfiguration.touchSlop
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
                        // ⚠️ 累加**有符号**的 dx/dy（净位移），不要累加 abs / getDistance()。
                        // getDistance() 恒非负 → 累加的是"路径长度"，来回抖动会单调累加
                        // 顶过 slop → 误判为拖动 → 误消费 → 外层 clickable 被取消。
                        // Compose 自己累加的也是 dragAccumulator += dragAmount（有符号 Offset）。
                        accumulatedX += dragAmount.x
                        accumulatedY += dragAmount.y

                        // 轴向锁定：判定为纵向意图 → 让位给父级滚动。
                        //
                        // ⚠️ 阈值 **必须跟 Compose 对齐**，不能用直觉的「|dy| > |dx|」。
                        // foundation 1.10.3 DragGestureNode.processAwaitTouchSlop：
                        //   atan2(x = |dx|, y = |dy|) 换算角度
                        //   Horizontal: angle <= 30 ; Vertical: 30 < angle <= 90
                        // 命名参数 x=|dx|、y=|dy|，即 atan(|dy|/|dx|)，**从 X 轴量角**
                        // （0°=纯横、90°=纯纵）。故 Vertical ⟺ |dy| > |dx| * tan30° = 0.577。
                        // 若写成 |dy| > |dx|（等于 45°），则 30°~45° 这段斜滑会出现
                        // "Compose 认为父级该滚、我们却判横向并消费"的残余死区。
                        //
                        // ⚠️ 必须 **break**，不能只"跳过 consume"（源码依据）：
                        // verticalScroll / LazyColumn 的 startDragImmediately = false，
                        // 父级只在 **Main pass** 消费，而 Main 自下而上 —— 子级永远
                        // 先拿到未消费事件。只跳 consume 的话，父级开滚后子级仍会继续
                        // 走 onDrag / 跟手位移，表现为"一边滚页面一边改数值"。
                        // break 后 awaitEachGesture 等抬手才重启，一次解决。
                        // 代价：用户要多滑几 px 才起滚，且按压态提前归位（与系统一致）。
                        //
                        // 用**累计** dx/dy 判定（Compose 自己也是用 dragAccumulator），
                        // 单帧 delta 在起手那一两帧方向噪声很大。
                        // ⚠️ 方向判定**必须先过幅度门限**，不能在第一帧就判。
                        // 起手那一两帧 accumulatedX/Y 还是亚像素噪声，
                        // 直接套 0.577 会让纯横向拖动被误判成纵向 → 整个手势作废
                        // → 按钮/页签"有时拖不出跟手效果"（随机，极难查）。
                        // Compose 也是在越过 slop 那一刻才算 gestureAngle，不是第一帧。
                        val reach = Offset(abs(accumulatedX), abs(accumulatedY)).getDistance()
                        if (reach >= slop) {
                            if (abs(accumulatedY) > abs(accumulatedX) * VERTICAL_INTENT_TAN30) {
                                break
                            }
                        }

                        // ⚠️ 用公开的 `touchSlop`。foundation 内部那个
                        // `viewConfiguration.pointerSlop(pointerType)` 是 internal 扩展，
                        // 外部调不到（CI 实测 Unresolved reference）。
                        // 📌 与 DampedDragAnimation 的 `abs(accumulatedX) > consumeSlopPx`
                        // 形似但**不是一回事**，不要照抄成 `> 0f`：
                        //   这里没有 consumeSlopPx 参数，阈值固定是 touchSlop（8dp）；
                        //   若改成 `> 0f` 就变成"一有横向位移就消费"，
                        //   会把 P0 那次修复（越 slop 才消费、点击不被取消）改回去。
                        // `>=` 与 `>` 在 8dp 下的差别只是正好等于阈值的那一帧，
                        // 不影响行为，保持现状。
                        if (abs(accumulatedX) >= slop) {
                            change.consume()
                        }
                        // 跟手位移与是否消费无关，照常累加 —— 手感不受影响。
                        rawOffsetX += dragAmount.x
                        rawOffsetY += dragAmount.y
                        // ⚠️ 拖动中**瞬时**跟手（上游 `snapTo`），不是弹簧跟随。
                        // 果冻来自**松手**的欠阻尼归零（见 [OffsetSettleSpring]），
                        // 拖动中走弹簧只会变成"位移迟钝"（本仓旧实现就是这条错路：
                        // NoBouncy + StiffnessLow，又软又没弹动）。
                        // UNDISPATCHED：与 snapValue 同一口径，当帧生效、不慢半拍。
                        animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
                            offsetXAnimatable.snapTo(rawOffsetX)
                            offsetYAnimatable.snapTo(rawOffsetY)
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
                // 松手归零走**欠阻尼**弹簧（上游 `animateTo(startPosition, spring(0.5,300))`）：
                // 元素从手指位置弹回原位、带一次轻微过冲 —— 果冻手感的第二个来源。
                // 本仓旧实现用默认 spec（临界阻尼）= 单调归零，没有弹动。
                animationScope.launch {
                    offsetXAnimatable.animateTo(0f, OffsetSettleSpring)
                    offsetYAnimatable.animateTo(0f, OffsetSettleSpring)
                }
            }
        }
    }

    /**
     * 驱动按压进度（2026-09-26 由 private 公开）：供"手势源被静态宿主独占命中"的
     * 调用点从**门禁通过路径**驱动按压进度 —— 典型是 LiquidBottomTabs：compose-ui
     * 1.10.3 `InnerNodeCoordinator.hitTestChild` 的兄弟命中独占语义下，胶囊上的
     * [gestureModifier] 收不到指针事件，按压高光改由 DampedDragAnimation 的
     * onDragStarted / onDragStopped 回调驱动（见 LiquidBottomTabs 的
     * `highlightPressDriver`）。
     *
     * 与 [gestureModifier] 的驱动**幂等**：两边都是对同一个 pressAnimatable
     * `animateTo` 同一目标值（按压 1f / 归位 0f），谁先谁后、单路还是双路都收敛到
     * 同一状态，不会打架。其余 7 个消费点不受影响（纯增量公开，函数体未动）。
     */
    fun setPressed(pressed: Boolean) {
        animationScope.launch {
            pressAnimatable.animateTo(
                targetValue = if (pressed) 1f else 0f,
                // 欠阻尼（上游 0.5/300）：按下/松手都带过冲收敛 —— 果冻手感来源之一。
                animationSpec = PressSpring
            )
        }
    }
}
