package com.rickeal.agent.feature.models

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.rickeal.agent.core.data.AppContainer
import com.rickeal.agent.core.data.LocalAppContainer
import com.rickeal.agent.core.data.viewModelFactory

object ModelsRoute {
    const val ROUTE = "models"

    fun build(): String = ROUTE
}

fun modelsViewModelFactory(container: AppContainer): ViewModelProvider.Factory =
    viewModelFactory { ModelsViewModel(container) }

fun NavGraphBuilder.modelsGraph(navController: NavController) {
    composable(route = ModelsRoute.ROUTE) {
        val container = LocalAppContainer.current
        ModelsScreen(viewModel = viewModel(factory = modelsViewModelFactory(container)))
    }
}
