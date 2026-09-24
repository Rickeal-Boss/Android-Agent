package com.rickeal.agent.core.design.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import kotlin.math.hypot

/**
 * 圆形揭示转场：从某个原点做圆形展开/收起（F3 风格），逐帧只有 draw 失效、**零重组**。
 *
 * 移植自 CAM-P `ui/effects/CircularRevealModifier.kt` + `CircularRevealState.kt`
 * （android-native-dev skill `references/animations.md` §2.1，Apache-2.0 自家产出）。
 *
 * ## 零重组的关键
 *
 * `progress` / `origin` 以 `() -> T` lambda 捕获 [CircularRevealState] 引用：
 * 数值变化只在 draw 阶段现读 `Animatable.value`，**不进入 modifier 链**——若解构成
 * `Float` 值传入，每次动画帧都会触发使用点重组（CAM-P 性能清单第 1 条）。
 *
 * ## 快速路径
 *
 *  - `t >= 1f` → 直接 `drawContent()`，无 `clipPath` 开销（动画结束后恒走此路）；
 *  - `t <= 0f` → 不绘制任何内容（完全收起）。
 *
 * ## 时序
 *
 * 进入/退出均为 700ms + FastOutSlowIn（Material 标准圆形展开曲线），刻意不用
 * spring——转场时序要可预测，回弹过冲会干扰后续动作的起点。
 */
fun Modifier.circularReveal(
    progress: () -> Float,
    origin: () -> Offset,
): Modifier = drawWithCache {
    val path = Path()
    val maxRadius = run {
        val p = origin()
        floatArrayOf(
            hypot(p.x, p.y),
            hypot(size.width - p.x, p.y),
            hypot(p.x, size.height - p.y),
            hypot(size.width - p.x, size.height - p.y),
        ).maxOrNull() ?: 0f
    }

    onDrawWithContent {
        val t = progress()
        when {
            t <= 0f -> Unit
            t >= 1f -> drawContent()
            else -> {
                // path 在 drawWithCache 作用域复用（rewind 而非重建）：动画帧不分配。
                path.rewind()
                path.addOval(Rect(center = origin(), radius = maxRadius * t))
                clipPath(path) { this@onDrawWithContent.drawContent() }
            }
        }
    }
}

/**
 * [circularReveal] 的驱动状态：进入/退出双 tween（精确控时，无过冲）。
 *
 * `expand` / `collapse` 是挂起函数，需在协程里调用（`rememberCoroutineScope` /
 * `LaunchedEffect`）。[origin] 在 `expand` 时写入，draw 阶段经 lambda 现读。
 */
@Stable
class CircularRevealState(
    initialProgress: Float = 0f,
    private val expandSpec: AnimationSpec<Float> =
        tween(durationMillis = 700, easing = FastOutSlowInEasing),
    private val collapseSpec: AnimationSpec<Float> =
        tween(durationMillis = 700, easing = FastOutSlowInEasing),
) {
    val progress = Animatable(initialProgress)

    var origin: Offset = Offset.Zero
        internal set

    val isRevealing: Boolean get() = progress.value > 0.01f

    /** 从原点 [o] 圆形展开到全显。 */
    suspend fun expand(o: Offset) {
        origin = o
        progress.animateTo(1f, expandSpec)
    }

    /** 收起到全隐。 */
    suspend fun collapse() {
        progress.animateTo(0f, collapseSpec)
    }
}

/** 记住 [CircularRevealState]（组合级；跨重组保留，Activity 重建后重置）。 */
@Composable
fun rememberCircularRevealState(initialProgress: Float = 0f): CircularRevealState =
    remember { CircularRevealState(initialProgress) }
