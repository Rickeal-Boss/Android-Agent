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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.GlassIconButton
import com.rickeal.agent.core.design.GlassMaterial
import com.rickeal.agent.core.design.LocalBottomBarOverlay
import com.rickeal.agent.core.design.LocalGlassColors
import com.rickeal.agent.core.design.LocalGlassConfig
import com.rickeal.agent.core.design.LocalGlassTokens
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
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current
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
                    .background(colors.glassShadow.copy(alpha = 0.35f))
                    .pointerInput(onDismiss) { detectTapGestures { onDismiss() } },
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // 底部悬浮玻璃页签浮在 sheet 之上（真机截图实锤：页签直接压进
                    // 参数行）。整个 sheet 上抬到页签上沿：先 navigationBarsPadding
                    // 再 padding(overlay)，顺序与 GlassScaffold 对 FAB 的处理同构 ——
                    // 先吃系统导航栏，再垫悬浮页签占位，两层互不吞并。
                    .navigationBarsPadding()
                    .padding(bottom = LocalBottomBarOverlay.current)
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
                    ),
            ) {
                // 覆盖层自身压实 scrim：THICK 材质在壁纸/聊天内容上偏"透"，
                // 参数行文字对比度不足（与 :66 的全屏 dim Box 是两回事 —— 那个
                // 压暗的是 sheet 背后的页面，这里压的是 sheet 自己，后者保留）。
                // 不透明度由设置页「覆盖层不透明度」经 LocalGlassConfig 驱动，
                // 此处只读消费（GlassConfig.overlayOpacity，并行成员接线中）。
                // matchParentSize 是 BoxScope 成员：玻璃 Box 就是 Box，直接可用，
                // 且不像 fillMaxSize 会反过来撑大父容器。
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(colors.glassShadow.copy(alpha = LocalGlassConfig.current.overlayOpacity)),
                )
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
                            // 抽屉底部 78% 高度 + 键盘几乎必然重叠，最下面的「系统提示词」
                            // 输入框会被键盘盖住。顺序为「先 ime 后 nav」：API 30+ 的
                            // Type.ime() 只报键盘自身高度、不含导航栏，两者相加才对
                            // （与 ChatInputBar 的做法一致）。
                            //
                            // 2026-09-26：删掉 navigationBarsPadding —— sheet 底边已经在
                            // 玻璃 Box 上抬到页签上沿（含导航栏），内容层再垫一次是双倍
                            // 空隙；imePadding 保留，键盘弹出时继续顶内容。
                            .imePadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
