package com.rickeal.agent.core.design

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rickeal.agent.core.design.liquid.EmptyBackdrop
import com.rickeal.agent.core.design.liquid.LocalBackdrop
import com.rickeal.agent.core.design.liquid.drawBackdrop
import com.rickeal.agent.core.design.liquid.highlight.Highlight
import com.rickeal.agent.core.design.liquid.shadow.InnerShadow
import com.rickeal.agent.core.design.liquid.shadow.Shadow
import kotlinx.coroutines.launch

/**
 * 液态弹窗。本仓库**唯一**的对话框组件（Wave 9 需求 6A 起，旧 GlassDialog 门面
 * 已删除、9 处调用点全部迁到这里）：自绘 `drawBackdrop` 结构 + 进出场动画，
 * 观感与 LiquidBottomTabs / GlassSegmented 同源。
 *
 * ## 为什么必须自绘而不是复用 LiquidGlassSurface
 *
 * `LiquidGlassSurface` 走的是 `liquidGlass` 门面，圆角矩形 + 均匀缩放；液态弹窗要的是
 * 「四层结构 + layerBlock 变换 + 退化路径」这套显式控制，和 LiquidBottomTabs 一致。
 *
 * ## 独立窗口 → 背景恒退化（关键约束）
 *
 * Dialog 跑在**独立窗口**里，主窗口录制的壁纸层在这里既对不齐也用不上。因此：
 *  - [LocalBackdrop] 提供 [EmptyBackdrop] —— 旧 GlassDialog 完全一致的写法；
 *  - 背景恒为 [EmptyBackdrop] → **恒走退化路径**（采样不到壁纸，blur / lens 无意义）：
 *    常驻 THICK 底色 + accent 描边 + 常驻高光。不依赖 `enableBackdropBlur` 开关。
 *
 * ## 宽度与遮罩（scrim）
 *
 *  - `DialogProperties(usePlatformDefaultWidth = false)` 关掉平台默认宽度上限，
 *    宽度完全自管：`fillMaxWidth()` + 左右各 20dp 边距。平台上限在平板 / 横屏 /
 *    桌面形态会把弹窗压成一条窄带，与「玻璃是全屏级模态面」的定位不符；
 *  - **不绘制自定义 scrim**：弹窗压暗靠系统 Dialog 自带的 dim。独立窗口里自绘
 *    scrim 只能盖住一块与壁纸对不齐的纯色矩形，观感是"糊了一层灰"，比不用更差。
 *
 * ## 进出场动画（不能"瞬间消失"）
 *
 * `Animatable` 驱动 `appear` 0→1：`scale 0.9→1` + `alpha 0→1`，施加在 `layerBlock` 上。
 * ⚠️ 出场时**必须先播完动画再真正 dismiss**：用局部 `visible` state 控制窗口显隐，
 * 动画归零后才置 `false` 并回调 [onDismissRequest]。返回键 / 点击外部 / 动作按钮
 * 三条路径都汇入同一个 [LiquidDialog] 内部的 dismiss 入口，保证一致。
 *
 * ## 关于"按压缩放"
 *
 * 弹窗是**模态面**，不是可按控件：给它挂 `InteractiveHighlight.gestureModifier` 会在
 * 越过 slop 后 consume 事件，**破坏弹窗内容里的滚动**。所以按压反馈由内容里的
 * [GlassButton] 各自承担（它们自带 liquidGlass + 按压形变），弹窗本体的 `layerBlock`
 * 只承载进出场缩放。
 *
 * @param icon 标题左侧的图标（可空）。
 * @param material 玻璃材质，默认 [GlassMaterial.THICK]（弹窗是最"重"的玻璃）。
 * @param dismissOnClickOutside 点击弹窗外部是否关闭。返回键恒可关闭。
 * @param actions 动作区。**回调参数 `dismiss` 必须用它关闭**（它先播完出场动画再
 *   真正 dismiss），否则调用方直接翻转自己的显隐 state 会让弹窗"瞬间消失"。
 *   动作区已被包进右对齐 Row（间距 10dp）—— 直接平铺按钮，不要再嵌套 Row。
 */
@Composable
fun LiquidDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    icon: ImageVector? = null,
    material: GlassMaterial = GlassMaterial.THICK,
    dismissOnClickOutside: Boolean = true,
    actions: (@Composable (dismiss: () -> Unit) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current
    val spec = GlassMaterials.of(material)
    val density = LocalDensity.current
    // 覆盖层 scrim（Wave 21）：alpha 由 GlassConfig.overlayOpacity 驱动（设置页
    // 「覆盖层不透明度」）。在组合期取值——draw 阶段的 lambda 里不能读
    // CompositionLocal（不在组合上下文，读取点必须像这里一样前移）。
    // 独立窗口恒走退化路径（THICK 底色，见类 KDoc），底色偏透，用户反馈文字
    // 可读性不足 —— scrim 压在表面绘制之上、内容之下，0=纯玻璃 1=完全不透明。
    val overlayOpacity = LocalGlassConfig.current.overlayOpacity

    // 窗口显隐（局部 state）：出场动画播完才置 false → 真正移除窗口。
    var visible by remember { mutableStateOf(true) }
    // 防重入：动作按钮双击 / 返回键连按不应叠加多条出场协程。
    var dismissing by remember { mutableStateOf(false) }
    // 进出场进度：0 = 消失（scale 0.9 / alpha 0），1 = 完全出现（scale 1 / alpha 1）。
    val appear = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // 进场：0 → 1。弹簧规格从 LocalLiquidMotion 取（此前写死 Default，
    // reduceMotion / 动效档位在这里会"失灵"——弹窗是高频模态入口，必须跟随主题）。
    // ⚠️ `CompositionLocal.current` 是 @Composable getter，**只能在组合期读**：
    //    直接在 LaunchedEffect 协程里读会编译红（@Composable 调用不允许出现在
    //    协程上下文）—— 先在组合期取值再进协程（全仓其余读取点同此写法）。
    //    key 用 motion：弹窗开着时动效档位变了会重启入场动画（从当前值继续，
    //    无视觉跳变），比写死 Unit 多一层正确性。
    val enterMotion = LocalLiquidMotion.current
    LaunchedEffect(enterMotion) {
        appear.animateTo(1f, LiquidMotion.floatSpring(enterMotion))
    }

    // 统一的关闭入口：先播完出场动画，再真正 dismiss。
    fun requestDismiss() {
        if (dismissing) return
        dismissing = true
        scope.launch {
            // 出场用 Snappy（快收敛），不让用户等一个"慢动作关闭"。
            appear.animateTo(0f, LiquidMotion.floatSpring(LiquidMotion.Snappy))
            visible = false
            onDismissRequest()
        }
    }

    if (!visible) return

    Dialog(
        onDismissRequest = { requestDismiss() },
        // usePlatformDefaultWidth = false：宽度自管（fillMaxWidth + 左右 20dp），
        // 摆脱平台默认宽度上限（平板/横屏会把弹窗压成窄带）。见 KDoc「宽度与遮罩」。
        properties = DialogProperties(
            dismissOnClickOutside = dismissOnClickOutside,
            usePlatformDefaultWidth = false,
        ),
    ) {
        // 独立窗口：主窗口的 LayerBackdrop 在这里既对不齐也用不上，
        // 降级为 EmptyBackdrop（见类 KDoc）。
        CompositionLocalProvider(
            LocalBackdrop provides EmptyBackdrop,
        ) {
            val radius = tokens.radiusXl
            val radiusPx = with(density) { radius.toPx() }
            val hasHeader = icon != null || title != null || subtitle != null

            Column(
                modifier = modifier
                    .fillMaxWidth()
                    // 屏幕左右各留 20dp：玻璃只覆盖弹窗本体，不铺满全屏。
                    .padding(horizontal = 20.dp)
                    .drawBackdrop(
                        backdrop = EmptyBackdrop,
                        shape = { RoundedCornerShape(radius) },
                        // 退化路径：无 blur / lens（背景为空，采样不到任何像素）。
                        effects = {},
                        // 常驻高光（不随按压 —— 退化路径的承诺之一，与 LiquidBottomTabs 一致）。
                        highlight = { Highlight.Default },
                        // 外阴影让弹窗"浮"在遮罩之上；内阴影给玻璃厚度。
                        shadow = { Shadow.Default },
                        innerShadow = { InnerShadow.Default },
                        layerBlock = {
                            // 进出场：scale 0.9→1 + alpha 0→1。
                            val progress = appear.value
                            val scale = lerp(0.9f, 1f, progress)
                            scaleX = scale
                            scaleY = scale
                            alpha = progress
                        },
                        onDrawSurface = {
                            // 退化路径（背景恒 EmptyBackdrop）：常驻 THICK 底色 + accent 描边。
                            // 描边用 drawRoundRect + Stroke（半径 = radiusXl，与 shape 的
                            // RoundedCornerShape 完全一致），避免 drawOutline 的引用解析问题。
                            drawRect(colors.glassTint.copy(alpha = spec.backgroundAlpha))
                            drawRoundRect(
                                color = colors.accent.copy(alpha = 0.35f),
                                cornerRadius = CornerRadius(radiusPx, radiusPx),
                                style = Stroke(width = 1.dp.toPx()),
                            )
                        },
                    )
                    // 内容内边距（在玻璃之内）。
                    // 覆盖层 scrim（Wave 21）：插在 drawBackdrop（表面绘制）之后、
                    // 内边距之前 —— 绘制层序上位于玻璃表面之上、内容之下，且覆盖
                    // 整个面板（含内边距带）。用 drawBehind 而非 wrap Box：本文件
                    // 层级是 Column 非 BoxScope，drawBehind 是最小侵入的等价写法
                    // （颜色在组合期已解析，draw lambda 只读局部 val）。
                    .drawBehind {
                        drawRect(colors.glassShadow.copy(alpha = overlayOpacity))
                    }
                    .padding(20.dp),
            ) {
                if (hasHeader) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (icon != null) {
                            Icon(
                                imageVector = icon,
                                // 装饰性图标：语义由 title 承担，避免读两遍。
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            if (title != null) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = colors.onGlass,
                                )
                            }
                            if (subtitle != null) {
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onGlassMuted,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
                Box(modifier = Modifier.padding(top = if (hasHeader) 12.dp else 0.dp)) {
                    content()
                }
                if (actions != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    ) {
                        // 把内部 dismiss 入口交给动作区：调用方用它关闭才能保住出场动画。
                        actions { requestDismiss() }
                    }
                }
            }
        }
    }
}
