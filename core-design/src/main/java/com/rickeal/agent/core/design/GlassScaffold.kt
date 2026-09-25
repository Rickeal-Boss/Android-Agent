package com.rickeal.agent.core.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.liquid.LocalBackdrop
import com.rickeal.agent.core.design.liquid.backdrops.layerBackdrop
import com.rickeal.agent.core.design.liquid.backdrops.rememberLayerBackdrop
import kotlin.math.roundToInt

/**
 * 自定义壁纸图层（Wave 9 需求 3b）：app 层（LiquidAgentApp）按 DataStore 路径解码后
 * 在根组合 provide，7 个业务屏的 [GlassScaffold] 经默认参数取用 —— feature 屏的
 * 签名与调用点零改动。null = 使用程序化壁纸（三色渐变 + 光斑场）。
 *
 * 用 [staticCompositionLocalOf]：换壁纸整个生命周期只发生两三次（选图 / 恢复默认 /
 * 冷启动），「值变化 → 整棵子树重组」的代价可以接受，换来的是读取处零开销。
 * 解码缓存刻意放在 app 根组合的 `remember` 里，不做进程级单例 —— 组合销毁后
 * Bitmap 交给 GC，泄漏面更小。
 */
val LocalWallpaperImage: ProvidableCompositionLocal<ImageBitmap?> =
    staticCompositionLocalOf { null }

/**
 * 底部悬浮页签的**占位高度**（2026-09-26「Tab 悬浮叠层」需求）。
 *
 * app 层 MainShell 把 [LiquidBottomTabs] 从独立布局槽改为**悬浮叠层**（Box 覆盖在
 * NavHost 之上，对齐参考设计的"页签叠在页面上"形态）后，页面内容会延伸到页签
 * 区域底下 —— 本地值告诉各屏：滚动内容的**内边距**（contentPadding / 尾部 padding，
 * 在滚动容器**之内**）要加这么多，让末尾条目能滚出页签区；玻璃页签下透出的正是
 * 滚过的内容与壁纸（refraction 实时跟随）。
 *
 *  - app MainShell 在 COMPACT（显示 GlassNavBar）时 provide **84.dp**
 *    （TabBarHeight 64 + GlassNavBar 上下 padding 10×2 —— 与 GlassNavBar 的实际
 *    组成同步，改那边必须同步这边）；
 *  - 宽屏（GlassNavRail，无底栏）不 provide，默认 0.dp；
 *  - [GlassScaffold] 内部也用它抬升 FAB / snackbar 槽位。
 *
 * ⚠️ 必须消费在**滚动内边距**上，不要挂在滚动容器自身的 layout padding 上：
 * 后者会把视口压短，内容就不再"穿过"页签了（叠层失去意义）。
 */
val LocalBottomBarOverlay: ProvidableCompositionLocal<Dp> =
    staticCompositionLocalOf { 0.dp }

/**
 * 玻璃骨架。壁纸铺底 → 顶栏 → 内容 → 底栏 → FAB / Snackbar 浮层。
 * 刻意不用 material3 的 Scaffold：我们需要壁纸贯穿整个层级，且 inset 由调用方决定。
 *
 * 壁纸那一层经 [layerBackdrop] 录制进 LayerBackdrop 的 GraphicsLayer 并通过
 * `LocalBackdrop` 下发 —— 这是所有玻璃节点（`liquidGlass`）「真实背景模糊 /
 * 折射」的唯一来源（旧的玻璃背景录制实现已随死代码清理移除）。
 *
 * [wallpaperImage] 是自定义壁纸的显式覆盖点：默认取 [LocalWallpaperImage]（app 根
 * 组合下发），透传给默认的 [GlassWallpaper]。传 null 强制用程序化壁纸（预览场景）。
 * 注意默认 wallpaper lambda 引用了前置参数 —— Kotlin 允许默认值引用排在它之前的参数，
 * 这正是「参数透传」与「调用点零改动」能同时成立的原因。
 */
@Composable
fun GlassScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    wallpaperImage: ImageBitmap? = LocalWallpaperImage.current,
    wallpaper: @Composable () -> Unit = {
        GlassWallpaper(modifier = Modifier.fillMaxSize(), backgroundImage = wallpaperImage)
    },
    contentWindowInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
    content: @Composable (PaddingValues) -> Unit,
) {
    // 新液态玻璃引擎的背景源：壁纸被录进 LayerBackdrop 的 GraphicsLayer，
    // 所有 drawBackdrop 节点（即 liquidGlass）从这里取背景做模糊/折射。
    // 不下发它玻璃就拿不到壁纸，会退化成「无背景的纯色半透明面板」。
    val layerBackdrop = rememberLayerBackdrop()
    // 悬浮页签占位（COMPACT 下 84dp，见 LocalBottomBarOverlay KDoc）：
    // 抬升 FAB / snackbar 槽位 + 下发 content 的默认 bottom padding。
    val overlay = LocalBottomBarOverlay.current
    CompositionLocalProvider(
        LocalBackdrop provides layerBackdrop,
    ) {
        Box(modifier = modifier.fillMaxSize()) {
            // 壁纸层：内容录制进背景图层，供玻璃节点取用（本身观感不变）。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(layerBackdrop),
            ) {
                wallpaper()
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(contentWindowInsets),
            ) {
                topBar()
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    content(PaddingValues(bottom = overlay))
                }
                bottomBar()
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    // 三键导航下导航栏高 48dp，只靠 20.dp 的 bottom padding 会让 FAB
                    // 约 28dp 落进导航栏区域：视觉上被压住，点击也容易被导航栏吃掉。
                    // navigationBarsPadding() 必须在 .padding(...) 之前 —— 先让出系统
                    // 导航区，再在剩余空间里加设计给的 20dp 边距。
                    // + overlay：悬浮页签占位（2026-09-26）—— FAB 不再压在页签底下。
                    .navigationBarsPadding()
                    .padding(end = 20.dp, bottom = 20.dp + overlay)
                    .windowInsetsPadding(contentWindowInsets),
            ) {
                floatingActionButton()
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 104.dp + overlay),
            ) {
                snackbarHost()
            }
        }
    }
}

/**
 * 程序化壁纸：**纯色底**（2026-09-26 用户需求：默认只米白色）。必须铺满 Scaffold 底层。
 *
 * [backgroundImage] 非空时**完全替换**背景（同日需求：照片不再与任何叠加层混合）：
 * 只画照片本身，不再叠光斑层与 scrim 色温层。渲染侧无特殊处理 —— 照片层
 * 依旧在 `layerBackdrop` 录制范围内，玻璃节点的模糊/折射照常取到它
 *（替换的是"画什么"，不是"录不录"）。
 *
 * drawImage 是 [androidx.compose.ui.graphics.drawscope.DrawScope] 的**成员函数**，
 * 在 DrawWithContent 的接收者作用域内直接可用，无需 import。
 *
 * ⚠️ 历史申报：旧实现 = 三色渐变 + 7 色光斑场（供折射"内容"）+ 照片模式的
 * scrim 罩层。用户裁定光斑彩色背景整体去除、默认只米白、照片直接替换 ——
 * 折射在纯色底上不再有可见内容（玻璃只剩磨砂与边缘形变），照片模式下不再有
 * 色温调和（文字可读性交给照片本身），均为本需求的直接后果。
 *
 * [solidColor] 独立成参数（默认按深浅色取米白 / 深底），预览或特殊容器可覆盖。
 */
@Composable
fun GlassWallpaper(
    modifier: Modifier = Modifier,
    backgroundImage: ImageBitmap? = null,
    solidColor: Color = if (LocalGlassColors.current.isDark) {
        Color(0xFF0B1020)
    } else {
        // Wave 9「浅色米白」的中段色：暖调米白，玻璃的蓝 accent 在暖底上更出挑。
        Color(0xFFF6F1E4)
    },
) {
    Box(
        modifier = modifier
            .drawWithCache {
                onDrawBehind {
                    if (backgroundImage != null) {
                        // 照片层直接铺满画布（dstSize 显式拉伸 —— 不裁剪，与旧行为
                        // 一致；导入时降采样已按屏幕最长边归一）。**只画照片**：
                        // 不叠光斑、不叠 scrim，"替换背景"而非"叠加在背景上"。
                        drawImage(
                            image = backgroundImage,
                            dstOffset = IntOffset.Zero,
                            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                            filterQuality = FilterQuality.Medium,
                        )
                    } else {
                        drawRect(solidColor)
                    }
                }
            },
    )
}
