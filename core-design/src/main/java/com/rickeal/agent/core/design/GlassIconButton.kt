package com.rickeal.agent.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.liquid.interactive.InteractiveHighlight
import com.rickeal.agent.core.design.liquid.shapes.Capsule

/**
 * 小尺寸图标按钮的轮廓。
 *
 * - [Circle]：正圆，触摸区是 48dp 的正方形（图标按钮的默认形态）。
 * - [Capsule]：胶囊，**高度**锁 48dp、宽度由内容撑开（带文字的按钮用它，
 *   否则长文本会被固定宽度裁掉）。
 */
enum class GlassIconButtonShape { Circle, Capsule }

/**
 * 玻璃图标按钮 —— 补齐"小的元素没有液态反馈"的缺口。
 *
 * ## 为什么需要它（而不是复用 [GlassButton]）
 *
 * [GlassButton] 是 `Text` 必填的文本按钮：内边距 `18dp/12dp`、`labelLarge` 字号，
 * 套在 20dp 的图标上会撑成一个明显偏大的胶囊。而顶栏图标 / 输入区图标 / 发送按钮
 * 这类元素在 App 里有十几处，此前全是裸 `Box + clickable`：**没有任何液态反馈**
 * （不跟手、不形变、按下无变化），用户真机反馈的"大部分小元素都没加入 Liquid Buttons"
 * 指的就是它们。
 *
 * ## 与 Button / Chip / Fab 同一套机制
 *
 * 按下形变、tanh 阻尼跟手、各向异性拉伸全部走 [pressLayerBlock]；
 * 玻璃本体走 `liquidGlass`；手势源走 [InteractiveHighlight]。
 * **没有新造体系**——参数取值（`blurRadius = 2.dp` / `lens(12, 24)` / `dispersion = false`）
 * 与 [GlassButton]、[GlassChip]、[GlassFab] 完全一致。
 *
 * ## 两个必须遵守的顺序（Kyant0 原序）
 *
 *  1. `clickable` 必须在 `gestureModifier` **之前**。颠倒后 `clickable` 会先吃掉手势，
 *     跟手位移直接失效（静态截图看不出来，真机一按就露馅）。
 *  2. `indication = null`：液态玻璃自带高光与内阴影，再叠一层 M3 ripple 就成了
 *     "原生按钮贴玻璃纸"。**不要**改回 `LocalIndication.current`。
 *
 * ## [pressOnly]：顶栏专用形态
 *
 * 顶栏图标**位于 [GlassTopBar] 自己的玻璃之上**，再叠一层玻璃会有两个问题：
 *  1. 玻璃叠玻璃 → 观感浑浊；
 *  2. 复现 [GlassTopBar] 记录过的"镜面高光从边缘溢出、盖住标题文字和 action 图标"。
 *
 * 所以 `pressOnly = true` 时**不挂 `liquidGlass`**，只保留交互层：
 * [InteractiveHighlight.modifier]（触摸点高光）+ `gestureModifier`（手势源）
 * + 跟手形变。用户得到的反馈是"按下去这块玻璃跟着手指动、落点亮起来"，
 * 而不是"又贴了一块玻璃"。
 *
 * ## 退化路径
 *
 * `GlassConfig.enableBackdropBlur = false`（设置页的性能开关）时**不挂玻璃**——
 * 与 [LiquidBottomTabs] / [GlassSegmented] 的退化分支同一约定：背景模糊关掉后
 * 采样壁纸没有意义，留着玻璃只会是"像素级透出壁纸"。
 * 注意：退化时交互层仍然保留（跟手形变不依赖玻璃）。
 *
 * ## 无障碍
 *
 * [contentDescription] 在图标重载里是**必填**参数（现状多处传 `null`，一并补齐）；
 * 触摸目标由 [size] 撑满（默认 `48.dp`，即 [GlassTokens.minTouchTarget]）。
 *
 * @param size 触摸目标尺寸。`Circle` 取正方形边长；`Capsule` 取最小高度。
 * @param iconSize 图标自身的绘制尺寸。**默认 20dp**（顶栏 / 发送按钮的原尺寸），
 *   但输入区图片 / 音频图标原本是 24dp、附件移除与提示条关闭是 16dp ——
 *   调用点按需传入即可**保持原有视觉尺寸**，不因为换组件而偷偷放大或缩小。
 * @param containerColor 容器底色。非空时铺一层实色，并**自动关闭玻璃** ——
 *   本项目强调色不透明（`accent` alpha = FF），玻璃会被完全盖住，叠了只是白付开销。
 *   发送按钮这类"强调色实心圆"用它：保留强调色的可操作性暗示，同时拿到跟手形变。
 * @param pressOnly 见上。顶栏图标传 `true`。
 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: GlassIconButtonShape = GlassIconButtonShape.Circle,
    size: Dp = 48.dp,
    iconSize: Dp = 20.dp,
    material: GlassMaterial = GlassMaterial.ULTRA_THIN,
    contentColor: Color = LocalGlassColors.current.onGlass,
    containerColor: Color? = null,
    pressOnly: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(12.dp),
) {
    GlassIconButton(
        onClick = onClick,
        modifier = modifier,
        contentDescription = contentDescription,
        enabled = enabled,
        shape = shape,
        size = size,
        material = material,
        containerColor = containerColor,
        pressOnly = pressOnly,
        contentPadding = contentPadding,
    ) {
        Icon(
            imageVector = icon,
            // 语义已提到外层（contentDescription 参数），这里必须置 null，
            // 否则 TalkBack 会把同一个名字念两遍。
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * 内容插槽重载 —— 供**带文字**的小元素使用（顶栏「返回」、卡片里的「编辑 / 删除」等）。
 *
 * 它们和图标按钮是同一类元素（同样是裸 `Box + clickable`、同样缺液态反馈），
 * 只是内容不是 `ImageVector`。所以共用同一套玻璃与手势，而不是为文字再写一份。
 *
 * 用 `Capsule` + 不锁宽度，长文本不会被裁。
 */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    enabled: Boolean = true,
    shape: GlassIconButtonShape = GlassIconButtonShape.Capsule,
    size: Dp = 48.dp,
    material: GlassMaterial = GlassMaterial.ULTRA_THIN,
    containerColor: Color? = null,
    pressOnly: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val config = LocalGlassConfig.current
    val tokens = LocalGlassTokens.current
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) { InteractiveHighlight(animationScope) }
    val interactive = enabled
    val capsule = shape == GlassIconButtonShape.Capsule
    val clipShape = if (capsule) Capsule else CircleShape
    // 三种情况都不叠玻璃：
    //  1. pressOnly —— 顶栏形态，见 KDoc；
    //  2. containerColor != null —— 实心底色会**完全盖住**玻璃。本项目强调色是
    //     不透明的（`accent = Color(0xFF2B6BFF)`，alpha = FF），叠了看不见，
    //     只是白付一遍模糊 + 折射的绘制开销。发送按钮就属于这一类；
    //  3. 背景模糊总闸关掉（设置页性能开关）—— 采样壁纸没意义了。
    val enableGlass = !pressOnly && containerColor == null && config.enableBackdropBlur

    Box(
        modifier = modifier
            .then(
                // contentDescription 非空时，把语义合并到容器上**一次**：
                // 内容里的文字（如「返回」）会被 mergeDescendants 并进来，
                // 但 contentDescription 优先，所以 TalkBack 只念一遍。
                // 内容自带可见文字时通常不必传它（传了也是覆盖，语义一致）。
                if (contentDescription != null) {
                    Modifier.semantics(mergeDescendants = true) {
                        this.contentDescription = contentDescription
                    }
                } else {
                    Modifier
                },
            )
            .then(
                if (interactive) {
                    Modifier.clickable(
                        interactionSource = null,
                        indication = null,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            // 跟手形变**不依赖玻璃**：所以单独挂在这里，pressOnly 与退化路径都能拿到。
            // 也正因为已经在这里做了形变，下面的 liquidGlass 一律传 layerBlock = null，
            // 否则同一套形变会叠两次（形变过头，且两处参数将来会分叉）。
            .then(
                if (interactive) {
                    Modifier.graphicsLayer(
                        pressLayerBlock(interactiveHighlight = interactiveHighlight, maxScale = 4.dp)
                    )
                } else {
                    Modifier
                },
            )
            .then(
                if (enableGlass) {
                    Modifier.liquidGlass(
                        material = material,
                        capsule = capsule,
                        cornerRadius = if (capsule) tokens.radiusFull else size / 2,
                        // 与 Button / Chip / Fab 同一组取值：模糊只做柔化，折射才是主角。
                        blurRadius = 2.dp,
                        refractionHeight = 12.dp,
                        refractionAmount = 24.dp,
                        // 小面积高频件：色散 7 次采样不划算，开销留给大面积容器。
                        dispersion = false,
                        intensity = if (enabled) 1f else 0.6f,
                        pressProgress = { interactiveHighlight.pressProgress },
                        layerBlock = null,
                    )
                } else {
                    Modifier
                },
            )
            // 裁剪：触摸点高光是 `drawWithContent` 在内容之后画的径向渐变，
            // 半径 ≈ min(w,h) * 0.9。不裁的话它会溢出节点边缘、糊到相邻元素上
            // （GlassTopBar 就因为这个原因放弃了高光修饰器）。
            // 这里节点只有 48dp，裁到自身轮廓即可，既不溢出也不影响形变
            // （形变在更外层的 graphicsLayer 上）。
            .clip(clipShape)
            .then(
                if (containerColor != null) Modifier.background(containerColor) else Modifier,
            )
            .then(
                if (interactive) {
                    // 顺序不能交换：clickable 在前、gestureModifier 在后（Kyant0 原序）。
                    Modifier
                        .then(interactiveHighlight.modifier)
                        .then(interactiveHighlight.gestureModifier)
                } else {
                    Modifier
                },
            )
            // Circle 锁正方形边长；Capsule 只锁最小高度，宽度交给内容 ——
            // 否则「查看完整条款」这类长文本会被固定宽度裁掉。
            .then(if (capsule) Modifier.heightIn(min = size) else Modifier.size(size))
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
