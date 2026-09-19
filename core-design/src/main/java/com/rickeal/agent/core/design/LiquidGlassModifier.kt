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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 纯函数版 `liquidGlass`：所有视觉输入显式传入。
 *
 * 绘制顺序（自下而上）：
 *   0. 模糊背景层（[backdropBlur]，画在内容之前，所以内容永远清晰）
 *   1. content（已由 clip 裁剪到圆角内）
 *   2. 玻璃底色（上亮下暗垂直渐变）
 *   3. 底部接触阴影
 *   4. 内描边（顶亮底暗，1.5dp）
 *   5. 方向性边缘光（沿形状内缘的柔和环形高光，按 specularAngle 定向）
 *   6. 噪点微纹理（固定种子，避免大面积纯色的"塑料感"）
 *
 * 本 Modifier **自带 clip**，调用方无需再 `.clip()`（重复 clip 也无害）。
 * [backdropBlur] 会被插到 clip 之内、drawWithCache 之前，因此模糊背景同样被圆角裁剪。
 */
fun Modifier.liquidGlass(
    tokens: GlassTokens,
    colors: GlassColorScheme,
    material: GlassMaterial = GlassMaterial.REGULAR,
    cornerRadius: Dp = GlassDefaults.RadiusLg,
    intensity: Float = 1f,
    noise: Boolean = true,
    specular: Boolean = true,
    backdropBlur: Modifier = Modifier,
): Modifier = this
    .clip(RoundedCornerShape(cornerRadius))
    .then(backdropBlur)
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

        // 2) 内描边：顶亮底暗
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

        // 3) 方向性边缘光：沿形状内缘的柔和环形高光
        //    方向由 tokens.specularAngle 决定（线性渐变：迎光侧亮、背光侧透明），
        //    柔度由「多遍同心描边 + 逐遍衰减」得到 —— 不新建离屏图层，也不用 AGSL / RuntimeShader。
        val angleRad = tokens.specularAngle * (PI / 180.0)
        val dirX = cos(angleRad).toFloat()
        val dirY = sin(angleRad).toFloat()
        val centerX = width * 0.5f
        val centerY = height * 0.5f
        val reach = (abs(dirX) * width + abs(dirY) * height) * 0.5f
        val edgePeakAlpha = (spec.specularAlpha * safeIntensity * 0.5f).coerceIn(0f, 1f)
        val edgeBrush = Brush.linearGradient(
            colors = listOf(
                colors.glassSpecular.copy(alpha = edgePeakAlpha),
                Color.Transparent,
            ),
            start = Offset(centerX - dirX * reach, centerY - dirY * reach),
            end = Offset(centerX + dirX * reach, centerY + dirY * reach),
        )
        val edgeFalloff = tokens.specularFalloff.coerceAtLeast(0.25f)
        val glowDepth = (minOf(width, height) * tokens.specularBandRatio * 0.25f)
            .coerceIn(1.5.dp.toPx(), 10.dp.toPx())
        val edgePasses = 5

        // 4) 底部接触阴影（让玻璃"坐"在壁纸上，而不是浮在贴纸上）
        val contactBrush = Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                colors.glassShadow.copy(alpha = 0.22f * safeIntensity),
            ),
            startY = height * 0.66f,
            endY = height,
        )

        // 5) 噪点微纹理（固定种子，尺寸变化时重建；数量与尺寸无关，开销恒定）
        val noisePoints = if (noise) buildNoisePoints(width, height, 150) else emptyList()
        // 噪点用 DrawScope 的 color 版 drawPoints（DrawScope 没有 Paint 版重载），
        // 透明度单独算好，避免每帧构造 Paint。
        val noiseColor = colors.glassSpecular
        val noiseAlpha = (spec.noiseAlpha * safeIntensity).coerceIn(0f, 1f)

        val strokeWidthPx = tokens.highlightStrokeWidth.toPx().coerceAtLeast(0.5f)
        val halfStroke = strokeWidthPx * 0.5f

        onDrawWithContent {
            drawContent()
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
                // 描边以形状边界为中心，外侧一半被外层 clip 裁掉 —— 于是得到「内边光」。
                for (pass in 1..edgePasses) {
                    val band = glowDepth * pass / edgePasses
                    val passAlpha = edgePeakAlpha / (1f + (pass - 1) * edgeFalloff)
                    drawRoundRect(
                        brush = edgeBrush,
                        topLeft = Offset.Zero,
                        size = Size(width, height),
                        cornerRadius = corner,
                        alpha = passAlpha.coerceIn(0f, 1f),
                        style = Stroke(width = band * 2f),
                    )
                }
            }
            if (noisePoints.isNotEmpty()) {
                drawPoints(
                    points = noisePoints,
                    pointMode = PointMode.Points,
                    color = noiseColor,
                    strokeWidth = 1.5f,
                    cap = StrokeCap.Round,
                    alpha = noiseAlpha,
                )
            }
        }
    }

/**
 * Composable 版：从 CompositionLocal 取 tokens/colors/config/背景源。UI 代码一律用这个。
 * （刻意不用 Modifier.composed —— 该 API 在新版 Compose 里已不推荐，用 @Composable 扩展更安全。）
 *
 * 真实背景模糊（P2 已恢复）：`GraphicsLayer.record` + `BlurEffect`，
 * 纯 Compose 层实现，不需要平台互操作。详见 [glassBackdropBlur]。
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
    val config = LocalGlassConfig.current
    val backdrop = LocalGlassBackdropState.current
    // 模糊半径取材质的 blurRadius（静态值，不参与动画）；没有背景源时退化为纯玻璃。
    val backdropBlur = if (config.enableBackdropBlur && backdrop != null) {
        Modifier.glassBackdropBlur(
            state = backdrop,
            blurRadius = GlassMaterials.of(material).blurRadius,
        )
    } else {
        Modifier
    }
    return this.liquidGlass(
        tokens = tokens,
        colors = colors,
        material = material,
        cornerRadius = cornerRadius,
        intensity = intensity * config.intensity,
        noise = noise && config.enableNoise,
        specular = specular && config.enableSpecular,
        backdropBlur = backdropBlur,
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

private fun buildNoisePoints(width: Float, height: Float, count: Int): List<Offset> {
    val random = Random(2026)
    return List(count) {
        Offset(random.nextFloat() * width, random.nextFloat() * height)
    }
}
