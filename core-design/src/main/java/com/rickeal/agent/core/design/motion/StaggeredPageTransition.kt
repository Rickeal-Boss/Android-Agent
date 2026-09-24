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
 * 交错页面转场（页签切换级联）：页面内的固定结构块按 [itemIndex] 依次错峰
 * 渐显 + 从下方 16dp 抬升归位——顶部块领动，越靠后越晚，形成"内容追上整页"的鞭梢感。
 *
 * 移植自 CAM-P `ui/effects/StaggeredPageTransition.kt` 的**泛化改编**
 * （android-native-dev skill `references/animations.md` §2.3，Apache-2.0 自家产出）。
 *
 * ## 为什么不是参考原文的 Pager 版（改编依据，勿"改回"）
 *
 * 原文实现绑定 `HorizontalPager`：一个共享 `Animatable` 弹簧 + `CompositionLocal`
 * 下发 `State<Float>`，卡片做相位映射——那是为**连续拖动**（每帧更新、27 卡并发）
 * 设计的。本仓没有任何 Pager，页面切换是 NavHost 的**离散事件**（人手点击频率），
 * 两点决定改编形态：
 *
 *  1. **每 item 一个一次性 `Animatable`**（与 [entranceReveal] 同构）：离散切换下
 *     并发弹簧数量 = 页内固定块数（3~6 个），且只在切页的 ~500ms 内活动，性能无虞。
 *     参考 §2.3"单弹簧"的性能教训针对的是拖动期逐帧驱动，此处不适用。
 *  2. **不做共享 Provider**：共享状态按新页 key 重建时，NavHost **正在退场的旧页**
 *     （exit 过渡 ~300ms 仍组合着）会读到新实例的 progress=0 而整体跳变。
 *     每 item 独立持状态则旧页的动画早已收敛在 1f，退场全程 identity，零跳变。
 *
 * ## 重播语义（自动、无需 playKey）
 *
 * `navigateTop` 的 saveState/restoreState 恢复的是 SavedState（rememberSaveable），
 * **普通 `remember` 不在恢复范围**——每次切回该页都是全新组合，`Animatable` 从 0
 * 重新播放；页内其它状态变化只重组不重播（remember 存活）。这正是"每次切页都
 * 级联、页内操作不闪"的语义，天然获得，不需要调用方传 key。
 *
 * ## 采纳范围与约束
 *
 *  - 只挂**固定结构块**（页头 / 卡片容器），**禁止挂 LazyColumn 的 item**——
 *     懒加载项的 index 随滚动回收变化，错峰序号会错乱（itemIndex 语义是"版面序"，
 *     不是"数据序"）。
 *  - 幅度刻意收敛（16dp / 420ms / 80ms 步进）：NavHost 整页已有 300ms slide+fade，
 *     级联是"内容追上整页"的第二层，幅度大了两层运动会打架。
 */
private const val PAGE_ITEM_DURATION_MS = 420
private const val PAGE_ITEM_STAGGER_MS = 80f
private const val PAGE_ITEM_DEFER_MS = 32L
private val PAGE_ITEM_OFFSET = 16.dp

@Stable
@Composable
fun Modifier.staggeredPageItem(itemIndex: Int): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(PAGE_ITEM_DEFER_MS)
        delay((itemIndex * PAGE_ITEM_STAGGER_MS).toLong())
        progress.animateTo(1f, tween(PAGE_ITEM_DURATION_MS, easing = FastOutSlowInEasing))
    }
    val density = LocalDensity.current
    val offsetPx = PAGE_ITEM_OFFSET.value * density.density
    return this.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * offsetPx
    }
}
