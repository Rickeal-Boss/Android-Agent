package com.rickeal.agent.core.design

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import com.rickeal.agent.core.design.liquid.internal.recordLayer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

/**
 * 覆盖层背景深度模糊（2026-09-27 用户需求；同日二次迭代为「缩略图管线」）。
 *
 * ## 需求原文与落点
 *
 * 「覆盖层打开时都需要把除了覆盖层以外的**整个背景**完全加入深度模糊（**随动画逐渐加强度**）」
 * —— 用户给的三个实例是：会话抽屉（本地工作区）、对话框（添加附件）、推理参数面板。
 * 追加反馈：「更多级别的过渡」（也可直接使用**非常多级缩略图播放**以提高性能）。
 *
 * ## 为什么要一个「共享状态 + 修饰符」而不是各自为政
 *
 * 三类覆盖层的**宿主层级完全不同**，不能用同一处代码一刀切：
 *
 * | 覆盖层 | 与「要模糊的那块背景」的关系 | 模糊施加点 |
 * |---|---|---|
 * | 会话抽屉 | `ModalNavigationDrawer` 的 `drawerContent`，是 body 的**兄弟** | 外壳 body（[OverlayBlurScope.SHELL]） |
 * | 对话框 | **独立窗口**（`androidx.compose.ui.window.Dialog`），压在整个应用之上 | 外壳 body（[OverlayBlurScope.SHELL]） |
 * | 推理参数面板 | 活在 `ChatScreen` 里、**在 NavHost 内部**（是 NavHost 的孩子） | 本屏 `GlassScaffold`（[OverlayBlurScope.PANEL]） |
 *
 * 关键约束：**Compose 里没有「反模糊」** —— 一旦父节点挂了 `RenderEffect`，子树全部跟着糊，
 * 没法在子节点上把它撤销。所以施加点必须选在「覆盖层刚好在它外面（绘制序在其后）」的那一层：
 *  - 抽屉与对话框都在 NavHost **外面/之上** ⇒ 模糊整个 `MainShell` body 是安全的；
 *  - 参数面板在 NavHost **里面** ⇒ 外壳那层模糊会把它自己也糊掉，只能由 `ChatScreen`
 *    模糊自己的 `GlassScaffold`（面板是它的兄弟节点，绘制序在后 ⇒ 不被糊），
 *    同时**单独**通知外壳把底部页签条也糊上（否则会剩一条清晰的玻璃页签浮在糊背景上，
 *    与本需求「整个背景」矛盾 —— 页签是 NavHost 的兄弟、在 ChatScreen 管辖范围之外）。
 *
 * ## 强度「随动画逐渐加强度」怎么来
 *
 * 两条路，按覆盖层的动画宿主选：
 *  - **抽屉**：直接把抽屉自身的位移映射成进度（`drawerState.currentOffset`），**完全跟手**，
 *    拖拽 / 开合动画全程逐帧同步，比任何自绘动画都准；
 *  - **对话框 / 面板**：由 [rememberOverlayBlurProgress] 起一个弹簧，
 *    在覆盖层出现 / 消失时驱动 0→1 / 1→0（规格与覆盖层自身进出场弹簧同源
 *    `LocalLiquidMotion`，所以观感是同一步调）。
 *
 * ## 缩略图管线（为什么 + 取舍）
 *
 * 旧实现把 `BlurEffect` 直接挂在目标节点的全屏 `graphicsLayer` 上：高斯成本 ∝ 半径 × 面积，
 * 每改一次半径就是一次**全屏**离屏重建，所以只能把半径量化到粗台阶（约 10 级），
 * 级跳肉眼可辨 —— 这正是用户追加反馈要改的两件事。
 *
 * 新管线：**降采样捕获一次 + 缩小空间里做廉价逐帧模糊 + 放大铺回**。
 *  - 面积比 1/scale²（256px 上限、夹在 1/16..1/4，典型全屏 3x 机上 scale≈1/8，
 *    缩略图面积约为全屏的 1/64）—— 高斯在这么小的空间里逐帧重做也近乎免费，
 *    于是半径可以**逐帧连续**变化，0.25px 台阶换算回屏幕空间 ≈ 0.7dp，
 *    20dp 满量程约 30 级过渡，级跳不可辨（用户要的「更多级别」）；
 *  - 附带第二重收益：p≥1 时缩略图完全盖住内容，可以**跳过一次全量内容绘制**
 *    （深度模糊下背景本来就糊到看不清细节，放大后的缩略图与真模糊视觉等价）；
 *  - **取舍（用户提案的既定语义）**：捕获的是**激活瞬间的静态背景** —— 覆盖层开着期间
 *    背景内容变化（如流式输出）不重录；p 回 0 作废捕获标记，下次激活重新捕获。
 *
 * ## 性能纪律（本文件最重要的部分）
 *
 * 1. **进度只在 draw 阶段读**（`drawWithContent {}` 的 block），绝不在组合期读 ——
 *    否则每帧都会重组 `MainShell`（连带 NavHost 与整屏）。这正是 `LiquidAgentApp` 里
 *    `DrawerBackHandler` 的 KDoc 记着的那类事故（在 MainShell 里读抽屉派生状态 ⇒
 *    每帧重组整屏）；这里把读取**下沉到 draw 阶段**，同类需求一律照此办理。
 *    （该符号在 `app` 模块，本文件在 `core-design` 不依赖它 ⇒ 写成文字引用而非 KDoc 链接。）
 * 2. **进度为 0 时零成本** —— `renderEffect = null`、不画缩略图，只多一个空跑的 draw block。
 * 3. **同一像素值复用同一个 `BlurEffect` 实例**（RenderEffect 是引用比较，每帧新建
 *    「数值相同」的实例会触发图层重建）。缓存与「已捕获尺寸」都住在 `remember` 的
 *    [ThumbPipelineCache] 里而不是 lambda 捕获的 `var`：draw block 的闭包随重组重建，
 *    跨帧状态必须由 remember 承载（上一轮审查明确的纪律）。
 *    ⚠️ 在仓记载（`DrawBackdropModifier`）：手动 create/remember 的图层改 renderEffect
 *    **不会自动触发重绘** —— 本设计不依赖它：重绘由 draw block 里的 `progress()` 读取驱动
 *    （p 每变一次 draw block 重跑、renderEffect 随之重设），因果闭环。
 * 4. 受 [GlassConfig.enableBackdropBlur] 总闸门控 —— 它与玻璃背景模糊是同一类开销
 *    （设置页「背景模糊」就是为这类开销准备的开关），关掉后覆盖层只剩 scrim 压暗。
 */
enum class OverlayBlurScope {
    /**
     * 覆盖层在**外壳 body 之外**（抽屉 / 独立窗口对话框）⇒ 模糊整个 `MainShell` body
     * （NavHost + 悬浮页签一起糊）。
     */
    SHELL,

    /**
     * 覆盖层在 **NavHost 内部**（`ChatParamsSheet`）⇒ 由覆盖层所在屏自己模糊自己的
     * `GlassScaffold`；外壳只补一轮「仅悬浮页签」的模糊。
     */
    PANEL,
}

/**
 * 覆盖层模糊的**占用登记表**。
 *
 * 一个覆盖层可能在壳层任意深度被 `LiquidDialog` 拉起（14 个调用点），也可能在别的窗口
 * （Dialog 的独立窗口）里 —— 它们没法通过参数把「我开了」传给 `MainShell`，
 * 只能共用一个状态对象（经 [LocalOverlayBlurState] 下发；`Dialog` 是子组合，
 * `CompositionLocal` 会照常继承进来）。
 *
 * 用**登记表**而不是一个 `Boolean`：对话框可能叠对话框、面板与对话框可能同时在，
 * 用计数语义（占位集合）才能保证「谁先释放都不会误清」。
 * 释放一律挂在 `DisposableEffect` 的 `onDispose` 上，覆盖层离开组合即自动归还 ——
 * 不留「卡在模糊」的僵尸状态。
 *
 * 读这两个派生值会订阅整张表（[mutableStateMapOf]），但**只在 draw 阶段的 lambda 里读**
 * （见 [rememberOverlayBlurProgress] 的用法），所以订阅触发的只是绘制失效，不是重组。
 */
@Stable
class OverlayBlurState {
    private val holders = mutableStateMapOf<Any, OverlayBlurScope>()

    /** 是否有 [OverlayBlurScope.SHELL] 级覆盖层占用（对话框 / 抽屉）。 */
    val shellActive: Boolean
        get() = holders.containsValue(OverlayBlurScope.SHELL)

    /** 是否有任意覆盖层占用（含 [OverlayBlurScope.PANEL]）——「页签条也要糊」的判据。 */
    val anyActive: Boolean
        get() = holders.isNotEmpty()

    /**
     * 登记一个覆盖层。 [owner] 用 `remember { Any() }` 的令牌（身份比较，天然唯一）。
     * 重复登记同一 owner 幂等（覆盖 scope）。
     */
    fun acquire(owner: Any, scope: OverlayBlurScope) {
        holders[owner] = scope
    }

    /** 归还。未登记过的 owner 调用无副作用（可安全地「先释放再检查」）。 */
    fun release(owner: Any) {
        holders.remove(owner)
    }
}

/**
 * 覆盖层模糊状态的下发点。默认值是**无人消费的孤儿实例** ——
 * 没有 provider 时（Compose 预览 / 单测 / 首启闸门内部的对话框）覆盖层照常登记，
 * 只是没有任何节点去读它，零副作用。真正的 provider 在 `MainShell` 之上。
 */
val LocalOverlayBlurState: ProvidableCompositionLocal<OverlayBlurState> =
    staticCompositionLocalOf { OverlayBlurState() }

/**
 * 把「覆盖层开着没有」变成一条可喂给 [Modifier.overlayBackdropBlur] 的进度曲线（0..1）。
 *
 * 返回的是 **lambda 而不是 Float**：调用点把它塞进 draw block，
 * 于是弹簧每帧的变化只失效绘制，**不会重组外壳**（`animateFloatAsState` 会把值读回组合期，
 * 在这个量级的节点上是不可接受的 —— 见 `MainShell` 里 `DrawerBackHandler` 的同类记载）。
 *
 * [active] 也刻意是 lambda：`snapshotFlow` 在协程里读它，`MainShell` 连
 * 「覆盖层开了」这件事都不会因订阅而重组。
 *
 * [GlassConfig.reduceMotion] 打开时直接 `snapTo` —— 减少动效的用户要的是「立刻到位」，
 * 而不是「慢慢糊上来」。
 *
 * @param enabled false 时**不起协程**（归零后直接 return）：关掉「背景模糊」总闸后没有
 *   节点消费进度，让弹簧空转纯属白烧 CPU；开关翻回 true 时 LaunchedEffect 重启、从 0 起算。
 */
@Composable
fun rememberOverlayBlurProgress(
    active: () -> Boolean,
    enabled: Boolean = true,
): () -> Float {
    val motion = LocalLiquidMotion.current
    val reduceMotion = LocalGlassConfig.current.reduceMotion
    val anim = remember { Animatable(0f) }
    val currentActive by rememberUpdatedState(active)
    LaunchedEffect(motion, reduceMotion, enabled) {
        if (!enabled) {
            // 总闸关闭：归零 + 不订阅（否则弹簧会在无人消费的情况下每帧跑）。
            anim.snapTo(0f)
            return@LaunchedEffect
        }
        snapshotFlow { currentActive() }
            .distinctUntilChanged()
            .collect { open ->
                val target = if (open) 1f else 0f
                if (reduceMotion) {
                    anim.snapTo(target)
                } else {
                    // 与 LiquidDialog / LiquidBottomTabs 同一套弹簧规格（LocalLiquidMotion），
                    // 所以模糊的渐强与覆盖层自身的进出场是同一步调。
                    anim.animateTo(target, LiquidMotion.floatSpring(motion))
                }
            }
    }
    return remember(anim) { { anim.value } }
}

/**
 * 缩略图最大宽度（px）。全屏宽被压到不超过它（同时夹在 1/16..1/4 缩放比内）：
 * ≤256px 宽的图层做高斯在低端机也是亚毫秒级，这是「逐帧连续改半径」的成本前提。
 */
private const val THUMB_MAX_WIDTH_PX = 256f

/**
 * 缩略图空间的模糊量化台阶（px）。0.25px 在缩略图空间经 1/scale 放大后 ≈ 屏幕空间 2px
 * ≈ 0.7dp（@3x），20dp 满量程约 30 级过渡 —— 旧全屏管线的 2dp 台阶只有 ~10 级且级跳可见。
 * 量化只用于 [BlurEffect] 实例复用（引用比较省重建），级差本身已不可辨。
 */
private const val THUMB_BLUR_STEP_PX = 0.25f

/**
 * [Modifier.overlayBackdropBlur] 的跨帧状态。draw block 的闭包随重组重建，
 * 「已捕获尺寸」与 BlurEffect 实例缓存必须住在 remember 的对象里才跨得住帧。
 */
private class ThumbPipelineCache {
    /** 最近一次捕获时缩略图的尺寸；null = 尚未捕获（或已因进度归零作废）。 */
    var capturedSize: IntSize? = null

    /** 最近一次赋给 [cachedEffect] 的缩略图空间半径（px），NaN = 尚未建过实例。 */
    var lastThumbRadiusPx: Float = Float.NaN

    /** 同一像素半径复用的同一个 BlurEffect 实例（RenderEffect 是引用比较）。 */
    var cachedEffect: RenderEffect? = null
}

/**
 * 给节点挂上「覆盖层背景深度模糊」（缩略图管线版，见文件 KDoc「缩略图管线」一节）。
 *
 * **为什么不用 `Modifier.blur`**：它的半径是普通参数，改一次半径要重走组合；
 * 而本需求要求半径逐帧随动画变化。`drawWithContent {}` 的 block 在 **draw 阶段**执行，
 * 状态读在里面的失效范围只有绘制本身 —— 这是「逐帧动画 + 零重组」的唯一写法。
 *
 * **为什么不再把 `BlurEffect` 挂在全屏 `graphicsLayer` 上**：全屏高斯每改一次半径
 * 就是全屏离屏重建，只能粗台阶量化（级跳可见）。新管线改为：draw 阶段把内容
 * 降采样录制进 [rememberGraphicsLayer] 的缩略图层（激活期内仅捕获一次），
 * 在缩略图上做廉价逐帧模糊，再放大铺回 —— 半径逐帧连续、成本可忽略，
 * 且 p≥1 时可跳过全量内容绘制（缩略图完全盖住内容）。
 *
 * @param progress 0..1 的模糊强度，**只能在 draw 阶段读**（lambda 每次执行都在 draw 阶段）。
 *   0 表示不挂 RenderEffect、不录缩略图（零成本）。
 * @param radius 强度 1 时的模糊半径（dp，屏幕空间）。来自 [GlassConfig.overlayBlurRadius]。
 * @param enabled false 时**完全不挂这个修饰符**（不是「挂一个 0 半径的」）——
 *   设置页关掉「背景模糊」时连 draw block 一起省掉。
 */
@Composable
fun Modifier.overlayBackdropBlur(
    progress: () -> Float,
    radius: Dp,
    enabled: Boolean = true,
): Modifier {
    if (!enabled || radius.value <= 0f) return this
    // 生命周期由 remember 机制自管，不手动 release（在仓先例：liquid/backdrops/LayerBackdrop.kt
    // 的 rememberLayerBackdrop 默认参数用法）。
    val thumbLayer = rememberGraphicsLayer()
    val cache = remember { ThumbPipelineCache() }
    return this.drawWithContent {
        val p = progress().coerceIn(0f, 1f)
        if (p <= 0f) {
            // 归零即零成本：摘掉效果，并作废「已捕获」标记 —— 下次激活重新捕获
            // 激活瞬间的静态背景（激活期背景变化不重录是既定取舍，见文件 KDoc）。
            thumbLayer.renderEffect = null
            cache.capturedSize = null
            drawContent()
            return@drawWithContent
        }
        if (size.width <= 0f) {
            // 首帧尚未布局：没有可降采样的尺寸，先照常画内容。
            drawContent()
            return@drawWithContent
        }
        val scale = (THUMB_MAX_WIDTH_PX / size.width).coerceIn(1f / 16f, 1f / 4f)
        val thumbSize = IntSize(
            (size.width * scale).toInt().coerceAtLeast(1),
            (size.height * scale).toInt().coerceAtLeast(1),
        )
        if (cache.capturedSize != thumbSize) {
            recordLayer(
                density = drawContext.density,
                layer = thumbLayer,
                size = thumbSize,
            ) {
                // 这里的 drawContent() 调用的是**外层 ContentDrawScope 的**（闭包捕获）——
                // recordLayer 的 block receiver 是它内部 layer.record 新建的 DrawScope。
                // 与 DrawBackdropModifier 的 ContentDrawScope.draw() 里 recordLayer{...drawContent()}
                // 是同款在仓先例，照抄该模式。
                scale(scale, scale, Offset.Zero) { drawContent() }
            }
            cache.capturedSize = thumbSize
        }
        // 屏幕空间期望半径 R_screen = p * radius；缩略图空间 R_thumb = R_screen * scale。
        // 缩略图上 0.25px 台阶放大回屏幕 ≈ 0.7dp，级差不可辨，却让 BlurEffect 实例可复用。
        val steppedThumbRadiusPx =
            (p * radius.toPx() * scale / THUMB_BLUR_STEP_PX).roundToInt() * THUMB_BLUR_STEP_PX
        if (steppedThumbRadiusPx != cache.lastThumbRadiusPx) {
            cache.lastThumbRadiusPx = steppedThumbRadiusPx
            cache.cachedEffect = if (steppedThumbRadiusPx <= 0f) {
                null
            } else {
                // TileMode.Clamp：图层被模糊到边缘时按边缘像素外推，
                // 不会出现「四周一圈半透明」的脏边（与 liquid/effects/Blur.kt 同口径）。
                BlurEffect(null, steppedThumbRadiusPx, steppedThumbRadiusPx, TileMode.Clamp)
            }
        }
        thumbLayer.renderEffect = cache.cachedEffect
        // 淡入：p<1 时缩略图半透明地叠在下层内容上（下层内容也在画），p 趋近 1 逐渐盖满。
        thumbLayer.alpha = p
        if (p < 1f) drawContent()
        // p≥1 时缩略图完全盖住内容 ⇒ 跳过一次全量内容绘制（缩略图管线的第二重收益）。
        withTransform({ scale(1f / scale, 1f / scale, Offset.Zero) }) {
            drawLayer(thumbLayer)
        }
    }
}
