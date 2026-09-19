package com.rickeal.agent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rickeal.agent.LiquidAgentApplication
import com.rickeal.agent.core.data.DarkMode
import com.rickeal.agent.core.data.LocalAppContainer
import com.rickeal.agent.core.data.ThemeState
import com.rickeal.agent.core.design.GlassConfig
import com.rickeal.agent.core.design.GlassMaterial
import com.rickeal.agent.core.design.LiquidAgentTheme
import com.rickeal.agent.core.design.LiquidGlassSurface
import com.rickeal.agent.core.design.LocalGlassColors
import com.rickeal.agent.core.design.LocalGlassTokens
import com.rickeal.agent.core.design.rememberWindowSizeClass
import com.rickeal.agent.feature.chat.ChatRoute
import com.rickeal.agent.feature.chat.chatGraph
import com.rickeal.agent.feature.models.ModelsRoute
import com.rickeal.agent.feature.models.modelsGraph
import com.rickeal.agent.feature.settings.SettingsRoute
import com.rickeal.agent.feature.settings.settingsGraph

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
 * 应用根：主题 → DI → 导航 → 自适应外壳。
 *
 * 自适应策略（架构 §7.5）：
 *  - COMPACT（手机竖屏）  单栏 + 底部玻璃导航栏
 *  - MEDIUM / EXPANDED    左侧玻璃导航栏（Rail）+ 内容区
 * 每个 feature 屏幕内部再按 `WindowSizeClass` 决定是否多开一栏（Chat 的参数面板）。
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
    val glassConfig = GlassConfig(
        // 真实背景模糊：minSdk 31 = Android 12，RenderEffect 官方保证可用，默认打开。
        // 之前这里写死 false 是为了绕过「无法本地验证」时期的编译风险，现已用正确的
        // BlurEffect 落地，保持开启才能看到液态玻璃的真实观感。
        enableBackdropBlur = true,
        enableNoise = themeState.enableNoise,
        enableSpecular = true,
        reduceMotion = themeState.reduceMotion,
        intensity = themeState.glassIntensity,
    )

    LiquidAgentTheme(darkTheme = darkTheme, glassConfig = glassConfig) {
        CompositionLocalProvider(LocalAppContainer provides container) {
            val windowSize = rememberWindowSizeClass()
            val navController = rememberNavController()
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
                        startDestination = ChatRoute.ROUTE,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
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
        }
    }
}

private fun NavHostController.navigateTop(route: String) {
    navigate(route) {
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun GlassNavBar(
    selected: TopDestination,
    onSelect: (TopDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current
    LiquidGlassSurface(
        modifier = modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        material = GlassMaterial.THICK,
        cornerRadius = tokens.radiusFull,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            for (destination in TopDestination.entries) {
                val isSelected = destination == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(destination) }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Icon(
                        imageVector = iconOf(destination),
                        contentDescription = destination.label,
                        tint = if (isSelected) colors.accent else colors.onGlassSubtle,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = destination.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) colors.onGlass else colors.onGlassSubtle,
                    )
                }
            }
        }
    }
}

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
        cornerRadius = tokens.radiusLg,
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
                val isSelected = destination == selected
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(destination) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Icon(
                        imageVector = iconOf(destination),
                        contentDescription = destination.label,
                        tint = if (isSelected) colors.accent else colors.onGlassSubtle,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = destination.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) colors.onGlass else colors.onGlassSubtle,
                    )
                }
            }
        }
    }
}
