package com.rickeal.agent.core.design.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 入场揭示：首屏内容按 [order]（元素序号）错峰渐显 + 从下方抬升归位。
 *
 * 移植自 CAM-P `ui/effects/EntranceReveal.kt`
 * （android-native-dev skill `references/animations.md` §2.2，Apache-2.0 自家产出）。
 *
 * ## 三个性能要点（照抄 CAM-P 实测教训）
 *
 *  1. **动画只更新 `graphicsLayer`（alpha + translationY），不重组 content**：
 *     `graphicsLayer` 的 lambda 是绘制属性作用域，读 `Animatable.value` 只触发
 *     draw 失效。
 *  2. **`@Composable` 扩展函数而非 `composed { }` 包装**：`composed` 会在每个
 *     使用点每次重组注入组合边界，纯"读值即画"的 modifier 用不上它，纯属浪费。
 *  3. **进程级一次性**：文件级 [entrancePlayed] 标记保证只有冷启动的第一次
 *     composition 播放错峰；应用内导航 / 回前台重组一律首帧即 `alpha=1`，无闪烁。
 *     标记不随配置变更重置（进程级意图）——旋转后不重播是特性不是缺陷。
 *
 * ## 与 [staggeredPageItem] 的分工
 *
 * 本 modifier 服务**冷启动**（进程内只播一次）；页签切换级联用
 * [staggeredPageItem]（每个页面组合独立重播）。两者同构但重播策略不同，
 * 不要混用——冷启动动画若挂在页签切换路径上，每次切页都会"闪一遍入场"。
 */
private const val REVEAL_DURATION_MS = 840
private const val REVEAL_STAGGER_MS = 140f
private const val FIRST_FRAME_DEFER_MS = 32L
private val REVEAL_OFFSET = 18.dp

/** 进程级一次性标记：冷启动第一次 composition 播放后置真。 */
private var entrancePlayed = false

@Stable
@Composable
fun Modifier.entranceReveal(order: Int = 0): Modifier {
    val progress = remember { Animatable(if (entrancePlayed) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (entrancePlayed) return@LaunchedEffect
        delay(FIRST_FRAME_DEFER_MS)
        delay((order * REVEAL_STAGGER_MS).toLong())
        progress.animateTo(1f, tween(REVEAL_DURATION_MS, easing = FastOutSlowInEasing))
        entrancePlayed = true
    }
    val density = LocalDensity.current
    val offsetPx = REVEAL_OFFSET.value * density.density
    return this.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * offsetPx
    }
}
