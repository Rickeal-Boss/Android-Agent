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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
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
import com.rickeal.agent.core.design.liquid.shapes.Capsule
import kotlin.random.Random

/**
 * vibrancy 的饱和度系数。
 * Kyant0 默认 1.5，但我们的壁纸是浅色渐变 + 光斑（不是照片），
 * 1.5 会把浅色推成荧光色。降到 1.22 —— 提鲜但不失真。
 */
private const val VibrancySaturation = 1.22f

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
 *   默认**开** —— 色散是"这真的是玻璃"的最强视觉信号，
 *   Kyant0 在 Tabs / Slider / Toggle 上都开了。开销约 7 倍（7 次采样），
 *   若真机掉帧可关（列表 item 建议关）。
 * @param pressProgress 按压进度 0~1。这是"液态"手感的关键一半 ——
 *   静态看是玻璃，**按下去会变实**（模糊减弱、折射增强、高光变亮），
 *   对应 Kyant0 各组件里 `blur(8f.dp * (1f - progress))` + `lens(... * progress)` 的写法。
 *   传 0 表示无按压（默认）。
 * @param shapeOverride 形状覆盖。默认按 [cornerRadius] 生成对称圆角矩形；
 *   需要非对称圆角（如底部 sheet 只有上方两角圆）时显式传入。
 * @param capsule 用**胶囊**（[Capsule]）代替圆角矩形。
 *   Kyant0 的所有交互组件都是胶囊，这是 iOS Liquid Glass 的标志性轮廓。
 * @param blurRadius 覆盖材质的背景模糊半径。传 null 用材质自带值。
 * @param refractionHeight / [refractionAmount] 覆盖材质的折射参数。传 null 用材质自带值。
 *   Kyant0 是**逐组件**调的（Button 12/24、Slider 10/14、Toggle 5/10、Tabs 24/24），
 *   全局固定一个值是"看着不像"的原因之一。
 * @param layerBlock **跟手形变**。iOS Liquid Glass 的标志性观感是"胶囊 + 按下去会跟着手指
 *   位移/拉伸"。它直接透传给 `drawBackdrop`，在玻璃节点的最外层 `graphicsLayer` 上生效，
 *   所以位移/缩放会连内容一起动（文本不会被单独拉扯）。
 *   见 `liquid/interactive/DampedDragAnimation` 与 `InteractiveHighlight`。
 */
@Composable
fun Modifier.liquidGlass(
    material: GlassMaterial = GlassMaterial.REGULAR,
    cornerRadius: Dp = GlassDefaults.RadiusLg,
    capsule: Boolean = false,
    intensity: Float = 1f,
    noise: Boolean = true,
    specular: Boolean = true,
    refraction: Boolean = true,
    dispersion: Boolean = true,
    blurRadius: Dp? = null,
    refractionHeight: Dp? = null,
    refractionAmount: Dp? = null,
    pressProgress: Float = 0f,
    shapeOverride: Shape? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
): Modifier {
    val tokens = LocalGlassTokens.current
    val colors = LocalGlassColors.current
    val config = LocalGlassConfig.current
    val backdrop = LocalBackdrop.current

    val spec = GlassMaterials.of(material)
    // 注意：remember 必须无条件调用（条件性 remember 会让重组时的缓存行为不确定），
    // 所以先无条件算出默认形状，再用 ?: 选择覆盖值。
    val defaultShape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val shape = shapeOverride ?: if (capsule) Capsule else defaultShape
    val press = pressProgress.coerceIn(0f, 1f)
    // 逐组件覆盖优先，其次用材质自带值。
    // 折射参数全局固定一个值是"看着不像"的原因之一：Kyant0 是 Button 12/24、
    // Slider 10/14、Toggle 5/10、Tabs 24/24，各不一样。
    val effectiveBlur = blurRadius ?: spec.blurRadius
    val effectiveRefractionHeight = refractionHeight ?: spec.refractionHeight
    val effectiveRefractionAmount = refractionAmount ?: spec.refractionAmount
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
                vibrancy(saturation = VibrancySaturation)
                // 按压时**减弱**模糊：玻璃被按"实"了，对应 Kyant0 的
                // `blur(8f.dp * (1f - progress))`。物理直觉：越实的东西越不需要磨砂。
                blur(effectiveBlur.toPx() * (1f - press * 0.7f))
                if (enableRefraction) {
                    // 按压时**增强**折射：对应 Kyant0 的 `lens(... * progress)`。
                    // 越用力按，玻璃形变越明显 —— 这是"液态"手感的核心。
                    lens(
                        refractionHeight = effectiveRefractionHeight.toPx() * (1f + press * 0.4f),
                        refractionAmount = effectiveRefractionAmount.toPx() * (1f + press * 0.25f),
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
                    // 按压时高光带变宽变亮 —— 玻璃被压时边缘反射更强
                    width = tokens.highlightStrokeWidth * (1f + press * 0.6f),
                    alpha = 1f,
                    style = HighlightStyle.DefaultStyle.copy(
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
            val radiusPx = glassCornerRadiusPx(size, cornerRadius.toPx(), capsule)
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
            val radiusPx = glassCornerRadiusPx(size, cornerRadius.toPx(), capsule)
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
        },
        layerBlock = layerBlock
    )
}

/**
 * 玻璃本体（底色 / 描边）绘制用的圆角半径（px）。
 *
 * 胶囊形状必须走 `min(w, h) / 2` 分支 —— 用 [cornerRadius] 画的话，
 * 玻璃底色会是一个圆角矩形，而外层的 clip 是胶囊，四角就会露出没被填满的空隙。
 */
private fun glassCornerRadiusPx(size: Size, cornerRadiusPx: Float, capsule: Boolean): Float {
    val maxRadius = minOf(size.width, size.height) / 2f
    return if (capsule) {
        maxRadius.coerceAtLeast(0f)
    } else {
        cornerRadiusPx.coerceAtMost(maxRadius).coerceAtLeast(0f)
    }
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
