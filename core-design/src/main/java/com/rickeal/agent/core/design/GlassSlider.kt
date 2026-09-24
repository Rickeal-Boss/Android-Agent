package com.rickeal.agent.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
// ⚠️ `key` / `type` 不是 KeyEvent 的成员，而是 `expect val KeyEvent.key` /
// `expect val KeyEvent.type` **扩展属性**（commonMain 声明、androidMain 实现）。
// 少了这两行就会报 "Unresolved reference 'key' / 'type'" —— CI 实测踩过。
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.liquid.Backdrop
import com.rickeal.agent.core.design.liquid.EmptyBackdrop
import com.rickeal.agent.core.design.liquid.LocalBackdrop
import com.rickeal.agent.core.design.liquid.backdrops.layerBackdrop
import com.rickeal.agent.core.design.liquid.backdrops.rememberBackdrop
import com.rickeal.agent.core.design.liquid.backdrops.rememberCombinedBackdrop
import com.rickeal.agent.core.design.liquid.backdrops.rememberLayerBackdrop
import com.rickeal.agent.core.design.liquid.drawBackdrop
import com.rickeal.agent.core.design.liquid.effects.blur
import com.rickeal.agent.core.design.liquid.effects.lens
import com.rickeal.agent.core.design.liquid.highlight.Highlight
import com.rickeal.agent.core.design.liquid.interactive.DampedDragAnimation
import com.rickeal.agent.core.design.liquid.shadow.InnerShadow
import com.rickeal.agent.core.design.liquid.shadow.Shadow
import com.rickeal.agent.core.design.liquid.shapes.Capsule
import kotlin.math.abs
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

/**
 * 参数滑块 —— 整体对齐 Kyant0 `LiquidSlider`。
 *
 * ## 必须完全重写的原因
 *
 * 原来这里是 `androidx.compose.material3.Slider`（用户点名的"原生按钮"之一就是它）：
 * 无论壁纸/折射调多准，观感永远是"原生 M3 控件贴在玻璃纸上"。
 * 现在整个组件基于玻璃引擎自绘：
 *
 *  - 轨道 / 已填充段 / thumb 全部是**胶囊**；
 *  - thumb 是真玻璃：`blur(8dp * (1-progress))` + `lens(10dp, 14dp, 色散开)`；
 *  - 拖动时 thumb 沿拖动方向**拉长**、垂直方向**压扁**（各向异性），松手回弹；
 *  - 点轨道任意位置会跳过去（带弹簧），不是只在 thumb 上能拖。
 *
 * ## 无障碍（自绘最容易丢的东西）
 *
 * 换成自绘后，M3 `Slider` 自带的 TalkBack / 键盘支持全没了，这里补齐：
 *  - `semantics`：`label` 作 `contentDescription` + `ProgressBarRangeInfo` + `setProgress`
 *    （Compose 的 `Role` **没有** Slider，foundation 的 `sliderSemantics` 是 internal，只能手写）；
 *  - 键盘：`.focusable()` + 左右/上下/Home/End 按 step 增减；
 *  - 48dp 触摸目标：手势挂在 48dp 的外层 Box 上，6dp 的轨道**不是**触摸区。
 *
 * ⚠️ 本文件**不得**再出现 `androidx.compose.material3.Slider` / `SliderDefaults`。
 */
@Composable
fun GlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    valueText: String? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val colors = LocalGlassColors.current
    val backdrop = if (LocalGlassConfig.current.enableBackdropBlur) {
        LocalBackdrop.current
    } else {
        EmptyBackdrop
    }
    Column(modifier = modifier.fillMaxWidth()) {
        if (label != null || valueText != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (label != null) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (enabled) colors.onGlass else colors.onGlassSubtle,
                        // 文案已经作为滑轨的 contentDescription 播报，这里清掉语义避免被读两遍。
                        modifier = Modifier.weight(1f).clearAndSetSemantics {},
                    )
                }
                if (valueText != null) {
                    Text(
                        text = valueText,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (enabled) colors.onGlassMuted else colors.onGlassSubtle,
                        // 当前值由 ProgressBarRangeInfo 播报，同理清掉。
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
            }
        }
        LiquidSliderTrack(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            label = label,
            backdrop = backdrop,
            accentColor = if (enabled) colors.accent else colors.onGlassSubtle,
            trackColor = colors.accentMuted,
        )
    }
}

@Composable
private fun LiquidSliderTrack(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)?,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    label: String?,
    backdrop: Backdrop,
    accentColor: Color,
    trackColor: Color,
) {
    val trackBackdrop = rememberLayerBackdrop()
    val tokens = LocalGlassTokens.current

    BoxWithConstraints(
        Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart,
    ) {
        val trackWidth = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        var didDrag by remember { mutableStateOf(false) }
        // 拖动期的三条状态（按下快照 / 手势内累积 / 拖动中标记）。
        //
        // ⚠️ 拖动的唯一事实来源是**手势本身**，不是调用方回传的 value：
        // onValueChange → ViewModel StateFlow → 重组 → snapshotFlow 回显，滞后 1~2 帧。
        // 若以回显值为增量基准（旧实现），慢拖时 `snapToStep(target + 0.1)` 会被
        // 量化回原值 → 状态不变无回显 → targetValue 永不前进 → **卡死**；
        // 快甩才跨过半档跳一格 → **"抽动"**。Kyant0 原版 onDrag 里没有 snap，
        // snapToStep 是移植时加进去的 —— 增量基准必须换成手势内累积才能共存。
        var dragAccumPx by remember { mutableStateOf(0f) }
        var dragStartValue by remember { mutableStateOf(0f) }
        var sliderDragging by remember { mutableStateOf(false) }
        // 这些值在 remember 出来的回调里被读取，必须用 rememberUpdatedState 拿最新值，
        // 否则回调会闭包住第一次组合时的旧引用（滑块在列表里复用时尤其明显）。
        val currentValue by rememberUpdatedState(value)
        val currentRange by rememberUpdatedState(valueRange)
        val currentSteps by rememberUpdatedState(steps)
        val currentOnChange by rememberUpdatedState(onValueChange)
        val currentOnFinished by rememberUpdatedState(onValueChangeFinished)
        // 触感只发在用户动作位点（拖动过档 / 松手 / 提交口），
        // 绝不进下面 LaunchedEffect 里的 snapshotFlow 回显收集器。
        val haptics = rememberGlassHaptics()
        val currentHaptics by rememberUpdatedState(haptics)

        // key 用区间的两个 Float 端点，不用区间对象本身：
        // 区间对象的相等性依赖具体实现类是否重写 equals（ClosedFloatRange 重写了，是值语义），
        // 但那种依赖是隐式的 —— 本轮已栽过 4 次"以为能用实际不能用"，不靠实现细节。
        // Float 是值比较，零歧义；区间真变了才重建，字面量区间下永不重建。
        val dampedDragAnimation = remember(
            animationScope,
            currentRange.start,
            currentRange.endInclusive,
        ) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = currentValue.coerceIn(currentRange),
                valueRange = currentRange,
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 1.5f,
                onDragStarted = {
                    // 按下瞬间快照：本次手势的一切增量都从它出发（绝对映射）。
                    // ⚠️ 锚点用 **receiver 的实时 value**（thumb 此刻的真实位置），
                    // 不能用 currentValue（外部回显 prop）：回显经 StateFlow → 重组
                    // 滞后 1~2 帧，thumb 在收敛动画中/回显未落时被按下，旧锚点的
                    // 第一帧 snapValue 会把 thumb **瞬移**到回显值（"Slider 同理"的
                    // 抽动来源，2026-09-24 Wave 6b 与 LiquidBottomTabs 同批修正）。
                    dragAccumPx = 0f
                    dragStartValue = this.value
                    sliderDragging = true
                },
                onDragStopped = {
                    // ⚠️ 让位给父级滚动时**不落盘**：
                    // 判定为纵向意图之前的几帧可能有横向亚像素噪声，那时 onDrag 已被调用
                    // 过、didDrag 已置真；若照常触发 onValueChangeFinished，用户只是
                    // 想滑列表却白白落盘一次（且值其实没怎么动）。
                    // 三个条件缺一不可：
                    //   didDrag           —— 确实改过值
                    //   !yieldedToParent  —— 不是让位给父级滚动的那次
                    //   finishedNormally  —— 手势是完整走完的（不是事件流断了）
                    // 少了最后一条仍然会漏：横向拖过 8dp 后手滑成大角度斜向，
                    // latch 判纵向 → break，此时这里仍会提交一次，
                    // 而外层 toggleable 也提交一次 → 翻两次没反应。
                    if (didDrag && !yieldedToParent && finishedNormally) {
                        currentOnFinished?.invoke()
                        // 松手**统一**发一次 gestureEnd()，不再按 steps 分区。
                        // 分区（原写法只给连续滑块发）有两个实打实的漏洞：
                        //   ① 在**一档之内**拖动后松手 ⇒ 离散滑块整段手势零反馈
                        //      （它只有过档 tick，没过档就不响）；
                        //   ② `SegmentTick` 是 **API 34** 常量，Android 12/13
                        //      （API 31–33）上逐档 tick **全程静默**，而 `GestureEnd`
                        //      是 API 30 常量、minSdk 31 全版本必响 —— 分区等于让
                        //      这些机型上的离散滑块一声不吭。
                        // 连续滑块拖动中一次都不发（见 onDrag 处注释），这下是它唯一的反馈。
                        currentHaptics.gestureEnd()
                    }
                    didDrag = false
                    sliderDragging = false
                },
                onDrag = { _, dragAmount ->
                    // ⚠️ 这里不能写 `dampedDragAnimation.xxx`：这个 lambda 正是
                    // `dampedDragAnimation` 自己的初始化表达式，变量尚未在作用域里。
                    // lambda 类型是 `DampedDragAnimation.(value, dragAmount) -> Unit`，
                    // 带 receiver —— `yieldedToParent` / `snapValue` 直接走 receiver。
                    //
                    // 让位期间彻底静默：不累积、不改值、不置 didDrag。
                    // 手势已经让给父级滚动，这里的任何值变化都是
                    // "一边滚页面一边改数值"。
                    if (!yieldedToParent) {
                        dragAccumPx += dragAmount.x
                        // didDrag 只在**确实改了值**时才置真。
                        // 判据用累积位移 `abs(dragAccumPx) > 0.5f` 而不是 `!= 0f`：
                        // 纵向滑列表经过滑块时必然带亚像素横向噪声，`!= 0f` 会被
                        // 噪声置真 → 抬手白白落盘一次。0.5px 远超噪声量级。
                        if (!didDrag && abs(dragAccumPx) > 0.5f) {
                            didDrag = true
                        }
                        val range = currentRange
                        val span = range.endInclusive - range.start
                        // 绝对映射：`dragStartValue + 累积位移比例`，与回显完全解耦。
                        // （旧实现 `targetValue + delta`：target 是异步回显值，慢拖时
                        // 被 snapToStep 量化回原值 → 卡死；快甩才跨半档 → 抽动。）
                        val fraction = (dragAccumPx / trackWidth) * if (isLtr) 1f else -1f
                        val raw = dragStartValue + span * fraction
                        val snapped = snapToStep(raw.coerceIn(range), range, currentSteps)
                        // 值没变不回调：掐掉同一档位内亚像素抖动造成的无意义重入
                        // （回调 → StateFlow → 重组 → 回显，一整条链只为重复同一个值）。
                        if (snapped != currentValue) {
                            snapValue(snapped)
                            currentOnChange(snapped)
                            // 只有**离散**滑块才逐档震（HIG：走过一格给一次可分辨的反馈）。
                            // 连续滑块绝不能接在这里：steps <= 0 时 snapToStep 原值返回，
                            // 每个亚像素位移都满足 snapped != currentValue → 变成
                            // "每像素一震"的嗡鸣，HIG 明确禁止。tick() 自带 100ms 节流，
                            // 挡住快速划过多个档位时的连震。
                            if (currentSteps > 0) currentHaptics.tick()
                        }
                    }
                }
            )
        }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { currentValue }
                .collectLatest { v ->
                    // 拖动期间**压制回显**：拖动的事实来源是手势（dragStartValue +
                    // 累积位移），回显（经 StateFlow → 重组，滞后 1~2 帧）此时只会
                    // 把值往旧方向拽 → thumb 抖动 / 不跟手。松手后恢复外部状态同步。
                    if (!sliderDragging && dampedDragAnimation.targetValue != v) {
                        dampedDragAnimation.updateValue(v)
                    }
                }
        }

        // 键盘步进量：有档位走档距；连续模式按区间的 5%（肉眼可感知，又不至于一步到底）。
        fun stepSize(): Float {
            val range = currentRange
            val span = range.endInclusive - range.start
            if (span <= 0f) return 0f
            return if (currentSteps > 0) span / (currentSteps + 1) else span / 20f
        }

        /**
         * 统一的值提交口（点轨道 / 键盘 / 无障碍 SetProgress 都走这里）。
         * 返回是否真的发生了变化 —— 无障碍 action 靠它决定"这步算不算被执行了"。
         *
         * @param throttled true = 触感走 [GlassHaptics.tick] 的 100ms 节流。
         *   键盘与无障碍路径会**高频重复**触发：长按方向键时系统以 20~30Hz 自动
         *   重复发 KeyDown、TalkBack 也会连续调 SetProgress —— 不节流就是本波要防的
         *   连震嗡鸣。false = 一次性离散动作（点轨道跳转），天然一次一发，
         *   节流只会让它丢反馈。
         *   ⚠️ 判定**不**用 `event.nativeKeyEvent.repeatCount`：该 API 在 Compose
         *   1.10 的状态不确定，为一个 P2 引入新编译风险不值得。
         */
        fun commitValue(next: Float, throttled: Boolean = true): Boolean {
            if (!enabled) return false
            val range = currentRange
            val snapped = snapToStep(next.coerceIn(range), range, currentSteps)
            if (snapped == currentValue) return false
            dampedDragAnimation.animateToValue(snapped)
            currentOnChange(snapped)
            if (throttled) currentHaptics.tick() else currentHaptics.tickForced()
            currentOnFinished?.invoke()
            return true
        }

        // 触摸目标 / 语义 / 键盘 / 点轨道跳转 **全部挂在这一层**：
        // 轨道本体只有 6dp 高，手势若挂在它上面，48dp 上下留白就是"看得见点不到"，
        // 触摸目标等于白给。所以外层包一个 48dp 的 Box，轨道居中放进里面。
        Box(
            modifier = Modifier
                .semantics(mergeDescendants = true) {
                    contentDescription = label.orEmpty()
                    // Compose 的 Role 里**没有** Slider（只有 Button/Checkbox/Switch/
                    // RadioButton/Tab/Image），且 foundation 的 progressSemantics /
                    // sliderSemantics 都是 internal，所以这里手写语义：
                    // TalkBack 的「增加 / 减少」正是从 ProgressBarRangeInfo + SetProgress
                    // 推导出来的，不需要额外的自定义 action。
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = currentValue.coerceIn(currentRange),
                        range = currentRange.start..currentRange.endInclusive,
                        steps = currentSteps
                    )
                    // 禁用态必须如实表达：打上 disabled()，并且 SetProgress 直接失败。
                    if (!enabled) disabled()
                    setProgress { target ->
                        if (!enabled) return@setProgress false
                        commitValue(target)
                    }
                }
                .focusable(enabled = enabled)
                .onKeyEvent { event: KeyEvent ->
                    if (!enabled || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val step = stepSize()
                    if (step == 0f) return@onKeyEvent false
                    when (event.key) {
                        // 左右键跟随布局方向（RTL 下语义反过来），上下键固定"下减上增"。
                        Key.DirectionLeft -> commitValue(currentValue - step * if (isLtr) 1f else -1f)
                        Key.DirectionRight -> commitValue(currentValue + step * if (isLtr) 1f else -1f)
                        Key.DirectionDown -> commitValue(currentValue - step)
                        Key.DirectionUp -> commitValue(currentValue + step)
                        Key.MoveHome -> commitValue(currentRange.start)
                        Key.MoveEnd -> commitValue(currentRange.endInclusive)
                        else -> false
                    }
                }
                .heightIn(min = tokens.minTouchTarget)
                .fillMaxWidth()
                .pointerInput(animationScope) {
                    detectTapGestures { position ->
                        // ⚠️ 两种情况抬手**都不要**当成点击跳值：
                        // 1. 让位给父级滚动 —— 用户是"按在滑块上想滑列表"，不是
                        //    "点轨道某个位置"。detectTapGestures 有自己的 slop，大幅
                        //    滑动本来就不会触发 onTap；这条挡的是小幅斜滑（未过它
                        //    slop）却已判定为纵向意图的边界情况。
                        // 2. 拖动后抬手 —— 拖动位移可能未过 detectTapGestures 自己的
                        //    slop（比如慢速拖半档又拖回来），此时 onTap 会触发
                        //    commitValue(按下点) → **thumb 跳回按下点**（"抽动"的
                        //    第二条来源）。didDrag 为真就说明这次交互是拖，不是点。
                        if (dampedDragAnimation.yieldedToParent || didDrag) return@detectTapGestures
                        val range = currentRange
                        val delta = (range.endInclusive - range.start) * (position.x / trackWidth)
                        val target =
                            if (isLtr) range.start + delta
                            else range.endInclusive - delta
                        // 点轨道跳转：一次性离散动作，不受节流。
                        // （键盘 / 无障碍路径走默认的节流版本，防长按连震。）
                        commitValue(target, throttled = false)
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            // 轨道本体：录进 trackBackdrop，供 thumb 做"透过玻璃看轨道"的折射。
            Box(Modifier.layerBackdrop(trackBackdrop)) {
                Box(
                    Modifier
                        .clip(Capsule)
                        .background(trackColor)
                        .height(6f.dp)
                        .fillMaxWidth(),
                )
                Box(
                    Modifier
                        .clip(Capsule)
                        .background(accentColor)
                        .height(6f.dp)
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            val width =
                                (constraints.maxWidth * dampedDragAnimation.progress).roundToInt()
                            layout(width, placeable.height) {
                                placeable.place(0, 0)
                            }
                        },
                )
            }
        }

        // Thumb：真玻璃 + 跟手各向异性拉伸。
        Box(
            Modifier
                .graphicsLayer {
                    translationX =
                        (-size.width / 2f + trackWidth * dampedDragAnimation.progress)
                            .coerceIn(-size.width / 4f, trackWidth - size.width * 3f / 4f) *
                            if (isLtr) 1f else -1f
                }
                .then(if (enabled) dampedDragAnimation.modifier else Modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(
                        backdrop,
                        rememberBackdrop(trackBackdrop) { drawBackdrop ->
                            val progress = dampedDragAnimation.pressProgress
                            val scaleX = 2f / 3f + (1f - 2f / 3f) * progress
                            val scaleY = progress
                            scale(scaleX, scaleY) {
                                drawBackdrop()
                            }
                        }
                    ),
                    shape = { Capsule },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        blur(8f.dp.toPx() * (1f - progress))
                        lens(
                            10f.dp.toPx() * progress,
                            14f.dp.toPx() * progress,
                            chromaticAberration = true
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        Highlight.Ambient.copy(
                            width = Highlight.Ambient.width / 1.5f,
                            blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                            alpha = progress
                        )
                    },
                    shadow = {
                        Shadow(
                            radius = 4f.dp,
                            color = Color.Black.copy(alpha = 0.05f)
                        )
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(
                            radius = 4f.dp * progress,
                            alpha = progress
                        )
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        // 快速拖动时沿拖动方向拉长、垂直方向压扁 —— 液态感最强的一处。
                        val velocity = dampedDragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).coerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).coerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(Color.White.copy(alpha = 1f - progress))
                    }
                )
                .size(40f.dp, 24f.dp),
        )
    }
}

/**
 * 按 [steps] 吸附。
 *
 * [steps] 是 min..max 之间的**档数**（与 M3 语义一致）：steps = 30 表示
 * 区间被等分成 31 份。steps <= 0 表示连续。
 */
private fun snapToStep(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int
): Float {
    if (steps <= 0) return value
    val span = range.endInclusive - range.start
    if (span <= 0f) return range.start
    val stepSize = span / (steps + 1)
    return (range.start + ((value - range.start) / stepSize).roundToInt() * stepSize)
        .coerceIn(range)
}
