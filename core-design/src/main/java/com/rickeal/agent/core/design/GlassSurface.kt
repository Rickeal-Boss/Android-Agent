package com.rickeal.agent.core.design
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.fillMaxSize

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale

/* ------------------------------------------------------------------ 表面 */

/**
 * 一切玻璃容器的基座。onClick 非空时自动带按压弹簧反馈。
 *
 * @param capsule 用胶囊（[Capsule]）代替圆角矩形。交互控件（按钮 / Chip / FAB /
 *   分段项）都应该用胶囊 —— 这是 iOS Liquid Glass 的标志性轮廓。
 * @param layerBlock **跟手形变**：直接透传给 `liquidGlass`，见
 *   `liquid/interactive/InteractiveHighlight`。静态截图看不出差别，真机一按就露馅。
 * @param gestureModifier 跟手手势（通常是 `InteractiveHighlight.gestureModifier`）。
 *   ⚠️ **必须**走这个形参，不能塞进 [modifier]：门面内部会把 `clickable` 追加在
 *   [modifier] **之后**，从 [modifier] 里塞进来的手势会变成
 *   `手势 → clickable`（顺序反了），横拖时可能误触发 onClick。
 *   本形参在 `clickable` **之后**才 `.then`，与其它组件
 *   （GlassButton / Chip / Fab / Segmented）的 `clickable → ... → 手势` 一致。
 * @param dispersion 色散（RGB 分离 → 边缘彩虹）。默认**关**：色散要 7 次采样（约 7 倍
 *   开销），默认开会让"没显式传"的调用点**悄悄吃掉 7 倍** —— 对话气泡这类长列表
 *   一屏十几条，开着必掉帧。大面积容器（Card / Dialog / 气泡）一律关；
 *   扛得住的极小控件再显式传 `true`。
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
    dispersion: Boolean = false,
    refractionHeight: Dp? = null,
    refractionAmount: Dp? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    gestureModifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    // 按压进度 → 传给玻璃，让它在按下时"变实"（模糊减弱 + 折射增强 + 高光变亮）。
    // 静态观感只是"像玻璃"，按下去的反馈才是"液态" —— 两者缺一不可。
    // 可点击时才收集按压状态，纯展示的容器不引入无谓的重组。
    val pressed by interactionSource.collectIsPressedAsState()
    // ⚠️ 刻意不用 `by` 委托：委托会在**组合期**把 State 读成 Float，于是按下动画
    // 每帧重组整个 LiquidGlassSurface（里面还包着调用方的 content —— 卡片/气泡
    // 的内容可比一个按钮重得多）。持有 State，把 `.value` 的读取推迟到
    // liquidGlass 内部的绘制期 lambda：每帧只失效绘制，不重组。
    val pressProgress = animateFloatAsState(
        targetValue = if (onClick != null && pressed && enabled) 1f else 0f,
        animationSpec = LiquidMotion.floatSpring(LocalLiquidMotion.current),
        label = "glassPressProgress",
    )

    val glassModifier = modifier
        .then(
            if (onClick != null) {
                Modifier
                    .then(
                        // 传了 layerBlock 就**不再**叠 liquidPress：
                        // liquidPress 是等比缩放（scaleX == scaleY），正是本轮要替掉的
                        // "原生按钮"手感；而且两个缩放会叠乘，形变过头。
                        if (layerBlock == null) {
                            Modifier.liquidPress(
                                interactionSource = interactionSource,
                                enabled = enabled
                            )
                        } else {
                            Modifier
                        },
                    )
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
            // 绘制期取值（不是组合期的 Float）：按下动画每帧只失效绘制。
            pressProgress = { pressProgress.value },
            layerBlock = layerBlock,
        )
        // 手势挂在整个链的**最后**（与 GlassButton / Chip / Fab / Segmented 一致）：
        // 它在内侧，真拖动时 consume 掉事件会让外层 clickable 取消按压；
        // 纯点击时它不消费，clickable 正常触发。顺序反了就是横拖误触发跳转。
        .then(gestureModifier)
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
    /**
     * 气泡下方的操作行插槽（复制等小动作键）。
     *
     * 贴在气泡**外**、气泡 Box 之后渲染：玻璃内容 padding（14/10dp）不动，
     * 操作键与气泡本体解耦，不参与气泡的 widthIn(max) 宽度约束；
     * Row 的对齐跟随外层 Column 的 isUser 对齐（End/Start），与气泡同轴。
     * null（默认）完全不渲染，流式气泡等调用点零成本。
     */
    actions: (@Composable RowScope.() -> Unit)? = null,
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
                    // 全 App 最长的 LazyColumn：一屏十几条 × 7 次采样必掉帧。
                    // 想开回来必须先拿真机 30+ 条气泡的滚动帧率数据。
                    dispersion = false,
                ),
        ) {
            if (isUser) {
                Box(
                    modifier = Modifier
                        // matchParentSize（BoxScope 专用）而非 fillMaxSize：
                        // fillMaxSize 会把 wrap-content 的父 Box 撑到 widthIn(max) 上限，
                        // 用户发「好」也是 340dp 满宽气泡（三线审查 UI#1，测量语义已独立复核）；
                        // matchParentSize 不参与父 Box 的子测量 pass，父 Box 依内容定尺寸后铺满。
                        .matchParentSize()
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
                    if (isStreaming) {
                        // 流式保持裸 Text，不包 SelectionContainer：流式文本每 120ms
                        // 变化会不断重置选区（刚选中就没了），且与列表的滚动跟随
                        // （snapshotFlow 驱动的 animateScrollToItem）打架。
                        Text(
                            text = text + "▍",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onGlass,
                            modifier = Modifier.padding(top = if (attachments.isEmpty()) 0.dp else 8.dp),
                        )
                    } else {
                        // 非流式（含用户气泡）包 SelectionContainer：长按可选、复制正文。
                        // 用户消息短，不存在流式重置选区的问题，一并开放选择。
                        SelectionContainer {
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.onGlass,
                                modifier = Modifier.padding(top = if (attachments.isEmpty()) 0.dp else 8.dp),
                            )
                        }
                    }
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
        // 操作行在气泡 Box 之后、同一 Column 里：跟随 isUser 对齐（End/Start），
        // 且不进气泡玻璃 —— 操作键多一层玻璃在 LazyColumn item 里纯属浪费。
        actions?.let {
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                it()
            }
        }
    }
}

private fun usageText(usage: GlassBubbleUsage): String {
    val tps = if (usage.tokensPerSecond > 0f) " · %.1f tok/s".format(Locale.US, usage.tokensPerSecond) else ""
    val ttft = if (usage.firstTokenLatencyMillis > 0L) " · 首字 ${usage.firstTokenLatencyMillis}ms" else ""
    // 流式实时指标（Wave3）只带 tok/s 与首字延迟，in/out 为 0：此时省略 token 计数前缀，
    // 避免「in 0 / out 0」的无意义占位。终态后引擎精确 usage 会覆盖为完整形态。
    val counts =
        if (usage.promptTokens > 0 || usage.completionTokens > 0) "in ${usage.promptTokens} / out ${usage.completionTokens}"
        else ""
    val body = "$counts$tps$ttft"
    return if (body.startsWith(" ·")) body.trimStart(' ', '·') else body
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
        // 展开后是几十行思考全文；且在气泡内随 LazyColumn 逐条渲染。
        // 判据见 GlassMaterial 文件头 KDoc：≥3 行正文禁 ULTRA_THIN，
        // 列表 item 内取 THIN（模糊成本 ×N，下限达标即止）。
        material = GlassMaterial.THIN,
        cornerRadius = tokens.radiusSm,
        onClick = onToggle,
        // 气泡内嵌套的容器，跟着气泡一起进 LazyColumn —— 同理关色散。
        dispersion = false,
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

// FlowRow 在 foundation 1.10.3 仍是 @ExperimentalLayoutApi（BOM 2026.02.00 核实），
// 必须显式 OptIn —— 这类"实验 API 转正与否"不能赌，编译器说了算。
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttachmentStrip(attachments: List<GlassBubbleAttachment>) {
    // 裸 Row 在窄屏会横向溢出被裁（三线审查 Wave10）：附件 chips 是信息展示，
    // 换行是正确 affordance（对比 ModelsScreen 预设 chips 的 horizontalScroll「主动横滑」）。
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        // 换行后的行间距沿用同一节奏：不设的话两行 chips 会贴死。
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
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
                // 附件条在气泡里逐条展开，同样按列表 item 处理。
                dispersion = false,
                contentPadding = PaddingValues(0.dp),
            ) {
                AttachmentThumb(uri = attachment.uri)
            }
        }
        else -> {
            LiquidGlassSurface(
                material = GlassMaterial.ULTRA_THIN,
                cornerRadius = tokens.radiusFull,
                // 附件条在气泡里逐条展开，同样按列表 item 处理。
                dispersion = false,
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
    // Wave 20：解码实现下沉到公开的 [GlassImageThumb]（输入框附件块复用同一组件，
    // 根修「裸绝对路径 decode 失败 → 永远显示占位图标」，见 GlassImageThumb KDoc）。
    // 这里只保留气泡附件条的尺寸口径：96×72、12dp 圆角。
    GlassImageThumb(
        uri = uri,
        modifier = Modifier.size(width = 96.dp, height = 72.dp),
    )
}
