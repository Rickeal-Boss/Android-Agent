package com.rickeal.agent.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.GlassIconButton
import com.rickeal.agent.core.design.GlassMaterial
import com.rickeal.agent.core.design.LocalBottomBarOverlay
import com.rickeal.agent.core.design.LocalGlassColors
import com.rickeal.agent.core.design.LocalGlassConfig
import com.rickeal.agent.core.design.LocalGlassTokens
import com.rickeal.agent.core.design.OVERLAY_TEXT_MIN_SCRIM
import com.rickeal.agent.core.design.asOverlayColors
import com.rickeal.agent.core.design.liquidGlass
import com.rickeal.agent.core.model.InferenceConfig

/**
 * COMPACT / MEDIUM 下的参数底部抽屉。
 * 刻意不用 material3 的 ModalBottomSheet：签名跨版本漂移风险高，
 * 这里用 AnimatedVisibility + 自绘玻璃，观感也更接近 iOS 27 的 sheet。
 */
@Composable
fun ChatParamsSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    config: InferenceConfig,
    onParamPreview: ((InferenceConfig) -> InferenceConfig) -> Unit,
    onParamCommit: () -> Unit,
    onParamChange: ((InferenceConfig) -> InferenceConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 覆盖层根取色（UI-1019 Batch 1）：sheet 表面恒深色（THICK + scrim + 平台
    // dim，实测亮度 #59~#7F），深色 onGlass 对比度仅约 3.9:1 ⇒ 直采的 colors
    // 取 overlay 白族。只换 onGlass 三件套：背后页面的 scrim（glassShadow）、
    // accent 不动。嵌套的 ChatParamsContent 在自身组合期读 LocalGlassColors，
    // 由下面 content 里的 CompositionLocalProvider 继承 overlay 版。
    val colors = LocalGlassColors.current.asOverlayColors()
    val tokens = LocalGlassTokens.current
    // Wave 51 F1：键盘弹出时底部导航栏已被键盘完全遮住 ⇒ 不再需要 overlay 占位（否则
    // sheet 被 imePadding 顶到键盘上方后又多垫一份）。derivedStateOf 收敛重组（直接读
    // WindowInsets.ime 会随键盘动画逐帧重组）。
    // ⚠️ `WindowInsets.ime` 是 @Composable @ReadOnlyComposable 取值 ⇒ 必须在组合作用域内
    // 先取出实例，再在 derivedStateOf 的普通 lambda 里读 getBottom。
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val imeVisible by remember(imeInsets, density) {
        derivedStateOf { imeInsets.getBottom(density) > 0 }
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { fullHeight -> fullHeight } + fadeIn(),
        exit = slideOutVertically { fullHeight -> fullHeight } + fadeOut(),
        modifier = modifier,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 页面压暗 0.35 → 0.45（UI-1019 Batch 2）：sheet 改为通底后，
                    // 它自身的玻璃采样的是**更亮的页面区域**（原先被页签/导航栏占位
                    // 抬起的部分不复存在），压暗不足会让白字对比度掉到 AA 以下。
                    // 该 dim 压的是 sheet 背后的页面，与 sheet 自身 scrim 是两回事。
                    .background(colors.glassShadow.copy(alpha = 0.45f))
                    .pointerInput(onDismiss) { detectTapGestures { onDismiss() } },
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // 通底（UI-1019 Batch 2，用户需求「覆盖层应当延伸至底部」）：
                    // 玻璃底边直达屏幕底（含系统导航栏区域），底角保持直角
                    // （shapeOverride 只圆上两角）。**不再**用 navigationBarsPadding /
                    // 页签占位把整块 sheet 抬起来 —— 那是旧形态（sheet 悬在页签上沿，
                    // 底部漏一条页面底色，看起来"没铺满"）。
                    .fillMaxWidth()
                    .fillMaxHeight(0.78f)
                    .clip(RoundedCornerShape(topStart = tokens.radiusXl, topEnd = tokens.radiusXl))
                    .liquidGlass(
                        material = GlassMaterial.THICK,
                        cornerRadius = tokens.radiusXl,
                        // 底部 sheet 只需上方两角圆。若只传 cornerRadius，
                        // liquidGlass 内部会按对称圆角裁剪，底角也会被圆 —— 浮在半屏很违和。
                        shapeOverride = RoundedCornerShape(
                            topStart = tokens.radiusXl,
                            topEnd = tokens.radiusXl,
                        ),
                        // 半屏级大面积容器，色散 7 次采样在这里纯属白烧 —— 显式关掉。
                        // 写死而不是依赖默认值：以后默认值被翻回去时这里不会跟着打开。
                        dispersion = false,
                        // 覆盖层面板：柔化白描边与高光，消除深色面板上的"白框"隔开感。
                        overlayPanel = true,
                    ),
            ) {
                // 覆盖层自身压实 scrim：THICK 材质在壁纸/聊天内容上偏"透"，
                // 参数行文字对比度不足（与上面全屏 dim Box 是两回事 —— 那个
                // 压暗的是 sheet 背后的页面，这里压的是 sheet 自己，后者保留）。
                // 取 max(用户滑条, OVERLAY_TEXT_MIN_SCRIM)：白族文字的可读性下限，
                // 实测 0.45 时 sheet 面板 #8B909A 仅 3.2:1（判据见该常量 KDoc）。
                // matchParentSize 是 BoxScope 成员：玻璃 Box 就是 Box，直接可用，
                // 且不像 fillMaxSize 会反过来撑大父容器。
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            colors.glassShadow.copy(
                                alpha = maxOf(
                                    LocalGlassConfig.current.overlayOpacity,
                                    OVERLAY_TEXT_MIN_SCRIM,
                                ),
                            ),
                        ),
                )
                // 覆盖层文字恒白（UI-1019 Batch 1）：包在玻璃表面绘制之后、内容之前
                // （不碰 liquidGlass 修饰符）。让在自身组合期读 LocalGlassColors 的
                // 嵌套组件（GlassIconButton / ChatParamsContent）继承白族文本色；
                // 直采的 colors 上面已取 overlay 版，对入参幂等。
                CompositionLocalProvider(
                    LocalGlassColors provides colors.asOverlayColors(),
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "推理参数",
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.onGlass,
                                modifier = Modifier.weight(1f),
                            )
                            // 抽屉头部的关闭键：触摸区 48dp 由组件保证，按下有跟手形变。
                            // 图标保持原来的 24dp（此前 Icon 没写显式尺寸，取默认 24dp）。
                            GlassIconButton(
                                icon = Icons.Filled.Close,
                                contentDescription = "关闭",
                                onClick = onDismiss,
                                contentColor = colors.onGlassMuted,
                                iconSize = 24.dp,
                            )
                        }
                        ChatParamsContent(
                            config = config,
                            onParamPreview = onParamPreview,
                            onParamCommit = onParamCommit,
                            onParamChange = onParamChange,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                // 项目走了 setDecorFitsSystemWindows(false)（edge-to-edge），
                                // sheet 通底后内容必须自己避让两层 inset：
                                //  - imePadding：键盘弹出时把内容顶起来；
                                //  - navigationBarsPadding：手势条 / 三键导航区。
                                // 顺序「先 ime 后 nav」：API 30+ 的 Type.ime() 只报键盘
                                // 自身高度、不含导航栏，两者相加才对（与 ChatInputBar 一致）。
                                //
                                // 末尾再垫「悬浮页签占位 + 12dp」——这是用户要求的
                                // **与底部 Tab 栏隔断**：页签是 MainShell 的悬浮玻璃，
                                // 绘制序在 NavHost 之后、天然浮在 sheet 之上；12dp 间隙
                                // 让最后一行控件与页签之间有可见的分界，而不是贴死/被压。
                                // 键盘弹出时页签被键盘完全遮住 ⇒ 该占位归零（Wave 51 F1）。
                                .imePadding()
                                .navigationBarsPadding()
                                .padding(
                                    bottom = if (imeVisible) {
                                        0.dp
                                    } else {
                                        LocalBottomBarOverlay.current + 12.dp
                                    },
                                )
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
