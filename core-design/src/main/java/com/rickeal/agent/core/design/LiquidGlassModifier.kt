package com.rickeal.agent.core.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CornerRadius
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.random.Random

/**
 * 纯函数版 `liquidGlass`：所有视觉输入显式传入。
 *
 * 绘制顺序（自下而上）：
 *   1. content（已由 clip 裁剪到圆角内）
 *   2. 背景折射光斑（把壁纸光斑场按自身尺寸缩放后画进来 —— 视觉上"透出背后壁纸"）
 *   3. 玻璃底色（上亮下暗垂直渐变）
 *   4. 内描边（顶亮底暗，1.5dp）
 *   5. 顶部折射高光带（上 1/3 区域柔和白色渐变）
 *   6. 底部接触阴影
 *   7. 噪点微纹理（固定种子，避免大面积纯色的"塑料感"）
 *
 * 本 Modifier **自带 clip**，调用方无需再 `.clip()`（重复 clip 也无害）。
 */
fun Modifier.liquidGlass(
    tokens: GlassTokens,
    colors: GlassColorScheme,
    backdrop: GlassBackdrop,
    material: GlassMaterial = GlassMaterial.REGULAR,
    cornerRadius: Dp = GlassDefaults.RadiusLg,
    intensity: Float = 1f,
    noise: Boolean = true,
    specular: Boolean = true,
): Modifier = this
    .clip(RoundedCornerShape(cornerRadius))
    .drawWithCache {
        val spec = GlassMaterials.of(material)
        val height = size.height.coerceAtLeast(1f)
        val width = size.width.coerceAtLeast(1f)
        val radiusPx = cornerRadius.toPx()
            .coerceAtMost(minOf(size.width, size.height) / 2f)
            .coerceAtLeast(0f)
        val corner = CornerRadius(radiusPx, radiusPx)
        val safeIntensity = intensity.coerceIn(0f, 1.5f)

        // 1) 玻璃底色：上亮下暗的垂直渐变
        val baseAlpha = (spec.backgroundAlpha * safeIntensity).coerceIn(0f, 1f)
        val fillBrush = Brush.verticalGradient(
            colors = listOf(
                colors.glassTint.copy(alpha = baseAlpha),
                colors.glassTintElevated.copy(alpha = (baseAlpha * 0.72f).coerceIn(0f, 1f)),
            ),
            startY = 0f,
            endY = height,
        )

        // 2) 背景折射：把壁纸光斑场按自身尺寸缩放后画进来
        val refractionAlpha = (spec.refractionAlpha * safeIntensity).coerceIn(0f, 1f)
        val refracted = backdrop.blobs.map { blob ->
            RefractionBlob(
                center = Offset(blob.x * width, blob.y * height),
                radius = blob.radiusFraction * max(width, height) * 0.5f,
                brush = Brush.radialGradient(
                    colors = listOf(blob.color.copy(alpha = refractionAlpha), Color.Transparent),
                ),
            )
        }

        // 3) 内描边：顶亮底暗
        val borderAlpha = (spec.borderAlpha * safeIntensity).coerceIn(0f, 1f)
        val borderBrush = Brush.verticalGradient(
            colors = listOf(
                colors.glassBorderTop.copy(alpha = borderAlpha),
                Color.Transparent,
                colors.glassBorderBottom.copy(alpha = (borderAlpha * 0.8f).coerceIn(0f, 1f)),
            ),
            startY = 0f,
            endY = height,
        )

        // 4) 顶部折射高光带
        val specularBrush = Brush.verticalGradient(
            colors = listOf(
                colors.glassSpecular.copy(alpha = (spec.specularAlpha * safeIntensity).coerceIn(0f, 1f)),
                Color.Transparent,
            ),
            startY = 0f,
            endY = (height * tokens.specularBandRatio.coerceIn(0.05f, 0.9f)).coerceAtLeast(1f),
        )

        // 5) 底部接触阴影（让玻璃"坐"在壁纸上，而不是浮在贴纸上）
        val contactBrush = Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                colors.glassShadow.copy(alpha = 0.22f * safeIntensity),
            ),
            startY = height * 0.66f,
            endY = height,
        )

        // 6) 噪点微纹理（固定种子，尺寸变化时重建；数量与尺寸无关，开销恒定）
        val noisePoints = if (noise) buildNoisePoints(width, height, 150) else emptyList()
        val noisePaint = Paint().apply {
            this.color = colors.glassSpecular.copy(alpha = spec.noiseAlpha * safeIntensity)
            this.strokeWidth = 1.5f
            this.strokeCap = StrokeCap.Round
        }

        val strokeWidthPx = tokens.highlightStrokeWidth.toPx().coerceAtLeast(0.5f)
        val halfStroke = strokeWidthPx * 0.5f

        onDrawWithContent {
            drawContent()
            for (blob in refracted) {
                drawCircle(brush = blob.brush, center = blob.center, radius = blob.radius)
            }
            drawRoundRect(brush = fillBrush, cornerRadius = corner)
            drawRoundRect(brush = contactBrush, cornerRadius = corner)
            drawRoundRect(
                brush = borderBrush,
                topLeft = Offset(halfStroke, halfStroke),
                size = Size(
                    width = (width - strokeWidthPx).coerceAtLeast(0f),
                    height = (height - strokeWidthPx).coerceAtLeast(0f),
                ),
                cornerRadius = CornerRadius(
                    x = (radiusPx - halfStroke).coerceAtLeast(0f),
                    y = (radiusPx - halfStroke).coerceAtLeast(0f),
                ),
                style = Stroke(width = strokeWidthPx),
            )
            if (specular) {
                drawRoundRect(
                    brush = specularBrush,
                    topLeft = Offset(strokeWidthPx, strokeWidthPx),
                    size = Size(
                        width = (width - strokeWidthPx * 2).coerceAtLeast(0f),
                        height = (height * tokens.specularBandRatio - strokeWidthPx).coerceAtLeast(0f),
                    ),
                    cornerRadius = CornerRadius(
                        x = (radiusPx * 0.85f).coerceAtLeast(0f),
                        y = (radiusPx * 0.85f).coerceAtLeast(0f),
                    ),
                )
            }
            if (noisePoints.isNotEmpty()) {
                drawPoints(
                    points = noisePoints,
                    pointMode = PointMode.Points,
                    paint = noisePaint,
                )
            }
        }
    }

/**
 * Composable 版：从 CompositionLocal 取 tokens/colors/backdrop/config。UI 代码一律用这个。
 * （刻意不用 Modifier.composed —— 该 API 在新版 Compose 里已不推荐，用 @Composable 扩展更安全。）
 */
@Composable
fun Modifier.liquidGlass(
    material: GlassMaterial = GlassMaterial.REGULAR,
    cornerRadius: Dp = GlassDefaults.RadiusLg,
    intensity: Float = 1f,
    noise: Boolean = true,
    specular: Boolean = true,
): Modifier {
    val tokens = LocalGlassTokens.current
    val colors = LocalGlassColors.current
    val backdrop = LocalGlassBackdrop.current
    val config = LocalGlassConfig.current
    // 真实背景模糊必须先于 liquidGlass 应用（见 GlassBackdropBlur.kt 的说明）
    val blurred = this.glassBackdropBlur(
        radius = tokens.blurRadius * config.intensity,
        cornerRadius = cornerRadius,
        enabled = config.enableBackdropBlur,
    )
    return blurred.liquidGlass(
        tokens = tokens,
        colors = colors,
        backdrop = backdrop,
        material = material,
        cornerRadius = cornerRadius,
        intensity = intensity * config.intensity,
        noise = noise && config.enableNoise,
        specular = specular && config.enableSpecular,
    )
}

/**
 * 液态按压反馈：按下时整体缩放到 pressScale 再回弹。
 * 与 `liquidGlass` 组合使用即可获得 iOS 27 的"果冻"手感。
 */
@Composable
fun Modifier.liquidPress(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
): Modifier {
    val motion = LocalLiquidMotion.current
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (enabled && pressed) motion.pressScale else 1f,
        animationSpec = LiquidMotion.floatSpring(motion),
        label = "liquidPress",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

private class RefractionBlob(
    val center: Offset,
    val radius: Float,
    val brush: Brush,
)

private fun buildNoisePoints(width: Float, height: Float, count: Int): List<Offset> {
    val random = Random(2026)
    return List(count) {
        Offset(random.nextFloat() * width, random.nextFloat() * height)
    }
}
