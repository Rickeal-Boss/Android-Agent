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
        )
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
