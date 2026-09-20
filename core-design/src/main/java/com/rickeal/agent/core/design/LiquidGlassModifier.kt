package com.rickeal.agent.core.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.liquid.EmptyBackdrop
import com.rickeal.agent.core.design.liquid.LocalBackdrop
import com.rickeal.agent.core.design.liquid.drawBackdrop
import com.rickeal.agent.core.design.liquid.effects.blur
import com.rickeal.agent.core.design.liquid.effects.lens
import com.rickeal.agent.core.design.liquid.effects.vibrancy
import com.rickeal.agent.core.design.liquid.highlight.Highlight
import com.rickeal.agent.core.design.liquid.highlight.HighlightStyle
import com.rickeal.agent.core.design.liquid.platform.LiquidGlassCapabilities
import com.rickeal.agent.core.design.liquid.shadow.InnerShadow
import com.rickeal.agent.core.design.liquid.shadow.Shadow
import kotlin.random.Random

/** 折射带高度（dp）：从边缘向内算，玻璃"厚度渐变"的范围。 */
private val RefractionHeightDp = 10.dp
/** 折射强度（dp）：背景被弯折的像素位移量。越大越"鼓"。 */
private val RefractionAmountDp = 26.dp

/**
 * 纯函数版 `liquidGlass`：所有视觉输入显式传入。
 *
 * ⚠️ 这是**旧引擎**（仅 Compose `BlurEffect` + 多遍描边模拟高光），保留只为兼容
 * 可能存在的直接调用方。UI 代码请一律用下面的 @Composable 版本 —— 它走
 * `core-design/liquid` 新引擎（真实折射 + 色散 + AGSL 方向性高光），
 * 这才是"液态玻璃"而非"磨砂玻璃"。
 */
fun Modifier.liquidGlassRaw(
    tokens: GlassTokens,
    colors: GlassColorScheme,
    material: GlassMaterial = GlassMaterial.REGULAR,
    cornerRadius: Dp = GlassDefaults.RadiusLg,
    intensity: Float = 1f,
    noise: Boolean = true,
    specular: Boolean = true,
    backdropBlur: Modifier = Modifier,
): Modifier = this
    .then(backdropBlur)
    .drawWithCacheCompat(tokens, colors, material, cornerRadius, intensity, noise, specular)

private fun Modifier.drawWithCacheCompat(
    tokens: GlassTokens,
    colors: GlassColorScheme,
    material: GlassMaterial,
    cornerRadius: Dp,
    intensity: Float,
    noise: Boolean,
    specular: Boolean,
): Modifier = androidx.compose.ui.draw.drawWithCache {
    val spec = GlassMaterials.of(material)
    val height = size.height.coerceAtLeast(1f)
    val width = size.width.coerceAtLeast(1f)
    val radiusPx = cornerRadius.toPx()
        .coerceAtMost(minOf(size.width, size.height) / 2f)
        .coerceAtLeast(0f)
    val corner = CornerRadius(radiusPx, radiusPx)
    val safeIntensity = intensity.coerceIn(0f, 1.5f)

    val baseAlpha = (spec.backgroundAlpha * safeIntensity).coerceIn(0f, 1f)
    val fillBrush = Brush.verticalGradient(
        colors = listOf(
            colors.glassTint.copy(alpha = baseAlpha),
            colors.glassTintElevated.copy(alpha = (baseAlpha * 0.72f).coerceIn(0f, 1f)),
        ),
        startY = 0f,
        endY = height,
    )

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

    val strokeWidthPx = tokens.highlightStrokeWidth.toPx().coerceAtLeast(0.5f)
    val halfStroke = strokeWidthPx * 0.5f

    onDrawWithContent {
        drawContent()
        drawRoundRect(brush = fillBrush, cornerRadius = corner)
        drawRoundRect(
            brush = borderBrush,
            topLeft = androidx.compose.ui.geometry.Offset(halfStroke, halfStroke),
            size = androidx.compose.ui.geometry.Size(
                width = (width - strokeWidthPx).coerceAtLeast(0f),
                height = (height - strokeWidthPx).coerceAtLeast(0f),
            ),
            cornerRadius = CornerRadius(
                x = (radiusPx - halfStroke).coerceAtLeast(0f),
                y = (radiusPx - halfStroke).coerceAtLeast(0f),
            ),
            style = Stroke(width = strokeWidthPx),
        )
    }
}

/**
 * **液态玻璃主入口**（新引擎）。
 *
 * 四层绘制顺序（这是"像液态玻璃"的必要条件，缺一层就退回磨砂塑料观感）：
 *  1. **背景**：由 [LocalBackdrop] 提供的壁纸，先过 `vibrancy` 提鲜 → `blur` 磨平 →
 *     `lens` **折射**（按圆角矩形 SDF 弯折采样坐标，边缘形成厚度渐变带）
 *  2. **玻璃本体**（onDrawSurface）：半透明底色渐变 + 噪点，压在背景之上
 *  3. **内容**（drawContent）：文本 / 图标，**永远清晰**，不被模糊
 *  4. **前景**：AGSL 方向性高光 + 内描边 + 内阴影
 *
 * 与之对比，旧实现只有「blur + 多遍同心描边假高光」，没有折射也没有色散 ——
 * 这就是"和液态玻璃完全没法沾边"的根因。
 *
 * @param refraction 是否开启折射。这是液态玻璃的核心，默认开。
 *   API 31~32（无 AGSL）自动降级为纯 blur，不崩。
 * @param dispersion 是否开启**色散**（RGB 分离 → 边缘彩虹色带）。
 *   开销约为纯折射的 7 倍，建议只在大面积容器（Dialog / 大卡片）开。
 */
@Composable
fun Modifier.liquidGlass(
    material: GlassMaterial = GlassMaterial.REGULAR,
    cornerRadius: Dp = GlassDefaults.RadiusLg,
    intensity: Float = 1f,
    noise: Boolean = true,
    specular: Boolean = true,
    refraction: Boolean = true,
    dispersion: Boolean = false,
): Modifier {
    val tokens = LocalGlassTokens.current
    val colors = LocalGlassColors.current
    val config = LocalGlassConfig.current
    val backdrop = LocalBackdrop.current

    val spec = GlassMaterials.of(material)
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val safeIntensity = (intensity * config.intensity).coerceIn(0f, 1.5f)
    val enableBackdrop = config.enableBackdropBlur
    // 折射依赖 AGSL（API 33+）；API 31~32 自动退回纯模糊，绝不硬上。
    val enableRefraction = refraction && enableBackdrop && LiquidGlassCapabilities.hasRuntimeShader
    val enableDispersion = dispersion && enableRefraction
    val enableNoise = noise && config.enableNoise
    val enableSpecular = specular && config.enableSpecular
    // 关掉「背景模糊」时**连背景都不采样**（而不是采样但不过滤）——
    // 否则玻璃会像素级透出壁纸，观感上等于玻璃消失了。
    // 这与旧引擎（enableBackdropBlur=false → 背景 Modifier 为空）语义一致，
    // 也是 UI-07 那个性能开关能被用户关掉的前提。
    val effectiveBackdrop = if (enableBackdrop) backdrop else EmptyBackdrop

    return this.drawBackdrop(
        backdrop = effectiveBackdrop,
        shape = { shape },
        effects = {
            if (enableBackdrop) {
                // vibrancy：把玻璃"吸走"的饱和度拉回来，iOS 26 Liquid Glass 标配
                vibrancy()
                blur(spec.blurRadius.toPx())
                if (enableRefraction) {
                    lens(
                        refractionHeight = RefractionHeightDp.toPx(),
                        refractionAmount = RefractionAmountDp.toPx(),
                        chromaticAberration = enableDispersion
                    )
                }
            }
        },
        highlight = {
            if (!enableSpecular) {
                null
            } else {
                Highlight(
                    width = tokens.highlightStrokeWidth,
                    alpha = 1f,
                    style = HighlightStyle.Default.copy(
                        color = colors.glassSpecular.copy(
                            alpha = (spec.specularAlpha * safeIntensity).coerceIn(0f, 1f)
                        ),
                        angle = tokens.specularAngle
                    )
                )
            }
        },
        shadow = {
            Shadow(
                radius = spec.shadowElevation,
                color = colors.glassShadow
            )
        },
        innerShadow = {
            InnerShadow(
                radius = tokens.radiusSm,
                color = colors.glassShadow,
                alpha = 0.5f
            )
        },
        onDrawSurface = {
            // 玻璃底色：上亮下暗的垂直渐变
            val baseAlpha = (spec.backgroundAlpha * safeIntensity).coerceIn(0f, 1f)
            val fillBrush = Brush.verticalGradient(
                colors = listOf(
                    colors.glassTint.copy(alpha = baseAlpha),
                    colors.glassTintElevated.copy(alpha = (baseAlpha * 0.72f).coerceIn(0f, 1f)),
                ),
                startY = 0f,
                endY = size.height,
            )
            val radiusPx = cornerRadius.toPx()
                .coerceAtMost(minOf(size.width, size.height) / 2f)
                .coerceAtLeast(0f)
            val corner = CornerRadius(radiusPx, radiusPx)
            drawRoundRect(brush = fillBrush, cornerRadius = corner)

            // 噪点微纹理：消除大面积纯色的"塑料感"
            if (enableNoise) {
                drawPoints(
                    points = buildNoisePoints(size.width, size.height, 150),
                    pointMode = PointMode.Points,
                    color = colors.glassSpecular,
                    strokeWidth = 1.5f,
                    cap = StrokeCap.Round,
                    alpha = (spec.noiseAlpha * safeIntensity).coerceIn(0f, 1f),
                )
            }
        },
        onDrawFront = {
            // 内描边：顶亮底暗
            val borderAlpha = (spec.borderAlpha * safeIntensity).coerceIn(0f, 1f)
            val borderBrush = Brush.verticalGradient(
                colors = listOf(
                    colors.glassBorderTop.copy(alpha = borderAlpha),
                    Color.Transparent,
                    colors.glassBorderBottom.copy(alpha = (borderAlpha * 0.8f).coerceIn(0f, 1f)),
                ),
                startY = 0f,
                endY = size.height,
            )
            val strokeWidthPx = tokens.highlightStrokeWidth.toPx().coerceAtLeast(0.5f)
            val halfStroke = strokeWidthPx * 0.5f
            val radiusPx = cornerRadius.toPx()
                .coerceAtMost(minOf(size.width, size.height) / 2f)
                .coerceAtLeast(0f)
            drawRoundRect(
                brush = borderBrush,
                topLeft = androidx.compose.ui.geometry.Offset(halfStroke, halfStroke),
                size = androidx.compose.ui.geometry.Size(
                    width = (size.width - strokeWidthPx).coerceAtLeast(0f),
                    height = (size.height - strokeWidthPx).coerceAtLeast(0f),
                ),
                cornerRadius = CornerRadius(
                    x = (radiusPx - halfStroke).coerceAtLeast(0f),
                    y = (radiusPx - halfStroke).coerceAtLeast(0f),
                ),
                style = Stroke(width = strokeWidthPx),
            )
        }
    )
}

/**
 * 液态按压反馈：按下时整体缩放到 pressScale 再回弹。
 * 与 `liquidGlass` 组合使用即可获得 iOS 26/27 的"果冻"手感。
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

private fun buildNoisePoints(width: Float, height: Float, count: Int): List<androidx.compose.ui.geometry.Offset> {
    val random = Random(2026)
    return List(count) {
        androidx.compose.ui.geometry.Offset(random.nextFloat() * width, random.nextFloat() * height)
    }
}
