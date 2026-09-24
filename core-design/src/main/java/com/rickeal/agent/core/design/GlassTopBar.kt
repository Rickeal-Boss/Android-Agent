package com.rickeal.agent.core.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.liquid.interactive.InteractiveHighlight

/**
 * 悬浮玻璃顶栏（iOS 27 风格：栏是"浮"在内容上的一块玻璃，不是一条实色横条）。
 *
 * **刻意保持全宽、不改成胶囊**：顶栏横跨整个屏幕宽度，做成胶囊（两端半圆）会把
 * 标题和 actions 挤到圆角里。这里用 `radiusFull`（999dp）让上下边缘圆到半高，
 * 观感上已经是"浮起来的一条玻璃"，不需要胶囊。
 */
@Composable
fun GlassTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current
    // 顶栏恒定超薄材质：它是**功能层**（控制 / 导航 / 悬浮层），
    // 见 GlassMaterial 文件头 KDoc 的豁免条款 —— 功能层不按"文本量"选档，
    // 标题只有单行，也没有长文容器需要压光斑。
    // （原实现有 scrollFraction → collapsed → THIN + 分隔线的折叠分支，
    //   7 个调用点 0 处传参 ⇒ 该分支恒为 false，属死代码，已整链删除。）
    val material = GlassMaterial.ULTRA_THIN
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) { InteractiveHighlight(animationScope) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .heightIn(min = tokens.topBarHeight)
                .liquidGlass(
                    material = material,
                    cornerRadius = tokens.radiusFull,
                    // 逐组件折射：顶栏比按钮大得多，折射带要给足才有厚度感
                    // （对齐 Kyant0 LiquidBottomTabs 的 lens(24, 24)）。
                    refractionHeight = 24.dp,
                    refractionAmount = 24.dp,
                    // 全宽大面积容器：色散 7 次采样扛不住，必须关。折射仍开。
                    dispersion = false,
                    // 触摸/拖动顶栏时玻璃"变实"（模糊减弱 + 折射增强 + 高光变亮）。
                    pressProgress = { interactiveHighlight.pressProgress },
                    layerBlock = pressLayerBlock(
                        interactiveHighlight = interactiveHighlight,
                        maxScale = 4.dp
                    ),
                )
                // ⚠️ 刻意**不**挂 InteractiveHighlight 的高光修饰器，只挂 gestureModifier：
                // 那个修饰器会在玻璃之后画一层**镜面高光**，半径 = min(w,h) * 0.9 ——
                // 顶栏是全宽 × 56dp，半径算出来 ≈ 50dp，会从边缘溢出去盖住标题文字
                // 和右侧 action 图标（drawWithContent 在玻璃之后绘制，压在内容之上）。
                // 设置行（全宽 × 48dp）是同一类问题，见 GlassSettingRow 的注释。
                .then(interactiveHighlight.gestureModifier)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (navigationIcon != null) {
                Box(modifier = Modifier.padding(end = 4.dp)) { navigationIcon() }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onGlass,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onGlassSubtle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (actions != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
    }
}

/**
 * 底部玻璃栏（导航 / 输入栏容器）。
 *
 * 刻意**不接跟手手势**：底栏里通常是输入框 / 导航按钮 / 语音键，再挂一层拖拽
 * 手势会跟文本选择拖动、按钮点击抢事件。它也不该跟着手指晃 —— iOS 的底栏
 * 在有输入焦点时是稳的。折射与色散策略照大面积容器处理（关色散）。
 */
@Composable
fun GlassBottomBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val tokens = LocalGlassTokens.current
    Row(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .heightIn(min = tokens.bottomBarHeight)
            .liquidGlass(
                material = GlassMaterial.THICK,
                cornerRadius = tokens.radiusXl,
                // 全宽大面积：关色散（7 次采样）。折射仍开，用 THICK 材质自带的 16/28。
                dispersion = false,
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
