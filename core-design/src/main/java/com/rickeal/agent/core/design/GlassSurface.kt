package com.rickeal.agent.core.design
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.fillMaxSize

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/* ------------------------------------------------------------------ 表面 */

/**
 * 一切玻璃容器的基座。onClick 非空时自动带按压弹簧反馈。
 *
 * @param capsule 用胶囊（[Capsule]）代替圆角矩形。交互控件（按钮 / Chip / FAB /
 *   分段项）都应该用胶囊 —— 这是 iOS Liquid Glass 的标志性轮廓。
 * @param layerBlock **跟手形变**：直接透传给 `liquidGlass`，见
 *   `liquid/interactive/InteractiveHighlight`。静态截图看不出差别，真机一按就露馅。
 * @param dispersion 色散（RGB 分离 → 边缘彩虹）。大面积容器（Card / Dialog）必须**关**：
 *   色散要 7 次采样，开销约 7 倍。
 */
@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    material: GlassMaterial = GlassMaterial.REGULAR,
    cornerRadius: Dp = GlassDefaults.RadiusLg,
    capsule: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    contentPadding: PaddingValues = PaddingValues(GlassDefaults.ContentPadding),
    contentAlignment: Alignment = Alignment.TopStart,
    propagateMinConstraints: Boolean = false,
    dispersion: Boolean = true,
    refractionHeight: Dp? = null,
    refractionAmount: Dp? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    // 按压进度 → 传给玻璃，让它在按下时"变实"（模糊减弱 + 折射增强 + 高光变亮）。
    // 静态观感只是"像玻璃"，按下去的反馈才是"液态" —— 两者缺一不可。
    // 可点击时才收集按压状态，纯展示的容器不引入无谓的重组。
    val pressed by interactionSource.collectIsPressedAsState()
    val pressProgress by animateFloatAsState(
        targetValue = if (onClick != null && pressed && enabled) 1f else 0f,
        animationSpec = LiquidMotion.floatSpring(LocalLiquidMotion.current),
        label = "glassPressProgress",
    )

    val glassModifier = modifier
        .then(
            if (onClick != null) {
                Modifier
                    .liquidPress(interactionSource = interactionSource, enabled = enabled)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = enabled,
                        onClick = onClick,
                    )
            } else {
                Modifier
            },
        )
        .liquidGlass(
            material = material,
            cornerRadius = cornerRadius,
            capsule = capsule,
            dispersion = dispersion,
            refractionHeight = refractionHeight,
            refractionAmount = refractionAmount,
            pressProgress = pressProgress,
            layerBlock = layerBlock,
        )
    Box(
        modifier = glassModifier,
        contentAlignment = contentAlignment,
        propagateMinConstraints = propagateMinConstraints,
    ) {
        Box(modifier = Modifier.padding(contentPadding)) {
            content()
        }
    }
}

/**
 * 语义化别名：卡片。默认 REGULAR 材质 + 28dp 圆角 + 16dp 内边距。
 *
 * ⚠️ 默认**关色散**：色散要 7 次采样（约 7 倍开销），卡片是全屏级大面积容器，
 * 列表里一屏能有五六张，开着必掉帧。折射（lens）仍然开 —— 厚度感靠它。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    material: GlassMaterial = GlassMaterial.REGULAR,
    cornerRadius: Dp = 28.dp,
    onClick: (() -> Unit)? = null,
    dispersion: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(GlassDefaults.ContentPadding),
    content: @Composable BoxScope.() -> Unit,
) {
    LiquidGlassSurface(
        modifier = modifier,
        material = material,
        cornerRadius = cornerRadius,
        onClick = onClick,
        dispersion = dispersion,
        contentPadding = contentPadding,
        content = content,
    )
}

/* ------------------------------------------------- 气泡的「纯视觉」DTO */

/**
 * `:core-design` 刻意不依赖 `:core-model`（见架构 §1.4），
 * 因此气泡的附件/用量用这里的纯字符串 DTO 承接，由 feature 层做一次映射。
 */
enum class GlassAttachmentKind { IMAGE, AUDIO, FILE, TEXT }

@androidx.compose.runtime.Immutable
data class GlassBubbleAttachment(
    val id: String,
    val label: String,
    val kind: GlassAttachmentKind = GlassAttachmentKind.FILE,
    /** 图片/音频用 content:// 或 file 绝对路径；文本类为空 */
    val uri: String = "",
)

@androidx.compose.runtime.Immutable
data class GlassBubbleUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val tokensPerSecond: Float = 0f,
    val firstTokenLatencyMillis: Long = 0L,
)

/* ------------------------------------------------------------------ 气泡 */

/**
 * 对话气泡。isUser 决定对齐、材质与强调色。
 */
@Composable
fun GlassBubble(
    text: String,
    isUser: Boolean,
    modifier: Modifier = Modifier,
    thinking: String? = null,
    thinkingExpanded: Boolean = false,
    onToggleThinking: (() -> Unit)? = null,
    attachments: List<GlassBubbleAttachment> = emptyList(),
    isStreaming: Boolean = false,
    errorMessage: String? = null,
    usage: GlassBubbleUsage? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current
    val maxBubbleWidth = 340.dp

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = maxBubbleWidth)
                .then(
                    if (onLongClick != null) {
                        Modifier.pointerInput(onLongClick) {
                            detectTapGestures(onLongPress = { onLongClick.invoke() })
                        }
                    } else {
                        Modifier
                    },
                )
                .liquidGlass(
                    material = if (isUser) GlassMaterial.REGULAR else GlassMaterial.THIN,
                    cornerRadius = tokens.radiusLg,
                ),
        ) {
            if (isUser) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    colors.accent.copy(alpha = 0.22f),
                                    colors.accent.copy(alpha = 0.08f),
                                ),
                            ),
                        ),
                )
            }
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (attachments.isNotEmpty()) {
                    AttachmentStrip(attachments = attachments)
                }
                if (!thinking.isNullOrBlank()) {
                    ThinkingBlock(
                        thinking = thinking,
                        expanded = thinkingExpanded,
                        onToggle = onToggleThinking,
                    )
                }
                if (text.isNotBlank()) {
                    Text(
                        text = if (isStreaming) text + "▍" else text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onGlass,
                        modifier = Modifier.padding(top = if (attachments.isEmpty()) 0.dp else 8.dp),
                    )
                } else if (isStreaming && thinking.isNullOrBlank()) {
                    GlassThinkingIndicator(label = "思考中")
                }
                if (errorMessage != null) {
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = colors.danger,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.danger,
                        )
                    }
                }
                if (usage != null) {
                    Text(
                        text = usageText(usage),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onGlassSubtle,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

private fun usageText(usage: GlassBubbleUsage): String {
    val tps = if (usage.tokensPerSecond > 0f) " · %.1f tok/s".format(Locale.US, usage.tokensPerSecond) else ""
    val ttft = if (usage.firstTokenLatencyMillis > 0L) " · 首字 ${usage.firstTokenLatencyMillis}ms" else ""
    return "in ${usage.promptTokens} / out ${usage.completionTokens}$tps$ttft"
}

@Composable
private fun ThinkingBlock(
    thinking: String,
    expanded: Boolean,
    onToggle: (() -> Unit)?,
) {
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current
    LiquidGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        material = GlassMaterial.ULTRA_THIN,
        cornerRadius = tokens.radiusSm,
        onClick = onToggle,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Psychology,
                    contentDescription = null,
                    tint = colors.onGlassMuted,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = "思考过程",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onGlassMuted,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .weight(1f),
                )
                if (onToggle != null) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = colors.onGlassSubtle,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(expandFrom = Alignment.Top),
                exit = shrinkVertically(shrinkTowards = Alignment.Top),
            ) {
                Text(
                    text = thinking,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onGlassMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (!expanded) {
                Text(
                    text = thinking.replace('\n', ' '),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onGlassSubtle,
                    maxLines = 2,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun AttachmentStrip(attachments: List<GlassBubbleAttachment>) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (attachment in attachments) {
            AttachmentItem(attachment = attachment)
        }
    }
}

@Composable
private fun AttachmentItem(attachment: GlassBubbleAttachment) {
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current
    when (attachment.kind) {
        GlassAttachmentKind.IMAGE -> {
            LiquidGlassSurface(
                material = GlassMaterial.ULTRA_THIN,
                cornerRadius = tokens.radiusSm,
                contentPadding = PaddingValues(0.dp),
            ) {
                AttachmentThumb(uri = attachment.uri)
            }
        }
        else -> {
            LiquidGlassSurface(
                material = GlassMaterial.ULTRA_THIN,
                cornerRadius = tokens.radiusFull,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when (attachment.kind) {
                            GlassAttachmentKind.AUDIO -> Icons.Filled.Audiotrack
                            GlassAttachmentKind.TEXT -> Icons.Filled.Description
                            else -> Icons.Filled.AttachFile
                        },
                        contentDescription = null,
                        tint = colors.onGlassMuted,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = attachment.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onGlassMuted,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 5.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentThumb(uri: String) {
    val context = LocalContext.current
    // content:// 不一定是本地文件：它可能是网盘 Provider（Google Drive / 各家云盘），
    // openInputStream 会同步走网络。原来这里是 `remember(uri) { decodeThumbnail(...) }`，
    // 在组合期同步执行 —— 主线程阻塞直到 ANR。
    // 改成 produceState + Dispatchers.IO：先出占位图标，解码完成后再替换。
    // 刻意不引 Coil（架构约定：不引入图片库）。
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = uri) {
        value = withContext(Dispatchers.IO) { decodeThumbnail(context, uri) }
    }
    // 委托属性（by produceState）在 null 检查与使用之间可能被其他帧改写，
    // Kotlin 不会为它做 smart cast —— 必须先取到局部 val 再判空。
    val decoded = bitmap
    if (decoded != null) {
        Image(
            bitmap = decoded,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 96.dp, height = 72.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
    } else {
        val colors = LocalGlassColors.current
        Box(
            modifier = Modifier.size(width = 96.dp, height = 72.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.PhotoLibrary,
                contentDescription = null,
                tint = colors.onGlassSubtle,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** 缩略图长边的目标上限（px）。缩略图实际显示尺寸只有 96×72 dp，320px 足够。 */
private const val THUMBNAIL_MAX_DIM = 320

/**
 * 缩略图解码：先读 bounds 算 inSampleSize，再按需缩放解码。
 * 刻意不使用 Coil（简报 §6：不引入图片库）。RGB_565 省一半内存。
 *
 * **必须在后台线程调用**：`content://` 可能是网盘 Provider，`openInputStream`
 * 会同步走网络（调用点 [AttachmentThumb] 已切到 Dispatchers.IO）。
 */
private fun decodeThumbnail(context: android.content.Context, uri: String): ImageBitmap? = runCatching {
    if (uri.isBlank()) return null
    val parsed = Uri.parse(uri)
    val resolver = context.contentResolver
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(parsed)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
    val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
    // inJustDecodeBounds 失败（流不可读 / 非图片 / Provider 报错）时 outWidth/outHeight
    // 会是 -1 或 0：不挡掉的话 maxOf(-1, -1) = -1 ⇒ 下面的 while 一次都不执行 ⇒
    // sample 保持 1 ⇒ 整图解码，4000×3000 的照片就是 24~36MB ⇒ OOM。
    if (maxDim <= 0) return null
    var sample = 1
    while (maxDim / sample > THUMBNAIL_MAX_DIM) sample *= 2
    val options = android.graphics.BitmapFactory.Options().apply {
        inSampleSize = sample.coerceAtLeast(1)
        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
    }
    val decoded = resolver.openInputStream(parsed)?.use {
        android.graphics.BitmapFactory.decodeStream(it, null, options)
    }
    decoded?.asImageBitmap()
}.getOrNull()
