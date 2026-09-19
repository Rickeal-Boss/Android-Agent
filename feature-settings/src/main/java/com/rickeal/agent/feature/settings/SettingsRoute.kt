package com.rickeal.agent.feature.settings

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.rickeal.agent.core.data.AppContainer
import com.rickeal.agent.core.data.LocalAppContainer
import com.rickeal.agent.core.data.viewModelFactory
import com.rickeal.agent.feature.settings.tools.ToolsRoute
import com.rickeal.agent.feature.settings.tools.ToolsScreen
import com.rickeal.agent.feature.settings.tools.ToolsViewModel

object SettingsRoute {
    const val ROUTE = "settings"

    fun build(): String = ROUTE
}

object EndpointsRoute {
    const val ROUTE = "settings/endpoints"

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
            onOpenEndpoints = { navController.navigate(EndpointsRoute.build()) },
            onOpenTools = { navController.navigate(ToolsRoute.build()) },
            onOpenDiagnostics = { navController.navigate(DiagnosticsRoute.build()) },
            onOpenLegal = { navController.navigate(LegalRoute.build()) },
        )
    }

    composable(route = LegalRoute.ROUTE) {
        LegalScreen(onBack = { navController.popBackStack() })
    }

    composable(route = DiagnosticsRoute.ROUTE) {
        DiagnosticsScreen(onBack = { navController.popBackStack() })
    }

    composable(route = EndpointsRoute.ROUTE) {
        val container = LocalAppContainer.current
        EndpointsScreen(
            viewModel = viewModel(factory = settingsViewModelFactory(container)),
            onBack = { navController.popBackStack() },
        )
    }

    composable(route = ToolsRoute.ROUTE) {
        val container = LocalAppContainer.current
        ToolsScreen(
            viewModel = viewModel(
                factory = viewModelFactory { ToolsViewModel(container) },
            ),
            onBack = { navController.popBackStack() },
        )
    }
}
