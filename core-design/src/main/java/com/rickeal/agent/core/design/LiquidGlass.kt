package com.rickeal.agent.core.design

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.random.Random

/** 把 Dp 圆角转成 Compose 的连续曲率圆角形状。 */
fun glassShape(cornerRadius: Dp): Shape = RoundedCornerShape(cornerRadius)

/**
 * Liquid Glass 的核心 Modifier（非组合版，显式传参）。
 *
 * 绘制顺序（自下而上）：
 * 1. 投影（shadow）
 * 2. 合成壁纸光斑（伪背景模糊：径向渐变叠加，观感接近真实模糊，零额外开销）
 * 3. 玻璃 tint + 折射渐变
 * 4. specular 高光带
 * 5. 噪声微纹理（抑制大面积渐变的色带）
 * 6. 内容（drawContent）
 * 7. 上下渐变描边（上亮下暗，玻璃的边缘感）
 */
fun Modifier.liquidGlass(
    tokens: GlassTokens,
    colors: GlassColorScheme,
    backdrop: GlassBackdrop,
    material: GlassMaterial = GlassMaterial.REGULAR,
    cornerRadius: Dp = tokens.radiusLg,
    intensity: Float = 1f,
    noise: Boolean = true,
    specular: Boolean = true,
): Modifier {
    val spec = GlassMaterials.of(material)
    val shape = glassShape(cornerRadius)
    val scale = intensity.coerceIn(0.35f, 1.6f)
    return this
        .shadow(
            elevation = spec.shadowElevation * scale,
            shape = shape,
            clip = false,
            ambientColor = colors.glassShadow,
            spotColor = colors.glassShadow,
        )
        .clip(shape)
        .drawWithCache {
            val width = size.width
            val height = size.height
            val radiusPx = cornerRadius.toPx()
            val corner = CornerRadius(radiusPx, radiusPx)
            val strokePx = tokens.borderWidth.toPx()
            val specularEnd = height * tokens.specularBandRatio
            val noisePoints = if (noise) buildNoisePoints(width, height) else emptyList()
            val tintAlpha = (spec.backgroundAlpha * scale).coerceIn(0f, 1f)
            val refractionAlpha = (spec.refractionAlpha * scale).coerceIn(0f, 1f)
            val specularAlpha = (spec.specularAlpha * scale).coerceIn(0f, 1f)
            val borderAlpha = (spec.borderAlpha * scale).coerceIn(0f, 1f)
            val noiseAlpha = (spec.noiseAlpha * scale).coerceIn(0f, 0.08f)

            onDrawWithContent {
                // 1) 合成壁纸（伪背景模糊）
                drawGlassBackdrop(backdrop = backdrop, alpha = 1f)
                // 2) 玻璃本体
                drawRoundRect(
                    color = colors.glassTint,
                    cornerRadius = corner,
                    alpha = tintAlpha,
                )
                // 3) 折射渐变：上缘更亮，向下衰减
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(colors.glassTintElevated, Color.Transparent),
                        startY = 0f,
                        endY = if (height > 0f) height else 1f,
                    ),
                    cornerRadius = corner,
                    alpha = refractionAlpha,
                )
                // 4) 高光带
                if (specular && specularEnd > 0f) {
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(colors.glassSpecular, Color.Transparent),
                            startY = 0f,
                            endY = specularEnd,
                        ),
                        cornerRadius = corner,
                        alpha = specularAlpha,
                    )
                }
                // 5) 噪声微纹理
                if (noisePoints.isNotEmpty()) {
                    drawPoints(
                        points = noisePoints,
                        pointMode = PointMode.Points,
                        color = colors.onGlass,
                        strokeWidth = 1.2f,
                        alpha = noiseAlpha,
                    )
                }
                // 6) 内容
                drawContent()
                // 7) 描边
                if (strokePx > 0f) {
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(colors.glassBorderTop, colors.glassBorderBottom),
                            startY = 0f,
                            endY = if (height > 0f) height else 1f,
                        ),
                        cornerRadius = corner,
                        style = Stroke(width = strokePx),
                        alpha = borderAlpha,
                    )
                }
            }
        }
}

/** 合成壁纸：把光斑画成柔和的径向渐变，等效于「模糊后的背景」。 */
private fun DrawScope.drawGlassBackdrop(backdrop: GlassBackdrop, alpha: Float) {
    val minSide = min(size.width, size.height)
    for (blob in backdrop.blobs) {
        val center = Offset(blob.x * size.width, blob.y * size.height)
        val radius = (blob.radiusFraction * minSide).coerceAtLeast(1f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(blob.color, Color.Transparent),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
            alpha = alpha,
        )
    }
}

/** 确定性伪随机噪声点，避免每帧重建导致重组开销。 */
private fun buildNoisePoints(width: Float, height: Float): List<Offset> {
    if (width <= 0f || height <= 0f) return emptyList()
    val count = ((width * height) / 2600f).toInt().coerceIn(24, 320)
    val random = Random(0x5EED)
    return List(count) {
        Offset(random.nextFloat() * width, random.nextFloat() * height)
    }
}

/** 玻璃默认圆角：大圆角是 iOS 27 的识别特征。 */
val GlassDefaultCornerRadius: Dp = 28.dp
