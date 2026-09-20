package com.rickeal.agent.core.design

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 「背景模糊」用户开关（UI-07）。
 *
 * 真实背景模糊是**最贵**的一项渲染开销：每个玻璃节点每帧都要离屏录制一次背景，
 * 再跑一次 Skia 高斯模糊（半径折算 42~120 px）。一屏十几个节点 × 60fps，
 * 中低端机会持续掉帧。所以它**必须**有一个用户能关掉它的入口 ——
 * 关掉后玻璃仍有底色渐变 + 内描边 + 边缘光，是观感降级，不是崩溃。
 *
 * ## 为什么这里是进程内的 object，而不是跟其它外观项一起进 `ThemeState`
 *
 * `ThemeState` 在 `:core-data`，本轮 UI 施工不越界改它（分工边界）。
 * 代价是这个开关**进程内有效、重启后回到默认 true**。
 *
 * core-data 侧加上 `ThemeState.enableBackdropBlur` 之后，迁移只需要两处：
 *  1. `LiquidAgentApp` 里 `enableBackdropBlur = GlassBackdropBlurOverride.enabled`
 *     改成 `enableBackdropBlur = themeState.enableBackdropBlur`；
 *  2. 设置页的 `onCheckedChange` 改成 `viewModel.onThemeChange(...copy(enableBackdropBlur = it))`。
 */
object GlassBackdropBlurOverride {
    /** 是否启用真实背景模糊。默认 true（保留液态玻璃的真实观感）。 */
    var enabled: Boolean by mutableStateOf(true)
        private set

    fun setEnabled(value: Boolean) {
        enabled = value
    }
}
