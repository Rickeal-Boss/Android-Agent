package com.rickeal.agent.core.design

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * 玻璃容器：所有玻璃组件的基座。
 *
 * - 按下时有弹簧缩放反馈（[LiquidMotionSpec.pressScale]）
 * - 开启 [GlassConfig.enableBackdropBlur] 且 API 31+ 时，叠一层真实 RenderEffect 背景模糊
 * - 未开启时退化为「径向渐变伪模糊」，观感接近、开销极低
 */
@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    material: GlassMaterial = GlassMaterial.REGULAR,
    cornerRadius: Dp = LocalGlassTokens.current.radiusLg,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    contentAlignment: Alignment = Alignment.TopStart,
    propagateMinConstraints: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val tokens = LocalGlassTokens.current
    val colors = LocalGlassColors.current
    val config = LocalGlassConfig.current
    val backdrop = LocalGlassBackdrop.current
    val motion = LocalLiquidMotion.current
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && onClick != null) motion.pressScale else 1f,
        animationSpec = LiquidMotion.floatSpring(motion),
        label = "glassPress",
    )
    val clickable = if (onClick != null) {
        Modifier.clickable(
            enabled = enabled,
            interactionSource = source,
            indication = null,
            onClick = onClick,
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .scale(scale)
            .then(clickable)
            .liquidGlass(
                tokens = tokens,
                colors = colors,
                backdrop = backdrop,
                material = material,
                cornerRadius = cornerRadius,
                intensity = config.intensity,
                noise = config.enableNoise,
                specular = config.enableSpecular,
            ),
        contentAlignment = contentAlignment,
        propagateMinConstraints = propagateMinConstraints,
    ) {
        if (config.enableBackdropBlur) {
            GlassBackdropBlurLayer(
                backdrop = backdrop,
                blurRadius = GlassMaterials.of(material).blurRadius,
                shape = glassShape(cornerRadius),
            )
        }
        Box(modifier = Modifier.padding(contentPadding)) {
            content()
        }
    }
}

/** 标准玻璃卡片。 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    material: GlassMaterial = GlassMaterial.THIN,
    cornerRadius: Dp = LocalGlassTokens.current.radiusMd,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(LocalGlassTokens.current.paddingMd),
    content: @Composable BoxScope.() -> Unit,
) {
    LiquidGlassSurface(
        modifier = modifier,
        material = material,
        cornerRadius = cornerRadius,
        onClick = onClick,
        contentPadding = contentPadding,
        content = content,
    )
}

/** 真实背景模糊层：只在 API 31+ 启用，且仅作用于背景，不会模糊内容文字。 */
@Composable
private fun BoxScope.GlassBackdropBlurLayer(
    backdrop: GlassBackdrop,
    blurRadius: Dp,
    shape: Shape,
) {
    val radiusPx = with(LocalDensity.current) { blurRadius.toPx() }
    val blurModifier = if (Build.VERSION.SDK_INT >= 31 && radiusPx > 0f) {
        Modifier.graphicsLayer {
            renderEffect = RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
        }
    } else {
        Modifier
    }
    Box(
        modifier = Modifier
            .matchParentSize()
            .clip(shape)
            .then(blurModifier)
            .drawWithCache {
                onDrawBehind { drawSoftBlobs(backdrop) }
            },
    )
}

internal fun DrawScope.drawSoftBlobs(backdrop: GlassBackdrop) {
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
        )
    }
}
