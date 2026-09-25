package com.rickeal.agent.ui

import android.app.Activity
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Psychology
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
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
import com.rickeal.agent.core.design.GlassHapticLevel
import com.rickeal.agent.core.design.GlassMaterial
import com.rickeal.agent.core.design.LiquidAgentTheme
import com.rickeal.agent.core.design.LiquidBottomTabs
import com.rickeal.agent.core.design.LiquidGlassSurface
import com.rickeal.agent.core.design.LocalGlassColors
import com.rickeal.agent.core.design.LocalGlassConfig
import com.rickeal.agent.core.design.LocalGlassTokens
import com.rickeal.agent.core.design.LocalWallpaperImage
import com.rickeal.agent.core.design.TabSpec
import com.rickeal.agent.core.design.WindowSizeClass
import com.rickeal.agent.core.design.WindowWidthClass
import com.rickeal.agent.core.design.liquid.interactive.InteractiveHighlight
import com.rickeal.agent.core.design.liquidGlass
import com.rickeal.agent.core.design.motion.circularReveal
import com.rickeal.agent.core.design.motion.entranceReveal
import com.rickeal.agent.core.design.motion.rememberCircularRevealState
import com.rickeal.agent.core.design.pressLayerBlock
import com.rickeal.agent.core.design.rememberWindowSizeClass
import com.rickeal.agent.feature.chat.ChatRoute
import com.rickeal.agent.feature.chat.chatGraph
import com.rickeal.agent.feature.models.ModelsRoute
import com.rickeal.agent.feature.models.modelsGraph
import com.rickeal.agent.feature.settings.SettingsRoute
import com.rickeal.agent.feature.settings.memory.MemoryRoute
import com.rickeal.agent.feature.settings.settingsGraph
import com.rickeal.agent.feature.settings.tools.ToolsRoute
import com.rickeal.agent.onboarding.FirstRunGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val TAG = "LiquidAgentApp"

/**
 * 壳层圆形揭示的进程级一次性标记（与 core-design motion 包的 entrancePlayed 同一策略）：
 * 冷启动第一次进入 MainShell 播放；旋转 / Activity 重建后不重播 —— 700ms 的全屏揭示
 * 在旋转瞬间再放一遍是干扰不是仪式感。
 */
private var shellRevealPlayed = false

private enum class TopDestination(val route: String, val label: String) {
    CHAT(ChatRoute.ROUTE, "对话"),
    MODELS(ModelsRoute.ROUTE, "模型"),
    TOOLS(ToolsRoute.ROUTE, "工具"),
    MEMORY(MemoryRoute.ROUTE, "记忆"),
    SETTINGS(SettingsRoute.ROUTE, "设置"),
}

private fun iconOf(destination: TopDestination): ImageVector = when (destination) {
    TopDestination.CHAT -> Icons.Filled.Chat
    TopDestination.MODELS -> Icons.Filled.Storage
    TopDestination.TOOLS -> Icons.Filled.Build
    TopDestination.MEMORY -> Icons.Filled.Psychology
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

    // ── 自定义壁纸（Wave 9 需求 3b）──────────────────────────────────────────
    // DataStore 存相对路径 → 这里一次性解码成 ImageBitmap → 经 LocalWallpaperImage
    // 下发，7 个业务屏的 GlassScaffold 经默认参数自动取用（feature 签名零改动）。
    // 解码缓存放在**根组合的 remember(wallpaperPath)**：只在路径变化（冷启动 / 换图 /
    // 恢复默认）时解码一次，旋转、切页签全走缓存；刻意不做进程级单例 ——
    // 组合销毁后 Bitmap 交给 GC，泄漏面更小。文件丢失 / 损坏 → null → 自动回退程序化壁纸。
    val wallpaperPath by container.settingsRepository.wallpaperPath.collectAsState(initial = "")
    var wallpaperBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(wallpaperPath) {
        wallpaperBitmap = if (wallpaperPath.isBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) { container.wallpaperStore.decode(wallpaperPath) }
        }
    }

    // 「生成速度通知」开关 → notifier 总开关（Wave 9 需求 5）。
    // 用 collect 而不是一次性赋值：设置页改开关要**立刻**生效（正在生成时也能开/关），
    // 不必等重启。notifier 的 enabled 关闭时 onTick 是纯 no-op（连时间戳都不动）。
    LaunchedEffect(container) {
        container.settingsRepository.generationNotification.collect { enabled ->
            container.generationNotifier.enabled = enabled
        }
    }
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
        // 触感档位：ThemeState 存的是 String（core-data 不能 import core-design 的枚举），
        // 在这里还原。脏值 / 枚举改名 / 老版本一律回退 STANDARD，不让坏值毒化整个设置。
        hapticLevel = runCatching { GlassHapticLevel.valueOf(themeState.hapticLevel) }
            .getOrDefault(GlassHapticLevel.STANDARD),
    )

    LiquidAgentTheme(darkTheme = darkTheme, glassConfig = glassConfig) {
        CompositionLocalProvider(
            LocalAppContainer provides container,
            // 壁纸位图在 Theme 内 provide：程序化壁纸/玻璃配色都按 darkTheme 取色，
            // provide 在主题内保证「换深浅色 → scrim 重算」与壁纸位图同帧生效。
            LocalWallpaperImage provides wallpaperBitmap?.asImageBitmap(),
        ) {
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
    // 选中态必须与 TopDestination 一一对应（六路审查 B-P0-1）：**不要写 else 兜底** ——
    // 枚举加项后 else 会把新页签静默归到 CHAT（胶囊错位、点当前页签重导航、Rail 无选中态），
    // 全部是"不报错但行为错"。下面的 when 对枚举穷尽，漏改 iconOf 那样直接编译失败。
    val selected = when {
        currentRoute == null -> TopDestination.CHAT
        else -> routeTop(currentRoute) ?: TopDestination.CHAT
    }

    // ── 壳层圆形揭示（2026-09-24 Wave 6）：首启闸门放行后，整个主界面从中心 ──
    // 圆形展开（700ms FastOutSlowIn），这是"液态壳体成型"的签名瞬间。
    // 进程级一次性（shellRevealPlayed）：旋转 / 主题内重组一律走 progress=1 的
    // 快速路径（无 clipPath 开销、无重播闪烁）；reduceMotion 用户直接跳过动画。
    val glassCfg = LocalGlassConfig.current
    val revealState = rememberCircularRevealState(
        if (shellRevealPlayed || glassCfg.reduceMotion) 1f else 0f,
    )
    var shellSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(Unit) {
        if (revealState.progress.value >= 1f) return@LaunchedEffect
        // 等首个布局 pass 给出壳层尺寸，再从中心展开（半径取对角线，任意原点都全覆盖）。
        snapshotFlow { shellSize }
            .filter { it != IntSize.Zero }
            .first()
        shellRevealPlayed = true
        revealState.expand(Offset(shellSize.width / 2f, shellSize.height / 2f))
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { shellSize = it }
            .circularReveal(
                progress = { revealState.progress.value },
                origin = { revealState.origin },
            ),
    ) {
        if (windowSize.useTwoPane) {
            GlassNavRail(
                selected = selected,
                onSelect = { destination ->
                    if (destination != selected) navController.navigateTop(destination.route)
                },
                windowSize = windowSize,
                modifier = Modifier
                    .fillMaxHeight()
                    .statusBarsPadding()
                    // Wave4 审查（B-P1-1）：Rail 此前缺导航栏避让 —— 5 项变高后
                    // 底部页签会被手势导航条压住，必须补。
                    .navigationBarsPadding(),
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
                // 页签切换 = iOS push/pop **视差**：新页大幅入场（32%），旧页小幅让位（14%）。
                // 旧实现两侧都满屏平移（±100%）+ 双 fade + tween(300)，观感是"整块屏幕
                // 被拖走"，而不是"推入一层新页"—— 深度感全靠模糊硬撑。
                // spec 依据（写死前逐条对过）：
                //  - 0.32 / 0.14 视差比：新页大幅入场、旧页小幅让位（iOS push 的经典比例），
                //    旧页只挪 14% 就能透出"下面还有一层"的暗示；
                //  - 260ms **大于**底部指示胶囊的 ~120ms（Wave 6b 定下的次序：胶囊先到位、
                //    内容随后到 —— 若内容比胶囊快，就会看到内容先飞进来胶囊再追）；
                //  - fade 内外**错开**（入 180 / 出 200）：若入出同长同相，切页瞬间两页都
                //    半透明叠在一起，看起来是"糊"而不是"换"。
                // 方向仍由新旧 destination 的页签索引差决定（索引增大 → 新页从右入、旧页向左出；
                // 反向则相反），pop 方向取反。四个方向都显式给值，避免依赖 NavHost 各版本默认值。
                // reduceMotion：四个 transition 全部 tween(0) —— 近瞬时切页，保留层级变化、去掉位移。
                enterTransition = {
                    val dir = slideDirection(initialState.destination.route, targetState.destination.route)
                    slideInHorizontally(
                        initialOffsetX = { fullWidth -> (fullWidth * PUSH_ENTER_PARALLAX).toInt() * dir },
                        animationSpec = tween(
                            if (glassCfg.reduceMotion) 0 else PUSH_SLIDE_MS,
                            easing = FastOutSlowInEasing,
                        ),
                    ) + fadeIn(tween(if (glassCfg.reduceMotion) 0 else PUSH_FADE_IN_MS))
                },
                exitTransition = {
                    val dir = slideDirection(initialState.destination.route, targetState.destination.route)
                    slideOutHorizontally(
                        targetOffsetX = { fullWidth -> (-fullWidth * PUSH_EXIT_PARALLAX).toInt() * dir },
                        animationSpec = tween(
                            if (glassCfg.reduceMotion) 0 else PUSH_SLIDE_MS,
                            easing = FastOutSlowInEasing,
                        ),
                    ) + fadeOut(tween(if (glassCfg.reduceMotion) 0 else PUSH_FADE_OUT_MS))
                },
                popEnterTransition = {
                    val dir = -slideDirection(initialState.destination.route, targetState.destination.route)
                    slideInHorizontally(
                        initialOffsetX = { fullWidth -> (fullWidth * PUSH_ENTER_PARALLAX).toInt() * dir },
                        animationSpec = tween(
                            if (glassCfg.reduceMotion) 0 else PUSH_SLIDE_MS,
                            easing = FastOutSlowInEasing,
                        ),
                    ) + fadeIn(tween(if (glassCfg.reduceMotion) 0 else PUSH_FADE_IN_MS))
                },
                popExitTransition = {
                    val dir = -slideDirection(initialState.destination.route, targetState.destination.route)
                    slideOutHorizontally(
                        targetOffsetX = { fullWidth -> (-fullWidth * PUSH_EXIT_PARALLAX).toInt() * dir },
                        animationSpec = tween(
                            if (glassCfg.reduceMotion) 0 else PUSH_SLIDE_MS,
                            easing = FastOutSlowInEasing,
                        ),
                    ) + fadeOut(tween(if (glassCfg.reduceMotion) 0 else PUSH_FADE_OUT_MS))
                },
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

/**
 * 顶层页签切换的 iOS push/pop 视差参数（替代原满屏平移 + 双 fade + 300ms 的旧配方）。
 *
 * 视差比：新页入场 32%、旧页退场 14% —— 新页"推入"、旧页"让位"，而不是两页等速
 * 把整块屏幕拖走。slide 统一 260ms（FastOutSlowIn）：必须**大于**底部指示胶囊的
 * TabSwitch（≈120ms 临界阻尼收敛，Wave 6b 真机定标）—— 点哪儿、哪儿先亮，指示器
 * 快于内容层是正确次序；内容层若比胶囊快，就会看到内容先飞进来、胶囊再追。
 * fade 内外错开（入 180 / 出 200）：同长同相会让切页瞬间两页都半透明叠在一起，
 * 看起来是"糊"而不是"换"。
 */
private const val PUSH_ENTER_PARALLAX = 0.32f
private const val PUSH_EXIT_PARALLAX = 0.14f
private const val PUSH_SLIDE_MS = 260
private const val PUSH_FADE_IN_MS = 180
private const val PUSH_FADE_OUT_MS = 200

/**
 * route → 顶层页签的**最长前缀**匹配（六路审查 B-P0-1/P1-4 的单一事实来源改造）。
 *
 * 此前 `selected` 与 `topIndex` 各写一份 `startsWith` 字面量 + else 兜底，页签从 3 扩到 5
 * 时任何一处漏改都是静默错乱。现在两者都从 [TopDestination.entries] 派生：
 * 枚举加项只改枚举与 [iconOf]（后者漏改编译失败，是天然的第一道守卫）。
 *
 * 子路由（`settings/diagnostics`、`settings/legal`）按最长前缀归到所属页签，
 * 因此「进入设置子页」天然继承设置的选中态与滑动方向。
 */
private fun routeTop(route: String?): TopDestination? {
    if (route == null) return null
    return TopDestination.entries
        .filter { route == it.route || route.startsWith("${it.route}?") || route.startsWith("${it.route}/") }
        .maxByOrNull { it.route.length }
}

/**
 * 页签切换的滑动方向：+1 = 新页从右入（页签索引增大），-1 = 从左入。
 * 子路由按其所属顶层页签计（见 [routeTop]），
 * 这样"进入设置子页"也天然从右侧滑入。
 */
private fun slideDirection(initialRoute: String?, targetRoute: String?): Int =
    if (topIndex(targetRoute) >= topIndex(initialRoute)) 1 else -1

private fun topIndex(route: String?): Int =
    routeTop(route)?.let { TopDestination.entries.indexOf(it) } ?: 0

/**
 * 返回键兜底收敛的 pop 次数上限。
 *
 * 正常路径下 [NavHostController.navigateTop] 一次就把栈收敛成 `[chat]`，这里跑 0 次。
 * 只有 startDestination 与 composable route 失配（UI-01）导致 popUpTo 静默失效时才会真跑，
 * 而回退栈深度受页签数 + 子页深度约束 —— Wave4 页签 3→5 + 设置子页 2 个，
 * 最坏栈深 > 8，取 12 留余量（六路审查 B-P1-5）。
 * 设上限是为了不让正确性依赖 Navigation 内部是否同步移除 backQueue。
 */
private const val MAX_BACK_STACK_DRAIN = 12

/**
 * 底部导航栏（COMPACT）。
 *
 * 真·LiquidBottomTabs：**选中指示胶囊随选中项滑动**（对齐 Kyant0 的四层结构），
 * 不再用"材质厚薄"区分选中态。四层结构与材质/折射参数全部收在
 * `core-design` 的 [LiquidBottomTabs] 里，这里只做 TopDestination → TabSpec 的映射。
 *
 * 拖动胶囊可直接换页（松手吸附到最近页签并触发导航）；单击未选中页签同样触发。
 * 外部选中态回流（返回键回到对话页等）会让胶囊滑回正确位置。
 */
@Composable
private fun GlassNavBar(
    selected: TopDestination,
    onSelect: (TopDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    LiquidBottomTabs(
        tabs = TopDestination.entries.map { destination ->
            TabSpec(label = destination.label, icon = iconOf(destination))
        },
        selectedIndex = TopDestination.entries.indexOf(selected),
        onSelected = { index -> onSelect(TopDestination.entries[index]) },
        modifier = modifier.padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/**
 * 左侧导航栏（MEDIUM / EXPANDED）——「桌面端」分类排版的容器。
 *
 * Wave4 五页签改造：竖排项 3→5 后的高度预算（5×48 + 间距 24 + 标题 28 + 内边距 72
 * ≈ 364dp）在横屏手机（可用高度 ≈ 288dp）会溢出，所以加 [verticalScroll] 兜底
 * （六路审查 B-P1-1）；并补 [navigationBarsPadding] —— 5 项变高后底部页签更容易
 * 被手势导航条压住。宽度按窗口档位参数化：MEDIUM 维持 88dp 图标栏，
 * EXPANDED 加宽到 132dp 让「图标 + 文字」完全展开（桌面端分类入口的可读性）。
 *
 * 与 [GlassNavBar] 同一套：容器走门面（胶囊 + 关色散），页签项走裸 `liquidGlass` + 跟手形变。
 */
@Composable
private fun GlassNavRail(
    selected: TopDestination,
    onSelect: (TopDestination) -> Unit,
    windowSize: WindowSizeClass,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current
    val railWidth = if (windowSize.width == WindowWidthClass.EXPANDED) 132.dp else 88.dp
    LiquidGlassSurface(
        modifier = modifier
            .padding(horizontal = 10.dp, vertical = 12.dp)
            .width(railWidth),
        material = GlassMaterial.THIN,
        capsule = true,
        // 同 [GlassNavBar]：保留但被胶囊覆盖。
        cornerRadius = tokens.radiusLg,
        dispersion = false,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
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
                    // 冷启动错峰入场：5 项按序号依次浮现（进程内仅一次，见 entranceReveal）。
                    // graphicsLayer-only，不参与任何交互重组。
                    modifier = Modifier
                        .fillMaxWidth()
                        .entranceReveal(order = destination.ordinal),
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
 *  2. **tanh 阻尼**的跟手位移（[pressLayerBlock]）—— 拖多远都不会飞出去，松手回弹
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
    // 局部别名：semantics 块里赋值目标（selected 语义属性）与参数同名，避免误读。
    val isSelected = selected

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
            // 与 LiquidBottomTabs 页签同一口径（三线审查 Wave10）：Role.Tab
            // 必须带 selected，TalkBack 才播报得出「已选中」。
            .semantics { selected = isSelected }
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
                pressProgress = { interactiveHighlight.pressProgress },
                // 复用 core-design 的正式实现，不在 app 侧留副本：
                // 两份实现一旦分叉就是"页签和按钮手感不一样"，而且没有任何报错。
                layerBlock = pressLayerBlock(interactiveHighlight, maxScale = 16.dp),
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
