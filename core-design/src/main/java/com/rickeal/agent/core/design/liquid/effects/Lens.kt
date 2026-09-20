package com.rickeal.agent.core.design.liquid.effects

import androidx.compose.ui.unit.LayoutDirection
import com.rickeal.agent.core.design.liquid.BackdropEffectScope
import com.rickeal.agent.core.design.liquid.RoundedRectRefractionShader
import com.rickeal.agent.core.design.liquid.RoundedRectRefractionWithDispersionShader
import com.rickeal.agent.core.design.liquid.internal.applyRuntimeShader
import com.rickeal.agent.core.design.liquid.platform.isRuntimeShaderSupported

/**
 * 液态玻璃的**折射（lens）**效果 —— 让"毛玻璃"变成"液态玻璃"的核心。
 *
 * 与 [blur] 的区别：
 *  - `blur` 只是把背景**糊掉**，是"磨砂玻璃"；
 *  - `lens` 按圆角矩形的 SDF 高度场**弯折**背景采样坐标，
 *    边缘形成厚度渐变的弯折带 —— 这才是 iOS 26 Liquid Glass 那种"果冻/水滴"观感。
 *
 * @param refractionHeight 折射带的像素高度（从边缘向内算）。典型 8~24 dp。
 * @param refractionAmount 折射强度（像素位移量）。典型 16~32 dp。越大越"鼓"。
 * @param depthEffect 是否叠加"向外凸"的深度感（沿中心方向的额外弯折）。
 * @param chromaticAberration 是否开启**色散**（RGB 分离 → 边缘彩虹色带）。
 *   这是"看起来真的像玻璃"的最强视觉信号，但开销约为纯折射的 7 倍（7 次采样）。
 *   建议只在大面积容器（Dialog / 卡片）开启，列表 item / Chip 关闭。
 *
 * 端口自 Kyant0 backdrop 库（Apache-2.0）。
 */
fun BackdropEffectScope.lens(
    refractionHeight: Float,
    refractionAmount: Float,
    depthEffect: Boolean = false,
    chromaticAberration: Boolean = false
) {
    if (!isRuntimeShaderSupported()) return
    if (refractionHeight <= 0f || refractionAmount <= 0f) return

    if (padding > 0f) {
        padding = (padding - refractionHeight).coerceAtLeast(0f)
    }

    val cornerRadii = cornerRadii ?: return
    val shader =
        if (!chromaticAberration) {
            obtainRuntimeShader(
                "Refraction",
                RoundedRectRefractionShader
            )
        } else {
            obtainRuntimeShader(
                "RefractionWithDispersion",
                RoundedRectRefractionWithDispersionShader
            )
        }
    shader.apply {
        setFloatUniform("size", size.width, size.height)
        setFloatUniform("offset", -padding, -padding)
        setFloatUniform("cornerRadii", cornerRadii)
        setFloatUniform("refractionHeight", refractionHeight)
        setFloatUniform("refractionAmount", -refractionAmount)
        setFloatUniform("depthEffect", if (depthEffect) 1f else 0f)
        if (chromaticAberration) {
            setFloatUniform("chromaticAberration", 1f)
        }
    }
    renderEffect = renderEffect.applyRuntimeShader(shader, "content")
}

/**
 * 从当前 shape 提取四角半径（FloatArray: topLeft / topRight / bottomRight / bottomLeft）。
 *
 * 只支持 `AbsoluteRoundedCornerShape` 与 `CornerBasedShape`（Compose foundation 自带），
 * 返回 null 表示该形状不是圆角矩形 —— 此时 [lens] 直接跳过（不崩，退化为纯模糊）。
 *
 * 注：Kyant0 原版还支持 `com.kyant.shapes.RoundedRectangularShape`（第三方形状库），
 * 本项目按「禁止新增依赖」铁律不支持它。
 */
private val BackdropEffectScope.cornerRadii: FloatArray?
    get() = when (val shape = shape) {
        is androidx.compose.foundation.shape.AbsoluteRoundedCornerShape -> {
            val size = size
            val maxRadius = size.minDimension / 2f
            val topLeft = shape.topStart.toPx(size, this)
            val topRight = shape.topEnd.toPx(size, this)
            val bottomRight = shape.bottomEnd.toPx(size, this)
            val bottomLeft = shape.bottomStart.toPx(size, this)
            floatArrayOf(
                topLeft.coerceAtMost(maxRadius),
                topRight.coerceAtMost(maxRadius),
                bottomRight.coerceAtMost(maxRadius),
                bottomLeft.coerceAtMost(maxRadius)
            )
        }

        is androidx.compose.foundation.shape.CornerBasedShape -> {
            val size = size
            val maxRadius = size.minDimension / 2f
            val isLtr = layoutDirection == LayoutDirection.Ltr
            // CornerBasedShape 内部字段是 topStart/topEnd/bottomStart/bottomEnd，
            // 但公开属性只有 topStart 等，AabsoluteRounded 才有 topLeft。
            // 这里用 topStart/topEnd/bottomStart/bottomEnd 做 RTL 映射。
            val topStart = shape.topStart.toPx(size, this)
            val topEnd = shape.topEnd.toPx(size, this)
            val bottomEnd = shape.bottomEnd.toPx(size, this)
            val bottomStart = shape.bottomStart.toPx(size, this)
            val (topLeft, topRight) = if (isLtr) topStart to topEnd else topEnd to topStart
            val (bottomLeft, bottomRight) = if (isLtr) bottomStart to bottomEnd else bottomEnd to bottomStart
            floatArrayOf(
                topLeft.coerceAtMost(maxRadius),
                topRight.coerceAtMost(maxRadius),
                bottomRight.coerceAtMost(maxRadius),
                bottomLeft.coerceAtMost(maxRadius)
            )
        }

        else -> null
    }
