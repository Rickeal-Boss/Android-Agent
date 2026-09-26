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
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.ceil

/**
 * 覆盖层背景深度模糊（2026-09-27 用户需求）。
 *
 * ## 需求原文与落点
 *
 * 「覆盖层打开时都需要把除了覆盖层以外的**整个背景**完全加入深度模糊（**随动画逐渐加强度**）」
 * —— 用户给的三个实例是：会话抽屉（本地工作区）、对话框（添加附件）、推理参数面板。
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
 * ## 性能纪律（本文件最重要的部分）
 *
 * 1. **进度只在 layer 阶段读**（`graphicsLayer {}` 的 block），绝不在组合期读 ——
 *    否则每帧都会重组 `MainShell`（连带 NavHost 与整屏）。这正是 `LiquidAgentApp` 里
 *    `DrawerBackHandler` 的 KDoc 记着的那类事故（在 MainShell 里读抽屉派生状态 ⇒
 *    每帧重组整屏）；这里把读取**下沉到 layer 阶段**，同类需求一律照此办理。
 *    （该符号在 `app` 模块，本文件在 `core-design` 不依赖它 ⇒ 写成文字引用而非 KDoc 链接。）
 * 2. **进度为 0 时 `renderEffect = null`** —— 没有覆盖层时这是零成本的一个图层节点。
 * 3. **半径量化到 [OVERLAY_BLUR_STEP_DP] 台阶**，且同一像素值复用同一个 `BlurEffect` 实例：
 *    每改一次半径，RenderNode 就要重做一次全屏高斯模糊（开销随半径 × 面积增长，
 *    见 `GlassMaterial` 里那条「不做逐帧动画」的记载）。量化 + 实例复用把一次进出场
 *    的**对象重建次数**从「每帧一次」压到「每两 dp 一次」，2dp 台阶在深度模糊下肉眼不可辨。
 *    ⚠️ 口径：**每帧一次全屏高斯本身并没有被台阶消解**（台阶只省掉重建 RenderEffect
 *    与重复赋值），真机若掉帧，优先降半径（设置页「覆盖层背景模糊」），其次把台阶提到 4dp。
 *    ⚠️ 缓存作用域：lambda 捕获的缓存变量**只活到下一次重组**（重组会重建 block 闭包）。
 *    稳态下（拖拽 / 弹簧期间外壳不重组）完全有效；发生重组只多建一次 BlurEffect，
 *    不会退化成每帧新建、也不影响正确性。
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
 * 读这两个派生值会订阅整张表（[mutableStateMapOf]），但**只在 layer 阶段的 lambda 里读**
 * （见 [rememberOverlayBlurProgress] 的用法），所以订阅触发的只是图层失效，不是重组。
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
 * 返回的是 **lambda 而不是 Float**：调用点把它塞进 `graphicsLayer {}` 的 block，
 * 于是弹簧每帧的变化只失效图层，**不会重组外壳**（`animateFloatAsState` 会把值读回组合期，
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
 * 半径台阶（dp）。见类 KDoc 性能纪律第 3 条：量化是为了少建 `BlurEffect`，
 * 2dp 在深度模糊下不可辨，却能把一次进出场的 RenderNode 重建次数砍掉一半以上。
 */
private const val OVERLAY_BLUR_STEP_DP = 2f

/**
 * 给节点挂上「覆盖层背景深度模糊」。
 *
 * **为什么不用 `Modifier.blur`**：它的半径是普通参数，改一次半径要重走组合；
 * 而本需求要求半径逐帧随动画变化。`graphicsLayer {}` 的 block 在 **layer 阶段**执行，
 * 状态读在里面的失效范围只有图层本身 —— 这是「逐帧动画 + 零重组」的唯一写法。
 * 效果本身仍是 `BlurEffect`（`Modifier.blur` 内部也是往 `graphicsLayer.renderEffect` 里塞它，
 * 见其源码 Blur.kt，所以两者能力等价，差别只在**驱动方式**）。
 *
 * @param progress 0..1 的模糊强度，**只能在 layer 阶段读**（lambda 每次执行都在 layer 阶段）。
 *   0 表示不挂 RenderEffect（零成本）。
 * @param radius 强度 1 时的模糊半径（dp）。来自 [GlassConfig.overlayBlurRadius]。
 * @param enabled false 时**完全不挂这个修饰符**（不是「挂一个 0 半径的」）——
 *   设置页关掉「背景模糊」时连图层一起省掉。
 */
fun Modifier.overlayBackdropBlur(
    progress: () -> Float,
    radius: Dp,
    enabled: Boolean = true,
): Modifier {
    if (!enabled || radius.value <= 0f) return this
    // 实例缓存：同一像素半径复用同一个 BlurEffect（RenderEffect 是引用比较，
    // 每帧新建一个「数值相同」的实例同样会触发 RenderNode 重建 —— 那正是要避免的开销）。
    var lastRadiusPx = Float.NaN
    var cachedEffect: RenderEffect? = null
    return this.graphicsLayer {
        val p = progress().coerceIn(0f, 1f)
        val steppedDp = if (p <= 0f) {
            0f
        } else {
            ceil(p * radius.value / OVERLAY_BLUR_STEP_DP) * OVERLAY_BLUR_STEP_DP
        }
        val radiusPx = steppedDp.dp.toPx()
        if (radiusPx != lastRadiusPx) {
            lastRadiusPx = radiusPx
            cachedEffect = if (radiusPx <= 0f) {
                null
            } else {
                // TileMode.Clamp：全屏节点被模糊到屏幕边缘时按边缘像素外推，
                // 不会出现「四周一圈半透明」的脏边（与 liquid/effects/Blur.kt 同口径）。
                BlurEffect(null, radiusPx, radiusPx, TileMode.Clamp)
            }
        }
        // 直接赋值：上面已经保证「同一像素值复用同一实例」，重复赋同一个实例不会
        // 引出一个新的 RenderEffect（框架侧按实例判断是否重新同步到 RenderNode）。
        // 这里只陈述**本文件能保证的**部分，不为框架内部实现背书。
        renderEffect = cachedEffect
    }
}
