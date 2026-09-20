package com.rickeal.agent.core.design.liquid.effects

import com.rickeal.agent.core.design.liquid.BackdropEffectScope
import com.rickeal.agent.core.design.liquid.internal.opacity
import com.rickeal.agent.core.design.liquid.internal.vibrancy
import com.rickeal.agent.core.design.liquid.platform.isRenderEffectSupported

/**
 * **Vibrancy（鲜艳度提升）** —— iOS 26 Liquid Glass 的标配。
 *
 * 玻璃会"吸走"背景的饱和度，看起来发灰。vibrancy 用 ColorMatrix 把饱和度拉回来，
 * 让透过玻璃看到的颜色依然鲜活。这是"廉价磨砂"与"高级液态玻璃"的分水岭之一。
 *
 * @param saturation 饱和度系数，1f = 不变，1.5f = 推荐值（与 Kyant0 默认一致）。
 * @param brightness 亮度偏移（0~1 之外的加值）。
 */
fun BackdropEffectScope.vibrancy(saturation: Float = 1.5f, brightness: Float = 0f) {
    if (!isRenderEffectSupported()) return
    renderEffect = renderEffect.vibrancy(saturation, brightness)
}

/**
 * 给背景叠加 alpha 透明度（线性，作用在整个 RenderEffect 链上）。
 *
 * 典型用途：按按压进度把玻璃**调暗**（`opacity(1f - pressProgress * 0.3f)`），
 * 做出"按下去玻璃变实"的反馈。
 */
fun BackdropEffectScope.opacity(alpha: Float) {
    if (!isRenderEffectSupported()) return
    renderEffect = renderEffect.opacity(alpha)
}
