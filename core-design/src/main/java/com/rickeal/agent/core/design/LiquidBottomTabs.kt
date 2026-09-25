package com.rickeal.agent.core.design

import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.rickeal.agent.core.design.liquid.EmptyBackdrop
import com.rickeal.agent.core.design.liquid.LocalBackdrop
import com.rickeal.agent.core.design.liquid.backdrops.layerBackdrop
import com.rickeal.agent.core.design.liquid.backdrops.rememberCombinedBackdrop
import com.rickeal.agent.core.design.liquid.backdrops.rememberLayerBackdrop
import com.rickeal.agent.core.design.liquid.drawBackdrop
import com.rickeal.agent.core.design.liquid.effects.blur
import com.rickeal.agent.core.design.liquid.effects.lens
import com.rickeal.agent.core.design.liquid.effects.vibrancy
import com.rickeal.agent.core.design.liquid.highlight.Highlight
import com.rickeal.agent.core.design.liquid.interactive.DampedDragAnimation
import com.rickeal.agent.core.design.liquid.interactive.InteractiveHighlight
import com.rickeal.agent.core.design.liquid.shadow.InnerShadow
import com.rickeal.agent.core.design.liquid.shadow.Shadow
import com.rickeal.agent.core.design.liquid.shapes.Capsule
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * vibrancy 的饱和度系数。
 * 与 `LiquidGlassModifier` 的同名常量同一取值：Kyant0 默认 1.5，但我们的壁纸是
 * 浅色渐变 + 光斑（不是照片），1.5 会把浅色推成荧光色。降到 1.22 —— 提鲜但不失真。
 */
private const val VibrancySaturation = 1.22f

/**
 * 页签几何的**单一来源**（Wave 10 Phase 2d 纠正轮）。
 *
 * ⚠️ 前三个量在**布局**（玻璃条 / 回显行 / 胶囊的 `.height()`、`.padding()`）与
 * **起手门禁**（`canStartDrag` 的 x / y 判据）两处使用。若各写一份字面量，改一处忘
 * 另一处会让门禁**静默错位**（不报错、行为错）—— 必须共用这里的常量。
 *  · [TabBarHeight]：玻璃条（第 1 层）高，也是**静态手势宿主的纵向范围**。
 *  · [TabCapsuleHeight]：胶囊（第 3 层）高，也是回显行（第 2 层）高。
 *  · [TabPad]：左右内边距 —— 胶囊格相对容器左右各内缩这么多，也是
 *    `tabWidth` 计算里那个"两侧共 2×"的来源。
 *  · [TabPressedHeight]：胶囊按下时的高度；`pressedScale` 由它与 [TabCapsuleHeight]
 *    求比值 —— 改胶囊高时按压比例自动跟随，不留"分母与胶囊高脱钩"的第二份来源。
 */
private val TabBarHeight = 64.dp
private val TabCapsuleHeight = 56.dp
private val TabPad = 4.dp

/** 胶囊按下时的高度 —— Kyant0 BottomTabs 实测值（56dp → 78dp）。 */
private val TabPressedHeight = 78.dp

/**
 * 单个页签的声明式描述。图标 + 文本，由调用方（app 模块）从自己的导航模型映射而来
 * （`:core-design` 不依赖任何导航库，见架构 §1.4）。
 */
@Immutable
data class TabSpec(
    val label: String,
    val icon: ImageVector,
)

/**
 * 页签内容的按压缩放下发通道，由主组件按 [DampedDragAnimation.pressProgress] 驱动。
 *
 * 入参是该页签**是否处于选中格**（2026-09-26 双影注册修复新增；旧版"只作用于回显行"
 * 的表述作废 —— 可见行现在也消费它，见下）。
 *
 * ## 注册原理（为什么可见行选中格也要缩放）
 *
 * 按压态的折射采样存在**复合放大**：回显行（第 2 层）按 `lerp(1f, 1.2f, p)` 缩放，
 * 胶囊（第 3 层）layerBlock 的 scaleY = `1 + (78/56 − 1)·p = 1 + 0.3929p`，
 * 两者相乘 = 折射采样复合放大（p=0.4 时 ≈1.25x）。胶囊表面极透
 *（onDrawSurface 只压白 0.10 / 黑 0.03 的薄对比色），第 1 层可见行的真实内容会从
 * 胶囊下透出 ⇒ 同一个选中图标出现"折射拷贝大、真实内容小"两份（真机报告的双影）。
 * 让可见行**选中格**以同一 compound（`lerp(1f, 1.2f, p) × scaleY`）缩放注册，
 * 两份拷贝逐帧对齐，双影消失。
 *
 *  - **非选中格恒 1f**：透出的内容与折射无关（折射素材是回显行的选中格）。
 *  - **p→1 裁剪边界残余错位（申报）**：两行的 `.clip(Capsule)` 都在 graphicsLayer
 *    **之前** ⇒ 缩放被未缩放的胶囊边界裁剪；可见格是 56dp 边界，折射拷贝等效
 *    ~78dp 边界，p→1 时两份内容在边界处有残余错位（按压鼓包最大时刻）。
 *  - **拖动期高亮格膨胀（申报）**：selected 由 highlightIndex 驱动，拖动中它跟随
 *    胶囊最近格 —— 高亮格会随按压进度膨胀，与回显行（tabsContent 共用同一
 *    selected 判定）逐帧一致。
 *  - 可见行刻意**不含** velocity 各向异性项（那是胶囊 layerBlock 里额外的乘除）：
 *    双影只在按压（含点住）时可见，拖动期注册无意义。
 */
private val LocalLiquidBottomTabScale = staticCompositionLocalOf<(Boolean) -> Float> { { _ -> 1f } }

/**
 * 真·LiquidBottomTabs：**选中指示胶囊随选中项横向滑动**的底部页签。
 *
 * 结构对齐 Kyant0 `catalog/components/LiquidBottomTabs`（Apache-2.0）的四层：
 *
 *  1. **滑动指示面板**（可见玻璃条）—— Row + Capsule + vibrancy/blur(8)/lens(24,24)。
 *  2. **隐形回显行** —— 同一份页签内容再渲染一遍，`alpha(0f)` 屏幕上不可见，
 *     但被 `layerBackdrop` 录进 [tabsBackdrop] 图层，并整体 tint 成强调色。
 *     它是第 3 层折射的素材：胶囊里看到的"发光页签"就是它。
 *  3. **滑动指示胶囊** —— `fillMaxWidth(1f/tabsCount)` 的 Box，横向平移
 *     `value * tabWidth`；**只挂** [InteractiveHighlight.gestureModifier]（按压高光）；
 *     拖动手势（[DampedDragAnimation.modifier]）**已迁到静态宿主**（见下「坐标反馈」）。
 *     lens(10,14) + 色散 + 按压缩放 + 速度各向异性拉伸。
 *  4. **选中格图标随按压复合缩放** —— 两行（可见 / 回显）共用 [LocalLiquidBottomTabScale]
 *     下发：回显行按 `lerp(1f, 1.2f, p)`，可见行选中格按同款 compound
 *    （`lerp(1f, 1.2f, p) × scaleY`）注册对齐折射拷贝（双影修复，见其 KDoc）。
 *
 * ## 拖动拉伸偏移（panelOffset）只挂一层（Wave 10）
 *
 * 拖动时整条玻璃朝拖动方向最长拉 4dp（EaseOut），松手弹簧归零。该偏移**只在只包视觉
 * 三层的内层 wrapper 的 `graphicsLayer` 上挂一次** —— 原先三层各自平移同一个
 * panelOffset、胶囊再自己 `+ panelOffset.value`，等价于"整体平移"；收敛到一层后
 * 语义一致，但每帧只有 1 个 layer 失效而不是 3 个。
 *
 * 差异口径：**≤4dp 不可辨差异** —— 第 2 层的 `layerBackdrop` 录制帧在旧代码里不随
 * `panelOffset` 平移、新代码里随 wrapper 一起平移（录制内容本身同源、位移量 ≤4dp，肉眼不可辨）。
 * 功能无回归。机制依据：`drawBackdrop` 通过 `GlobalPositionAwareModifierNode.onGloballyPositioned`
 * 拿**全局坐标**采样背景（`DrawBackdropModifier.kt`），祖先层平移会一并计入子节点窗口
 * 坐标 ⇒ 采样区域不变，只是位移量同源。
 *
 * ## ⚠️ 坐标反馈：手势宿主必须与"被平移的视觉节点"分离（Wave 10 Phase 2d）
 *
 * 真机回归（2026-09-25 15:01 录屏，commit `53656a5`）实测：胶囊位移对指示点位移的
 * 斜率 ≈ **0.489**（n=47）—— 手指移动 1 个 tabWidth，胶囊只走约半个。根因：
 *  - 旧实现把 `.then(dampedDragAnimation.modifier)` 与
 *    `.graphicsLayer { translationX = renderValue*tabWidth + … }` 挂在**同一个胶囊节点**上；
 *  - `PointerInputChange.position` 是**该节点的局部坐标** ⇒ 节点每帧右移 Δ胶囊，手指的
 *    局部 x 就少 Δ胶囊 ⇒ `Δ胶囊 = Δ(dragAccumPx)` ⇒ `dragAccumPx = 手指 − dragAccumPx`
 *    ⇒ 胶囊恒走手指一半（一阶反馈，斜率 0.5）；
 *  - 叠加 `snapValue` 的派发延迟 1 帧 ⇒ `v_{t+1} = v_{t-1}` 二周期振荡（中途回退/抽搐）；
 *  - 落点错误是其下游（targetValue 偏小 → roundToInt 落错页签）。
 *
 * 修复（R1）：手势宿主迁到**静态**节点（`matchParentSize()` 覆盖整条、放最上层），
 * 胶囊只保留视觉平移；`panelOffset` 从 BoxWithConstraints 下移到只包视觉三层的内层
 * wrapper（仍是 1 层）。三者（静态宿主 / 被平移的 wrapper / 胶囊）分属不同节点 ⇒
 * 坐标反馈的因果链被切断。
 *
 * ⚠️ 迁移带来的两个**必须**的配套：
 *  1. `DampedDragAnimation.canStartDrag` 门禁：手势区从"胶囊那一格"扩到整条底栏，
 *     必须逐轴复刻旧抓取区（**宿主矩形 ∩ 胶囊实时矩形**）。门禁读胶囊**实时** `value`
 *     与 `panelOffset`，并判 x（左右边）**与 y**（宿主高 64dp、胶囊高 56dp 居中 ⇒
 *     `[4dp, 60dp]`）⇒ 与旧实现（胶囊节点自身 bounds）**逐帧、逐轴一致，无已知偏离**；
 *     否则"按任意页签"都会触发 56→78dp 按压鼓包 + 拖动（行为变更）。
 *  2. 高光手势（[InteractiveHighlight.gestureModifier]）**留在胶囊上**、**不**放静态宿主：
 *     静态宿主无门禁，若把高光手势也放上去，高光 pressAnimatable 会在"按任意页签"时被
 *     点亮（高光画在胶囊处）—— 同样破坏「行为零变化」。
 *
 * ## 关于 `interactiveHighlight.modifier` 的分层（Wave 10 修正）
 *
 * ⚠️ `InteractiveHighlight.pressAnimatable` 是**实例字段**（`InteractiveHighlight.kt:84`），
 * 而本组件的 `interactiveHighlight` 是**单实例**（见下方 `remember(animationScope)`）——
 * 第 3 层胶囊的 `gestureModifier`（见胶囊 Modifier 链）驱动的就是**同一个** `pressAnimatable`。
 * 因此它驱动的按压进度是**全层共享**的：
 *  - **第 1 层（可见玻璃条）必须保留 `.then(interactiveHighlight.modifier)`**：
 *    它绘制的白色径向高光（`InteractiveHighlight.kt:109-128`，半径 `min(w,h)*0.9`，
 *    圆心 = 胶囊中心 `position(nodeSize, touchOffset)`）是**可见效果**，删掉即真实视觉变更，
 *    违反「零视觉风险」裁决。
 *  - **第 2 层（回显行）那一处已删除**：该层 `.alpha(0f)`，屏幕上本就不可见，删它是
 *    **真正的零视觉变更**。附带收益：`nodeSize` 从「64dp / 56dp 两节点交替写同一个 state」
 *    变成**单写者**（只剩第 1 层），消除了状态抖动 —— 这才是这处改动真正的价值。
 *
 * 📌 曾误判为「第 1 / 2 层 pressProgress 恒为 0 的死代码」：错在把 `pressAnimatable`
 * 当成按节点隔离的状态；它是**单实例共享**的。第 1 层的 modifier 是活的，不要再删。
 *
 * ## 与 Kyant0 原版的 API 差异（全部是等价替换，结构不变）
 *
 *  - 原版 `backdrop: Backdrop` 入参 → 读 [LocalBackdrop]（GlassScaffold 在最外层
 *    提供的壁纸录制层），并尊重 [GlassConfig.enableBackdropBlur]（UI-07 性能开关：
 *    用户关掉背景模糊后，这里退化为底色 + 高光，与 `liquidGlass` 门面行为一致）。
 *  - 原版 `content: RowScope.() -> Unit` 自由内容 → `tabs: List<TabSpec>` 强类型页签，
 *    内部生成内容（两行共用同一个 lambda，保证逐帧一致）。
 *  - 玻璃底色用 [GlassMaterial.THICK] 的 `backgroundAlpha`（0.34）—— 真机反馈
 *    "透明度不要太高"，导航栏是常驻组件，底色要压得住复杂背景。
 *
 * ## 为什么 8dp consumeSlopPx 是**必须**的
 *
 * 静态手势宿主（覆盖整条底栏）与选中页签的 `clickable` 在同一区域叠着，一次点击会
 * **同时**命中拖动手势和页签的 `clickable`。若手势一有位移就 `consume()`，真机点击的
 * 亚像素抖动就会取消 clickable 的按压 → **点击选中页签没反应**（GlassSwitch 踩过的同一口井）。
 * 越过 8dp slop 才消费，"点击"与"拖动换页"两条路径干净分开。
 * （Phase 2d 起拖动手势挂在静态宿主上、并由 `canStartDrag` 门禁限定起手区域为胶囊格；
 *  门禁不过的按下**不消费**，非当前页签的点击照常。）
 *
 * ## ⚠️ 静态宿主独占命中 → 页签点击死亡（onTap 补救，2026-09-26）
 *
 * compose-ui 1.10.3 `InnerNodeCoordinator.hitTestChild` 的命中语义：兄弟节点按 z 序
 * **逆序**命中，顶部兄弟命中后若其 `shouldSharePointerInputWithSiblings()` 为 false
 * （默认），**下层兄弟全部不再参与命中**。Wave 10 Phase 2d/2e 把拖动手势迁到
 * `matchParentSize` 覆盖整条的**静态宿主**（z 序最顶，见下方"必须是最后一个兄弟"）
 * 之后，第 1 层可见行的 clickable、胶囊上的 [InteractiveHighlight.gestureModifier]、
 * 回显行等下层兄弟**全部收不到指针事件** —— 真机表现为"底栏页签点击失效，只能拖动
 * 换页"（拖动挂在宿主上所以幸存）。
 *
 * 补救：给 [DampedDragAnimation] 新增 `onTap` 观察通道 —— 门禁（`canStartDrag`）不过
 * 时，手势循环改为**观察**至抬手（全程不消费事件、不触碰任何状态字段），净位移未越过
 * touchSlop 且事件流未断时，以**宿主局部坐标**按下点回调 onTap；本组件在 onTap 里按
 * `TabPad` 边界守卫 + 格宽换算出页签序号，走与 clickable **完全同款同序**的三件套
 * （`currentIndex = index` + `onSelectedCallback(index)` + `currentHaptics.tick()`）。
 *
 *  - **触达扩大申报**：onTap 命中区是整条 64dp 高的宿主（含胶囊上/下各 4dp 纵向带），
 *    比旧实现（手势挂胶囊节点、56dp 格内）略大；横向 `TabPad`（4dp）左右带**不可点**
 *    （边界守卫剔除 —— Kotlin `toInt()` 向零截断，`(-0.5).toInt() == 0`，否则左带
 *    会被静默映射到第 0 格）。
 *  - **双发去重**：onTap 与"外部回显 → 收集器"可能双发 onSelected —— 由 MainShell 的
 *    `destination != selected` 守卫去重（与页签 clickable 路径同一条链），本组件不重复
 *    设防（GlassSegmented 侧的既有口径：重复调用幂等，收集器照发）。
 *  - **按压高光复活**：胶囊上的 gestureModifier 同样被宿主挡死，按压白色径向高光改由
 *    onDragStarted / onDragStopped 经 `highlightPressDriver` 驱动（见该处注释）。
 *
 * ## 页签切换的数据流（2026-09-24 Wave 6b 重构：**onSelected 只在用户位点发**）
 *
 *  - 单击页签 → `currentIndex = index` + **直接** `onSelected(index)`
 *  - 拖动胶囊 → `onDragStopped` 里 `currentIndex = targetIndex` + **直接** `onSelected(index)`
 *  - 外部 [selectedIndex] 变化（导航返回等）→ [LaunchedEffect] 写回 [currentIndex]
 *    （**拖拽进行中跳过**——见该处门禁注释）→ 收集器把胶囊动画过去
 *  - 收集器只负责动画、**不发 onSelected**：外部回显写也会触达收集器，若在那里
 *    回调再走 navigateTop → selectedIndex 回压 → 再写 currentIndex，反馈环闭合
 *    后任何一次回显都能自激振荡（真机录屏实证的"胶囊两端逐帧横跳"）
 *
 *  拖拽期锚点是 receiver 的实时 `value`（非 currentIndex）——按住动画中的胶囊
 *  不会瞬移；底栏无纵向滚动父级，让位锁关闭（`canYieldToParent = false`）。
 */
@Composable
fun LiquidBottomTabs(
    tabs: List<TabSpec>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) {
        // 空页签：`fillMaxWidth(1f/0)` 会算出 NaN 约束，直接占位返回。
        Box(modifier = modifier)
        return
    }
    val tabsCount = tabs.size
    val safeSelectedIndex = selectedIndex.coerceIn(0, tabsCount - 1)

    val colors = LocalGlassColors.current
    val config = LocalGlassConfig.current
    // 底色材质：THICK（真机反馈"透明度不要太高"）。只取它的 backgroundAlpha 做玻璃
    // 底色；blur / lens 沿用 Kyant0 LiquidBottomTabs 的实测值（8 / 24,24），那是
    // "液态"观感的来源，不随材质走（GlassMaterial.kt 的参数表也如此记录）。
    val thick = GlassMaterials.of(GlassMaterial.THICK)

    // 全屏壁纸背景源（GlassScaffold 最外层提供）—— 等价于 Kyant0 传进来的 backdrop 参数。
    val wallpaperBackdrop = if (config.enableBackdropBlur) LocalBackdrop.current else EmptyBackdrop
    // 回显行的录制层 —— 等价于 Kyant0 的 rememberLayerBackdrop() + .layerBackdrop()。
    val tabsBackdrop = rememberLayerBackdrop()

    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()

    // 手势回调里要读的布局量。用 State 承载而不是闭包捕获 val：
    // DampedDragAnimation 被 remember，闭包捕获的是创建那一刻的值；
    // 分屏 / 旋转后窗口宽度变了，旧闭包仍用旧宽度 → 胶囊跟手比例失真。
    // 刻意用 mutableStateOf + 显式 .value（不用 by 委托），与 DampedDragAnimation
    // 的同一决定一致 —— 少一层隐式依赖。
    val tabWidthState = remember { mutableStateOf(0f) }
    val containerWidthState = remember { mutableStateOf(0f) }

    // 面板拉伸偏移（px）：拖动时整条玻璃朝拖动方向最长拉 4dp，松手弹簧归零。
    //
    // ⚠️ 拖动期**同步累加**到这个 State，不再每帧 `animationScope.launch { … snapTo(…) }`。
    // 旧写法每帧新建一个协程，多个在途协程都先读到**同一个** `offsetAnimation.value`
    // 再加各自的 delta，谁最后写谁生效 → 累加丢帧 → 面板 ±4dp 抽搐（真机反馈）。
    // 同步累加在 onDrag 当帧完成，没有任何在途协程读旧值。
    //
    // ⚠️ Wave 10：这两个 State **刻意声明在 BoxWithConstraints 之外**，平移只挂到
    // **只包视觉三层的内层 wrapper** 一个 graphicsLayer 上（见下方 wrapper 的 modifier）。
    // 原先三层各自 `.graphicsLayer { translationX = panelOffset.value }`、胶囊再自己
    // `+ panelOffset.value` —— 三层平移同一个 panelOffset 等价于"整体平移"；收敛到一层
    // 语义完全一致，但每帧只有 1 个 layer 失效（原先是 3 个）。
    // ⚠️ Phase 2d：平移**不再**挂在 BoxWithConstraints 上 —— 必须留在内层 wrapper，
    // 这样静态手势宿主（BoxWithConstraints 的另一个子节点）才不被平移（见类 KDoc「坐标反馈」）。
    val panelOffsetPx = remember { mutableStateOf(0f) }
    val panelOffset = remember(density) {
        derivedStateOf {
            val containerWidth = containerWidthState.value
            val fraction = if (containerWidth > 0f) {
                (panelOffsetPx.value / containerWidth).coerceIn(-1f, 1f)
            } else {
                0f
            }
            with(density) {
                4f.dp.toPx() * sign(fraction) * EaseOut.transform(abs(fraction))
            }
        }
    }

    // 外层 Box：只承载调用方 modifier，**静态**（不随 panelOffset 平移）。
    // ⚠️ Wave 10 Phase 2d 坐标反馈修复：手势宿主必须是**静态**节点 —— 旧实现把
    // `dampedDragAnimation.modifier` 挂在被 `graphicsLayer { translationX }` 平移的胶囊
    // 节点上 ⇒ 手势节点与被驱动节点是同一个 ⇒ `PointerInputChange.position`（节点局部
    // 坐标）里已扣掉胶囊自身位移 ⇒ `dragAccumPx = 手指 − dragAccumPx` ⇒ 胶囊恒走手指
    // 一半（真机实测斜率 0.489，n=47）。修复：视觉平移只留在内层 wrapper，手势宿主迁到
    // 下方**静态**覆盖层，二者彻底分离（见类 KDoc「坐标反馈」）。
    Box(modifier = modifier) {
        BoxWithConstraints(
            contentAlignment = Alignment.CenterStart,
        ) {
            // 容器左右各 TabPad 内边距（下面三层都带同一内衬），可用宽度 = maxWidth − 2×TabPad。
            // ⚠️ 必须写成 `TabPad * 2f` 而不是字面量 `8f.dp`：这个 tabWidth 同时喂给**起手门禁**
            // 与**胶囊 translationX**，而真实格宽由 `.padding(horizontal = TabPad)` 决定 ——
            // 若这里另有一份字面量来源，改 TabPad 时两者会一起**静默漂移**且不报错
            //（正是本轮引入常量要消除的失败模式，审查 P2-1）。
            val tabWidth = with(density) {
                (constraints.maxWidth.toFloat() - (TabPad * 2f).toPx()) / tabsCount
            }
            // Wave4 审查（B-P0-3/P1-3）：5 项后窄容器（分屏 / 自由窗口可低至 ~206dp）单项
            // 宽度跌破 48dp 触摸标准且文字被 Clip 成半个字。完整修法（横向滚动 + 宽度同源）
            // 需要联动改 4 处几何，本轮不做；先用「窄容器退化为纯图标」缓解拥挤与截字 ——
            // 触摸目标问题的根治方案连同 4 处联动清单记入蓝图 §4 backlog。
            // ⚠️ 这里的 `56.dp` 是**单项最小宽度**阈值（宽度量），与 [TabCapsuleHeight]
            // （高度量，同为 56dp）只是**数值巧合、语义无关** —— 勿"顺手清理"合并为同一常量：
            // 合并后"改胶囊高度"会连带改掉"窄容器退化阈值"，且不报错。
            val compactTabs = with(density) { (tabWidth.toDp()) < 56.dp }
            // 带守卫的写入：值没变不触发失效，不会造成"组合期写状态"的重组循环。
            if (tabWidthState.value != tabWidth) tabWidthState.value = tabWidth
            val maxWidthPx = constraints.maxWidth.toFloat()
            if (containerWidthState.value != maxWidthPx) containerWidthState.value = maxWidthPx

            // 面板拉伸偏移（panelOffsetPx / panelOffset）已上移到 BoxWithConstraints 之外，
            // 平移只在**内层 wrapper** 挂一次 graphicsLayer（见该处注释）。
            // 松手回弹的在途协程。新一次拖动开始要先取消它 —— 否则回弹一边归零、拖动一边
            // 累加，两者对同一个 State 互相覆盖，面板又抖。
            val panelReboundJob = remember { mutableStateOf<Job?>(null) }

            // 内部选中态：单击页签与拖动胶囊都只改它，由它统一驱动动画与回调。
            var currentIndex by remember { mutableStateOf(safeSelectedIndex) }

            // ⚠️ consumeSlopPx 必须 8dp（见类 KDoc）——"点击选中页签没反应"的保险丝。
            val consumeSlopPx = with(density) { 8.dp.toPx() }

            // 拖动期的绝对映射基准（按下快照 / 手势内累积）。
            //
            // ⚠️ 拖动唯一的事实来源是**手势本身**，不是异步回显链：旧实现把
            //「当前目标值 + 本帧位移增量」交给 `updateValue`，而 `updateValue` 内部是
            // `animateTo(spring)`（**收敛动画，不是瞬时**），拖动期每帧重启弹簧 → 弹簧永远
            // 追不上每帧前移的目标 → 胶囊恒定滞后手指（"不跟手"）；单帧只走"一步"而目标走
            // "一个手指增量" → 误差随拖动距离单调累积（"越远越偏差"）；速度快时弹簧速度反向
            // 打架 → 抽搐。绝对映射 + snapValue（瞬时到位）彻底解耦，与 GlassSlider 同一套修法。
            //
            // ⚠️ 锚点 = receiver 的实时 `value`（onDragStarted 已带 receiver，见
            // DampedDragAnimation）——不能用 currentIndex：点击动画进行中按住胶囊时
            // 两者可能差出数个页签，旧锚点的第一帧 snapValue 会把胶囊瞬移到 currentIndex
            //（真机录屏"首尾乱飘"的来源之一）。
            var dragAccumPx by remember { mutableStateOf(0f) }
            var dragStartValue by remember { mutableStateOf(0f) }

            // onSelected 的最新引用：onDragStopped / 页签 onClick 两个**用户动作位点**
            // 直接回调（见各处注释——绝不能挂回 snapshotFlow 收集器）。必须声明在
            // dampedDragAnimation 之前：onDragStopped 闭包要捕获它。
            val onSelectedCallback by rememberUpdatedState(onSelected)
            // 触感用同一条纪律：只发在 onDragStopped 与页签 onClick 两个用户动作位点。
            // 绝不进 LaunchedEffect(selectedIndex) 或 snapshotFlow 收集器 ——
            // 导航返回 / 程序化切换都会触达那里，会变成"返回上一页也震"。
            val haptics = rememberGlassHaptics()
            val currentHaptics by rememberUpdatedState(haptics)

            // ⚠️ holder 回填（2026-09-26 按压高光复活）：interactiveHighlight 声明在
            // DDA **之后**（其 position lambda 读 dampedDragAnimation.value —— 二者
            // 互相前向引用，谁上移谁就编译红），而 DDA 的 onDragStarted / onDragStopped
            // 闭包创建于 DDA 构造处、需要驱动按压高光 ⇒ 用本 holder + SideEffect 回填。
            //
            // 为什么**不违反** DampedDragAnimation.canStartDrag KDoc 对"remember{}
            // 之后用 holder 回填实例"的批判：那条批判针对的是 canStartDrag 的
            // **组合期求值**场景 —— 若门禁闭包在组合期就要读实例，就必须带"首帧
            // holder 尚为 null"的分支，等于把时序炸弹埋进正常路径。此处 setPressed
            // 只在**手势事件回调**里调用：触摸事件派发时组合必已完成至少一帧，
            // SideEffect 必已回填，holder 不可能还是 null（`?.` 只是防御性写法）。
            val highlightPressDriver = remember { mutableStateOf<InteractiveHighlight?>(null) }

            val dampedDragAnimation = remember(animationScope) {
                DampedDragAnimation(
                    animationScope = animationScope,
                    initialValue = safeSelectedIndex.toFloat(),
                    valueRange = 0f..(tabsCount - 1).toFloat(),
                    visibilityThreshold = 0.001f,
                    initialScale = 1f,
                    // 56dp 的胶囊按下时放大到 78dp 高 —— Kyant0 BottomTabs 的实测值。
                    // 写成两个常量求比值（审查 P2-2）：改 [TabCapsuleHeight] 时按压比例自动跟随，
                    // 不会留下"分母与胶囊高脱钩"的第二份来源。
                    pressedScale = TabPressedHeight.value / TabCapsuleHeight.value,
                    consumeSlopPx = consumeSlopPx,
                    // ⚠️ 底栏**没有纵向滚动父级**（MainShell 的 Column 上下都无
                    // verticalScroll），让位锁在这里是纯害：拖页签时手指的自然弧线会让
                    // net-vertical 越过 tan30° 阈值 → 拖拽**中途冻结**（2026-09-24 真机
                    // 录屏"拉越远越偏移"的来源之一）。必须关掉。
                    canYieldToParent = false,
                    // ⚠️ 起手门禁（Wave 10 Phase 2d）：拖动手势已迁到**静态**宿主（覆盖整
                    // 条底栏），必须逐轴复刻旧实现"手势挂在胶囊节点上 ⇒ 抓取区 = 胶囊自身
                    // bounds"的命中区 —— 否则"按任意页签"都会触发 56→78dp 按压鼓包 + 拖动
                    //（Ruling 1）。
                    // 门禁读**胶囊实时位置**：`value`（胶囊动画值）+ `panelOffset`（内层
                    // wrapper 的平移）⇒ 抓取区与旧实现**逐帧、逐轴一致，无已知偏离**
                    //（不再用 currentIndex，也就没有"动画窗口内偏半格"那类偏离）。
                    // 入参 `pos` 是**宿主局部坐标**（宿主 = matchParentSize 覆盖整条，与
                    // 内层 wrapper 同原点、同宽度）。
                    canStartDrag = { pos ->
                        val tabWidth = tabWidthState.value
                        val width = containerWidthState.value
                        if (tabWidth <= 0f || width <= 0f) {
                            false
                        } else {
                            // 胶囊左缘（含 panelOffset —— 胶囊实际位于被平移的 wrapper 内）。
                            // LTR = 内边距 + 平移 + 值*格宽；RTL 镜像（平移同为正向）。
                            val padPx = with(density) { TabPad.toPx() }
                            val v = value.coerceIn(0f, (tabsCount - 1).toFloat())
                            val left = if (isLtr) {
                                padPx + panelOffset.value + v * tabWidth
                            } else {
                                width - padPx - (v + 1) * tabWidth + panelOffset.value
                            }
                            // 纵向：宿主高 = 玻璃条高（64dp）、胶囊 56dp 居中
                            // ⇒ 旧实现（手势挂胶囊节点）的纵向命中区 [4dp, 60dp]。
                            val hostH = with(density) { TabBarHeight.toPx() }
                            val capsuleH = with(density) { TabCapsuleHeight.toPx() }
                            val top = (hostH - capsuleH) / 2f
                            val bottom = (hostH + capsuleH) / 2f
                            pos.x >= left && pos.x <= left + tabWidth &&
                                pos.y >= top && pos.y <= bottom
                        }
                    },
                    // ⚠️ onTap（2026-09-26 点击死亡修复）：静态宿主 z 序最顶、独占命中
                    //（compose-ui 1.10.3 InnerNodeCoordinator.hitTestChild —— 见类 KDoc
                    // 「静态宿主独占命中」一节），第 1 层可见行的 clickable 收不到指针
                    // 事件 ⇒ "点页签换页"改由宿主代观察后回调到这里，走与 clickable
                    // **完全同款同序**的三件套（currentIndex → onSelected → tick，见
                    // tabsContent 的 onClick）。双发由 MainShell 的
                    // `destination != selected` 守卫去重（与 clickable 路径同一条链）。
                    onTap = { pos ->
                        val tabWidth = tabWidthState.value
                        val width = containerWidthState.value
                        if (tabWidth <= 0f || width <= 0f) {
                            return@DampedDragAnimation
                        }
                        // 边界守卫（必须保留）：Kotlin `toInt()` 向零截断，
                        // `(-0.5).toInt() == 0` —— 若不先剔除左 pad 带，落点 x ∈ [0, padPx）
                        // 会被静默映射到第 0 格（右带同理）。横向 pad 带**不可点**是申报过的语义。
                        val padPx = with(density) { TabPad.toPx() }
                        if (pos.x < padPx || pos.x > width - padPx) {
                            return@DampedDragAnimation
                        }
                        val index = (if (isLtr) {
                            (pos.x - padPx) / tabWidth
                        } else {
                            (width - padPx - pos.x) / tabWidth
                        }).toInt().coerceIn(0, tabsCount - 1)
                        currentIndex = index
                        onSelectedCallback(index)
                        currentHaptics.tick()
                    },
                    onDragStarted = {
                        // 新一次拖动：取消在途回弹，避免它与拖动同时写 panelOffsetPx。
                        panelReboundJob.value?.cancel()
                        panelReboundJob.value = null
                        // 按下瞬间快照：本次手势的一切增量都从它出发（绝对映射）。
                        // ⚠️ 锚点必须用 **receiver 的实时 value**（胶囊此刻的真实位置），
                        // 不能用 currentIndex：点击动画进行中按住胶囊时两者可能差半个
                        // 屏 —— 旧锚点会让第一帧 snapValue 把胶囊**瞬移**到 currentIndex
                        // （真机录屏 6.060s/6.193s 帧"首尾乱飘"的来源之一）。
                        dragAccumPx = 0f
                        dragStartValue = this.value
                        // 按压高光（白色径向）点亮：手势源被静态宿主独占命中后，胶囊上的
                        // InteractiveHighlight.gestureModifier 收不到指针事件，改由门禁
                        // 通过路径驱动（与 DDA 自身 setPressed 双路幂等，同目标 animateTo）。
                        // 只有门禁通过（按在胶囊格）才走到这里 ⇒ 与 Phase 2d 之前的
                        // "只有按在胶囊格才亮高光"语义一致。
                        highlightPressDriver.value?.setPressed(true)
                    },
                    onDragStopped = {
                        // 松手：四舍五入到最近页签，内部态收敛，面板拉伸弹回 0。
                        val targetIndex = targetValue.roundToInt().coerceIn(0, tabsCount - 1)
                        currentIndex = targetIndex
                        animateToValue(targetIndex.toFloat())
                        // ⚠️ onSelected 只在**用户完成动作**的两个位点（这里与页签单击）
                        // 直接回调 —— 绝不能挂回 snapshotFlow 收集器：外部回显写
                        // currentIndex 也会触达收集器，若在那里再发 onSelected →
                        // navigateTop → selectedIndex 回压 → 再写 currentIndex，
                        // 就是真机录屏实证的"胶囊两端自激振荡"（2026-09-24 Wave 6b）。
                        onSelectedCallback(targetIndex)
                        // 拖动换页提交 → 一次 tick（页签是离散档位）。
                        //
                        // ⚠️ 只认 `finishedNormally`：onDragStopped 在 `finally` 里执行，
                        // pointerInput 协程被取消（旋转 / 导航 / 页面销毁）时也会走到这里，
                        // 那时并不是一次用户提交。（本控件 `canYieldToParent = false`，
                        // 没有"让位给滚动"这条路径，故不判 yieldedToParent —— 与
                        // GlassSegmented 的门禁口径差异就来自这个开关。）
                        if (finishedNormally) currentHaptics.tick()
                        // 面板拉伸弹回：从当前累加值出发做一次弹簧（**单个**协程，非每帧）。
                        val start = panelOffsetPx.value
                        if (start != 0f) {
                            panelReboundJob.value?.cancel()
                            panelReboundJob.value = animationScope.launch {
                                animate(
                                    initialValue = start,
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = 1f,
                                        stiffness = 300f,
                                        visibilityThreshold = 0.5f,
                                    ),
                                ) { value, _ -> panelOffsetPx.value = value }
                            }
                        }
                        // 按压高光归位：onDragStopped 在手势循环的 finally 里执行，协程取消
                        // 路径（旋转 / 导航 / 页面销毁）也会走到 —— 防御性归位，无害。
                        //（让位路径不存在：本控件 canYieldToParent = false。）
                        highlightPressDriver.value?.setPressed(false)
                    },
                    onDrag = { _, dragAmount ->
                        // 面板拉伸：**同步累加**（当帧完成），不再每帧 launch。
                        panelOffsetPx.value += dragAmount.x
                        // 胶囊位置：手势内累积 → 绝对映射（每移动一个 tabWidth 前进一页），
                        // 与异步回显完全解耦。
                        dragAccumPx += dragAmount.x
                        val tabWidth = tabWidthState.value
                        if (tabWidth > 0f) {
                            val raw = dragStartValue + dragAccumPx / tabWidth * if (isLtr) 1f else -1f
                            val coerced = raw.coerceIn(0f, (tabsCount - 1).toFloat())
                            // 值没变不重复 snapValue：掐掉亚像素抖动造成的无意义协程启动。
                            if (coerced != targetValue) snapValue(coerced)
                        }
                    },
                )
            }

            // ⚠️ 外部选中态变化（导航返回 / 程序化切换）→ 写回内部态。
            // 2026-09-24 Wave 6b 门禁：**拖拽进行中绝不回写**。拖拽期手势（snapValue）
            // 是胶囊位置的唯一事实来源；导航回压（navigateTop 落地晚于手势开始）此刻写
            // currentIndex，会经收集器触发一次 animateToValue 旧页签，与 snapValue 逐帧
            // 互搏 —— 真机录屏实证的"胶囊两端自激振荡"（手指按住对话、胶囊在对话/设置
            // 间逐帧横跳 1.6 秒）。拖拽结束后 onDragStopped 提交用户的选择，这里错过的
            // 回写由那次提交覆盖（onSelected 已把导航带到用户要的页签）。
            LaunchedEffect(selectedIndex) {
                if (!dampedDragAnimation.isDragging) currentIndex = safeSelectedIndex
            }
            // 内部态变化 → 弹簧动画到位。drop(1)：初始组合不回调。
            // ⚠️ onSelected **不在收集器里发**：收集器也会被外部回显写触达，若在那里
            // 发 onSelected → navigateTop → selectedIndex 回压 → 再写 currentIndex →
            // 再进收集器 —— 反馈环闭合，任何一次回显都能自激振荡。用户动作的两个
            // 位点（onClick / onDragStopped）直接回调，环被切断。
            // key 只留 dampedDragAnimation：调用方的 lambda 字面量每次重组都是新实例，
            // 用 rememberUpdatedState 让闭包始终读最新 lambda（见上方声明处）。
            LaunchedEffect(dampedDragAnimation) {
                snapshotFlow { currentIndex }
                    .drop(1)
                    .collectLatest { index ->
                        // 点击切换（及外部选中态回流）走 TabSwitch 规格 —— 2026-09-24
                        // 真机修正后与上游一致（临界阻尼快弹簧，≈120ms 收敛，无过冲）。
                        dampedDragAnimation.animateToValue(
                            index.toFloat(),
                            LiquidMotion.floatSpring(LiquidMotion.TabSwitch),
                        )
                    }
            }

            // 高光中心跟随"胶囊当前位置"而不是手指落点 —— 手指在哪不重要，
            // 玻璃胶囊滑到哪，高光就在哪。
            val interactiveHighlight = remember(animationScope) {
                InteractiveHighlight(
                    animationScope = animationScope,
                    position = { size, _ ->
                        // 与胶囊 translationX 同一套钳制（见下方 renderValue 注释）。
                        val v = dampedDragAnimation.value.coerceIn(0f, (tabsCount - 1).toFloat())
                        Offset(
                            if (isLtr) {
                                (v + 0.5f) * tabWidthState.value
                            } else {
                                size.width - (v + 0.5f) * tabWidthState.value
                            },
                            size.height / 2f,
                        )
                    },
                )
            }

            // holder 回填：组合提交后把实例交给手势回调使用（为什么合法见声明处 KDoc）。
            // 必须在 interactiveHighlight 声明**之后**（前向引用会编译红）。
            SideEffect { highlightPressDriver.value = interactiveHighlight }

            // 拖动期可见行高亮跟随**胶囊当前位置**（而不是 currentIndex）。
            // ⚠️ **本波有意引入的拖动期视觉变更**（**不属于**"行为零变化"范畴 —— 勿误判为
            // 回归）：拖动中 currentIndex 要到松手才更新，可见行的选中高亮会留在旧格、而
            // 胶囊已滑到新格 ⇒ 用户报的"双重叠加态"（两处高亮同时亮）。改用 highlightIndex
            //（拖动中 = 胶囊最近格）后可见行高亮随胶囊走，松手由 currentIndex 接管。
            // 非拖动期恒等于 currentIndex ⇒ 与旧行为逐帧一致（derivedStateOf 只在
            // roundToInt 跨格时变化，不是每帧重组）。
            // 📌 只改**显示**用的 selected；onClick / onSelected / 导航不受影响。
            // 📌 真机验收项：拖动时可见行高亮是否随胶囊走、松手是否无跳变。
            val highlightIndex by remember(dampedDragAnimation) {
                derivedStateOf {
                    if (dampedDragAnimation.isDragging) {
                        dampedDragAnimation.value.roundToInt().coerceIn(0, tabsCount - 1)
                    } else {
                        currentIndex
                    }
                }
            }

            // 两行共用同一份内容 lambda：保证可见行与回显行逐帧一致。
            val tabsContent: @Composable RowScope.() -> Unit = {
                tabs.forEachIndexed { index, tab ->
                    LiquidBottomTab(
                        tab = tab,
                        selected = index == highlightIndex,
                        // ⚠️ onSelected 在用户动作位点直发（非收集器）——见收集器处注释。
                        // 点当前页签时 currentIndex 不变、onSelected 照发，由调用方的
                        // "destination != selected" 守卫去重（MainShell 的 onSelect）。
                        onClick = {
                            currentIndex = index
                            onSelectedCallback(index)
                            // 点击换页 → 一次 tick（与拖动换页同规格）。
                            currentHaptics.tick()
                        },
                        showLabel = !compactTabs,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            /* ── 内层 wrapper：只承载 panelOffset 平移，把视觉三层整体平移 ──────────
             * ⚠️ Wave 10 Phase 2d：panelOffset 从 BoxWithConstraints 下移到这一层（原先挂在
             * BoxWithConstraints 上）。语义完全一致（"整体平移"），且**手势宿主不再是它** ——
             * 手势宿主已迁到下方静态覆盖层，与被平移的视觉节点分离，从根上消除坐标反馈
             *（见类 KDoc「坐标反馈」）。每帧仍只有 1 个 layer 失效。
             * `contentAlignment = Alignment.CenterStart` **必须保留**：胶囊是
             * `fillMaxWidth(1/tabsCount) × 56dp`，不居起点会贴顶。
             */
            Box(
                modifier = Modifier
                    .graphicsLayer { translationX = panelOffset.value }
                    .fillMaxWidth(),
                contentAlignment = Alignment.CenterStart,
            ) {

                /* ── 第 1 层：滑动指示面板（可见玻璃条）──────────────────────────────
                 * ⚠️ 双影注册（2026-09-26）：可见行也消费 [LocalLiquidBottomTabScale] ——
                 * 选中格以与折射拷贝同一 compound 缩放注册（lerp(1,1.2,p) × scaleY），
                 * 真实内容与回显行折射拷贝逐帧对齐（见该 CompositionLocal 的 KDoc）。
                 */
                CompositionLocalProvider(
                    LocalLiquidBottomTabScale provides { selected ->
                        if (selected) {
                            lerp(1f, 1.2f, dampedDragAnimation.pressProgress) *
                                dampedDragAnimation.scaleY
                        } else {
                            1f
                        }
                    }
                ) {
                    Row(
                        Modifier
                            // 无障碍分组（三线审查 Wave10）：TalkBack 把整行当一组页签播报，
                            // 配合每个页签的 selected 才有「第 N 项，已选中，共 M 项」的语义。
                            .selectableGroup()
                            .drawBackdrop(
                                backdrop = wallpaperBackdrop,
                                shape = { Capsule },
                                effects = {
                                    if (config.enableBackdropBlur) {
                                        vibrancy(saturation = VibrancySaturation)
                                        blur(8f.dp.toPx())
                                        // 导航栏比按钮大，折射带要给足（Kyant0 Tabs：24,24）。
                                        lens(refractionHeight = 24f.dp.toPx(), refractionAmount = 24f.dp.toPx())
                                    }
                                },
                                layerBlock = {
                                    // 按压时整条面板微微放大（相对宽度归一化，避免宽屏拉过头）。
                                    val progress = dampedDragAnimation.pressProgress
                                    val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, progress)
                                    scaleX = scale
                                    scaleY = scale
                                },
                                onDrawSurface = {
                                    if (config.enableBackdropBlur) {
                                        // 正常路径：THICK 底色上亮下暗垂直渐变，与 liquidGlass 同一配方。
                                        val baseAlpha = thick.backgroundAlpha
                                        drawRect(
                                            Brush.verticalGradient(
                                                colors = listOf(
                                                    colors.glassTint.copy(alpha = baseAlpha),
                                                    colors.glassTintElevated
                                                        .copy(alpha = (baseAlpha * 0.72f).coerceIn(0f, 1f)),
                                                ),
                                                startY = 0f,
                                                endY = size.height,
                                            )
                                        )
                                    } else {
                                        // 退化路径（背景模糊关）：常驻底色，不读任何动画状态 ——
                                        // 与 liquidGlass 门面「底色 + 高光」的退化承诺对齐。
                                        drawRect(colors.glassTint.copy(alpha = thick.backgroundAlpha * 0.8f))
                                    }
                                },
                            )
                            .then(interactiveHighlight.modifier)
                            .height(TabBarHeight)
                            .fillMaxWidth()
                            .padding(
                                // ⚠️ 纵向必须**推导**，不能写 TabPad：内容高 = TabBarHeight − 2×纵向内边距
                                // 必须恒等于 TabCapsuleHeight（第 2/3 层就是按它显式定高的）。
                                // 写死 TabPad 会留下一个无人保证的隐式不变量（今天 64−8=56 成立，把
                                // TabBarHeight 改成 72 就悄悄变成 64）⇒ 可见行与回显行纵向错位、
                                // 胶囊折射素材整体偏移，且**不报错**。用 * 0.5f 而非 Dp.div(Int)：
                                // Dp.times(Float) 是确定存在的运算符，编译风险为零。
                                horizontal = TabPad,
                                vertical = (TabBarHeight - TabCapsuleHeight) * 0.5f,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        content = tabsContent,
                    )
                }

                /* ── 第 2 层：隐形回显行（录进 tabsBackdrop，供胶囊折射）───────────── */
                CompositionLocalProvider(
                    // 签名随 (Boolean) -> Float 走：回显行所有格统一按 1.2p 缩放
                    //（不管 selected —— 回显行本来就是折射素材，行为逐帧不变）。
                    LocalLiquidBottomTabScale provides { _ ->
                        lerp(1f, 1.2f, dampedDragAnimation.pressProgress)
                    }
                ) {
                    Row(
                        Modifier
                            // 回显行不参与无障碍 / 触摸（真实交互在第 1 层与第 3 层）。
                            .clearAndSetSemantics {}
                            // alpha(0f) 挂在 layerBackdrop **之前**：录制时拿到的是不透明内容，
                            // 上屏时整层透明 —— "屏幕上看不见、玻璃里看得见"。
                            .alpha(0f)
                            .layerBackdrop(tabsBackdrop)
                            .drawBackdrop(
                                backdrop = wallpaperBackdrop,
                                shape = { Capsule },
                                effects = {
                                    val progress = dampedDragAnimation.pressProgress
                                    if (config.enableBackdropBlur) {
                                        vibrancy(saturation = VibrancySaturation)
                                        blur(8f.dp.toPx())
                                        lens(
                                            refractionHeight = 24f.dp.toPx() * progress,
                                            refractionAmount = 24f.dp.toPx() * progress,
                                        )
                                    }
                                },
                                highlight = {
                                    if (config.enableBackdropBlur) {
                                        val progress = dampedDragAnimation.pressProgress
                                        Highlight.Default.copy(alpha = progress)
                                    } else {
                                        // 退化路径：高光是门面退化承诺的一半，不能被 progress=0 关死。
                                        Highlight.Default
                                    }
                                },
                                onDrawSurface = {
                                    if (config.enableBackdropBlur) {
                                        val baseAlpha = thick.backgroundAlpha
                                        drawRect(
                                            Brush.verticalGradient(
                                                colors = listOf(
                                                    colors.glassTint.copy(alpha = baseAlpha),
                                                    colors.glassTintElevated
                                                        .copy(alpha = (baseAlpha * 0.72f).coerceIn(0f, 1f)),
                                                ),
                                                startY = 0f,
                                                endY = size.height,
                                            )
                                        )
                                    } else {
                                        // 退化路径：常驻底色，不读任何动画状态。
                                        drawRect(colors.glassTint.copy(alpha = thick.backgroundAlpha * 0.8f))
                                    }
                                },
                            )
                            .height(TabCapsuleHeight)
                            .fillMaxWidth()
                            .padding(horizontal = TabPad)
                            // 整层 tint 成强调色：胶囊折射看到的"发光页签"就是这层染色的内容。
                            .graphicsLayer(colorFilter = ColorFilter.tint(colors.accent)),
                        verticalAlignment = Alignment.CenterVertically,
                        content = tabsContent,
                    )
                }

                /* ── 第 3 层：滑动指示胶囊（高光手势 + 折射在这层；拖动手势在静态宿主）─── */
                Box(
                    Modifier
                        .padding(horizontal = TabPad)
                        .graphicsLayer {
                            // 渲染值钳制（2026-09-24 Wave 6）：把 value 限回页签区间再参与定位。
                            // TabSwitch 改临界阻尼后弹簧本身不再过冲，这是**防御层**——将来若有
                            // 人把规格改回欠阻尼（或引入带初速的重定向），钳制保证胶囊永不画出
                            // 玻璃条两端（真机录屏 6.060s / 6.193s 帧的"漂移越界"）。拖动路径的
                            // snapValue 与点击路径的 animateToValue 目标都已 coerce，钳制在正常
                            // 路径是恒等变换，零开销。
                            val renderValue = dampedDragAnimation.value
                                .coerceIn(0f, (tabsCount - 1).toFloat())
                            translationX =
                                if (isLtr) {
                                    renderValue * tabWidthState.value
                                } else {
                                    size.width - (renderValue + 1f) * tabWidthState.value
                                }
                        }
                        // ⚠️ Wave 10 Phase 2d：这里**只保留** interactiveHighlight.gestureModifier
                        //（按压高光），**删掉** `.then(dampedDragAnimation.modifier)` —— 拖动手势
                        // 已迁到下方静态宿主（坐标反馈修复：胶囊被平移，不能自己当手势节点）。
                        // 高光手势留在胶囊上是**有意的**：胶囊当前所在格 = 旧实现的抓取区域，
                        // 保证"按当前页签才出高光"与旧行为一致（高光 pressAnimatable 全层共享，
                        // 由 Layer 1 的 `.then(interactiveHighlight.modifier)` 绘制）。
                        .then(interactiveHighlight.gestureModifier)
                        .drawBackdrop(
                            // 背景 = 壁纸 + 回显行：折射同时弯折壁纸与染色的页签内容。
                            backdrop = rememberCombinedBackdrop(wallpaperBackdrop, tabsBackdrop),
                            shape = { Capsule },
                            effects = {
                                val progress = dampedDragAnimation.pressProgress
                                // 胶囊小（1/tabsCount 宽），7 次采样扛得住 —— 色散开。
                                // 这是 Kyant0 在指示胶囊上唯一开色散的位置。
                                if (config.enableBackdropBlur) {
                                    lens(
                                        refractionHeight = 10f.dp.toPx() * progress,
                                        refractionAmount = 14f.dp.toPx() * progress,
                                        chromaticAberration = true,
                                    )
                                }
                            },
                            highlight = {
                                if (config.enableBackdropBlur) {
                                    val progress = dampedDragAnimation.pressProgress
                                    Highlight.Default.copy(alpha = progress)
                                } else {
                                    // 退化路径：高光常驻，不随按压（与回显行同一处理）。
                                    Highlight.Default
                                }
                            },
                            shadow = {
                                val progress = dampedDragAnimation.pressProgress
                                Shadow(alpha = progress)
                            },
                            innerShadow = {
                                val progress = dampedDragAnimation.pressProgress
                                InnerShadow(radius = 8f.dp * progress, alpha = progress)
                            },
                            layerBlock = {
                                // 按压缩放（DampedDragAnimation 的 scaleX/scaleY）
                                // + 速度各向异性：拖得快沿运动方向拉长、垂直方向压扁。
                                //
                                // ⚠️ 已知取舍（本轮 P0 拖动跟手改造引入）：拖动改用 snapValue
                                //（瞬时到位）后，`valueAnimatable` 不再保留"未走完的弹簧速度"，
                                // `velocity` 会偏小 → 这里的各向异性拉伸在**拖动中**会减弱
                                //（松手回弹那一段仍有速度，拉伸还在）。换取的是胶囊**严格跟手**
                                //（真机"不跟手/越远越偏差"的根治）——跟手优先。
                                // 若真机确认拉伸观感缺失，再单独调 velocity 的来源，
                                // **不为此回退绝对映射**。
                                scaleX = dampedDragAnimation.scaleX
                                scaleY = dampedDragAnimation.scaleY
                                val velocity = dampedDragAnimation.velocity / 10f
                                scaleX /= 1f - (velocity * 0.75f).coerceIn(-0.2f, 0.2f)
                                scaleY *= 1f - (velocity * 0.25f).coerceIn(-0.2f, 0.2f)
                            },
                            onDrawSurface = {
                                // ⚠️ 正常路径刻意**不用** THICK 底色：面板（第 1 层）已经是 THICK，
                                // 胶囊再叠一层厚底色会把回显行的强调色盖掉、"发光"就没了。
                                // 这里只压一层薄对比色把胶囊从面板里衬出来（Kyant0 原配方），
                                // 按下时淡出、换成阴影表达"被按住"。
                                val progress = dampedDragAnimation.pressProgress
                                if (config.enableBackdropBlur) {
                                    drawRect(
                                        if (colors.isDark) {
                                            Color.White.copy(alpha = 0.10f)
                                        } else {
                                            Color.Black.copy(alpha = 0.10f)
                                        },
                                        alpha = 1f - progress,
                                    )
                                    drawRect(Color.Black.copy(alpha = 0.03f * progress))
                                } else {
                                    // 退化路径：折射没了，"选中"信号改由**常驻底色 + accent 描边**
                                    // 承担（描边即选中，不依赖折射链，也不读 pressProgress ——
                                    // 常驻层不引入逐帧重绘）。
                                    // 描边用 drawRoundRect + Stroke（qa-review 认可的等价方案）：
                                    // 半径 = min(w,h)/2，与 Capsule.createOutline 的公式完全一致，
                                    // 避免 drawOutline 的引用解析问题（CI 实测 Unresolved）。
                                    drawRect(colors.glassTint.copy(alpha = thick.backgroundAlpha * 0.8f))
                                    val capsuleRadius = minOf(size.width, size.height) / 2f
                                    drawRoundRect(
                                        color = colors.accent.copy(alpha = 0.35f),
                                        cornerRadius = CornerRadius(capsuleRadius, capsuleRadius),
                                        style = Stroke(width = 1.dp.toPx()),
                                    )
                                }
                            },
                        )
                        .height(TabCapsuleHeight)
                        .fillMaxWidth(1f / tabsCount),
                )
            }

            /* ── 静态手势宿主（Wave 10 Phase 2d）──────────────────────────────────
             * 覆盖整条底栏的**静态**（不随 panelOffset / 胶囊平移）节点，承载拖动手势：
             *  - 它与被平移的视觉三层**不是同一个节点** ⇒ `PointerInputChange.position`
             *    是宿主局部坐标、不随胶囊移动 ⇒ 消除"胶囊走手指一半"的坐标反馈
             *   （真机实测斜率 0.489 → 修复后应 ≈1.0，n=47）；
             *  - `canStartDrag` 门禁逐轴复刻旧实现"只有按在胶囊**实时矩形**内才起手"
             *   （x / y 两轴，读胶囊实时 `value` + `panelOffset`），
             *    保证"按非当前页签不鼓包、点击照常"（见 DampedDragAnimation.canStartDrag）。
             * ⚠️ 必须是**最后一个兄弟**（z 序最上）：手势宿主在最上层才能稳定接管整条
             * 底栏的按下。它不绘制任何内容 ⇒ 对视觉零影响；不设语义 ⇒ 不影响 TalkBack。
             * ⚠️ 刻意**不**在这层挂 `interactiveHighlight.gestureModifier`：那会让高光
             * pressAnimatable 在"按任意页签"时被点亮（高光画在胶囊处），而旧实现只有按在
             * 胶囊格才亮 —— 会破坏「行为零变化」。高光手势保留在胶囊上（见胶囊处注释）。
             */
            Box(
                Modifier
                    .matchParentSize()
                    .then(dampedDragAnimation.modifier)
            )
        }
    }
}

/**
 * 单个页签项：图标 + 文本，单击改内部选中态。
 *
 * `indication = null`：液态玻璃的高光 / 折射反馈已足够，再叠 M3 ripple 就是
 * "原生按钮贴玻璃纸"。触摸目标 56dp（容器高 64 - 上下 4×2），高于 48dp 标准。
 */
@Composable
private fun RowScope.LiquidBottomTab(
    tab: TabSpec,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
) {
    val colors = LocalGlassColors.current
    val scale = LocalLiquidBottomTabScale.current
    // 局部别名：semantics 块里 `selected = isSelected` 的赋值目标（语义属性）与
    // 取值来源（函数参数）同名，不借用中间变量极易误读。
    val isSelected = selected
    Column(
        modifier
            .clip(Capsule)
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            // Role.Tab 此前只有 role 没有 selected：TalkBack 播报不出「已选中」
            // （三线审查 Wave10）。加在 clickable 同一布局节点上，两个语义配置
            // 合并进同一节点；echo 层（:474）的 clearAndSetSemantics {} 是整层
            // 清空、本来就不播报，不受影响。
            // 注意必须写 this.selected：裸名 selected 会被同名函数参数遮蔽
            // （局部作用域优先于隐式 receiver 的扩展属性），赋值就落在 val 参数上。
            .semantics { this.selected = isSelected }
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                // 入参 selected（函数参数，非 this.selected —— 遮蔽语义见上方 semantics 块）：
                // 只有选中格做 compound 注册缩放，非选中格恒 1f（见 CompositionLocal KDoc）。
                val s = scale(selected)
                scaleX = s
                scaleY = s
            },
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = tab.label,
            tint = if (selected) colors.accent else colors.onGlassSubtle,
            modifier = Modifier.size(22.dp),
        )
        // 窄容器（compactTabs）下不渲染文字：4 字标签在 ~48dp 单项宽里会被 Clip 成
        // 半个字，宁可只剩图标（contentDescription 仍保留无障碍语义）。
        if (showLabel) {
            Text(
                text = tab.label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) colors.onGlass else colors.onGlassSubtle,
                maxLines = 1,
                // 必须显式 Ellipsis：默认 Clip 在中文下是"切半个字"，比省略号观感差得多
                //（六路审查 B-P1-3）。
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}
