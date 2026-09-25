package com.rickeal.agent.feature.settings

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.rickeal.agent.core.data.AppContainer
import com.rickeal.agent.core.data.LocalAppContainer
import com.rickeal.agent.core.data.viewModelFactory
import com.rickeal.agent.feature.settings.memory.MemoryRoute
import com.rickeal.agent.feature.settings.memory.MemoryScreen
import com.rickeal.agent.feature.settings.memory.MemoryViewModel
import com.rickeal.agent.feature.settings.storage.StorageScreen
import com.rickeal.agent.feature.settings.storage.StorageViewModel
import com.rickeal.agent.feature.settings.tools.ToolsRoute
import com.rickeal.agent.feature.settings.tools.ToolsScreen
import com.rickeal.agent.feature.settings.tools.ToolsViewModel

object SettingsRoute {
    const val ROUTE = "settings"

    fun build(): String = ROUTE
}

object DiagnosticsRoute {
    const val ROUTE = "settings/diagnostics"

    fun build(): String = ROUTE
}

/**
 * 「条款与授权」回看页。
 *
 * 单独一条路由而不是塞进设置首页：条款正文较长，摊在设置列表里会把其它设置项挤下去；
 * 而且它的性质是「查阅已同意内容」，与「调整配置」不是一类操作。
 */
object LegalRoute {
    const val ROUTE = "settings/legal"

    fun build(): String = ROUTE
}

/**
 * 「存储空间」页（Wave 10 Phase 2b C-2）。
 *
 * route 走 `settings/` 前缀：会被 `MainShell` 的 `routeTop()` 最长前缀匹配自动归到
 * SETTINGS 页签（选中态与滑动方向零额外改动）—— 与 diagnostics / legal 同一处置。
 */
object StorageRoute {
    const val ROUTE = "settings/storage"

    fun build(): String = ROUTE
}

fun settingsViewModelFactory(container: AppContainer): ViewModelProvider.Factory =
    viewModelFactory { SettingsViewModel(container) }

fun NavGraphBuilder.settingsGraph(
    navController: NavController,
    onOpenModels: () -> Unit,
) {
    composable(route = SettingsRoute.ROUTE) {
        val container = LocalAppContainer.current
        SettingsScreen(
            viewModel = viewModel(factory = settingsViewModelFactory(container)),
            onOpenDiagnostics = { navController.navigate(DiagnosticsRoute.build()) },
            onOpenLegal = { navController.navigate(LegalRoute.build()) },
            onOpenStorage = { navController.navigate(StorageRoute.build()) },
        )
    }

    composable(route = StorageRoute.ROUTE) {
        val container = LocalAppContainer.current
        StorageScreen(
            viewModel = viewModel(factory = viewModelFactory { StorageViewModel(container) }),
            onBack = { navController.popBackStack() },
            onOpenModels = onOpenModels,
        )
    }

    composable(route = LegalRoute.ROUTE) {
        LegalScreen(onBack = { navController.popBackStack() })
    }

    composable(route = DiagnosticsRoute.ROUTE) {
        val container = LocalAppContainer.current
        DiagnosticsScreen(
            onBack = { navController.popBackStack() },
            readPersistedErrors = { container.agentLogFileStore.read() },
            clearPersistedErrors = { container.agentLogFileStore.clear() },
        )
    }

    // Wave4：工具页提升为一级页签（route 已改为顶层 "tools"），但 composable 仍注册
    // 在本 graph builder 里 —— settingsGraph 只在 MainShell 的 NavHost 上调用一次，
    // 与注册在独立 toolsGraph 完全等价，少一层文件改动（B-P1-8 的零构建脚本改动原则）。
    composable(route = ToolsRoute.ROUTE) {
        val container = LocalAppContainer.current
        ToolsScreen(
            viewModel = viewModel(
                factory = viewModelFactory { ToolsViewModel(container) },
            ),
            onBack = { navController.popBackStack() },
        )
    }

    // Wave4：记忆页与工具页同理 —— 一级页签的路由也注册在本 graph builder。
    composable(route = MemoryRoute.ROUTE) {
        val container = LocalAppContainer.current
        MemoryScreen(
            viewModel = viewModel(
                factory = viewModelFactory { MemoryViewModel(container) },
            ),
        )
    }
}
