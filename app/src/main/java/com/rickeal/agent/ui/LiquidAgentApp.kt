package com.rickeal.agent.ui

import android.app.Activity
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rickeal.agent.LiquidAgentApplication
import com.rickeal.agent.core.data.DarkMode
import com.rickeal.agent.core.data.LocalAppContainer
import com.rickeal.agent.core.data.ThemeState
import com.rickeal.agent.core.design.GlassBackdropBlurOverride
import com.rickeal.agent.core.design.GlassConfig
import com.rickeal.agent.core.design.GlassMaterial
import com.rickeal.agent.core.design.LiquidAgentTheme
import com.rickeal.agent.core.design.LiquidGlassSurface
import com.rickeal.agent.core.design.LocalGlassColors
import com.rickeal.agent.core.design.LocalGlassTokens
import com.rickeal.agent.core.design.liquid.interactive.InteractiveHighlight
import com.rickeal.agent.core.design.liquidGlass
import com.rickeal.agent.core.design.rememberWindowSizeClass
import com.rickeal.agent.feature.chat.ChatRoute
import com.rickeal.agent.feature.chat.chatGraph
import com.rickeal.agent.feature.models.ModelsRoute
import com.rickeal.agent.feature.models.modelsGraph
import com.rickeal.agent.feature.settings.SettingsRoute
import com.rickeal.agent.feature.settings.settingsGraph
import com.rickeal.agent.onboarding.FirstRunGate
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

private const val TAG = "LiquidAgentApp"

private enum class TopDestination(val route: String, val label: String) {
    CHAT(ChatRoute.ROUTE, "对话"),
    MODELS(ModelsRoute.ROUTE, "模型"),
    SETTINGS(SettingsRoute.ROUTE, "设置"),
}

private fun iconOf(destination: TopDestination): ImageVector = when (destination) {
    TopDestination.CHAT -> Icons.Filled.Chat
    TopDestination.MODELS -> Icons.Filled.Storage
    TopDestination.SETTINGS -> Icons.Filled.Settings
}

/**
 * 应用根：主题 → DI → 首启闸门 → 主界面外壳。
 *
 * 首启闸门（[FirstRunGate]）包住整个外壳，而不是塞进某条路由里：
 * 「未接受应用条款就不进主界面」必须是结构性保证，不能依赖导航规则是否写对。
 * 闸门放行后才渲染 [MainShell]。
 */
@Composable
fun LiquidAgentApp() {
    val context = LocalContext.current
    val container = remember(context) {
        (context.applicationContext as? LiquidAgentApplication)?.container
    } ?: return

    val themeState by container.settingsRepository.themeState.collectAsState(initial = ThemeState())
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeState.darkMode) {
        DarkMode.DARK -> true
        DarkMode.LIGHT -> false
        DarkMode.SYSTEM -> systemDark
    }

    // 浅色主题下把状态栏 / 导航栏图标切成深色。
    // themes.xml 的 windowLightStatusBar=false 让图标恒为白色，画在浅色壁纸上几乎看不见；
    // 项目又走了 edge-to-edge（MainActivity 里 setDecorFitsSystemWindows(false)），
    // 系统不会再自动帮我们反色，只能自己在 darkTheme 变化时同步一次。
    val view = LocalView.current
    LaunchedEffect(darkTheme) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }

    val glassConfig = GlassConfig(
        // 真实背景模糊：minSdk 31 = Android 12，RenderEffect 官方保证可用，默认打开。
        // 之前这里写死 false 是为了绕过「无法本地验证」时期的编译风险，现已用正确的
        // BlurEffect 落地，保持开启才能看到液态玻璃的真实观感。
        //
        // UI-07：不能再写死 true —— 它是最贵的一项渲染开销（每个玻璃节点每帧一次
        // 离屏录制 + 高斯模糊），而用户此前**没有任何入口**关掉它。现在由设置页的
        // 「背景模糊」开关驱动，关闭后玻璃退化为底色渐变 + 内描边 + 边缘光。
        // 默认仍为 true，老用户观感不变。
        enableBackdropBlur = GlassBackdropBlurOverride.enabled,
        enableNoise = themeState.enableNoise,
        enableSpecular = true,
        reduceMotion = themeState.reduceMotion,
        intensity = themeState.glassIntensity,
    )

    LiquidAgentTheme(darkTheme = darkTheme, glassConfig = glassConfig) {
        CompositionLocalProvider(LocalAppContainer provides container) {
            // 首启闸门必须在主题之内：引导页 / 条款页要用玻璃组件与主题下发的配色。
            // 放在导航之外，是为了「未接受条款就进不了主界面」这件事不依赖任何路由规则。
            // 同 MainShell：用 LocalActivity 而非 `context as? Activity`。
            // 拒绝条款后点退出却什么都没发生，是最难排查的一类"没反应"。
            val activity: Activity? = LocalActivity.current
            FirstRunGate(
                container = container,
                onExitApp = {
                    if (activity != null) {
                        activity.finish()
                    } else {
                        Log.w(TAG, "首启闸门退出失败：LocalActivity 为 null，无法调用 finish()")
                    }
                },
            ) {
                MainShell()
            }
        }
    }
}

/**
 * 主界面外壳：自适应导航 + NavHost。
 *
 * 自适应策略（架构 §7.5）：
 *  - COMPACT（手机竖屏）  单栏 + 底部玻璃导航栏
 *  - MEDIUM / EXPANDED    左侧玻璃导航栏（Rail）+ 内容区
 * 每个 feature 屏幕内部再按 `WindowSizeClass` 决定是否多开一栏（Chat 的参数面板）。
 *
 * **这里刻意不做 Gemma 授权的拦截**：授权闸门放在 `:feature-models` 的下载 / 加载动作上。
 * 早先的实现在这里拦整个「模型」页，等于让只想下载 Qwen / Phi 的用户也被迫接受
 * Google 的 Gemma 条款 —— 那是**捆绑**：条款的适用边界必须与模型的归属一致。
 */
@Composable
private fun MainShell() {
    val windowSize = rememberWindowSizeClass()
    val navController = rememberNavController()
    // 用 LocalActivity 而不是 `LocalContext.current as? Activity`：
    // 后者一旦解析失败，activity 为 null，`finish()` 就变成静默什么都不做 ——
    // 用户按返回没反应，且没有任何报错。activity-compose 1.10.1 已提供，不是新依赖。
    val activity: Activity? = LocalActivity.current
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val selected = when {
        currentRoute == null -> TopDestination.CHAT
        currentRoute.startsWith("settings") -> TopDestination.SETTINGS
        currentRoute.startsWith("models") -> TopDestination.MODELS
        else -> TopDestination.CHAT
    }

    Row(modifier = Modifier.fillMaxSize()) {
        if (windowSize.useTwoPane) {
            GlassNavRail(
                selected = selected,
                onSelect = { destination ->
                    if (destination != selected) navController.navigateTop(destination.route)
                },
                modifier = Modifier
                    .fillMaxHeight()
                    .statusBarsPadding(),
            )
        }
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            NavHost(
                navController = navController,
                // 必须与 composable 注册的 route 同源（ChatRoute.PATTERN）。
                // 写 ROUTE("chat") 能启动（NavGraphNavigator 是拿 route 字符串去
                // findNode 匹配的），但 destination id 由 createRoute(route).hashCode()
                // 决定，"chat" 与 "chat?conversationId={conversationId}" 算出来是**两个 id**。
                // 于是 navigateTop 的 popUpTo(graph.startDestinationId) 永远匹配不到 ——
                // popBackStackInternal 对「栈里没有这个 id」是打一行日志然后 return false，
                // 一条都不 pop，回退栈就这么随切页签无限涨起来的（UI-01 的根因）。
                startDestination = ChatRoute.PATTERN,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                // 默认 700ms 淡入淡出在切页签时明显“拖沓”，这里统一压到 160ms。
                // 四个方向都显式给值，避免依赖 NavHost 各版本不同的默认值。
                enterTransition = { fadeIn(animationSpec = tween(TOP_NAV_TRANSITION_MS)) },
                exitTransition = { fadeOut(animationSpec = tween(TOP_NAV_TRANSITION_MS)) },
                popEnterTransition = { fadeIn(animationSpec = tween(TOP_NAV_TRANSITION_MS)) },
                popExitTransition = { fadeOut(animationSpec = tween(TOP_NAV_TRANSITION_MS)) },
            ) {
                chatGraph(
                    navController = navController,
                    onOpenModels = { navController.navigateTop(ModelsRoute.build()) },
                    onOpenSettings = { navController.navigateTop(SettingsRoute.build()) },
                )
                modelsGraph(navController = navController)
                settingsGraph(
                    navController = navController,
                    onOpenModels = { navController.navigateTop(ModelsRoute.build()) },
                )
            }
            if (!windowSize.useTwoPane) {
                GlassNavBar(
                    selected = selected,
                    onSelect = { destination ->
                        if (destination != selected) navController.navigateTop(destination.route)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding(),
                )
            }
        }
    }

    // ── 系统返回键：两段式（先回对话页，再一次退出应用）─────────────────────────
    //
    // 用户要的语义很明确：**第一次按返回 → 回到对话页；再按一次 → 退出应用。**
    // 修好 popUpTo 只解决了「栈无限增长」，从「设置 → 某个子页」返回仍会是逐级 pop
    // （子页 → 设置 → 对话 → 退出），不是两段式。所以这里再加一层显式策略。
    //
    // **位置是这段代码能不能生效的关键**：OnBackPressedDispatcher 是后注册的先执行，
    // 而 NavHost 内部自己注册了 PredictiveBackHandler（栈 > 1 时接管返回键）。
    // 放在 NavHost 之前，返回键会被导航层先吃掉，本回调一次都不会执行，
    // 而且**不报错**——是最容易踩空、又最难发现的一类错误。所以必须写在 NavHost 之后。
    BackHandler(enabled = true) {
        val route = navController.currentBackStackEntry?.destination?.route
        if (route != null && isChatRoute(route) && navController.previousBackStackEntry == null) {
            // 第二步：已经在对话页，且栈里只剩它 → 退出应用。
            // 显式判空而不是 `activity?.finish()`：解析不到 Activity 时什么都不做
            // 会让用户「按返回没反应」且无迹可寻，这条日志就是唯一的线索。
            if (activity != null) {
                activity.finish()
            } else {
                Log.w(TAG, "返回键退出失败：LocalActivity 为 null，无法调用 finish()")
            }
        } else {
            // 第一步：无论当前在哪个页签、哪个子页，一律先回到对话页。
            navController.navigateTop(ChatRoute.ROUTE)
            // 兜底收敛：navigateTop 依赖 graph.startDestinationId 命中对话页，
            // 一旦将来又被人改坏（就是 UI-01 的根因），popUpTo 会静默失败、栈继续增长。
            // 这里强制 pop 到只剩栈底的对话页，让「再按一次就退出」不依赖 popUpTo 是否生效。
            //
            // 用**固定次数上限**而不是 while(true)：收敛性不该押在「popBackStack 是否
            // 同步移除 backQueue」这类 Navigation 内部实现上（2.8 起走 popWithTransition，
            // 条目在过渡中时 pop 会被忽略 —— 见 NavigatorState.popWithTransition 的早退分支）。
            // 收敛失败也必须留下 Log，而不是静默退化成「多按几次才退出」。
            for (i in 0 until MAX_BACK_STACK_DRAIN) {
                if (navController.previousBackStackEntry == null) break
                if (!navController.popBackStack()) break
            }
            if (navController.previousBackStackEntry != null) {
                Log.w(
                    TAG,
                    "返回键兜底收敛失败：$MAX_BACK_STACK_DRAIN 次 pop 后回退栈仍不止一条。" +
                        "优先检查 startDestination 与 composable 注册的 route 是否同源" +
                        "（UI-01 的根因，见 docs/09-back-navigation.md）。",
                )
            }
        }
    }
}

/**
 * 当前 route 是否指向对话页。
 *
 * startDestination 用的是 PATTERN，所以栈底那条目的 route 是
 * `chat?conversationId={conversationId}`；而切页签时导航用的是 ROUTE(`chat`)，
 * 带具体会话时又会变成 `chat?conversationId=xxx`。三种形态都要认。
 */
private fun isChatRoute(route: String): Boolean =
    route == ChatRoute.ROUTE ||
        route == ChatRoute.PATTERN ||
        route.startsWith("${ChatRoute.ROUTE}?")

/**
 * 顶层页签跳转。
 *
 * 必须 `popUpTo` 到起始目的地，否则返回栈会随切页签无限增长
 * （`[chat, models, chat, models]`）：按返回键变成“在上一个页签间倒着走”，
 * 退出要按 N 次；更糟的是每个 `chat` 条目都有自己的 `ViewModelStore`，
 * 每次切回对话页都是一个**全新的 ChatViewModel** —— 草稿、滚动位置、
 * 正在流式生成的回答全都会从界面上消失（旧的 VM 还在后台空跑）。
 *
 * `saveState = true` + `restoreState = true` 成对出现才有意义：前者在 pop 时
 * 保存该目的地的 SavedState，后者在重新 navigate 时把它还回去。
 * `inclusive = false` 保留起始目的地本身。
 */
private fun NavHostController.navigateTop(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) {
            saveState = true
            inclusive = false
        }
        launchSingleTop = true
        restoreState = true
    }
}

/** 顶层页签切换的过渡时长（ms）。默认 700ms 太慢，160ms 既顺滑又不拖沓。 */
private const val TOP_NAV_TRANSITION_MS = 160

/**
 * 返回键兜底收敛的 pop 次数上限。
 *
 * 正常路径下 [NavHostController.navigateTop] 一次就把栈收敛成 `[chat]`，这里跑 0 次。
 * 只有 startDestination 与 composable route 失配（UI-01）导致 popUpTo 静默失效时才会真跑，
 * 而回退栈深度受页签数 + 子页深度约束，8 次足够兜住现实的栈深度。
 * 设上限是为了不让正确性依赖 Navigation 内部是否同步移除 backQueue。
 */
private const val MAX_BACK_STACK_DRAIN = 8

/**
 * 底部导航栏（COMPACT）。
 *
 * 结构照 `core-design` 的 `GlassSegmented` —— 它就是"多分项 + 玻璃容器"的现成答案：
 * **容器走门面 `LiquidGlassSurface`，每个页签项走裸 `Modifier.liquidGlass`**。
 * 之所以不能整块都用门面：门面的 `modifier` 在最外层，跟手手势挂不进
 * `drawBackdrop` 之后（Kyant0 原序），页签就只剩静态玻璃，验收过不了。
 *
 * 折射参数取 Kyant0 `LiquidBottomTabs` 的 `lens(24, 24)`（与 `GlassSegmented` 一致）：
 * 导航栏比按钮大，折射带要给足才看得出厚度。
 */
@Composable
private fun GlassNavBar(
    selected: TopDestination,
    onSelect: (TopDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalGlassTokens.current
    LiquidGlassSurface(
        modifier = modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        material = GlassMaterial.THICK,
        // 胶囊（两端正半圆）取代原来的 radiusFull 圆角矩形 —— iOS Liquid Glass 的标志性轮廓。
        capsule = true,
        // cornerRadius 保留但被胶囊覆盖（与 GlassButton 的 cornerRadius 同一处理）：
        // 留着是为了不丢掉"这里原本想做全圆角"的意图；capsule=true 时它被忽略。
        cornerRadius = tokens.radiusFull,
        // 色散要 7 次采样（约 7 倍开销）。导航栏常驻屏幕、3 个页签同时渲染，必须关。
        dispersion = false,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            for (destination in TopDestination.entries) {
                NavDestinationItem(
                    destination = destination,
                    selected = destination == selected,
                    onClick = { onSelect(destination) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 左侧导航栏（MEDIUM / EXPANDED）。
 *
 * 与 [GlassNavBar] 同一套：容器走门面（胶囊 + 关色散），页签项走裸 `liquidGlass` + 跟手形变。
 * 竖排时胶囊就是竖向药丸，两端正半圆 —— 与底部栏同一视觉语言。
 */
@Composable
private fun GlassNavRail(
    selected: TopDestination,
    onSelect: (TopDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current
    LiquidGlassSurface(
        modifier = modifier
            .padding(horizontal = 10.dp, vertical = 12.dp)
            .width(88.dp),
        material = GlassMaterial.THIN,
        capsule = true,
        // 同 [GlassNavBar]：保留但被胶囊覆盖。
        cornerRadius = tokens.radiusLg,
        dispersion = false,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Liquid",
                style = MaterialTheme.typography.titleSmall,
                color = colors.onGlass,
                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
            )
            for (destination in TopDestination.entries) {
                NavDestinationItem(
                    destination = destination,
                    selected = destination == selected,
                    onClick = { onSelect(destination) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * 单个页签项：胶囊玻璃 + 跟手形变。底部栏与侧边栏共用。
 *
 * 三件事缺一就不是"液态"（对齐 `GlassSegmented.SegmentItem`）：
 *  1. 按下时玻璃"变实"（`pressProgress` → 模糊减弱 / 折射增强 / 高光变亮）
 *  2. **tanh 阻尼**的跟手位移（[navPressLayerBlock]）—— 拖多远都不会飞出去，松手回弹
 *  3. **各向异性**拉伸 —— 沿拖动方向拉长、垂直方向压扁；等比缩放就是"原生按钮"手感
 *
 * 每个页签项各自持有 [InteractiveHighlight]，**不能共用**：
 * 共用一个对象会导致按 A 时 B 也跟着形变。
 */
@Composable
private fun NavDestinationItem(
    destination: TopDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) { InteractiveHighlight(animationScope) }

    Column(
        modifier = modifier
            // indication = null：液态玻璃自己有高光 / 内阴影反馈，
            // 再叠一层 M3 ripple 就变成"原生按钮贴玻璃纸"了。
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .liquidGlass(
                // 选中项给到 REGULAR 才有"浮起来"的厚度差；未选中压到最薄，让位给容器。
                material = if (selected) GlassMaterial.REGULAR else GlassMaterial.ULTRA_THIN,
                capsule = true,
                // 对齐 Kyant0 LiquidBottomTabs 的分段项：blur(8) + lens(24, 24)。
                // 模糊必须轻到能看见折射把背景像素"掰弯"，否则就是磨砂塑料。
                blurRadius = 8.dp,
                refractionHeight = 24.dp,
                refractionAmount = 24.dp,
                // 色散 7 次采样，常驻组件必须关。
                dispersion = false,
                pressProgress = interactiveHighlight.pressProgress,
                layerBlock = navPressLayerBlock(interactiveHighlight),
            )
            .then(
                // 顺序不能交换：clickable 在前、gestureModifier 在后（Kyant0 原序）。
                // 交换后 clickable 会先吃掉手势，跟手位移就没了。
                Modifier
                    .then(interactiveHighlight.modifier)
                    .then(interactiveHighlight.gestureModifier),
            )
            // 触摸目标必须是完整 48dp（项目自己在 GlassTokens.minTouchTarget 定的标准）。
            .heightIn(min = tokens.minTouchTarget)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            imageVector = iconOf(destination),
            contentDescription = destination.label,
            tint = if (selected) colors.accent else colors.onGlassSubtle,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = destination.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) colors.onGlass else colors.onGlassSubtle,
        )
    }
}

/**
 * 页签项的**跟手形变**：按下放大 + tanh 阻尼位移 + 各向异性拉伸。
 *
 * ⚠️ 这是 `core-design` 里 `GlassPressLayer.kt` 的 `pressLayerBlock` 的**副本**。
 * 原函数是 `internal`，app 模块调不到；已请 dev-glass 把它导出为 public，
 * **导出后请删掉本函数**，改用 `pressLayerBlock(interactiveHighlight, maxScale = 16.dp)`。
 * 两处逻辑必须保持一致：一旦分叉，页签与按钮的手感会不一样，而且**没有任何报错**。
 *
 * @param maxScale 形变量级。Kyant0 的 BottomTabs / 分段项取 16dp（面板比按钮大），
 *   按钮取 4dp。
 */
private fun navPressLayerBlock(
    interactiveHighlight: InteractiveHighlight,
    maxScale: Dp = 16.dp,
): GraphicsLayerScope.() -> Unit = {
    val width = size.width.coerceAtLeast(1f)
    val height = size.height.coerceAtLeast(1f)
    val progress = interactiveHighlight.pressProgress
    val scale = 1f + (maxScale.toPx() / height) * progress

    val maxOffset = size.minDimension.coerceAtLeast(1f)
    val offset = interactiveHighlight.offset
    translationX = maxOffset * tanh(0.05f * offset.x / maxOffset)
    translationY = maxOffset * tanh(0.05f * offset.y / maxOffset)

    // scaleX 与 scaleY 故意不相等：等比缩放（两者相等）就是"原生按钮"的手感。
    val maxDragScale = maxScale.toPx() / height
    val offsetAngle = atan2(offset.y, offset.x)
    scaleX = scale +
        maxDragScale * abs(cos(offsetAngle) * offset.x / size.maxDimension) *
        (width / height).coerceAtMost(1f)
    scaleY = scale +
        maxDragScale * abs(sin(offsetAngle) * offset.y / size.maxDimension) *
        (height / width).coerceAtMost(1f)
}
